/* Visual health summaries and app-owned workouts. Measurements remain labelled demo data. */
'use strict';
const Health = (() => {
  const titles={body:'Body',workouts:'Workouts',overview:'Health',sleep:'Your night'},kinds=['Walking','Running','Cycling','Strength'];
  const key='orbit-workouts-v1',q=s=>document.querySelector(s);
  let page=null,store={active:null,history:[]},storageFault=false,options,timer=0,returnFocus;
  let bodyMetric='weight',bodyRange=30,bodyDate=null,setup=null,countdown=null,countTimer=0,countDots=null;
  let timerMode='elapsed',timerFocused=false,dotPattern=0;
  let sleepDate=null,sleepMinute=0,workoutRecord=null,workoutChart='route';
  const now=()=>performance.timeOrigin+performance.now();
  const stamp=(date,full=false)=>new Date(date).toLocaleDateString('en-GB',full?{day:'numeric',month:'short',year:'numeric'}:{day:'numeric',month:'short'});
  const elapsed=(session,time=now())=>{if(session&&session===store.active&&window.OrbitWorkouts?.elapsedMs){const value=window.OrbitWorkouts.elapsedMs();if(Number.isFinite(value)&&value>=0)return value}return session?Math.max(0,session.elapsed+(session.resumedAt==null?0:Math.max(0,time-session.resumedAt))):0};
  const total=session=>{if(session===store.active&&window.OrbitWorkouts?.totalMs){const value=window.OrbitWorkouts.totalMs();if(Number.isFinite(value)&&value>=0)return Math.max(elapsed(session),value)}return Math.max(elapsed(session),session.totalMs??((session.endedAt??now())-session.startedAt))};
  const clock=ms=>{const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s/60)%60;return (h?h+':':'')+String(m).padStart(2,'0')+':'+String(s%60).padStart(2,'0')};
  const validNumber=n=>Number.isFinite(n)&&n>=0;
  const validTarget=n=>n===undefined||n===0||Number.isInteger(n)&&n>=60000&&n<=86400000;
  function validSession(s,active){return s&&WorkoutDetails.valid(s)&&kinds.includes(s.kind)&&validNumber(s.startedAt)&&validNumber(s.elapsed)&&validTarget(s.targetMs)&&(active?(s.resumedAt===null||validNumber(s.resumedAt)):validNumber(s.endedAt))}
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
  function action(name,kind,targetMs=0,recording={}){
    if(storageFault){options.notice('Saved workouts could not be read. Your data has been preserved.');return false}
    if(name==='start'&&(!kinds.includes(kind)||!validTarget(targetMs)||!WorkoutDetails.valid(recording)||recording.trackLocation&&kind==='Strength'))return false;
    const native=window.OrbitWorkouts;
    if(native?.action){try{const raw=name==='start'&&native.start?native.start(kind,targetMs,Boolean(recording.trackLocation),recording.weightKg||0):native.action(name,kind||'',targetMs);if(raw===null)throw Error('Save failed');store=decode(raw);changed();return true}catch{options.notice('Could not update the workout. Open it and try again.');return false}}
    const active=store.active,time=now();
    if(name==='start'){if(active)return false;return commit({...store,active:{kind,startedAt:time,elapsed:0,resumedAt:time,targetMs,weightKg:recording.weightKg||0,trackLocation:Boolean(recording.trackLocation),...(recording.trackLocation?{metrics:{state:'unavailable',distanceM:0,maxSpeedMps:0,speedMps:null,altitudeMinM:null,altitudeMaxM:null,accuracyM:null,points:[]}}:{})}})}
    if(!active)return false;
    if(name==='pause'&&active.resumedAt!==null)return commit({...store,active:{...active,elapsed:elapsed(active,time),resumedAt:null}});
    if(name==='resume'&&active.resumedAt===null)return commit({...store,active:{...active,resumedAt:time}});
    if(name==='finish')return commit({active:null,history:[{...active,resumedAt:undefined,endedAt:time,elapsed:elapsed(active,time),totalMs:total(active),targetMs:active.targetMs||0},...store.history]});
    return false;
  }
  function live(){const a=store.active;return a?{title:a.kind,elapsed:clock(elapsed(a)),paused:a.resumedAt===null,targetMs:a.targetMs||0,remaining:clock(Math.max(0,(a.targetMs||0)-elapsed(a)))}:null}
  const bodyFields={weight:['Weight','kg'],muscle:['Muscle','kg'],fat:['Body fat','%'],fatMass:['Fat mass','kg'],lean:['Lean mass','kg']};
  function sample(which,date){
    const ago=options.dates.length-1-options.dates.indexOf(date),weight=75.8+ago*.025,fat=18.2+Math.sin(ago)*.3;
    if(which==='body')return {weight,muscle:34.1+Math.sin(ago)*.2,fat,fatMass:weight*fat/100,lean:weight*(1-fat/100)};
    return {value:98-ago%3,low:97-ago%3,high:99};
  }
  const activityIcon=kind=>`<span class="workout-glyph" data-kind="${kind.toLowerCase()}" aria-hidden="true"></span>`;
  function spark(values,bar=false){
    const min=Math.min(...values),max=Math.max(...values),span=Math.max(1,max-min),points=values.map((v,i)=>`${8+i*284/Math.max(1,values.length-1)},${70-(v-min)/span*52}`);
    return `<svg class="mini-plot" viewBox="0 0 300 88" preserveAspectRatio="none" aria-hidden="true"><path d="M8 77H292" stroke="#ffffff24" stroke-dasharray="1 5"/>${bar?values.map((v,i)=>`<path d="M${8+i*284/Math.max(1,values.length-1)} 70v-${9+(v-min)/span*45}" stroke="${i===values.length-1?'#b69cff':'#dedee5'}" stroke-width="3" stroke-linecap="round"/>`).join(''):`<polyline points="${points.join(' ')}" fill="none" stroke="#b69cff" stroke-width="2" stroke-linejoin="round"/>`}</svg>`;
  }
  function overview(){
    const date=options.getDate(),oxygen=sample('oxygen',date),recent=options.dates.filter(r=>r<=date).slice(-7);
    return `<div class="health-grid health-destinations"><button class="health-tile health-tile-button" data-open="body"><h2>Body composition</h2><p class="tile-value">${sample('body',date).weight.toFixed(1)}<small>kg</small></p>${spark(options.dates.filter(r=>r<=date).slice(-10).map(r=>sample('body',r).weight))}<small>Measurements ↗</small></button><button class="health-tile health-tile-button" data-open="workouts"><h2>Workout</h2><div class="tile-art">${activityIcon('Running')}<span class="round-arrow">↗</span></div><small>4 activities</small></button></div>
    <details class="health-tile oxygen-tile"><summary><span class="oxygen-symbol" aria-hidden="true">O₂</span><span class="oxygen-label"><h2>Blood oxygen</h2><small>Latest · ${stamp(date+'T12:00:00')}</small></span><p class="tile-value">${oxygen.value}<small>%</small></p><span class="oxygen-chevron">⌄</span></summary><div class="details-body"><div class="oxygen-detail-head"><span>Recent readings</span><output id="oxygen-date">${stamp(date+'T12:00:00')} · ${oxygen.value}%</output></div><div class="oxygen-week" role="group" aria-label="Blood oxygen readings by day">${recent.map(day=>`<button data-oxygen-day="${day}" aria-pressed="${day===date}" aria-label="${stamp(day+'T12:00:00',true)}, ${sample('oxygen',day).value}%"><small>${new Date(day+'T12:00:00').toLocaleDateString('en-GB',{weekday:'short'})}</small><strong>${sample('oxygen',day).value}<small>%</small></strong></button>`).join('')}</div></div></details>`;
  }
  function bodyRows(){return options.dates.filter(date=>date<=options.getDate()).slice(-bodyRange)}
  function sleepView(){const index=options.dates.indexOf(sleepDate);return `<div class="sleep-date-nav"><button data-night-date="-1" aria-label="Previous night" ${index<=0?'disabled':''}>‹</button><span>${stamp(sleepDate+'T12:00:00',true)}</span><button data-night-date="1" aria-label="Next night" ${index>=options.dates.length-1?'disabled':''}>›</button></div>`+SleepTimeline.view(options.getDaily(sleepDate).night,sleepMinute)}
  function bodyRing(data){
    const fraction=bodyMetric==='weight'?1:bodyMetric==='fat'?data.fat/100:data[bodyMetric]/data.weight;
    return `<svg viewBox="0 0 300 300" aria-hidden="true">${Array.from({length:80},(_,i)=>{const a=(i/80*360-90)*Math.PI/180,r=128;const fill=bodyMetric==='weight'?(i/80<data.fat/100?'#b69cff':'#dddce4'):(i/80<fraction?'#b69cff':'#36343e');return `<circle data-mix-dot="${i}" cx="${150+Math.cos(a)*r}" cy="${150+Math.sin(a)*r}" r="3.2" fill="${fill}"/>`}).join('')}<circle cx="150" cy="150" r="112" fill="none" stroke="#ffffff09"/></svg>`;
  }
  function bodyPlot(rows){const values=rows.map(date=>sample('body',date)[bodyMetric]),min=Math.min(...values),span=Math.max(.1,Math.max(...values)-min);return values.map((value,i)=>({x:10+i*280/Math.max(1,values.length-1),y:97-(value-min)/span*65}))}
  function bodyView(){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1);
    const [label,unit]=bodyFields[bodyMetric],data=sample('body',bodyDate),first=sample('body',rows[0])[bodyMetric],delta=data[bodyMetric]-first,points=bodyPlot(rows),point=points[rows.indexOf(bodyDate)],smooth=curve(points);
    return `<section class="body-hero"><div class="body-metric-picker" role="group" aria-label="Body measurement">${Object.entries(bodyFields).map(([field,[name]])=>`<button data-body-metric="${field}" aria-pressed="${field===bodyMetric}">${name}</button>`).join('')}</div><button class="body-composition" data-next-body aria-label="${label}, ${data[bodyMetric].toFixed(1)} ${unit}. Tap to explore the next body measurement"><span id="body-ring">${bodyRing(data)}</span><span class="body-center"><span class="body-measure-name">${label}</span><span id="body-value">${HeroDots.markup(data[bodyMetric].toFixed(1))} <small>${unit}</small></span><span id="body-share">${bodyMetric==='weight'?'Lean mass + fat mass':Math.round((bodyMetric==='fat'?data.fat/100:data[bodyMetric]/data.weight)*100)+'% of body weight'}</span></span></button><p class="body-explore-hint">Tap the centre to explore your body mix</p><div class="body-mix-key"><button data-select-body="lean"><i></i><span>Lean mass<strong data-body-value="lean">${data.lean.toFixed(1)} kg</strong></span></button><button data-select-body="fatMass"><i></i><span>Fat mass<strong data-body-value="fatMass">${data.fatMass.toFixed(1)} kg</strong></span></button></div></section>
    <section class="health-tile body-timeline"><div class="health-section-head"><h2>History</h2><div class="segmented">${[7,30].map(n=>`<button data-body-range="${n}" aria-pressed="${bodyRange===n}">${n}D</button>`).join('')}</div></div><div class="body-date-stepper"><button data-body-step="-1" aria-label="Previous measurement" ${bodyDate===rows[0]?'disabled':''}>‹</button><output id="body-date">${stamp(bodyDate+'T12:00:00',true)}</output><button data-body-step="1" aria-label="Next measurement" ${bodyDate===rows.at(-1)?'disabled':''}>›</button></div><p class="body-change" id="body-change">${delta>0?'+':''}${delta.toFixed(1)} ${unit} <span>since ${stamp(rows[0]+'T12:00:00')}</span></p><figure class="body-chart" aria-label="${label} history. Drag on the chart or use arrow keys to inspect each date."><svg viewBox="0 0 300 120" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="body-area-fill" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#b69cff" stop-opacity=".26"/><stop offset="1" stop-color="#b69cff" stop-opacity=".015"/></linearGradient></defs><path d="M10 107H290M10 70H290M10 33H290" stroke="#ffffff10" stroke-dasharray="1 6"/><path d="${smooth}L${points.at(-1).x} 107L${points[0].x} 107Z" fill="url(#body-area-fill)"/><path d="${smooth}" stroke="#c0a5f5" stroke-width="2" stroke-linecap="round" fill="none"/><path id="body-guide" d="M${point.x} 10V107" stroke="#ffffff35" stroke-dasharray="2 4"/><circle id="body-point" cx="${point.x}" cy="${point.y}" r="4" fill="#e6daff" stroke="#3c304f" stroke-width="2"/></svg><input id="body-scrub" type="range" min="0" max="${rows.length-1}" value="${rows.indexOf(bodyDate)}" aria-label="Inspect body measurement date" aria-valuetext="${stamp(bodyDate+'T12:00:00')}: ${data[bodyMetric].toFixed(1)} ${unit}"/><figcaption><span>${stamp(rows[0]+'T12:00:00')}</span><span>Drag to inspect</span><span>${stamp(rows.at(-1)+'T12:00:00')}</span></figcaption></figure></section><p class="health-note">The ring shows each measurement as a share of body weight. Fat and lean mass are calculated from weight and body fat; muscle is part of lean mass.</p>`;
  }
  function inspectBody(index){const rows=bodyRows();if(!Number.isInteger(index)||!rows[index])return;bodyDate=rows[index];const data=sample('body',bodyDate),unit=bodyFields[bodyMetric][1],delta=data[bodyMetric]-sample('body',rows[0])[bodyMetric],point=bodyPlot(rows)[index];q('[data-next-body]').setAttribute('aria-label',`${bodyFields[bodyMetric][0]}, ${data[bodyMetric].toFixed(1)} ${unit}. Tap to explore the next body measurement`);q('#body-value').innerHTML=HeroDots.markup(data[bodyMetric].toFixed(1))+` <small>${unit}</small>`;q('#body-ring').innerHTML=bodyRing(data);q('#body-share').textContent=bodyMetric==='weight'?'Lean mass + fat mass':Math.round((bodyMetric==='fat'?data.fat/100:data[bodyMetric]/data.weight)*100)+'% of body weight';q('#body-change').innerHTML=`${delta>0?'+':''}${delta.toFixed(1)} ${unit} <span>since ${stamp(rows[0]+'T12:00:00')}</span>`;q('#body-date').textContent=stamp(bodyDate+'T12:00:00',true);q('#body-scrub').value=index;q('#body-scrub').setAttribute('aria-valuetext',`${stamp(bodyDate+'T12:00:00')}: ${data[bodyMetric].toFixed(1)} ${unit}`);q('#body-point').setAttribute('cx',point.x);q('#body-point').setAttribute('cy',point.y);q('#body-guide').setAttribute('d',`M${point.x} 10V107`);q('[data-body-step="-1"]').disabled=index===0;q('[data-body-step="1"]').disabled=index===rows.length-1;document.querySelectorAll('[data-body-value]').forEach(n=>n.textContent=data[n.dataset.bodyValue].toFixed(1)+' kg')}
  function workoutHome(){
    const recent=store.history[0],bins=Array.from({length:7},(_,i)=>{const date=new Date(now());date.setHours(0,0,0,0);date.setDate(date.getDate()-6+i);const next=new Date(date);next.setDate(next.getDate()+1);const records=store.history.filter(r=>r.endedAt>=date.getTime()&&r.endedAt<next.getTime());return {date,records,minutes:records.reduce((n,r)=>n+r.elapsed,0)/60000}}),count=bins.reduce((n,b)=>n+b.records.length,0),mins=Math.round(bins.reduce((n,b)=>n+b.minutes,0)),peak=Math.max(1,...bins.map(b=>b.minutes));
    return `<section class="workout-intro"><h2>Choose an activity</h2></section><div class="workout-kinds">${kinds.map(kind=>`<button data-setup="${kind}" ${storageFault?'disabled':''}>${activityIcon(kind)}<span>${kind}</span><small>↗</small></button>`).join('')}</div>${storageFault?'<p class="health-error">Saved workouts could not be read. Recording is disabled to preserve them.</p>':''}<section class="health-tile workout-week"><div class="health-section-head"><h2>Last 7 days</h2><span>${count} sessions</span></div><p class="tile-value">${mins}<small>min</small></p><div class="week-bars" role="img" aria-label="${bins.map(b=>stamp(b.date)+': '+Math.round(b.minutes)+' minutes').join(', ')}">${bins.map(b=>`<span><i style="height:${Math.max(3,b.minutes/peak*64)}px;opacity:${b.minutes?1:.25}"></i><small>${b.date.toLocaleDateString('en-GB',{weekday:'narrow'})}</small></span>`).join('')}</div></section><details class="health-tile workout-history"><summary><span><h2>${recent?'Recent workout':'Your workouts'}</h2><small>${recent?recent.kind+' · '+stamp(recent.endedAt):'Completed sessions appear here'}</small></span><strong>${recent?clock(recent.elapsed):'—'}</strong><span>⌄</span></summary><div class="details-body">${store.history.length?'<ol>'+store.history.map(r=>`<li><button data-workout-detail="${r.startedAt}"><span>${r.kind}<small>${stamp(r.endedAt,true)}${r.targetMs?' · '+r.targetMs/60000+' min target':''}</small></span><strong>${clock(r.elapsed)} <small>↗</small></strong></button></li>`).join(''):'<p class="health-note">Choose an activity to get started.</p>'}</div></details>`;
  }
  function setupView(){return `<section class="workout-setup"><div class="workout-art">${activityIcon(setup.kind)}</div><h2>${setup.kind}</h2><form id="workout-setup-form"><fieldset><legend>Workout target</legend><label><input type="radio" name="target" value="open" ${setup.targetMs?'':'checked'}/> Open workout</label><label><input type="radio" name="target" value="time" ${setup.targetMs?'checked':''}/> Time target</label></fieldset><div id="target-options" class="target-options" ${setup.targetMs?'':'hidden'}><label class="target-minutes">Minutes<input id="target-minutes" ${setup.targetMs?'':'disabled'} type="number" min="1" max="1440" step="1" value="${setup.targetMs?setup.targetMs/60000:30}" inputmode="numeric"/></label></div>${setup.kind!=='Strength'?`<label class="tracking-option"><span>Track outdoors<small>Phone GPS · route, distance and pace</small></span><input id="workout-track" type="checkbox" ${setup.trackLocation?'checked':''}/></label>`:''}<label class="workout-weight"><span>Weight <small>optional · for energy estimate</small></span><span><input id="workout-weight" type="number" min="20" max="350" step="0.1" value="${setup.weightKg||''}" inputmode="decimal" placeholder="—"/> kg</span></label><p class="health-note">${setup.kind==='Strength'?'Records active time, pauses and your session history.':'GPS records only during this workout. Allow precise location when Android asks, or keep it off for an indoor session.'}</p><p class="health-error" id="workout-error" role="alert"></p><button class="workout-primary" type="submit">Start ${setup.kind.toLowerCase()} <span>→</span></button></form>${window.OrbitMusic?'<button class="music-access" data-music-connect>Music access <span>↗</span></button>':''}</section>`}
  function sessionView(){
    const a=store.active,t=elapsed(a),paused=a.resumedAt===null;
    if(!a.targetMs)timerMode='elapsed';
    return `<section class="workout-live ${timerFocused?'timer-focused':''} ${paused?'is-paused':''}"><div class="workout-live-top"><button class="workout-dot-play" data-dot-play aria-label="Change dot pattern">${HeroDots.pattern(dotPattern)}</button><p class="health-eyebrow" id="session-status">${paused?'Paused':'Recording'}</p></div><div class="workout-time-focus"><button class="timer-dial" data-timer-focus aria-pressed="${timerFocused}" aria-label="${timerFocused?'Show workout details':'Focus on timer and music'}"><svg class="timer-orbit" viewBox="0 0 320 300" aria-hidden="true">${Array.from({length:60},(_,i)=>{const a=(i/60*300-240)*Math.PI/180;return `<circle data-timer-dot="${i}" cx="${160+Math.cos(a)*143}" cy="${150+Math.sin(a)*132}" r="${i%5===0?2.5:1.7}"/>`}).join('')}</svg><span class="timer-center"><span id="timer-label">${timerMode==='remaining'?'Remaining':'Duration'}</span><output class="session-time" id="session-time" tabindex="-1" aria-label="Workout ${timerMode} time"><canvas id="focus-time-dots" aria-hidden="true"></canvas>${HeroDots.markup(clock(timerMode==='remaining'?Math.max(0,a.targetMs-t):t))}</output><span class="timer-tap-hint">${timerFocused?'Tap to show details':'Tap to focus'}</span></span></button>${a.targetMs?`<div class="timer-mode-picker" role="group" aria-label="Timer reading"><button data-timer-mode="elapsed" aria-pressed="${timerMode==='elapsed'}">Elapsed</button><button data-timer-mode="remaining" aria-pressed="${timerMode==='remaining'}">Remaining</button></div><progress class="sr-only" id="session-progress" value="${Math.min(t,a.targetMs)}" max="${a.targetMs}" aria-label="Workout target progress"></progress><p class="target-remaining" id="target-remaining">${t>=a.targetMs?'Target reached':clock(a.targetMs-t)+' remaining'} · ${a.targetMs/60000} min target</p>`:'<p class="target-remaining">Open workout</p>'}</div><section class="workout-music" aria-label="Music player" ${timerFocused?'':'hidden'}></section><div class="workout-unconnected" id="live-workout-metrics">${WorkoutDetails.compact(a,t)}</div><div class="session-actions"><button data-session="${paused?'resume':'pause'}">${paused?'Resume':'Pause'}</button><button data-session="finish">Finish</button></div>${window.OrbitWorkouts?.notificationStatus&&window.OrbitWorkouts.notificationStatus()==='disabled'?'<button class="notification-enable" data-notifications>Enable live notification</button>':''}</section>`;
  }
  function record(){return workoutRecord==='active'?store.active:store.history.find(s=>s.startedAt===workoutRecord)}
  function recordView(){const s=record();return s?`<div id="workout-record-body">${WorkoutDetails.view(s,elapsed(s),total(s),workoutChart)}</div>`:''}
  function paintMetrics(){
    if(page!=='workouts')return;
    if(workoutRecord!==null&&record()){const focused=document.activeElement?.dataset?.workoutChart;q('#workout-record-body').innerHTML=WorkoutDetails.view(record(),elapsed(record()),total(record()),workoutChart);if(focused)q('[data-workout-chart="'+focused+'"]').focus({preventScroll:true})}
    else if(store.active&&q('#live-workout-metrics')){const root=q('#live-workout-metrics'),focused=root.contains(document.activeElement)?document.activeElement:null,selector=focused?.hasAttribute('data-tracking')?'[data-tracking]':focused?.hasAttribute('data-workout-detail')?'[data-workout-detail]':null;root.innerHTML=WorkoutDetails.compact(store.active,elapsed(store.active));if(selector)(root.querySelector(selector)||root.querySelector('[data-workout-detail]')).focus({preventScroll:true})}
  }
  function render(){
    if(!page)return;
    const body=q('#health-content'),scroll=q('#health-scroll').scrollTop,focus=page==='workouts'&&workoutRecord===null&&(store.active||setup||countdown!==null);
    q('#health-title').textContent=page==='workouts'?(record()?.kind||store.active?.kind||setup?.kind||titles[page]):titles[page];
    q('#health-back').setAttribute('aria-label',workoutRecord!==null?'Back to workouts':countdown!==null?'Cancel countdown':setup?'Back to workouts':page==='sleep'?'Back to sleep summary':'Back to dashboard');
    q('#health-source').textContent=page==='workouts'?'':page==='sleep'?'Demo night · stage timing illustration':'Demo data · '+stamp(options.getDate()+'T12:00:00',true);
    q('#health-source').hidden=page==='workouts';q('#health-page').classList.toggle('workout-focus',Boolean(focus));
    countDots?.stop();countDots=null;WorkoutFocus.dispose();MusicPlayer.stop();
    body.innerHTML=page==='body'?bodyView():page==='overview'?overview():page==='sleep'?sleepView():workoutRecord!==null?recordView():countdown!==null?`<div class="workout-countdown"><p class="countdown-heading">${setup.kind}</p><div class="countdown-play"><canvas id="countdown-dots" aria-hidden="true"></canvas><output class="sr-only" id="workout-count" aria-live="polite">${countdown}</output></div><p class="countdown-invitation">Touch the dots</p><div class="countdown-stages" aria-hidden="true">${[3,2,1].map(n=>`<i data-count-stage="${n}" class="${countdown<=n?'lit':''}"></i>`).join('')}</div><button data-cancel-countdown>Cancel</button></div>`:store.active?sessionView():setup?setupView():workoutHome();
    if(page==='workouts'&&countdown!==null)countDots=HeroDots.mount(q('#countdown-dots'),countdown);
    if(page==='workouts'&&store.active&&workoutRecord===null){paintTimer();if(timerFocused){WorkoutFocus.attach(q('.workout-live'),true);MusicPlayer.mount(q('.workout-music'))}}
    q('#health-scroll').scrollTop=scroll;
  }
  function cancelCountdown(){clearInterval(countTimer);countTimer=0;countdown=null;countDots?.stop();countDots=null}
  function startCountdown(kind,targetMs,recording={}){
    if(store.active||!kinds.includes(kind)||!validTarget(targetMs)||!WorkoutDetails.valid(recording)||recording.trackLocation&&kind==='Strength')return false;
    cancelCountdown();setup={...recording,kind,targetMs};timerMode='elapsed';timerFocused=false;SurfaceMotion.change(()=>{countdown=3;render()});
    countTimer=setInterval(()=>{
      countdown--;
      if(countdown<=0)SurfaceMotion.change(()=>{cancelCountdown();if(action('start',kind,targetMs,recording)){setup=null;render();q('#session-time').focus({preventScroll:true});return true}render();return false});
      else{q('#workout-count').textContent=countdown;countDots?.set(countdown);document.querySelectorAll('[data-count-stage]').forEach(n=>n.classList.toggle('lit',countdown<=Number(n.dataset.countStage)))}
    },1000);return true;
  }
  function open(which,source=q('#live-bar'),stage){
    if(!Object.hasOwn(titles,which))return false;
    const enter=()=>{
      if(!page)returnFocus=source||document.activeElement;page=which;setup=null;workoutRecord=null;cancelCountdown();options.beforeOpen();q('#health-page').hidden=false;q('.screen').classList.add('is-detail');
      if(which==='sleep'){sleepDate=options.getDate();const night=options.getDaily(sleepDate).night;sleepMinute=SleepTimeline.valid(night)?SleepTimeline.stageMinute(night,stage):0}
      for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island','#live-island'])q(selector).inert=true;
      render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true});q('#health-page').setAttribute('aria-label',titles[which]);return true;
    };
    if(page)return SurfaceMotion.change(enter);
    enter();SurfaceMotion.reveal(q('#health-page'));return true;
  }
  function close(){
    if(!page)return false;
    if(workoutRecord!==null){SurfaceMotion.change(()=>{workoutRecord=null;render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true})});return true}
    if(countdown!==null){SurfaceMotion.change(()=>{cancelCountdown();render();q('.workout-primary').focus({preventScroll:true})});return true}
    if(setup){const kind=setup.kind;SurfaceMotion.change(()=>{setup=null;render();q('#health-scroll').scrollTop=0;q('[data-setup="'+kind+'"]').focus({preventScroll:true})});return true}
    MusicPlayer.stop();WorkoutFocus.dispose();page=null;SurfaceMotion.dismiss(q('#health-page'),()=>{
      q('#health-page').hidden=true;q('.screen').classList.remove('is-detail');
      for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island','#live-island'])q(selector).inert=false;
      options.onClose();if(returnFocus?.isConnected&&!returnFocus.closest('[inert]'))returnFocus.focus({preventScroll:true});else q('#live-bar').focus({preventScroll:true});
    });return true;
  }
  function refresh(){const before=JSON.stringify(store),structure=JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length]);if(window.OrbitWorkouts)readStore();if(before!==JSON.stringify(store)){if(structure!==JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length])){if(workoutRecord==='active'&&!store.active)workoutRecord=store.history[0]?.startedAt??null;render();schedule()}else paintMetrics();options.onChange()}}
  function paintTimer(){if(!store.active||!q('#session-time'))return;const t=elapsed(store.active),target=store.active.targetMs||0,reading=clock(timerMode==='remaining'?Math.max(0,target-t):t),ratio=target?Math.min(1,t/target):t%60000/60000;if(q('#session-time').dataset.reading!==reading){const output=q('#session-time');let svg=output.querySelector('.matrix-reading');if(svg)svg.outerHTML=HeroDots.markup(reading);else output.insertAdjacentHTML('beforeend',HeroDots.markup(reading));WorkoutFocus.reading(reading);q('#session-time').dataset.reading=reading;q('#session-time').setAttribute('aria-label',`${timerMode==='remaining'?'Remaining':'Duration'}: ${reading}`)}document.querySelectorAll('[data-timer-dot]').forEach(n=>n.classList.toggle('lit',Number(n.dataset.timerDot)/60<ratio));if(target){q('#session-progress').value=Math.min(t,target);q('#target-remaining').textContent=(t>=target?'Target reached':clock(Math.max(0,target-t))+' remaining')+' · '+target/60000+' min target'}}
  function tick(){refresh();if(page==='workouts'){paintTimer();if(workoutRecord!==null&&record())WorkoutDetails.updateTimes(q('#workout-record-body'),record(),elapsed(record()),total(record()));else if(store.active&&q('#live-workout-metrics'))WorkoutDetails.updateCompact(q('#live-workout-metrics'),store.active,elapsed(store.active))}options.onTick()}
  function schedule(){clearInterval(timer);timer=0;if(store.active&&!document.hidden)timer=setInterval(tick,1000)}
  function init(config){
    options=config;MusicPlayer.init(config.notice);readStore();q('#health-back').addEventListener('click',close);
    q('#health-content').addEventListener('click',event=>{
      const summary=event.target.closest('summary');if(summary){event.preventDefault();SurfaceMotion.toggleDetails(summary.closest('details'));if(summary.closest('.oxygen-tile'))q('.oxygen-week').scrollLeft=q('.oxygen-week').scrollWidth;return}
      const b=event.target.closest('button');if(!b)return;
      if(b.dataset.session){
        const finish=b.dataset.session==='finish';
        const saved=finish?SurfaceMotion.change(()=>{const saved=action('finish');if(saved){workoutRecord=store.history[0].startedAt;workoutChart='route';render();q('#health-scroll').scrollTop=0}return saved}):action(b.dataset.session);
        if(saved){if(finish){options.notice('Workout saved');q('#health-back').focus({preventScroll:true})}else q('#health-content [data-session="'+(store.active.resumedAt===null?'resume':'pause')+'"]').focus({preventScroll:true})}
      }
      else if(b.dataset.setup)SurfaceMotion.change(()=>{setup={kind:b.dataset.setup,targetMs:0,trackLocation:b.dataset.setup!=='Strength',weightKg:0};render();q('#health-scroll').scrollTop=0;q('input[name="target"]:checked').focus({preventScroll:true})});
      else if(b.dataset.workoutDetail){workoutRecord=b.dataset.workoutDetail==='active'?'active':Number(b.dataset.workoutDetail);if(!record()){workoutRecord=null;return}workoutChart='route';SurfaceMotion.change(()=>{render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true})})}
      else if(b.dataset.workoutChart){workoutChart=b.dataset.workoutChart;if(['route','speed','altitude'].includes(workoutChart)){const s=record();q('.workout-route-group').innerHTML=WorkoutDetails.chart(s,elapsed(s),workoutChart);q('[data-workout-chart="'+workoutChart+'"]').focus({preventScroll:true})}}
      else if(b.hasAttribute('data-music-connect'))MusicPlayer.connect();
      else if(b.hasAttribute('data-tracking'))window.OrbitWorkouts?.enableTracking();
      else if(b.dataset.bodyMetric||b.dataset.selectBody||b.hasAttribute('data-next-body')){const fields=Object.keys(bodyFields);bodyMetric=b.dataset.bodyMetric||b.dataset.selectBody||fields[(fields.indexOf(bodyMetric)+1)%fields.length];const centre=b.hasAttribute('data-next-body');render();q(centre?'[data-next-body]':'[data-body-metric="'+bodyMetric+'"]').focus({preventScroll:true})}
      else if(b.dataset.bodyRange){bodyRange=Number(b.dataset.bodyRange);render();q('[data-body-range="'+bodyRange+'"]').focus({preventScroll:true})}
      else if(b.dataset.bodyStep)inspectBody(bodyRows().indexOf(bodyDate)+Number(b.dataset.bodyStep));
      else if(b.dataset.nightDate){const date=options.dates[options.dates.indexOf(sleepDate)+Number(b.dataset.nightDate)];if(date){sleepDate=date;sleepMinute=0;render();q('[data-night-date="'+b.dataset.nightDate+'"]').focus({preventScroll:true})}}
      else if(b.dataset.nightPart){const night=options.getDaily(sleepDate).night;sleepMinute=SleepTimeline.inspect(night,SleepTimeline.part(night,sleepMinute,Number(b.dataset.nightPart)))}
      else if(b.dataset.nightStage){const night=options.getDaily(sleepDate).night;sleepMinute=SleepTimeline.inspect(night,SleepTimeline.stageMinute(night,b.dataset.nightStage))}
      else if(b.dataset.timerMode){timerMode=b.dataset.timerMode;render();q('[data-timer-mode="'+timerMode+'"]').focus({preventScroll:true})}
      else if(b.hasAttribute('data-timer-focus')){timerFocused=!timerFocused;WorkoutFocus.toggle(q('.workout-live'),timerFocused)}
      else if(b.hasAttribute('data-dot-play')){dotPattern=(dotPattern+1)%3;HeroDots.reshape(b,dotPattern)}
      else if(b.dataset.oxygenDay){const date=b.dataset.oxygenDay;if(options.dates.includes(date)){q('#oxygen-date').textContent=stamp(date+'T12:00:00')+' · '+sample('oxygen',date).value+'%';document.querySelectorAll('[data-oxygen-day]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.oxygenDay===date)))}}
      else if(b.dataset.open)open(b.dataset.open,b);
      else if(b.hasAttribute('data-cancel-countdown'))close();
      else if(b.hasAttribute('data-notifications'))window.OrbitWorkouts?.enableNotifications();
    });
    q('#health-content').addEventListener('change',event=>{if(event.target.name==='target'){const open=event.target.value==='time';q('#target-minutes').disabled=!open;SurfaceMotion.expand(q('#target-options'),open)}});
    q('#health-content').addEventListener('input',event=>{if(event.target.id==='body-scrub')inspectBody(Number(event.target.value));if(event.target.id==='night-scrub')sleepMinute=SleepTimeline.inspect(options.getDaily(sleepDate).night,Number(event.target.value))});
    q('#health-content').addEventListener('submit',event=>{if(event.target.id!=='workout-setup-form')return;event.preventDefault();const time=q('input[name="target"]:checked').value==='time',minutes=Number(q('#target-minutes').value);if(time&&(!Number.isInteger(minutes)||minutes<1||minutes>1440)){q('#workout-error').textContent='Choose between 1 and 1,440 minutes.';return}const weightKg=Number(q('#workout-weight').value||0),trackLocation=Boolean(q('#workout-track')?.checked);if(weightKg!==0&&(!Number.isFinite(weightKg)||weightKg<20||weightKg>350)){q('#workout-error').textContent='Enter a weight from 20 to 350 kg, or leave it empty.';return}startCountdown(setup.kind,time?minutes*60000:0,{trackLocation,weightKg})});
    document.addEventListener('visibilitychange',()=>{if(document.hidden&&countdown!==null){cancelCountdown();render()}schedule();if(!document.hidden)tick()});
    window.addEventListener('focus',()=>{refresh();if(page)render()});schedule();
  }
  return {init,open,close,render,live,action,sample,elapsed,clock,validSession,validTarget,refresh,startCountdown,get page(){return page},get state(){return store}};
})();
