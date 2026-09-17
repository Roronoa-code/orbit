// Real local Chromium touch input; no phone connection or production health data.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import','liquid-interaction');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({headless:true}),results=[];
try{for(const width of [390,320]){
 const context=await browser.newContext({viewport:{width,height:844},hasTouch:true,isMobile:true}),p=await context.newPage(),errors=[];
 p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(data=>{
  window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',data:known==='1'?null:data,status:'Local fixture',available:true,permitted:true}),load(){}};
  localStorage.setItem('orbit-profile-v1',JSON.stringify({name:'Local',heightCm:180,weightKg:75,birthDate:''}));
  window.haptics=[];window.OrbitFeedback={pulse:kind=>haptics.push(kind)};
 },require('./samsung-import.cjs').fixture());
 await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.waitForTimeout(500);
 const cdp=await context.newCDPSession(p),touch=async(type,x,y)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'||type==='touchCancel'?[]:[{x,y}]});
 const centre=async selector=>{const r=await p.locator(selector).boundingBox();assert(r,selector+' exists');return{x:r.x+r.width/2,y:r.y+r.height/2}};
 const shot=name=>p.screenshot({path:path.join(out,`${name}-${width}.png`)});
 const snap=selector=>p.locator(selector).evaluate(n=>{
  const pill=n.querySelector('.selection-pill'),r=(pill||n).getBoundingClientRect(),s=getComputedStyle(pill||n),surface=n.id==='live-bar'?n.closest('.live-island').querySelector('.glass-shell-response'):n.querySelector('.glass-response-surface');
  return {x:r.x,y:r.y,width:r.width,height:r.height,state:n.dataset.glassState,opacity:surface?Number(surface.style.opacity):Number(n.style.getPropertyValue('--glass-engagement')),layoutWidth:parseFloat(s.width),layoutHeight:parseFloat(s.height)};
 });
 async function held(selector,host=selector,name){
  await p.locator(selector).scrollIntoViewIfNeeded();const point=await centre(selector),before=await snap(host);
  await touch('touchStart',point.x,point.y);await p.waitForTimeout(32);const first=await snap(host);assert(first.opacity>0,'Touch feedback begins on first frames: '+selector);
  await p.waitForTimeout(320);const hold=await snap(host);assert(hold.opacity>.97,'Held material is engaged: '+selector);
  if(name)await shot(name);
  return {point,before,hold};
 }
 async function release(){await touch('touchEnd');await p.waitForTimeout(750)}
 async function quietHold(selector,host=selector,name){
  await p.locator(selector).scrollIntoViewIfNeeded();const point=await centre(selector);
  await touch('touchStart',point.x,point.y);await p.waitForTimeout(400);
  assert.equal(await p.locator(host+' .glass-response-surface').count(),0,'Reading content never creates a held glass layer: '+selector);
  assert.notEqual(await p.locator(host).getAttribute('data-glass-state'),'engaged');
  if(name)await shot(name);
 }
 async function disclosure(selector,name,quiet=false){
  const wasOpen=await p.locator(selector).evaluate(n=>n.parentElement.open);
  if(quiet)await quietHold(selector,selector,name);else{
  const hit=await held(selector,selector,name),style=await p.locator(selector+'>.glass-response-surface').evaluate(n=>{
   const s=getComputedStyle(n);return{shadow:s.boxShadow,radius:s.borderRadius,transform:s.transform,light:getComputedStyle(n.firstElementChild).display};
  });
  assert.deepEqual(style,{shadow:'none',radius:'12px',transform:'none',light:'none'},'Disclosure feedback stays flat and softly rounded');
  assert.equal(hit.before.width,hit.hold.width);assert.equal(hit.before.height,hit.hold.height);
  }
  await release();assert.equal(await p.locator(selector).evaluate(n=>n.parentElement.open),!wasOpen);
  assert((await snap(selector)).opacity<.001,'Disclosure feedback clears after release');
  await p.locator(selector).focus();await p.keyboard.press('Space');await p.waitForTimeout(750);
  assert.equal(await p.locator(selector).evaluate(n=>n.parentElement.open),wasOpen,'Keyboard disclosure still toggles');
 }
 async function dragTo(from,to){for(let i=1;i<=14;i++){await touch('touchMove',from.x+(to.x-from.x)*i/14,from.y+(to.y-from.y)*i/14);await p.waitForTimeout(16)}}

 const globe=await centre('#orb-button');await touch('touchStart',globe.x,globe.y);await p.waitForTimeout(450);
 assert.equal(await p.locator('#orb-button .glass-response-surface').count(),0,'The globe retains its gesture without a full-hit-area glow');
 await shot('globe-held');await touch('touchCancel');
 await p.evaluate(()=>Health.open('body'));await p.waitForTimeout(550);
 const dial=await centre('.body-centre');await touch('touchStart',dial.x,dial.y);await p.waitForTimeout(350);
 assert.equal(await p.locator('.body-centre .glass-response-surface').count(),0,'Content dials do not acquire navigation glass');await touch('touchCancel');
 const track='.body-metric-picker',start='[data-body-metric=weight]';
 const heldBody=await held(start,track,'body-held');
 assert(heldBody.hold.height>heldBody.before.height*1.15,'Held selector lifts beyond resting track height');
 assert.equal(heldBody.before.layoutWidth,heldBody.hold.layoutWidth);assert.equal(heldBody.before.layoutHeight,heldBody.hold.layoutHeight);
 const labelBoxes=await p.locator(track+' button').evaluateAll(ns=>ns.map(n=>({w:n.offsetWidth,h:n.offsetHeight})));
 await p.evaluate(()=>{
  window.geometryReads=0;window.mapEdits=0;window.watchDrag=false;window.frameTimes=[];
  const original=Element.prototype.getBoundingClientRect;
  Element.prototype.getBoundingClientRect=function(){if(watchDrag&&this.closest?.('.body-metric-picker'))geometryReads++;return original.call(this)};
  window.maps=new MutationObserver(records=>mapEdits+=records.length);document.querySelectorAll('filter[id^="orbit-glass-"] feImage').forEach(n=>maps.observe(n,{attributes:true}));
  let last=performance.now();window.watchFrames=true;const tick=t=>{if(!watchFrames)return;frameTimes.push(t-last);last=t;requestAnimationFrame(tick)};requestAnimationFrame(tick);
 });
 const end=await centre('[data-body-metric=lean]');await p.evaluate(()=>watchDrag=true);
 await cdp.send('Performance.enable');const beforeMetrics=await cdp.send('Performance.getMetrics');
 await dragTo(heldBody.point,end);await p.waitForTimeout(130);
 const afterMetrics=await cdp.send('Performance.getMetrics');await p.evaluate(()=>{watchDrag=false;watchFrames=false;maps.disconnect()});
 const performanceData=await p.evaluate(()=>({geometryReads,mapEdits,frameTimes}));
 assert.equal(performanceData.geometryReads,0,'No layout reads on selector drag');assert.equal(performanceData.mapEdits,0,'No optical map generation on drag');
 const count=(result,name)=>result.metrics.find(m=>m.name===name)?.value||0;
 performanceData.layouts=count(afterMetrics,'LayoutCount')-count(beforeMetrics,'LayoutCount');assert.equal(performanceData.layouts,0,'Selector movement uses transforms, no layout');
 assert.deepEqual(await p.locator(track+' button').evaluateAll(ns=>ns.map(n=>({w:n.offsetWidth,h:n.offsetHeight}))),labelBoxes,'Labels and hit targets keep their dimensions');
 await shot('body-drag');await touch('touchEnd');const atRelease=await snap(track);assert(atRelease.opacity>.85,'Release retains material instead of snapping off');
 await p.waitForTimeout(45);const beforeRegrab=await snap(track),current={x:beforeRegrab.x+beforeRegrab.width/2,y:beforeRegrab.y+beforeRegrab.height/2};
 await touch('touchStart',current.x,current.y);const afterRegrab=await snap(track);assert(Math.abs(beforeRegrab.x-afterRegrab.x)<4&&Math.abs(beforeRegrab.height-afterRegrab.height)<4,'Re-grab preserves displayed material');
 await p.waitForTimeout(300);await dragTo(current,heldBody.point);await p.waitForTimeout(100);await release();
 assert.equal(await p.locator(start).getAttribute('aria-pressed'),'true');assert.equal((await snap(track)).state,'idle');assert((await snap(track)).opacity<.001);
 await held(start,track);await touch('touchCancel');await p.waitForTimeout(700);assert.equal((await snap(track)).state,'idle');assert.equal(await p.locator(start).getAttribute('aria-pressed'),'true');
 // Starting on a distant, unselected item must also follow and commit at the finger.
 for(const delay of [350,16]){
  const from=await centre('[data-body-metric=lean]'),to=await centre('[data-body-metric=fatMass]');
  await touch('touchStart',from.x,from.y);await p.waitForTimeout(delay);
  if(delay>300){const lens=await snap(track);assert(Math.abs(lens.x+lens.width/2-from.x)<6,'Held lens reaches the unselected item');}
  await dragTo(from,to);await p.waitForTimeout(90);await release();
  assert.equal(await p.locator('[data-body-metric=fatMass]').getAttribute('aria-pressed'),'true','Distant grab releases over the intended item');
  await p.locator(start).tap();await p.waitForTimeout(650);
 }
 // An active track survives keyboard interaction and cancels on blur/hidden lifecycle.
 await p.locator(start).focus();await p.keyboard.down(' ');await p.waitForTimeout(250);assert((await snap(track)).opacity>.9);await p.keyboard.up(' ');await p.waitForTimeout(700);
 await held(start,track);await p.evaluate(()=>window.dispatchEvent(new Event('blur')));await touch('touchCancel');assert.equal((await snap(track)).opacity,0);
 await p.emulateMedia({reducedMotion:'reduce'});const gentle=await held(start,track);assert(Math.abs(gentle.hold.height-gentle.before.height)<1,'Reduced motion keeps feedback without elasticity');await release();await p.emulateMedia({reducedMotion:'no-preference'});
 await quietHold('[data-body-range="30"]','.segmented','range-held');await release();

 // The main launcher is also a held, draggable navigation control.
 await p.evaluate(()=>Health.close());await p.waitForTimeout(400);const live=await held('#live-bar','#live-bar','live-held');
 await dragTo(live.point,{x:live.point.x,y:live.point.y-110});await shot('live-drag');
 assert.equal(await p.locator('#live-bar>.glass-response-surface').count(),0,'Live drag uses its existing shell, not a second capsule');
 assert.equal(await p.locator('#live-island>.island-surface>.glass-shell-response').count(),1);await release();
 assert(await p.evaluate(()=>islands.live.open));
 const nav=await held('[data-activity=body]','.activity-choices','navigation-held'),destination=await centre('[data-activity=overview]');
 await dragTo(nav.point,destination);await p.waitForTimeout(120);
 assert(await p.locator('[data-activity=body]').evaluate(n=>getComputedStyle(n).backgroundColor==='rgba(0, 0, 0, 0)'&&getComputedStyle(n,':before').content==='none'),'The original pressed option leaves no second background');
 await shot('navigation-drag');await release();assert.equal(await p.evaluate(()=>Health.page),'overview');
 await disclosure('.oxygen-tile summary','oxygen-row-held',true);
 await p.locator('.oxygen-tile summary').tap();await p.waitForTimeout(450);assert.equal(await p.locator('[data-oxygen-point]').count(),7);await quietHold('#oxygen-scrub','.oxygen-chart');await release();

 // Native switches can be held and dragged, without a subsequent click undoing the choice.
 await p.evaluate(()=>Health.open('settings'));await p.waitForTimeout(350);
 await p.locator('.settings-health>summary').tap();await p.waitForTimeout(350);
 await disclosure('.settings-health .settings-about summary','connection-row-held');
 await disclosure('.settings-section.settings-about summary','about-row-held');
 const toggle='#settings-rotation';await p.locator(toggle).scrollIntoViewIfNeeded();
 const checked=await p.locator(toggle).isChecked(),switchHold=await held(toggle,toggle,'switch-held');
 await dragTo(switchHold.point,{x:switchHold.point.x+(checked?-20:20),y:switchHold.point.y});await p.waitForTimeout(90);await release();assert.equal(await p.locator(toggle).isChecked(),!checked);
 await p.locator(toggle).tap();await p.waitForTimeout(750);assert.equal(await p.locator(toggle).isChecked(),checked,'Normal switch tap still toggles once');
 const stableSwitch=await p.locator(toggle).evaluate(n=>({x:n.style.getPropertyValue('--switch-x'),checked:n.checked}));assert.equal(parseFloat(stableSwitch.x),stableSwitch.checked?18:0);
 await held('#profile-form button[type=submit]','#profile-form button[type=submit]');await release();

 await p.evaluate(()=>Health.open('sleep'));await p.waitForTimeout(350);await quietHold('[data-night-stage=deep]','[data-night-stage=deep]','sleep-held');await release();
 await quietHold('#night-scrub','.night-chart');await release();assert.equal(await p.locator('#night-cursor').count(),0);
 await p.evaluate(()=>Health.open('workouts'));await p.waitForTimeout(350);await held('.workout-tabs [data-workout-tab=history]','.workout-tabs','workout-tabs-held');await release();
 await quietHold('[data-week-day][aria-pressed=true]','.history-days','date-held');await release();
 await p.locator('[data-workout-tab=train]').tap();await p.locator('[data-setup=Running]').tap();await p.waitForTimeout(300);
 await held('.setup-segments label:first-of-type','.setup-segments');await release();
 await require('./video-audit-scenes.cjs').seedMedia(p);
 await p.evaluate(()=>{Health.action('start','Running',60000);Health.render()});await p.waitForTimeout(350);
 await held('[data-timer-mode=elapsed]','.timer-mode-picker');await release();
 await held('.session-actions [data-session=pause]','.session-actions [data-session=pause]','pause-held');await release();
 assert(await p.locator('.session-actions [data-session=resume]').isVisible());await held('.session-actions [data-session=resume]','.session-actions [data-session=resume]');await release();
 await held('.session-actions [data-session=pause]','.session-actions [data-session=pause]');
 assert.equal(await p.locator('.session-actions [data-session=pause]>.glass-response-surface').count(),1,'Label changes preserve the material');await touch('touchCancel');await p.waitForTimeout(700);
 await p.locator('[data-music-expand]').tap();await p.waitForTimeout(650);
 for(const action of ['previous','toggle','toggle','next']){
  const selector=`[data-music="${action}"]`,before=await p.locator(selector).boundingBox();await held(selector,selector,action==='toggle'?'music-held':undefined);await release();
  const after=await p.locator(selector).boundingBox();assert.equal(before.width,after.width);assert.equal(before.height,after.height);
 }
 await held('.music-timeline input','.music-timeline input');await release();
 const musicPoint=await centre('.music-heading h2');await touch('touchStart',musicPoint.x,musicPoint.y);await dragTo(musicPoint,{x:musicPoint.x,y:musicPoint.y+220});await release();
 assert.equal(await p.locator('.workout-live').evaluate(n=>n.classList.contains('timer-focused')),false,'Music swipe returns to the same compact controls');
 await p.evaluate(()=>{Health.state.active.weightKg=75;Health.state.active.trackLocation=true;Health.state.active.metrics={state:'tracking',distanceM:0,maxSpeedMps:0,speedMps:0,points:[{lat:51,lon:0,elapsedMs:0,speedMps:0,breakBefore:true}]};Health.render()});
 await p.locator('[data-workout-detail=active]').tap();await p.waitForTimeout(350);
 await held('[data-workout-chart=speed]','.workout-chart-tabs');await release();
 await disclosure('.energy-method summary','energy-row-held',true);
 await p.emulateMedia({forcedColors:'active'});await p.keyboard.press('Tab');await p.locator('#health-back').focus();assert.notEqual(await p.locator('#health-back').evaluate(n=>getComputedStyle(n).outlineStyle),'none');await p.emulateMedia({forcedColors:'none'});
 await p.evaluate(()=>{Health.open('body');Health.open('settings');Health.open('sleep')});await p.waitForTimeout(900);
 assert.equal(await p.evaluate(()=>GlassResponse.active),0,'No idle response frame work');assert.deepEqual(errors,[]);
 results.push({width,status:'PASS',performanceData,errors});console.log(width,'PASS: all control families, touch/hold/drag/release/regrab, native values, keyboard/cancel, no layout/map work',performanceData);
 await context.close();
}}finally{await browser.close()}
fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(results,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
