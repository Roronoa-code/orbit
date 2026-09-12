// Evidence for the frosted material and the selector engine: the indicator held halfway with the pointer still
// down (with and without a diagnostic overlay), a distant hold at its cap, the material over a high-contrast
// pattern and over bright and dark artwork, and a contact sheet of every migrated selector. Synthetic data only.
// Usage: node frosted-shots.cjs <outDir> [width=390] [height=844]
const {chromium}=require('playwright');const sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.argv[2]||'frosted'),W=Number(process.argv[3]||390),H=Number(process.argv[4]||844);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));

(async()=>{
 let browser;
 browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:2,isMobile:true,hasTouch:true});
 const page=await context.newPage(),errors=[],numbers={width:W,height:H};
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  const cover=(stops,label)=>{const c=document.createElement('canvas');c.width=c.height=600;const x=c.getContext('2d');
   const g=x.createLinearGradient(0,0,600,600);stops.forEach((s,i)=>g.addColorStop(i/(stops.length-1),s));x.fillStyle=g;x.fillRect(0,0,600,600);
   x.strokeStyle='#ffffffaa';x.lineWidth=16;for(let i=0;i<7;i++){x.beginPath();x.arc(300,250,40+i*46,0,Math.PI*2);x.stroke()}
   x.fillStyle=stops[0]==='#0b0b12'?'#ffffff':'#101018';x.font='700 58px sans-serif';x.fillText(label,40,540);return c.toDataURL('image/jpeg',.9)};
  window.__covers={bright:cover(['#fff3c4','#ffd0e0','#dff5ff'],'BRIGHT'),dark:cover(['#0b0b12','#1d2140','#2a1636'],'DARK')};
  const snapshot={status:'ready',id:'frost:1',artKey:'frost-art',art:window.__covers.bright,title:'Would You Love Me the Same If I Were Somebody Else',artist:'A Deliberately Long Artist Name',source:'Synthetic music fixture',position:22000,duration:214000,playing:true,buffering:false,canToggle:true,canSeek:true,canPrevious:true,canNext:true,canOpen:true};
  window.__snapshot=snapshot;
  window.OrbitMusic={read(key){const next={...snapshot};if(key===next.artKey)delete next.art;return JSON.stringify(next)},command(){return true},connect(){}};
 });
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?frosted-shots');
 await page.waitForSelector('#live-bar');await page.evaluate(()=>document.fonts.ready);await sleep(700);
 await page.addScriptTag({content:'window.__frost={Health,BlobTrack}'});
 const shot=name=>page.screenshot({path:path.join(out,name+'.png')});
 const patternOn=on=>page.evaluate(state=>{
   let node=document.querySelector('#frost-pattern');
   if(!state){node?.remove();return}
   if(node)return;
   node=document.createElement('div');node.id='frost-pattern';
   // The pattern goes inside the selector's own stacking context: an ancestor that isolates also bounds what a
   // backdrop filter may sample, so a page-level pattern would prove nothing about this surface.
   node.style.cssText='position:absolute;left:0;right:0;top:-140px;bottom:-140px;z-index:0;pointer-events:none;background-image:repeating-linear-gradient(45deg,#fff 0 14px,#000 14px 28px),repeating-linear-gradient(-45deg,#f0f 0 9px,#0ff 9px 18px);background-blend-mode:difference;opacity:.6';
   const track=document.querySelector('.body-metric-picker');
   (track?.parentElement||document.querySelector('#health-page')||document.body).prepend(node);
 },on);

 await page.evaluate(()=>__frost.Health.open('body'));await sleep(800);
 const pickerBox=await page.evaluate(()=>{const r=document.querySelector('.body-metric-picker').getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height}});
 const crop={x:0,y:Math.max(0,Math.round(pickerBox.y-70)),width:W,height:Math.min(H,190)};
 await shot('01-selector-rest');
 await page.screenshot({path:path.join(out,'01-selector-rest-crop.png'),clip:crop});

 // The material over a high-contrast pattern: real diffusion, sharp labels.
 await patternOn(true);await sleep(300);
 await shot('02-material-checkerboard');
 await page.screenshot({path:path.join(out,'02-material-checkerboard-crop.png'),clip:crop});

 // Held exactly halfway between two options, with the pointer still down.
 const held=await page.evaluate(async()=>{
  const track=__frost.Health.tracks.get('body-metric'),host=document.querySelector('.body-metric-picker');
  const labels=[...document.querySelectorAll('[data-body-metric]')];
  const frame=()=>new Promise(r=>requestAnimationFrame(r));
  const box=n=>n.getBoundingClientRect();
  const ptr=(target,type,x,y)=>target.dispatchEvent(new PointerEvent(type,{pointerId:41,isPrimary:true,pointerType:'touch',clientX:x,clientY:y,bubbles:true,cancelable:true}));
  const y=box(host).top+box(host).height/2,startX=box(labels[0]).left+box(labels[0]).width/2;
  ptr(labels[0],'pointerdown',startX,y);await frame();
  let at=startX+12;ptr(host,'pointermove',at,y);await frame();await frame();
  const boundary=(track.options[1].centre+track.options[2].centre)/2;
  for(let i=0;i<5;i++){const error=boundary-track.pose.cx;if(Math.abs(error)<.05)break;at+=error;ptr(host,'pointermove',at,y);await frame();await frame()}
  await new Promise(r=>setTimeout(r,140));
  const pill=document.querySelector('.body-metric-picker .selection-pill').getBoundingClientRect();
  const cover=n=>Number(getComputedStyle(n).getPropertyValue('--blob-influence')||0);
  window.__held={at,y};
  return {pointerDownAt:startX,gesture:'body-metric drag, still held',committed:document.querySelector('[data-body-metric][aria-pressed="true"]').dataset.bodyMetric,
    presented:{left:+pill.left.toFixed(2),width:+pill.width.toFixed(2),centre:+((pill.left+pill.right)/2).toFixed(2)},
    boundaryClient:+(box(host).left+boundary).toFixed(2),
    raw:track.influences.map(v=>+v.raw.toFixed(3)),eased:track.influences.map(v=>+v.eased.toFixed(3)),
    renderedInfluence:labels.map(cover),labelColours:labels.map(n=>getComputedStyle(n).color),
    candidate:track.options.map(o=>o.id),trackBackdrop:getComputedStyle(host).backdropFilter,indicatorBackdrop:getComputedStyle(pill&&document.querySelector('.body-metric-picker .selection-pill')).backdropFilter};
 });
 numbers.halfHeld=held;
 await shot('03-selector-half-held');
 await page.screenshot({path:path.join(out,'03-selector-half-held-crop.png'),clip:crop});
 // The same moment with a diagnostic overlay, then removed again for the clean evidence above.
 await page.evaluate(state=>{
   const node=document.createElement('div');node.id='frost-debug';
   node.style.cssText='position:fixed;left:8px;right:8px;bottom:8px;z-index:99;padding:8px 10px;border-radius:10px;background:#000c;color:#9ff;font:9px/1.45 monospace;white-space:pre-wrap';
   node.textContent='held halfway, pointer down\ncommitted='+state.committed+'  centre='+state.presented.centre+'  boundary='+state.boundaryClient+'\nraw='+JSON.stringify(state.raw)+'\neased='+JSON.stringify(state.eased)+'\nrendered='+JSON.stringify(state.renderedInfluence);
   document.body.append(node);
 },held);
 await sleep(120);await page.screenshot({path:path.join(out,'04-selector-half-held-debug.png'),clip:{x:0,y:0,width:W,height:H}});
 await page.evaluate(()=>document.querySelector('#frost-debug')?.remove());
 await page.evaluate(()=>{const h=window.__held;document.querySelector('.body-metric-picker').dispatchEvent(new PointerEvent('pointerup',{pointerId:41,isPrimary:true,pointerType:'touch',clientX:h.at,clientY:h.y,bubbles:true,cancelable:true}))});
 await sleep(1000);

 // A distant stationary hold: pressed from the far end, capped at a tenth of one local slot.
 numbers.distantHold=await page.evaluate(async()=>{
  const track=__frost.Health.tracks.get('body-metric'),host=document.querySelector('.body-metric-picker');
  const labels=[...document.querySelectorAll('[data-body-metric]')];
  const frame=()=>new Promise(r=>requestAnimationFrame(r)),box=n=>n.getBoundingClientRect();
  const ptr=(target,type,x,y)=>target.dispatchEvent(new PointerEvent(type,{pointerId:42,isPrimary:true,pointerType:'touch',clientX:x,clientY:y,bubbles:true,cancelable:true}));
  labels[0].click();await new Promise(r=>setTimeout(r,950));
  const resting={...track.pose},slot=track.options[0].width;
  const y=box(host).top+box(host).height/2,farX=box(labels[3]).left+box(labels[3]).width/2;
  ptr(labels[3],'pointerdown',farX,y);
  let worst=0;for(let i=0;i<40;i++){await frame();const at=track.pose;worst=Math.max(worst,Math.abs(at.cx-resting.cx)+Math.max(0,at.width-resting.width)/2)}
  window.__far={farX,y};
  return {slot:+slot.toFixed(2),budget:+(slot*.10).toFixed(2),worstLeadingExcursion:+worst.toFixed(2),
    committed:document.querySelector('[data-body-metric][aria-pressed="true"]').dataset.bodyMetric,pressed:labels[3].dataset.bodyMetric};
 });
 await shot('05-distant-hold-capped');
 await page.screenshot({path:path.join(out,'05-distant-hold-capped-crop.png'),clip:crop});
 await page.evaluate(()=>{const f=window.__far;document.querySelector('.body-metric-picker').dispatchEvent(new PointerEvent('pointercancel',{pointerId:42,isPrimary:true,pointerType:'touch',clientX:f.farX,clientY:f.y,bubbles:true,cancelable:true}))});
 await sleep(900);await patternOn(false);

 // Every migrated selector, in its own screen.
 const sheet=[];
 const capture=async(name,file)=>{await shot(file);sheet.push(file)};
 await capture('Body measurement and history range','06-body-selectors');
 await page.evaluate(()=>{__frost.Health.close()});await sleep(400);
 await page.evaluate(()=>{__frost.Health.open('workouts')});await sleep(500);
 await page.evaluate(()=>document.querySelector('[data-setup="Walking"]').click());await sleep(600);
 await capture('Workout target','07-workout-target');
 await page.evaluate(()=>{__frost.Health.startCountdown('Walking',30*60000,{trackLocation:true,weightKg:0})});await sleep(4300);
 await capture('Timer reading','08-timer-reading');
 await page.evaluate(()=>document.querySelector('[data-workout-detail="active"]').click());await sleep(700);
 await capture('Workout chart','09-workout-chart');
 numbers.selectors=await page.evaluate(()=>[...document.querySelectorAll('.blob-track')].map(n=>({
   label:n.getAttribute('aria-label')||n.className.split(' ')[0],options:n.querySelectorAll('.blob-option').length,
   backdrop:getComputedStyle(n).backdropFilter,indicator:Boolean(n.querySelector('.selection-pill.glass-indicator'))})));
 await page.evaluate(()=>document.querySelector('#health-back').click());await sleep(600);

 // The same material over bright and dark artwork, with the transition held part way.
 for(const [name,file] of [['bright','10-artwork-bright-held'],['dark','11-artwork-dark-held']]){
   await page.evaluate(kind=>{window.__snapshot.art=window.__covers[kind];window.__snapshot.artKey='frost-'+kind;window.dispatchEvent(new Event('orbit-music-change'))},name);
   await sleep(900);
   await page.evaluate(()=>{document.querySelector('[data-timer-focus]').click()});await sleep(1500);
   await page.evaluate(async()=>{
     const node=document.querySelector('.music-heading h2'),r=node.getBoundingClientRect(),start=r.top;
     const touch=(type,dy)=>{const t=new Touch({identifier:9,target:node,clientX:r.left+r.width/2,clientY:start+dy});
       node.dispatchEvent(new TouchEvent(type,{touches:type==='touchend'?[]:[t],changedTouches:[t],cancelable:true,bubbles:true}))};
     touch('touchstart',0);for(const dy of [30,110,180]){touch('touchmove',dy);await new Promise(r=>requestAnimationFrame(r))}
     window.__musicTouch=touch;
   });
   await sleep(500);await shot(file);
   await page.evaluate(()=>window.__musicTouch('touchend',180));await sleep(1400);
   await page.evaluate(()=>{if(document.querySelector('[data-timer-focus]').getAttribute('aria-pressed')==='true')document.querySelector('[data-timer-focus]').click()});await sleep(1200);
 }
 // What one second of dragging actually costs on this engine, recorded rather than promised.
 await page.evaluate(()=>{__frost.Health.action('finish');__frost.Health.close()});await sleep(400);
 await page.evaluate(()=>__frost.Health.open('body'));await sleep(700);
 numbers.performance=await page.evaluate(async()=>{
  const host=document.querySelector('.body-metric-picker'),labels=[...document.querySelectorAll('[data-body-metric]')];
  const box=n=>n.getBoundingClientRect(),frame=()=>new Promise(r=>requestAnimationFrame(r));
  const ptr=(target,type,x,y)=>target.dispatchEvent(new PointerEvent(type,{pointerId:43,isPrimary:true,pointerType:'touch',clientX:x,clientY:y,bubbles:true,cancelable:true}));
  const y=box(host).top+box(host).height/2,startX=box(labels[0]).left+box(labels[0]).width/2;
  const span=box(labels[3]).left+box(labels[3]).width/2-startX;
  ptr(labels[0],'pointerdown',startX,y);await frame();
  const intervals=[];let last=performance.now(),longest=0;
  const started=performance.now();
  while(performance.now()-started<1000){
    await frame();
    const now=performance.now(),dt=now-last;last=now;intervals.push(dt);longest=Math.max(longest,dt);
    const t=(now-started)/1000;ptr(host,'pointermove',startX+span*(0.5-0.5*Math.cos(t*Math.PI*2)),y);
  }
  ptr(host,'pointerup',startX,y);
  intervals.sort((a,b)=>a-b);
  return {scope:'one second of continuous dragging on the Body measurement selector, headless Chromium, no display',
    frames:intervals.length,medianFrameMs:+intervals[Math.floor(intervals.length/2)].toFixed(2),
    longestFrameMs:+longest.toFixed(2),note:'Browser frame timing does not establish physical display cadence or touch latency.'};
 });
 await sleep(900);await page.evaluate(()=>__frost.Health.close());await sleep(300);
 numbers.errors=errors;
 fs.writeFileSync(path.join(out,'numbers.json'),JSON.stringify(numbers,null,1)+'\n');
 fs.writeFileSync(path.join(out,'selector-half-held.json'),JSON.stringify(held,null,1)+'\n');

 const tiles=[];for(const name of ['01-selector-rest-crop','02-material-checkerboard-crop','03-selector-half-held-crop','05-distant-hold-capped-crop'])tiles.push(await sharp(path.join(out,name+'.png')).resize({width:380}).toBuffer());
 const meta=await sharp(tiles[0]).metadata();
 await sharp({create:{width:380*2,height:meta.height*2,channels:3,background:'#111'}})
  .composite(tiles.map((input,i)=>({input,left:380*(i%2),top:meta.height*Math.floor(i/2)})))
  .png().toFile(path.join(out,'selector-sheet.png'));
 const screens=[];for(const name of sheet)screens.push(await sharp(path.join(out,name+'.png')).resize({width:250}).toBuffer());
 const screenMeta=await sharp(screens[0]).metadata();
 await sharp({create:{width:250*screens.length,height:screenMeta.height,channels:3,background:'#111'}})
  .composite(screens.map((input,i)=>({input,left:250*i,top:0})))
  .png().toFile(path.join(out,'migration-contact-sheet.png'));
 await browser.close();
 console.log(JSON.stringify({out,errors},null,1));
})().catch(async e=>{console.error(e);try{await browser.close()}catch{}process.exit(1)});
