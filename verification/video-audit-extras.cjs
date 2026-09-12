const {chromium}=require('playwright'),sharp=require('sharp'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {seedMedia,materialBoard}=require('./video-audit-scenes.cjs');
const out=path.join(__dirname,'video-audit-20260912','extras');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true}),page=await browser.newPage({viewport:{width:384,height:832}}),results=[];
 try{
  await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
  await page.evaluate(()=>{const end=Date.now();localStorage.setItem('orbit-workouts-v1',JSON.stringify({active:null,history:Array.from({length:65},(_,i)=>({kind:'Strength',startedAt:end-i*86400000-901000,endedAt:end-i*86400000,elapsed:900000,totalMs:901000,weightKg:0,trackLocation:false}))}))});
  await page.reload();await page.evaluate(()=>Health.open('workouts'));await page.waitForTimeout(600);await page.locator('.workout-history summary').click();
  const row=page.locator('[data-workout-detail]').nth(30);await row.scrollIntoViewIfNeeded();const scroll=await page.locator('#health-scroll').evaluate(n=>n.scrollTop);await row.click();await page.waitForTimeout(500);await page.locator('#health-back').click();await page.waitForTimeout(600);
  assert.equal(await page.locator('.workout-history').getAttribute('open'),'');assert(Math.abs(await page.locator('#health-scroll').evaluate(n=>n.scrollTop)-scroll)<2);results.push({name:'65-record history keeps group, opened state and scroll after detail',status:'PASS'});
  await page.evaluate(()=>Health.open('body'));await page.waitForTimeout(650);
  const cdp=await page.context().newCDPSession(page),ax=await cdp.send('Accessibility.getFullAXTree');
  const exposed=ax.nodes.filter(n=>!n.ignored&&n.role?.value==='button'),names=exposed.map(n=>n.name?.value);
  assert(names.includes('Back to dashboard')&&names.includes('Weight')&&names.includes('Lean mass'));assert(!names.some(n=>!n));
  await page.locator('[data-body-metric="muscle"]').focus();await page.keyboard.press('Enter');await page.waitForTimeout(750);assert.equal(await page.locator('[data-body-metric="muscle"]').getAttribute('aria-pressed'),'true');
  results.push({name:'Native browser accessibility tree has named controls; keyboard selection publishes pressed state',status:'PASS',names});
  await seedMedia(page);await materialBoard(page);await page.setViewportSize({width:1140,height:1100});await page.screenshot({path:path.join(out,'materials.png')});
  const track=page.locator('[data-underlay="Text and shapes"] .glass-track').first(),before=await track.screenshot();
  const off=await page.addStyleTag({content:'.glass-track{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}'}),without=await track.screenshot();await off.evaluate(n=>n.remove());
  const raw=async b=>sharp(b).removeAlpha().raw().toBuffer(),A=await raw(before),B=await raw(without),rms=Math.sqrt(A.reduce((s,v,i)=>s+(v-B[i])**2,0)/A.length);assert(rms>3,'Backdrop blur must change rendered pixels');
  await page.locator('.audit-underlay').evaluateAll(nodes=>nodes.forEach(n=>n.style.transform='translateY(-37px)'));const moved=await track.screenshot();const C=await raw(moved),movement=Math.sqrt(A.reduce((s,v,i)=>s+(v-C[i])**2,0)/A.length);assert(movement>2,'The sample must follow a moving underlay');await page.screenshot({path:path.join(out,'materials-moved.png')});
  results.push({name:'Material diffusion changes pixels and follows underlay movement',status:'PASS',blurOnOffRms:rms,movingUnderlayRms:movement});
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
