// Deck opening by tap and by a short flick, first time after load versus a repeat, each in a fresh page.
// Reports deck frame intervals (in-page), heavy main-thread frames, raster time and image repaints.
// Usage: node measure11.cjs <label> [cpuThrottle=4] [url]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'run',throttle=Number(process.argv[3]||4);
const URL=process.argv[4]||'http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-open';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const WINDOW_US=700000;
(async()=>{
 const browser=await chromium.launch({headless:true});
 const report={label,throttle,url:URL,runs:{}};
 for(const kind of ['tap','flick']){
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
  const page=await context.newPage(),cdp=await context.newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
  page.on('pageerror',e=>console.log('pageerror',e.message));
  await page.goto(URL);await page.waitForSelector('#live-bar');await sleep(3000);
  await page.evaluate(()=>{const log=window.__deck=[];const layout=window.layoutDeck;window.layoutDeck=function(){const t=performance.now();layout.apply(this,arguments);log.push({t,p:deckProgress})}});
  const start=await page.evaluate(()=>{const r=document.querySelector('#stack-open').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+40)}});
  await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','devtools.timeline','blink','cc','v8']});
  for(const round of [1,2]){
   const name=`${kind}-${round===1?'first':'repeat'}`;
   const t0=await page.evaluate(n=>{__deck.length=0;console.timeStamp(n);return performance.now()},name);
   if(kind==='tap'){await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[start]});await sleep(40);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})}
   else{let y=start.y;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[start]});for(let i=0;i<5;i++){await sleep(16);y-=18;await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]})}await sleep(8);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})}
   await sleep(1500);
   const deck=await page.evaluate(()=>__deck.slice());
   const moving=deck.filter(d=>d.p>0&&d.p<1),gaps=moving.slice(1).map((d,i)=>+(d.t-moving[i].t).toFixed(1));
   report.runs[name]={firstFrameAfterInputMs:moving.length?+(moving[0].t-t0).toFixed(1):null,frames:moving.length,maxGapMs:gaps.length?Math.max(...gaps):null,gapsOver20Ms:gaps.filter(g=>g>20).length,firstGaps:gaps.slice(0,10)};
   await page.evaluate(()=>setDeckExpanded(false));await sleep(1700);
  }
  const events=JSON.parse((await browser.stopTracing()).toString('utf8')).traceEvents||[];
  const main=events.find(e=>e.ph==='M'&&e.name==='thread_name'&&e.args?.name==='CrRendererMain');
  for(const mark of events.filter(e=>e.name==='TimeStamp'&&/^(tap|flick)-/.test(e.args?.data?.message||''))){
   const inWindow=e=>e.ts>=mark.ts&&e.ts<mark.ts+WINDOW_US;
   const heavy=events.filter(e=>inWindow(e)&&e.ph==='X'&&e.pid===main.pid&&e.tid===main.tid&&e.name==='WebFrameWidgetImpl::UpdateLifecycle'&&e.dur>2500).map(e=>({atMs:+((e.ts-mark.ts)/1000).toFixed(0),ms:+(e.dur/1000).toFixed(1)}));
   const raster=events.filter(e=>inWindow(e)&&e.ph==='X'&&/RasterTask|ImageDecodeTask/.test(e.name)).reduce((s,e)=>s+e.dur/1000,0);
   const images={};for(const e of events)if(inWindow(e)&&e.name==='PaintImage'){const k=`${e.args?.data?.srcWidth}x${e.args?.data?.srcHeight}`;(images[k]??=[]).push(Math.round((e.ts-mark.ts)/1000))}
   Object.assign(report.runs[mark.args.data.message],{heavyLifecycleFrames:heavy,rasterMs:+raster.toFixed(1),paintImages:images});
  }
  await context.close();
 }
 for(const [name,run] of Object.entries(report.runs))console.log(name.padEnd(13),JSON.stringify(run));
 fs.writeFileSync(`measure11-${label}.json`,JSON.stringify(report,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
