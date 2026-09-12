// Real rendered workout flow. Each viewport owns isolated storage; no phone data is used.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'player-settings-20260912','workout-rework');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({headless:true}),results=[];
 try{for(const width of [390,384,320]){
  const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{
   const kinds=['Running','Strength','Walking','Cycling'],today=new Date();today.setHours(9,0,0,0);
   const history=[0,1,2,4,8,10,17].map((ago,i)=>{const d=new Date(today);d.setDate(d.getDate()-ago);const startedAt=d.getTime(),elapsed=[2542000,2100000,1320000,3030000,14e3,1800000,2400000][i];return {kind:kinds[i%4],startedAt,endedAt:startedAt+elapsed+90000,elapsed,totalMs:elapsed+90000,weightKg:75,targetMs:0,trackLocation:false}});
   localStorage.setItem('orbit-workouts-v1',JSON.stringify({active:null,history}));localStorage.setItem('orbit-profile-v1',JSON.stringify({name:'',birthDate:'',heightCm:180,weightKg:75}));
  });
  await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
  await page.addStyleTag({content:'.status,.home-indicator{display:none}.screen{height:100dvh!important;min-height:0!important}:root{--orbit-inset-top:34px;--orbit-inset-bottom:8px}'});
  await page.locator('#live-bar').tap();await page.evaluate(()=>Health.open('workouts'));
  const settle=()=>page.waitForFunction(()=>!SurfaceMotion.active&&!WorkoutFocus.active),shot=async name=>{await settle();await page.screenshot({path:path.join(out,`${name}-${width}.png`)})};
  const overflow=()=>page.evaluate(()=>{const n=document.querySelector('#health-scroll');return n.scrollWidth>n.clientWidth+1});
  await shot('train');assert.equal(await page.locator('.workout-kinds button').count(),4);assert(!await overflow());
  const boxes=await page.locator('.workout-kinds button').evaluateAll(ns=>ns.map(n=>{const b=n.getBoundingClientRect();return {x:b.x,y:b.y,bottom:b.bottom}}));assert.equal(boxes[0].y,boxes[1].y);assert(boxes[3].bottom<690);
  // Fast view changes retain the same navigation and retarget its capsule.
  await page.evaluate(()=>window.testTabs=document.querySelector('.workout-tabs'));
  for(const tab of ['history','train','history','train','history']){await page.locator(`.workout-tabs [data-workout-tab="${tab}"]`).evaluate(n=>n.click());await page.waitForTimeout(65)}
  await shot('history');assert(await page.evaluate(()=>testTabs===document.querySelector('.workout-tabs')));assert.equal(await page.locator('.workout-tabs [aria-pressed=true]').innerText(),'History');
  await page.locator('[data-history-week="-1"]').click();await settle();const previous=await page.locator('.history-week-nav p').innerText(),shortDay=await page.evaluate(()=>{const d=new Date();d.setDate(d.getDate()-8);return (d.getDay()+6)%7});await page.locator(`[data-week-day="${shortDay}"]`).click();await settle();
  assert.match(await page.locator('#history-sessions').innerText(),/14s/);await page.locator('#history-sessions [data-workout-detail]').click();await shot('record');await page.locator('.energy-method summary').click();await settle();assert(await page.locator('.energy-method').evaluate(n=>n.open));await page.locator('.energy-method summary').click();await settle();assert.match(await page.locator('#workout-record-body').innerText(),/Active|Paused|Energy/);
  await page.locator('#health-back').click();await settle();assert.equal(await page.locator('.history-week-nav p').innerText(),previous);assert.equal(await page.locator(`[data-week-day="${shortDay}"]`).getAttribute('aria-pressed'),'true');
  await page.locator('[data-history-week="1"]').click();await settle();assert(await page.locator('[data-history-week="1"]').isDisabled());assert(await page.evaluate(()=>document.activeElement.hasAttribute('data-week-day')));
  await page.locator('.workout-tabs [data-workout-tab=train]').click();await settle();
  for(const kind of ['Walking','Running','Cycling','Strength']){await page.locator(`[data-setup="${kind}"]`).click();await settle();assert.equal(await page.locator('#workout-weight').inputValue(),'75');assert.equal(await page.locator('#workout-track').count(),kind==='Strength'?0:1);assert(!await overflow());if(kind==='Running')await shot('setup');await page.locator('#health-back').click();await settle()}
  await page.locator('[data-setup=Strength]').click();await settle();const weightTop=await page.locator('.workout-weight').evaluate(n=>n.getBoundingClientRect().top);await page.locator('.setup-segments label').last().click();await settle();assert(Math.abs(await page.locator('.workout-weight').evaluate(n=>n.getBoundingClientRect().top)-weightTop)<1,'Changing the goal must not shift the setup rows');await page.locator('#target-minutes').fill('45');await shot('setup-time');
  await page.locator('.workout-primary').click();await page.waitForFunction(()=>Boolean(Health.state.active));await shot('live');
  assert.equal(await page.evaluate(()=>Health.state.active.targetMs),2700000);assert.equal(await page.evaluate(()=>Health.state.active.weightKg),75);
  assert(await page.evaluate(()=>document.querySelector('#health-content .session-actions').getBoundingClientRect().bottom<=document.querySelector('#health-scroll').getBoundingClientRect().bottom),'Actions must remain inside the native inset viewport');
  await page.locator('#health-content [data-session=pause]').click();await settle();assert.equal(await page.evaluate(()=>Health.state.active.resumedAt),null);await page.locator('#health-content [data-session=resume]').click();await page.locator('#health-content [data-session=finish]').click();await settle();
  assert.equal(await page.evaluate(()=>Health.state.history.length),8);await page.locator('#health-back').click();await settle();assert.equal(await page.locator('.workout-tabs [aria-pressed=true]').innerText(),'History');
  await page.emulateMedia({forcedColors:'active'});assert.notEqual(await page.locator('.workout-tabs [aria-pressed=true]').evaluate(n=>getComputedStyle(n).outlineStyle),'none');
  assert.deepEqual(errors,[]);results.push({width,status:'PASS',checks:'Train grid, rapid navigation identity, selected date return, week bounds, seconds, all activities, profile, target, pause/resume/save, contrast, overflow'});console.log(width+'px PASS');await context.close();
 }}finally{await browser.close()}fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(results,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
