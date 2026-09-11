// Frames of the Body ring's horizontal switch: a held drag at several positions, its release, a short drag that
// springs back, and a tap on a distant selector entry. Writes numbered PNGs, sheet.png and states.json.
// Usage (NODE_PATH must also reach sharp): node body-swipe-frames.cjs <outDir> [width=384] [height=832]
const {chromium}=require('playwright');const sharp=require('sharp');
const fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.argv[2]||'body-swipe'),W=Number(process.argv[3]||384),H=Number(process.argv[4]||832);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:2,isMobile:true,hasTouch:true});
 const page=await context.newPage(),cdp=await context.newCDPSession(page),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await page.waitForSelector('#live-bar');
 await page.evaluate(()=>{Health.open('body')});await page.evaluate(()=>document.fonts.ready);await sleep(900);
 const box=await page.evaluate(()=>{const a=document.querySelector('.body-composition').getBoundingClientRect(),b=document.querySelector('.body-metric-picker').getBoundingClientRect();return {cx:a.left+a.width/2,cy:a.top+a.height/2,top:a.top,bottom:b.bottom}});
 const clip={x:0,y:Math.max(0,box.top-8),width:W,height:box.bottom-box.top+16};
 const shots=[],states=[];
 const state=()=>page.evaluate(()=>{const pill=document.querySelector('.body-metric-picker>.selection-pill').getBoundingClientRect(),pressed=document.querySelector('[data-body-metric][aria-pressed="true"]');return {metric:pressed.dataset.bodyMetric,pillLeft:Math.round(pill.left*10)/10,current:document.querySelector('.body-reading.is-current').style.transform,next:document.querySelector('.body-reading.is-next').style.transform}});
 async function shot(name){const file=path.join(out,String(shots.length).padStart(2,'0')+'-'+name+'.png');await page.screenshot({path:file,clip});shots.push({name,file});states.push({name,...await state()})}
 const touch=(type,x,y)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'?[]:[{x,y}]});
 await shot('rest');
 // Drag left (towards the next measurement), holding at several distances.
 let x=box.cx+60;const y=box.cy+20;await touch('touchStart',x,y);
 for(const to of [box.cx+30,box.cx-10,box.cx-50,box.cx-100]){while(x>to){x=Math.max(to,x-6);await touch('touchMove',x,y);await sleep(16)}await sleep(100);await shot('held-'+Math.round(box.cx+60-to)+'px')}
 await touch('touchEnd');await sleep(90);await shot('released-90ms');await sleep(700);await shot('settled');
 // A short drag right springs back to the same measurement.
 x=box.cx;await touch('touchStart',x,y);while(x<box.cx+40){x+=5;await touch('touchMove',x,y);await sleep(16)}await sleep(80);await shot('short-held');
 await touch('touchEnd');await sleep(700);await shot('sprung-back');
 // A tap on a distant entry runs the same move over one lens width while the pill travels the row.
 await page.tap('[data-body-metric="lean"]');await sleep(70);await shot('tap-70ms');await sleep(110);await shot('tap-180ms');await sleep(600);await shot('tap-settled');
 // From the first measurement a drag back loops round to the last.
 await page.tap('[data-body-metric="weight"]');await sleep(800);x=box.cx-20;await touch('touchStart',x,y);while(x<box.cx+100){x+=6;await touch('touchMove',x,y);await sleep(16)}await sleep(80);await shot('loop-held');await touch('touchEnd');await sleep(700);await shot('loop-settled');
 // Press the selector and slide across the labels: the capsule follows, the labels stay, release picks.
 const labels=await page.evaluate(()=>[...document.querySelectorAll('[data-body-metric]')].map(n=>{const r=n.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2}}));
 if(labels.length>2){x=labels[0].x;const ly=labels[0].y;await touch('touchStart',x,ly);while(x<labels[2].x){x=Math.min(labels[2].x,x+7);await touch('touchMove',x,ly);await sleep(16)}await sleep(60);await shot('selector-drag');await touch('touchEnd');await sleep(90);await shot('selector-released');await sleep(700);await shot('selector-settled')}
 fs.writeFileSync(path.join(out,'states.json'),JSON.stringify({W,H,states,errors},null,1));
 const tileW=W,tileH=clip.height,cols=4,rows=Math.ceil(shots.length/cols),composites=[];
 for(const [i,s] of shots.entries()){const left=(i%cols)*(tileW+8),top=Math.floor(i/cols)*(tileH+30);composites.push({input:await sharp(s.file).resize(tileW,Math.round(tileH)).toBuffer(),left,top:top+26});composites.push({input:Buffer.from(`<svg width="${tileW}" height="24"><text x="4" y="17" font-family="Segoe UI" font-size="15" fill="#fff">${s.name}</text></svg>`),left,top})}
 await sharp({create:{width:cols*(tileW+8),height:rows*(Math.round(tileH)+30),channels:3,background:'#444'}}).composite(composites).png().toFile(path.join(out,'sheet.png'));
 console.log(JSON.stringify({states,errors},null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
