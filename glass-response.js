/* One touch-driven material. Existing controls own values/navigation; this owns only their
   continuous contact, light and flex. Reuses BlobTrack's analytic spring. No changing blur,
   backdrop readback, frame-by-frame geometry measurement, or animation library. */
'use strict';
window.GlassResponse=(()=>{
  const cached=new WeakMap(),active=new Set(),external=new Set(),navigation=new Map();
  const motion=matchMedia('(prefers-reduced-motion:reduce)');
  const reduced=()=>motion.matches||SurfaceMotion.reduced;
  const clamp=BlobTrack.clamp;
  let frame=0,last=0,pointer=null,swallow=null;
  function layer(parent,name){const n=document.createElement('span');n.className=name;n.setAttribute('aria-hidden','true');parent.append(n);return n}

  function create(host,driven=false){
    if(cached.has(host))return cached.get(host);
    const input=host.matches('input'),range=input&&host.type==='range',toggle=input&&host.type==='checkbox';
    const indicator=driven?host.querySelector('.selection-pill'):null;
    const field=range&&(host.classList.contains('chart-input')||host.id==='night-scrub'||host.id==='body-scrub');
    const shell=host.matches('#live-bar,.stack-shell');
    const parent=indicator||(host.id==='live-bar'?host.closest('.live-island').querySelector('.island-surface'):field?host.parentElement:host);
    let surface=null,light=null,spread=null;
    if(!input||field){
      if(getComputedStyle(parent).position==='static')parent.style.position='relative';
      surface=layer(parent,field?'glass-contact-mark':'glass-response-surface');
      if(shell)surface.classList.add('glass-shell-response');
      light=layer(surface,'glass-response-light');
      if(driven)spread=layer(host,'glass-shared-light');
    }
    host.classList.add('glass-responsive');
    if(toggle)host.style.setProperty('--switch-x',host.checked?'18px':'0px');
    const s={host,driven,range,toggle,field,e:0,ev:0,held:false,dragging:false,x:0,y:0,
      dx:0,dy:0,dxv:0,dyv:0,vx:0,vy:0,speed:0,stretch:0,stretchV:0,box:null,time:0,switchX:toggle&&host.checked?18:0,switchV:0};
    function measure(){
      s.box=host.getBoundingClientRect();
      if(field){const r=parent.getBoundingClientRect();s.offsetX=s.box.left-r.left;s.offsetY=s.box.top-r.top}
      return s.box;
    }
    function aim(x,y,time=performance.now()){
      if(!s.box)return;
      const nx=x-s.box.left,ny=y-s.box.top,dt=Math.max(.008,(time-s.time)/1000);
      if(s.time){s.vx=clamp((nx-s.x)/dt,-1600,1600);s.vy=clamp((ny-s.y)/dt,-1600,1600);s.speed=Math.min(1,Math.hypot(s.vx,s.vy)/900)}
      s.x=nx;s.y=ny;s.time=time;
      if(!driven)wake(s);
    }
    function begin(x,y){
      measure();s.time=0;aim(x,y);s.held=true;s.dragging=false;
      // Initial velocity starts feedback on the next display frame, without snapping the material's pose.
      s.ev=Math.max(s.ev,5);host.dataset.glassState='touch';
      if(!driven)wake(s);
    }
    function end(cancel=false){s.held=false;s.dragging=false;host.dataset.glassState=cancel?'cancel':'settle';if(!driven)wake(s)}
    function step(dt){
      const gentle=reduced(),target=s.held?1:0;
      const e=BlobTrack.spring(s.e,s.ev,target,dt,650,gentle?1:.86);s.e=e.x;s.ev=e.velocity;
      const stretch=BlobTrack.spring(s.stretch,s.stretchV,s.held&&!gentle?s.speed:0,dt,500,1);s.stretch=stretch.x;s.stretchV=stretch.velocity;
      s.speed*=Math.exp(-dt/.045);
      const tx=s.held&&!gentle?clamp((s.x-(s.box?.width||0)/2)*.065,-3,3):0;
      const ty=s.held&&!gentle?clamp((s.y-(s.box?.height||0)/2)*.065,-3,3):0;
      const x=BlobTrack.spring(s.dx,s.dxv,tx,dt,900,1),y=BlobTrack.spring(s.dy,s.dyv,ty,dt,900,1);
      s.dx=x.x;s.dy=y.x;s.dxv=x.velocity;s.dyv=y.velocity;
      let moving=Math.abs(s.e-target)>.001||Math.abs(s.ev)>.015||Math.abs(s.stretch)>.001||Math.abs(s.dx-tx)>.03||Math.abs(s.dy-ty)>.03;
      if(toggle){
        const targetX=s.dragging?s.switchX:host.checked?18:0;
        if(!s.dragging){const x=gentle?{x:targetX,velocity:0}:BlobTrack.spring(s.switchX,s.switchV,targetX,dt,550,.88);s.switchX=x.x;s.switchV=x.velocity}
        moving||=Math.abs(s.switchX-targetX)>.02||Math.abs(s.switchV)>.1;
      }
      if(!moving){
        s.e=target;s.ev=0;s.stretch=0;s.stretchV=0;s.dx=tx;s.dy=ty;s.dxv=s.dyv=0;
        if(toggle&&!s.dragging){s.switchX=host.checked?18:0;s.switchV=0}
        host.dataset.glassState=s.held?'engaged':'idle';
      }
      else if(s.held)host.dataset.glassState=s.dragging?'drag':s.e>.94?'engaged':'touch';
      paint();return moving;
    }
    function paint(){
      const e=clamp(s.e,0,1),flex=reduced()?0:Math.max(0,s.e),w=s.box?.width||1,h=s.box?.height||1;
      if(surface){
        // A playback icon or button label can change without replacing its control.
        if(surface.parentNode!==parent)parent.append(surface);
        surface.style.opacity=String(e);
        if(field){surface.style.transform=`translate3d(${s.offsetX+clamp(s.x,0,w)-14}px,${s.offsetY+clamp(s.y,0,h)-14}px,0) scale(${1+flex*.12})`}
        else surface.style.transform=driven||shell?'none':`translate3d(${s.dx*flex}px,${s.dy*flex}px,0) scale(${1+flex*.025+s.stretch*.025},${1+flex*.045-s.stretch*.015})`;
        light.style.transform=`translate3d(${(clamp(s.x/w,0,1)-.5)*50}%,${(clamp(s.y/h,0,1)-.5)*35}%,0)`;
      }
      if(spread){spread.style.opacity=String(e*.38);spread.style.transform=`translate3d(${(clamp(s.x/w,0,1)-.5)*70}%,0,0) scaleX(${.65+e*.35})`}
      if(input){host.style.setProperty('--glass-engagement',e.toFixed(4));host.style.setProperty('--glass-flex',flex.toFixed(4));host.style.setProperty('--glass-stretch',s.stretch.toFixed(4))}
      if(toggle)host.style.setProperty('--switch-x',s.switchX.toFixed(3)+'px');
    }
    function reset(){
      s.held=s.dragging=false;s.e=s.ev=s.dx=s.dy=s.dxv=s.dyv=s.speed=s.stretch=s.stretchV=0;
      if(toggle){s.switchX=host.checked?18:0;s.switchV=0}
      host.dataset.glassState='idle';paint();active.delete(s);
    }
    function destroy(){reset();surface?.remove();spread?.remove();external.delete(api);cached.delete(host);host.classList.remove('glass-responsive')}
    const api={state:s,begin,aim,end,step,reset,destroy,measure,get engagement(){return reduced()?0:Math.max(0,s.e)}};
    s.api=api;cached.set(host,api);if(driven)external.add(api);return api;
  }
  function wake(s){active.add(s);if(frame||document.hidden)return;last=performance.now();frame=requestAnimationFrame(tick)}
  function tick(now){
    frame=0;const dt=Math.min(.05,Math.max(0,(now-last)/1000));last=now;
    for(const s of active){if(!s.host.isConnected||document.hidden){s.api.reset();continue}if(!s.api.step(dt))active.delete(s)}
    if(active.size)frame=requestAnimationFrame(tick);
  }
  function targetOf(target){
    if(target.closest('.blob-track,[inert]'))return null;
    let node=target.closest('button,summary,a,input,[role=button]');
    if(!node){const label=target.closest('label');node=label?.querySelector('input[type=checkbox]')}
    if(!node||node.disabled||node.getAttribute('aria-disabled')==='true'||node.matches('.orb-button,.body-centre,[data-body-dial]'))return null;
    if(node.matches('input')&&!node.matches('input[type=range],input[type=checkbox]'))return null;
    // The grab blanket spans the whole folded deck; put feedback on the visible card, not the blanket.
    if(node.id==='stack-open')node=document.querySelector('.stack-card .stack-shell');
    return node;
  }
  document.addEventListener('pointerdown',event=>{
    if(!event.isPrimary){if(pointer)finish({pointerId:pointer.id},true);return}
    if(event.button!==0)return;
    if(pointer){pointer.api.end(true);pointer=null}
    const node=targetOf(event.target);if(!node)return;
    const api=create(node);api.begin(event.clientX,event.clientY);
    pointer={api,id:event.pointerId,x:event.clientX,y:event.clientY,start:api.state.switchX};
  },true);
  document.addEventListener('pointermove',event=>{
    const p=pointer;if(!p||p.id!==event.pointerId)return;
    const s=p.api.state,dx=event.clientX-p.x,dy=event.clientY-p.y;
    if(!s.toggle&&!s.range&&Math.abs(dy)>10&&!s.host.closest('#live-island,.panel-deck')&&!s.host.matches('[data-body-dial]')){p.api.end(true);pointer=null;return}
    if(s.toggle&&!s.dragging){
      if(Math.abs(dy)>8&&Math.abs(dy)>=Math.abs(dx)){p.api.end(true);pointer=null;return}
      if(Math.abs(dx)>6){s.dragging=true;s.host.setPointerCapture(event.pointerId);OrbitInteraction.haptic('tick')}
    }
    if(s.toggle&&s.dragging){if(event.cancelable)event.preventDefault();s.switchX=clamp(p.start+dx,0,18)}
    p.api.aim(event.clientX,event.clientY);if(s.range)s.dragging=true;
  },true);
  function finish(event,cancel=false){
    const p=pointer;if(!p||p.id!==event.pointerId)return;pointer=null;
    const s=p.api.state;
    if(s.toggle&&s.dragging){
      cancel||=s.host.disabled||event.clientY!==undefined&&(event.clientY<s.box.top-48||event.clientY>s.box.bottom+48);
      swallow={host:s.host,until:performance.now()+450};
      if(!cancel){
        const velocity=performance.now()-s.time<70?s.vx:0,next=s.switchX+clamp(velocity*.035,-4,4)>=9;
        if(s.host.checked!==next){s.host.checked=next;s.host.dispatchEvent(new Event('input',{bubbles:true}));s.host.dispatchEvent(new Event('change',{bubbles:true}));OrbitInteraction.haptic('select')}
      }
      try{s.host.releasePointerCapture(p.id)}catch{}
    }
    p.api.end(cancel);
  }
  document.addEventListener('pointerup',event=>finish(event),true);
  document.addEventListener('pointercancel',event=>finish(event,true),true);
  document.addEventListener('lostpointercapture',event=>{if(pointer?.api.state.toggle&&event.target===pointer.api.state.host)finish(event,true)},true);
  document.addEventListener('click',event=>{
    if(event.detail&&swallow&&performance.now()<swallow.until&&(event.target===swallow.host||event.target.closest('label')?.contains(swallow.host))){swallow=null;event.preventDefault();event.stopImmediatePropagation()}
  },true);
  document.addEventListener('change',event=>{if(event.target.matches('input[type=checkbox]')){const api=cached.get(event.target);if(api)wake(api.state)}},true);
  document.addEventListener('keydown',event=>{
    if(event.repeat||!['Enter',' '].includes(event.key))return;
    const node=targetOf(event.target);if(!node||node.matches('input'))return;
    const api=create(node),r=node.getBoundingClientRect();api.begin(r.left+r.width/2,r.top+r.height/2);
  },true);
  document.addEventListener('keyup',event=>{if(['Enter',' '].includes(event.key))cached.get(targetOf(event.target))?.end()},true);
  document.addEventListener('focusout',event=>{if(!pointer)cached.get(event.target)?.end()},true);
  function clear(){
    if(pointer)finish({pointerId:pointer.id},true);cancelAnimationFrame(frame);frame=0;
    for(const s of active)s.api.reset();for(const api of external)api.reset();
  }
  function enhance(root=document){
    for(const [host,track] of navigation)if(!host.isConnected){track.destroy();navigation.delete(host)}
    for(const host of root.querySelectorAll('.activity-choices'))if(!navigation.has(host)){
      const choices=[...host.querySelectorAll('[data-activity]')];if(!choices.length)continue;
      let selected=choices[0].dataset.activity;
      host.classList.add('blob-track','transient-track');const pill=layer(host,'selection-pill glass-indicator');host.prepend(pill);
      choices.forEach(n=>n.classList.add('blob-option'));
      const track=BlobTrack.create({host,optionSelector:'[data-activity]',idOf:n=>n.dataset.activity,transient:true,
        committed:()=>selected,influence:(n,raw,eased)=>n.style.setProperty('--blob-influence',eased.toFixed(3)),
        haptics:{tick:()=>OrbitInteraction.haptic('tick'),select:()=>OrbitInteraction.haptic('select')},
        commit:id=>{selected=id;choices.find(n=>n.dataset.activity===id)?.click()},
        onReselect:id=>choices.find(n=>n.dataset.activity===id)?.click()});
      navigation.set(host,track);
    }
  }
  document.addEventListener('visibilitychange',()=>{if(document.hidden)clear()});window.addEventListener('blur',clear);
  window.addEventListener('resize',clear);motion.addEventListener('change',clear);
  return {create,reduced,clear,enhance,get active(){return active.size}};
})();
