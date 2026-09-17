// Actual local touch input: reorder, interrupted shuffle, edge scrolling and persisted layout.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'samsung-import/health-cards');fs.mkdirSync(out,{recursive:true});
(async()=>{const browser=await chromium.launch();try{for(const width of [390,320]){
 const p=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];
 p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(data=>{window.OrbitHealth={snapshot:()=>JSON.stringify({revision:'1',available:true,permitted:true,data}),load(){}}},require('./samsung-import.cjs').fixture());
 const open=async()=>{await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.evaluate(()=>Health.open('overview'));await p.waitForTimeout(450)};await open();
 const cdp=await p.context().newCDPSession(p),touch=(type,point)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:point?[point]:[]});
 const ids=()=>p.locator('.health-library>[data-health-card]').evaluateAll(ns=>ns.map(n=>n.dataset.healthCard));
 const centre=async id=>{const r=await p.locator(`[data-health-card=${id}]`).boundingBox();return{x:r.x+r.width/2,y:r.y+r.height/2}};
 const move=async(from,to)=>{for(let i=1;i<=12;i++){await touch('touchMove',{x:from.x+(to.x-from.x)*i/12,y:from.y+(to.y-from.y)*i/12});await p.waitForTimeout(16)}};
 const hold=async id=>{await p.locator(`[data-health-card=${id}]`).evaluate(n=>n.scrollIntoView({block:'center'}));const at=await centre(id);await touch('touchStart',at);await p.waitForTimeout(500);return at};
 const start=await ids(),from=await hold('steps'),to=await centre('heart');await move(from,to);
 assert.equal(await p.locator('.card-dragging').count(),1,'Real card stays under the finger');assert.equal(await p.locator('.card-drop-slot').count(),1);
 assert.deepEqual(await ids(),['sleep','heart','steps','body','intake','oxygen']);
 assert(await p.locator('[data-health-card=heart]').evaluate(n=>n.getAnimations().some(a=>a.playState==='running')),'Neighbour moves, rather than jumping');
 const floated=await p.locator('.card-dragging').boundingBox();assert(Math.abs(floated.x+floated.width/2-to.x)<3&&Math.abs(floated.y+floated.height/2-to.y)<3,'Card follows both axes');
 await p.screenshot({path:path.join(out,`drag-shuffle-${width}.png`)});
 // Reverse while neighbouring cards are still settling, then cancel back to saved order.
 await move(to,from);await touch('touchCancel');await p.waitForTimeout(350);assert.deepEqual(await ids(),start);assert.equal(await p.locator('.card-dragging,.card-drop-slot').count(),0);
 const again=await hold('steps');await move(again,await centre('heart'));await touch('touchEnd');await p.waitForTimeout(350);
 const saved=await ids();assert.notDeepEqual(saved,start);assert.equal(await p.evaluate(()=>Health.page),'overview');await open();assert.deepEqual(await ids(),saved,'Order survives reload');
 // Keyboard access uses the same persistence and animated reflow.
 await p.locator('.reading-sleep').focus();await p.keyboard.press('Alt+ArrowDown');await p.waitForTimeout(350);assert.equal((await ids())[1],'sleep');assert.match(await p.locator('#card-layout-status').innerText(),/position 2/);
 // Hold near an edge and continue moving through more than one viewport.
 await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);const beforeScroll=await ids(),edgeFrom=await hold(beforeScroll[0]),edge={x:width/2,y:815};
 await move(edgeFrom,edge);await p.waitForTimeout(1000);assert(await p.locator('#health-scroll').evaluate(n=>n.scrollTop>120),'Held card auto-scrolls through the grid');
 await touch('touchMove',{x:width/2,y:685});await p.waitForTimeout(200);await touch('touchEnd');await p.waitForTimeout(350);assert.equal((await ids()).at(-1),beforeScroll[0],'Card can move to the last slot');
 // Failed native persistence rolls the visible order back, rather than lying about saving.
 await p.evaluate(()=>{window.OrbitPreferences={read:()=>null,write:()=>false}});const stable=await ids();await p.locator(`.reading-${stable[1]}`).focus();await p.keyboard.press('Alt+ArrowUp');assert.deepEqual(await ids(),stable);assert.match(await p.locator('#toast').innerText(),/could not be saved/);await p.evaluate(()=>delete window.OrbitPreferences);
 const cancelFrom=await hold('body');await move(cancelFrom,{x:cancelFrom.x+60,y:cancelFrom.y-40});await p.evaluate(()=>window.dispatchEvent(new Event('blur')));await touch('touchCancel');await p.waitForTimeout(350);assert.deepEqual(await ids(),stable);assert.equal(await p.locator('.card-dragging,.card-drop-slot').count(),0);
 await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);await p.screenshot({path:path.join(out,`reordered-${width}.png`)});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));assert.deepEqual(errors,[]);
 await p.evaluate(()=>localStorage.setItem('orbit-health-order-v1','["sleep","sleep","steps","body","intake","oxygen"]'));await open();assert.deepEqual(await ids(),start,'Malformed saved order falls back to all six readings');
 await p.close();console.log(width,'PASS: actual touch shuffle, neighbour animation, reversal/cancel, persistence, keyboard, edge scrolling, write failure and invalid order');
}}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
