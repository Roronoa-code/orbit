/* Health's six readings, local card sizes and compact oxygen history. No generated health data. */
'use strict';
const HealthDashboard=(()=>{
  const key='orbit-health-layout-v1',defaults={sleep:true,steps:false,heart:false,body:false,intake:false,oxygen:true};
  const labels={sleep:'Sleep',steps:'Steps',heart:'Heart rate',body:'Measurements',intake:'Nutrition',oxygen:'Blood oxygen'};
  const q=s=>document.querySelector(s),stamp=value=>new Date(value).toLocaleDateString('en-GB',{day:'numeric',month:'short'});
  let options,sizes=null,order=null,held=null,selected=null,suppressUntil=0,drag=null;
  const moves=new Map();
  function load(){
    if(sizes)return;
    try{order=JSON.parse(SettingsStore.read('orbit-health-order-v1')||'null');
      if(order!==null&&(!Array.isArray(order)||order.length!==6||new Set(order).size!==6||order.some(id=>!Object.hasOwn(defaults,id))))throw Error('Invalid card order');
    }catch{order=null;options.notice('Saved card order could not be read. Using the default order.');}
    order??=Object.keys(defaults);
    try{const raw=SettingsStore.read(key),saved=raw===null?{}:JSON.parse(raw);
      if(!saved||typeof saved!=='object'||Array.isArray(saved)||Object.entries(saved).some(([k,v])=>!Object.hasOwn(defaults,k)||typeof v!=='boolean'))throw Error('Invalid card sizes');
      sizes={...defaults,...saved};
    }catch{sizes={...defaults};options.notice('Saved card sizes could not be read. Using the default layout.');}
  }
  const resizeIcon=wide=>`<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${wide?'M4 9h5V4m6 0v5h5M4 15h5v5m6 0v-5h5':'M9 4H4v5m16 0V4h-5M4 15v5h5m6 0h5v-5'}"/></svg>`;
  function card(id,content){return `<article class="health-card ${sizes[id]?'is-wide':''}" data-health-card="${id}">${content}<button class="card-resize" data-card-resize="${id}" aria-label="${sizes[id]?'Make compact':'Widen'}: ${labels[id]}">${resizeIcon(sizes[id])}</button></article>`}
  const svg=content=>`<svg viewBox="0 0 80 80" class="reading-art" aria-hidden="true">${content}</svg>`;
  const time=value=>new Date(value).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'});
  const extras=items=>`<span class="reading-extra reading-facts">${items.map(([label,value])=>`<span><small>${label}</small><strong>${value}</strong></span>`).join('')}</span>`;
  function sleepWindows(night){
    if(!SleepTimeline.valid(night))return '';
    const blocks=SleepTimeline.blocks(night),windows=[];let start=night.start;
    for(const s of night.segments)if(s.stage==='unrecorded'&&s.end-s.start>=1800000){if(s.start>start)windows.push({start,end:s.start});start=s.end}
    if(start<night.end)windows.push({start,end:night.end});
    const colors={awake:'#ff8098',rem:'#63c8ed',light:'#488bfa',deep:'#6654db',sleeping:'#91a2c3',unknown:'#92909b'};
    return `<span class="reading-extra sleep-windows">${windows.map(w=>`<span class="sleep-bout"><span class="sleep-bout-times"><time>${time(w.start)}</time><time>${time(w.end)}</time></span><svg class="sleep-ribbon" viewBox="0 0 300 12" preserveAspectRatio="none" aria-label="Sleep stages from ${time(w.start)} to ${time(w.end)}">${blocks.filter(s=>s.stage!=='unrecorded'&&s.end>w.start&&s.start<w.end).map(s=>`<rect x="${(Math.max(s.start,w.start)-w.start)/(w.end-w.start)*300}" y="0" width="${(Math.min(s.end,w.end)-Math.max(s.start,w.start))/(w.end-w.start)*300}" height="12" rx="2" fill="${colors[s.stage]}"/>`).join('')}</svg></span>`).join('')}</span>`;
  }
  function view(config){
    options=config;load();clearSelection();for(const a of moves.values())a.cancel();moves.clear();
    const date=options.getDate(),d=options.getDaily(date),body=HealthData.latestBody(date),oxygen=d.oxygen,show=HealthData.display;
    const night=d.sleepTimeline,goal=options.settings.getGoal();
    const button=(id,value,unit,note,art,extra='')=>card(id,`<button class="health-reading reading-${id}" ${['sleep','body'].includes(id)?'data-open':'data-home-metric'}="${id}" aria-describedby="card-size-help"><span class="reading-label">${labels[id]}</span>${art}<span class="health-reading-value">${value}${unit?`<small>${unit}</small>`:''}</span><small>${note}</small>${extra}</button>`);
    const moon=svg('<path d="M52 13a28 28 0 1 0 14 45A29 29 0 0 1 52 13Z" fill="currentColor"/><path d="M62 12v10m-5-5h10M70 32v6m-3-3h6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>');
    const dots=Array.from({length:32},(_,i)=>{const a=i/32*Math.PI*2-Math.PI/2;return `<circle cx="${40+Math.cos(a)*31}" cy="${40+Math.sin(a)*31}" r="2.1" opacity="${d.steps!==null&&i/32<Math.min(1,d.steps/goal)?1:.16}"/>`}).join('');
    const steps=svg(`<g fill="currentColor">${dots}</g><g fill="currentColor" transform="rotate(-18 40 40)"><ellipse cx="33" cy="33" rx="4" ry="8"/><circle cx="33" cy="46" r="3.5"/><ellipse cx="46" cy="44" rx="4" ry="8"/><circle cx="46" cy="57" r="3.5"/></g>`);
    const heart=svg('<path d="M40 65C29 56 12 44 12 28a15 15 0 0 1 28-7 15 15 0 0 1 28 7c0 16-17 28-28 37Z" fill="currentColor"/><path d="M22 28c0-5 4-8 8-7" fill="none" stroke="#f1e7ff" stroke-width="3" stroke-linecap="round"/>');
    const tape=svg('<g transform="rotate(-12 40 40)"><path d="M18 38v17c0 8 10 13 22 13h27V51H40" fill="currentColor" opacity=".58"/><ellipse cx="40" cy="36" rx="25" ry="19" fill="currentColor"/><ellipse cx="40" cy="34" rx="13" ry="8" fill="#211d2a"/><path d="M43 54v7m8-7v4m8-4v7M20 33l6 2m-3-12 5 4m5-9 2 5m13-5-2 5m12 0-5 4" fill="none" stroke="#f4ebff" stroke-width="2" stroke-linecap="round"/></g>');
    const bowl=svg('<path d="M14 39h52c-2 16-11 24-26 24S16 55 14 39Z" fill="currentColor"/><path d="M11 38h58M29 67h22" stroke="currentColor" stroke-width="4" stroke-linecap="round"/><path d="M40 32c-15-1-16-16-16-16s17-1 16 16Zm3-4c0-17 16-18 16-18s1 16-16 18Z" fill="currentColor" opacity=".65"/>');
    const bubbles=svg('<circle cx="40" cy="43" r="24" fill="currentColor" opacity=".16"/><circle cx="64" cy="16" r="7" fill="currentColor" opacity=".65"/><circle cx="17" cy="18" r="4" fill="currentColor"/><text x="23" y="52" fill="currentColor" font-size="29" font-family="inherit">O<tspan font-size="14" dy="5">2</tspan></text>');
    const sleepValue=d.asleep===null?'—':`${Math.floor(Math.round(d.asleep)/60)}<small>h</small> ${Math.round(d.asleep)%60}<small>m</small>`;
    const oxygenValues=oxygenDays().map(date=>HealthData.daily(date).oxygen.value).filter(Number.isFinite);
    const oxygenExtra=extras([['Past week',oxygenValues.length?`${Math.min(...oxygenValues)}${Math.min(...oxygenValues)===Math.max(...oxygenValues)?'':'–'+Math.max(...oxygenValues)}%`:'—'],['Days recorded',`${oxygenValues.length} of 7`]]);
    const oxygenCard=card('oxygen',`<details class="oxygen-tile"><summary class="health-reading reading-oxygen" aria-describedby="card-size-help"><span class="reading-label">Blood oxygen</span>${bubbles}<span class="health-reading-value">${show(oxygen.value)}<small>%</small></span><small>${oxygen.value===null?'No readings shared':oxygen.low===oxygen.high?'Recorded average':`${oxygen.low}–${oxygen.high}% range`}</small>${oxygenExtra}<span class="oxygen-toggle" aria-hidden="true"></span></summary><div class="details-body">${oxygenChart()}</div></details>`);
    const cards={
      sleep:button('sleep',sleepValue,'',night?`${time(night.start)} – ${time(night.end)}`:'No sleep shared',moon,sleepWindows(night)),
      steps:button('steps',show(d.steps),'',d.steps===null?'No steps shared':`${Math.round(d.steps/goal*100)}% of your goal`,steps,extras([['To your goal',d.steps===null?'—':`${show(Math.max(0,goal-d.steps))} steps`],['Distance',d.distance===null?'—':`${show(d.distance/1000,2)} km`]])),
      heart:button('heart',show(d.latest),'bpm',d.latest===null?'No readings shared':`Latest · ${time(d.latestTime)}`,heart,extras([['Lowest',`${show(d.heartLow)} bpm`],['Highest',`${show(d.heartHigh)} bpm`]])),
      body:button('body',show(body.weight,1),'kg',body.date?`${stamp(body.date+'T12:00:00')} · latest weight`:'No measurements shared',tape,extras([['Body fat',`${show(body.fat,1)}%`],['Lean mass',`${show(body.lean,1)} kg`]])),
      intake:button('intake',show(HealthData.sum(d.meals.map(m=>m.calories))),'kcal',d.meals.length?`${d.meals.length} ${d.meals.length===1?'meal':'meals'} recorded`:'No meals shared',bowl,extras([['Protein',`${show(HealthData.sum(d.meals.map(m=>m.protein)))} g`],['Carbs',`${show(HealthData.sum(d.meals.map(m=>m.carbs)))} g`],['Water',`${show(d.water)} ml`]])),oxygen:oxygenCard};
    return `<p class="health-overview-intro">${new Date(date+'T12:00:00').toLocaleDateString('en-GB',{day:'numeric',month:'short',year:'numeric'})}</p><p class="sr-only" id="card-size-help">Hold, then drag to move this card, or use its corner button to change size. With a keyboard, use Alt and an arrow key to move a card.</p><p class="sr-only" id="card-layout-status" aria-live="polite"></p><section class="health-library" aria-label="Your health readings">${order.map(id=>cards[id]).join('')}</section><button class="source-link" data-open="settings">${HealthData.meta.lastSync?'Samsung Health':'Connect Samsung Health'}<span class="health-connection-action">${HealthData.meta.lastSync?'Manage':'Connect'}</span></button>`;
  }
  const oxygenDays=()=>options.dates.filter(date=>date<=options.getDate()).slice(-7);
  function oxygenChart(){
    const rows=oxygenDays().map(date=>({date,value:HealthData.daily(date).oxygen.value})),known=rows.filter(r=>r.value!==null);
    if(!known.length)return '<p class="health-note">No blood oxygen readings have been shared this week.</p>';
    const low=Math.min(90,Math.floor(Math.min(...known.map(r=>r.value))/5)*5),selected=rows.findLastIndex(r=>r.value!==null),x=i=>32+i*280/Math.max(1,rows.length-1),y=value=>14+(100-value)/(100-low)*64;
    const line=rows.map((r,i)=>r.value===null?'':`${!i||rows[i-1].value===null?'M':'L'}${x(i)} ${y(r.value)}`).join(' '),r=rows[selected];
    return `<div class="oxygen-detail-head"><div><span>Past week</span><output id="oxygen-date">${stamp(r.date+'T12:00:00')}</output></div><p><strong id="oxygen-value">${HealthData.display(r.value)}</strong><small>%</small></p></div><figure class="oxygen-chart" aria-label="Blood oxygen over the past week"><svg viewBox="0 0 340 108" aria-hidden="true"><path d="M32 14H312M32 78H312" stroke="#ffffff10"/><text x="0" y="18">100</text><text x="0" y="82">${low}</text><path d="${line}" fill="none" stroke="#bfa7f2" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>${rows.map((r,i)=>`${r.value===null?'':`<circle data-oxygen-point="${i}" cx="${x(i)}" cy="${y(r.value)}" r="${i===selected?4:2}" fill="${i===selected?'#e9dcff':'#a68dcc'}"/>`}<text x="${x(i)}" y="104" text-anchor="middle">${new Date(r.date+'T12:00:00').toLocaleDateString('en-GB',{weekday:'narrow'})}</text>`).join('')}</svg><input id="oxygen-scrub" type="range" min="0" max="${rows.length-1}" step="1" value="${selected}" aria-label="Inspect blood oxygen by day" aria-valuetext="${stamp(r.date+'T12:00:00')}, ${r.value}%"/></figure>`;
  }
  function inspectOxygen(index){
    const date=oxygenDays()[index];if(!Number.isInteger(index)||!date)return;
    const value=HealthData.daily(date).oxygen.value,label=stamp(date+'T12:00:00');q('#oxygen-date').textContent=label;q('#oxygen-value').textContent=HealthData.display(value);q('#oxygen-scrub').setAttribute('aria-valuetext',label+', '+(value===null?'Not recorded':value+'%'));
    document.querySelectorAll('[data-oxygen-point]').forEach(n=>{const active=Number(n.dataset.oxygenPoint)===index;n.setAttribute('r',active?4:2);n.setAttribute('fill',active?'#e9dcff':'#a68dcc')});
  }

  const gridCards=()=>[...document.querySelectorAll('.health-library>.health-card')];
  const positions=()=>new Map(gridCards().map(node=>[node,node.getBoundingClientRect()]));
  function flow(before){
    for(const a of moves.values())a.cancel();moves.clear();
    for(const [node,from] of before){if(!node.isConnected||node===drag?.card)continue;const to=node.getBoundingClientRect();
      if(SurfaceMotion.reduced||document.hidden||!to.width)continue;
      const animation=node.animate([{transform:`translate(${from.x-to.x}px,${from.y-to.y}px) scale(${from.width/to.width},${from.height/to.height})`},{transform:'none'}],{duration:280,easing:'cubic-bezier(.2,.8,.2,1)'});
      moves.set(node,animation);animation.onfinish=()=>{if(moves.get(node)===animation)moves.delete(node)};
    }
  }
  function saveOrder(next){try{SettingsStore.write('orbit-health-order-v1',JSON.stringify(next));order=next;return true}catch{options.notice('Card order could not be saved. Try again.');return false}}
  function clearSelection(){if(drag)finishDrag(false);selected?.classList.remove('is-sizing');selected=null;clearTimeout(held?.timer);held?.node.classList.remove('is-pressed');held=null}
  function revealSize(card){if(selected!==card){selected?.classList.remove('is-sizing');selected=card;card.classList.add('is-sizing');OrbitInteraction.haptic('select')}}
  function resize(id){
    if(!Object.hasOwn(defaults,id))return;
    const next={...sizes,[id]:!sizes[id]};try{SettingsStore.write(key,JSON.stringify(next))}catch{options.notice('Card size could not be saved. Try again.');return}
    const before=positions();sizes=next;
    const card=q(`[data-health-card="${id}"]`),control=card.querySelector('.card-resize');card.classList.toggle('is-wide',sizes[id]);control.innerHTML=resizeIcon(sizes[id]);control.setAttribute('aria-label',`${sizes[id]?'Make compact':'Widen'}: ${labels[id]}`);
    flow(before);
  }
  function dragGeometry(){const base=drag.grid.getBoundingClientRect();drag.boxes=[...drag.grid.children].map(node=>({node,rect:{x:node.offsetLeft+base.x,y:node.offsetTop+base.y,width:node.offsetWidth,height:node.offsetHeight}}))}
  function beginDrag(){
    const card=held.node.closest('.health-card'),grid=card.parentElement,rect=card.getBoundingClientRect(),slot=document.createElement('div');
    // Move the real card above the scroll surface; its slot preserves layout and reading state.
    slot.className='card-drop-slot';slot.dataset.healthCard=card.dataset.healthCard;slot.style.height=rect.height+'px';slot.style.gridColumn=card.classList.contains('is-wide')||card.querySelector('details[open]')?'1 / -1':'';
    grid.insertBefore(slot,card);const scroll=q('#health-scroll'),bounds=scroll.getBoundingClientRect();
    moves.get(card)?.cancel();moves.delete(card);document.body.append(card);card.classList.add('card-dragging');held.node.classList.remove('is-pressed');
    Object.assign(card.style,{position:'fixed',left:rect.x+'px',top:rect.y+'px',width:rect.width+'px',height:rect.height+'px',margin:'0'});
    drag={card,grid,slot,scroll,bounds,rect,x:held.x,y:held.y,px:held.x,py:held.y,frame:0,last:performance.now(),boxes:[]};dragGeometry();drag.frame=requestAnimationFrame(dragFrame);
  }
  function dragFrame(now){
    const d=drag;if(!d)return;const elapsed=Math.min(32,now-d.last);d.last=now;
    d.card.style.transform=`translate3d(${d.px-d.x}px,${d.py-d.y}px,0)`;
    const top=d.bounds.top+80,bottom=d.bounds.bottom-110,speed=d.py<top?-Math.min(12,(top-d.py)*.2):d.py>bottom?Math.min(12,(d.py-bottom)*.2):0;
    if(speed){const old=d.scroll.scrollTop;d.scroll.scrollTop+=speed*elapsed/16;if(old!==d.scroll.scrollTop)dragGeometry()}
    const contains=(r,inset=0)=>d.px>=r.x+r.width*inset&&d.px<=r.x+r.width*(1-inset)&&d.py>=r.y+r.height*inset&&d.py<=r.y+r.height*(1-inset);
    if(!d.boxes.some(b=>b.node===d.slot&&contains(b.rect))){
      const target=d.boxes.find(b=>b.node!==d.slot&&contains(b.rect,.18));
      if(target){const before=positions(),items=[...d.grid.children],forward=items.indexOf(d.slot)<items.indexOf(target.node);d.grid.insertBefore(d.slot,forward?target.node.nextSibling:target.node);flow(before);dragGeometry();OrbitInteraction.haptic('tick')}
    }
    d.frame=requestAnimationFrame(dragFrame);
  }
  function finishDrag(commit){
    const d=drag;if(!d)return;cancelAnimationFrame(d.frame);const before=positions();before.set(d.card,d.card.getBoundingClientRect());
    const next=[...d.grid.children].map(n=>n.dataset.healthCard),saved=commit&&(next.join()===order.join()||saveOrder(next));
    d.slot.replaceWith(d.card);d.card.classList.remove('card-dragging');d.card.removeAttribute('style');drag=null;
    if(!saved)for(const id of order)d.grid.append(d.grid.querySelector(`[data-health-card="${id}"]`));
    flow(before);if(saved){OrbitInteraction.haptic('select');q('#card-layout-status').textContent=`${labels[d.card.dataset.healthCard]} moved to position ${order.indexOf(d.card.dataset.healthCard)+1} of 6`}
  }
  function init(host){
    host.addEventListener('pointerdown',event=>{
      suppressUntil=0;
      if(!event.isPrimary||event.button!==0||event.target.closest('.card-resize,input,.details-body'))return;
      const card=event.target.closest('.health-card');if(!card)return;
      const node=card.querySelector('.health-reading');node.classList.add('is-pressed');
      held={id:event.pointerId,node,x:event.clientX,y:event.clientY,timer:setTimeout(()=>{revealSize(card);held.revealed=true},450)};
    });
    const cancel=commit=>{if(held?.revealed)suppressUntil=performance.now()+600;if(drag)finishDrag(commit);clearTimeout(held?.timer);held?.node.classList.remove('is-pressed');held=null};
    document.addEventListener('pointermove',event=>{if(!held||event.pointerId!==held.id)return;
      if(Math.hypot(event.clientX-held.x,event.clientY-held.y)>10){if(!held.revealed){cancel(false);return}if(!drag)beginDrag()}
      if(drag){drag.px=event.clientX;drag.py=event.clientY;if(event.cancelable)event.preventDefault()}
    });
    document.addEventListener('touchmove',event=>{if(held?.revealed&&event.cancelable)event.preventDefault()},{capture:true,passive:false});
    document.addEventListener('touchstart',event=>{if(event.touches.length>1)cancel(false)},{passive:true});
    document.addEventListener('pointerup',()=>cancel(true));document.addEventListener('pointercancel',()=>cancel(false));
    document.addEventListener('click',event=>{if(event.target.closest('.card-resize'))return;if(event.detail&&performance.now()<suppressUntil){event.preventDefault();event.stopImmediatePropagation();suppressUntil=0}},true);
    host.addEventListener('keydown',event=>{
      if(event.key==='Escape'&&selected){event.preventDefault();event.stopPropagation();clearSelection();return}
      const card=event.target.closest('.health-card');if(!card)return;
      if(event.altKey&&['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){
        event.preventDefault();const id=card.dataset.healthCard,index=order.indexOf(id),to=Math.max(0,Math.min(5,index+(['ArrowLeft','ArrowUp'].includes(event.key)?-1:1))),next=[...order];
        next.splice(index,1);next.splice(to,0,id);if(to===index||!saveOrder(next))return;const before=positions(),focus=document.activeElement,grid=card.parentElement;for(const key of order)grid.append(grid.querySelector(`[data-health-card="${key}"]`));flow(before);focus.focus({preventScroll:true});q('#card-layout-status').textContent=`${labels[id]} moved to position ${to+1} of 6`;return;
      }
      if(event.key==='ContextMenu'||event.key==='F10'&&event.shiftKey){event.preventDefault();revealSize(card);card.querySelector('.card-resize').focus()}
    });
    document.addEventListener('pointerdown',event=>{suppressUntil=0;if(selected&&!selected.contains(event.target))clearSelection()},true);
    window.addEventListener('blur',clearSelection);document.addEventListener('visibilitychange',()=>{if(document.hidden)clearSelection()});
    window.addEventListener('resize',clearSelection);
  }
  function click(button){if(!button.hasAttribute('data-card-resize'))return false;resize(button.dataset.cardResize);return true}
  return {view,init,click,inspectOxygen};
})();
