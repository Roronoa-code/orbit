// First deck drag versus a repeat drag, driven by real touch events at 60 Hz in a CPU-throttled
// headless Chromium at phone geometry. Reports presented-frame gaps and the work inside the first
// 250 ms of each drag. Usage: node measure8.cjs <label> [cpuThrottle=4]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'run',throttle=Number(process.argv[3]||4);
const URL='http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-first-drag';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const WINDOW_US=250000;
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 const cdp=await context.newCDPSession(page);
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
 page.on('pageerror',e=>console.log('pageerror',e.message));
 await page.goto(URL);await page.waitForSelector('#live-bar');await sleep(3000);
 const start=await page.evaluate(()=>{const r=document.querySelector('#stack-open').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+40)}});
 async function drag(name){
  await page.evaluate(n=>console.timeStamp(n),name);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:start.x,y:start.y}]});
  let y=start.y;
  for(let i=0;i<22;i++){await sleep(16);y-=11;await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]})}
  await sleep(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await sleep(1400);
 }
 async function collapse(){await page.evaluate(()=>setDeckExpanded(false));await sleep(1600)}
 await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','devtools.timeline','cc','viz','gpu','blink','v8','disabled-by-default-devtools.timeline.frame']});
 await drag('drag-1-first');await collapse();
 await drag('drag-2-repeat');await collapse();
 await page.evaluate(()=>cyclePeriod());await sleep(60);
 await drag('drag-3-after-period-change');await collapse();
 const trace=JSON.parse((await browser.stopTracing()).toString('utf8'));
 const events=trace.traceEvents||[];
 const marks=events.filter(e=>e.name==='TimeStamp'&&/^drag-/.test(e.args?.data?.message||'')).map(e=>({name:e.args.data.message,ts:e.ts}));
 const report={label,throttle,drags:{}};
 for(const mark of marks){
  const inWindow=e=>e.ts>=mark.ts&&e.ts<mark.ts+WINDOW_US;
  const draws=events.filter(e=>e.name==='Display::DrawAndSwap'&&e.ph==='X'&&inWindow(e)).map(e=>e.ts).sort((a,b)=>a-b);
  const gaps=draws.slice(1).map((t,i)=>+((t-draws[i])/1000).toFixed(1));
  const firstDraw=draws.length?+((draws[0]-mark.ts)/1000).toFixed(1):null;
  const byName={};
  for(const e of events){if(e.ph!=='X'||!e.dur||!inWindow(e))continue;byName[e.name]=(byName[e.name]||0)+e.dur/1000}
  const top=Object.entries(byName).filter(([n])=>!/^(ThreadControllerImpl::RunTask|RunTask|ThreadPool_RunTask|TaskGraphRunner::RunTask|Scheduler::|SequenceManager|MessageLoop|ProxyMain::BeginMainFrame|WidgetBase::|TimerBase::Run|BlinkScheduler)/.test(n)).sort((a,b)=>b[1]-a[1]).slice(0,18).map(([n,ms])=>[n,+ms.toFixed(1)]);
  const raster=Object.entries(byName).filter(([n])=>/RasterTask|RasterizeTile|GpuRaster|ImageDecode/.test(n)).reduce((s,[,ms])=>s+ms,0);
  report.drags[mark.name]={presentedFrames:draws.length,firstDrawMs:firstDraw,maxGapMs:gaps.length?Math.max(...gaps):null,gapsOver20Ms:gaps.filter(g=>g>20).length,gaps,rasterMs:+raster.toFixed(1),top};
  console.log(mark.name,JSON.stringify({presentedFrames:draws.length,firstDrawMs:firstDraw,maxGapMs:report.drags[mark.name].maxGapMs,gapsOver20Ms:report.drags[mark.name].gapsOver20Ms,rasterMs:report.drags[mark.name].rasterMs}));
  console.log('  top',JSON.stringify(top.slice(0,12)));
 }
 fs.writeFileSync(`measure8-${label}.json`,JSON.stringify(report,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
