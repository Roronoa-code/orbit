// Desktop-only regression for the six interaction follow-ups. Never connects to a phone.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'player-settings-20260912','interaction-followup');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{for(const width of [390,384,320]){
  const context=await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true}),p=await context.newPage(),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(()=>{window.haptics=[];window.OrbitFeedback={pulse:kind=>haptics.push(kind)}});
  await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.waitForTimeout(700);
  const cdp=await context.newCDPSession(p);
  async function drag(selector,dx,dy=0,hold=0){const r=await p.locator(selector).boundingBox(),x=r.x+r.width/2,y=r.y+r.height/2;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});if(hold)await p.waitForTimeout(hold);for(let i=1;i<=12;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*i/12,y:y+dy*i/12}]});await p.waitForTimeout(16)}await p.waitForTimeout(130);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await p.waitForTimeout(500)}
  for(const active of [false,true]){
   if(active)await p.evaluate(()=>Health.action('start','Strength'));
   await p.evaluate(()=>setDeckExpanded(true));await p.waitForTimeout(900);await p.evaluate(()=>deckScroller.scrollTop=160);
   await p.locator('#live-bar').tap();await p.waitForTimeout(700);
   assert.deepEqual(await p.evaluate(()=>[deckExpanded,islands.live.open]),[true,true]);
   const body=await p.locator('#live-choices').boundingBox();await drag('#live-choices',0,Math.min(180,body.height+15));assert.deepEqual(await p.evaluate(()=>[deckExpanded,islands.live.open]),[true,false]);
   await drag('#live-bar',0,-190);assert.deepEqual(await p.evaluate(()=>[deckExpanded,islands.live.open]),[true,true]);
   await p.locator('#live-bar').tap();if(active)await p.evaluate(()=>Health.action('finish'));
  }
  assert(await p.evaluate(()=>haptics.length>=6),'Navigation and bar feedback must reach the native bridge');
  await p.evaluate(()=>{setDeckExpanded(false);Health.open('workouts')});await p.waitForTimeout(500);
  await drag('.workout-start-title',-110);assert.equal(await p.locator('.workout-tabs [data-workout-tab=history]').getAttribute('aria-pressed'),'true');
  const days=await p.locator('[data-week-day]:not(:disabled)').all();if(days.length>1){await days.at(-1).tap();await p.waitForTimeout(400);const a=await days.at(-1).boundingBox(),b=await days[0].boundingBox();await drag('[data-week-day][aria-pressed=true]',b.x-a.x,0,450);assert.equal(await days[0].getAttribute('aria-pressed'),'true',JSON.stringify(await p.locator('[data-week-day]').evaluateAll(ns=>ns.map(n=>[n.dataset.weekDay,n.getAttribute('aria-pressed')]))))}
  await p.locator('[data-workout-tab=train]').tap();await p.locator('[data-setup=Running]').tap();await p.locator('.setup-segments label').last().tap();await p.locator('#target-minutes').tap();
  assert.equal(await p.evaluate(()=>document.activeElement.id),'target-minutes');await p.locator('#target-minutes').fill('45');assert.equal(await p.locator('#target-minutes').inputValue(),'45');
  assert(await p.locator('#target-minutes').evaluate(n=>{const e=new MouseEvent('contextmenu',{bubbles:true,cancelable:true});return !n.dispatchEvent(e)}));
  await p.locator('#target-minutes').blur();await drag('.setup-activity',100);assert(await p.locator('.workout-tabs').isVisible(),'Swipe back exits setup');
  await p.evaluate(()=>Health.open('sleep'));await p.waitForTimeout(500);
  const first=await p.locator('[data-night-stage=awake]').boundingBox(),last=await p.locator('[data-night-stage=deep]').boundingBox();
  await p.locator('[data-night-stage=awake]').tap();await p.waitForTimeout(350);await drag('[data-night-stage=awake]',last.x-first.x,0,450);assert.equal(await p.locator('[data-night-stage=deep]').getAttribute('aria-pressed'),'true');
  await p.screenshot({path:path.join(out,`sleep-${width}.png`)});
  await p.evaluate(()=>Health.close());await p.waitForTimeout(500);await p.screenshot({path:path.join(out,`home-${width}.png`)});
  const filters=await p.evaluate(()=>[getComputedStyle(document.querySelector('.stack-motion')).backdropFilter,getComputedStyle(document.querySelector('#settings-open')).backdropFilter,getComputedStyle(document.querySelector('#live-island .island-frost')).backdropFilter]);
  assert(filters.every(f=>f.includes('blur(8px)')));assert(!filters[2].includes('url('),'Large moving launcher must avoid the displacement pass');
  await p.emulateMedia({forcedColors:'active'});assert.equal(await p.locator('.stack-motion').first().evaluate(n=>getComputedStyle(n).backdropFilter),'none');
  assert.deepEqual(errors,[]);console.log(width,'PASS: bar over cards, haptics, page swipes, held selectors, compact input and material');await context.close();
 }
 // A monotonic native clock sample is reused by all UI readings. State changes remain authoritative.
 const p=await browser.newPage({viewport:{width:384,height:844}});
 await p.addInitScript(()=>{
  window.samples=0;window.revision='1';window.bad=false;window.nativeState={active:{kind:'Running',startedAt:1000,elapsed:30000,resumedAt:1000,targetMs:60000},history:[]};
  window.OrbitWorkouts={snapshot(known){samples++;return bad===true?'{}':JSON.stringify({revision,store:known===revision?null:JSON.stringify(nativeState),startedAt:bad==='mismatch'?999:nativeState.active?.startedAt??null,elapsedMs:30000,totalMs:60000})}};
 });
 await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.waitForTimeout(100);
 const clock=await p.evaluate(async()=>{const before=samples;for(let i=0;i<100;i++)Health.live();const calls=samples-before;const a=Health.elapsed(Health.state.active);await new Promise(r=>setTimeout(r,160));const b=Health.elapsed(Health.state.active);nativeState.active.resumedAt=null;revision='2';Health.refresh();const paused=Health.elapsed(Health.state.active);await new Promise(r=>setTimeout(r,160));const held=Health.elapsed(Health.state.active);bad=true;Health.refresh();const rejected=!Health.action('start','Walking');return {calls,advance:b-a,paused,held,rejected}});
 assert.equal(clock.calls,0);assert(clock.advance>=140&&clock.advance<400);assert.equal(clock.paused,clock.held);assert(clock.rejected);
 assert(await p.evaluate(()=>{const before=Health.state;bad='mismatch';nativeState.active.kind='Walking';revision='3';Health.refresh();return Health.state===before&&Health.state.active.kind==='Running'}),'An inconsistent clock must not partially replace saved state');
 await p.close();
 for(const reject of [false,true]){
  const migration=await browser.newPage();await migration.addInitScript(reject=>{
   const legacy=JSON.stringify({active:null,history:[{kind:'Walking',startedAt:1000,endedAt:3000,elapsed:2000}]});localStorage.setItem('orbit-workouts-v1',legacy);let raw=null;
   window.writes=0;window.OrbitWorkouts={write(value){writes++;if(reject)return false;raw=value;return true},snapshot(){return JSON.stringify({empty:raw===null,revision:raw?'2':'1',store:raw||'{"active":null,"history":[]}',startedAt:null,elapsedMs:0,totalMs:0})}};
  },reject);await migration.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
  assert.equal(await migration.evaluate(()=>writes),1);assert.equal(await migration.evaluate(()=>Health.state.history.length),reject?0:1);
  if(reject)assert(await migration.evaluate(()=>!Health.action('start','Walking')&&JSON.parse(localStorage.getItem('orbit-workouts-v1')).history.length===1));await migration.close();
 }
 fs.writeFileSync(path.join(out,'checks.json'),JSON.stringify({widths:[390,384,320],status:'PASS',clock,migrationAndFailurePreservation:true,phoneTested:false},null,2));console.log('Native sample and migration boundary PASS',clock);
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
