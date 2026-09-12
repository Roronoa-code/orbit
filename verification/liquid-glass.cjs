const {chromium}=require('playwright'),sharp=require('sharp'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'player-settings-20260912','glass-port');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const b=await chromium.launch({headless:true});
 try{for(const width of [390,320]){
  const context=await b.newContext({viewport:{width,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true}),p=await context.newPage(),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.waitForTimeout(700);
  const math=await p.evaluate(()=>{const f=LiquidGlass.displacement,centre=f(180,70,360,140,28),left=f(3,70,360,140,28),right=f(357,70,360,140,28);return{centre,left,right}});
  assert.deepEqual(math.centre,[0,0]);assert(math.left[0]>0&&math.right[0]<0);assert(Math.abs(math.left[0]+math.right[0])<1e-8);
  await p.evaluate(()=>{Health.open('body');document.activeElement.blur()});await p.waitForTimeout(600);
  const material=await p.locator('.body-metric-picker').evaluate(n=>({filter:getComputedStyle(n).backdropFilter,tint:getComputedStyle(n).backgroundColor,indicator:getComputedStyle(n.querySelector('.selection-pill')).backgroundColor}));
  assert(material.filter.includes('blur(8px)')&&material.filter.includes('saturate(1.5)')&&material.filter.includes('url('));assert.equal(material.tint,'rgba(18, 18, 18, 0.4)');assert.equal(material.indicator,'rgba(0, 0, 0, 0.5)');
  const mapMutations=await p.evaluate(async()=>{let edits=0;const observer=new MutationObserver(records=>edits+=records.length);document.querySelectorAll('filter[id^="orbit-glass-"] feImage').forEach(n=>observer.observe(n,{attributes:true}));for(const metric of ['fatMass','muscle','lean','weight']){document.querySelector('[data-body-metric="'+metric+'"]').click();await new Promise(r=>setTimeout(r,450))}observer.disconnect();return edits});assert.equal(mapMutations,0,'Dragging/settling must reuse the lens geometry');
  await p.screenshot({path:path.join(out,`body-${width}.png`)});
  // Real backdrop sampling over colour and fine detail: changing the underlay and switching off the lens must both change pixels.
  await p.evaluate(()=>{Health.close();const scene=document.createElement('div');scene.id='glass-proof';scene.style.cssText='position:fixed;inset:0;z-index:100;background:repeating-linear-gradient(90deg,#fa704b 0 18px,#456af5 18px 36px,#f1d45a 36px 54px)';scene.innerHTML='<div class="glass-track" style="position:absolute;left:18px;right:18px;top:80px;height:80px;border-radius:28px"><span style="display:block;text-align:center;padding-top:25px;color:white;font-size:18px">Orbit glass</span></div>';document.body.append(scene);LiquidGlass.enhance(scene)});await p.waitForTimeout(250);
  const raw=async()=>sharp(await p.screenshot({clip:{x:20,y:82,width:width-40,height:76}})).raw().toBuffer(),lens=await raw();
  await p.locator('#glass-proof .glass-track').evaluate(n=>n.style.setProperty('--glass-lens','blur(0px)'));const plain=await raw();let changed=0;for(let i=0;i<lens.length;i++)changed+=Math.abs(lens[i]-plain[i]);assert(changed>10000,'The lens must actually refract sampled pixels');
  await p.emulateMedia({forcedColors:'active'});const fallback=await p.locator('#glass-proof .glass-track').evaluate(n=>getComputedStyle(n).backdropFilter);assert.equal(fallback,'none');
  await p.emulateMedia({forcedColors:'none'});await p.locator('#glass-proof').evaluate(n=>n.remove());assert.deepEqual(errors,[]);
  const result={width,math,material,mapMutations,lensPixelDifference:changed,forcedColors:fallback,errors};fs.writeFileSync(path.join(out,`checks-${width}.json`),JSON.stringify(result,null,2));console.log(width,{mapMutations,lensPixelDifference:changed,forcedColors:fallback});await context.close();
 }}finally{await b.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
