// Why does anything repaint during the deck fold? Aggregate Blink paint-invalidation tracking by node and reason.
const {chromium}=require('playwright');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const scenario=process.argv[2]||'deck';
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit');
 await page.waitForSelector('#live-bar');if(process.argv[3])await page.addStyleTag({content:process.argv[3]});await sleep(1800);
 await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-blink.invalidation','disabled-by-default-devtools.timeline','disabled-by-default-devtools.timeline.invalidationTracking','blink','cc']});
 if(scenario==='deck'){await page.click('#stack-open');await sleep(900);await page.evaluate(()=>document.querySelector('#live-bar').click());await sleep(900)}
 else if(scenario==='live'){await page.click('#live-bar');await sleep(700);await page.click('#live-bar');await sleep(700)}
 else await sleep(2000);
 const trace=JSON.parse((await browser.stopTracing()).toString('utf8'));
 const inval=new Map(),paints=new Map(),names=new Map();let rasterTasks=0,paintCount=0;
 for(const e of trace.traceEvents){
  if(e.name==='PaintInvalidationTracking'||e.name==='LayoutInvalidationTracking'||e.name==='StyleInvalidatorInvalidationTracking'||e.name==='ScheduleStyleInvalidationTracking'){
   const d=e.args?.data||{};const key=e.name+' | '+(d.nodeName||'?')+(d.selectorPart?' '+d.selectorPart:'')+' | '+(d.reason||d.invalidationReason||d.invalidatedSelectorId||'?');
   inval.set(key,(inval.get(key)||0)+1);
  }
  if(e.name==='Paint'&&e.ph==='X'){paintCount++;const d=e.args?.data||{};const key=(d.nodeName||'?')+' '+JSON.stringify(d.clip||[]).slice(0,40);paints.set(key,(paints.get(key)||0)+1)}
  if(/RasterTask|RasterizeTile/.test(e.name)&&e.ph==='X')rasterTasks++;
  names.set(e.name,(names.get(e.name)||0)+1);
 }
 console.log('raster tasks',rasterTasks,'paint events',paintCount);
 console.log('--- invalidation tracking (top 25) ---');
 for(const [k,v] of [...inval].sort((a,b)=>b[1]-a[1]).slice(0,25))console.log(String(v).padStart(6),k.slice(0,150));
 console.log('--- Paint events by node/clip (top 12) ---');
 for(const [k,v] of [...paints].sort((a,b)=>b[1]-a[1]).slice(0,12))console.log(String(v).padStart(6),k.slice(0,120));
 console.log('--- other frequent event names (top 20) ---');
 for(const [k,v] of [...names].sort((a,b)=>b[1]-a[1]).slice(0,20))console.log(String(v).padStart(6),k);
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
