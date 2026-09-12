/* Rounded-rectangle lens adapted from Kyant0/backdrop v2.0.0, as vendored by BitChord.
 * Copyright 2025 Kyant. Apache-2.0; see licenses/Kyant-backdrop-LICENSE.txt.
 * Modified for WebView: cache a low-resolution displacement field, then let the browser sample the live backdrop.
 * The foreground is never part of this filter. This is a web adaptation, not Compose's RenderNode pipeline.
 */
'use strict';
window.LiquidGlass=(()=>{
  const ns='http://www.w3.org/2000/svg',entries=new Map(),selector='.glass-track,.masthead .icon-button,.health-page-head .icon-button,.island-frost';
  const preference=matchMedia('(prefers-reduced-transparency:reduce), (prefers-contrast:more), (forced-colors:active)');
  let defs,serial=0;
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  // Kyant's rounded-rectangle distance, outward normal and circular lens profile.
  function displacement(x,y,width,height,radius){
    const hx=width/2,hy=height/2,cx=x-hx,cy=y-hy,qx=Math.abs(cx)-(hx-radius),qy=Math.abs(cy)-(hy-radius);
    const distance=Math.hypot(Math.max(qx,0),Math.max(qy,0))-radius+Math.min(Math.max(qx,qy),0),depth=24;
    if(-distance>=depth)return [0,0];
    const gradRadius=Math.min(radius*1.5,hx,hy),gx=Math.abs(cx)-(hx-gradRadius),gy=Math.abs(cy)-(hy-gradRadius);
    let nx,ny;if(gx>=0||gy>=0){nx=Math.max(gx,0);ny=Math.max(gy,0);const length=Math.hypot(nx,ny)||1;nx/=length;ny/=length}else{nx=gx>=gy?1:0;ny=1-nx}
    const centre=Math.hypot(cx,cy)||1;nx=nx*Math.sign(cx)+cx/centre;ny=ny*Math.sign(cy)+cy/centre;
    const length=Math.hypot(nx,ny)||1,t=clamp(1+Math.min(distance,0)/depth,0,1),bend=-24*(1-Math.sqrt(1-t*t));
    return [bend*nx/length,bend*ny/length];
  }
  function element(tag,attributes){const node=document.createElementNS(ns,tag);for(const [k,v] of Object.entries(attributes))node.setAttribute(k,v);return node}
  function update(entry){
    const node=entry.node,w=node.offsetWidth,h=node.offsetHeight;if(w<=0||h<=0||preference.matches)return;
    const radius=node.closest('#live-island')?31:node.closest('#utility-island')?24:Math.min(w/2,h/2,parseFloat(getComputedStyle(node).borderTopLeftRadius)||h/2),key=[w,h,radius].join(':');
    if(entry.key===key)return;entry.key=key;
    // The map is geometry, not a screenshot; 1/3 resolution needs nine times fewer generated pixels.
    const canvas=document.createElement('canvas');canvas.width=Math.max(2,Math.ceil(w/3));canvas.height=Math.max(2,Math.ceil(h/3));
    const ctx=canvas.getContext('2d'),pixels=ctx.createImageData(canvas.width,canvas.height);
    for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++){
      const [dx,dy]=displacement((x+.5)*w/canvas.width,(y+.5)*h/canvas.height,w,h,radius),i=(y*canvas.width+x)*4;
      pixels.data[i]=Math.round((.5+dx/48)*255);pixels.data[i+1]=Math.round((.5+dy/48)*255);pixels.data[i+2]=128;pixels.data[i+3]=255;
    }
    ctx.putImageData(pixels,0,0);entry.image.setAttribute('href',canvas.toDataURL());entry.image.setAttribute('width',w);entry.image.setAttribute('height',h);
    entry.filter.setAttribute('width',w+48);entry.filter.setAttribute('height',h+48);
    node.style.setProperty('--glass-lens',`url(#${entry.filter.id})`);node.classList.add('liquid-surface');
  }
  const observer=new ResizeObserver(records=>{for(const record of records){const entry=entries.get(record.target);if(entry)update(entry)}});
  function enhance(root=document){
    if(!defs){const svg=element('svg',{'aria-hidden':'true',width:0,height:0});svg.style.cssText='position:absolute;pointer-events:none';defs=element('defs',{});svg.append(defs);document.body.append(svg)}
    for(const [node,entry] of entries)if(!node.isConnected){observer.unobserve(node);entry.filter.remove();entries.delete(node)}
    for(const node of root.querySelectorAll(selector))if(!entries.has(node)){
      const filter=element('filter',{id:'orbit-glass-'+(++serial),filterUnits:'userSpaceOnUse',x:-24,y:-24,'color-interpolation-filters':'sRGB'});
      const image=element('feImage',{x:0,y:0,preserveAspectRatio:'none',result:'lens'});
      filter.append(image,element('feDisplacementMap',{in:'SourceGraphic',in2:'lens',scale:48,xChannelSelector:'R',yChannelSelector:'G'}));defs.append(filter);
      const entry={node,filter,image,key:''};entries.set(node,entry);observer.observe(node);update(entry);
    }
  }
  preference.addEventListener('change',()=>{if(!preference.matches)for(const entry of entries.values())update(entry)});
  return {enhance,displacement};
})();
