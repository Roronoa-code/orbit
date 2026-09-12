// Run against the preview, or pass --phone after forwarding the Audit WebView to 9224.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const phone=process.argv.includes('--phone'),out=path.join(__dirname,'player-settings-20260912','home-motion');
(async()=>{
 const b=phone?await chromium.connectOverCDP('http://127.0.0.1:9224'):await chromium.launch({headless:true});
 try{for(const width of phone?[384]:[390,384,320]){
  const context=phone?b.contexts()[0]:await b.newContext({viewport:{width,height:832},isMobile:true,hasTouch:true});
  const p=phone?context.pages()[0]:await context.newPage();if(!phone)await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
  const result=await p.evaluate(async()=>{
   const wait=ms=>new Promise(r=>setTimeout(r,ms)),check=(v,m)=>{if(!v)throw Error(m)},event={cancelable:true,preventDefault(){}};
   Health.close();setDeckExpanded(false);setLiveOpen(false);await wait(1100);
   const bar=document.querySelector('#live-bar'),stack=document.querySelector('#stack-open');
   // Catch an in-flight transition after touch-down but before the finger leaves touch slop.
   for(const live of [false,true]){
    if(live)setLiveOpen(true);else setDeckExpanded(true);await wait(90);
    startDeckGesture(200,500,live?bar:stack,9);await wait(110);
    const y=live?488:512,before=live?islands.live.value:deckProgress;moveDeckGesture(200,y,event);
    const after=live?islands.live.value:deckMotion.value;
    check(Math.abs(after-before)<.07,'A second swipe jumped back to its touch-down position');
    await wait(160);const opacity=Number(getComputedStyle(panelContent).opacity);endDeckGesture(200,y);
    check((live?islands.live.velocity:deckMotion.velocity)===0,'Held finger released with stale momentum');
    if(live)check(Math.abs(Number(getComputedStyle(panelContent).opacity)-opacity)<.001,'Launcher fade jumped on release');
    await wait(900);setLiveOpen(false);setDeckExpanded(false);await wait(1000);
   }
   // A launcher drag must not freeze a stack that is returning underneath it.
   setDeckExpanded(true);await wait(300);startDeckGesture(200,700,bar,9);moveDeckGesture(200,660,event);
   const before=deckProgress;await wait(200);check(deckProgress<before-.05,'Live drag froze the returning cards');endDeckGesture(200,660);await wait(1000);setLiveOpen(false);await wait(1000);
   const motionHeights=[...document.querySelectorAll('.stack-motion')].map(n=>n.offsetHeight);
   let maxControlDrift=0,maxCardHeightDrift=0;
   for(const active of [false,true]){
    if(active)Health.action('start','Strength');await wait(500);
    const base=bar.getBoundingClientRect(),label=bar.querySelector('.live-title'),range=document.createRange();range.selectNodeContents(label);const textHeight=range.getBoundingClientRect().height;
    for(const open of [true,false,true,false]){
     setLiveOpen(open);
     for(let i=0;i<48;i++){await new Promise(requestAnimationFrame);const r=bar.getBoundingClientRect();maxControlDrift=Math.max(maxControlDrift,Math.abs(r.height-base.height),Math.abs(r.width-base.width),Math.abs(r.bottom-base.bottom),Math.abs(range.getBoundingClientRect().height-textHeight))}
    }
    await wait(1000);if(active)Health.action('finish');
   }
   for(const open of [true,false]){
    setDeckExpanded(open);for(let i=0;i<50;i++){await new Promise(requestAnimationFrame);document.querySelectorAll('.stack-motion').forEach((n,i)=>{maxCardHeightDrift=Math.max(maxCardHeightDrift,Math.abs(n.offsetHeight-motionHeights[i]))})}await wait(1000);
   }
   check(maxControlDrift<.1,'Launcher controls resized during shell movement: '+maxControlDrift);check(maxCardHeightDrift===0,'Card layout height changed during fold');
   setDeckExpanded(true);await wait(1000);deckScroller.scrollTop=160;await wait(80);check(Number(getComputedStyle(deckFrost).opacity)>.99,'Scroll fog missing');setDeckExpanded(false);await wait(1100);check(deckScroller.scrollTop===0&&Number(getComputedStyle(deckFrost).opacity)===0,'Fold left stale scrolling or fog');
   return {maxControlDrift,maxCardHeightDrift,secondSwipe:true,heldRelease:true,coordinatedDrag:true,scrollReset:true};
  });
  assert(result.secondSwipe);fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,`${phone?'phone':'browser'}-${width}.json`),JSON.stringify(result,null,2));console.log(width,result);
  if(!phone)await context.close();
 }}finally{await b.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
