// Local-only regression for the fourteen reading/hold screenshots. Never connects to a phone.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import/quiet-readings');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({headless:true});try{
 for(const width of [390,384,320]){
  const p=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];
  p.on('pageerror',e=>errors.push(e.message));
  await p.addInitScript(data=>{
   window.fixture=data;window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:true,data:known==='1'?null:data}),load(){}};
   const today=new Date();today.setHours(9,0,0,0);
   const history=[0,1,9].map(ago=>{const startedAt=+today-ago*86400000;return {kind:'Walking',startedAt,endedAt:startedAt+120000,elapsed:120000,totalMs:120000,weightKg:75,targetMs:0,trackLocation:false}});
   localStorage.setItem('orbit-workouts-v2',JSON.stringify({active:null,history}));
  },require('./sleep-apple.cjs').dense());
  await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
  await p.addStyleTag({content:':root{--safe-top:34px;--safe-bottom:8px}'});
  const settle=()=>p.waitForTimeout(450),shot=async name=>{await settle();await p.screenshot({path:path.join(out,`${name}-${width}.png`)});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));};
  const cdp=await p.context().newCDPSession(p),touch=(type,point)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:point?[point]:[]});
  const point=async selector=>{await p.locator(selector).evaluate(n=>n.scrollIntoView({block:"center"}));const r=await p.locator(selector).boundingBox();return {x:r.x+r.width/2,y:r.y+r.height/2};};
  async function quiet(selector,host=selector){await touch('touchStart',await point(selector));await p.waitForTimeout(400);assert.equal(await p.locator(host+' .glass-response-surface').count(),0,selector+' has no held glass');assert.equal(await p.locator(host+' .glass-shared-light').count(),0);await touch('touchCancel');await settle();}
  async function bottomClear(selector){await p.locator('#health-scroll').evaluate(n=>n.scrollTop=n.scrollHeight);await settle();const r=await p.locator(selector).boundingBox(),bar=await p.locator('#live-bar').boundingBox();assert(r.y+r.height<=bar.y-8,selector+' scrolls above Explore');}
  await p.locator('#live-bar').tap();await settle();await p.locator('[data-activity=workouts]').tap();await settle();
  assert.equal(await p.locator('#health-back').evaluate(n=>getComputedStyle(n).outlineStyle),'none','Touch navigation leaves no keyboard ring');await p.keyboard.press('Tab');await p.locator('#health-back').focus();assert.notEqual(await p.locator('#health-back').evaluate(n=>getComputedStyle(n).outlineStyle),'none','Keyboard focus stays visible');
  for(const kind of ['Walking','Running','Cycling','Strength'])await quiet(`[data-setup=${kind}]`);
  await quiet('.workout-week-link');await quiet('.workout-last .history-session');await bottomClear('.workout-last');await shot('train-bottom');
  await p.locator('.workout-tabs [data-workout-tab=history]').tap();await settle();
  await quiet('.history-session');await quiet('[data-history-week="-1"]');await p.locator('[data-history-week="-1"]').tap();await settle();await quiet('[data-history-week="1"]');await p.locator('[data-history-week="1"]').tap();await settle();
  await quiet('[data-week-day][aria-pressed=true]','.history-days');assert.equal(await p.locator('.history-days.liquid-surface').count(),0);
  assert.equal(await p.locator('.history-days').evaluate(n=>getComputedStyle(n).backdropFilter),'none');await shot('history');
  await p.locator('#history-sessions .history-session').first().tap();await settle();await quiet('.energy-method summary');await p.locator('.energy-method summary').tap();await settle();
  await bottomClear('.workout-source-note');await shot('record-bottom');
  await p.evaluate(()=>Health.open('overview'));await settle();
  assert.equal(await p.locator('.health-reading').count(),6);for(const selector of ['[data-home-metric=steps]','.source-link','.oxygen-tile summary'])await quiet(selector);
  assert.equal(await p.locator('.health-library .reading-chevron,.history-arrow,.workout-kind-arrow').count(),0);
  assert.equal(await p.locator('.health-library').evaluate(n=>getComputedStyle(n).gridTemplateColumns.split(' ').length),2);
  // A held card compresses immediately; cancellation/regrab retargets the same CSS transition.
  const card=p.locator('.reading-sleep'),other=p.locator('.reading-body'),contact=await point('.reading-sleep'),layout=await other.boundingBox(),origin=await card.boundingBox();
  await touch('touchStart',contact);await p.waitForTimeout(130);const pressed=await card.boundingBox();assert(pressed.width<origin.width*.98&&pressed.width>origin.width*.94);assert.deepEqual(await other.boundingBox(),layout);
  await p.screenshot({path:path.join(out,`health-held-${width}.png`)});await touch('touchCancel');await p.waitForTimeout(70);const returning=await card.boundingBox();assert(returning.width>pressed.width&&returning.width<origin.width);
  await touch('touchStart',await point('.reading-sleep'));await p.waitForTimeout(25);assert((await card.boundingBox()).width<returning.width+.1);await touch('touchCancel');await settle();assert(Math.abs((await card.boundingBox()).width-origin.width)<.1);
  await shot('health');await p.locator('.oxygen-tile summary').tap();await settle();
  // Shared disclosures reverse without opacity resets or a padding snap at either endpoint.
  const continuity=await p.evaluate(async()=>{const d=document.querySelector('.oxygen-tile'),body=d.querySelector('.details-body'),wait=ms=>new Promise(r=>setTimeout(r,ms));SurfaceMotion.toggleDetails(d);await wait(65);const before=body.getBoundingClientRect().height;SurfaceMotion.toggleDetails(d);const after=body.getBoundingClientRect().height,opacity=getComputedStyle(body).opacity;await wait(290);return {before,after,opacity,open:d.open,height:body.getBoundingClientRect().height,scroll:body.scrollHeight}});
  assert(Math.abs(continuity.before-continuity.after)<1);assert.equal(continuity.opacity,'1');assert(continuity.open);assert(Math.abs(continuity.height-continuity.scroll)<1);
  assert.equal(await p.locator('[data-oxygen-point]').count(),7);assert.equal(await p.locator('.oxygen-history,.oxygen-week').count(),0);
  assert((await p.locator('.oxygen-tile .details-body').boundingBox()).height<220,'Oxygen history stays compact');
  await quiet('#oxygen-scrub','.oxygen-chart');await p.locator('#oxygen-scrub').focus();await p.keyboard.press('Home');assert.equal(await p.locator('#oxygen-scrub').inputValue(),'0');assert.match(await p.locator('#oxygen-scrub').getAttribute('aria-valuetext'),/98%/);await p.keyboard.press('End');assert.equal(await p.locator('#oxygen-scrub').inputValue(),'6');await p.locator('#oxygen-scrub').evaluate(n=>n.blur());
  await bottomClear('.source-link');await shot('oxygen');
  await p.evaluate(()=>{const data=structuredClone(fixture),gap=dayOffset(data.date,-3);data.rows=data.rows.filter(r=>r.id!=='o-'+gap);data.rows.find(r=>r.id==='o-'+data.date).value=85;HealthData.accept(data);Health.render()});await p.locator('.oxygen-tile summary').tap();await settle();
  assert.equal(await p.locator('#oxygen-value').innerText(),'85');assert.equal(await p.locator('[data-oxygen-point="3"]').count(),0,'Missing days remain gaps');
  assert.equal(await p.locator('.oxygen-chart path[stroke-linejoin]').getAttribute('d').then(d=>(d.match(/M/g)||[]).length),2,'A missing day breaks the connecting line');
  await p.locator('#oxygen-scrub').focus();await p.keyboard.press('Home');for(let i=0;i<3;i++)await p.keyboard.press('ArrowRight');assert.match(await p.locator('#oxygen-scrub').getAttribute('aria-valuetext'),/Not recorded/);assert.equal(await p.locator('#oxygen-value').innerText(),'—');
  await p.evaluate(()=>{HealthData.accept(fixture);Health.render()});
  await p.evaluate(()=>Health.open('sleep'));await settle();
  assert.equal(await p.locator('.sleep-date-nav').count(),1);assert.equal(await p.locator('.night-view-label').count(),0);assert(!await p.locator('#health-content').innerText().then(t=>t.includes('Hold the chart')));
  const summary=await p.locator('.sleep-summary').boundingBox(),chart=await p.locator('.night-chart').boundingBox();assert(summary.height<175);assert(chart.y<360,'One compact summary sits above the chart');
  await quiet('[data-night-date="-1"]','.sleep-summary');await shot('sleep');
  const chartTop=()=>p.locator('.night-chart').evaluate(n=>n.getBoundingClientRect().top+document.querySelector('#health-scroll').scrollTop);
  const before=await chartTop();await p.locator('[data-night-stage=deep]').tap();assert.equal(before,await chartTop(),'Inspection never shifts chart within scrolling content');
  await p.evaluate(()=>setLiveOpen(true));await settle();
  for(const open of [true,false,true,false]){await p.evaluate(value=>setLiveOpen(value),open);await settle();assert(await p.locator('.island-rim rect').evaluate(n=>{const r=n.getBoundingClientRect(),s=getComputedStyle(n);return s.vectorEffect==='non-scaling-stroke'&&s.strokeWidth==='1px'&&r.height>=60&&s.stroke!=='none';}));}
  await shot('explore-rim');
  await p.evaluate(()=>{HealthData.accept({schema:1,date:fixture.date,rows:[],workouts:[],meta:{}});Health.open('overview')});await p.locator('.oxygen-tile summary').tap();await settle();assert.equal(await p.locator('.oxygen-history').count(),0);assert.match(await p.locator('.oxygen-tile').innerText(),/No blood oxygen readings/);await shot('empty-health');
  await p.evaluate(()=>Health.open('sleep'));assert(await p.locator('.sleep-empty').isVisible());await shot('empty-sleep');
  assert.deepEqual(errors,[]);console.log(width,'PASS: quiet content holds, date lens removal, workout bottom clearance, oxygen layout/data, sleep grouping, steady Explore rim and empty states');await p.close();
 }
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
