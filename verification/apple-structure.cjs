// Local browser only. Real interactions against the app; Samsung fixtures never enter the build.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import/apple-structure');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch({headless:true});try{
for(const width of [390,320])for(const populated of [true,false]){
 const context=await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true}),p=await context.newPage(),errors=[];
 p.on('pageerror',e=>errors.push(e.message));
 await p.addInitScript(({data,populated})=>{window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:populated,status:populated?'Samsung Health connected':'Connect Samsung Health',data:known==='1'?null:populated?data:{schema:1,date:data.date,rows:[],workouts:[],meta:{}}}),load(){},connect(){},sync(){}}},{data:require('./samsung-import.cjs').fixture(),populated});
 await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');const settle=()=>p.waitForTimeout(500);await settle();
 async function shot(name){await settle();await p.screenshot({path:path.join(out,`${populated?'records':'empty'}-${name}-${width}.png`)});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'No horizontal viewport overflow');}
 async function nav(name){await p.locator('#live-bar').tap();await settle();await p.locator(`[data-activity=${name}]`).tap();await settle();assert.equal(await p.evaluate(()=>Health.page),name);assert.equal(await p.locator(`[data-activity=${name}]`).getAttribute('aria-current'),'page');}
 assert(await p.locator('#back').isHidden());const title=await p.locator('.masthead h1').boundingBox(),gear=await p.locator('#settings-open').boundingBox();assert(Math.abs(title.y-gear.y)<16,'Home settings shares the title row');await shot('home');
 await nav('overview');assert.equal(await p.locator('.health-reading').count(),5);assert.equal(await p.locator('.health-destinations').count(),0);assert.match(await p.locator('[data-home-metric=intake]').innerText(),populated?/550/:/—/);await shot('health');
 for(const destination of ['body','sleep']){await p.locator(`[data-open=${destination}]`).tap();await settle();assert.equal(await p.locator('#health-back').getAttribute('aria-label'),'Back to Health');assert(await p.locator('#live-bar').isVisible());await shot(destination);await p.locator('#health-back').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),'overview');}
 await p.locator('[data-home-metric=heart]').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),null);assert.equal(await p.locator('.masthead h1').innerText(),'Heart rate');assert(await p.evaluate(()=>deckExpanded));await shot('heart');
 await nav('overview');await p.locator('[data-home-metric=intake]').tap();await shot('nutrition');assert.equal(await p.evaluate(()=>metric),'intake');
 await nav('overview');await p.locator('.source-link').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),'settings');
 assert.equal(await p.locator('#health-content>*').first().getAttribute('id'),'profile-form');
 assert.equal(await p.locator('.settings-health').evaluate(n=>n.open),!populated,'Unconnected setup is disclosed; connected details recede');
 await p.locator('#profile-name').fill('Local profile');await p.locator('#profile-weight').fill('75');await p.locator('#profile-form button').tap();assert.equal(await p.evaluate(()=>OrbitSettings.profile().weightKg),75);await shot('settings');
 await p.locator('#settings-goal').fill('123');await p.locator('#settings-goal-form button').tap();assert.match(await p.locator('#settings-goal-error').innerText(),/100/);await p.locator('#settings-goal').fill('9000');await p.locator('#settings-goal-form button').tap();assert.equal(await p.evaluate(()=>goal),9000);
 await p.locator('#health-scroll').evaluate(n=>n.scrollTop=n.scrollHeight);await settle();assert.equal(await p.locator('.health-head-frost').evaluate(n=>getComputedStyle(n).opacity),'1');await shot('settings-details');
 const bottom=await p.locator('.settings-section:last-child').boundingBox(),bar=await p.locator('#live-bar').boundingBox();assert(bottom.y+bottom.height<=bar.y,'Last settings row scrolls fully above navigation');
 await p.locator('#health-back').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),'overview');
 await nav('workouts');await shot('train');await p.locator('.workout-tabs [data-workout-tab=history]').tap();await shot('history');
 if(populated){await p.locator('[data-workout-detail]').tap();await shot('record');assert.match(await p.locator('#workout-record-body').innerText(),/Samsung Health/);await p.locator('#health-back').tap();await settle();}
 await p.locator('.workout-tabs [data-workout-tab=train]').tap();await p.locator('[data-setup=Walking]').tap();await shot('setup');assert(await p.locator('#live-bar').isHidden(),'Focused setup uses contextual actions');assert.equal(await p.locator('#workout-weight').count(),0);
 await p.locator('#workout-track').uncheck();await p.locator('.workout-primary').tap();await p.waitForTimeout(200);assert(await p.locator('.workout-countdown').isVisible());await shot('countdown');await p.waitForTimeout(3200);await shot('active');assert.equal(await p.evaluate(()=>Health.state.active.weightKg),75);assert(await p.locator('#live-bar').isHidden());
 await p.locator('#health-content [data-session=pause]').tap();await shot('paused');await p.locator('#health-back').tap();await settle();
 await nav('body');assert(await p.locator('#live-timer').isVisible());await p.locator('#live-bar').tap();await settle();assert(await p.locator('#live-open-workout').isVisible());await p.locator('#live-open-workout').tap();await settle();assert.equal(await p.evaluate(()=>Health.page),'workouts');
 await p.locator('#health-content [data-session=finish]').tap();await shot('saved');assert(await p.locator('#live-bar').isVisible(),'Finished reading returns persistent navigation');
 await nav('body');if(populated)assert.equal(await p.locator('#body-scrub').inputValue(),await p.locator('#body-scrub').getAttribute('max'),'Navigation release must not scrub the newly revealed chart');
 const cdp=await context.newCDPSession(p),r=await p.locator('#live-bar').boundingBox(),x=r.x+r.width/2,y=r.y+r.height/2;
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await p.waitForTimeout(350);for(let i=1;i<=10;i++){await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:y-i*13}]});await p.waitForTimeout(16)}await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await settle();assert(await p.evaluate(()=>islands.live.open),'Explore drag commits above a detail page');await shot('explore');
 await p.keyboard.press('Escape');await settle();assert(!await p.evaluate(()=>islands.live.open));assert.equal(await p.evaluate(()=>Health.page),'body');
 await p.locator('#live-bar').focus();await p.keyboard.press('ArrowUp');await settle();await p.locator('[data-activity=overview]').focus();await p.keyboard.press('Enter');await settle();assert.equal(await p.evaluate(()=>Health.page),'overview');
 await p.locator('.source-link').tap();await settle();await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);await p.emulateMedia({reducedMotion:'reduce',forcedColors:'active'});await p.locator('#profile-name').focus();assert.notEqual(await p.locator('#profile-name').evaluate(n=>getComputedStyle(n).outlineStyle),'none');await shot('contrast');await p.emulateMedia({reducedMotion:'no-preference',forcedColors:'none'});
 await p.addStyleTag({content:'.settings-profile,.settings-section{font-size:20px}.settings-fields input,.settings-fields label,.settings-measurements label,.settings-section h2{font-size:20px!important}'});await shot('large-type');
 assert.deepEqual(errors,[]);console.log(width,populated?'records':'empty','PASS: reading routes, nested return, persistent launcher, tasks, settings, empty states, keyboard and contrast');
 await context.close();
}
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
