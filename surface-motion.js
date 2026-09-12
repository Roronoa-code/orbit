/* Short native fades and small slides; no page clones or expanding page masks. */
'use strict';
const SurfaceMotion=(()=>{
  const running=new Map();
  let reduced=false;try{reduced=localStorage.getItem('orbit-reduce-motion-v1')==='true'}catch{}
  const immediate=()=>document.hidden||reduced;
  function setReduced(value){const next=Boolean(value);localStorage.setItem('orbit-reduce-motion-v1',String(next));if(localStorage.getItem('orbit-reduce-motion-v1')!==String(next))throw Error('Motion setting was not saved');reduced=next;settle()}
  function stop(node){const old=running.get(node);if(old){old.animation.onfinish=null;old.animation.cancel();running.delete(node)}return old}
  function play(node,frames,duration,finish,open){
    stop(node);if(!node?.getBoundingClientRect||immediate()){finish?.();return}
    const animation=node.animate(frames,{duration,easing:'cubic-bezier(.2,.7,.2,1)',fill:'both'}),entry={animation,finish,open};
    running.set(node,entry);animation.onfinish=()=>{if(running.get(node)!==entry)return;running.delete(node);animation.onfinish=null;animation.cancel();finish?.()};
  }
  function reveal(node,offset={x:0,y:8},duration=190){const moving=running.has(node),style=moving?getComputedStyle(node):null;play(node,[{opacity:style?.opacity||0,transform:style?.transform||`translate(${offset.x}px,${offset.y}px)`},{opacity:1,transform:'translate(0,0)'}],duration)}
  function shift(node,offset={x:0,y:8},duration=220){const style=running.has(node)?getComputedStyle(node):null;play(node,[{opacity:1,transform:style?.transform||`translate(${offset.x}px,${offset.y}px)`},{opacity:1,transform:'translate(0,0)'}],duration)}
  function dismiss(node,done){const style=node?.getBoundingClientRect?getComputedStyle(node):null;play(node,[{opacity:style?.opacity||1,transform:style?.transform||'translateY(0)'},{opacity:0,transform:'translateY(6px)'}],130,done)}
  function pop(node,open){play(node,[{opacity:open?0:1,transform:open?'translateY(5px)':'translateY(0)'},{opacity:open?1:0,transform:open?'translateY(0)':'translateY(3px)'}],open?170:110)}
  function change(update){const result=update();if(result!==false)reveal(document.querySelector('#health-content'));return result}
  function expand(node,open,done){
    if(!node)return;const before=node.getBoundingClientRect?.().height||0;stop(node);node.hidden=false;node.style.height='';const full=node.scrollHeight;node.style.overflow='hidden';
    play(node,[{height:before+'px',opacity:open?0:1},{height:(open?full:0)+'px',opacity:open?1:0}],190,()=>{node.hidden=!open;node.style.height='';node.style.overflow='';done?.()},open);
  }
  function toggleDetails(details){const body=details.querySelector('.details-body'),open=!(running.get(body)?.open??details.open);if(!details.open){body.hidden=true;details.open=true}details.querySelector('summary').setAttribute('aria-expanded',String(open));expand(body,open,()=>{details.open=open})}
  function settle(){for(const [node,entry] of [...running]){stop(node);entry.finish?.()}}
  document.addEventListener('visibilitychange',()=>{if(document.hidden)settle()});window.addEventListener('resize',settle);
  return {reveal,shift,dismiss,pop,change,expand,toggleDetails,settle,setReduced,get reduced(){return reduced},get active(){return running.size}};
})();
