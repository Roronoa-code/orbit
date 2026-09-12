const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {seedMedia}=require('./video-audit-scenes.cjs');
(async()=>{
 const b=await chromium.launch({headless:true}),results=[];
 try{for(const width of [390,384,320]){
  const p=await b.newPage({viewport:{width,height:844}});await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await seedMedia(p);await p.evaluate(()=>{Health.action('start','Strength',60000);Health.open('workouts')});await p.waitForTimeout(800);
  const boxes=()=>p.locator('.music-controls button,.music-controls button svg,.session-actions button').evaluateAll(nodes=>nodes.map(n=>{const r=n.getBoundingClientRect();return {name:n.dataset.music||n.dataset.session||'icon',w:r.width,h:r.height}}));
  const before=await boxes();let maxDelta=0;
  for(const open of [true,false,true,false]){await p.evaluate(open=>WorkoutFocus.toggle(document.querySelector('.workout-live'),open),open);for(let i=0;i<14;i++){await p.waitForTimeout(35);const now=await boxes();now.forEach((n,j)=>{maxDelta=Math.max(maxDelta,Math.abs(n.w-before[j].w),Math.abs(n.h-before[j].h))});}if(!open)await p.waitForTimeout(600)}
  assert(maxDelta<.6,`Buttons resized by ${maxDelta}px at ${width}px`);results.push({width,maxButtonSizeChangePx:maxDelta,status:'PASS'});
  if(width===390){
   await p.evaluate(()=>{auditMedia.playing=false;auditAcknowledge=false;MusicPlayer.refresh()});await p.locator('[data-music="toggle"]').click();assert.equal(await p.locator('[data-music="toggle"]').getAttribute('aria-label'),'Play music');assert.equal(await p.locator('[data-music="toggle"]').getAttribute('aria-busy'),'true');
   await p.waitForTimeout(8500);await p.evaluate(()=>MusicPlayer.refresh());assert.equal(await p.locator('[data-music="toggle"]').getAttribute('aria-busy'),'false');assert.match(await p.locator('#toast').innerText(),/not confirmed/);
   await p.evaluate(()=>{auditMedia.playback='unavailable';MusicPlayer.refresh()});assert.match(await p.locator('.music-source').textContent(),/Playback unavailable/);assert(await p.evaluate(()=>Health.state.active.resumedAt!==null));results.push({width,status:'PASS',name:'Unacknowledged media command retains reported icon, times out visibly, and preserves workout; unavailable playback is explicit'});
  }
  await p.close();
 }}finally{await b.close()}
 fs.writeFileSync(path.join(__dirname,'video-audit-20260912','controls.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results));
})().catch(e=>{console.error(e);process.exitCode=1});
