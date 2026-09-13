// Local browser regression: authoritative steps, preserved gesture nodes and a suspended countdown.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),path=require('node:path');
const {fixture}=require('./samsung-import.cjs');
(async()=>{const browser=await chromium.launch({headless:true});
try{for(const width of [390,320]){
 const page=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(data=>{
  localStorage.setItem('orbit-profile-v1',JSON.stringify({name:'Local test',birthDate:'',heightCm:180,weightKg:85}));
  window.direct=null;window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',data:known==='1'?null:data,status:'Samsung Health imported',available:true,permitted:true,live:{connected:true,status:'Live from Samsung Health',reading:window.direct}}),load(){},connectLive(){}};
 },JSON.parse(JSON.stringify(fixture())));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await page.waitForTimeout(300);
 await page.evaluate(()=>{
  const date=HealthData.today(),start=new Date(date+'T00:00:00').getTime();window.direct={source:'com.sec.android.app.shealth',date,steps:8430,hours:[{start,end:start+3600000,value:8430}],at:Date.now()};HealthData.refresh();
 });
 await page.waitForFunction(()=>document.querySelector('#steps').textContent==='8,430');
 assert.equal(await page.evaluate(()=>HealthData.daily(HealthData.today()).steps),8430,'Combined total replaces imported steps');
 await page.evaluate(()=>Health.open('settings'));await page.locator('#profile-name').fill('Unsaved typing');
 await page.evaluate(()=>{window.draft=document.querySelector('#profile-name');direct.steps+=10;direct.hours[0].value+=10;HealthData.refresh()});await page.waitForTimeout(350);
 assert.equal(await page.locator('#profile-name').inputValue(),'Unsaved typing');assert(await page.evaluate(()=>draft===document.querySelector('#profile-name')));
 await page.evaluate(()=>{direct.steps=-1;HealthData.refresh()});assert.equal(await page.evaluate(()=>HealthData.daily(HealthData.today()).steps),8440,'Invalid update preserves the last valid reading');
 await page.evaluate(()=>{direct.steps=8440;direct.date=HealthData.date(Date.now()-86400000);HealthData.refresh()});
 assert.equal(await page.evaluate(()=>HealthData.daily(HealthData.today()).steps),8420,'Yesterday is not applied to today');
 await page.evaluate(()=>Health.open('sleep'));await page.waitForTimeout(300);await page.locator('[data-night-stage=light]').tap();await page.waitForTimeout(250);
 assert(await page.locator('[data-night-segment]').evaluateAll(ns=>ns.every(n=>n.getAttribute('opacity')==='1')));
 await page.screenshot({path:path.join(__dirname,'samsung-import',`sleep-reworked-${width}.png`)});
 await page.evaluate(()=>Health.open('workouts'));await page.locator('[data-setup=Walking]').click();
 assert.equal(await page.locator('#workout-weight').count(),0);assert.equal(await page.locator('.workout-setup [data-music-connect]').count(),0);
 await page.locator('#workout-track').uncheck();await page.locator('.workout-primary').click();
 const session=await page.evaluate(()=>Health.state.active);assert.equal(session.weightKg,85);assert(session.startedAt>Date.now()-1000);
 const cdp=await page.context().newCDPSession(page);await cdp.send('Page.setWebLifecycleState',{state:'frozen'});
 await new Promise(r=>setTimeout(r,4400));await cdp.send('Page.setWebLifecycleState',{state:'active'});
 await page.waitForSelector('.workout-live');assert(await page.evaluate(()=>Health.elapsed(Health.state.active)>=1000),'Countdown survived JavaScript suspension');
 await page.evaluate(()=>{
  window.gps=structuredClone(Health.state);gps.active.trackLocation=true;gps.active.metrics={state:'tracking',distanceM:0,maxSpeedMps:0,speedMps:0,altitudeMinM:null,altitudeMaxM:null,accuracyM:4,points:[{lat:51,lon:0,elapsedMs:0,altitudeM:null,speedMps:0,breakBefore:true}]};window.gpsRevision=1;
  window.OrbitWorkouts={snapshot:known=>JSON.stringify({realDataMode:true,empty:false,revision:String(gpsRevision),store:known===String(gpsRevision)?null:JSON.stringify(gps),startedAt:gps.active.startedAt,elapsedMs:10000,totalMs:10000})};Health.refresh();Health.render();
 });
 await page.locator('[data-workout-detail=active]').click();await page.waitForTimeout(250);
 const retained=await page.evaluate(async()=>{
  const nav=document.querySelector('.workout-chart-tabs'),button=nav.querySelector('[data-workout-chart=speed]'),track=Health.tracks.get('workout-chart'),wait=ms=>new Promise(r=>setTimeout(r,ms));
  for(let i=0;i<20;i++){
   gps.active.metrics.distanceM=i*4;gps.active.metrics.points.push({lat:51+i*.00001,lon:i*.00001,elapsedMs:1000+i*1000,altitudeM:null,speedMps:1.3,breakBefore:false});gpsRevision++;Health.refresh();
   nav.querySelector(`[data-workout-chart=${['speed','altitude','route'][i%3]}]`).click();await wait(25);
   if(nav!==document.querySelector('.workout-chart-tabs')||button!==nav.querySelector('[data-workout-chart=speed]')||track!==Health.tracks.get('workout-chart'))return false;
  }return true;
 });assert(retained,'GPS updates and chart switches must preserve navigation and its drag controller');
 await page.locator('#health-back').click();await page.waitForTimeout(300);
 const controls=await page.evaluate(()=>{window.controls=document.querySelector('.session-actions');window.metrics=document.querySelector('#live-workout-metrics');gpsRevision++;Health.refresh();return controls===document.querySelector('.session-actions')&&metrics===document.querySelector('#live-workout-metrics')});assert(controls);
 await page.screenshot({path:path.join(__dirname,'samsung-import',`workout-stable-${width}.png`)});
 assert.deepEqual(errors,[]);console.log(width,'PASS: +10 replacement, bad source data, day boundary, draft, sleep, profile, frozen countdown, GPS updates and retained navbar');await page.close();
}}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
