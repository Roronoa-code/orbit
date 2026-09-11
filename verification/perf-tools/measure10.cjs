// Main-thread timeline from the first touch to the first frames of a deck drag (first drag after load,
// repeat drag, drag right after a period change). Lists renderer main-thread slices of 1 ms or more.
// Usage: node measure10.cjs [cpuThrottle=4] [windowMs=140]
const {chromium}=require('playwright');
const throttle=Number(process.argv[2]||4),windowUs=Number(process.argv[3]||140)*1000;
const URL='http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-drag-timeline';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 const cdp=await context.newCDPSession(page);
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
 await page.goto(URL);await page.waitForSelector('#live-bar');await sleep(3000);
 const start=await page.evaluate(()=>{const r=document.querySelector('#stack-open').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+40)}});
 async function drag(name){
  await page.evaluate(n=>console.timeStamp(n),name);
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:start.x,y:start.y}]});
  let y=start.y;
  for(let i=0;i<22;i++){await sleep(16);y-=11;await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]})}
  await sleep(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await sleep(1400);await page.evaluate(()=>setDeckExpanded(false));await sleep(1600);
 }
 await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','devtools.timeline','blink','v8','cc','disabled-by-default-v8.compile']});
 await drag('drag-1-first');await drag('drag-2-repeat');
 await page.evaluate(()=>cyclePeriod());await sleep(60);await drag('drag-3-after-period-change');
 const events=JSON.parse((await browser.stopTracing()).toString('utf8')).traceEvents||[];
 const main=events.find(e=>e.ph==='M'&&e.name==='thread_name'&&e.args?.name==='CrRendererMain');
 const onMain=e=>main&&e.pid===main.pid&&e.tid===main.tid;
 for(const mark of events.filter(e=>e.name==='TimeStamp'&&/^drag-/.test(e.args?.data?.message||''))){
  const firstMove=events.find(e=>onMain(e)&&e.name==='EventDispatch'&&e.args?.data?.type==='touchmove'&&e.ts>mark.ts);
  if(!firstMove){console.log(mark.args.data.message,'no touchmove');continue}
  console.log(`\n${mark.args.data.message}: first touchmove handled at +${((firstMove.ts-mark.ts)/1000).toFixed(1)} ms for ${(firstMove.dur/1000).toFixed(2)} ms`);
  const slices=events.filter(e=>onMain(e)&&e.ph==='X'&&e.dur>=1000&&e.ts>=firstMove.ts&&e.ts<firstMove.ts+windowUs&&!/^(ThreadControllerImpl::RunTask|RunTask|SequenceManager|Scheduler|TimerBase|ProxyMain::BeginMainFrame::commit)/.test(e.name)).sort((a,b)=>a.ts-b.ts);
  for(const e of slices){const d=e.args?.data||{};const extra=d.type||d.functionName||d.url?.split('/').pop()||'';console.log(`  +${((e.ts-firstMove.ts)/1000).toFixed(1).padStart(6)}  ${(e.dur/1000).toFixed(2).padStart(6)} ms  ${e.name}${extra?' ('+extra+')':''}`)}
 }
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
