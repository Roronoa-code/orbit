// Real rendered workout flow. Each viewport owns isolated storage; no phone data is used.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'player-settings-20260912','workout-rework');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({headless:true}),results=[];
 try{for(const width of [390,384,320]){
  const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true}),page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.clock.setFixedTime(new Date('2026-09-13T12:00:00Z'));
  await page.addInitScript(()=>{
   const kinds=['Running','Strength','Walking','Cycling'],today=new Date();today.setHours(9,0,0,0);
   const history=[0,1,2,4,8,10,17].map((ago,i)=>{const d=new Date(today);d.setDate(d.getDate()-ago);const startedAt=d.getTime(),elapsed=[2542000,2100000,1320000,3030000,14e3,1800000,2400000][i];return {kind:kinds[i%4],startedAt,endedAt:startedAt+elapsed+90000,elapsed,totalMs:elapsed+90000,weightKg:75,targetMs:0,trackLocation:false}});
   history[0].metrics={state:'finished',distanceM:6250,maxSpeedMps:3.5,points:[{lat:51.5,lon:-.1,elapsedMs:1000,breakBefore:false}]};
   localStorage.setItem('orbit-workouts-v2',JSON.stringify({active:null,history}));localStorage.setItem('orbit-profile-v1',JSON.stringify({name:'',birthDate:'',heightCm:180,weightKg:75}));
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
  const layout=await page.evaluate(()=>{const rect=n=>{const r=n.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}};return {metrics:[...document.querySelectorAll('.history-week-metrics p')].map(rect),days:[...document.querySelectorAll('[data-week-day]')].map(rect),summary:rect(document.querySelector('.history-overview')),session:rect(document.querySelector('#history-sessions .history-session')),indicator:rect(document.querySelector('.history-days>.selection-pill'))}});
  assert(Math.abs(layout.metrics[0].width-layout.metrics[1].width)<1,'Weekly metrics have equal columns');assert.equal(layout.metrics[0].y,layout.metrics[1].y);
  assert(layout.days.every(r=>Math.abs(r.width-layout.days[0].width)<1&&r.y===layout.days[0].y),'Seven dates align evenly');
  assert(layout.days.every(r=>Math.abs(r.width-r.height)<1),'Dates are circles, not tall capsules');assert.equal(await page.locator('.history-day-bar').count(),0);assert.equal(await page.locator('.history-days .has-records').count(),4);
  const selectedDate=layout.days[6];assert(Math.abs(layout.indicator.width-selectedDate.width)<1&&Math.abs(layout.indicator.y-selectedDate.y)<1,'Moving selection fits the date circle');
  const readings=await page.locator('#history-sessions .history-session-metrics>span').evaluateAll(ns=>ns.map(n=>{const r=n.getBoundingClientRect();return {y:r.y,width:r.width,text:n.innerText,overflow:n.scrollWidth>n.clientWidth}}));
  assert.equal(readings.length,3);assert(readings.every(r=>r.y===readings[0].y&&Math.abs(r.width-readings[0].width)<1&&!r.overflow),'Duration, distance and energy align without overflow');
  assert(layout.summary.height<280&&layout.session.bottom<750,'Overview and first workout stay in view');assert(!await overflow());
  const cdp=await context.newCDPSession(page);
  async function dragDay(from,to){const a=await page.locator(`[data-week-day="${from}"]`).boundingBox(),b=await page.locator(`[data-week-day="${to}"]`).boundingBox(),y=a.y+a.height/2,x=a.x+a.width/2,dx=b.x+b.width/2-x;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await page.waitForTimeout(450);for(let i=1;i<=12;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*i/12,y}]});await page.waitForTimeout(16)}await page.waitForTimeout(130);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})}
  await page.evaluate(()=>window.testDays=document.querySelector('.history-days'));await dragDay(6,0);assert.equal(await page.locator('[data-week-day="0"]').getAttribute('aria-pressed'),'true');await dragDay(0,6);assert.equal(await page.locator('[data-week-day="6"]').getAttribute('aria-pressed'),'true');
  for(const day of [2,5,1,6]){await page.locator(`[data-week-day="${day}"]`).evaluate(n=>n.click());await page.waitForTimeout(65)}
  await page.waitForTimeout(500);assert(await page.evaluate(()=>testDays===document.querySelector('.history-days')),'Day changes retain the same moving circle');assert.equal(await page.locator('[data-week-day="6"]').getAttribute('aria-pressed'),'true');
  await page.locator('[data-history-week="-1"]').click();await settle();const previous=await page.locator('.history-week-nav p').innerText(),shortDay=await page.evaluate(()=>{const d=new Date();d.setDate(d.getDate()-8);return (d.getDay()+6)%7});await page.locator(`[data-week-day="${shortDay}"]`).click();await settle();
  assert.match(await page.locator('#history-sessions').innerText(),/14s/);await page.locator('#history-sessions [data-workout-detail]').click();await shot('record');await page.locator('.energy-method summary').click();await settle();assert(await page.locator('.energy-method').evaluate(n=>n.open));await page.locator('.energy-method summary').click();await settle();assert.match(await page.locator('#workout-record-body').innerText(),/Active|Paused|Energy/);
  await page.locator('#health-back').click();await settle();assert.equal(await page.locator('.history-week-nav p').innerText(),previous);assert.equal(await page.locator(`[data-week-day="${shortDay}"]`).getAttribute('aria-pressed'),'true');
  await page.locator('[data-history-week="1"]').click();await settle();assert(await page.locator('[data-history-week="1"]').isDisabled());assert(await page.evaluate(()=>document.activeElement.hasAttribute('data-week-day')));
  await page.locator('.workout-tabs [data-workout-tab=train]').click();await settle();
  for(const kind of ['Walking','Running','Cycling','Strength']){await page.locator(`[data-setup="${kind}"]`).click();await settle();assert.equal(await page.locator('#workout-weight').count(),0);assert.equal(await page.locator('.workout-setup [data-music-connect]').count(),0);assert.equal(await page.locator('#workout-track').count(),kind==='Strength'?0:1);assert(!await overflow());if(kind==='Running')await shot('setup');await page.locator('#health-back').click();await settle()}
  await page.locator('[data-setup=Strength]').click();await settle();const weightTop=await page.locator('.setup-help').evaluate(n=>n.getBoundingClientRect().top);await page.locator('.setup-segments label').last().click();await settle();assert(Math.abs(await page.locator('.setup-help').evaluate(n=>n.getBoundingClientRect().top)-weightTop)<1,'Changing the goal must not shift the setup rows');await page.locator('#target-minutes').fill('45');await shot('setup-time');
  await page.locator('.workout-primary').click();await page.waitForSelector('.workout-live');await shot('live');
  assert.equal(await page.evaluate(()=>Health.state.active.targetMs),2700000);assert.equal(await page.evaluate(()=>Health.state.active.weightKg),75);
  assert(await page.evaluate(()=>document.querySelector('#health-content .session-actions').getBoundingClientRect().bottom<=document.querySelector('#health-scroll').getBoundingClientRect().bottom),'Actions must remain inside the native inset viewport');
  await page.locator('#health-content [data-session=pause]').click();await settle();assert.equal(await page.evaluate(()=>Health.state.active.resumedAt),null);await page.locator('#health-content [data-session=resume]').click();await page.locator('#health-content [data-session=finish]').click();await settle();
  assert.equal(await page.evaluate(()=>Health.state.history.length),8);await page.locator('#health-back').click();await settle();assert.equal(await page.locator('.workout-tabs [aria-pressed=true]').innerText(),'History');
  await page.emulateMedia({forcedColors:'active'});assert.notEqual(await page.locator('.workout-tabs [aria-pressed=true]').evaluate(n=>getComputedStyle(n).outlineStyle),'none');
  assert.deepEqual(errors,[]);results.push({width,status:'PASS',checks:'Train grid, rapid navigation identity, selected date return, week bounds, seconds, all activities, profile, target, pause/resume/save, contrast, overflow'});console.log(width+'px PASS');await context.close();
 }}finally{await browser.close()}fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify(results,null,2));
})().catch(e=>{console.error(e);process.exitCode=1});
