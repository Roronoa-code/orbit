/* One physical selector: a measured track, a single moving indicator and the influence its rendered overlap gives
   every option. Geometry belongs to this module; the committed value belongs to the page. A press answers at once,
   a held neighbour draws the same material beneath the finger, a drag follows the pointer without
   tweening, walls compress the candidate shape, a release projects a bounded distance and commits once, and a
   cancelled gesture commits nothing. Lengths are CSS pixels in track-local coordinates; time is seconds. */
'use strict';
const BlobTrack=(()=>{
  const finite=(name,value)=>{if(!Number.isFinite(value))throw new TypeError(name+' must be finite');return value};
  const clamp=(x,lo,hi)=>Math.max(lo,Math.min(hi,x));
  const smoothstep01=x=>{x=clamp(x,0,1);return x*x*(3-2*x)};
  function rubberband(overshoot,dimension,coefficient=.55){
    finite('overshoot',overshoot);finite('dimension',dimension);finite('coefficient',coefficient);
    if(dimension<=0||coefficient<=0)return 0;
    const x=Math.max(0,overshoot);return x*dimension*coefficient/(dimension+coefficient*x);
  }
  // Contiguous catchments: inner boundaries are midpoints between measured centres, outer boundaries the track
  // edges, so the indicator can never fall into a gap between options.
  function catchments(centres,left,right){
    finite('left',left);finite('right',right);
    if(right<=left||centres.length===0)throw new RangeError('Empty track');
    centres.forEach((x,i)=>{finite('centre',x);if(x<left||x>right||(i>0&&x<=centres[i-1]))throw new RangeError('Centres must be physically sorted and inside track')});
    return centres.map((_,i)=>({left:i===0?left:(centres[i-1]+centres[i])/2,right:i===centres.length-1?right:(centres[i]+centres[i+1])/2}));
  }
  function influences(blobLeft,blobWidth,zones){
    finite('blobLeft',blobLeft);finite('blobWidth',blobWidth);
    if(blobWidth<=0)return zones.map(()=>({raw:0,eased:0}));
    return zones.map(zone=>{
      const overlap=Math.max(0,Math.min(blobLeft+blobWidth,zone.right)-Math.max(blobLeft,zone.left));
      const raw=clamp(overlap/blobWidth,0,1);return {raw,eased:smoothstep01(raw)};
    });
  }
  // Collision uses the candidate stretched shape, so a deformed indicator cannot protrude after the check.
  function wallPose(cx,width,height,left,right,maxHeight){
    [cx,width,height,left,right,maxHeight].forEach(x=>finite('geometry',x));
    if(right<=left||width<=0||height<=0||maxHeight<=0)throw new RangeError('Positive track and shape are required');
    width=Math.min(width,right-left);height=Math.min(height,maxHeight);
    const leftOver=Math.max(0,left-(cx-width/2)),rightOver=Math.max(0,cx+width/2-right),over=Math.max(leftOver,rightOver);
    if(over===0)return {cx,width,height,compression:0};
    const give=Math.min(width*.34,rubberband(over,width)),outWidth=width-give;
    return {cx:leftOver>=rightOver?left+outWidth/2:right-outWidth/2,width:outWidth,height:Math.min(maxHeight,height+give*.30),compression:give};
  }
  function projectCentre(cx,velocity,localSlotSpan,horizon=.12){
    [cx,velocity,localSlotSpan,horizon].forEach(x=>finite('projection',x));
    if(localSlotSpan<=0||horizon<0)throw new RangeError('Invalid projection');
    const boundedVelocity=clamp(velocity,-8*localSlotSpan,8*localSlotSpan);
    return {centre:cx+clamp(boundedVelocity*horizon,-.5*localSlotSpan,.5*localSlotSpan),velocity:boundedVelocity};
  }
  function nearestCentre(centres,x,tieIndex=0){
    if(centres.length===0)return -1;
    finite('x',x);let best=clamp(tieIndex,0,centres.length-1),distance=Math.abs(finite('centre',centres[best])-x);
    centres.forEach((c,i)=>{const d=Math.abs(finite('centre',c)-x);if(d<distance-1e-9){best=i;distance=d}});
    return best;
  }
  // Exact solution of x'' + 2*zeta*sqrt(k)*x' + k*(x-target) = 0, so a frame split into any number of steps lands
  // in the same place. Critical damping is 1; the tuning below is deliberately underdamped.
  function spring(x,velocity,target,dt,stiffness=160,damping=.74){
    [x,velocity,target,dt,stiffness,damping].forEach(v=>finite('spring',v));
    if(dt<0||stiffness<=0||damping<0)throw new RangeError('Invalid spring');
    const w=Math.sqrt(stiffness),y=x-target;
    if(Math.abs(damping-1)<1e-6){const b=velocity+w*y,e=Math.exp(-w*dt);return {x:target+(y+b*dt)*e,velocity:(velocity-w*b*dt)*e}}
    if(damping<1){
      const a=damping*w,d=w*Math.sqrt(1-damping*damping),b=(velocity+a*y)/d,c=Math.cos(d*dt),s=Math.sin(d*dt),e=Math.exp(-a*dt);
      return {x:target+e*(y*c+b*s),velocity:e*((b*d-a*y)*c+(-y*d-a*b)*s)};
    }
    const q=Math.sqrt(damping*damping-1),r1=-w*(damping-q),r2=-w*(damping+q),c1=(velocity-r2*y)/(r1-r2),c2=y-c1;
    const e1=Math.exp(r1*dt),e2=Math.exp(r2*dt);
    return {x:target+c1*e1+c2*e2,velocity:c1*r1*e1+c2*r2*e2};
  }
  const TUNING={
    pressStiffness:260,pressDamping:.78,
    settleStiffness:320,settleDamping:.72,shapeStiffness:500,shapeDamping:1,
    slop:6,maxStretch:.16,squash:.5,stretchSlotsPerSecond:8,stretchTau:.035,
    velocityWindow:.1,stillness:.06,projectionHorizon:.12,
  };

  function create(config){
    const host=config.host;if(!host)throw new TypeError('A track host is required');
    const tuning=Object.assign({},TUNING,config.tuning||{});
    const noop=()=>{};
    const haptics=config.haptics||{tick:noop,select:noop};
    let opts=[],zones=[],bounds={left:0,right:0},rest={width:0,height:0},maxHeight=0,targets={cx:0,width:0,height:0};
    let pose={cx:0,width:0,height:0},vel={cx:0,width:0,height:0},stretch=0;
    let mode='rest',session=null,frame=0,generation=0,lastInfluences=[],destroyed=false,centres=[],hostLeft=0,ignoreClickUntil=0;
    const material=window.GlassResponse?.create(host,true),gentle=()=>window.GlassResponse?.reduced()||false;
    let materialAt=performance.now();
    function stepMaterial(now=performance.now()){const moving=material?.step(Math.min(.05,Math.max(0,(now-materialAt)/1000)))||false;materialAt=now;return moving}
    const optionEls=()=>[...host.querySelectorAll(config.optionSelector)].filter(el=>el.getClientRects().length);
    const idOf=el=>config.idOf?config.idOf(el):el.dataset.blobId;
    const committedIndex=()=>{const id=config.committed();const i=opts.findIndex(o=>o.id===id);return i<0?0:i};

    function measure(){
      const els=optionEls();
      if(!els.length){opts=[];zones=[];return false}
      const base=host.getBoundingClientRect(),style=getComputedStyle(host);
      const padLeft=parseFloat(style.paddingLeft)||0,padRight=parseFloat(style.paddingRight)||0;
      const inner={left:padLeft,right:base.width-padRight};
      if(inner.right-inner.left<=0)return false;
      opts=els.map(el=>{const r=el.getBoundingClientRect();return {el,id:idOf(el),left:r.left-base.left,right:r.right-base.left,centre:r.left-base.left+r.width/2,top:r.top-base.top,width:r.width,height:r.height}})
        .sort((a,b)=>a.centre-b.centre);
      // Equal centres (a collapsed layout) cannot produce catchments.
      for(let i=1;i<opts.length;i++)if(opts[i].centre<=opts[i-1].centre)return false;
      bounds=inner;
      hostLeft=base.left;centres=opts.map(o=>o.centre);
      zones=catchments(centres,inner.left,inner.right);
      const active=opts[committedIndex()];
      rest={width:active.width,height:active.height};
      host.style.setProperty('--selection-base-width',rest.width+'px');
      host.style.setProperty('--selection-base-height',rest.height+'px');
      maxHeight=Math.max(rest.height,base.height-(parseFloat(style.paddingTop)||0)-(parseFloat(style.paddingBottom)||0));
      return true;
    }
    const slotSpan=i=>{const z=zones[clamp(i,0,zones.length-1)];return Math.max(1,z.right-z.left)};
    function restingPose(index=committedIndex()){const o=opts[clamp(index,0,opts.length-1)];return {cx:o.centre,width:o.width,height:o.height}}

    function render(){
      const lift=material?.engagement||0;
      const shaped=wallPose(pose.cx,pose.width*(1+lift*.07),pose.height*(1+lift*.20),bounds.left,bounds.right,maxHeight+rest.height*.22);
      const active=opts[committedIndex()];
      const left=shaped.cx-shaped.width/2,top=active?active.top+(active.height-shaped.height)/2:0;
      if(config.render)config.render({cx:shaped.cx,left,top,width:shaped.width,height:shaped.height},{opts,bounds,mode});
      else{
        host.style.setProperty('--selection-x',left.toFixed(2)+'px');
        host.style.setProperty('--selection-y',top.toFixed(2)+'px');
        host.style.setProperty('--selection-width',shaped.width.toFixed(2)+'px');
        host.style.setProperty('--selection-height',shaped.height.toFixed(2)+'px');
        host.style.setProperty('--selection-scale-x',(shaped.width/rest.width).toFixed(5));
        host.style.setProperty('--selection-scale-y',(shaped.height/rest.height).toFixed(5));
      }
      lastInfluences=influences(left,shaped.width,zones);
      if(config.influence)opts.forEach((o,i)=>config.influence(o.el,lastInfluences[i].raw,lastInfluences[i].eased,i));
      config.onPreview?.({cx:shaped.cx,left,width:shaped.width,height:shaped.height},lastInfluences,mode);
      return shaped;
    }

    function stop(){cancelAnimationFrame(frame);frame=0}
    function run(){
      if(frame||destroyed)return;let last=performance.now();
      const tick=now=>{
        frame=0;const dt=Math.min(.05,Math.max(0,(now-last)/1000));last=now;
        const dragging=session?.owned;
        const position=dragging?tuning.settleStiffness:mode==='press'?tuning.pressStiffness:tuning.settleStiffness;
        const damping=dragging?tuning.settleDamping:mode==='press'?tuning.pressDamping:tuning.settleDamping;
        let moving=false;const materialMoving=stepMaterial(now),reduced=gentle();
        if(dragging){
          // Translation is raw: the finger owns the centre. Only the deformation relaxes over time.
          const target=reduced?0:Math.min(1,session.speedSlots/tuning.stretchSlotsPerSecond)*tuning.maxStretch;
          stretch+=(target-stretch)*(1-Math.exp(-dt/tuning.stretchTau));
          session.speedSlots*=Math.exp(-dt/tuning.stretchTau);
          // A fast grab of another item retains the displayed pose, then closes only
          // that initial gap. Subsequent finger movement remains one-to-one.
          const catchup=reduced?{x:0,velocity:0}:spring(session.catchup,session.catchupV,0,dt,650,1);
          session.catchup=catchup.x;session.catchupV=catchup.velocity;
          pose.cx=session.rawCx+session.catchup;vel.cx=session.catchupV;
          pose.width=session.baseWidth*(1+stretch);
          pose.height=rest.height*(1-tuning.squash*stretch);
          moving=stretch>.001||Math.abs(session.catchup)>.05||Math.abs(session.catchupV)>.5;
        }else if(reduced){Object.assign(pose,targets);vel={cx:0,width:0,height:0};stretch=0}
        else{
          const p=spring(pose.cx,vel.cx,targets.cx,dt,position,damping);pose.cx=p.x;vel.cx=p.velocity;
          const w=spring(pose.width,vel.width,targets.width,dt,mode==='press'?position:tuning.shapeStiffness,mode==='press'?damping:tuning.shapeDamping);pose.width=w.x;vel.width=w.velocity;
          const h=spring(pose.height,vel.height,targets.height,dt,tuning.shapeStiffness,tuning.shapeDamping);pose.height=h.x;vel.height=h.velocity;
          stretch=0;
          moving=Math.abs(pose.cx-targets.cx)>.05||Math.abs(vel.cx)>.5||Math.abs(pose.width-targets.width)>.05||Math.abs(vel.width)>.5||Math.abs(pose.height-targets.height)>.05;
          if(!moving){pose.cx=targets.cx;pose.width=targets.width;pose.height=targets.height;vel={cx:0,width:0,height:0}}
        }
        render();
        if(moving||materialMoving)frame=requestAnimationFrame(tick);
        else if(mode==='settle'){mode='rest';host.classList.remove('is-dragging')}
      };
      frame=requestAnimationFrame(tick);
    }

    function settleTo(index,velocity=0){
      const target=restingPose(index);
      targets={cx:target.cx,width:target.width,height:target.height};
      vel.cx=velocity;mode='settle';run();
    }
    function sample(active,event){
      const now=event.timeStamp/1000;
      active.samples.push({t:now,x:event.clientX});
      while(active.samples.length>2&&now-active.samples[0].t>tuning.velocityWindow)active.samples.shift();
    }
    function velocityNow(active){
      const s=active.samples;if(s.length<2)return 0;
      const first=s[0],last=s[s.length-1],dt=last.t-first.t;
      if(dt<=0)return 0;
      // A finger that stopped before releasing carries no momentum.
      if(active.lastMove!==undefined&&(performance.now()/1000-active.lastMove)>tuning.stillness)return 0;
      return (last.x-first.x)/dt;
    }

    function down(event){
      if(session&&session.id!==event.pointerId){cancel('multiple pointers');return}
      if(destroyed||!event.isPrimary||event.button!==0||session||!measure())return;
      const el=event.target.closest?.(config.optionSelector);
      const index=el?opts.findIndex(o=>o.el===el):-1;
      if(index<0||opts[index].el.disabled)return;
      // Touch can synthesize a mousedown after navigation reveals a different control.
      // Suppress compatibility mouse events before they can focus or edit that content.
      if(event.pointerType!=='mouse'&&event.cancelable)event.preventDefault();
      ignoreClickUntil=0;
      stop();
      const base=restingPose();
      // A launcher has no selected destination while idle. Its temporary lens originates
      // at the touched item; an interrupted material keeps the pose already on screen.
      if(config.transient&&mode==='rest'&&!(material?.engagement>.01))pose=restingPose(index);
      // Only an uninitialised indicator adopts a resting slot; otherwise the press keeps whatever is on screen,
      // including a pose the page is driving through a cyclic move.
      if(!(pose.width>0)){pose={cx:base.cx,width:base.width,height:base.height};vel={cx:0,width:0,height:0}}
      const from=committedIndex();
      const held={cx:pose.cx,width:pose.width||base.width,height:pose.height||base.height};
      session={id:event.pointerId,downX:event.clientX,downY:event.clientY,index,from,owned:false,done:false,
        grabDx:event.clientX-hostLeft-(index===from?held.cx:opts[index].centre),
        samples:[],speedSlots:0,baseWidth:rest.width,lastMove:performance.now()/1000,generation:++generation,candidate:from};
      sample(session,event);
      material?.begin(event.clientX,event.clientY);
      materialAt=performance.now();
      mode='press';
      targets=index===from?{cx:held.cx,width:base.width,height:base.height}:restingPose(index);
      run();
    }
    function move(event){
      if(!session||event.pointerId!==session.id)return;
      material?.aim(event.clientX,event.clientY);
      const dx=event.clientX-session.downX,dy=event.clientY-session.downY;
      if(!session.owned){
        if(Math.abs(dy)>tuning.slop&&Math.abs(dy)>=Math.abs(dx)){cancel('vertical');return} // the scroller keeps it
        if(Math.abs(dx)<tuning.slop)return;
        session.owned=true;haptics.tick();session.baseWidth=rest.width;
        if(material)material.state.dragging=true;
        try{host.setPointerCapture(session.id)}catch{}
        host.classList.add('is-dragging');
        session.catchup=pose.cx-(event.clientX-session.grabDx-hostLeft);
        session.catchupV=vel.cx;
        session.samples=[];sample(session,event);
      }
      if(event.cancelable)event.preventDefault();
      const previous=session.samples[session.samples.length-1];
      sample(session,event);
      session.rawCx=event.clientX-session.grabDx-hostLeft;
      const dt=previous?Math.max(1e-3,event.timeStamp/1000-previous.t):0;
      if(dt>0&&previous){
        const speed=Math.abs(event.clientX-previous.x)/dt/slotSpan(session.candidate);
        session.speedSlots=Math.max(session.speedSlots,speed);
      }
      session.lastMove=performance.now()/1000;
      const candidate=nearestCentre(centres,session.rawCx,session.candidate);
      if(candidate!==session.candidate&&Math.abs(opts[candidate].centre-session.rawCx)<slotSpan(candidate)*.42){session.candidate=candidate;haptics.tick()}
      run();
    }
    function up(event){
      if(!session||event.pointerId!==session.id||session.done)return;
      const active=session;active.done=true;
      ignoreClickUntil=performance.now()+450;
      material?.end();
      try{host.releasePointerCapture(active.id)}catch{}
      session=null;
      // A release away from the track commits nothing. An owned drag is judged vertically only, so pulling past a
      // wall along the track still commits; an unowned tap must end over the control it started on.
      const box=host.getBoundingClientRect();
      const vertical=event.clientY>=box.top-48&&event.clientY<=box.bottom+48;
      const horizontal=event.clientX>=box.left-24&&event.clientX<=box.right+24;
      if(!vertical||(!active.owned&&!horizontal)){config.onCancel?.('outside');settleTo(committedIndex());return}
      if(active.owned){
        sample(active,event);
        const projected=projectCentre(active.rawCx,velocityNow(active),slotSpan(active.candidate),tuning.projectionHorizon);
        const index=nearestCentre(centres,projected.centre,active.from);
        commit(index,'drag');
        settleTo(index,clamp(projected.velocity,-8*slotSpan(index),8*slotSpan(index)));
      }else{commit(active.index,'tap');settleTo(active.index)}
    }
    function commit(index,reason){
      const option=opts[index];if(!option||option.el.disabled)return;
      if(option.id===config.committed()){config.onReselect?.(option.id,reason);return}
      haptics.select();config.commit(option.id,reason);
    }
    function cancel(reason){
      if(!session)return;
      const active=session;session=null;active.done=true;
      ignoreClickUntil=performance.now()+450;
      material?.end(true);
      try{host.releasePointerCapture(active.id)}catch{}
      config.onCancel?.(reason);
      settleTo(committedIndex());
    }
    function lost(event){
      if(!session||event.pointerId!==session.id)return;
      // Touch starts with implicit capture on the child; moving it to the track is not cancellation.
      if(event.type==='lostpointercapture'&&event.target!==host)return;
      cancel(event.type);
    }

    function sync(id,{animate=true}={}){
      if(session)return; // an owned gesture keeps the pose it is showing
      if(!measure())return;
      const index=opts.findIndex(o=>o.id===id);
      if(index<0)return;
      if(!animate||mode==='rest'&&pose.width===0){const target=restingPose(index);pose={...target};vel={cx:0,width:0,height:0};targets={...target};stop();render();mode='rest';return}
      settleTo(index);
    }
    function setPose(next){ // a page-owned move, for example Body's cyclic metric gesture
      if(session)return;
      stop();mode='rest';pose={cx:next.cx,width:next.width??rest.width,height:next.height??rest.height};vel={cx:0,width:0,height:0};targets={...pose};const moving=stepMaterial();render();if(moving)run();
    }
    function remeasure(){if(measure()&&!session){const target=restingPose();pose={...target};targets={...target};render()}}
    function suspend(){
      if(session)cancel('interrupted');stop();material?.reset();
      if(opts.length){pose=restingPose();targets={...pose};vel={cx:0,width:0,height:0};mode='rest';render()}
      host.classList.remove('is-dragging');
    }
    function keyDown(event){
      if(session||event.repeat||!['Enter',' '].includes(event.key)||!event.target.closest(config.optionSelector)||!measure())return;
      const o=opts.find(o=>o.el===event.target.closest(config.optionSelector));if(!o||o.el.disabled)return;
      material?.begin(hostLeft+o.centre,host.getBoundingClientRect().top+o.top+o.height/2);run();
    }
    function keyUp(event){if(!session&&['Enter',' '].includes(event.key)){material?.end();run()}}
    function focusOut(){if(!session){material?.end();run()}}
    function blockClick(event){if(event.detail&&performance.now()<ignoreClickUntil){event.preventDefault();event.stopImmediatePropagation()}}
    // A navigation commit can move the track before the browser emits its click.
    // Consume that release even if it is retargeted to newly revealed content.
    // A new physical press clears the guard, so the next deliberate tap still works.
    const nextPress=()=>{ignoreClickUntil=0};
    const visibility=()=>{if(document.hidden)suspend()};
    function destroy(){
      destroyed=true;stop();session=null;material?.destroy();
      host.removeEventListener('pointerdown',down);host.removeEventListener('pointermove',move);
      for(const type of ['pointerup','pointercancel','lostpointercapture'])host.removeEventListener(type,type==='pointerup'?up:lost);
      document.removeEventListener('pointerup',up);document.removeEventListener('pointercancel',lost);
      host.removeEventListener('keydown',keyDown);host.removeEventListener('keyup',keyUp);host.removeEventListener('focusout',focusOut);
      document.removeEventListener('click',blockClick,true);document.removeEventListener('pointerdown',nextPress,true);
      window.removeEventListener('blur',suspend);window.removeEventListener('resize',suspend);document.removeEventListener('visibilitychange',visibility);
    }
    host.addEventListener('pointerdown',down);host.addEventListener('pointermove',move);
    host.addEventListener('pointerup',up);host.addEventListener('pointercancel',lost);host.addEventListener('lostpointercapture',lost);
    document.addEventListener('pointerup',up);document.addEventListener('pointercancel',lost);
    host.addEventListener('keydown',keyDown);host.addEventListener('keyup',keyUp);host.addEventListener('focusout',focusOut);
    document.addEventListener('click',blockClick,true);document.addEventListener('pointerdown',nextPress,true);
    window.addEventListener('blur',suspend);window.addEventListener('resize',suspend);document.addEventListener('visibilitychange',visibility);
    if(measure()){const target=restingPose();pose={...target};targets={...target};render()}
    return {host,sync,remeasure,setPose,cancel:()=>cancel('external'),destroy,
      get busy(){return Boolean(session)},get dragging(){return Boolean(session?.owned)},
      get pose(){return {...pose}},get influences(){return lastInfluences.map(v=>({...v}))},
      get options(){return opts.map(o=>({id:o.id,centre:o.centre,width:o.width}))},get bounds(){return {...bounds}}};
  }
  return {create,clamp,smoothstep01,rubberband,catchments,influences,wallPose,projectCentre,nearestCentre,spring,TUNING};
})();
