// Desktop-only regression: isolated browser storage; never connects to a phone.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {seedMedia}=require('./video-audit-scenes.cjs');
const out=path.join(__dirname,'player-settings-20260912');fs.mkdirSync(out,{recursive:true});
const url=process.env.ORBIT_PREVIEW_URL||'http://127.0.0.1:8784/signal-orbit-steps/index.html';
(async()=>{
 const browser=await chromium.launch({headless:true}),results=[];
 try{for(const width of [390,384,320]){
  const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:2,hasTouch:true}),p=await context.newPage(),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.goto(url);assert.deepEqual(errors,[]);
  const settle=()=>p.waitForFunction(()=>!SurfaceMotion.active&&!WorkoutFocus.active&&!islandFrame&&!motionFrame);
  const shot=name=>p.screenshot({path:path.join(out,`${width}-${name}.png`)});
  // Match the native inset and its edge-to-edge shell without using ADB.
  await p.addStyleTag({content:'.status,.home-indicator{display:none}.screen{height:100dvh!important;min-height:0!important}.masthead{padding-top:12px}:root{--orbit-inset-top:34px;--orbit-inset-bottom:8px}'});
  await settle();await shot('home');
  assert.equal(await p.locator('#more,#options,#health-minimize,.timer-scrim').count(),0);
  for(const open of [true,false,true,false]){await p.evaluate(open=>setDeckExpanded(open),open);await p.waitForTimeout(90);assert.equal(await p.locator('#panel-deck').evaluate(n=>getComputedStyle(n,'::before').content),'none');await settle()}
  await p.locator('#settings-open').click();await settle();assert.equal(await p.locator('#health-title').textContent(),'Settings');
  assert.equal(await p.locator('#profile-weight').inputValue(),'');assert.equal(await p.locator('#profile-height').inputValue(),'');
  await p.locator('#profile-name').fill('Alex');await p.locator('#profile-birth').fill('1996-04-12');await p.locator('#profile-height').fill('178');await p.locator('#profile-weight').fill('76.4');
  await p.evaluate(()=>Health.render());assert.equal(await p.locator('#profile-name').inputValue(),'Alex','Background refresh lost profile draft');
  await p.locator('#profile-form button[type=submit]').click();assert.equal(await p.locator('#profile-saved').textContent(),'Profile saved');
  const raw=await p.evaluate(()=>localStorage.getItem('orbit-profile-v1'));assert.equal(JSON.parse(raw).weightKg,76.4);
  await p.locator('#profile-weight').fill('999');await p.locator('#profile-form button[type=submit]').click();assert.match(await p.locator('#profile-error').textContent(),/20 to 350/);assert.equal(await p.evaluate(()=>localStorage.getItem('orbit-profile-v1')),raw);
  await p.locator('#profile-weight').fill('76.4');
  await p.evaluate(()=>{window.savedSetItem=Storage.prototype.setItem;Storage.prototype.setItem=function(k,v){if(k==='orbit-profile-v1')throw Error('test unavailable storage');return savedSetItem.call(this,k,v)}});
  await p.locator('#profile-name').fill('Unsaved');await p.locator('#profile-form button[type=submit]').click();assert.match(await p.locator('#profile-error').textContent(),/Could not confirm/);assert.equal(await p.evaluate(()=>localStorage.getItem('orbit-profile-v1')),raw);
  await p.evaluate(()=>Storage.prototype.setItem=savedSetItem);await p.locator('#profile-name').fill('Alex');await p.locator('#profile-form button[type=submit]').click();
  await p.locator('#settings-goal').fill('12000');await p.locator('#settings-goal-form button').click();assert.equal(await p.locator('#profile-name').inputValue(),'Alex');assert.equal(await p.locator('#settings-goal-error').textContent(),'');
  await p.locator('#settings-reduce').check();assert(await p.evaluate(()=>SurfaceMotion.reduced));await p.locator('#settings-reduce').uncheck();
  await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);await shot('settings');
  await p.reload();await p.locator('#settings-open').click();await settle();assert.equal(await p.locator('#profile-name').inputValue(),'Alex');assert.equal(await p.locator('#profile-height').inputValue(),'178');assert.equal(await p.locator('#profile-weight').inputValue(),'76.4');assert.equal(await p.locator('#settings-goal').inputValue(),'12000');
  assert.equal(await p.evaluate(()=>OrbitSettings.validate({name:'',birthDate:'2025-02-31',heightCm:null,weightKg:null})), 'Enter a valid birth date, up to today.');
  await p.locator('#health-back').click();await settle();assert.equal(await p.evaluate(()=>document.activeElement.id),'settings-open');
  await p.locator('#date-button').click();await settle();assert(await p.evaluate(()=>islands.utility.open));await p.locator('#date-dialog .cancel').click();await settle();assert.equal(await p.evaluate(()=>document.activeElement.id),'date-button');
  await p.evaluate(()=>Health.open('workouts'));await settle();
  for(const kind of ['Walking','Running','Cycling','Strength']){await p.locator(`[data-setup="${kind}"]`).click();await settle();assert.equal(await p.locator('#workout-weight').inputValue(),'76.4');if(kind!=='Strength'){await p.locator('#health-back').click();await settle()}}
  await p.locator('#workout-weight').fill('77');await p.locator('#workout-setup-form button[type=submit]').click();await p.waitForFunction(()=>Health.state.active);await settle();assert.equal(await p.evaluate(()=>Health.state.active.weightKg),77);assert.equal(await p.evaluate(()=>OrbitSettings.profile().weightKg),76.4);
  await p.addStyleTag({content:'.status,.home-indicator{display:none}.screen{height:100dvh!important;min-height:0!important}.masthead{padding-top:12px}:root{--orbit-inset-top:34px;--orbit-inset-bottom:8px}'});
  await seedMedia(p);await p.evaluate(()=>MusicPlayer.refresh());await p.waitForTimeout(750);await shot('compact');
  await p.locator('[data-music-expand]').click();await settle();await shot('music');
  const geometry=await p.evaluate(()=>{const q=s=>document.querySelector(s),page=q('#health-page').getBoundingClientRect(),image=q('.music-cover img').getBoundingClientRect(),header=q('#health-back').getBoundingClientRect();return {top:page.top,imageTop:image.top,width:page.width,imageWidth:image.width,headerTop:header.top}});
  assert.equal(geometry.top,0);assert.equal(geometry.imageTop,0);assert(Math.abs(geometry.width-geometry.imageWidth)<1);assert(geometry.headerTop>=34);
  for(const action of ['previous','next','toggle']){
   const selector=`[data-music="${action}"]`,before=await p.locator(selector).boundingBox();await p.locator(selector).click();
   const frames=await p.locator(selector+' svg').evaluate(n=>n.getAnimations().map(a=>a.effect.getKeyframes()));assert(frames.length&&frames.flat().some(f=>f.transform&&f.transform!=='none'),action+' has no animated feedback');
   const after=await p.locator(selector).boundingBox();assert.equal(before.width,after.width);assert.equal(before.height,after.height);await settle();
  }
  await p.evaluate(()=>{for(let i=0;i<8;i++)document.querySelector('[data-music="next"]').click()});await settle();assert.equal(await p.evaluate(()=>WorkoutFocus.active),0,'Interrupted icon animations must clean up');
  await p.evaluate(()=>SurfaceMotion.setReduced(true));await p.locator('[data-music="previous"]').click();
  assert(await p.locator('[data-music="previous"] svg').evaluate(n=>n.getAnimations().every(a=>a.effect.getKeyframes().every(f=>!f.transform||f.transform==='none'))),'Reduced feedback must use opacity only');await settle();await p.evaluate(()=>SurfaceMotion.setReduced(false));
  // Real DOM touch events exercise the swipe handler, not its internal toggle method.
  await p.evaluate(async()=>{const target=document.querySelector('.music-heading h2'),y=target.getBoundingClientRect().top;const fire=(type,dy)=>{const t=new Touch({identifier:3,target,clientX:100,clientY:y+dy});target.dispatchEvent(new TouchEvent(type,{touches:type==='touchend'?[]:[t],changedTouches:[t],bubbles:true,cancelable:true}))};fire('touchstart',0);for(let i=1;i<=12;i++){await new Promise(requestAnimationFrame);fire('touchmove',i*24)}fire('touchend',288)});await settle();assert.equal(await p.locator('[data-timer-focus]').getAttribute('aria-pressed'),'false');
  assert.equal(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  if(width===390){
   await p.evaluate(()=>{localStorage.setItem('orbit-profile-v1','{broken');Health.open('settings')});await settle();assert.match(await p.locator('#profile-error').textContent(),/preserved/);assert(await p.locator('#profile-form button[type=submit]').isDisabled());assert.equal(await p.evaluate(()=>localStorage.getItem('orbit-profile-v1')),'{broken');
  }
  assert.deepEqual(errors,[]);results.push({width,status:'PASS',geometry,checks:'Profile persistence, validation, save failure, draft preservation, goal, motion, back/date navigation, defaults for four activities, session override, edge-to-edge artwork, fixed animated buttons, swipe-down and seam removal'});await context.close();
 }}finally{await browser.close()}
 fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results));
})().catch(e=>{console.error(e);process.exitCode=1});
