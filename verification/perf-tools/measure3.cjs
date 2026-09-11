// Attribute per-frame cost during the deck fold and live-bar growth by switching single features off.
// Usage: node measure3.cjs <label> [cpuThrottle=4] [variants=all] [scenarios=all]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'attr',throttle=Number(process.argv[3]||4),onlyV=(process.argv[4]||'all').split(','),onlyS=(process.argv[5]||'all').split(',');
const URL='http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const VARIANTS={
 baseline:null,
 'no-card-clip':'.stack-card{clip-path:none!important}',
 'no-card-shell':'.stack-card:before{display:none!important}',
 'static-card-children':'.stack-card>*{opacity:1!important;transform:none!important}',
 'no-facts-frost':'.stack-card.facts{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}',
 'no-live-frost':'.live-island .island-frost{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}',
 'short-live-frost':'.live-island .island-frost{height:62px!important}',
 'fixed-live-body':'.live-island-body{bottom:auto!important;height:calc(var(--live-frost-height,62px) - 62px)!important}',
 'no-utility-frost':'.utility-island .island-frost{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}',
};
const GROUPS={
 main:/^(Layout|UpdateLayoutTree|Paint|PrePaint|FunctionCall|EventDispatch|TimerFire|Animation|HitTest|UpdateLayer|CompositeLayers|ParseHTML|ScheduleStyleRecalculation)$/,
 raster:/RasterTask|ImageDecodeTask|GpuRasterTask|RasterizerTask|RasterizeTile/,
 compositor:/ProxyImpl::ScheduledActionDraw|LayerTreeHostImpl::DrawLayers|LayerTreeHostImpl::PrepareToDraw|ProxyImpl::NotifyReadyToCommit|LayerTreeHost::DoUpdateLayers/,
 viz:/Display::DrawAndSwap|DirectRenderer::DrawFrame|SkiaRenderer::DrawFrame|DrawRenderPass|FinishPaintRenderPass|SwapBuffers|Display::Draw/,
};
function summarise(traceJson){
 const out={};for(const k of Object.keys(GROUPS))out[k+'Ms']=0;out.drawAndSwap=0;out.rasterTasks=0;
 for(const e of traceJson.traceEvents||[]){
  if(e.ph!=='X'||!e.dur)continue;
  for(const [k,re] of Object.entries(GROUPS))if(re.test(e.name)){out[k+'Ms']+=e.dur/1000;if(k==='raster')out.rasterTasks++;break}
  if(e.name==='Display::DrawAndSwap')out.drawAndSwap++;
 }
 for(const k of Object.keys(out))if(k.endsWith('Ms'))out[k]=+out[k].toFixed(1);
 return out;
}
(async()=>{
 const browser=await chromium.launch({headless:true});
 const all={};
 for(const [variant,css] of Object.entries(VARIANTS)){
  if(!onlyV.includes('all')&&!onlyV.includes(variant))continue;
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
  const page=await context.newPage();
  const cdp=await context.newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
  await page.goto(URL);await page.waitForSelector('#live-bar');
  if(css)await page.addStyleTag({content:css});
  await sleep(1800);
  const metrics=async()=>{const {metrics}=await cdp.send('Performance.getMetrics');const m={};for(const {name,value} of metrics)m[name]=value;return m};
  const results={};
  async function scenario(name,durationMs,action){
   if(!onlyS.includes('all')&&!onlyS.includes(name))return;
   await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','cc','viz','gpu','blink','v8']});
   const before=await metrics();const t0=Date.now();
   await action();
   const remaining=durationMs-(Date.now()-t0);if(remaining>0)await sleep(remaining);
   const after=await metrics();
   const trace=JSON.parse((await browser.stopTracing()).toString('utf8'));
   const d=k=>+(((after[k]||0)-(before[k]||0))*1000).toFixed(1);
   results[name]={busyPct:+((after.TaskDuration-before.TaskDuration)/(after.Timestamp-before.Timestamp)*100).toFixed(1),styleMs:d('RecalcStyleDuration'),layoutMs:d('LayoutDuration'),layouts:after.LayoutCount-before.LayoutCount,...summarise(trace)};
   console.log(variant.padEnd(22),name.padEnd(24),JSON.stringify(results[name]));
  }
  await scenario('deck-open-close-x2',4000,async()=>{for(let i=0;i<2;i++){await page.click('#stack-open');await sleep(900);await page.evaluate(()=>document.querySelector('#live-bar').click());await sleep(900)}});
  await scenario('live-bar-open-close-x2',3000,async()=>{for(let i=0;i<2;i++){await page.click('#live-bar');await sleep(700);await page.click('#live-bar');await sleep(700)}});
  all[variant]=results;
  await context.close();
 }
 fs.writeFileSync(`measure3-${label}.json`,JSON.stringify(all,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
