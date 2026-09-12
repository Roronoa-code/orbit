/* One physical selector: a measured track, a single moving indicator and the influence its rendered overlap gives
   every option. Geometry belongs to this module; the committed value belongs to the page. A press answers at once,
   a held neighbour leans within a hard cap of one tenth of a local slot, a drag follows the pointer without
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
  // The stationary lean toward a pressed option. The budget is one tenth of one local slot, never of the bar or of
  // the distance to that option; 85% of it moves the centre and 15% grows the width, so the rendered leading edge
  // stays inside the budget.
  function holdPose(base,slotWidth,direction,progress=1){
    for(const key of ['cx','width','height'])finite(key,base[key]);
    finite('slotWidth',slotWidth);finite('direction',direction);finite('progress',progress);
    if(base.width<=0||base.height<=0||slotWidth<=0)throw new RangeError('Positive geometry is required');
    const d=Math.sign(direction),budget=d===0?0:slotWidth*.10*clamp(progress,0,1);
    return {cx:base.cx+d*budget*.85,width:base.width+budget*.15,height:base.height};
  }
  const leadingExcursion=(pose,base)=>Math.abs(pose.cx-base.cx)+Math.max(0,pose.width-base.width)/2;
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
    pressShrink:8,pressStiffness:260,pressDamping:.78,
    settleStiffness:160,settleDamping:.74,shapeStiffness:500,shapeDamping:1,
    slop:6,maxStretch:.16,squash:.45,stretchSlotsPerSecond:8,stretchTau:.035,
    velocityWindow:.1,stillness:.06,projectionHorizon:.12,holdCap:.10,
  };

  function create(config){
    const host=config.host;if(!host)throw new TypeError('A track host is required');
    const tuning=Object.assign({},TUNING,config.tuning||{});
    const noop=()=>{};
    const haptics=config.haptics||{tick:noop,select:noop};
    let opts=[],zones=[],bounds={left:0,right:0},rest={width:0,height:0},maxHeight=0,targets={cx:0,width:0,height:0};
    let pose={cx:0,width:0,height:0},vel={cx:0,width:0,height:0},stretch=0,stretchVelocity=0;
    let mode='rest',session=null,frame=0,generation=0,ownIndex=0,lastInfluences=[],destroyed=false,driven=null;
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
      zones=catchments(opts.map(o=>o.centre),inner.left,inner.right);
      const active=opts[committedIndex()];
      rest={width:active.width,height:active.height};
      maxHeight=Math.max(rest.height,base.height-(parseFloat(style.paddingTop)||0)-(parseFloat(style.paddingBottom)||0));
      return true;
    }
    const slotSpan=i=>{const z=zones[clamp(i,0,zones.length-1)];return Math.max(1,z.right-z.left)};
    // The cap uses the active slot and, for a farther press, the nearest neighbour in that direction.
    function referenceSlot(from,to){
      if(from===to)return opts[from].width;
      const step=Math.sign(to-from);
      return Math.max(1,Math.min(opts[from].width,opts[clamp(from+step,0,opts.length-1)].width));
    }
    function restingPose(index=committedIndex()){const o=opts[clamp(index,0,opts.length-1)];return {cx:o.centre,width:o.width,height:o.height}}

    function render(){
      const shaped=wallPose(pose.cx,pose.width,pose.height,bounds.left,bounds.right,maxHeight);
      const active=opts[committedIndex()];
      const left=shaped.cx-shaped.width/2,top=active?active.top+(active.height-shaped.height)/2:0;
      if(config.render)config.render({cx:shaped.cx,left,top,width:shaped.width,height:shaped.height},{opts,bounds,mode});
      else{
        host.style.setProperty('--selection-x',left.toFixed(2)+'px');
        host.style.setProperty('--selection-y',top.toFixed(2)+'px');
        host.style.setProperty('--selection-width',shaped.width.toFixed(2)+'px');
        host.style.setProperty('--selection-height',shaped.height.toFixed(2)+'px');
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
        let moving=false;
        if(dragging){
          // Translation is raw: the finger owns the centre. Only the deformation relaxes over time.
          const target=Math.min(1,session.speedSlots/tuning.stretchSlotsPerSecond)*tuning.maxStretch;
          stretch+=(target-stretch)*(1-Math.exp(-dt/tuning.stretchTau));
          session.speedSlots*=Math.exp(-dt/tuning.stretchTau);
          pose.cx=session.rawCx;vel.cx=0;
          pose.width=session.baseWidth*(1+stretch);
          pose.height=rest.height*(1-tuning.squash*stretch);
          moving=stretch>.001;
        }else{
          const p=spring(pose.cx,vel.cx,targets.cx,dt,position,damping);pose.cx=p.x;vel.cx=p.velocity;
          const w=spring(pose.width,vel.width,targets.width,dt,mode==='press'?position:tuning.shapeStiffness,mode==='press'?damping:tuning.shapeDamping);pose.width=w.x;vel.width=w.velocity;
          const h=spring(pose.height,vel.height,targets.height,dt,tuning.shapeStiffness,tuning.shapeDamping);pose.height=h.x;vel.height=h.velocity;
          stretch=0;
          // The hold cap binds every rendered frame, including the press spring's overshoot.
          if(mode==='press'&&session)capHold();
          moving=Math.abs(pose.cx-targets.cx)>.05||Math.abs(vel.cx)>.5||Math.abs(pose.width-targets.width)>.05||Math.abs(vel.width)>.5||Math.abs(pose.height-targets.height)>.05;
          if(!moving){pose.cx=targets.cx;pose.width=targets.width;pose.height=targets.height;vel={cx:0,width:0,height:0}}
        }
        render();
        if(moving||dragging)frame=requestAnimationFrame(tick);
        else if(mode==='settle'){mode='rest';host.classList.remove('is-dragging')}
      };
      frame=requestAnimationFrame(tick);
    }
    // Both contributions scale back together, so the rendered leading edge lands exactly on the budget.
    function capHold(){
      const base=session.base,budget=session.reference*tuning.holdCap;
      if(budget<=0)return;
      const excursion=leadingExcursion(pose,base);
      if(excursion<=budget)return;
      const factor=budget/excursion;
      pose.cx=base.cx+(pose.cx-base.cx)*factor;
      pose.width=base.width+(pose.width-base.width)*factor;
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
      if(destroyed||!event.isPrimary||session||!measure())return;
      const el=event.target.closest?.(config.optionSelector);
      const index=el?opts.findIndex(o=>o.el===el):-1;
      if(index<0)return;
      stop();
      const base=restingPose();
      // Only an uninitialised indicator adopts a resting slot; otherwise the press keeps whatever is on screen,
      // including a pose the page is driving through a cyclic move.
      if(!(pose.width>0)){pose={cx:base.cx,width:base.width,height:base.height};vel={cx:0,width:0,height:0}}
      const from=committedIndex();
      // The press leans from the pose actually on screen, so interrupting a settle never snaps the indicator back
      // to a resting slot, and the hold cap measures the lean rather than that distance.
      const held={cx:pose.cx,width:pose.width||base.width,height:pose.height||base.height};
      session={id:event.pointerId,downX:event.clientX,downY:event.clientY,index,from,owned:false,done:false,
        base:held,reference:referenceSlot(from,index),
        samples:[],speedSlots:0,baseWidth:rest.width,lastMove:performance.now()/1000,generation:++generation,candidate:from};
      sample(session,event);
      mode='press';
      if(index===from){
        const shrink=Math.min(tuning.pressShrink,rest.width*.25);
        targets={cx:held.cx,width:Math.max(8,held.width-shrink),height:held.height};
      }else{
        const lean=holdPose(held,session.reference,index-from,1);
        targets={cx:lean.cx,width:lean.width,height:lean.height};
      }
      run();
    }
    function move(event){
      if(!session||event.pointerId!==session.id)return;
      const dx=event.clientX-session.downX,dy=event.clientY-session.downY;
      if(!session.owned){
        if(Math.abs(dy)>tuning.slop&&Math.abs(dy)>=Math.abs(dx)){cancel('vertical');return} // the scroller keeps it
        if(Math.abs(dx)<tuning.slop)return;
        session.owned=true;session.baseWidth=rest.width;
        try{host.setPointerCapture(session.id)}catch{}
        host.classList.add('is-dragging');
        // Rebase from the pose actually on screen, so a press on a distant option never teleports the indicator.
        session.grabDx=event.clientX-(host.getBoundingClientRect().left+pose.cx);
        session.samples=[];sample(session,event);
      }
      if(event.cancelable)event.preventDefault();
      const previous=session.samples[session.samples.length-1];
      sample(session,event);
      const base=host.getBoundingClientRect();
      session.rawCx=event.clientX-session.grabDx-base.left;
      const dt=previous?Math.max(1e-3,event.timeStamp/1000-previous.t):0;
      if(dt>0&&previous){
        const speed=Math.abs(event.clientX-previous.x)/dt/slotSpan(session.candidate);
        session.speedSlots=Math.max(session.speedSlots,speed);
      }
      session.lastMove=performance.now()/1000;
      const candidate=nearestCentre(opts.map(o=>o.centre),session.rawCx,session.candidate);
      if(candidate!==session.candidate&&Math.abs(opts[candidate].centre-session.rawCx)<slotSpan(candidate)*.42){session.candidate=candidate;haptics.tick()}
      run();
    }
    function up(event){
      if(!session||event.pointerId!==session.id||session.done)return;
      const active=session;active.done=true;
      host.classList.remove('is-dragging');
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
        const projected=projectCentre(pose.cx,velocityNow(active),slotSpan(active.candidate),tuning.projectionHorizon);
        const index=nearestCentre(opts.map(o=>o.centre),projected.centre,active.from);
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
      host.classList.remove('is-dragging');
      try{host.releasePointerCapture(active.id)}catch{}
      config.onCancel?.(reason);
      settleTo(committedIndex());
    }
    function lost(event){if(session&&event.pointerId===session.id&&event.type!=='lostpointercapture')cancel(event.type);else if(session&&event.type==='lostpointercapture'&&session.owned)cancel('lost')}

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
      stop();mode='rest';pose={cx:next.cx,width:next.width??rest.width,height:next.height??rest.height};vel={cx:0,width:0,height:0};targets={...pose};render();
    }
    function remeasure(){if(measure()&&!session){const target=restingPose();pose={...target};targets={...target};render()}}
    function destroy(){
      destroyed=true;stop();session=null;
      host.removeEventListener('pointerdown',down);host.removeEventListener('pointermove',move);
      for(const type of ['pointerup','pointercancel','lostpointercapture'])host.removeEventListener(type,type==='pointerup'?up:lost);
    }
    host.addEventListener('pointerdown',down);host.addEventListener('pointermove',move);
    host.addEventListener('pointerup',up);host.addEventListener('pointercancel',lost);host.addEventListener('lostpointercapture',lost);
    if(measure()){const target=restingPose();pose={...target};targets={...target};render()}
    return {sync,remeasure,setPose,cancel:()=>cancel('external'),destroy,
      get busy(){return Boolean(session)},get dragging(){return Boolean(session?.owned)},
      get pose(){return {...pose}},get influences(){return lastInfluences.map(v=>({...v}))},
      get options(){return opts.map(o=>({id:o.id,centre:o.centre,width:o.width}))},get bounds(){return {...bounds}}};
  }
  return {create,clamp,smoothstep01,rubberband,holdPose,leadingExcursion,catchments,influences,wallPose,projectCentre,nearestCentre,spring,TUNING};
})();
