// Requires explicit phone authorization. Writes test data only to com.mani.orbit.audit.
const {chromium}=require('playwright'),{execFileSync,spawn}=require('node:child_process');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const serial=process.env.ORBIT_AUDIT_SERIAL;if(!serial)throw Error('Set the authorized Galaxy serial');
const adb=path.join(process.env.LOCALAPPDATA,'Android/Sdk/platform-tools/adb.exe');
const command=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true}).trim(),sh=s=>command('shell',s),wait=ms=>new Promise(r=>setTimeout(r,ms));
const out=path.join(__dirname,'player-settings-20260912','phone');fs.mkdirSync(out,{recursive:true});
const results=[],errors=[];let browser,page,cdp,original,recorder;
const ready=()=>page.waitForFunction(()=>!SurfaceMotion.active&&!WorkoutFocus.active&&!islandFrame&&!motionFrame);
async function connect(){const pid=sh('pidof com.mani.orbit.audit');assert(/^\d+$/.test(pid));command('forward','tcp:9224','localabstract:webview_devtools_remote_'+pid);browser=await chromium.connectOverCDP('http://127.0.0.1:9224');page=browser.contexts()[0].pages()[0];cdp=await page.context().newCDPSession(page);page.on('pageerror',e=>errors.push(e.message));await page.waitForFunction(()=>typeof OrbitSettings!=='undefined');assert.equal(page.url(),'https://orbit.invalid/index.html')}
async function restart(){await browser.close();sh('am force-stop com.mani.orbit.audit');sh('am start -W -n com.mani.orbit.audit/com.mani.orbit.MainActivity');await wait(500);await connect();await ready()}
async function tap(selector){await page.locator(selector).scrollIntoViewIfNeeded();const r=await page.locator(selector).boundingBox();assert(r,selector);await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:r.x+r.width/2,y:r.y+r.height/2}]});await wait(55);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await wait(350)}
async function swipe(selector,dy){const r=await page.locator(selector).boundingBox(),x=r.x+r.width/2,y=r.y+Math.min(14,r.height/2);await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});for(let i=1;i<=12;i++){await wait(18);await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:y+dy*i/12}]})}await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await ready()}
function shot(name){sh('screencap -p /sdcard/orbit-settings-check.png');command('pull','/sdcard/orbit-settings-check.png',path.join(out,name+'.png'))}
async function check(name,fn){const detail=await fn();results.push({name,status:'PASS',detail});console.log('PASS '+name)}
async function media(){return page.evaluate(()=>{const s=JSON.parse(OrbitMusic.read(''));delete s.art;return s})}
async function restoreMedia(){if(!original||!page)return;const s=await media();if(s.status!=='ready')return;if(s.title===original.title&&s.canSeek)await page.evaluate(position=>{const s=JSON.parse(OrbitMusic.read(''));OrbitMusic.command(s.id,'seek',position)},original.position);if(s.playing!==original.playing&&s.canToggle)await page.evaluate(playing=>{const s=JSON.parse(OrbitMusic.read(''));OrbitMusic.command(s.id,playing?'play':'pause',0)},original.playing)}
(async()=>{
 const font=sh('settings get system font_scale');
 try{
  sh('pm grant com.mani.orbit.audit android.permission.POST_NOTIFICATIONS');
  await connect();assert.equal(await page.evaluate(()=>Health.state.active),null,'Use a fresh Audit app');
  original=await media();fs.writeFileSync(path.join(out,'media-before.json'),JSON.stringify(original,null,2));
  recorder=spawn(adb,['-s',serial,'shell','screenrecord --size 720x1560 --bit-rate 5000000 --time-limit 180 /sdcard/orbit-settings-check.mp4'],{windowsHide:true,stdio:'ignore'});
  await check('Home seam removed through physical deck gestures',async()=>{
   shot('home');await swipe('#stack-open',-220);assert.equal(await page.evaluate(()=>deckExpanded),true);shot('home-expanded');
   for(let i=0;i<4;i++){await page.evaluate(()=>setDeckExpanded(false));await wait(120);assert.equal(await page.locator('#panel-deck').evaluate(n=>getComputedStyle(n,'::before').content),'none');await page.evaluate(()=>setDeckExpanded(true));await ready()}
   await page.evaluate(()=>setDeckExpanded(false));await ready();
  });
  await check('Native keyboard, profile save and persistence across app restart',async()=>{
   await tap('#settings-open');await ready();await tap('#profile-height');await wait(650);
   const r=await page.locator('#profile-height').boundingBox();assert(r.y+r.height<=await page.evaluate(()=>innerHeight));shot('settings-keyboard');
   await page.locator('#profile-name').fill('Audit test');await page.locator('#profile-birth').fill('1996-04-12');await page.locator('#profile-height').fill('178');await page.locator('#profile-weight').fill('76.4');
   sh('input keyevent KEYCODE_BACK');await wait(350);assert.equal(await page.evaluate(()=>Health.page),'settings');
   await tap('#profile-form button[type=submit]');assert.equal(await page.locator('#profile-saved').textContent(),'Profile saved');await page.locator('#health-scroll').evaluate(n=>n.scrollTop=0);shot('settings');
   await page.locator('#settings-goal').fill('12000');sh('input keyevent KEYCODE_BACK');await wait(250);await tap('#settings-goal-form button');await tap('#settings-reduce');
   await restart();await tap('#settings-open');await ready();assert.equal(await page.locator('#profile-height').inputValue(),'178');assert.equal(await page.locator('#profile-weight').inputValue(),'76.4');assert.equal(await page.locator('#settings-goal').inputValue(),'12000');assert(await page.locator('#settings-reduce').isChecked());await tap('#settings-reduce');
  });
  await check('Saved weight reused by all activities and isolated native workout start',async()=>{
   await page.evaluate(()=>Health.open('workouts'));await ready();
   for(const kind of ['Walking','Running','Cycling','Strength']){await tap(`[data-setup="${kind}"]`);await ready();assert.equal(await page.locator('#workout-weight').inputValue(),'76.4');if(kind!=='Strength'){await tap('#health-back');await ready()}}
   shot('setup');await tap('#workout-setup-form button[type=submit]');await page.waitForSelector('.workout-live',{timeout:10000});await ready();assert.equal(await page.evaluate(()=>Health.state.active.weightKg),76.4);await page.waitForSelector('.music-thumb i.is-visible',{timeout:10000});shot('compact');
  });
  await check('Native artwork fills top edge and keeps original decode resolution',async()=>{
   await tap('[data-music-expand]');await ready();shot('music');
   const geometry=await page.evaluate(()=>{const img=document.querySelector('.music-cover img'),r=img.getBoundingClientRect(),back=document.querySelector('#health-back').getBoundingClientRect();return {source:[img.naturalWidth,img.naturalHeight],displayCss:[r.width,r.height],imageTop:r.top,headerTop:back.top,viewport:[innerWidth,innerHeight],pixelRatio:devicePixelRatio,scrimCount:document.querySelectorAll('.timer-scrim,#health-minimize').length}});
   assert.equal(geometry.imageTop,0);assert.equal(geometry.scrimCount,0);assert(Math.abs(geometry.displayCss[0]-geometry.viewport[0])<1);assert(geometry.headerTop>=34);return geometry;
  });
  await check('Actual music play/pause acknowledgement with independent workout state',async()=>{
   assert.equal(original.status,'ready');assert(original.canToggle);await tap('[data-music="toggle"]');await page.waitForFunction(playing=>JSON.parse(OrbitMusic.read('')).playing!==playing,original.playing,{timeout:10000});
   assert(await page.evaluate(()=>Health.state.active.resumedAt!==null));await tap('[data-music="toggle"]');await page.waitForFunction(playing=>JSON.parse(OrbitMusic.read('')).playing===playing,original.playing,{timeout:10000});return {restoredPlaying:original.playing};
  });
  await check('Previous/next native transport and original-track restoration',async()=>{
   const before=await media();assert(before.canPrevious&&before.canSeek);await page.evaluate(()=>{const s=JSON.parse(OrbitMusic.read(''));OrbitMusic.command(s.id,'seek',0)});await wait(500);await tap('[data-music="previous"]');
   await page.waitForFunction(title=>JSON.parse(OrbitMusic.read('')).title!==title,original.title,{timeout:10000});const previous=await media();assert(previous.canNext);await page.waitForFunction(()=>!document.querySelector('[data-music="next"]').disabled);
   await tap('[data-music="next"]');await page.waitForFunction(title=>JSON.parse(OrbitMusic.read('')).title===title,original.title,{timeout:10000});await restoreMedia();await wait(600);shot('music-restored');return {originalTrackRestored:true};
  });
  await check('Swipe down/up and fixed transport targets through interrupted focus changes',async()=>{
   const size=()=>page.locator('.music-controls button,.session-actions button').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return [r.width,r.height]}));const before=await size();let delta=0;
   await swipe('.music-heading h2',210);assert.equal(await page.locator('[data-timer-focus]').getAttribute('aria-pressed'),'false');await swipe('.music-heading h2',-210);assert.equal(await page.locator('[data-timer-focus]').getAttribute('aria-pressed'),'true');
   for(const focused of [false,true,false,true]){await page.evaluate(v=>WorkoutFocus.toggle(document.querySelector('.workout-live'),v),focused);for(let i=0;i<8;i++){await wait(30);const now=await size();now.forEach((r,j)=>r.forEach((v,k)=>delta=Math.max(delta,Math.abs(v-before[j][k]))))}await ready()}
   assert(delta<.6);return {maxControlSizeChangePx:delta};
  });
  await check('Pause, resume and exactly one saved native test record',async()=>{
   const id=await page.evaluate(()=>Health.state.active.startedAt);await tap('#health-content [data-session="pause"]');const time=await page.evaluate(()=>OrbitWorkouts.elapsedMs());await wait(1100);assert.equal(await page.evaluate(()=>OrbitWorkouts.elapsedMs()),time);await tap('#health-content [data-session="resume"]');await wait(650);assert((await page.evaluate(()=>OrbitWorkouts.elapsedMs()))>time);await tap('#health-content [data-session="finish"]');await ready();assert.equal(await page.evaluate(()=>Health.state.active),null);const records=await page.evaluate(id=>Health.state.history.filter(s=>s.startedAt===id),id);assert.equal(records.length,1);assert.equal(records[0].weightKg,76.4);shot('saved');
  });
  await check('Settings remains usable with 130 percent native text size',async()=>{
   sh('settings put system font_scale 1.3');await restart();await tap('#settings-open');await ready();
   const layout=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>innerWidth,fields:[...document.querySelectorAll('#profile-form input')].map(n=>{const r=n.getBoundingClientRect();return {width:r.width,height:r.height,right:r.right}}),width:innerWidth}));assert(!layout.overflow);assert(layout.fields.every(r=>r.height>=44&&r.right<=layout.width));shot('settings-large-text');return layout;
  });
  assert.deepEqual(errors,[]);
 }catch(e){results.push({name:'Interrupted check',status:'FAIL',error:e.message});throw e}
 finally{
  try{await restoreMedia()}catch(e){results.push({name:'Restore playback',status:'FAIL',error:e.message})}
  sh('settings put system font_scale '+font);
  try{sh("for p in $(pidof screenrecord); do case $(cat /proc/$p/cmdline | tr '\\0' ' ') in *orbit-settings-check.mp4*) kill -INT $p;; esac; done");await wait(1500);command('pull','/sdcard/orbit-settings-check.mp4',path.join(out,'sequence.mp4'))}catch(e){results.push({name:'Recording',status:'FAIL',error:e.message})}
  recorder?.unref();sh('rm -f /sdcard/orbit-settings-check.png /sdcard/orbit-settings-check.mp4');fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));await browser?.close();
 }
})().catch(e=>{console.error(e);process.exitCode=1});
