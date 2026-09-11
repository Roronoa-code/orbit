// Launcher (idle and active) and workout chooser/setup captures with layout numbers, plus an open -> reverse -> close strip.
// Uses a fresh browser context and synthetic workouts only. NODE_PATH must also reach sharp.
// Usage: node launcher-workout-shots.cjs <outDir> [width=390] [height=844]
const {chromium}=require('playwright');const sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.argv[2]||'launcher-workout'),W=Number(process.argv[3]||390),H=Number(process.argv[4]||844);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const page=await (await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:2,isMobile:true,hasTouch:true})).newPage(),errors=[],numbers={W,H};
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?launcher-workout-shots');await page.waitForSelector('#live-bar');await page.evaluate(()=>document.fonts.ready);await sleep(900);
 const shot=name=>page.screenshot({path:path.join(out,name+'.png')});
 const launcher=()=>page.evaluate(()=>{const r=n=>{const b=document.querySelector(n).getBoundingClientRect();return Math.round(b.height*10)/10},fits=n=>n.scrollWidth<=n.clientWidth+1;
  return {island:r('#live-island'),islandBottom:Math.round(document.querySelector('#live-island').getBoundingClientRect().bottom),fullHeight:islands.live.fullHeight,choices:[...document.querySelectorAll('.activity-choice')].map(n=>n.textContent.trim()+(fits(n)?'':' (clipped)')).join(', '),
   title:document.querySelector('#live-title').textContent+(fits(document.querySelector('#live-title'))?'':' (clipped)'),meta:document.querySelector('#live-meta').textContent+(fits(document.querySelector('#live-meta'))?'':' (clipped)'),metaOpacity:Number(getComputedStyle(document.querySelector('#live-meta')).opacity).toFixed(2),
   timer:document.querySelector('#live-timer').hidden?null:document.querySelector('#live-timer').textContent+(fits(document.querySelector('#live-timer'))?'':' (clipped)')}});
 await shot('01-home-collapsed');numbers.idleCollapsed=await launcher();
 await page.evaluate(()=>setLiveOpen(true));await sleep(1000);await shot('02-home-expanded');numbers.idleExpanded=await launcher();
 await page.evaluate(()=>setLiveOpen(false));await sleep(1000);
 // Open -> reverse -> reopen -> close, frames clipped to the lower screen.
 const clip={x:0,y:Math.max(0,H-330),width:W,height:330},frames=[];
 const frame=async label=>{const file=path.join(out,`frame-${String(frames.length).padStart(2,'0')}.png`);await page.screenshot({path:file,clip});frames.push({label,file,height:await page.evaluate(()=>Math.round(document.querySelector('#live-island').getBoundingClientRect().height))})};
 await page.evaluate(()=>setLiveOpen(true));for(let i=0;i<4;i++)await frame('opening');
 await page.evaluate(()=>setLiveOpen(false));for(let i=0;i<4;i++)await frame('reversed');
 await page.evaluate(()=>setLiveOpen(true));for(let i=0;i<4;i++)await frame('reopening');
 await page.evaluate(()=>setLiveOpen(false));for(let i=0;i<5;i++)await frame('closing');
 numbers.frames=frames.map(f=>f.label+' '+f.height).join(' | ');
 const tile=W,composites=[];
 for(const [i,f] of frames.entries()){composites.push({input:await sharp(f.file).resize(tile,330).toBuffer(),left:(i%6)*(tile+6),top:Math.floor(i/6)*356+24});composites.push({input:Buffer.from(`<svg width="${tile}" height="22"><text x="4" y="16" font-family="Segoe UI" font-size="14" fill="#fff">${i} ${f.label} · ${f.height}px</text></svg>`),left:(i%6)*(tile+6),top:Math.floor(i/6)*356})}
 await sharp({create:{width:6*(tile+6),height:Math.ceil(frames.length/6)*356,channels:3,background:'#444'}}).composite(composites).png().toFile(path.join(out,'frames-sheet.png'));
 // Active workout, synthetic session.
 await page.evaluate(()=>{Health.action('start','Walking',1800000);setLiveOpen(true)});await sleep(1000);await shot('03-active-running');numbers.activeRunning=await launcher();
 await page.evaluate(()=>Health.action('pause'));await sleep(500);await shot('04-active-paused');numbers.activePaused=await launcher();
 await page.evaluate(()=>{Health.action('resume');Health.state.active.elapsed=3*3600000+754000;updateLiveBar()});await sleep(400);await shot('05-active-hours');numbers.activeHours=await launcher();
 await page.evaluate(()=>{Health.action('finish');setLiveOpen(false)});await sleep(900);
 // Chooser and setups.
 const fit=()=>page.evaluate(()=>{const s=document.querySelector('#health-scroll');return {overflow:s.scrollWidth>s.clientWidth,scrollHeight:s.scrollHeight,clientHeight:s.clientHeight,rows:[...document.querySelectorAll('.workout-kinds [data-setup]')].map(n=>Math.round(n.getBoundingClientRect().height)).join(',')||undefined,title:document.querySelector('#health-title').textContent,start:document.querySelector('.workout-primary')?.getBoundingClientRect().bottom|0}});
 await page.evaluate(()=>Health.open('workouts'));await sleep(700);await shot('06-workouts-chooser');numbers.chooser=await fit();
 for(const kind of ['Walking','Running','Cycling','Strength']){
  await page.click(`[data-setup="${kind}"]`);await sleep(600);await shot(`07-setup-${kind.toLowerCase()}`);numbers['setup'+kind]=await fit();
  if(kind==='Walking'){
   await page.click('input[name="target"][value="time"] + span');await sleep(500);await shot('08-setup-walking-time');numbers.setupWalkingTime=await fit();
   await page.evaluate(()=>{document.querySelector('#workout-setup-form').noValidate=true;document.querySelector('#workout-weight').value='10'});await page.click('.workout-primary');await sleep(200);
   await page.evaluate(()=>document.querySelector('#workout-error').scrollIntoView({block:'center'}));await sleep(300);await shot('09-setup-validation-error');numbers.validation=await page.evaluate(()=>({error:document.querySelector('#workout-error').textContent,errorBottom:Math.round(document.querySelector('#workout-error').getBoundingClientRect().bottom),startTop:Math.round(document.querySelector('.workout-primary').getBoundingClientRect().top)}));
  }
  await page.evaluate(()=>Health.close());await sleep(500);
 }
 numbers.errors=errors;fs.writeFileSync(path.join(out,'numbers.json'),JSON.stringify(numbers,null,1));console.log(JSON.stringify(numbers,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
