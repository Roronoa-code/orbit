// The workout view <-> page-wide music view move (tap open, tap close, finger drag down): frame gaps,
// heavy lifecycle frames, raster time and image repaints while it runs. Browser-only music fixture.
// Usage: node measure12.cjs <label> [cpuThrottle=4]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'run',throttle=Number(process.argv[3]||4);
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const WINDOW_US=900000;
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage(),cdp=await context.newCDPSession(page);
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
 page.on('pageerror',e=>console.log('pageerror',e.message));
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-morph');await page.waitForSelector('#live-bar');await sleep(1500);
 await page.evaluate(()=>{
  const canvas=document.createElement('canvas');canvas.width=canvas.height=1200;const c=canvas.getContext('2d'),g=c.createLinearGradient(0,0,1200,1200);g.addColorStop(0,'#1d2b4a');g.addColorStop(.55,'#3a2c3f');g.addColorStop(1,'#0e0f14');c.fillStyle=g;c.fillRect(0,0,1200,1200);
  for(let i=0;i<60;i++){c.fillStyle=i%3?'#ff8a3c':'#ffd27a';c.fillRect(40+i*19,650+Math.sin(i)*60,12,180+((i*37)%120))}
  const snapshot={status:'ready',id:'s:1',artKey:'a:1',art:canvas.toDataURL('image/jpeg',.92),title:'drunk text',artist:'Henry Moodie',source:'Test music app',position:112000,duration:187000,playing:true,buffering:false,canToggle:true,canSeek:true,canPrevious:true,canNext:true,canOpen:true};
  window.OrbitMusic={read(key){const next={...snapshot};if(key===next.artKey)delete next.art;return JSON.stringify(next)},command(){return true},connect(){}};
  if(!Health.state.active)Health.action('start','Walking',1800000);Health.open('workouts');
  window.__frames=[];let last=performance.now();const tick=t=>{window.__frames.push(t-last);last=t;requestAnimationFrame(tick)};requestAnimationFrame(tick);
 });
 await sleep(3000);
 await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','devtools.timeline','blink','cc']});
 const results={};
 async function scenario(name,action){
  await page.evaluate(n=>{__frames.length=0;console.timeStamp(n)},name);
  await action();await sleep(900);
  const frames=await page.evaluate(()=>__frames.slice());
  results[name]={frames:frames.length,maxGapMs:+Math.max(...frames).toFixed(1),gapsOver20Ms:frames.filter(f=>f>20).length};
  await sleep(500);
 }
 // A real tap (touch down, lift, click), as on the phone.
 await scenario('tap-open',async()=>{const at=await page.evaluate(()=>{const b=document.querySelector('[data-music-expand]').getBoundingClientRect();return {x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)}});await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[at]});await sleep(90);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})});
 await scenario('tap-close',()=>page.evaluate(()=>document.querySelector('[data-timer-focus]').click()));
 await page.evaluate(()=>document.querySelector('[data-music-expand]').click());await sleep(1200);
 const start=await page.evaluate(()=>{const r=document.querySelector('.music-heading h2').getBoundingClientRect();return {x:Math.round(r.left+40),y:Math.round(r.top+10)}});
 await scenario('drag-close',async()=>{
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[start]});let y=start.y;
  for(let i=0;i<14;i++){await sleep(16);y+=14;await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]})}
  await sleep(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 });
 const events=JSON.parse((await browser.stopTracing()).toString('utf8')).traceEvents||[];
 const main=events.find(e=>e.ph==='M'&&e.name==='thread_name'&&e.args?.name==='CrRendererMain');
 for(const mark of events.filter(e=>e.name==='TimeStamp'&&/^(tap|drag)-/.test(e.args?.data?.message||''))){
  const inWindow=e=>e.ts>=mark.ts&&e.ts<mark.ts+WINDOW_US;
  const heavy=events.filter(e=>inWindow(e)&&e.ph==='X'&&e.pid===main.pid&&e.tid===main.tid&&e.name==='WebFrameWidgetImpl::UpdateLifecycle'&&e.dur>6000).map(e=>({atMs:+((e.ts-mark.ts)/1000).toFixed(0),ms:+(e.dur/1000).toFixed(1)}));
  const raster=events.filter(e=>inWindow(e)&&e.ph==='X'&&/RasterTask|ImageDecodeTask/.test(e.name)).reduce((s,e)=>s+e.dur/1000,0);
  const images={};for(const e of events)if(inWindow(e)&&e.name==='PaintImage'){const k=`${e.args?.data?.srcWidth}x${e.args?.data?.srcHeight}`;(images[k]??=[]).push(Math.round((e.ts-mark.ts)/1000))}
  Object.assign(results[mark.args.data.message],{heavyLifecycleFrames:heavy,rasterMs:+raster.toFixed(1),paintImages:images});
 }
 for(const [name,run] of Object.entries(results))console.log(name.padEnd(11),JSON.stringify(run));
 fs.writeFileSync(`measure12-${label}.json`,JSON.stringify({label,throttle,results},null,1));
 await page.evaluate(()=>{Health.action('finish');Health.close()});
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
