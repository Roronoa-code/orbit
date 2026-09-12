// Galaxy-only timing and real touch checks. Trace setup/export stays outside the sample.
const {chromium}=require('playwright'),{execFileSync}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const serial=process.env.ORBIT_AUDIT_SERIAL;if(!serial)throw Error('Set authorized Galaxy serial');
const adb=path.join(process.env.LOCALAPPDATA,'Android','Sdk','platform-tools','adb.exe');
const cmd=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true}).trim();
const label=process.argv[2]||'run',out=path.join(__dirname,'player-settings-20260912','home-motion');fs.mkdirSync(out,{recursive:true});
(async()=>{
 cmd('forward','tcp:9224','localabstract:webview_devtools_remote_'+cmd('shell','pidof','com.mani.orbit.audit'));
 const b=await chromium.connectOverCDP('http://127.0.0.1:9224'),p=b.contexts()[0].pages()[0],cdp=await p.context().newCDPSession(p),errors=[];
 p.on('pageerror',e=>errors.push(e.message));let tracing=false;
 try{
  await p.waitForFunction(()=>typeof Health!=='undefined');await p.evaluate(()=>{Health.close();setLiveOpen(false);setDeckExpanded(false)});await p.waitForTimeout(1200);
  await b.startTracing(p,{screenshots:false,categories:['devtools.timeline','blink','cc']});tracing=true;cmd('shell','dumpsys','gfxinfo','com.mani.orbit.audit','reset');
  const report={};
  for(const kind of ['deck','live','active-live']){
   if(kind==='active-live'){await p.evaluate(()=>{if(!Health.state.active)Health.action('start','Strength')});await p.waitForTimeout(600)}
   await p.evaluate(name=>{console.timeStamp(name);window.motionGaps=[];let last;function tick(t){if(last!==undefined)motionGaps.push(t-last);last=t;window.motionSample=requestAnimationFrame(tick)}window.motionSample=requestAnimationFrame(tick)},kind);
   for(let cycle=0;cycle<3;cycle++)for(const open of [true,false]){
    const at=await p.evaluate(({kind,open})=>{const r=document.querySelector(kind==='deck'?(open?'#stack-open':'.facts'):'#live-bar').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(kind==='deck'?r.top+55:r.top+30)}},{kind,open});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[at]});
    for(let n=1;n<=12;n++){await p.waitForTimeout(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:at.x,y:at.y+(open?-1:1)*n*12}]})}
    await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await p.waitForTimeout(1000);
    const state=await p.evaluate(()=>({deck:deckExpanded,live:islands.live.open,motion:motionFrame,island:islandFrame,gesture:!!deckGesture,pose:islands.live.value,height:islands.live.height}));
    const detail=JSON.stringify({kind,open,cycle,state});assert.equal(kind==='deck'?state.deck:state.live,open,detail);assert.equal(state.gesture,false,detail);assert.equal(state.motion,0,detail);assert.equal(state.island,0,detail);
   }
   report[kind]=await p.evaluate(()=>{cancelAnimationFrame(motionSample);const f=motionGaps.slice().sort((a,b)=>a-b);return {frames:f.length,median:f[Math.floor(f.length*.5)],p95:f[Math.floor(f.length*.95)],max:f.at(-1),over25:f.filter(x=>x>25.1).length}});
   console.log(kind,report[kind]);
  }
  const gfx=cmd('shell','dumpsys','gfxinfo','com.mani.orbit.audit','framestats'),trace=(await b.stopTracing()).toString();tracing=false;
  report.jank=gfx.match(/Janky frames: ([^\n]+)/)?.[1];report.errors=errors;assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(out,label+'.json'),JSON.stringify(report,null,2));fs.writeFileSync(path.join(out,label+'-trace.json'),trace);fs.writeFileSync(path.join(out,label+'-gfxinfo.txt'),gfx);
  console.log(label,report.jank);
 }finally{if(tracing)await b.stopTracing();await p.evaluate(()=>{cancelAnimationFrame(window.motionSample);if(Health.state.active)Health.action('finish');Health.close();setLiveOpen(false);setDeckExpanded(false)});await b.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
