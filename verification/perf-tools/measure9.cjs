// How quickly the folded stack follows a finger: in-page timestamps for touch input, each deck layout
// and long tasks, for the first drag after load, a repeat drag and a drag right after a period change.
// Usage: node measure9.cjs <label> [cpuThrottle=4]
const {chromium}=require('playwright');
const fs=require('node:fs');
const label=process.argv[2]||'run',throttle=Number(process.argv[3]||4);
const URL='http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-drag-response';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 const cdp=await context.newCDPSession(page);
 await cdp.send('Emulation.setCPUThrottlingRate',{rate:throttle});
 page.on('pageerror',e=>console.log('pageerror',e.message));
 await page.goto(URL);await page.waitForSelector('#live-bar');await sleep(3000);
 await page.evaluate(()=>{
  const log=window.__log={deck:[],touches:[],long:[]};
  const layout=window.layoutDeck;window.layoutDeck=function(){const t=performance.now();layout.apply(this,arguments);log.deck.push({t,ms:performance.now()-t,p:deckProgress,content:parseFloat(/translateY\(([-\d.]+)px\)/.exec(document.querySelector('#period-content').style.transform)?.[1])})};
  for(const type of ['touchstart','touchmove','touchend'])document.addEventListener(type,e=>log.touches.push({type,t:e.timeStamp,y:e.touches[0]?.clientY??null}),{capture:true,passive:true});
  new PerformanceObserver(list=>{for(const e of list.getEntries())log.long.push({t:e.startTime,ms:e.duration})}).observe({type:'longtask'});
 });
 const start=await page.evaluate(()=>{const r=document.querySelector('#stack-open').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+40)}});
 const results={};
 async function drag(name,before){
  await page.evaluate(()=>{__log.deck.length=__log.touches.length=__log.long.length=0});
  if(before)await before();
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:start.x,y:start.y}]});
  let y=start.y;
  for(let i=0;i<22;i++){await sleep(16);y-=11;await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:start.x,y}]})}
  await sleep(16);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  await sleep(1400);
  const log=await page.evaluate(()=>JSON.parse(JSON.stringify(__log)));
  const touchStart=log.touches.find(t=>t.type==='touchstart'),moves=log.touches.filter(t=>t.type==='touchmove'),release=log.touches.find(t=>t.type==='touchend');
  const moving=log.deck.filter(d=>d.t>=touchStart.t&&(!release||d.t<=release.t+1)&&d.p>0);
  const first=moving[0],gaps=moving.slice(1).map((d,i)=>+(d.t-moving[i].t).toFixed(1));
  // Distance between the finger and the content on the first moved frame (px of lag or jump).
  const fingerAtFirst=[...moves].reverse().find(m=>m.t<=first.t);
  results[name]={
   firstMoveToFirstFrameMs:first&&moves[0]?+(first.t-moves[0].t).toFixed(1):null,
   firstFrameContentShiftPx:first?+((log.deck.find(d=>d.t<first.t)?.content??first.content)-first.content).toFixed(1):null,
   fingerTravelAtFirstFramePx:fingerAtFirst?touchStart.y-fingerAtFirst.y:null,
   frames:moving.length,maxGapMs:gaps.length?Math.max(...gaps):null,gapsOver20Ms:gaps.filter(g=>g>20).length,firstGaps:gaps.slice(0,8),
   layoutMsFirst:first?+first.ms.toFixed(2):null,layoutMsMedian:moving.length?+moving.map(d=>d.ms).sort((a,b)=>a-b)[Math.floor(moving.length/2)].toFixed(2):null,
   longTasks:log.long.filter(l=>l.t>=touchStart.t-5).map(l=>({atMs:+(l.t-touchStart.t).toFixed(0),ms:+l.ms.toFixed(0)}))
  };
  console.log(name,JSON.stringify(results[name]));
  await page.evaluate(()=>setDeckExpanded(false));await sleep(1600);
 }
 await drag('drag-1-first');
 await drag('drag-2-repeat');
 await drag('drag-3-after-period-change',async()=>{await page.evaluate(()=>cyclePeriod());await sleep(60)});
 fs.writeFileSync(`measure9-${label}.json`,JSON.stringify({label,throttle,results},null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
