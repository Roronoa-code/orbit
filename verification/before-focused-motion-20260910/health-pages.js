/* Visual health summaries and app-owned workouts. Measurements remain labelled demo data. */
'use strict';
const Health = (() => {
  const titles={body:'Body',workouts:'Workouts',overview:'Health'},kinds=['Walking','Running','Cycling','Strength'];
  const key='orbit-workouts-v1',q=s=>document.querySelector(s);
  let page=null,store={active:null,history:[]},storageFault=false,options,timer=0,returnFocus;
  let bodyMetric='weight',bodyRange=30,bodyDate=null,setup=null,countdown=null,countTimer=0;
  const now=()=>performance.timeOrigin+performance.now();
  const stamp=(date,full=false)=>new Date(date).toLocaleDateString('en-GB',full?{day:'numeric',month:'short',year:'numeric'}:{day:'numeric',month:'short'});
  const elapsed=(session,time=now())=>{if(session&&session===store.active&&window.OrbitWorkouts?.elapsedMs){const value=window.OrbitWorkouts.elapsedMs();if(Number.isFinite(value)&&value>=0)return value}return session?Math.max(0,session.elapsed+(session.resumedAt===null?0:Math.max(0,time-session.resumedAt))):0};
  const clock=ms=>{const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s/60)%60;return (h?h+':':'')+String(m).padStart(2,'0')+':'+String(s%60).padStart(2,'0')};
  const validNumber=n=>Number.isFinite(n)&&n>=0;
  const validTarget=n=>n===undefined||n===0||Number.isInteger(n)&&n>=60000&&n<=86400000;
  function validSession(s,active){return s&&kinds.includes(s.kind)&&validNumber(s.startedAt)&&validNumber(s.elapsed)&&validTarget(s.targetMs)&&(active?(s.resumedAt===null||validNumber(s.resumedAt)):validNumber(s.endedAt))}
  function decode(raw){const v=JSON.parse(raw);if(!v||!Array.isArray(v.history)||(v.active!==null&&!validSession(v.active,true))||!v.history.every(row=>validSession(row,false)))throw Error('Invalid workout history');return v}
  function readStore(){
    try{const native=window.OrbitWorkouts,nativeRaw=native?native.read():null,raw=nativeRaw??localStorage.getItem(key);if(raw===null){storageFault=false;return}const value=decode(raw);if(native&&nativeRaw===null&&!native.write(raw))throw Error('Migration not saved');store=value;storageFault=false}
    catch{storageFault=true}
  }
  function changed(){render();options.onChange();schedule()}
  function commit(value){
    try{const encoded=JSON.stringify(value),native=window.OrbitWorkouts;if(native){if(!native.write(encoded)||native.read()!==encoded)throw Error('Save not confirmed')}else{localStorage.setItem(key,encoded);if(localStorage.getItem(key)!==encoded)throw Error('Save not confirmed')}}
    catch{options.notice('Could not save. The previous workout state is unchanged.');return false}
    store=value;changed();return true;
  }
  function action(name,kind,targetMs=0){
    if(storageFault){options.notice('Saved workouts could not be read. Your data has been preserved.');return false}
    if(name==='start'&&(!kinds.includes(kind)||!validTarget(targetMs)))return false;
    const native=window.OrbitWorkouts;
    if(native?.action){try{const raw=native.action(name,kind||'',targetMs);if(raw===null)throw Error('Save failed');store=decode(raw);changed();return true}catch{options.notice('Could not update the workout. Open it and try again.');return false}}
    const active=store.active,time=now();
    if(name==='start'){if(active)return false;return commit({...store,active:{kind,startedAt:time,elapsed:0,resumedAt:time,targetMs}})}
    if(!active)return false;
    if(name==='pause'&&active.resumedAt!==null)return commit({...store,active:{...active,elapsed:elapsed(active,time),resumedAt:null}});
    if(name==='resume'&&active.resumedAt===null)return commit({...store,active:{...active,resumedAt:time}});
    if(name==='finish')return commit({active:null,history:[{kind:active.kind,startedAt:active.startedAt,endedAt:time,elapsed:elapsed(active,time),targetMs:active.targetMs||0},...store.history]});
    return false;
  }
  function live(){const a=store.active;return a?{title:a.kind,elapsed:clock(elapsed(a)),paused:a.resumedAt===null,targetMs:a.targetMs||0,remaining:clock(Math.max(0,(a.targetMs||0)-elapsed(a)))}:null}
  const bodyFields={weight:['Weight','kg'],muscle:['Muscle','kg'],fat:['Body fat','%'],fatMass:['Fat mass','kg'],lean:['Lean mass','kg']};
  function sample(which,date){
    const ago=options.dates.length-1-options.dates.indexOf(date),weight=75.8+ago*.025,fat=18.2+Math.sin(ago)*.3;
    if(which==='body')return {weight,muscle:34.1+Math.sin(ago)*.2,fat,fatMass:weight*fat/100,lean:weight*(1-fat/100)};
    return {value:98-ago%3,low:97-ago%3,high:99};
  }
  const activityIcon=kind=>`<svg viewBox="0 0 80 80" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${kind==='Walking'?'<circle cx="42" cy="12" r="6"/><path d="m39 26-10 12-10 5m20-17 7 17 12 7M39 26l-4 23 13 19m-13-19-7 23"/>':kind==='Cycling'?'<circle cx="18" cy="58" r="13"/><circle cx="64" cy="58" r="13"/><path d="m18 58 15-28 14 28H18m29 0 9-32h-8M28 30h13m14-4-3-9h10"/>':kind==='Strength'?'<path d="M7 29v22m9-29v36m48-36v36m9-29v22M16 40h48"/>':'<circle cx="46" cy="13" r="6"/><path d="m41 26-12 9-10 3m20-11 11 15 14-1M39 27l-7 20 15 12 3 14M32 47l-9 15-12 5"/>'}</svg>`;
  function spark(values,bar=false){
    const min=Math.min(...values),max=Math.max(...values),span=Math.max(1,max-min),points=values.map((v,i)=>`${8+i*284/Math.max(1,values.length-1)},${70-(v-min)/span*52}`);
    return `<svg class="mini-plot" viewBox="0 0 300 88" preserveAspectRatio="none" aria-hidden="true"><path d="M8 77H292" stroke="#ffffff24" stroke-dasharray="1 5"/>${bar?values.map((v,i)=>`<path d="M${8+i*284/Math.max(1,values.length-1)} 70v-${9+(v-min)/span*45}" stroke="${i===values.length-1?'#b69cff':'#dedee5'}" stroke-width="3" stroke-linecap="round"/>`).join(''):`<polyline points="${points.join(' ')}" fill="none" stroke="#b69cff" stroke-width="2" stroke-linejoin="round"/>`}</svg>`;
  }
  function overview(){
    const date=options.getDate(),d=options.getDaily(date),oxygen=sample('oxygen',date),ratio=Math.min(1,d.steps/d.goal),sleep=Math.floor(d.asleep/60)+'h '+d.asleep%60+'m';
    return `<section class="health-tile activity-hero"><div class="health-section-head"><h2>Your activity</h2><span>${stamp(date+'T12:00:00')}</span></div><button class="activity-track" data-metric="steps" aria-label="Steps, ${d.steps} of ${d.goal}. Open step details"><svg viewBox="0 0 320 170" aria-hidden="true"><rect x="12" y="12" width="296" height="146" rx="73" fill="none" stroke="#3b3b42" stroke-width="13"/><rect x="12" y="12" width="296" height="146" rx="73" fill="none" stroke="#b69cff" stroke-width="13" pathLength="100" stroke-dasharray="${ratio*100} 100" stroke-linecap="round"/><rect x="36" y="36" width="248" height="98" rx="49" fill="none" stroke="#ffffff0c" stroke-width="1"/></svg><span><strong class="dot-value">${d.steps.toLocaleString('en-GB')}</strong><small>of ${d.goal.toLocaleString('en-GB')} steps</small></span></button><div class="activity-facts"><span><strong>${(d.steps*.00075).toFixed(2)}</strong><small>km travelled</small></span><span><strong>${Math.round(d.steps*.04)}</strong><small>kcal estimated</small></span><span><strong>${Math.round(d.steps/d.goal*100)}%</strong><small>step goal</small></span></div></section>
    <div class="health-grid"><button class="health-tile health-tile-button" data-open="workouts"><h2>Workout</h2><div class="tile-art">${activityIcon('Running')}<span class="round-arrow">↗</span></div><small>Choose your activity</small></button><button class="health-tile health-tile-button" data-open="body"><h2>Body</h2><p class="tile-value dot-value">${sample('body',date).weight.toFixed(1)}<small>kg</small></p>${spark(options.dates.filter(r=>r<=date).slice(-10).map(r=>sample('body',r).weight))}<small>Weight trend</small></button>
    <button class="health-tile health-tile-button" data-metric="heart"><h2>Heart rate</h2><p class="tile-value">${d.latest}<small>bpm</small></p>${spark(d.heart.filter(v=>v!==null),true)}<small>Through the day</small></button><details class="health-tile oxygen-tile"><summary><h2>Blood oxygen</h2><p class="tile-value">${oxygen.value}<small>%</small></p><div class="oxygen-dots" aria-hidden="true">· · <b>│</b> · ·</div><small>Latest reading <span>⌄</span></small></summary><p class="oxygen-detail">${oxygen.low}–${oxygen.high}% today<br>Recent: ${options.dates.filter(r=>r<=date).slice(-7).map(r=>sample('oxygen',r).value+'%').join(' · ')}</p></details>
    <button class="health-tile health-tile-button compact-tile" data-metric="sleep"><h2>Sleep</h2><p class="tile-value">${sleep}</p><small>Time asleep</small></button><button class="health-tile health-tile-button compact-tile" data-metric="intake"><h2>Intake</h2><p class="tile-value">${d.meals.reduce((n,m)=>n+m.calories,0)}<small>kcal</small></p><small>${(d.water/1000).toFixed(1)} L water</small></button></div>`;
  }
  function bodyRows(){return options.dates.filter(date=>date<=options.getDate()).slice(-bodyRange)}
  function bodyView(){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1);
    const [label,unit]=bodyFields[bodyMetric],data=sample('body',bodyDate),first=sample('body',rows[0])[bodyMetric],delta=data[bodyMetric]-first;
    return `<section class="health-tile body-hero"><div class="health-section-head"><h2>${label}</h2><div class="segmented">${[7,30].map(n=>`<button data-body-range="${n}" aria-pressed="${bodyRange===n}">${n}D</button>`).join('')}</div></div><p class="health-number dot-value" id="body-value">${data[bodyMetric].toFixed(1)} <span>${unit}</span></p><p class="body-change" id="body-change">${delta>0?'+':''}${delta.toFixed(1)} ${unit} <span>since ${stamp(rows[0]+'T12:00:00')}</span></p><figure class="body-chart" aria-label="${label} trend. Use the date slider for exact values.">${spark(rows.map(date=>sample('body',date)[bodyMetric]))}<input id="body-scrub" type="range" min="0" max="${rows.length-1}" value="${rows.indexOf(bodyDate)}" aria-label="Inspect body measurement date" aria-valuetext="${stamp(bodyDate+'T12:00:00')}: ${data[bodyMetric].toFixed(1)} ${unit}"/><figcaption><span>${stamp(rows[0]+'T12:00:00')}</span><output id="body-date">${stamp(bodyDate+'T12:00:00',true)}</output></figcaption></figure></section><div class="health-grid body-metrics">${Object.entries(bodyFields).map(([field,[name,u]])=>`<button class="health-tile health-tile-button ${field===bodyMetric?'selected':''}" data-body-metric="${field}" aria-pressed="${field===bodyMetric}"><h2>${name}</h2><p class="tile-value"><span data-body-value="${field}">${data[field].toFixed(1)}</span><small>${u}</small></p></button>`).join('')}</div><p class="health-note">Fat mass and lean mass are calculated from weight and body fat. Muscle is part of lean mass.</p>`;
  }
  function inspectBody(index){const rows=bodyRows();if(!Number.isInteger(index)||!rows[index])return;bodyDate=rows[index];const data=sample('body',bodyDate),unit=bodyFields[bodyMetric][1],delta=data[bodyMetric]-sample('body',rows[0])[bodyMetric];q('#body-value').innerHTML=data[bodyMetric].toFixed(1)+` <span>${unit}</span>`;q('#body-change').innerHTML=`${delta>0?'+':''}${delta.toFixed(1)} ${unit} <span>since ${stamp(rows[0]+'T12:00:00')}</span>`;q('#body-date').textContent=stamp(bodyDate+'T12:00:00',true);q('#body-scrub').setAttribute('aria-valuetext',`${stamp(bodyDate+'T12:00:00')}: ${data[bodyMetric].toFixed(1)} ${unit}`);document.querySelectorAll('[data-body-value]').forEach(n=>n.textContent=data[n.dataset.bodyValue].toFixed(1))}
  function workoutHome(){
    const recent=store.history[0],bins=Array.from({length:7},(_,i)=>{const date=new Date(now());date.setHours(0,0,0,0);date.setDate(date.getDate()-6+i);const next=new Date(date);next.setDate(next.getDate()+1);const records=store.history.filter(r=>r.endedAt>=date.getTime()&&r.endedAt<next.getTime());return {date,records,minutes:records.reduce((n,r)=>n+r.elapsed,0)/60000}}),count=bins.reduce((n,b)=>n+b.records.length,0),mins=Math.round(bins.reduce((n,b)=>n+b.minutes,0)),peak=Math.max(1,...bins.map(b=>b.minutes));
    return `<section class="workout-intro"><p class="health-eyebrow">Make time for movement</p><h2>Find your<br>next rhythm.</h2></section><div class="workout-kinds">${kinds.map(kind=>`<button data-setup="${kind}" ${storageFault?'disabled':''}>${activityIcon(kind)}<span>${kind}</span><small>↗</small></button>`).join('')}</div>${storageFault?'<p class="health-error">Saved workouts could not be read. Recording is disabled to preserve them.</p>':''}<section class="health-tile workout-week"><div class="health-section-head"><h2>Last 7 days</h2><span>${count} sessions</span></div><p class="tile-value dot-value">${mins}<small>min</small></p><div class="week-bars" role="img" aria-label="${bins.map(b=>stamp(b.date)+': '+Math.round(b.minutes)+' minutes').join(', ')}">${bins.map(b=>`<span><i style="height:${Math.max(3,b.minutes/peak*64)}px;opacity:${b.minutes?1:.25}"></i><small>${b.date.toLocaleDateString('en-GB',{weekday:'narrow'})}</small></span>`).join('')}</div></section><details class="health-tile workout-history"><summary><span><h2>${recent?'Recent workout':'Your workouts'}</h2><small>${recent?recent.kind+' · '+stamp(recent.endedAt):'Completed sessions appear here'}</small></span><strong>${recent?clock(recent.elapsed):'—'}</strong><span>⌄</span></summary>${store.history.length?'<ol>'+store.history.map(r=>`<li><span>${r.kind}<small>${stamp(r.endedAt,true)}${r.targetMs?' · '+r.targetMs/60000+' min target':''}</small></span><strong>${clock(r.elapsed)}</strong></li>`).join(''):'<p class="health-note">Choose an activity to get started.</p>'}</details>`;
  }
  function setupView(){return `<section class="workout-setup"><div class="workout-art">${activityIcon(setup.kind)}</div><p class="health-eyebrow">${setup.kind}</p><h2>Your pace.<br>Your time.</h2><form id="workout-setup-form"><fieldset><legend>Workout target</legend><label><input type="radio" name="target" value="open" ${setup.targetMs?'':'checked'}/> Open workout</label><label><input type="radio" name="target" value="time" ${setup.targetMs?'checked':''}/> Time target</label></fieldset><label class="target-minutes">Minutes<input id="target-minutes" type="number" min="1" max="1440" step="1" value="${setup.targetMs?setup.targetMs/60000:30}" inputmode="numeric"/></label><p class="health-note">Records your time. GPS and watch tracking are not connected.</p><p class="health-error" id="workout-error" role="alert"></p><button class="workout-primary" type="submit">Start ${setup.kind.toLowerCase()} <span>→</span></button></form></section>`}
  function sessionView(){
    const a=store.active,t=elapsed(a),paused=a.resumedAt===null;
    return `<section class="workout-live"><div class="workout-live-top">${activityIcon(a.kind)}<p class="health-eyebrow" id="session-status">${paused?'Paused':'In progress'}</p></div><div class="workout-time-focus"><p>Duration</p><output class="session-time dot-value" id="session-time" tabindex="-1" aria-label="Workout elapsed time">${clock(t)}</output>${a.targetMs?`<progress id="session-progress" value="${Math.min(t,a.targetMs)}" max="${a.targetMs}"></progress><p class="target-remaining" id="target-remaining">${t>=a.targetMs?'Target reached':clock(a.targetMs-t)+' remaining'} · ${a.targetMs/60000} min target</p>`:'<p class="target-remaining">Open workout</p>'}</div><div class="workout-unconnected"><span>Distance<strong>— <small>km</small></strong></span><span>Heart rate<strong>— <small>bpm</small></strong></span></div><p class="health-note">GPS and watch not connected</p><div class="session-actions"><button data-session="${paused?'resume':'pause'}">${paused?'Resume':'Pause'}</button><button data-session="finish">Finish</button></div>${window.OrbitWorkouts?.notificationStatus&&window.OrbitWorkouts.notificationStatus()==='disabled'?'<button class="notification-enable" data-notifications>Enable live notification</button>':''}</section>`;
  }
  function render(){
    if(!page)return;
    const body=q('#health-content'),scroll=q('#health-scroll').scrollTop,focus=page==='workouts'&&(store.active||setup||countdown!==null);
    q('#health-title').textContent=page==='workouts'?(store.active?.kind||setup?.kind||titles[page]):titles[page];
    q('#health-source').textContent=page==='workouts'?'':'Demo data · '+stamp(options.getDate()+'T12:00:00',true);
    q('#health-source').hidden=page==='workouts';q('#health-page').classList.toggle('workout-focus',Boolean(focus));
    body.innerHTML=page==='body'?bodyView():page==='overview'?overview():countdown!==null?`<div class="workout-countdown"><p>Get ready</p><output class="dot-value" id="workout-count">${countdown}</output><button data-cancel-countdown>Cancel</button></div>`:store.active?sessionView():setup?setupView():workoutHome();
    q('#health-scroll').scrollTop=scroll;
  }
  function cancelCountdown(){clearInterval(countTimer);countTimer=0;countdown=null}
  function startCountdown(kind,targetMs){
    if(store.active||!kinds.includes(kind)||!validTarget(targetMs))return false;
    cancelCountdown();setup={kind,targetMs};countdown=3;render();
    countTimer=setInterval(()=>{countdown--;if(countdown<=0){cancelCountdown();if(action('start',kind,targetMs)){setup=null;render();q('#session-time').focus({preventScroll:true})}else render()}else q('#workout-count').textContent=countdown},1000);return true;
  }
  function open(which){
    if(!Object.hasOwn(titles,which))return false;
    if(!page)returnFocus=document.activeElement;page=which;setup=null;cancelCountdown();options.beforeOpen();q('#health-page').hidden=false;q('.screen').classList.add('is-detail');
    for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island','#live-island'])q(selector).inert=true;
    render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true});q('#health-page').setAttribute('aria-label',titles[which]);return true;
  }
  function close(){
    if(!page)return false;
    if(countdown!==null){cancelCountdown();render();return true}
    if(setup){setup=null;render();return true}
    page=null;q('#health-page').hidden=true;q('.screen').classList.remove('is-detail');
    for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island','#live-island'])q(selector).inert=false;
    options.onClose();if(returnFocus?.isConnected&&!returnFocus.closest('[inert]'))returnFocus.focus({preventScroll:true});else q('#live-bar').focus({preventScroll:true});return true;
  }
  function refresh(){const before=JSON.stringify(store);if(window.OrbitWorkouts)readStore();if(before!==JSON.stringify(store)){render();options.onChange();schedule()}}
  function tick(){refresh();if(page==='workouts'&&store.active&&q('#session-time')){const t=elapsed(store.active);q('#session-time').textContent=clock(t);if(store.active.targetMs){q('#session-progress').value=Math.min(t,store.active.targetMs);q('#target-remaining').textContent=(t>=store.active.targetMs?'Target reached':clock(store.active.targetMs-t)+' remaining')+' · '+store.active.targetMs/60000+' min target'}}options.onTick()}
  function schedule(){clearInterval(timer);timer=0;if(store.active&&!document.hidden)timer=setInterval(tick,1000)}
  function init(config){
    options=config;readStore();q('#health-back').addEventListener('click',close);
    q('#health-content').addEventListener('click',event=>{
      const b=event.target.closest('button');if(!b)return;
      if(b.dataset.session){if(action(b.dataset.session)){if(b.dataset.session==='finish'){options.notice('Workout saved');q('#health-back').focus({preventScroll:true})}else q('#health-content [data-session="'+(store.active.resumedAt===null?'resume':'pause')+'"]').focus({preventScroll:true})}}
      else if(b.dataset.setup){setup={kind:b.dataset.setup,targetMs:0};render();q('#health-scroll').scrollTop=0;q('input[name="target"]:checked').focus({preventScroll:true})}
      else if(b.dataset.bodyMetric){bodyMetric=b.dataset.bodyMetric;render();q('[data-body-metric="'+bodyMetric+'"]').focus({preventScroll:true})}
      else if(b.dataset.bodyRange){bodyRange=Number(b.dataset.bodyRange);render();q('[data-body-range="'+bodyRange+'"]').focus({preventScroll:true})}
      else if(b.dataset.open)open(b.dataset.open);
      else if(b.dataset.metric){close();options.openMetric(b.dataset.metric)}
      else if(b.hasAttribute('data-cancel-countdown')){cancelCountdown();render()}
      else if(b.hasAttribute('data-notifications'))window.OrbitWorkouts?.enableNotifications();
    });
    q('#health-content').addEventListener('input',event=>{if(event.target.id==='body-scrub')inspectBody(Number(event.target.value))});
    q('#health-content').addEventListener('submit',event=>{if(event.target.id!=='workout-setup-form')return;event.preventDefault();const time=q('input[name="target"]:checked').value==='time',minutes=Number(q('#target-minutes').value);if(time&&(!Number.isInteger(minutes)||minutes<1||minutes>1440)){q('#workout-error').textContent='Choose between 1 and 1,440 minutes.';return}startCountdown(setup.kind,time?minutes*60000:0)});
    document.addEventListener('visibilitychange',()=>{if(document.hidden&&countdown!==null){cancelCountdown();render()}schedule();if(!document.hidden)tick()});
    window.addEventListener('focus',()=>{refresh();if(page)render()});schedule();
  }
  return {init,open,close,render,live,action,sample,elapsed,clock,validSession,validTarget,refresh,startCountdown,get page(){return page},get state(){return store}};
})();
