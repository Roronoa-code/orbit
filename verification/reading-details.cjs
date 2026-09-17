// Local fixtures only: expanded information, sparse records, return navigation and quiet inspection.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import/reading-details');fs.mkdirSync(out,{recursive:true});
function fixture(){
 const data=require('./samsung-import.cjs').fixture(),weights=data.rows.filter(r=>r.type==='weight'),keep=new Set([weights[20].start,weights[22].start]);weights[20].value=76.0;weights[22].value=76.4;
 data.rows=data.rows.filter(r=>!['weight','fat','lean'].includes(r.type)||keep.has(r.start));
 const end=new Date(data.date+'T15:00:00').getTime(),start=end-3600000;
 data.rows.push({id:'afternoon-sleep',type:'sleep',source:'com.sec.android.app.shealth',start,end,stages:[[start,end,4]]});return data;
}
(async()=>{const browser=await chromium.launch();try{for(const width of [390,320]){
 const p=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true,timezoneId:'Europe/London'}),errors=[];p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(data=>{window.fixture=data;window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:true,data:known==='1'?null:data}),load(){}}},fixture());
 await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.waitForFunction(()=>typeof Health!=='undefined');
 const settle=()=>p.waitForTimeout(400),shot=async name=>{await settle();await p.screenshot({path:path.join(out,`${name}-${width}.png`)});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));};
 const cdp=await p.context().newCDPSession(p),touch=(type,point)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:point?[point]:[]});
 async function quiet(selector,host){await p.locator(selector).scrollIntoViewIfNeeded();const r=await p.locator(selector).boundingBox();await touch('touchStart',{x:r.x+r.width/2,y:r.y+r.height/2});await p.waitForTimeout(500);assert.equal(await p.locator(host+' .glass-response-surface,'+host+' .glass-contact-mark').count(),0);await touch('touchCancel');await settle()}
 async function resize(id){await p.locator(`.reading-${id}`).focus();await p.keyboard.press('Shift+F10');await p.keyboard.press('Enter');await settle();await p.keyboard.press('Escape')}
 await p.evaluate(()=>Health.open('overview'));await settle();
 assert.equal(await p.locator('.reading-body .reading-label').innerText(),'Measurements');
 assert.equal(await p.locator('.sleep-bout').count(),2);assert.deepEqual(await p.locator('.sleep-bout-times').allTextContents(),['23:0007:00','14:0015:00']);
 await resize('sleep');assert(!await p.locator('.sleep-windows').isVisible(),'Compact Sleep has no stages');await shot('compact-sleep');await resize('sleep');
 for(const id of ['steps','heart','body','intake'])await resize(id);
 for(const id of ['steps','heart','body','intake','oxygen'])assert(await p.locator(`.reading-${id} .reading-extra`).isVisible(),id+' adds information');
 assert.match(await p.locator('.reading-steps .reading-extra').innerText(),/1,580 steps/);assert.match(await p.locator('.reading-intake .reading-extra').innerText(),/25 g/);
 for(const id of ['sleep','steps','heart','body','intake']){await p.locator(`.reading-${id}`).scrollIntoViewIfNeeded();await shot('wide-'+id)}
 await p.locator('.reading-oxygen').tap();await settle();
 async function oxygenFits(){const b=await p.locator('[data-health-card=oxygen]').boundingBox(),c=await p.locator('.oxygen-chart').boundingBox(),source=await p.locator('.source-link').boundingBox();assert(c.y+c.height<=b.y+b.height+.5,'Chart stays inside oxygen card');assert(source.y>=b.y+b.height,'Source follows the whole card');assert((await p.locator('.reading-oxygen').boundingBox()).height<270,'No stretched summary')}
 await oxygenFits();await resize('oxygen');await oxygenFits();await resize('oxygen');await oxygenFits();await shot('oxygen');
 await p.evaluate(async()=>{const d=document.querySelector('.oxygen-tile');SurfaceMotion.toggleDetails(d);await new Promise(r=>setTimeout(r,70));SurfaceMotion.toggleDetails(d)});await settle();await oxygenFits();
 for(const id of ['steps','heart','intake']){
  await p.locator(`.reading-${id}`).scrollIntoViewIfNeeded();const before=await p.locator('#health-scroll').evaluate(n=>n.scrollTop);
  await p.locator(`.reading-${id}`).tap();await settle();assert.equal(await p.evaluate(()=>Health.page),null);assert(!await p.locator('#back').isDisabled());
  if(id==='steps'){await p.locator('#settings-open').tap();await settle();await p.locator('#health-back').tap();await settle();assert(await p.evaluate(()=>Health.hasMetricReturn),'Opening Settings keeps the reading return path')}
  if(id==='heart'){await p.evaluate(()=>closeInline())}else await p.locator('#back').tap();await settle();
  assert.equal(await p.evaluate(()=>Health.page),'overview');assert(Math.abs(await p.locator('#health-scroll').evaluate(n=>n.scrollTop)-before)<1,'Back restores reading position');assert(await p.locator('.oxygen-tile').evaluate(n=>n.open));
 }
 await p.locator('.reading-body').tap();await settle();await p.locator('.body-timeline').scrollIntoViewIfNeeded();await shot('body');
 assert.equal(await p.locator('#body-guide,#body-halo,[data-body-step]').count(),0);assert.equal(await p.locator('#body-dots circle').count(),2);
 assert(!await p.locator('.body-stats').isVisible(),'Two readings do not repeat average/min/max');assert((await p.locator('.body-timeline').boundingBox()).height<380,'Sparse history stays compact');
 const points=await p.locator('#body-dots circle').evaluateAll(ns=>ns.map(n=>[+n.getAttribute('cx'),+n.getAttribute('cy')]));assert.equal(points[0][0],10);assert.equal(points[1][0],290);assert.notEqual(points[0][1],points[1][1]);
 await quiet('#body-scrub','.body-chart');await p.locator('#body-scrub').focus();await p.keyboard.press('Home');assert.equal(+await p.locator('#body-point').getAttribute('cx'),10);await p.keyboard.press('End');assert.equal(+await p.locator('#body-point').getAttribute('cx'),290);
 for(const range of [90,365,7,30]){await p.locator(`[data-body-range="${range}"]`).tap();await settle();assert.equal(await p.locator('#body-dots circle').count(),range===7?0:2)}
 await quiet('[data-body-range="30"]','.body-timeline');
 for(const field of ['fatMass','muscle','lean','weight']){await p.locator(`[data-body-metric=${field}]`).tap();await settle();assert(!/NaN|undefined|Infinity/.test(await p.locator('.body-timeline').innerText()))}
 await p.locator('#health-back').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),'overview');await p.locator('.reading-sleep').tap();await settle();
 await quiet('[data-night-stage=deep]','.sleep-breakdown');await p.locator('[data-night-stage=deep]').tap();assert.equal(await p.locator('[data-night-stage=deep]').getAttribute('aria-pressed'),'true');await quiet('#night-scrub','.night-chart');await shot('sleep-stages');
 await p.locator('#live-bar').tap();await settle();assert.equal(await p.locator('[data-activity][aria-current=page]').evaluate(n=>getComputedStyle(n).textDecorationLine),'none');
 await p.evaluate(()=>{setLiveOpen(false);const data=structuredClone(fixture);data.rows=data.rows.filter(r=>!['weight','fat','lean'].includes(r.type)||r.id===fixture.rows.find(r=>r.type==='weight').id);HealthData.accept(data);Health.open('body')});await settle();assert(await p.locator('#body-scrub').isDisabled());assert(!await p.locator('.body-chart').isVisible(),'One reading does not create an empty chart');
 await p.evaluate(()=>{HealthData.accept({schema:1,date:fixture.date,rows:[],workouts:[],meta:{}});Health.open('overview')});await settle();assert.equal(await p.locator('.sleep-ribbon').count(),0);assert.equal(await p.locator('.health-reading-value').allTextContents().then(v=>v.filter(t=>t.includes('—')).length),6);await shot('empty');
 assert.deepEqual(errors,[]);console.log(width,'PASS: wide/compact detail, gap timings, oxygen containment, Back state, sparse chart, quiet stages and empty readings');await p.close();
}}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
