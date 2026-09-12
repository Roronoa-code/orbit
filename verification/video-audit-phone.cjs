// Runs only against the separately installed, debuggable Orbit Audit package.
const {chromium}=require('playwright');
const {execFileSync,spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const phase=process.argv[2]||'after',baseline=phase==='before',serial=process.env.ORBIT_AUDIT_SERIAL;
if(!serial)throw Error('Set ORBIT_AUDIT_SERIAL to the authorized Galaxy device');
const adb=path.join(process.env.LOCALAPPDATA,'Android','Sdk','platform-tools','adb.exe');
const command=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true}).trim();
const shell=s=>command('shell',s),out=path.join(__dirname,'video-audit-20260912','phone',phase);fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms)),focusOnly=process.argv.includes('--focus-only');
(async()=>{
 const pid=shell('pidof com.mani.orbit.audit');assert(/^\d+$/.test(pid));command('forward','tcp:9224',`localabstract:webview_devtools_remote_${pid}`);
 const browser=await chromium.connectOverCDP('http://127.0.0.1:9224'),context=browser.contexts()[0],page=context.pages()[0],cdp=await context.newCDPSession(page);
 const results=[],errors=[];page.on('pageerror',e=>errors.push(e.message));
 assert.equal(await page.url(),'https://orbit.invalid/index.html');
 const ready=()=>page.waitForFunction(()=>!motionFrame&&!islandFrame);
 const tap=async selector=>{const p=await page.locator(selector).boundingBox();assert(p,selector);await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:p.x+p.width/2,y:p.y+p.height/2}]});await sleep(50);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});};
 const shot=async name=>{shell(`screencap -p /sdcard/orbit-audit-${phase}.png`);command('pull',`/sdcard/orbit-audit-${phase}.png`,path.join(out,name+'.png'));};
 const run=async(name,fn)=>{try{const detail=await fn();results.push({name,status:'PASS',detail});console.log('PASS '+name)}catch(e){results.push({name,status:'FAIL',error:e.message});console.log('FAIL '+name+': '+e.message)}};
 const remoteVideo=`/sdcard/orbit-audit-${phase}.mp4`;
 const recorder=spawn(adb,['-s',serial,'shell',`screenrecord --size 720x1560 --bit-rate 5000000 --time-limit 300 ${remoteVideo}`],{windowsHide:true,stdio:'ignore'});
 try{
  await page.evaluate(()=>{Health.close();closeUtility();setLiveOpen(false);setDeckExpanded(false)});await ready();
  if(!focusOnly){await shot('home');
  await run('Explore first tap from expanded deck',async()=>{await page.evaluate(()=>setDeckExpanded(true));await ready();await tap('#live-bar');await ready();assert(await page.evaluate(()=>islands.live.open));});
  await page.evaluate(()=>setLiveOpen(true));await ready();await shot('explore');await page.evaluate(()=>setLiveOpen(false));await ready();
  await run('Twenty deck cycles and partial reversals',async()=>{
   for(let i=0;i<20;i++){await page.evaluate(()=>setDeckExpanded(true));await sleep([70,180,300][i%3]);if(i<3)await shot('deck-partial-'+i);await page.evaluate(()=>setDeckExpanded(false));await sleep(80);await page.evaluate(()=>setDeckExpanded(true));await ready();assert.equal(await page.evaluate(()=>deckProgress),1);await page.evaluate(()=>setDeckExpanded(false));await ready()}
  });
  await run('Intake comparison scroll and reversal twenty times',async()=>{
   await page.evaluate(()=>{metric='intake';days=1;render();setDeckExpanded(true)});await ready();
   for(let i=0;i<20;i++){await page.evaluate(()=>{$('#deck-scroll').scrollTop=$('#deck-scroll').scrollHeight});await sleep(50);assert(await page.locator('#current-average').innerText());await page.evaluate(()=>setDeckExpanded(false));await sleep(90+(i%3)*45);await page.evaluate(()=>setDeckExpanded(true));await ready()}
   await page.evaluate(()=>{$('#deck-scroll').scrollTop=$('#deck-scroll').scrollHeight});await sleep(200);await shot('comparison');
   return await page.locator('.comparison').innerText();
  });
  await run('Metric and Body navigation plus chart inspection',async()=>{
   for(const expanded of [false,true])for(const next of ['steps','sleep','heart','intake']){await page.evaluate(({next,expanded})=>{metric=next;render();setDeckExpanded(expanded)},{next,expanded});await ready();assert(await page.locator('#steps').innerText())}
   await page.evaluate(()=>Health.open('body'));await sleep(700);
   for(let i=0;i<8;i++){await tap('[data-body-metric="'+['weight','fatMass','muscle','lean'][i%4]+'"]');await sleep(95)}await sleep(800);await shot('body');
  });
  }
  await page.evaluate(()=>{Health.open('workouts');if(Health.state.active)Health.action('finish')});await sleep(600);
  await run('Optional weight countdown starts native session',async()=>{await tap('[data-setup="Strength"]');await sleep(600);await shot('setup');await tap('.workout-primary');await page.waitForSelector('.workout-countdown');await shot('countdown');await page.waitForSelector('.workout-live',{timeout:10000});assert(await page.evaluate(()=>Health.state.active&&typeof OrbitWorkouts.elapsedMs==='function'));});
  const started=await page.evaluate(()=>Health.state.active?.startedAt);assert(started);
  await sleep(1200);await shot('workout-normal');
  const original=await page.evaluate(()=>{const s=JSON.parse(OrbitMusic.read(''));return {status:s.status,playing:s.playing,art:!!s.art,canToggle:s.canToggle}});
  fs.writeFileSync(path.join(out,'media-initial.json'),JSON.stringify(original,null,2));
  await run('Real media callback and play pause acknowledgement',async()=>{
   assert.equal(original.status,'ready');assert(original.canToggle);await page.waitForSelector('[data-music="toggle"]');await tap('[data-music="toggle"]');
   await page.waitForFunction(before=>JSON.parse(OrbitMusic.read('')).playing!==before,original.playing,{timeout:10000});
   const elapsed=await page.evaluate(()=>OrbitWorkouts.elapsedMs());assert(elapsed>0);await tap('[data-music="toggle"]');
   await page.waitForFunction(before=>JSON.parse(OrbitMusic.read('')).playing===before,original.playing,{timeout:10000});
   return {originalPlaying:original.playing,restored:true};
  });
  shell('dumpsys gfxinfo com.mani.orbit.audit reset');
  await page.evaluate(()=>{window.auditFrames=[];let last=performance.now();window.auditFrame=0;function frame(t){auditFrames.push(t-last);last=t;auditFrame=requestAnimationFrame(frame)}auditFrame=requestAnimationFrame(frame)});
  await browser.startTracing(page,{screenshots:false,categories:['devtools.timeline','blink','cc']});
  await run('Focus reversals and readable timer',async()=>{
   for(let i=0;i<6;i++){await tap('[data-music-expand]');await sleep([90,220,400][i%3]);if(i<3)await shot('focus-partial-'+i);await page.evaluate(()=>WorkoutFocus.toggle(document.querySelector('.workout-live'),false));await sleep(100);await page.evaluate(()=>WorkoutFocus.toggle(document.querySelector('.workout-live'),true));await sleep(900);assert.equal(await page.evaluate(()=>Health.state.active.startedAt),started);if(i===0)await shot('workout-focus');await tap('#health-minimize');await sleep(1000)}
  });
  const trace=JSON.parse((await browser.stopTracing()).toString());
  const perf=await page.evaluate(()=>{cancelAnimationFrame(auditFrame);const f=auditFrames.slice(1).sort((a,b)=>a-b);return {frames:f.length,median:f[Math.floor(f.length*.5)],p95:f[Math.floor(f.length*.95)],max:f.at(-1),over25:f.filter(n=>n>25).length}});
  perf.rasterMs=trace.traceEvents.filter(e=>e.ph==='X'&&/RasterTask|ImageDecodeTask/.test(e.name)).reduce((s,e)=>s+(e.dur||0)/1000,0);
  fs.writeFileSync(path.join(out,'performance.json'),JSON.stringify(perf,null,2));fs.writeFileSync(path.join(out,'trace.json'),JSON.stringify(trace));fs.writeFileSync(path.join(out,'gfxinfo.txt'),shell('dumpsys gfxinfo com.mani.orbit.audit framestats'));
  if(!baseline){
   await run('Pause resume across normal focus details',async()=>{
    for(const mode of ['normal','focus','details']){if(mode==='focus'){await tap('[data-music-expand]');await sleep(900)}if(mode==='details'){await tap('#health-minimize');await sleep(900);await tap('[data-workout-detail="active"]');await sleep(600)}await tap('#health-content [data-session="pause"]');await sleep(200);const t=await page.evaluate(()=>OrbitWorkouts.elapsedMs());await sleep(1100);assert.equal(await page.evaluate(()=>OrbitWorkouts.elapsedMs()),t);await tap('#health-content [data-session="resume"]');await sleep(250);assert((await page.evaluate(()=>OrbitWorkouts.elapsedMs()))>t)}await shot('details');await tap('#health-back');await sleep(600);
   });
   await run('Background and screen off keep native elapsed authority',async()=>{const before=await page.evaluate(()=>OrbitWorkouts.elapsedMs());shell('input keyevent KEYCODE_HOME');await sleep(1200);shell('input keyevent KEYCODE_SLEEP');await sleep(1400);shell('input keyevent KEYCODE_WAKEUP');shell('wm dismiss-keyguard');shell('am start -W -n com.mani.orbit.audit/com.mani.orbit.MainActivity');await sleep(700);const after=await page.evaluate(()=>OrbitWorkouts.elapsedMs());assert(after-before>=2400);return {advancedMs:after-before}});
  }
  await run('Finish paused session creates exactly one reconciled record',async()=>{
   await tap('#health-content [data-session="pause"]');await sleep(1100);await tap('#health-content [data-session="finish"]');await sleep(700);const data=await page.evaluate(started=>Health.state.history.filter(r=>r.startedAt===started),started);assert.equal(data.length,1);assert(data[0].totalMs>=data[0].elapsed);assert(data[0].totalMs-data[0].elapsed>=900);assert(!await page.evaluate(()=>Health.state.active));await shot('saved');return {elapsedMs:data[0].elapsed,totalMs:data[0].totalMs,pausedMs:data[0].totalMs-data[0].elapsed};
  });
  await tap('#health-back');await sleep(600);await tap('.workout-history summary');await sleep(300);await shot('history');await page.evaluate(()=>Health.open('overview'));await sleep(700);await shot('health');await page.evaluate(()=>Health.close());await ready();
  await run('Final overflow taps from closed open and recently closed Explore',async()=>{for(const open of [false,true,false,true,false]){await page.evaluate(open=>setLiveOpen(open),open);await ready();await tap('#more');await ready();assert(await page.evaluate(()=>islands.utility.open));await page.evaluate(()=>closeUtility());await ready()}await tap('#more');await ready();await shot('final-overflow')});
  assert.deepEqual(errors,[]);
 }finally{
  try{shell("for p in $(pidof screenrecord); do case $(cat /proc/$p/cmdline | tr '\\0' ' ') in *"+remoteVideo+"*) kill -INT $p;; esac; done");await sleep(1800);command('pull',remoteVideo,path.join(out,'sequence.mp4'));shell(`rm -f ${remoteVideo} /sdcard/orbit-audit-${phase}.png`)}catch(e){results.push({name:'Recording',status:'FAIL',error:e.message})}recorder.unref();
  fs.writeFileSync(path.join(out,focusOnly?'results-focus.json':'results.json'),JSON.stringify(results,null,2)+'\n');await browser.close();
 }
 if(!baseline&&results.some(r=>r.status==='FAIL'))process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1});
