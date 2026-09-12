// Matched phone trace: same generated cover, no recording or screenshots during measurement.
const {chromium}=require('playwright'),{execFileSync}=require('node:child_process'),fs=require('node:fs'),path=require('node:path');
const {seedMedia}=require('./video-audit-scenes.cjs'),serial=process.env.ORBIT_AUDIT_SERIAL;if(!serial)throw Error('Set authorized Galaxy serial');
const adb=path.join(process.env.LOCALAPPDATA,'Android','Sdk','platform-tools','adb.exe'),cmd=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true}).trim();
const phase=process.argv[2],out=path.join(__dirname,'video-audit-20260912','phone','performance');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const pid=cmd('shell','pidof com.mani.orbit.audit');cmd('forward','tcp:9224','localabstract:webview_devtools_remote_'+pid);
 const b=await chromium.connectOverCDP('http://127.0.0.1:9224'),p=b.contexts()[0].pages()[0];
 try{
  await p.waitForFunction(()=>typeof Health!=='undefined');await seedMedia(p);await p.evaluate(()=>{if(Health.state.active)throw Error('An audit session is already active');Health.action('start','Strength',1800000);Health.open('workouts')});await p.waitForFunction(()=>document.querySelector('.music-cover img')?.complete);await p.waitForTimeout(1200);
  await b.startTracing(p,{screenshots:false,categories:['devtools.timeline','blink','cc']});
  cmd('shell','dumpsys gfxinfo com.mani.orbit.audit reset');
  await p.evaluate(()=>{window.auditFrames=[];let last=performance.now();function tick(t){auditFrames.push(t-last);last=t;auditFrame=requestAnimationFrame(tick)}window.auditFrame=requestAnimationFrame(tick)});
  for(let i=0;i<8;i++){await p.evaluate(()=>document.querySelector('[data-music-expand]').click());await p.waitForTimeout(850);await p.evaluate(()=>document.querySelector('[data-timer-focus]').click());await p.waitForTimeout(850)}
  // Trace startup/export can stall WebView's debugger. Keep that overhead outside the measured interaction.
  const result=await p.evaluate(()=>{cancelAnimationFrame(auditFrame);const f=auditFrames.slice(1).sort((a,b)=>a-b);return {frames:f.length,median:f[Math.floor(f.length*.5)],p95:f[Math.floor(f.length*.95)],max:f.at(-1),over25:f.filter(x=>x>25).length}});
  const gfx=cmd('shell','dumpsys gfxinfo com.mani.orbit.audit framestats');
  const trace=JSON.parse((await b.stopTracing()).toString());
  result.rasterMs=trace.traceEvents.filter(e=>e.ph==='X'&&/RasterTask|ImageDecodeTask/.test(e.name)).reduce((s,e)=>s+(e.dur||0)/1000,0);
  result.largeImagePaints=trace.traceEvents.filter(e=>e.name==='PaintImage'&&e.args?.data?.srcWidth===600).length;
  result.jank=gfx.match(/Janky frames: ([^\n]+)/)?.[1];
  fs.writeFileSync(path.join(out,phase+'.json'),JSON.stringify(result,null,2));fs.writeFileSync(path.join(out,phase+'-trace.json'),JSON.stringify(trace));fs.writeFileSync(path.join(out,phase+'-gfxinfo.txt'),gfx);console.log(phase,JSON.stringify(result));
 }finally{await p.evaluate(()=>{Health.action('finish');Health.close()});await b.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
