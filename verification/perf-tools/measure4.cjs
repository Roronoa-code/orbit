// Direct main-thread cost of the Home render steps (ms per call, CPU-throttled).
const {chromium}=require('playwright');
const throttle=Number(process.argv[2]||4);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 const cdp=await context.newCDPSession(page);
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit');
 await page.waitForSelector('#live-bar');await sleep(1500);
 const timings=await page.evaluate(async()=>{
  const time=(label,fn,n=6)=>{const t=[];for(let i=0;i<n;i++){const a=performance.now();fn();t.push(performance.now()-a)}t.sort((x,y)=>x-y);return [label,+t[Math.floor(n/2)].toFixed(1)]};
  const out=[];
  out.push(time('renderMetric()',()=>renderMetric()));
  out.push(time('measureDeck()',()=>measureDeck()));
  out.push(time('renderCharts()',()=>renderCharts()));
  out.push(time('render() [steps]',()=>render()));
  out.push(time('cyclePeriod() incl. orb turn',()=>cyclePeriod()));
  out.push(time('changeMetric(1)',()=>changeMetric(1)));
  out.push(time('updateLiveBar()',()=>updateLiveBar()));
  out.push(time('layoutDeck()',()=>layoutDeck()));
  out.push(time('layoutIsland(live)',()=>layoutIsland(islands.live)));
  return out;
 });
 for(const [l,v] of timings)console.log(l.padEnd(30),v,'ms');
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
