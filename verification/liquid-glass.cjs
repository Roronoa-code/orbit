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
  const homeMaterial=await p.evaluate(()=>{
   const pick=(n,keys)=>Object.fromEntries(keys.map(k=>[k,getComputedStyle(n)[k]])),fill=['backgroundColor','backgroundImage','backdropFilter'],rim=['background','borderTopWidth','borderTopColor','borderRightColor','borderBottomColor','borderLeftColor','boxShadow'];
   return {nav:pick(document.querySelector('#live-island .island-frost'),fill),cards:[...document.querySelectorAll('.stack-motion')].map(n=>pick(n,fill)),navRim:pick(document.querySelector('#live-island .island-surface'),rim),cardRims:[...document.querySelectorAll('.stack-shell')].map(n=>pick(n,rim)),foreground:[...document.querySelectorAll('.stack-card')].map(n=>[getComputedStyle(n).filter,getComputedStyle(n).backdropFilter])};
  });
  homeMaterial.cards.forEach(c=>assert.deepEqual(c,homeMaterial.nav,'All four cards must use the bottom navbar material'));
  homeMaterial.cardRims.forEach(c=>assert.deepEqual(c,homeMaterial.navRim,'Card edges must match the navbar'));
  homeMaterial.foreground.forEach(c=>assert.deepEqual(c,['none','none'],'No extra filter on the readings'));
  await p.screenshot({path:path.join(out,`home-${width}.png`)});await p.evaluate(()=>setDeckExpanded(true));await p.waitForTimeout(1000);
  await p.screenshot({path:path.join(out,`cards-${width}.png`)});
  // Clone the real layers with identical geometry over one detailed backdrop. This catches a blur
  // nested inside the card's clip: matching CSS values alone did not mean it could sample behind it.
  await p.evaluate(()=>{
   const scene=document.createElement('div');scene.id='home-glass-proof';scene.style.cssText='position:fixed;inset:0;z-index:100;background:repeating-linear-gradient(90deg,#fa704b 0 18px,#456af5 18px 36px,#f1d45a 36px 54px)';
   const card=document.querySelector('.stack-motion').cloneNode(true),nav=document.querySelector('#live-island').cloneNode(true);
   card.style.cssText='position:absolute;left:18px;right:18px;top:80px;height:80px;transform:none;opacity:1;clip-path:inset(0 round 20px)';
   const reading=card.querySelector('.stack-card');reading.style.height='80px';for(const n of [...reading.children])if(!n.matches('.stack-shell,.stack-glass'))n.remove();card.querySelector('.stack-shell').style.transform='none';
   nav.style.cssText='position:absolute;left:18px;right:18px;top:80px;bottom:auto;height:80px;visibility:hidden';
   for(const n of [...nav.children])if(!n.matches('.island-frost,.island-surface'))n.remove();
   nav.querySelector('.island-frost').style.cssText='inset:0;height:auto;clip-path:inset(0 round 20px)';nav.querySelector('.island-surface').style.cssText='inset:0;width:auto;height:auto;transform:none;border-radius:20px';
   scene.append(card,nav);document.body.append(scene);
  });
  const sampleHome=async()=>sharp(await p.screenshot({clip:{x:22,y:84,width:width-44,height:72}})).raw().toBuffer();
  const cardPixels=await sampleHome();await p.locator('#home-glass-proof .stack-motion').evaluate(n=>n.style.visibility='hidden');await p.locator('#home-glass-proof #live-island').evaluate(n=>n.style.visibility='visible');const navPixels=await sampleHome();
  let materialDifference=0;for(let i=0;i<cardPixels.length;i++)materialDifference+=Math.abs(cardPixels[i]-navPixels[i]);
  assert(materialDifference/cardPixels.length<.1,'Card and navbar must render the same material over the same backdrop');
  await p.locator('#home-glass-proof .island-frost').evaluate(n=>{n.style.backdropFilter='none';n.style.webkitBackdropFilter='none'});const unblurred=await sampleHome();let backdropDifference=0;for(let i=0;i<cardPixels.length;i++)backdropDifference+=Math.abs(cardPixels[i]-unblurred[i]);assert(backdropDifference>10000,'Card glass must actually diffuse the external backdrop');
  await p.locator('#home-glass-proof').evaluate(n=>n.remove());
  await p.emulateMedia({forcedColors:'active'});assert.equal(await p.locator('.stack-motion').first().evaluate(n=>getComputedStyle(n).backdropFilter),'none');await p.emulateMedia({forcedColors:'none'});
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
  const result={width,math,material,homeMaterial,materialDifference,backdropDifference,mapMutations,lensPixelDifference:changed,forcedColors:fallback,errors};fs.writeFileSync(path.join(out,`checks-${width}.json`),JSON.stringify(result,null,2));console.log(width,{materialDifference,backdropDifference,mapMutations,lensPixelDifference:changed,forcedColors:fallback});await context.close();
 }}finally{await b.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
