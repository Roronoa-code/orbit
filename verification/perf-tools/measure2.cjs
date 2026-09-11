// Relative cost of Orbit scenarios (main thread + compositor/raster/viz trace sums) in a CPU-throttled
// headless Chromium at phone geometry. Usage: node measure2.cjs <label> [cpuThrottle=4] [variants=all]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'run',throttle=Number(process.argv[3]||4),only=(process.argv[4]||'all').split(',');
const URL='http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const VARIANTS={
 baseline:null,
 'no-facts-frost':'.stack-card.facts{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}',
 'no-frost-at-all':'.island-frost,.stack-card.facts{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}',
};
const GROUPS={
 main:/^(Layout|UpdateLayoutTree|Paint|PrePaint|FunctionCall|EventDispatch|TimerFire|Animation|HitTest|UpdateLayer|CompositeLayers|ParseHTML|ScheduleStyleRecalculation)$/,
 raster:/RasterTask|ImageDecodeTask|GpuRasterTask|RasterizerTask|RasterizeTile/,
 compositor:/ProxyImpl::ScheduledActionDraw|LayerTreeHostImpl::DrawLayers|LayerTreeHostImpl::PrepareToDraw|ProxyImpl::NotifyReadyToCommit|LayerTreeHost::DoUpdateLayers/,
 viz:/Display::DrawAndSwap|DirectRenderer::DrawFrame|SkiaRenderer::DrawFrame|DrawRenderPass|FinishPaintRenderPass|SwapBuffers|Display::Draw/,
};
function summarise(traceJson){
 const out={};for(const k of Object.keys(GROUPS))out[k+'Ms']=0;out.drawAndSwap=0;
 for(const e of traceJson.traceEvents||[]){
  if(e.ph!=='X'||!e.dur)continue;
  for(const [k,re] of Object.entries(GROUPS))if(re.test(e.name)){out[k+'Ms']+=e.dur/1000;break}
  if(e.name==='Display::DrawAndSwap')out.drawAndSwap++;
 }
 for(const k of Object.keys(out))if(k.endsWith('Ms'))out[k]=+out[k].toFixed(1);
 return out;
}
(async()=>{
 const browser=await chromium.launch({headless:true});
 const all={};
 for(const [variant,css] of Object.entries(VARIANTS)){
  if(!only.includes('all')&&!only.includes(variant))continue;
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
  const page=await context.newPage();
  const cdp=await context.newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
  page.on('pageerror',e=>console.log('pageerror',e.message));
  await page.goto(URL);
  await page.waitForSelector('#live-bar');
  if(css)await page.addStyleTag({content:css});
  await page.evaluate(()=>{window.__frames=[];let last=performance.now();function f(t){window.__frames.push(t-last);last=t;requestAnimationFrame(f)}requestAnimationFrame(f)});
  await sleep(1800);
  const metrics=async()=>{const {metrics}=await cdp.send('Performance.getMetrics');const m={};for(const {name,value} of metrics)m[name]=value;return m};
  const results={};
  async function scenario(name,durationMs,action){
   await page.evaluate(()=>{window.__frames.length=0});
   await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','cc','viz','gpu','blink','v8']});
   const before=await metrics();const t0=Date.now();
   await action();
   const remaining=durationMs-(Date.now()-t0);if(remaining>0)await sleep(remaining);
   const after=await metrics();const frames=await page.evaluate(()=>window.__frames.slice());
   const trace=JSON.parse((await browser.stopTracing()).toString('utf8'));
   const wall=(after.Timestamp-before.Timestamp)*1000;
   const d=k=>+(((after[k]||0)-(before[k]||0))*1000).toFixed(1);
   results[name]={wallMs:+wall.toFixed(0),busyPct:+((after.TaskDuration-before.TaskDuration)/(after.Timestamp-before.Timestamp)*100).toFixed(1),scriptMs:d('ScriptDuration'),layoutMs:d('LayoutDuration'),styleMs:d('RecalcStyleDuration'),layouts:after.LayoutCount-before.LayoutCount,recalcs:after.RecalcStyleCount-before.RecalcStyleCount,frames:frames.length,over20ms:frames.filter(f=>f>20).length,over50ms:frames.filter(f=>f>50).length,maxFrameMs:+Math.max(0,...frames).toFixed(1),...summarise(trace)};
   console.log(variant,name,JSON.stringify(results[name]));
  }
  await scenario('idle-home',4000,async()=>{});
  await scenario('orb-period-taps-x3',3000,async()=>{for(let i=0;i<3;i++){await page.click('#orb-button');await sleep(700)}});
  await scenario('deck-open-close-x2',4000,async()=>{for(let i=0;i<2;i++){await page.click('#stack-open');await sleep(900);await page.evaluate(()=>document.querySelector('#live-bar').click());await sleep(900)}});
  await page.click('#stack-open');await sleep(1000);
  await scenario('deck-scroll-expanded',3000,async()=>{await page.evaluate(async()=>{const s=document.querySelector('#deck-scroll');for(let i=0;i<60;i++){s.scrollTop=i*12;await new Promise(r=>requestAnimationFrame(r))}for(let i=60;i>=0;i--){s.scrollTop=i*12;await new Promise(r=>requestAnimationFrame(r))}})});
  await page.evaluate(()=>document.querySelector('#live-bar').click());await sleep(900);
  await scenario('live-bar-open-close-x2',3000,async()=>{for(let i=0;i<2;i++){await page.click('#live-bar');await sleep(700);await page.click('#live-bar');await sleep(700)}});
  await scenario('open-body-page-and-back',3000,async()=>{await page.click('#live-bar');await sleep(600);await page.click('[data-activity="body"]');await sleep(1200);await page.evaluate(()=>document.querySelector('#health-back')?.click());await sleep(700)});
  all[variant]=results;
  await context.close();
 }
 fs.writeFileSync(`measure2-${label}.json`,JSON.stringify(all,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
