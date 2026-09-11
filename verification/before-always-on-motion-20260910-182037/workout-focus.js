/* Keep the timer in place through focus changes; display only media shared by Android. */
'use strict';
const WorkoutFocus=(()=>{
  const reduced=matchMedia('(prefers-reduced-motion: reduce)'),animations=new Set();
  let dots=null;
  function attach(live,focused){dots?.stop();dots=null;if(focused){const canvas=live.querySelector('#focus-time-dots');dots=HeroDots.mount(canvas,live.querySelector('#session-time').dataset.reading||'00:00',true);canvas.onclick=event=>event.stopPropagation()}}
  function dispose(){settle();dots?.stop();dots=null}
  function settle(){for(const animation of animations)animation.cancel();animations.clear()}
  function animate(node,frames,duration=500){if(reduced.matches||document.hidden)return;const animation=node.animate(frames,{duration,easing:'cubic-bezier(.2,.75,.2,1)'});animations.add(animation);animation.onfinish=()=>{animations.delete(animation);animation.cancel()}}
  function layout(live,update){
    const nodes=[live.querySelector('.timer-dial'),live.querySelector('.session-actions')],before=nodes.map(n=>n.getBoundingClientRect()),page=document.querySelector('#health-page'),scale=page.getBoundingClientRect().width/page.offsetWidth||1;
    settle();update();
    nodes.forEach((node,i)=>{const after=node.getBoundingClientRect(),old=before[i];animate(node,[{transformOrigin:'0 0',transform:`translate(${(old.left-after.left)/scale}px,${(old.top-after.top)/scale}px) scale(${old.width/after.width},${old.height/after.height})`},{transformOrigin:'0 0',transform:'none'}])});
  }
  function toggle(live,focused){
    layout(live,()=>{
      if(!focused)MusicPlayer.stop(true);
      live.classList.toggle('timer-focused',focused);
      const button=live.querySelector('.timer-dial');button.setAttribute('aria-pressed',String(focused));button.setAttribute('aria-label',focused?'Show workout details':'Focus on timer and music');live.querySelector('.timer-tap-hint').textContent=focused?'Tap to show details':'Tap to focus';
      attach(live,focused);if(focused)MusicPlayer.mount(live.querySelector('.workout-music'));
    });
    const arriving=focused?live.querySelector('.music-bottom'):live.querySelector('.workout-live-top');if(arriving&&!arriving.closest('[hidden]'))animate(arriving,[{opacity:0,transform:'translateY(22px)'},{opacity:1,transform:'none'}],480);
  }
  reduced.addEventListener('change',()=>{if(reduced.matches)settle()});document.addEventListener('visibilitychange',()=>{if(document.hidden)settle()});window.addEventListener('resize',settle);
  return {toggle,layout,animate,settle,attach,dispose,reading(value){dots?.set(value)},get active(){return animations.size}};
})();

const MusicPlayer=(()=>{
  let root=null,timer=0,state=null,art='',artKey='',scrubbing=false,scrubId='',notice=()=>{};
  let cover=null,artGeneration=0,artCleanup=0,exitCleanup=0,exiting=null;
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  const icon=name=>`<svg viewBox="0 0 24 24" aria-hidden="true">${{play:'<path d="M8 4 21 12 8 20Z"/>',pause:'<rect x="5" y="4" width="5" height="16" rx="1.3"/><rect x="14" y="4" width="5" height="16" rx="1.3"/>',previous:'<path d="M19 5 7 12 19 19Z"/><rect x="3" y="5" width="3" height="14" rx="1"/>',next:'<path d="m5 5 12 7L5 19Z"/><rect x="18" y="5" width="3" height="14" rx="1"/>',music:'<path d="M10 4v12.2a4 4 0 1 0 2 3.5V8l8-2v8.2a4 4 0 1 0 2 3.5V1Z"/>',open:'<path d="M14 3h7v7h-2V6.4l-8.3 8.3-1.4-1.4L17.6 5H14Z"/><path d="M5 7h6v2H5v10h10v-6h2v8H3V7Z"/>'}[name]||''}</svg>`;
  const clock=ms=>{const s=Math.floor(Math.max(0,ms)/1000);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')};
  function valid(s){return s&&['permission','idle','ready','error','inactive','browser'].includes(s.status)&&(s.status!=='ready'||typeof s.id==='string'&&typeof s.artKey==='string'&&['title','artist','source'].every(k=>typeof s[k]==='string')&&['position','duration'].every(k=>Number.isFinite(s[k])&&s[k]>=0)&&['playing','buffering','canToggle','canPrevious','canNext','canSeek','canOpen'].every(k=>typeof s[k]==='boolean')&&(s.art===undefined||typeof s.art==='string'&&s.art.length<1500000&&(s.art===''||/^data:image\/(?:jpeg|png);base64,[A-Za-z0-9+/=]+$/.test(s.art))))}
  function shell(){root.innerHTML=`<div class="music-track" hidden><div class="music-bottom"><div class="music-heading"><div><h2></h2><p></p></div><button data-music="open" aria-label="Open music app">${icon('open')}</button></div><div class="music-timeline"><input type="range" min="0" max="1" value="0" step="1000" aria-label="Track position"/><div><output class="music-position">0:00</output><span class="music-source"></span><output class="music-duration">0:00</output></div></div><div class="music-controls"><button data-music="previous" aria-label="Previous track">${icon('previous')}</button><button data-music="toggle" aria-label="Play music">${icon('play')}</button><button data-music="next" aria-label="Next track">${icon('next')}</button></div></div></div>`}
  function backdrop(value){document.querySelector('#health-page').style.setProperty('--workout-art',value?`url("${value}")`:'none')}
  function clearArtwork(){artGeneration++;clearTimeout(artCleanup);artCleanup=0;cover?.remove();cover=null;art='';document.querySelector('#health-page').classList.remove('music-focus');backdrop('')}
  function artwork(value){
    if(value===art&&cover?.lastElementChild?.querySelector('img')?.src===value)return;
    if(!value){clearArtwork();return}
    art=value;const generation=++artGeneration,node=root,img=new Image();img.alt='Album artwork';img.src=value;
    img.decode().then(()=>{
      if(root!==node||generation!==artGeneration)return;
      const page=document.querySelector('#health-page');if(!cover){cover=document.createElement('div');cover.className='music-cover';page.append(cover)}
      clearTimeout(artCleanup);while(cover.children.length>1)cover.firstElementChild.remove();
      const previous=cover.lastElementChild,layer=document.createElement('div');layer.className='music-art-layer';layer.style.setProperty('--art',`url("${value}")`);layer.append(img);cover.append(layer);
      backdrop(value);page.classList.add('music-focus');node.classList.add('has-art');
      if(reduced.matches||document.hidden){layer.classList.add('is-visible');previous?.remove();return}
      layer.getBoundingClientRect();requestAnimationFrame(()=>{if(generation!==artGeneration||!layer.isConnected)return;layer.classList.add('is-visible');previous?.classList.remove('is-visible')});
      artCleanup=setTimeout(()=>{previous?.remove();artCleanup=0},620);
    }).catch(()=>{if(root===node&&generation===artGeneration)clearArtwork()});
  }
  function finishExit(){clearTimeout(exitCleanup);exitCleanup=0;if(exiting){exiting.hidden=true;exiting.inert=false;exiting.removeAttribute('aria-hidden');exiting.style.cssText='';exiting=null}clearArtwork();state=null;artKey=''}
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
  function stop(animateExit=false){
    clearInterval(timer);timer=0;if(exiting)finishExit();const node=root,rect=node?.getBoundingClientRect();
    if(node){node.removeEventListener('click',click);node.removeEventListener('input',input);node.removeEventListener('change',change);node.removeEventListener('pointercancel',cancel);node.removeEventListener('focusout',cancel);node.closest('.workout-live')?.classList.remove('has-music')}
    root=null;scrubbing=false;
    if(animateExit&&node&&!node.hidden&&!reduced.matches&&!document.hidden){
      const page=document.querySelector('#health-page'),bounds=page.getBoundingClientRect(),scale=bounds.width/page.offsetWidth||1;
      exiting=node;node.inert=true;node.setAttribute('aria-hidden','true');node.style.cssText=`position:absolute;left:${(rect.left-bounds.left)/scale}px;top:${(rect.top-bounds.top)/scale}px;width:${rect.width/scale}px;z-index:2;transition:opacity 280ms ease,transform 350ms ease;`;
      node.getBoundingClientRect();node.style.opacity='0';node.style.transform='translateY(14px)';if(cover)cover.style.opacity='0';exitCleanup=setTimeout(finishExit,500);
    }else{if(node)node.hidden=true;finishExit()}
  }
  function mount(node){
    if(exiting===node){clearTimeout(exitCleanup);exitCleanup=0;exiting=null;node.inert=false;node.removeAttribute('aria-hidden');node.style.cssText='';root=node;artKey='';if(cover)cover.style.opacity='1'}
    else{stop();root=node;shell()}
    root.addEventListener('click',click);root.addEventListener('input',input);root.addEventListener('change',change);root.addEventListener('pointercancel',cancel);root.addEventListener('focusout',cancel);refresh();if(!document.hidden&&window.OrbitMusic)timer=setInterval(refresh,1000)
  }
  document.addEventListener('visibilitychange',()=>{clearInterval(timer);timer=0;scrubbing=false;if(document.hidden&&exiting)finishExit();if(!document.hidden&&root){refresh();if(window.OrbitMusic)timer=setInterval(refresh,1000)}});
  reduced.addEventListener('change',()=>{if(reduced.matches){if(exiting)finishExit();if(cover){while(cover.children.length>1)cover.firstElementChild.remove();cover.lastElementChild?.classList.add('is-visible')}}});
  return {mount,stop,refresh,valid,connect,init(fn){notice=fn},get active(){return Boolean(root)}};
})();
