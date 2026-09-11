// Captures the Explore launcher and the Body page (top, bottom and full length) for before/after review.
// Usage (from this folder, static server on 8784 rooted at C:\HA\design-concepts):
//   node body-shots.cjs <outDir> [width=384] [height=832]
// 384 x 832 CSS px is the Galaxy S25 Ultra viewport (1440 x 3120 at 3.75 device pixels per CSS pixel).
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.argv[2]||'body-shots'),W=Number(process.argv[3]||384),H=Number(process.argv[4]||832);
fs.mkdirSync(out,{recursive:true});
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:W,height:H},deviceScaleFactor:2,isMobile:true,hasTouch:true});
 const page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
 await page.waitForSelector('#live-bar');await page.evaluate(()=>document.fonts.ready);await sleep(700);
 const shot=name=>page.screenshot({path:path.join(out,name+'.png')});
 await page.click('#live-bar');await sleep(1000);await shot('launcher');
 await page.click('[data-activity="body"]');await sleep(1100);await shot('body-top');
 await page.evaluate(()=>{const s=document.querySelector('#health-scroll');s.scrollTop=s.scrollHeight});await sleep(600);await shot('body-bottom');
 const layout=await page.evaluate(()=>{const o={};for(const s of ['.health-page-head','.body-hero','.body-composition','.body-metric-picker','.body-timeline','.body-chart','.health-note']){const n=document.querySelector(s);if(n){const r=n.getBoundingClientRect();o[s]=[r.left,r.top,r.width,r.height].map(Math.round)}}o.scrollHeight=document.querySelector('#health-scroll').scrollHeight;return o});
 await page.evaluate(()=>{document.querySelector('#health-scroll').scrollTop=0});
 await page.setViewportSize({width:W,height:Math.max(H,layout.scrollHeight+120)});await sleep(700);await shot('body-full');
 fs.writeFileSync(path.join(out,'layout.json'),JSON.stringify({W,H,layout,errors},null,1));
 console.log(JSON.stringify({W,H,layout,errors}));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
