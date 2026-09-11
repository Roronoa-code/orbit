/* Shared surface motion: the shell changes shape while its text keeps its size. */
'use strict';
const SurfaceMotion = (() => {
  const running=new Map(),fades=new Map(),reduced=matchMedia('(prefers-reduced-motion: reduce)');
  let frame=0,last=0,snapshot=null;
  const rect=node=>node?.getBoundingClientRect?.();
  const immediate=()=>reduced.matches||document.hidden;
  function fadeContents(node,out=false){
    for(const child of node.querySelectorAll('.health-page-head,.health-scroll')){
      const previous=fades.get(child),opacity=previous?Number(getComputedStyle(child).opacity):1;
      previous?.cancel();child.style.opacity='';fades.delete(child);if(immediate())continue;
      const animation=child.animate([{opacity:out||previous?opacity:0},{opacity:out?0:1}],{duration:out?90:200,delay:out||previous?0:child.classList.contains('health-page-head')?300:100,easing:'ease-out',fill:'both'});
      fades.set(child,animation);
      animation.onfinish=()=>{child.style.opacity=out?'0':'';animation.cancel();fades.delete(child)};
    }
  }
  function stop(node,clear=true){const item=running.get(node);if(item)running.delete(node);if(clear&&node?.style){node.style.clipPath='';node.style.willChange='';node.style.height='';node.style.overflow='';node.style.opacity=''}return item}
  function finish(item){running.delete(item.node);item.finish?.()}
  function paint(item){
    if(item.type==='clip'){
      const [top,right,bottom,left,radius]=item.values.map(v=>v.value);
      item.node.style.clipPath=`inset(${top}px ${right}px ${bottom}px ${left}px round ${Math.max(0,radius)}px)`;
    }else{item.node.style.height=Math.max(0,item.values[0].value)+'px';item.node.style.opacity=String(Math.min(1,Math.max(0,item.values[0].value/Math.max(1,item.full))))}
  }
  function settle(){cancelAnimationFrame(frame);frame=0;for(const [child,animation] of fades){animation.cancel();child.style.opacity=''}fades.clear();for(const item of [...running.values()]){item.values.forEach((v,i)=>{v.value=item.target[i];v.velocity=0});paint(item);finish(item)}}
  function tick(time){
    const dt=Math.min(.05,Math.max(0,(time-last)/1000));last=time;
    for(const item of [...running.values()]){
      item.values.forEach((v,i)=>springStep(v,item.target[i],dt,18));paint(item);
      if(item.values.every((v,i)=>Math.abs(v.value-item.target[i])<.5&&Math.abs(v.velocity)<8)){item.values.forEach((v,i)=>v.value=item.target[i]);paint(item);finish(item)}
    }
    frame=running.size?requestAnimationFrame(tick):0;
  }
  function start(item){running.set(item.node,item);if(immediate()){settle();return}if(!frame){last=performance.now();frame=requestAnimationFrame(tick)}}
  function shape(bounds,source){
    if(!source)return [0,0,0,0,0];
    return [source.top-bounds.top,bounds.right-source.right,bounds.bottom-source.bottom,source.left-bounds.left,Math.min(source.width,source.height)/2];
  }
  function clip(node,source,closing,done){
    const bounds=rect(node);if(!bounds||immediate()){stop(node);done?.();return}
    const previous=running.get(node),from=shape(bounds,source),full=[0,0,0,0,0];
    const item={node,type:'clip',values:previous?.type==='clip'?previous.values:(closing?full:from).map(value=>({value,velocity:0})),target:closing?from:full,
      finish:()=>{node.style.clipPath='';node.style.willChange='';done?.()}};
    node.style.willChange='clip-path';paint(item);start(item);
  }
  function removeSnapshot(){if(snapshot){stop(snapshot);snapshot.remove();snapshot=null}}
  function copyPage(page){
    if(!page?.cloneNode||page.hidden||immediate())return null;
    removeSnapshot();const copy=page.cloneNode(true),bounds=rect(page),screen=rect(document.querySelector('.screen'));
    copy.removeAttribute('id');copy.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));
    copy.inert=true;copy.setAttribute('aria-hidden','true');copy.classList.add('surface-snapshot');
    Object.assign(copy.style,{left:bounds.left-screen.left+'px',top:bounds.top-screen.top+'px',width:bounds.width+'px',height:bounds.height+'px',right:'auto',bottom:'auto'});
    page.parentElement.appendChild(copy);copy.querySelector('.health-scroll').scrollTop=page.querySelector('.health-scroll').scrollTop;
    snapshot=copy;return copy;
  }
  function reveal(page,source){removeSnapshot();if(rect(page))fadeContents(page);clip(page,rect(source),false)}
  function dismiss(page,target,done){removeSnapshot();if(rect(page))fadeContents(page,true);clip(page,rect(target),true,done)}
  function change(update,source,backTarget){
    const page=document.querySelector('#health-page'),origin=rect(source),old=copyPage(page),wasMoving=running.get(page);
    const result=update();if(result===false){removeSnapshot();return false}
    if(!old)return result;
    fadeContents(old,true);fadeContents(page);
    if(backTarget){
      if(wasMoving)running.set(old,{...wasMoving,node:old});stop(page);old.style.zIndex='24';
      clip(old,rect(backTarget()),true,()=>{if(snapshot===old)snapshot=null;old.remove()});
    }else{old.style.zIndex='21';clip(page,origin,false,()=>{if(snapshot===old)snapshot=null;old.remove()})}
    return result;
  }
  function expand(node,open,done){
    if(!node)return;
    const previous=running.get(node),before=rect(node)?.height||0;
    if(!rect(node)||immediate()){stop(node);node.hidden=!open;done?.();return}
    node.hidden=false;node.style.height='';const full=node.scrollHeight;
    const item={node,type:'height',full,open,values:previous?.type==='height'?previous.values:[{value:before,velocity:0}],target:[open?full:0],finish:()=>{node.hidden=!open;node.style.height='';node.style.opacity='';node.style.overflow='';node.style.willChange='';done?.()}};
    node.style.overflow='hidden';node.style.willChange='height,opacity';paint(item);start(item);
  }
  function toggleDetails(details){
    const body=details.querySelector('.details-body'),current=running.get(body),open=!(current?.open??details.open);
    if(!details.open){body.hidden=true;details.open=true}
    details.querySelector('summary').setAttribute('aria-expanded',String(open));
    expand(body,open,()=>{details.open=open});
  }
  document.addEventListener('visibilitychange',()=>{if(document.hidden)settle()});
  reduced.addEventListener('change',()=>{if(reduced.matches)settle()});
  window.addEventListener('resize',settle);
  return {reveal,dismiss,change,expand,toggleDetails,settle,get active(){return running.size}};
})();
