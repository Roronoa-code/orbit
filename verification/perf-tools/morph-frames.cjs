// Captures the small <-> page-wide music move at held finger positions (and a tap sequence), plus geometry.
// Usage: node frames.cjs <outDir> [width=390] [height=844]
const {chromium}=require('playwright');
const sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path');
const out=process.argv[2]||'frames',W=Number(process.argv[3]||390),H=Number(process.argv[4]||844);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage(),cdp=await context.newCDPSession(page);
 page.on('pageerror',e=>console.log('pageerror',e.message));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?frames');await page.waitForSelector('#live-bar');await sleep(1200);
 await page.evaluate(()=>{
  const canvas=document.createElement('canvas');canvas.width=canvas.height=1200;const c=canvas.getContext('2d'),g=c.createLinearGradient(0,0,1200,1200);g.addColorStop(0,'#e8e2d6');g.addColorStop(.5,'#c9b9a4');g.addColorStop(1,'#3b2f2a');c.fillStyle=g;c.fillRect(0,0,1200,1200);
  c.fillStyle='#111';c.beginPath();c.arc(600,470,230,0,Math.PI*2);c.fill();c.fillStyle='#b3261e';c.fillRect(0,900,1200,300);
  const snapshot={status:'ready',id:'s:1',artKey:'a:1',art:canvas.toDataURL('image/jpeg',.92),title:'SLOW DANCING IN THE DARK',artist:'Joji',source:'Test music app',position:112000,duration:209000,playing:true,buffering:false,canToggle:true,canSeek:true,canPrevious:true,canNext:true,canOpen:true};
  window.OrbitMusic={read(key){const next={...snapshot};if(key===next.artKey)delete next.art;return JSON.stringify(next)},command(){return true},connect(){}};
  if(!Health.state.active)Health.action('start','Walking',0,{trackLocation:true});Health.open('workouts');
 });
 await sleep(2500);
 const rects=await page.evaluate(()=>{const o={};for(const s of ['.workout-live-top','.timer-dial','#session-time','.workout-unconnected','.workout-music','.music-bottom','.music-thumb','.music-heading','.music-heading>div','.music-heading h2','.music-heading p','.music-controls','.session-actions','#health-page','.health-page-head','.music-cover img']){const n=document.querySelector(s);if(n){const r=n.getBoundingClientRect();o[s]=[r.left,r.top,r.width,r.height].map(v=>Math.round(v))}}return o});
 const shots=[];
 async function shot(name){const file=path.join(out,name+'.png');await page.screenshot({path:file});shots.push({name,file});}
 async function h2info(){return page.evaluate(()=>{const h=document.querySelector('.music-heading h2'),col=document.querySelector('.music-heading>div'),ctl=document.querySelector('.music-controls button');const r=h.getBoundingClientRect(),c=col.getBoundingClientRect(),b=ctl.getBoundingClientRect();return {h2:[r.left,r.right].map(Math.round),column:[c.left,c.right].map(Math.round),firstControlLeft:Math.round(b.left)}})}
 await shot('00-compact');
 const start=await page.evaluate(()=>{const r=document.querySelector('.music-heading h2').getBoundingClientRect();return {x:Math.round(r.left+30),y:Math.round(r.top+8)}});
 const range=await page.evaluate(()=>{const p=document.querySelector('#health-page');return Math.max(200,Math.min(420,p.offsetHeight*.42))});
 const info={W,H,rects,range,held:{}};
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[start]});
 let y=start.y;
 for(const p of [.1,.25,.5,.75,.9,1]){
  const to=start.y-(8+p*range);while(y>to){y=Math.max(to,y-12);await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]});await sleep(16)}
  await sleep(120);await shot('up-'+String(Math.round(p*100)).padStart(3,'0'));info.held['up'+p]=await h2info();
 }
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await sleep(1200);await shot('10-focus');info.focusRects=await page.evaluate(()=>{const o={};for(const s of ['.timer-dial','#session-time','.music-heading','.music-heading h2','.music-heading p','.music-controls','.music-timeline','.session-actions','.music-cover img']){const n=document.querySelector(s);if(n){const r=n.getBoundingClientRect();o[s]=[r.left,r.top,r.width,r.height].map(v=>Math.round(v))}}return o});
 const s2=await page.evaluate(()=>{const r=document.querySelector('.music-heading h2').getBoundingClientRect();return {x:Math.round(r.left+30),y:Math.round(r.top+8)}});
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[s2]});y=s2.y;
 for(const p of [.9,.75,.5,.25,.1,0]){
  const to=s2.y+(8+(1-p)*range);while(y<to){y=Math.min(to,y+12);await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:s2.x,y}]});await sleep(16)}
  await sleep(120);await shot('down-'+String(Math.round(p*100)).padStart(3,'0'));info.held['down'+p]=await h2info();
 }
 await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await sleep(1200);await shot('20-compact-again');
 fs.writeFileSync(path.join(out,'info.json'),JSON.stringify(info,null,1));
 // Contact sheet: each frame at 1/3 scale with its name.
 const tile=W,th=H,cols=7,rows=Math.ceil(shots.length/cols);
 const composites=[];
 for(const [i,s] of shots.entries()){
  const buf=await sharp(s.file).resize(tile,th).toBuffer();
  const x=(i%cols)*(tile+8),yy=Math.floor(i/cols)*(th+28);
  composites.push({input:buf,left:x,top:yy+24});
  composites.push({input:Buffer.from(`<svg width="${tile}" height="22"><text x="4" y="16" font-family="Segoe UI" font-size="15" fill="#fff">${s.name}</text></svg>`),left:x,top:yy});
 }
 await sharp({create:{width:cols*(tile+8),height:rows*(th+28),channels:3,background:'#444'}}).composite(composites).png().toFile(path.join(out,'sheet.png'));
 await page.evaluate(()=>{Health.action('finish');Health.close()});
 await browser.close();
 console.log(JSON.stringify(info,null,1));
})().catch(e=>{console.error(e);process.exitCode=1});
