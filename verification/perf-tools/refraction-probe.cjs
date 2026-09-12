// Does this engine actually displace backdrop pixels through an SVG filter, or only blur them?
// Renders a checkerboard with three capsules and measures the pixels each one produces.
const {chromium}=require('playwright');const sharp=require('sharp');
const path=require('node:path'),fs=require('node:fs');
const out=path.resolve(process.argv[2]||'refraction');fs.mkdirSync(out,{recursive:true});

const page=`<!doctype html><meta charset="utf-8"><style>
body{margin:0;background:#0a0a0c}
.scene{position:relative;width:390px;height:300px;overflow:hidden;
 background-image:repeating-linear-gradient(45deg,#fff 0 12px,#000 12px 24px),repeating-linear-gradient(-45deg,#f0f 0 8px,#0ff 8px 16px);background-blend-mode:difference}
.cap{position:absolute;left:40px;width:150px;height:56px;border-radius:28px;
 box-shadow:inset 0 1px 0 #ffffff55,inset 0 0 0 1px #ffffff33}
#blurOnly{top:20px;backdrop-filter:blur(14px) saturate(.95);-webkit-backdrop-filter:blur(14px) saturate(.95);background:linear-gradient(180deg,#cbb6ff55,#b69cff33)}
#refract{top:110px;backdrop-filter:url(#lens) blur(6px) saturate(.95);-webkit-backdrop-filter:url(#lens) blur(6px) saturate(.95);background:linear-gradient(180deg,#cbb6ff33,#b69cff1a)}
#plain{top:200px;background:#2a2733}
</style>
<div class="scene">
 <div class="cap" id="blurOnly"></div>
 <div class="cap" id="refract"></div>
 <div class="cap" id="plain"></div>
</div>
<svg width="0" height="0" style="position:absolute">
 <filter id="lens" x="0" y="0" width="100%" height="100%" filterUnits="objectBoundingBox" primitiveUnits="objectBoundingBox" color-interpolation-filters="sRGB">
   <feImage href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='150' height='56'%3E%3Cdefs%3E%3ClinearGradient id='x' x1='0' x2='1'%3E%3Cstop offset='0' stop-color='rgb(255,128,0)'/%3E%3Cstop offset='0.14' stop-color='rgb(128,128,0)'/%3E%3Cstop offset='0.86' stop-color='rgb(128,128,0)'/%3E%3Cstop offset='1' stop-color='rgb(0,128,0)'/%3E%3C/linearGradient%3E%3ClinearGradient id='y' x1='0' y1='0' x2='0' y2='1'%3E%3Cstop offset='0' stop-color='rgb(0,255,0)' stop-opacity='0.55'/%3E%3Cstop offset='0.3' stop-color='rgb(0,128,0)' stop-opacity='0'/%3E%3Cstop offset='0.7' stop-color='rgb(0,128,0)' stop-opacity='0'/%3E%3Cstop offset='1' stop-color='rgb(0,0,0)' stop-opacity='0.55'/%3E%3C/linearGradient%3E%3C/defs%3E%3Crect width='150' height='56' fill='url(%23x)'/%3E%3Crect width='150' height='56' fill='url(%23y)'/%3E%3C/svg%3E"
     result="map" preserveAspectRatio="none" x="0" y="0" width="1" height="1"/>
   <feDisplacementMap in="SourceGraphic" in2="map" scale="0.12" xChannelSelector="R" yChannelSelector="G"/>
 </filter>
</svg>`;

(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:300},deviceScaleFactor:2});
 const tab=await context.newPage();
 await tab.setContent(page);
 await tab.waitForTimeout(500);
 const supports=await tab.evaluate(()=>({
   backdrop:CSS.supports('backdrop-filter','blur(1px)'),
   urlBackdrop:CSS.supports('backdrop-filter','url(#lens)'),
   computed:getComputedStyle(document.querySelector('#refract')).backdropFilter,
 }));
 await tab.screenshot({path:path.join(out,'probe.png')});
 // Compare the pixels inside each capsule with the same region of the untouched pattern.
 const shot=await tab.screenshot();
 const image=sharp(shot);
 const stats=async(top)=>{
   const region=await image.clone().extract({left:80,top:top*2,width:140,height:80}).raw().toBuffer({resolveWithObject:true});
   const d=region.data;let sum=0,sq=0,n=0;
   for(let i=0;i<d.length;i+=region.info.channels){const v=(d[i]+d[i+1]+d[i+2])/3;sum+=v;sq+=v*v;n++}
   const mean=sum/n;return {mean:+mean.toFixed(2),deviation:+Math.sqrt(sq/n-mean*mean).toFixed(2)};
 };
 const report={supports,blurOnly:await stats(26),refract:await stats(116),plain:await stats(206),pattern:await stats(86)};
 fs.writeFileSync(path.join(out,'probe.json'),JSON.stringify(report,null,1));
 console.log(JSON.stringify(report,null,1));
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
