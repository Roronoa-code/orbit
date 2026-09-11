// Evidence for the recorded-interaction audit: target-selector gestures driven by a real pointer, the music
// transition held at partial progress and at both ends, time-only session content, weekly inspection and
// grouped history, all over a deliberately bright synthetic cover. Synthetic records and music only.
// Usage: node audit-shots.cjs <outDir> [width=390] [height=844]
const {chromium}=require('playwright');const sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.argv[2]||'audit'),W=Number(process.argv[3]||390),H=Number(process.argv[4]||844);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const LONG='Would You Love Me the Same If I Were Somebody Else Entirely Different';

(async()=>{
 let browser;
 browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:2,isMobile:true,hasTouch:true});
 const page=await context.newPage(),errors=[],numbers={width:W,height:H};
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(({long})=>{
  const day=86400000,at=(back,hour,minutes,kind)=>{const end=new Date(Date.now()-back*day);end.setHours(hour,20,0,0);return {kind,startedAt:end.getTime()-minutes*60000,endedAt:end.getTime(),elapsed:minutes*60000,totalMs:minutes*60000,targetMs:0,weightKg:0,trackLocation:false}};
  localStorage.setItem('orbit-workouts-v1',JSON.stringify({active:null,history:[at(0,9,25,'Walking'),at(0,7,12,'Strength'),at(2,8,40,'Walking')]}));
  const canvas=document.createElement('canvas');canvas.width=canvas.height=600;const c=canvas.getContext('2d');
  const g=c.createLinearGradient(0,0,600,600);g.addColorStop(0,'#fff3c4');g.addColorStop(.5,'#ffd0e0');g.addColorStop(1,'#dff5ff');c.fillStyle=g;c.fillRect(0,0,600,600);
  c.strokeStyle='#ffffffcc';c.lineWidth=18;for(let i=0;i<8;i++){c.beginPath();c.arc(300,240,40+i*44,0,Math.PI*2);c.stroke()}
  c.fillStyle='#ffffff';c.font='700 64px sans-serif';c.fillText('BRIGHT',40,520);c.fillText('COVER',40,585);
  const art=canvas.toDataURL('image/jpeg',.9);
  const snapshot={status:'ready',id:'audit:1',artKey:'audit-art',art,title:long,artist:'A Deliberately Long Artist Name For Truncation',source:'Synthetic music fixture',position:22000,duration:214000,playing:true,buffering:false,canToggle:true,canSeek:true,canPrevious:true,canNext:true,canOpen:true};
  window.OrbitMusic={read(key){const next={...snapshot};if(key===next.artKey)delete next.art;return JSON.stringify(next)},command(){return true},connect(){}};
 },{long:LONG});
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?audit-shots');
 await page.waitForSelector('#live-bar');await page.evaluate(()=>document.fonts.ready);await sleep(700);
 const shot=name=>page.screenshot({path:path.join(out,name+'.png')});

 // Workouts home: chooser, weekly inspection and grouped history.
 await page.evaluate(()=>Health.open('workouts'));await sleep(650);
 await shot('01-workouts-home');
 numbers.chooser=await page.evaluate(()=>[...document.querySelectorAll('.workout-kinds [data-setup]')].map(n=>n.textContent.replace(/\s+/g,' ').trim()));
 const inspect=day=>page.evaluate(d=>{const s=document.querySelector('#week-scrub');s.value=String(d);s.dispatchEvent(new Event('input',{bubbles:true}));return document.querySelector('#week-readout').textContent},day);
 numbers.weekToday=await inspect(6);await shot('02-week-inspect-today');
 numbers.weekEmpty=await inspect(5);await shot('03-week-inspect-empty');
 numbers.weekOlder=await inspect(4);
 numbers.weekTotal=await page.evaluate(()=>document.querySelector('.workout-week .tile-value').textContent+' / '+document.querySelector('.workout-week .health-section-head span').textContent);
 await page.evaluate(()=>document.querySelector('.workout-history summary').click());await sleep(450);
 await page.evaluate(()=>document.querySelector('.workout-history').scrollIntoView({block:'center'}));await sleep(250);
 await shot('04-history-grouped');
 numbers.history=await page.evaluate(()=>({title:document.querySelector('.workout-history summary h2').textContent,caption:document.querySelector('.workout-history summary small').textContent,
   days:[...document.querySelectorAll('.history-day')].map(n=>n.textContent),rows:[...document.querySelectorAll('.workout-history li button')].map(n=>n.textContent.replace(/\s+/g,' ').trim())}));

 // Target selector: pressed, carried by a real pointer, released over each option.
 await page.evaluate(()=>{document.querySelector('#health-scroll').scrollTop=0;document.querySelector('[data-setup="Strength"]').click()});await sleep(560);
 await shot('05-setup-idle');
 const seg=await page.evaluate(()=>[...document.querySelectorAll('.setup-segments label')].map(n=>{const r=n.getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}}));
 await page.mouse.move(seg[0].x,seg[0].y);await page.mouse.down();await sleep(150);await shot('06-target-pressed');
 for(let i=1;i<=4;i++){await page.mouse.move(seg[0].x+(seg[1].x-seg[0].x)*i/8,seg[0].y);await sleep(30)}
 await sleep(120);await shot('07-target-half-dragged');
 for(let i=5;i<=8;i++){await page.mouse.move(seg[0].x+(seg[1].x-seg[0].x)*i/8,seg[0].y);await sleep(30)}
 await sleep(120);await shot('07b-target-dragged');
 numbers.targetDuringDrag=await page.evaluate(()=>({checked:document.querySelector('input[name="target"]:checked').value,capsule:Math.round(document.querySelector('.setup-capsule').getBoundingClientRect().x),dragging:document.querySelector('.setup-segments').classList.contains('is-dragging')}));
 await page.mouse.up();await sleep(450);await shot('08-target-released-time');
 numbers.targetAfterTime=await page.evaluate(()=>({checked:document.querySelector('input[name="target"]:checked').value,duration:document.querySelector('#target-options').hidden?'hidden':'shown',disabled:document.querySelector('#target-minutes').disabled}));
 await page.mouse.move(seg[1].x,seg[1].y);await page.mouse.down();
 for(let i=1;i<=8;i++){await page.mouse.move(seg[1].x-(seg[1].x-seg[0].x)*i/8,seg[1].y);await sleep(30)}
 await page.mouse.up();await sleep(450);await shot('09-target-released-open');
 numbers.targetAfterOpen=await page.evaluate(()=>({checked:document.querySelector('input[name="target"]:checked').value,duration:document.querySelector('#target-options').hidden?'hidden':'shown',kept:document.querySelector('#target-minutes').value}));

 // A time-only session: live summary and in-progress details with their own controls.
 await page.evaluate(()=>Health.startCountdown('Strength',0,{trackLocation:false,weightKg:0}));await sleep(4200);
 await shot('10-strength-live');
 numbers.liveSummary=await page.evaluate(()=>document.querySelector('#live-workout-metrics').textContent.replace(/\s+/g,' ').trim());
 await page.evaluate(()=>document.querySelector('[data-workout-detail="active"]').click());await sleep(560);
 await shot('11-strength-details');
 numbers.details=await page.evaluate(()=>({heading:document.querySelector('.health-eyebrow').textContent,actions:[...document.querySelectorAll('#workout-record-body [data-session]')].map(n=>n.textContent),timers:document.querySelectorAll('#health-content .timer-dial').length}));
 await page.evaluate(()=>document.querySelector('#health-back').click());await sleep(560);

 // A timed session for the transition evidence, so the reading control is present at both ends.
 await page.evaluate(()=>{Health.action('finish');Health.startCountdown('Strength',30*60000,{trackLocation:false,weightKg:0})});await sleep(4200);

 // Music transition: settled ends, partial progress, a held near-end and a reversal, over the bright cover.
 await page.evaluate(()=>{document.querySelector('[data-timer-focus]').click()});await sleep(1600);
 await shot('12-focus-settled-full');
 const skin=()=>page.evaluate(()=>{const cs=n=>getComputedStyle(n),vis=n=>cs(n).display==='none'?0:Number(cs(n).opacity),size=n=>{const b=n.getBoundingClientRect();return {w:Math.round(b.width),h:Math.round(b.height)}};
   const matrix=document.querySelector('.session-matrix'),canvas=document.querySelector('#focus-time-dots');
   const title=document.querySelector('.music-heading h2').getBoundingClientRect();
   const hits=[...document.querySelectorAll('.music-controls button')].filter(b=>{const c=b.getBoundingClientRect();return title.right>c.left+.5&&title.left<c.right-.5&&title.bottom>c.top+.5&&title.top<c.bottom-.5}).map(b=>b.dataset.music);
   const picked=document.querySelector('.timer-mode-picker button[aria-pressed=true]');
   return {matrix:Number(vis(matrix).toFixed(2)),canvas:Number(vis(canvas).toFixed(2)),clock:vis(canvas)>vis(matrix)?size(canvas):size(matrix),
     head:cs(document.querySelector('.health-page-head .icon-button')).backgroundColor,picker:picked?cs(picked).backgroundColor:null,
     action:cs(document.querySelector('.session-actions button:first-child')).backgroundColor,lit:document.querySelector('#health-page').classList.contains('music-lit'),remaining:document.querySelector('#target-remaining')?getComputedStyle(document.querySelector('#target-remaining')).color:null,
     titleWidth:Math.round(title.width),titleOverlapsTransport:hits,veil:document.querySelector('#health-page').style.getPropertyValue('--art-veil')}});
 numbers.settledFull=await skin();
 const touch=(type,dy)=>page.evaluate(({type,dy})=>{const node=document.querySelector('.music-heading h2'),r=node.getBoundingClientRect(),y=window.__auditStart+dy;
   const t=new Touch({identifier:3,target:node,clientX:r.left+r.width/2,clientY:y});
   node.dispatchEvent(new TouchEvent(type,{touches:type==='touchend'?[]:[t],changedTouches:[t],cancelable:true,bubbles:true}));},{type,dy});
 await page.evaluate(()=>{window.__auditStart=document.querySelector('.music-heading h2').getBoundingClientRect().top});
 await touch('touchstart',0);
 const sampled=[];
 for(const step of [['10pc',40],['50pc',180],['90pc',320]]){await touch('touchmove',step[1]);await sleep(140);sampled.push(Object.assign({progress:step[0]},await skin()));await shot('13-held-'+step[0])}
 await touch('touchmove',352);await sleep(650);numbers.heldCompact=await skin();await shot('14-held-compact-end');
 for(const dy of [300,220,140,60,12]){await touch('touchmove',dy);await sleep(80)}
 await sleep(650);numbers.heldFullReversed=await skin();await shot('15-held-full-after-reversal');
 await touch('touchend',12);await sleep(1500);numbers.settledAfterRelease=await skin();await shot('16-settled-after-release');
 numbers.partialProgress=sampled;
 await page.evaluate(()=>{document.querySelector('[data-timer-focus]').click()});await sleep(1600);
 numbers.settledCompact=await skin();await shot('17-settled-compact');
 numbers.errors=errors;
 fs.writeFileSync(path.join(out,'numbers.json'),JSON.stringify(numbers,null,1)+'\n');

 // One sheet of the transition evidence: both settled ends, three partial values, a held end and a reversal.
 const sheet=['12-focus-settled-full','13-held-10pc','13-held-50pc','13-held-90pc','14-held-compact-end','15-held-full-after-reversal','16-settled-after-release','17-settled-compact'];
 const tiles=[];for(const name of sheet)tiles.push(await sharp(path.join(out,name+'.png')).resize({width:250}).toBuffer());
 const meta=await sharp(tiles[0]).metadata();
 await sharp({create:{width:250*4,height:meta.height*2,channels:3,background:'#111'}})
  .composite(tiles.map((input,i)=>({input,left:250*(i%4),top:meta.height*Math.floor(i/4)})))
  .png().toFile(path.join(out,'transition-sheet.png'));
 await browser.close();
 console.log(JSON.stringify({out,errors},null,1));
})().catch(async e=>{console.error(e);try{await browser.close()}catch{}process.exit(1)});
