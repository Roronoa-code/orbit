/* Full health pages and the app-owned workout timer. No sensor readings are fabricated. */
'use strict';
const Health = (() => {
  const titles={body:'Body composition',workouts:'Workouts',oxygen:'Blood oxygen'};
  const icons={body:'body-icon',workouts:'workout-icon',oxygen:'oxygen-icon'};
  const kinds=['Walking','Running','Cycling','Strength'];
  const key='orbit-workouts-v1',q=s=>document.querySelector(s);
  let page=null,store={active:null,history:[]},storageFault=false,options,timer=0,returnFocus;
  const now=()=>performance.timeOrigin+performance.now();
  const stamp=(date,full=false)=>new Date(date).toLocaleDateString('en-GB',full?{day:'numeric',month:'short',year:'numeric'}:{day:'numeric',month:'short'});
  const elapsed=(session,time=now())=>session?Math.max(0,session.elapsed+(session.resumedAt===null?0:Math.max(0,time-session.resumedAt))):0;
  const clock=ms=>{const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s/60)%60;return (h?h+':':'')+String(m).padStart(2,'0')+':'+String(s%60).padStart(2,'0')};
  const validNumber=n=>Number.isFinite(n)&&n>=0;
  function validSession(s,active){return s&&kinds.includes(s.kind)&&validNumber(s.startedAt)&&validNumber(s.elapsed)&&(active?(s.resumedAt===null||validNumber(s.resumedAt)):validNumber(s.endedAt))}
  function readStore(){
    try{const native=window.OrbitWorkouts,nativeRaw=native?native.read():null,raw=nativeRaw??localStorage.getItem(key);if(raw===null)return;const value=JSON.parse(raw);if(!value||!Array.isArray(value.history)||(value.active!==null&&!validSession(value.active,true))||!value.history.every(row=>validSession(row,false)))throw Error('Invalid workout history');if(native&&nativeRaw===null&&!native.write(raw))throw Error('Migration not saved');store=value}
    catch{storageFault=true}
  }
  function commit(value){
    if(storageFault){options.notice('Workout storage could not be read. Your saved data has been preserved.');return false}
    try{const encoded=JSON.stringify(value),native=window.OrbitWorkouts;if(native){if(!native.write(encoded)||native.read()!==encoded)throw Error('Native write not confirmed')}else{localStorage.setItem(key,encoded);if(localStorage.getItem(key)!==encoded)throw Error('Write not confirmed')}}
    catch{options.notice('Could not save the workout. The previous session state is unchanged.');return false}
    store=value;render();options.onChange();schedule();return true;
  }
  function action(name,kind){
    const active=store.active,time=now();
    if(name==='start'){if(active||!kinds.includes(kind))return false;return commit({...store,active:{kind,startedAt:time,elapsed:0,resumedAt:time}})}
    if(!active)return false;
    if(name==='pause'&&active.resumedAt!==null)return commit({...store,active:{...active,elapsed:elapsed(active,time),resumedAt:null}});
    if(name==='resume'&&active.resumedAt===null)return commit({...store,active:{...active,resumedAt:time}});
    if(name==='finish')return commit({active:null,history:[{kind:active.kind,startedAt:active.startedAt,endedAt:time,elapsed:elapsed(active,time)},...store.history]});
    return false;
  }
  function live(){const a=store.active;return a?{title:a.kind,elapsed:clock(elapsed(a)),paused:a.resumedAt===null}:null}
  function sample(which,date){
    const ago=options.dates.length-1-options.dates.indexOf(date),minutes=32+ago%12;
    if(which==='body')return {value:75.8+ago*.025,unit:'kg',label:'Weight',stats:[['Weight',(75.8+ago*.025).toFixed(1),'kg'],['Muscle',(34.1+Math.sin(ago)*.2).toFixed(1),'kg'],['Body fat',(18.2+Math.sin(ago)*.3).toFixed(1),'%']]};
    if(which==='oxygen')return {value:98-ago%3,unit:'%',label:'Blood oxygen',stats:[['Latest',String(98-ago%3),'%'],['Lowest',String(97-ago%3),'%'],['Highest','99','%']]};
    return {value:minutes,unit:'min',label:'Walking',stats:[['Duration',String(minutes),'min'],['Distance',(minutes*105*.72/1000).toFixed(2),'km'],['Energy',String(Math.round(minutes*4.1)),'kcal']]};
  }
  function trend(rows,which){
    const values=rows.map(r=>sample(which,r).value),min=Math.min(...values),max=Math.max(...values),span=Math.max(max-min,which==='body'?.5:1);
    const points=values.map((v,i)=>`${8+i*284/Math.max(1,values.length-1)},${75-(v-min)/span*57}`).join(' ');
    return `<figure class="health-trend" aria-label="${titles[which]} trend, ${rows.length} days; exact values in history below"><svg viewBox="0 0 300 90" preserveAspectRatio="none" aria-hidden="true"><path d="M8 18H292M8 75H292" stroke="#ffffff13" fill="none"/><polyline points="${points}" fill="none" stroke="#b69cff" stroke-width="1.7" stroke-linejoin="round"/></svg><figcaption><span>${stamp(rows[0]+'T12:00:00')}</span><span>${stamp(rows.at(-1)+'T12:00:00')}</span></figcaption></figure>`;
  }
  function statCells(stats){return '<dl class="health-stats">'+stats.map(([name,value,unit])=>`<div><dt>${name}</dt><dd>${value} <span>${unit}</span></dd></div>`).join('')+'</dl>'}
  function sessionPanel(){
    if(store.active)return `<section class="health-glass session-panel"><p class="health-eyebrow">${store.active.resumedAt===null?'Paused':'In progress'} · ${store.active.kind}</p><output class="session-time" id="session-time" aria-label="Workout elapsed time">${clock(elapsed(store.active))}</output><p class="health-note">Duration only · sensor tracking is not connected in this copy.</p><div class="session-actions"><button data-session="${store.active.resumedAt===null?'resume':'pause'}">${store.active.resumedAt===null?'Resume':'Pause'}</button><button data-session="finish">Finish &amp; save</button></div></section>`;
    return `<section class="health-glass session-panel"><h2>Start a workout</h2><p class="health-note">Record elapsed time. GPS and watch sensors are not connected in this copy.</p><div class="workout-kinds">${kinds.map(kind=>`<button data-session="start" data-kind="${kind}" ${storageFault?'disabled':''}>${kind}<span aria-hidden="true">↗</span></button>`).join('')}</div>${storageFault?'<p class="health-error">Saved workout data could not be read. Recording is disabled to preserve it.</p>':''}</section>`;
  }
  function render(){
    if(!page)return;
    const selected=options.getDate(),rows=options.dates.filter(date=>date<=selected),data=sample(page,selected),body=q('#health-content'),scroll=q('#health-scroll').scrollTop;
    q('#health-title').textContent=titles[page];q('#health-source').textContent=page==='workouts'?'Your sessions and demonstration history':'Demonstration history · '+stamp(selected+'T12:00:00',true);
    let content=page==='workouts'?sessionPanel():`<section class="health-overview"><p class="health-eyebrow">${data.label}</p><p class="health-number">${data.stats[0][1]} <span>${data.unit}</span></p>${statCells(data.stats.slice(1))}</section>`;
    const label=page==='body'?'Weight over time':page==='oxygen'?'Oxygen over time':'Workout duration';
    content+=`<section class="health-glass"><div class="health-section-head"><h2>${label}</h2><span>${rows.length} days</span></div>${trend(rows,page)}</section>`;
    if(page==='workouts')content+=`<section class="health-history"><h2>Saved on this device</h2>${store.history.length?'<ol>'+store.history.map(r=>`<li><span>${r.kind}<small>${stamp(r.endedAt,true)}</small></span><strong>${clock(r.elapsed)}</strong></li>`).join(''):'<p class="health-note">Your completed workouts will appear here.</p>'}</section>`;
    content+=`<section class="health-history"><div class="health-section-head"><h2>${page==='workouts'?'Demonstration sessions':'Measurement history'}</h2><span>${rows.length} records</span></div><ol>${rows.slice().reverse().map(date=>{const r=sample(page,date);return `<li><span>${stamp(date+'T12:00:00',true)}<small>${page==='body'?'Muscle '+r.stats[1][1]+' kg · Fat '+r.stats[2][1]+'%':page==='oxygen'?'Range '+r.stats[1][1]+'–'+r.stats[2][1]+'%':'Walking · '+r.stats[1][1]+' km · '+r.stats[2][1]+' kcal'}</small></span><strong>${r.stats[0][1]} <small>${r.unit}</small></strong></li>`}).join('')}</ol></section>`;
    body.innerHTML=content;q('#health-scroll').scrollTop=scroll;
  }
  function open(which){
    if(!Object.hasOwn(titles,which))return false;
    returnFocus=document.activeElement;page=which;options.beforeOpen();q('#health-page').hidden=false;q('.screen').classList.add('is-detail');
    for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island'])q(selector).inert=true;
    render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true});q('#health-page').setAttribute('aria-label',titles[which]);return true;
  }
  function close(){
    if(!page)return false;page=null;q('#health-page').hidden=true;q('.screen').classList.remove('is-detail');
    for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island'])q(selector).inert=false;
    options.onClose();if(returnFocus?.isConnected&&!returnFocus.closest('[inert]'))returnFocus.focus({preventScroll:true});else q('#live-bar').focus({preventScroll:true});return true;
  }
  function tick(){if(page==='workouts'&&q('#session-time'))q('#session-time').textContent=clock(elapsed(store.active));options.onTick()}
  function schedule(){clearInterval(timer);timer=0;if(store.active&&!document.hidden)timer=setInterval(tick,1000)}
  function init(config){
    options=config;readStore();q('#health-back').addEventListener('click',close);
    q('#health-content').addEventListener('click',event=>{const b=event.target.closest('[data-session]');if(b)action(b.dataset.session,b.dataset.kind)});
    document.addEventListener('visibilitychange',()=>{schedule();if(!document.hidden)tick()});schedule();
  }
  return {init,open,close,render,live,action,sample,elapsed,clock,validSession,get page(){return page},get state(){return store}};
})();
