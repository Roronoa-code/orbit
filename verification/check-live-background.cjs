// Run with the installed Playwright and sharp packages on NODE_PATH.
const {chromium}=require('playwright'),sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  for(const width of [390,320]){
   const context=await browser.newContext({viewport:{width,height:844}}),page=await context.newPage();
   try{
    await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?live-background-check');
    await page.evaluate(()=>{window.__background={Health,setLiveOpen,orb,updateLiveReadout,updateLiveBar,measureIsland,islands};Health.action('start','Strength')});
    await page.waitForTimeout(900);
    const heights=await page.evaluate(async()=>{
     const api=window.__background,frost=document.querySelector('#live-island .island-frost'),values=[];
     api.setLiveOpen(true);
     for(let i=0;i<45;i++){await new Promise(requestAnimationFrame);values.push(frost.getBoundingClientRect().height)}
     return values;
    });
    await page.waitForTimeout(500);
    const stableTargets=await page.evaluate(async()=>{
     const root=document.querySelector('#live-island'),records=[];
     const observer=new MutationObserver(items=>records.push(...items.map(m=>({id:m.target.id,attribute:m.attributeName,type:m.type}))));
     observer.observe(root,{subtree:true,childList:true,attributes:true,characterData:true});
     const refresh=setInterval(()=>{const api=window.__background;api.updateLiveBar();api.measureIsland(api.islands.live)},250);
     await new Promise(resolve=>setTimeout(resolve,3200));clearInterval(refresh);observer.disconnect();return records;
    });
    // The unoccluded background must stay visually stable across reversals.
    await page.evaluate(()=>window.__background.orb.setPaused(true));
    const clip={x:0,y:90,width,height:380},first=await sharp(await page.screenshot({clip})).raw().toBuffer();
    let maximumPixelChange=0;
    for(const open of [false,true,false,true]){
     await page.evaluate(open=>window.__background.setLiveOpen(open),open);
     for(let i=0;i<10;i++){
      const current=await sharp(await page.screenshot({clip})).raw().toBuffer();
      for(let j=0;j<first.length;j++)maximumPixelChange=Math.max(maximumPixelChange,Math.abs(first[j]-current[j]));
     }
    }
    const report={width,frostHeightMin:Math.min(...heights),frostHeightMax:Math.max(...heights),stableTargets,maximumPixelChange};
    const out=path.join(__dirname,'seven-ux-browser',`live-background-${width}${process.env.ORBIT_BASELINE?'-before':''}.json`);
    fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
    if(!process.env.ORBIT_BASELINE){
     assert(report.frostHeightMax-report.frostHeightMin<.1,'Backdrop texture must keep its full size while the shell opens');
     assert(stableTargets.some(item=>item.id==='live-timer'),'The live time must continue updating');
     assert(stableTargets.every(item=>item.id==='live-timer'),'A timer tick must not repaint unchanged launcher content');
     assert.equal(maximumPixelChange,0,'Background above the launcher must not flash during reversal');
    }
    console.log(JSON.stringify({width,frostRange:report.frostHeightMax-report.frostHeightMin,mutationTargets:[...new Set(stableTargets.map(item=>item.id+':'+item.attribute))],maximumPixelChange}));
   }finally{await context.close()}
  }
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
