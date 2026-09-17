// Local browser only: individual card sizes, real summaries, hold cancellation and persistence.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import/health-cards');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch();try{for(const width of [390,320]){
 const p=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];
 p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(data=>{window.fixture=data;window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:true,data:known==='1'?null:data}),load(){}}},require('./samsung-import.cjs').fixture());
 const open=async()=>{await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.evaluate(()=>Health.open('overview'));await p.waitForTimeout(420)};await open();
 const cdp=await p.context().newCDPSession(p),touch=(type,point)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:point?[point]:[]});
 const centre=async selector=>{await p.locator(selector).evaluate(n=>n.scrollIntoView({block:"center"}));const r=await p.locator(selector).boundingBox();return{x:r.x+Math.min(35,r.width/2),y:r.y+35}};
 const capture=async name=>{await p.screenshot({path:path.join(out,`${name}-${width}.png`)});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))};
 const resize=async id=>{await p.locator(`[data-card-resize=${id}]`).tap();await p.waitForTimeout(330)};
 assert.equal(await p.locator('.health-card').count(),6);assert.equal(await p.locator('.reading-art').count(),6);assert.equal(await p.locator('.reading-mini-chart').count(),0);
 assert.match(await p.locator('.reading-steps').innerText(),/84%/);assert.match(await p.locator('.reading-heart').innerText(),/80/);await capture('mixed');
 assert(await p.locator('#live-bar').evaluate(n=>{const r=n.getBoundingClientRect();return n.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))}),'Explore remains above the reading surface');
 for(const id of ['sleep','steps','heart','body','intake','oxygen']){
  const at=await centre(`.reading-${id}`);await touch('touchStart',at);await p.waitForTimeout(520);assert.equal(await p.locator('.health-card.is-sizing').count(),1);await capture('hold-'+id);await touch('touchEnd');await p.waitForTimeout(100);
  assert.equal(await p.evaluate(()=>Health.page),'overview','A long hold must not navigate');assert.equal(await p.locator('.oxygen-tile').evaluate(n=>n.open),false);
  const old=await p.locator(`[data-health-card=${id}]`).evaluate(n=>n.classList.contains('is-wide'));await resize(id);assert.equal(await p.locator(`[data-health-card=${id}]`).evaluate(n=>n.classList.contains('is-wide')),!old);
 }
 // Every card can be compact, then wide. Only the held card exposes its control.
 for(const id of ['steps','heart','body','intake']){await p.locator(`.reading-${id}`).focus();await p.keyboard.press('Shift+F10');await p.keyboard.press('Enter');await p.waitForTimeout(330)}
 assert.equal(await p.locator('.health-card.is-wide').count(),0);await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);await capture('all-compact');
 await p.locator('.reading-oxygen').tap();await p.waitForTimeout(350);assert(await p.locator('.oxygen-tile').evaluate(n=>n.open));assert((await p.locator('.oxygen-chart').boundingBox()).width>width*.7);await p.locator('.reading-oxygen').tap();await p.waitForTimeout(350);
 for(const id of ['sleep','steps','heart','body','intake','oxygen']){await p.locator(`.reading-${id}`).focus();await p.keyboard.press('Shift+F10');await p.keyboard.press('Enter');await p.waitForTimeout(330)}
 assert.equal(await p.locator('.health-card.is-wide').count(),6);await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);await capture('all-wide');
 await open();assert.equal(await p.locator('.health-card.is-wide').count(),6,'Sizes survive a reload');
 await p.locator('.reading-heart').focus();await p.keyboard.press('Shift+F10');await p.keyboard.press('Enter');await p.waitForTimeout(60);await p.keyboard.press('Enter');await p.waitForTimeout(350);assert.equal(await p.locator('.health-card.is-wide').count(),6,'Rapid resize reverses without stale state');
 // Starting a scroll cancels the pending hold, so no control is left behind.
 await p.keyboard.press('Escape');const at=await centre('.reading-sleep');await touch('touchStart',at);await touch('touchMove',{x:at.x,y:at.y-45});await p.waitForTimeout(520);await touch('touchCancel');assert.equal(await p.locator('.health-card.is-sizing').count(),0);
 // A denied native write leaves the last saved arrangement intact.
 await p.evaluate(()=>{window.OrbitPreferences={write:()=>false,read:()=>null}});await p.locator('.reading-sleep').focus();await p.keyboard.press('Shift+F10');await p.keyboard.press('Enter');assert.equal(await p.locator('.health-card.is-wide').count(),6);assert.match(await p.locator('#toast').innerText(),/could not be saved/);await p.evaluate(()=>delete window.OrbitPreferences);
 await p.emulateMedia({forcedColors:'active'});await p.locator('.reading-sleep').focus();await p.keyboard.press('Shift+F10');assert.notEqual(await p.locator('[data-card-resize=sleep]').evaluate(n=>getComputedStyle(n).outlineStyle),'none');await capture('contrast');await p.emulateMedia({forcedColors:'none'});
 await p.evaluate(()=>{HealthData.accept({schema:1,date:fixture.date,rows:[],workouts:[],meta:{}});Health.render()});assert.equal(await p.locator('.health-reading-value').allTextContents().then(a=>a.filter(v=>v.includes('—')).length),6);await capture('empty');
 assert.deepEqual(errors,[]);await p.close();console.log(width,'PASS: all six sizes, holds, scroll cancellation, rapid reversal, persistence, failed writes, honest data, contrast and empty states');
}}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
