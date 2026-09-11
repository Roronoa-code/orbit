/* Keep the timer in place through focus changes; display only media shared by Android. */
'use strict';
const WorkoutFocus=(()=>{
  const animations=new Set();
  let dots=null,dotsCanvas=null;
  // Mounting is idempotent: the canvas starts drawing when a move begins and keeps its particles when it settles.
  function attach(live,focused){
    const canvas=live.querySelector('#focus-time-dots');
    if(focused&&dots&&dotsCanvas===canvas)return;
    dots?.stop();dots=null;dotsCanvas=null;
    if(focused&&canvas){dots=HeroDots.mount(canvas,live.querySelector('#session-time').dataset.reading||'00:00',true);dotsCanvas=canvas;canvas.onclick=event=>event.stopPropagation()}
  }
  function dispose(){finish();settle();dots?.stop();dots=null;dotsCanvas=null}
  function settle(){for(const animation of animations)animation.cancel();animations.clear()}
  function animate(node,frames,duration=500){if(document.hidden)return;const animation=node.animate(frames,{duration,easing:'cubic-bezier(.2,.75,.2,1)'});animations.add(animation);animation.onfinish=()=>{animations.delete(animation);animation.cancel()}}
  function layout(live,update){
    finish();
    const nodes=[live.querySelector('.timer-dial'),live.querySelector('.session-actions')],before=nodes.map(n=>n.getBoundingClientRect()),page=document.querySelector('#health-page'),scale=page.getBoundingClientRect().width/page.offsetWidth||1;
    settle();update();
    nodes.forEach((node,i)=>{const after=node.getBoundingClientRect(),old=before[i];animate(node,[{transformOrigin:'0 0',transform:`translate(${(old.left-after.left)/scale}px,${(old.top-after.top)/scale}px) scale(${old.width/after.width},${old.height/after.height})`},{transformOrigin:'0 0',transform:'none'}])});
  }

  /* One progress value p moves the workout view (0) and the page-wide music view (1) into each other, so a
     tap and a finger drag travel the same path and reverse from wherever they are. The page keeps the layout
     it started in: parts that exist only in the other layout wait at their measured places and fade in, parts
     of this layout fade out in place, shared parts move and scale, and the cover image itself travels between
     the page and the small player. The clock reading keeps its proportions and stays visible the whole way;
     the dial ring and its labels fade. Every frame writes transforms and opacity, plus an explicit width on the
     two metadata lines so their text is ellipsised inside the region the move has reached, never under the
     transport controls. Treatment that cannot be interpolated - surfaces, the selected reading control, the
     artwork-derived actions, the dial's own label - crosses over at half progress instead of at the release. */
  const SHARED='#session-time,.timer-mode-picker,.session-actions,.music-heading h2,.music-heading p,.music-controls button';
  const UNIFORM='.music-heading h2,.music-heading p,.music-controls button';
  const ONLY='.workout-live-top,.workout-unconnected,.target-remaining,.music-tile,.music-thumb,.music-timeline,.music-heading [data-music="open"],.timer-orbit,#timer-label,.timer-tap-hint';
  const TEXT='.music-heading h2,.music-heading p',CONTROL='.music-controls button';
  const HELD=['position','left','top','width','height','margin','display','z-index','box-sizing','pointer-events','opacity'];
  let morph=null,frame=0,drag=null,warm=0,swallowClick=false,hooks={target(){}};
  const clamp=n=>Math.max(0,Math.min(1,n)),smooth=(p,a,b)=>{const t=clamp((p-a)/(b-a));return t*t*(3-2*t)},mix=(a,b,t)=>a+(b-a)*t;
  const between=(a,b,t)=>({x:mix(a.x,b.x,t),y:mix(a.y,b.y,t),w:mix(a.w,b.w,t),h:mix(a.h,b.h,t)});
  const host=()=>document.querySelector('#health-page');
  function geometry(){const page=host(),box=page.getBoundingClientRect();return {box,scale:box.width/page.offsetWidth||1}}
  function rect(node,g){const r=node.getBoundingClientRect();return r.width&&r.height?{x:(r.left-g.box.left)/g.scale,y:(r.top-g.box.top)/g.scale,w:r.width/g.scale,h:r.height/g.scale}:null}
  function measure(live,g){
    const shared=new Map(),only=new Map();
    for(const node of live.querySelectorAll(SHARED)){const r=rect(node,g);if(r)shared.set(node,r)}
    for(const node of live.querySelectorAll(ONLY)){const r=rect(node,g);if(r)only.set(node,{rect:r,display:getComputedStyle(node).display})}
    const matrix=live.querySelector('.session-matrix'),canvas=live.querySelector('#focus-time-dots');
    return {shared,only,clock:{matrix:matrix&&rect(matrix,g),canvas:canvas&&rect(canvas,g)}};
  }
  // An SVG has no offsetParent, so the containing block is found the same way the browser does.
  function container(node){
    if(node.offsetParent instanceof Element)return node.offsetParent;
    let parent=node.parentElement;
    while(parent&&parent!==document.body&&getComputedStyle(parent).position==='static')parent=parent.parentElement;
    return parent||host();
  }
  // Takes a part of the other layout out of flow at the place it will have there.
  function hold(node,{rect:r,display},g){
    node.classList.add('is-held');
    Object.assign(node.style,{display,position:'absolute',boxSizing:'border-box',margin:'0',pointerEvents:'none',opacity:'0',zIndex:node.classList.contains('music-tile')?'-3':''});
    const parent=container(node),origin=rect(parent,g)||{x:0,y:0};
    Object.assign(node.style,{left:r.x-origin.x-parent.clientLeft+parent.scrollLeft+'px',top:r.y-origin.y-parent.clientTop+parent.scrollTop+'px',width:r.w+'px',height:r.h+'px'});
  }
  function release(node){node.classList.remove('is-held');for(const name of HELD)node.style.removeProperty(name)}
  function begin(live){
    if(morph)return morph;
    settle();
    const g=geometry(),focused=live.classList.contains('timer-focused'),here=measure(live,g);
    live.classList.toggle('timer-focused',!focused);const there=measure(live,g);live.classList.toggle('timer-focused',focused);
    const compact=focused?there:here,focus=focused?here:there,page=host(),cover=page.querySelector('.music-cover'),img=cover?.querySelector('img'),thumb=live.querySelector('.music-thumb');
    const m={live,start:focused,p:focused?1:0,v:0,target:focused,shared:[],leaving:[],held:[],cover,thumb,imgRect:img?rect(img,g):null,thumbRect:thumb?compact.only.get(thumb)?.rect:null,
      chevron:page.querySelector('#health-minimize'),range:Math.max(200,Math.min(420,g.box.height/g.scale*.42)),k:1};
    m.flying=Boolean(m.imgRect&&m.thumbRect);
    for(const [node,c] of compact.shared){const f=focus.shared.get(node);if(f){Object.assign(node.style,{transformOrigin:'0 0',transition:'none'});m.shared.push({node,c,f,uniform:node.matches(UNIFORM),centred:node.id==='session-time',text:node.matches(TEXT),control:node.matches(CONTROL)})}}
    // One clock, two renderings: the ring's dot matrix and the focus canvas cross over during the move, each at the
    // size its own layout gives it, so a gesture held near either end already shows that end's clock.
    const flow=focused?live.querySelector('#focus-time-dots'):live.querySelector('.session-matrix');
    const over=focused?live.querySelector('.session-matrix'):live.querySelector('#focus-time-dots'),overRect=focused?compact.clock.matrix:focus.clock.canvas;
    if(!focused)attach(live,true);
    if(flow&&over&&overRect){
      Object.assign(over.style,{display:'block',position:'absolute',left:'50%',top:'50%',width:overRect.w+'px',height:overRect.h+'px',margin:'0',pointerEvents:'none',zIndex:'1'});
      m.clockOver=over;m.clockFlow=flow;
    }
    for(const node of here.only.keys())if(!there.only.has(node))m.leaving.push(node);
    for(const [node,info] of there.only)if(!here.only.has(node)){hold(node,info,g);m.held.push(node)}
    if(cover)Object.assign(cover.style,{transition:'none',opacity:'1',visibility:'visible'});
    if(m.chevron)m.chevron.hidden=false;
    morph=m;apply();return m;
  }
  function apply(){
    const m=morph;if(!m)return;const p=clamp(m.p);
    for(const s of m.shared)s.v=between(s.c,s.f,p);
    // The transport keeps its own width the whole way: the metadata is only ever as wide as the room beside it.
    const controls=m.shared.filter(s=>s.control).map(s=>s.v);
    for(const s of m.shared){
      const at=m.start?s.f:s.c,v=s.v;
      if(s.centred){const k=v.w/at.w;m.k=k;s.node.style.transform=`translate(${v.x+v.w/2-at.x-at.w*k/2}px,${v.y+v.h/2-at.y-at.h*k/2}px) scale(${k})`;continue}
      const sy=v.h/at.h,sx=s.uniform?sy:v.w/at.w;
      if(s.text){let room=v.w;for(const c of controls)if(v.y<c.y+c.h&&c.y<v.y+v.h)room=Math.min(room,c.x-v.x-8);s.node.style.width=Math.max(24,Math.min(v.w,room))/sy+'px'}
      s.node.style.transform=`translate(${v.x-at.x}px,${v.y-at.y}px) scale(${sx},${sy})`;
    }
    const going=m.start?smooth(p,.45,1):smooth(1-p,.45,1),coming=m.start?smooth(1-p,.5,1):smooth(p,.5,1),swap=smooth(p,.02,.14);
    for(const node of m.leaving)node.style.opacity=String(node===m.thumb&&m.flying?1-swap:going);
    for(const node of m.held)node.style.opacity=String(node===m.thumb&&m.flying?1-swap:coming);
    if(m.clockOver){
      const fade=smooth(p,.25,.75),toCanvas=!m.start;
      m.clockOver.style.opacity=String(toCanvas?fade:1-fade);m.clockFlow.style.opacity=String(toCanvas?1-fade:fade);
      m.clockOver.style.transform=`translate(-50%,-50%) scale(${(1/(m.k||1)).toFixed(4)})`;
    }
    // The full view's surfaces, selected control and artwork-derived actions belong to the far half of the move,
    // not to the release: a gesture held near either end already carries that end's treatment.
    if(m.cover)host().classList.toggle('music-lit',p>=.5);
    dialState(m.live,p>=.5);
    if(m.chevron)m.chevron.style.opacity=String(smooth(p,.5,1));
    if(!m.cover)return;
    for(const node of m.cover.querySelectorAll('.music-tint,.music-shade'))node.style.opacity=String(p);
    for(const node of m.cover.querySelectorAll('.music-glow'))node.style.opacity=String(.8*p);
    const F=m.imgRect,T=m.thumbRect;
    for(const img of m.cover.querySelectorAll('img')){
      if(!m.flying){img.style.opacity=String(p);continue}
      img.style.transform=`translate(${mix(T.x,F.x,p)-F.x}px,${mix(T.y,F.y,p)-F.y}px) scale(${mix(T.w,F.w,p)/F.w})`;img.style.opacity=String(swap);
    }
  }
  function finish(){
    const m=morph;if(!m)return;
    cancelAnimationFrame(frame);frame=0;morph=null;if(drag?.active)drag=null;
    const final=m.target==null?m.p>.5:m.target,live=m.live;
    for(const s of m.shared){s.node.style.removeProperty('transform');s.node.style.removeProperty('transform-origin');if(s.text)s.node.style.removeProperty('width')}
    for(const node of m.leaving)node.style.removeProperty('opacity');
    for(const node of m.held)release(node);
    if(m.clockOver){for(const name of ['display','position','left','top','width','height','margin','pointer-events','z-index','transform','opacity'])m.clockOver.style.removeProperty(name);m.clockFlow.style.removeProperty('opacity')}
    if(m.cover)host().classList.toggle('music-lit',final);
    dialState(live,final);
    if(m.chevron){m.chevron.style.removeProperty('opacity');m.chevron.hidden=!final}
    if(m.cover){
      // Hide first, then restore the cover's own values out of sight so nothing flashes back to full size.
      const cover=m.cover;if(!final)Object.assign(cover.style,{opacity:'0',visibility:'hidden'});
      for(const node of cover.querySelectorAll('img,.music-tint,.music-glow,.music-shade')){node.style.removeProperty('transform');node.style.removeProperty('opacity')}
      requestAnimationFrame(()=>{if(morph?.cover!==cover)for(const name of ['transition','opacity','visibility'])cover.style.removeProperty(name)});
    }
    // Both clock renderings have already crossed over; the resting classes only take ownership of them back.
    if(final!==m.start)live.classList.toggle('timer-focused',final);
    attach(live,final);
    MusicPlayer.setFocused(final);
    // Commit the resting styles while transitions are still off, so nothing animates back from where the move left it.
    for(const s of m.shared){getComputedStyle(s.node).transform;s.node.style.removeProperty('transition')}
  }
  function run(){
    if(frame||!morph)return;let last=performance.now();
    const tick=now=>{
      frame=0;const m=morph;if(!m||drag?.active)return;
      const dt=Math.min(.05,Math.max(0,(now-last)/1000)),goal=m.target?1:0,offset=m.p-goal,c=m.v+16*offset,decay=Math.exp(-16*dt);last=now;
      m.p=goal+(offset+c*dt)*decay;m.v=(m.v-16*c*dt)*decay;
      if(Math.abs(m.p-goal)<.002&&Math.abs(m.v)<.02){m.p=goal;apply();finish();return}
      apply();frame=requestAnimationFrame(tick);
    };
    frame=requestAnimationFrame(tick);
  }
  // The dial's own state belongs to the far half of the move as well, so a held gesture never waits for the release.
  function dialState(live,focused){
    const dial=live.querySelector('.timer-dial');if(!dial||dial.getAttribute('aria-pressed')===String(focused))return;
    dial.setAttribute('aria-pressed',String(focused));dial.setAttribute('aria-label',focused?'Show workout details':'Focus on timer and music');
    const hint=live.querySelector('.timer-tap-hint');if(hint)hint.textContent=focused?'Tap to show details':'Tap to focus';
  }
  function target(live,final){
    const m=begin(live);m.target=final;
    MusicPlayer.setFocused(final);hooks.target(final);
    if(document.hidden)finish();else run();
  }
  function toggle(live,focused){target(live,focused)}
  // The page-wide view follows a finger down into the small player; the small player follows a finger up.
  function bind(root,options){
    hooks=options;
    root.addEventListener('touchstart',down,{passive:true});root.addEventListener('touchmove',move,{passive:false});
    root.addEventListener('touchend',event=>up(event),{passive:true});root.addEventListener('touchcancel',()=>up(null,true),{passive:true});
    // A drag that began on a control must not also press it.
    root.addEventListener('click',event=>{if(swallowClick&&event.detail!==0){swallowClick=false;event.preventDefault();event.stopPropagation()}},true);
  }
  // Touching the small player starts drawing the hidden cover, transparently, so the move's first frame does not wait for it.
  function prewarm(){
    const cover=host().querySelector('.music-cover');if(!cover||morph)return;
    Object.assign(cover.style,{transition:'none',opacity:'.001',visibility:'visible'});clearTimeout(warm);
    warm=setTimeout(()=>{if(morph?.cover!==cover)for(const name of ['transition','opacity','visibility'])cover.style.removeProperty(name)},900);
  }
  function down(event){
    swallowClick=false;if(drag?.active)return;drag=null;if(event.touches.length!==1)return;
    const touch=event.touches[0],node=event.target,live=node?.closest?.('.workout-live');
    if(!live||node.closest('input,#focus-time-dots'))return;
    const focused=morph?.live===live?Boolean(morph.target??morph.p>.5):live.classList.contains('timer-focused');
    if(focused?document.querySelector('#health-scroll').scrollTop>0:!node.closest('.workout-music'))return;
    if(!focused)prewarm();
    drag={live,id:touch.identifier,x:touch.clientX,y:touch.clientY,lastY:touch.clientY,lastTime:performance.now(),active:false,direction:focused?1:-1};
  }
  function move(event){
    const d=drag;if(!d)return;const touch=[...event.touches].find(t=>t.identifier===d.id);if(!touch)return;
    const dx=touch.clientX-d.x,dy=touch.clientY-d.y;
    if(!d.active){
      if(Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)){drag=null;return}
      if(Math.abs(dy)<=8||Math.abs(dy)<=Math.abs(dx)*1.2)return;
      if(Math.sign(dy)!==d.direction){drag=null;return}
      const m=begin(d.live);cancelAnimationFrame(frame);frame=0;m.target=null;Object.assign(d,{active:true,start:m.p,slop:Math.sign(dy)*8});swallowClick=true;
    }
    if(event.cancelable)event.preventDefault();
    const m=morph;if(!m)return;const now=performance.now(),dt=Math.max(.008,(now-d.lastTime)/1000),speed=(touch.clientY-d.lastY)/dt;d.lastY=touch.clientY;d.lastTime=now;
    // Movement starts from rest at the edge of the touch slop instead of jumping to the finger.
    m.p=clamp(d.start-(dy-d.slop)/m.range);m.v=-speed/m.range;apply();
  }
  function up(event,cancelled=false){
    const d=drag;drag=null;if(!d?.active||!morph)return;
    const y=event?.changedTouches?.[0]?.clientY??d.lastY,moved=Math.abs(y-d.y)>=24,projection=morph.p+(performance.now()-d.lastTime<90?morph.v*.18:0);
    target(d.live,cancelled||!moved?d.direction===1:projection>.5);
  }
  document.addEventListener('visibilitychange',()=>{if(document.hidden){finish();settle()}});window.addEventListener('resize',()=>{finish();settle()});
  return {toggle,layout,animate,settle,attach,dispose,bind,reading(value){dots?.set(value)},get active(){return animations.size+(morph?1:0)}};
})();

const MusicPlayer=(()=>{
  let root=null,timer=0,state=null,art='',artKey='',scrubbing=false,scrubId='',notice=()=>{};
  let cover=null,artGeneration=0,artCleanup=0,focused=true;
  // Transport glyphs in the manner of One UI's media controls: filled, with softly rounded corners.
  const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true">${{play:'<path d="M7.8 5.4v13.2a1.6 1.6 0 0 0 2.44 1.36l10.2-6.6a1.6 1.6 0 0 0 0-2.72l-10.2-6.6A1.6 1.6 0 0 0 7.8 5.4Z"/>',pause:'<rect x="5.6" y="4.2" width="4.5" height="15.6" rx="1.8"/><rect x="13.9" y="4.2" width="4.5" height="15.6" rx="1.8"/>',previous:'<rect x="4.2" y="4.6" width="2.9" height="14.8" rx="1.45"/><path d="M19.8 6.1v11.8a1.45 1.45 0 0 1-2.26 1.2l-8.66-5.9a1.45 1.45 0 0 1 0-2.4l8.66-5.9a1.45 1.45 0 0 1 2.26 1.2Z"/>',next:'<rect x="16.9" y="4.6" width="2.9" height="14.8" rx="1.45"/><path d="M4.2 6.1v11.8a1.45 1.45 0 0 0 2.26 1.2l8.66-5.9a1.45 1.45 0 0 0 0-2.4L6.46 4.9A1.45 1.45 0 0 0 4.2 6.1Z"/>',music:'<path d="M10 4v12.2a4 4 0 1 0 2 3.5V8l8-2v8.2a4 4 0 1 0 2 3.5V1Z"/>',open:'<path d="M14 3h7v7h-2V6.4l-8.3 8.3-1.4-1.4L17.6 5H14Z"/><path d="M5 7h6v2H5v10h10v-6h2v8H3V7Z"/>'}[name]||''}</svg>`;
  const clock=ms=>{const s=Math.floor(Math.max(0,ms)/1000);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')};
  const page=()=>document.querySelector('#health-page');
  function valid(s){return s&&['permission','idle','ready','error','inactive','browser'].includes(s.status)&&(s.status!=='ready'||typeof s.id==='string'&&typeof s.artKey==='string'&&['title','artist','source'].every(k=>typeof s[k]==='string')&&['position','duration'].every(k=>Number.isFinite(s[k])&&s[k]>=0)&&['playing','buffering','canToggle','canPrevious','canNext','canSeek','canOpen'].every(k=>typeof s[k]==='boolean')&&(s.art===undefined||typeof s.art==='string'&&s.art.length<1500000&&(s.art===''||/^data:image\/(?:jpeg|png);base64,[A-Za-z0-9+/=]+$/.test(s.art))))}
  function shell(){root.innerHTML=`<div class="music-track" hidden><div class="music-bottom"><div class="music-tile" aria-hidden="true"></div><div class="music-thumb" aria-hidden="true">${icon('music')}</div><button class="music-expand" data-music-expand aria-label="Show full music view"></button><div class="music-heading"><div><h2></h2><p></p></div><button data-music="open" aria-label="Open music app">${icon('open')}</button></div><div class="music-timeline"><input type="range" min="0" max="1" value="0" step="1000" aria-label="Track position"/><div><output class="music-position">0:00</output><span class="music-source"></span><output class="music-duration">0:00</output></div></div><div class="music-controls"><button data-music="previous" aria-label="Previous track">${icon('previous')}</button><button data-music="toggle" aria-label="Play music">${icon('play')}</button><button data-music="next" aria-label="Next track">${icon('next')}</button></div></div></div>`}
  function backdrop(value){page().style.setProperty('--workout-art',value?`url("${value}")`:'none')}
  // Only the focused view tints the page and shows the cover; the workout view keeps it decoded but hidden.
  function setFocused(value){focused=Boolean(value);page().classList.toggle('music-focus',focused&&Boolean(cover));cover?.classList.toggle('is-minimised',!focused)}
  // The small cover on the workout view is its own copy, sized for the phone's pixel density.
  function miniArt(value){
    const box=root?.querySelector('.music-thumb');if(!box)return;const old=[...box.querySelectorAll('i')];
    if(value){const layer=document.createElement('i');layer.style.backgroundImage=`url("${value}")`;box.append(layer);if(document.hidden)layer.classList.add('is-visible');else requestAnimationFrame(()=>requestAnimationFrame(()=>layer.classList.add('is-visible')))}
    else for(const node of old)node.classList.remove('is-visible');
    setTimeout(()=>{for(const node of old)node.remove()},value?420:360);
  }
  const hsl=(r,g,b)=>{const max=Math.max(r,g,b),min=Math.min(r,g,b),l=(max+min)/2,d=max-min;return d?[((max===r?(g-b)/d+6:max===g?(b-r)/d+2:(r-g)/d+4)%6)/6,d/(1-Math.abs(2*l-1)),l]:[0,0,l]};
  const rgb=(h,s,l)=>{const a=s*Math.min(l,1-l),f=n=>{const k=(n+h*12)%12;return l-a*Math.max(-1,Math.min(k-3,9-k,1))};return [f(0),f(8),f(4)]};
  const luminance=c=>{const [r,g,b]=c.map(v=>v<=.03928?v/12.92:((v+.055)/1.055)**2.4);return .2126*r+.7152*g+.0722*b};
  const css=c=>`rgb(${c.map(v=>Math.round(v*255)).join(' ')})`;
  // Two related colours from the cover's most saturated hue, a glass from its shadows, and the more
  // readable of dark or white text on the brighter one. Grey covers keep the neutral buttons.
  function palette(img){
    try{
      const n=24,canvas=document.createElement('canvas');canvas.width=canvas.height=n;const context=canvas.getContext('2d',{willReadFrequently:true});context.drawImage(img,0,0,n,n);const px=context.getImageData(0,0,n,n).data;
      const bins=Array.from({length:18},()=>[0,0,0,0]),dark=[0,0,0,0];
      let lit=0,litCount=0,band=0,bandCount=0;
      for(let i=0;i<px.length;i+=4){
        const r=px[i]/255,g=px[i+1]/255,b=px[i+2]/255,[h,s,l]=hsl(r,g,b),row=Math.floor(i/4/n),light=luminance([r,g,b]);
        lit+=light;litCount++;
        if(row>=n*.3&&row<n*.85){band+=light;bandCount++} // the part of the cover the clock and controls sit over
        if(l<.4){dark[0]+=r;dark[1]+=g;dark[2]+=b;dark[3]++}
        if(s<.2||l<.1||l>.94)continue;
        const w=s*s*(1-Math.abs(l-.55)),bin=bins[Math.floor(h*18)%18];bin[0]+=r*w;bin[1]+=g*w;bin[2]+=b*w;bin[3]+=w;
      }
      // How much local shading the chrome needs: a bright or busy cover gets more, a dark one keeps today's veil.
      const veil=Math.min(.72,Math.max(.2,.2+Math.max(lit/Math.max(1,litCount),bandCount?band/bandCount:0)*.62));
      const best=bins.reduce((a,b)=>b[3]>a[3]?b:a);if(best[3]<3)return {veil:veil.toFixed(3)};
      const [h,s]=hsl(best[0]/best[3],best[1]/best[3],best[2]/best[3]),accent=rgb(h,Math.min(.85,Math.max(.5,s)),.64),second=rgb((h+.05)%1,Math.min(.9,Math.max(.55,s)),.5);
      const lift=luminance(accent),shadow=dark[3]?[dark[0]/dark[3],dark[1]/dark[3],dark[2]/dark[3]]:rgb(h,.3,.12),degrees=Math.round(h*360);
      return {veil:veil.toFixed(3),accent:css(accent),'accent-2':css(second),ink:(lift+.05)/.057>1.05/(lift+.05)?'#17121b':'#ffffff',glass:`rgb(${shadow.map(v=>Math.round(v*255)).join(' ')} / .58)`,edge:`hsl(${degrees} 70% 72% / .45)`,soft:`hsl(${degrees} 55% 91%)`};
    }catch{return null}
  }
  function tint(value){const node=page();if(!value){node.style.removeProperty('--art-veil');node.classList.remove('has-art-palette');return}for(const [key,colour] of Object.entries(value))node.style.setProperty('--art-'+key,colour);node.classList.toggle('has-art-palette',Boolean(value.accent))}
  function clearArtwork(){artGeneration++;clearTimeout(artCleanup);artCleanup=0;cover?.remove();cover=null;art='';page().classList.remove('music-focus','music-lit');backdrop('');tint(null);root?.querySelector('.music-thumb')?.querySelectorAll('i').forEach(node=>node.remove())}
  // A track that really has no cover fades the previous one out instead of cutting to the bare page.
  function retireArtwork(){
    const old=cover;artGeneration++;clearTimeout(artCleanup);artCleanup=0;cover=null;art='';
    page().classList.remove('music-focus','music-lit');root?.classList.remove('has-art');backdrop('');tint(null);miniArt('');
    if(!old)return;if(document.hidden){old.remove();return}
    old.style.opacity='0';setTimeout(()=>old.remove(),460);
  }
  // Downscaled copies: the blurred backdrop cannot show more detail than its blur radius, and the small
  // cover needs only its displayed size. Neither is ever enlarged beyond the decoded image.
  function thumbnail(img,width){try{const w=Math.max(1,Math.min(width,img.naturalWidth)),height=Math.max(1,Math.round(w*img.naturalHeight/img.naturalWidth)),canvas=document.createElement('canvas');canvas.width=w;canvas.height=height;canvas.getContext('2d').drawImage(img,0,0,w,height);return canvas.toDataURL('image/jpeg',.82)}catch{return null}}
  function artwork(value){
    if(value===art&&cover?.querySelector('.music-art-layer:last-child img')?.src===value)return;
    if(!value){if(cover)retireArtwork();else clearArtwork();return}
    art=value;const generation=++artGeneration,node=root,img=new Image();img.alt='Album artwork';img.src=value;
    img.decode().then(()=>{
      if(root!==node||generation!==artGeneration)return;
      const host=page();
      if(!cover){cover=document.createElement('div');cover.className='music-cover';cover.classList.toggle('is-minimised',!focused);const base=document.createElement('div');base.className='music-tint';cover.append(base);host.append(cover)}
      clearTimeout(artCleanup);const layers=cover.querySelectorAll('.music-art-layer');for(let i=0;i<layers.length-1;i++)layers[i].remove();
      const small=thumbnail(img,48)||value,previous=cover.querySelector('.music-art-layer'),layer=document.createElement('div'),glow=document.createElement('div'),shade=document.createElement('div');
      layer.className='music-art-layer';glow.className='music-glow';shade.className='music-shade';layer.style.setProperty('--art',`url("${small}")`);layer.append(glow,img,shade);cover.append(layer);
      backdrop(small);host.classList.toggle('music-focus',focused);host.classList.toggle('music-lit',focused);node.classList.add('has-art');miniArt(thumbnail(img,192)||value);tint(palette(img));
      if(document.hidden){layer.classList.add('is-visible');previous?.remove();return}
      // Two frames let the new layer rasterise before its fade starts, so the crossfade begins smoothly.
      layer.getBoundingClientRect();requestAnimationFrame(()=>requestAnimationFrame(()=>{if(generation!==artGeneration||!layer.isConnected)return;layer.classList.add('is-visible');previous?.classList.remove('is-visible')}));
      artCleanup=setTimeout(()=>{previous?.remove();artCleanup=0},640);
    }).catch(()=>{if(root===node&&generation===artGeneration)clearArtwork()});
  }
  function paint(next){
    if(!root)return;const q=s=>root.querySelector(s),ready=next.status==='ready',previous=state,live=root.closest('.workout-live');state=next;
    const visibility=()=>{root.hidden=!ready;live.classList.toggle('has-music',ready)};if(previous&&ready!==(previous.status==='ready'))WorkoutFocus.layout(live,visibility);else visibility();
    if(!ready){artKey='';scrubbing=false;clearArtwork();root.classList.remove('has-art');return}
    q('.music-track').hidden=false;
    q('.music-heading h2').textContent=next.title||'Untitled track';q('.music-heading p').textContent=next.artist||next.source;q('.music-source').textContent=next.buffering?'Buffering…':(/^[a-z][\w]*(?:\.[\w]+){2,}$/i.test(next.source)?'This phone':next.source);
    if(next.art!==undefined){artKey=next.artKey;artwork(next.art)}
    if(previous?.status==='ready'&&(previous.title!==next.title||previous.artist!==next.artist))WorkoutFocus.animate(q('.music-heading'),[{opacity:0,transform:'translateY(7px)'},{opacity:1,transform:'none'}],340);
    if(previous&&previous.status!=='ready')WorkoutFocus.animate(q('.music-bottom'),[{opacity:0,transform:'translateY(22px)'},{opacity:1,transform:'none'}],480);
    root.classList.toggle('music-playing',next.playing);const toggle=q('[data-music="toggle"]'),action=next.playing?'pause':'play';if(toggle.dataset.state!==action){toggle.innerHTML=icon(action);toggle.dataset.state=action}toggle.setAttribute('aria-label',next.playing?'Pause music':'Play music');toggle.disabled=!next.canToggle;
    for(const [action,key] of [['previous','canPrevious'],['next','canNext'],['open','canOpen']])q(`[data-music="${action}"]`).disabled=!next[key];
    const range=q('input');range.disabled=!next.canSeek;range.max=String(next.duration||1);if(!scrubbing){range.value=String(next.position);range.style.setProperty('--music-progress',Math.min(100,next.position/(next.duration||1)*100)+'%');q('.music-position').textContent=clock(next.position);range.setAttribute('aria-valuetext',clock(next.position)+' of '+clock(next.duration))}q('.music-duration').textContent=next.duration?clock(next.duration):'Live';
  }
  function refresh(){if(!root||document.hidden)return;try{const next=window.OrbitMusic?JSON.parse(window.OrbitMusic.read(artKey)):{status:'browser'};if(!valid(next))throw Error('Invalid media response');paint(next)}catch{paint({status:'error'})}}
  function send(action,value=0){if(state?.status!=='ready')return false;try{if(!window.OrbitMusic?.command(state.id,action,value))throw Error('Unavailable');return true}catch{notice('Music control unavailable. Check the music app.');return false}}
  function connect(){try{window.OrbitMusic?.connect()}catch{notice('Open Android Settings > Notification access > Orbit.')}}
  function click(event){const button=event.target.closest('button');if(button&&!button.disabled&&button.dataset.music)send(button.dataset.music==='toggle'?(state.playing?'pause':'play'):button.dataset.music)}
  function input(event){if(event.target.type!=='range')return;if(!scrubbing)scrubId=state?.id;scrubbing=true;const value=Number(event.target.value);event.target.style.setProperty('--music-progress',Math.min(100,value/(state?.duration||1)*100)+'%');root.querySelector('.music-position').textContent=clock(value);event.target.setAttribute('aria-valuetext',clock(value)+' of '+clock(state?.duration||0))}
  function change(event){if(event.target.type==='range'){if(scrubId===state?.id)send('seek',Number(event.target.value));else notice('The track changed. Choose a position in the new track.');scrubbing=false}}
  function cancel(){scrubbing=false;refresh()}
  function stop(){
    clearInterval(timer);timer=0;const node=root;
    if(node){node.removeEventListener('click',click);node.removeEventListener('input',input);node.removeEventListener('change',change);node.removeEventListener('pointercancel',cancel);node.removeEventListener('focusout',cancel);node.closest('.workout-live')?.classList.remove('has-music');node.hidden=true}
    root=null;scrubbing=false;clearArtwork();state=null;artKey='';
  }
  function mount(node,isFocused=true){
    stop();root=node;focused=Boolean(isFocused);shell();
    root.addEventListener('click',click);root.addEventListener('input',input);root.addEventListener('change',change);root.addEventListener('pointercancel',cancel);root.addEventListener('focusout',cancel);refresh();if(!document.hidden&&window.OrbitMusic)timer=setInterval(refresh,1000)
  }
  window.addEventListener('orbit-music-change',refresh);
  document.addEventListener('visibilitychange',()=>{clearInterval(timer);timer=0;scrubbing=false;if(!document.hidden&&root){refresh();if(window.OrbitMusic)timer=setInterval(refresh,1000)}});
  return {mount,stop,refresh,valid,connect,setFocused,init(fn){notice=fn},get active(){return Boolean(root)}};
})();
