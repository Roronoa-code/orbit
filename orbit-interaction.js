/* Shared tactile feedback and direct manipulation; the native host owns system haptic preferences. */
'use strict';
const OrbitInteraction=(()=>{
  let last=-Infinity;
  function haptic(kind='select'){
    if(document.hidden||performance.now()-last<55)return;
    last=performance.now();window.OrbitFeedback?.pulse(kind);
  }
  const compact=node=>node?.matches?.('input[type=number],input[inputmode=numeric],input[inputmode=decimal]');
  // Let a short numeric value receive the keyboard programmatically. Preventing the text-touch default avoids
  // the insertion handle; a normal caret, hardware keyboard, IME, validation and paste remain available.
  document.addEventListener('pointerdown',event=>{
    if(event.pointerType!=='touch'||!compact(event.target)||event.target.disabled)return;
    event.preventDefault();event.target.focus({preventScroll:true});
  },true);
  document.addEventListener('touchstart',event=>{
    if(event.touches.length!==1||!compact(event.target)||event.target.disabled)return;
    event.preventDefault();event.target.focus({preventScroll:true});
  },{capture:true,passive:false});
  document.addEventListener('contextmenu',event=>{if(compact(event.target))event.preventDefault()},true);
  document.addEventListener('click',event=>{
    if(!event.isTrusted)return;
    const target=event.target.closest('button,summary,a,input[type=checkbox]');
    if(target&&!target.disabled&&!target.closest('[inert]')&&target.dataset.session!=='finish'&&(!target.closest('.blob-track')||event.detail===0))haptic();
  },true);
  const rangeValues=new WeakMap();
  document.addEventListener('input',event=>{
    const n=event.target;if(!event.isTrusted||!n.matches('input[type=range]'))return;
    if(rangeValues.get(n)!==n.value){rangeValues.set(n,n.value);haptic('tick')}
  });
  function swipe(host,resolve){
    let drag=null,swallowUntil=0;
    const finish=(event,cancel=false)=>{
      const d=drag;if(!d||event.pointerId!==d.id)return;drag=null;
      if(d.owned){swallowUntil=performance.now()+400;try{host.releasePointerCapture(d.id)}catch{}}
      const dx=event.clientX-d.x,dy=event.clientY-d.y;
      const done=!cancel&&d.owned&&Math.abs(dx)>=48&&Math.abs(dx)>Math.abs(dy)*1.2;
      if(done){d.commit(Math.sign(dx));haptic('select')}
      if(d.node.isConnected){const start=d.node.style.transform;d.node.style.removeProperty('transform');if(!document.hidden&&!SurfaceMotion.reduced)d.node.animate([{transform:start},{transform:'none'}],{duration:180,easing:'cubic-bezier(.2,.7,.2,1)'})}
    };
    host.addEventListener('pointerdown',event=>{
      if(!event.isPrimary||event.button!==0||event.target.closest('button,input,a,summary,label,.blob-track,[data-body-dial],.night-chart,.workout-music'))return;
      const route=resolve(event.target);if(!route)return;
      drag={...route,id:event.pointerId,x:event.clientX,y:event.clientY,owned:false};
    });
    host.addEventListener('pointermove',event=>{
      const d=drag;if(!d||d.id!==event.pointerId)return;
      const dx=event.clientX-d.x,dy=event.clientY-d.y;
      if(!d.owned){if(Math.abs(dy)>8&&Math.abs(dy)>=Math.abs(dx)){drag=null;return}if(Math.abs(dx)<=10)return;d.owned=true;host.setPointerCapture(d.id);haptic('tick')}
      if(event.cancelable)event.preventDefault();
      if(!SurfaceMotion.reduced)d.node.style.transform=`translateX(${Math.sign(dx)*Math.min(56,Math.abs(dx)*.3)}px)`;
    });
    host.addEventListener('pointerup',event=>finish(event));
    host.addEventListener('pointercancel',event=>finish(event,true));
    host.addEventListener('lostpointercapture',event=>{if(event.target===host)finish(event,true)});
    host.addEventListener('click',event=>{if(event.detail&&performance.now()<swallowUntil){event.preventDefault();event.stopImmediatePropagation()}},true);
    const abort=()=>{if(drag)finish({pointerId:drag.id,clientX:drag.x,clientY:drag.y},true)};
    window.addEventListener('blur',abort);window.addEventListener('resize',abort);
    document.addEventListener('visibilitychange',()=>{if(document.hidden)abort()});
  }
  return {haptic,swipe};
})();
