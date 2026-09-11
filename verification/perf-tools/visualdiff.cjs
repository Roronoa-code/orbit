const {chromium}=require('playwright');const sharp=require('sharp');const fs=require('node:fs');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const OUT='C:/HA/design-concepts/signal-orbit-steps/verification/perf-visual-diff';fs.mkdirSync(OUT,{recursive:true});
async function shots(url,tag,css){const b=await chromium.launch({headless:true});const c=await b.newContext({viewport:{width:390,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true});const p=await c.newPage();
 await p.goto(url);await p.waitForSelector('#live-bar');if(process.env.ORBIT_HIDE_ORB)await p.addStyleTag({content:'#orb-canvas{visibility:hidden!important}'});if(css?.startsWith('jsfile:'))await p.evaluate(fs.readFileSync(css.slice(7),'utf8'));else if(css?.startsWith('js:'))await p.evaluate(css.slice(3));else if(css)await p.addStyleTag({content:css});await sleep(1500);
 await p.evaluate(()=>{orb.setPaused(true)});await sleep(300);const orbBox=await p.evaluate(()=>{const r=document.querySelector('#orb-canvas').getBoundingClientRect();return {x:r.left,y:r.top,w:r.width,h:r.height}});
 const states={folded:async()=>{},mid:async()=>{await p.evaluate(()=>{setDeckExpanded(true);stopDeckMotion();deckMotion.value=orbPose.value=.5;deckMotion.velocity=orbPose.velocity=0;deckProgress=.5;layoutDeck()})},open:async()=>{await p.evaluate(()=>{setDeckExpanded(true);stopDeckMotion();deckMotion.value=orbPose.value=1;deckMotion.velocity=orbPose.velocity=0;deckProgress=1;layoutDeck()})},live:async()=>{await p.evaluate(()=>{setDeckExpanded(false);stopDeckMotion();deckMotion.value=orbPose.value=0;deckProgress=0;layoutDeck();setLiveOpen(true)});await sleep(2200)}};
 const files={};for(const [name,fn] of Object.entries(states)){await fn();await sleep(250);files[name]=`${OUT}/${tag}-${name}.png`;await p.screenshot({path:files[name]})}
 files.orbBox=orbBox;
 await b.close();return files}
(async()=>{
 const before=await shots('http://127.0.0.1:8784/_orbit_before_tmp/index.html?perf-audit','before');
 const after=await shots('http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit','after',process.argv[2]||'');
 const ob=before.orbBox;for(const name of Object.keys(before)){if(name==='orbBox')continue;
  const a=await sharp(before[name]).raw().toBuffer({resolveWithObject:true}),b=await sharp(after[name]).raw().toBuffer({resolveWithObject:true});
  let diff=0,strong=0;const out=Buffer.alloc(a.data.length);
  for(let i=0;i<a.data.length;i+=a.info.channels){const d=Math.max(Math.abs(a.data[i]-b.data[i]),Math.abs(a.data[i+1]-b.data[i+1]),Math.abs(a.data[i+2]-b.data[i+2]));const px=(i/a.info.channels)%a.info.width/2,py=Math.floor(i/a.info.channels/a.info.width)/2;const inOrb=px>=ob.x&&px<=ob.x+ob.w&&py>=ob.y&&py<=ob.y+ob.h;if(!inOrb){if(d>8)diff++;if(d>40)strong++}out[i]=out[i+1]=out[i+2]=d>8?255:Math.round(a.data[i]*.25);if(a.info.channels===4)out[i+3]=255}
  const total=a.info.width*a.info.height;
  await sharp(out,{raw:{width:a.info.width,height:a.info.height,channels:a.info.channels}}).png().toFile(`${OUT}/diff-${name}.png`);
  console.log(name.padEnd(8),'pixels >8 diff:',diff,`(${(diff/total*100).toFixed(3)}%)`,'| >40 diff:',strong,`(${(strong/total*100).toFixed(3)}%)`);
 }
})().catch(e=>{console.error(e);process.exitCode=1});
