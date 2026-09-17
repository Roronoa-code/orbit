/* Visual health summaries and app-owned workouts. Shared Samsung measurements retain their source. */
'use strict';
const Health = (() => {
  const titles={body:'Body',workouts:'Workouts',overview:'Health',sleep:'Sleep',settings:'Settings'},kinds=['Walking','Running','Cycling','Strength'];
  const key='orbit-workouts-v2',q=s=>document.querySelector(s);
  let page=null,store={active:null,history:[]},storageFault=false,options,timer=0,returnFocus,pageTrail=[],metricReturn=null;
  let bodyMetric='weight',bodyRange=30,bodyDate=null,setup=null,countdown=null,countTimer=0,countDots=null;
  let timerMode='elapsed',timerFocused=false,dotPattern=0,historyDate=null,workoutTab='train';
  let sleepDate=null,sleepMinute=0,workoutRecord=null,workoutChart='route',workoutReturn=null;
  const now=()=>performance.timeOrigin+performance.now();
  const stamp=(date,full=false)=>new Date(date).toLocaleDateString('en-GB',full?{day:'numeric',month:'short',year:'numeric'}:{day:'numeric',month:'short'});
  let nativeClock=null,nativeRevision='',lastRaw;
  const sampledTime=(session,key)=>nativeClock&&nativeClock.startedAt===session?.startedAt?nativeClock[key]+(key==='totalMs'||session.resumedAt!==null?Math.max(0,performance.now()-nativeClock.at-nativeClock.startsInMs):0):null;
  const elapsed=(session,time=now())=>{if(session===store.active){const value=sampledTime(session,'elapsedMs');if(value!==null)return value}return session?Math.max(0,session.elapsed+(session.resumedAt==null?0:Math.max(0,time-session.resumedAt))):0};
  const total=session=>{if(session===store.active){const value=sampledTime(session,'totalMs');if(value!==null)return Math.max(elapsed(session),value)}return Math.max(elapsed(session),session.totalMs??((session.endedAt??now())-session.startedAt))};
  const clock=ms=>{const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s/60)%60;return (h?h+':':'')+String(m).padStart(2,'0')+':'+String(s%60).padStart(2,'0')};
  const validNumber=n=>Number.isFinite(n)&&n>=0;
  const validTarget=n=>n===undefined||n===0||Number.isInteger(n)&&n>=60000&&n<=86400000;
  function validSession(s,active){return s&&WorkoutDetails.valid(s)&&kinds.includes(s.kind)&&validNumber(s.startedAt)&&validNumber(s.elapsed)&&validTarget(s.targetMs)&&(active?(s.resumedAt===null||validNumber(s.resumedAt)):validNumber(s.endedAt))}
  function decode(raw){const v=JSON.parse(raw);if(!v||!Array.isArray(v.history)||(v.active!==null&&!validSession(v.active,true))||!v.history.every(row=>validSession(row,false)))throw Error('Invalid workout history');return v}
  function readStore(){
    try{
      const native=window.OrbitWorkouts;
      if(native?.snapshot){
        let sample=JSON.parse(native.snapshot(nativeRevision));
        if(sample.realDataMode!==true)throw Error('Demo cleanup not saved');if(sample.empty){const legacy=localStorage.getItem(key);if(legacy!==null){decode(legacy);if(!native.write(legacy))throw Error('Migration not saved');sample=JSON.parse(native.snapshot(''))}}
        if(sample.error||typeof sample.revision!=='string'||!validNumber(sample.elapsedMs)||!validNumber(sample.totalMs)||sample.startedAt!==null&&!validNumber(sample.startedAt))throw Error('Invalid clock sample');
        const value=sample.store===null?store:decode(sample.store);
        if(sample.startedAt!==(value.active?.startedAt??null))throw Error('Clock belongs to another workout');
        store=value;nativeClock={startedAt:sample.startedAt,elapsedMs:sample.elapsedMs,totalMs:sample.totalMs,startsInMs:sample.startsInMs||0,at:performance.now()};nativeRevision=sample.revision;storageFault=false;return;
      }
      const nativeRaw=native?native.read():null,raw=nativeRaw??localStorage.getItem(key);
      if(raw===null){storageFault=false;return}if(raw!==lastRaw){const value=decode(raw);if(native&&nativeRaw===null&&!native.write(raw))throw Error('Migration not saved');store=value;lastRaw=raw}storageFault=false;
    }catch{storageFault=true}
  }
  function changed(){if(!paintLive())render();options.onChange();schedule()}
  function commit(value){
    try{const encoded=JSON.stringify(value),native=window.OrbitWorkouts;if(native){if(!native.write(encoded)||native.read()!==encoded)throw Error('Save not confirmed')}else{localStorage.setItem(key,encoded);if(localStorage.getItem(key)!==encoded)throw Error('Save not confirmed')}}
    catch{options.notice('Could not save. The previous workout state is unchanged.');return false}
    store=value;OrbitInteraction.haptic('select');changed();return true;
  }
  function action(name,kind,targetMs=0,recording={}){
    if(storageFault){options.notice('Saved workouts could not be read. Your data has been preserved.');return false}
    if(['start','countdown'].includes(name)&&(!kinds.includes(kind)||!validTarget(targetMs)||!WorkoutDetails.valid(recording)||recording.trackLocation&&kind==='Strength'))return false;
    const native=window.OrbitWorkouts;
    if(native?.action){try{const raw=name==='countdown'&&native.startCountdown?native.startCountdown(kind,targetMs,Boolean(recording.trackLocation),recording.weightKg||0):name==='start'&&native.start?native.start(kind,targetMs,Boolean(recording.trackLocation),recording.weightKg||0):native.action(name,kind||'',targetMs);if(raw===null)throw Error('Save failed');store=decode(raw);nativeRevision='';nativeClock=null;readStore();OrbitInteraction.haptic(name==='finish'?'confirm':'select');changed();return true}catch{options.notice('Could not update the workout. Open it and try again.');return false}}
    const active=store.active,time=now()+(name==='countdown'?3000:0);
    if(name==='start'||name==='countdown'){if(active)return false;return commit({...store,active:{kind,startedAt:time,elapsed:0,resumedAt:time,targetMs,weightKg:recording.weightKg||0,trackLocation:Boolean(recording.trackLocation),...(recording.trackLocation?{metrics:{state:'unavailable',distanceM:0,maxSpeedMps:0,speedMps:null,altitudeMinM:null,altitudeMaxM:null,accuracyM:null,points:[]}}:{})}})}
    if(!active)return false;
    if(name==='pause'&&active.resumedAt!==null)return commit({...store,active:{...active,elapsed:elapsed(active,time),resumedAt:null}});
    if(name==='resume'&&active.resumedAt===null)return commit({...store,active:{...active,resumedAt:time}});
    if(name==='finish')return commit({active:null,history:[{...active,resumedAt:undefined,endedAt:time,elapsed:elapsed(active,time),totalMs:total(active),targetMs:active.targetMs||0},...store.history]});
    return false;
  }
  function live(){const a=store.active;return a?{title:a.kind,elapsed:clock(elapsed(a)),paused:a.resumedAt===null,targetMs:a.targetMs||0,remaining:clock(Math.max(0,(a.targetMs||0)-elapsed(a)))}:null}
  // Weight first, then its parts from smallest to largest. Fat is tracked in kg; its share of weight is shown alongside.
  const bodyFields={weight:['Weight','kg'],fatMass:['Fat','kg'],muscle:['Muscle','kg'],lean:['Lean mass','kg']};
  function sample(which,date){return which==='body'?HealthData.body(date):HealthData.daily(date).oxygen}
  const activityIcon=kind=>kinds.includes(kind)?`<span class="workout-glyph" data-kind="${HealthData.escape(kind.toLowerCase())}" aria-hidden="true"></span>`:'<svg class="workout-glyph" aria-hidden="true"><use href="#target"/></svg>';
  function history(){return [...store.history,...HealthData.workouts].sort((a,b)=>b.startedAt-a.startedAt)}
  function bodyRows(){const end=options.getDate(),start=dayOffset(end,1-bodyRange);return HealthData.bodyDates().filter(date=>date>=start&&date<=end&&Number.isFinite(sample('body',date)[bodyMetric]))}
  function setSleepMinute(value){sleepMinute=SleepTimeline.inspect(options.getDaily(sleepDate).sleepTimeline,value)}
  function sleepView(){const index=options.dates.indexOf(sleepDate),navigation=`<div class="sleep-date-nav"><span>${stamp(sleepDate+'T12:00:00',true)}</span><div><button data-night-date="-1" aria-label="Previous day" ${index<=0?'disabled':''}><svg class="date-arrow date-arrow-back" aria-hidden="true"><use href="#chevron"/></svg></button><button data-night-date="1" aria-label="Next day" ${index>=options.dates.length-1?'disabled':''}><svg class="date-arrow" aria-hidden="true"><use href="#chevron"/></svg></button></div></div>`;return SleepTimeline.view(options.getDaily(sleepDate).sleepTimeline,sleepMinute,navigation)}
  // Each of the ring's 100 dots is 1% of body weight: purple is the selected measurement's share, with Weight filling the whole ring.
  const bodyOrder=Object.keys(bodyFields),bodyTone={purple:[182,156,255],rest:[52,50,60]};
  const bodyShare=(field,d)=>!Number.isFinite(d[field])||!(d.weight>0)?0:Math.max(0,Math.min(1,field==='weight'?1:field==='fatMass'?d.fat/100:d[field]/d.weight));
  const bodyRest=()=>bodyTone.rest;
  const bodyIndex=field=>bodyOrder.indexOf(field),bodyNumber=(field,d)=>HealthData.display(d[field],1),percent=n=>Math.round(n*100)+'%';
  const mixTone=(a,b,t)=>a.map((v,i)=>Math.round(v+(b[i]-v)*t));
  let ringFills=[];
  function bodyCaption(field,d){
    if(!Number.isFinite(d[field]))return 'No shared measurement';
    if(field==='weight')return '<i></i>Total body weight';
    if(!(d.weight>0))return 'Weight not recorded together';
    return `<i></i>${field==='fatMass'?d.fat.toFixed(1)+'%':percent(bodyShare(field,d))} of body weight`;
  }
  function bodyReading(field,date){const d=sample('body',date),[label,unit]=bodyFields[field],latest=date===bodyRows().at(-1);return `<span class="body-measure-name">${label}${latest?'':' · '+stamp(date+'T12:00:00')}</span><span class="body-value">${HeroDots.markup(bodyNumber(field,d))}<small>${unit}</small></span><span class="body-share">${bodyCaption(field,d)}</span>`}
  function bodyRing(){return `<svg viewBox="0 0 300 300" aria-hidden="true"><circle cx="150" cy="150" r="117" fill="none" stroke="#ffffff08"/>${Array.from({length:100},(_,i)=>{const a=(i/100*360-90)*Math.PI/180;return `<circle data-mix-dot="${i}" cx="${(150+Math.cos(a)*134).toFixed(2)}" cy="${(150+Math.sin(a)*134).toFixed(2)}" r="2.6"/>`}).join('')}</svg>`}
  // A fractional share lights the boundary dot partly, so the arc grows smoothly while it moves.
  function paintRing(share,rest){const lit=share*100;document.querySelectorAll('[data-mix-dot]').forEach((dot,i)=>{const fill=`rgb(${mixTone(rest,bodyTone.purple,Math.max(0,Math.min(1,lit-i)))})`;if(ringFills[i]!==fill){ringFills[i]=fill;dot.setAttribute('fill',fill)}})}
  const bodyOffset=date=>Math.round((Date.parse(date+'T12:00:00Z')-Date.parse(dayOffset(options.getDate(),1-bodyRange)+'T12:00:00Z'))/86400000);
  function bodyPlot(rows){
    const values=rows.map(date=>Number(sample('body',date)[bodyMetric].toFixed(1))),min=Math.min(...values),high=Math.max(...values),span=Math.max(.1,high-min),start=bodyOffset(rows[0]),days=bodyOffset(rows.at(-1))-start;
    // Fit the dated readings, not a fabricated full period. The axis and coverage state the actual window.
    return values.map((value,i)=>({x:days?10+(bodyOffset(rows[i])-start)/days*280:150,y:high===min?64:97-(value-min)/span*65}));
  }
  // Every selector on this page is one BlobTrack: it measures its own options, owns the capsule's geometry and
  // reports each option's influence from the capsule actually on screen. The page keeps the committed value.
  const tracks=new Map();let trackCommit=0;
  const influenceLabel=(el,raw,eased)=>{el.style.setProperty('--blob-influence',eased.toFixed(3));el.style.setProperty('--blob-lift',eased.toFixed(3))};
  function bindTrack(key,host,config){
    if(host&&tracks.get(key)?.host===host)return tracks.get(key);
    tracks.get(key)?.destroy();tracks.delete(key);
    if(!host)return null;
    const track=BlobTrack.create(Object.assign({host,influence:influenceLabel,haptics:{tick:()=>OrbitInteraction.haptic('tick'),select:()=>OrbitInteraction.haptic('select')}},config));tracks.set(key,track);return track;
  }
  const releaseTracks=()=>{for(const track of tracks.values())track.destroy();tracks.clear()};
  const remeasureTracks=()=>{for(const track of tracks.values())track.remeasure()};
  // A physical commit already applied the choice; the click it generates must not apply it twice, while a keyboard
  // or assistive activation (which carries no click detail) still goes through the ordinary handler.
  const fromTrack=event=>Boolean(event.detail)&&performance.now()-trackCommit<400;
  function bindTracks(){
    for(const [key,track] of tracks)if(!track.host?.isConnected){track.destroy();tracks.delete(key)}
    bindTrack('workout-tab',q('.workout-tabs'),{optionSelector:'[data-workout-tab]',idOf:el=>el.dataset.workoutTab,
      committed:()=>workoutTab,commit:id=>{trackCommit=performance.now();setWorkoutTab(id)}});
    bindTrack('body-metric',q('.body-metric-picker'),{optionSelector:'[data-body-metric]',idOf:el=>el.dataset.bodyMetric,
      committed:()=>bodyMetric,commit:id=>{trackCommit=performance.now();lens.fromTrack=true;goBody(id);if(!lens.frame)lens.fromTrack=false}});
    bindTrack('body-range',q('.body-timeline .segmented'),{optionSelector:'[data-body-range]',idOf:el=>el.dataset.bodyRange,
      committed:()=>String(bodyRange),commit:id=>{trackCommit=performance.now();setBodyRange(Number(id))}});
    bindTrack('setup-target',q('.setup-segments'),{optionSelector:'label',idOf:el=>el.querySelector('input').value,
      committed:()=>q('.setup-segments input:checked')?.value||'open',commit:id=>{trackCommit=performance.now();setWorkoutTarget(id)}});
    bindTrack('timer-mode',q('.timer-mode-picker'),{optionSelector:'[data-timer-mode]',idOf:el=>el.dataset.timerMode,
      committed:()=>timerMode,commit:id=>{trackCommit=performance.now();setTimerMode(id)}});
    bindTrack('history-day',q('.history-days'),{optionSelector:'[data-week-day]:not(:disabled)',idOf:el=>el.dataset.weekDay,committed:()=>q('[data-week-day][aria-pressed=true]')?.dataset.weekDay,commit:id=>{trackCommit=performance.now();inspectWeek(Number(id))}});
    bindTrack('workout-chart',q('.workout-chart-tabs'),{optionSelector:'[data-workout-chart]',idOf:el=>el.dataset.workoutChart,
      committed:()=>workoutChart,commit:id=>{trackCommit=performance.now();setWorkoutChart(id)}});
  }
  function setBodyRange(next){
    if(![7,30,90,365].includes(next)||next===bodyRange)return;
    bodyRange=next;paintBody(true);
  }
  function setWorkoutTarget(value){
    const input=q('.setup-segments input[value="'+value+'"]');
    if(input&&!input.checked){input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}))}
  }
  function setTimerMode(mode){
    if(!['elapsed','remaining'].includes(mode))return;
    timerMode=mode;document.querySelectorAll('[data-timer-mode]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.timerMode===timerMode)));
    tracks.get('timer-mode')?.sync(timerMode);q('#timer-label').textContent=timerMode==='remaining'?'Remaining':'Duration';paintTimer();
  }
  function setWorkoutChart(mode){
    if(!['route','speed','altitude'].includes(mode)||!record())return;
    workoutChart=mode;const s=record();
    document.querySelectorAll('[data-workout-chart]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.workoutChart===mode)));
    tracks.get('workout-chart')?.sync(mode);
    WorkoutDetails.updateRecord(q('#workout-record-body'),s,elapsed(s),total(s),mode);
  }
  // The ring is a lens over a looping row of measurements. A horizontal drag slides the reading, grows or shrinks the
  // purple arc and carries the selector capsule together; a tap on the centre or on the selector runs the same move.
  // x is in lens widths: 0 rests on `from`, -1 has moved one place on (to the next measurement), +1 one place back.
  // toSlot is where the capsule heads: one past either end of the row when the move loops round.
  const lens={x:{value:0,velocity:0},target:0,from:null,to:null,frame:0,last:0,width:200,drag:null,dragged:0,fromTrack:false};
  const lensLayers=()=>{const root=q('.body-lens');return [root.querySelector('.is-current'),root.querySelector('.is-next')]};
  const bodyNeighbour=(field,dir)=>bodyOrder[(bodyIndex(field)+dir+bodyOrder.length)%bodyOrder.length];
  // The row still loops, but the capsule is one physical object: a wrap travels between the two measured end slots
  // instead of running a second capsule round the back of the bar.
  function placePill(fromIndex,toIndex,t){
    const track=tracks.get('body-metric'),options=track?.options||[];
    // A move that began on the track keeps its own release spring; only a ring swipe or tap drives the capsule.
    if(!track||track.busy||lens.fromTrack||!options.length)return;
    const a=options[BlobTrack.clamp(fromIndex,0,options.length-1)],b=options[BlobTrack.clamp(toIndex,0,options.length-1)];
    track.setPose({cx:a.centre+(b.centre-a.centre)*t,width:a.width+(b.width-a.width)*t});
  }
  function paintLens(){
    const [current,next]=lensLayers(),x=lens.x.value,t=Math.min(1,Math.abs(x)),w=lens.width,side=x<0?1:-1,from=lens.from||bodyMetric,to=lens.to||from,d=sample('body',bodyDate);
    current.style.transform=`translate3d(${(x*w).toFixed(2)}px,0,0) scale(${(1-.05*t).toFixed(4)})`;current.style.opacity=String(1-t*.6);
    next.style.transform=`translate3d(${((x+side)*w).toFixed(2)}px,0,0) scale(${(.95+.05*t).toFixed(4)})`;next.style.opacity=String(lens.to?.4+t*.6:0);
    paintRing(bodyShare(from,d)+(bodyShare(to,d)-bodyShare(from,d))*t,mixTone(bodyRest(from),bodyRest(to),t));
    placePill(bodyIndex(from),bodyIndex(to),t);
  }
  function settleLens(){
    const [current,next]=lensLayers();lens.frame=0;
    if(lens.target&&lens.to)current.innerHTML=next.innerHTML;
    next.innerHTML='';lens.x.value=lens.x.velocity=lens.target=0;lens.from=lens.to=null;
    for(const layer of [current,next]){layer.style.transform='';layer.style.opacity=''}
    q('.body-lens').classList.remove('is-moving');lens.fromTrack=false;paintHero();
  }
  function runLens(){
    cancelAnimationFrame(lens.frame);lens.last=performance.now();
    const step=time=>{
      const dt=Math.min(.034,Math.max(.001,(time-lens.last)/1000));lens.last=time;springStep(lens.x,lens.target,dt,19);
      const done=Math.abs(lens.x.value-lens.target)<.002&&Math.abs(lens.x.velocity)<.02;if(done)lens.x.value=lens.target;
      paintLens();if(done)settleLens();else lens.frame=requestAnimationFrame(step);
    };
    lens.frame=requestAnimationFrame(step);
  }
  // The decision is made at release or tap, so the selector, history and announcement answer at once while the lens settles.
  function aimLens(target){
    lens.target=target;const field=target&&lens.to?lens.to:lens.from||bodyMetric;
    runLens();
    if(field!==bodyMetric){bodyMetric=field;paintBody(false);const d=sample('body',bodyDate);q('#body-announce').textContent=`${bodyFields[field][0]}, ${bodyNumber(field,d)} ${bodyFields[field][1]}`}
  }
  // dir (+1 or -1) steps round the loop as a swipe does; without it a selector tap slides straight to the chosen label.
  function goBody(field,dir=0){
    if(!Object.hasOwn(bodyFields,field)||lens.drag)return;
    if(field!==bodyMetric)OrbitInteraction.haptic('select');
    if(SurfaceMotion.reduced){cancelAnimationFrame(lens.frame);lens.frame=0;lens.from=lens.to=null;lens.target=0;bodyMetric=field;paintBody(false);return}
    const [current,next]=lensLayers();
    if(lens.frame&&lens.to&&Math.abs(lens.x.value)>=.5){current.innerHTML=next.innerHTML;lens.x.value+=lens.x.value<0?1:-1;lens.from=lens.to;lens.to=null}
    else if(!lens.frame)lens.from=bodyMetric;
    if(field===lens.from){if(lens.frame)aimLens(0);return}
    const from=bodyIndex(lens.from);lens.to=field;
    next.innerHTML=bodyReading(field,bodyDate);lens.width=q('.body-lens').clientWidth||200;q('.body-lens').classList.add('is-moving');
    aimLens(dir?-dir:bodyIndex(field)>from?-1:1);
  }
  function lensDown(event){
    lens.dragged=0;const dial=event.target.closest?.('[data-body-dial]');if(!dial||!event.isPrimary||lens.drag)return;
    lens.drag={id:event.pointerId,x:event.clientX,y:event.clientY,start:lens.x.value,active:false,samples:[[event.timeStamp,event.clientX]],dial};
  }
  function lensMove(event){
    const g=lens.drag;if(!g||event.pointerId!==g.id)return;
    if(!g.active){
      const dx=event.clientX-g.x,dy=event.clientY-g.y;
      if(Math.abs(dy)>8&&Math.abs(dy)>Math.abs(dx)){lens.drag=null;return}
      if(Math.abs(dx)<8)return;
      // Start from rest at the edge of the touch slop, and from wherever a settling move currently is.
      g.active=true;g.x+=Math.sign(dx)*8;try{g.dial.setPointerCapture(g.id)}catch{}cancelAnimationFrame(lens.frame);lens.frame=0;
      // A new swipe starts from the last committed measurement, even while its move is settling.
      // Swap the two visible layers and rebase progress without moving either layer on screen.
      if(lens.target&&lens.to){
        const [current,next]=lensLayers(),old=current.innerHTML;current.innerHTML=next.innerHTML;next.innerHTML=old;
        const from=lens.from;lens.from=lens.to;lens.to=from;lens.x.value-=lens.target;lens.target=0;lens.toSlot=null;
      }
      g.start=lens.x.value;
      if(!lens.from)lens.from=bodyMetric;lens.pillFrom=null;lens.width=q('.body-lens').clientWidth||200;q('.body-lens').classList.add('is-moving');
    }
    g.samples.push([event.timeStamp,event.clientX]);if(g.samples.length>5)g.samples.shift();
    const x=Math.max(-1,Math.min(1,g.start+(event.clientX-g.x)/lens.width)),dir=x<0?1:-1,slot=bodyIndex(lens.from)+dir; // the row loops
    if(slot!==lens.toSlot||!lens.to){lens.to=bodyNeighbour(lens.from,dir);lens.toSlot=slot;lensLayers()[1].innerHTML=bodyReading(lens.to,bodyDate)}
    lens.x.value=x;lens.x.velocity=0;paintLens();
  }
  function lensUp(event){
    // Moving the capture from the touched button to the dial reports a lost capture on the button; only the dial's own counts.
    const g=lens.drag;if(!g||event.pointerId!==g.id||event.type==='lostpointercapture'&&event.target!==g.dial)return;lens.drag=null;if(!g.active)return;
    lens.dragged=performance.now();const [t0,x0]=g.samples[0],[t1,x1]=g.samples.at(-1),speed=event.type==='pointerup'&&event.timeStamp-t1<80?(x1-x0)/Math.max(8,t1-t0)*1000/lens.width:0,x=lens.x.value;
    lens.x.velocity=speed;aimLens(lens.to&&(Math.abs(x)>.38||Math.abs(speed)>1.6&&Math.sign(speed)===Math.sign(x))?Math.sign(x):0);
  }
  // The selector is also a drag track: press and slide across the fixed labels and the glass capsule follows the finger
  // on a light spring, stretching slightly with speed; the release picks the label beneath it.
  function paintHero(){
    const d=sample('body',bodyDate),[label,unit]=bodyFields[bodyMetric];
    if(!lens.frame&&!lens.drag){lensLayers()[0].innerHTML=bodyReading(bodyMetric,bodyDate);paintRing(bodyShare(bodyMetric,d),bodyRest(bodyMetric));tracks.get('body-metric')?.sync(bodyMetric)}
    document.querySelectorAll('[data-body-metric]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.bodyMetric===bodyMetric)));
    if(!lens.frame)tracks.get('body-metric')?.sync(bodyMetric);
    q('[data-next-body]').setAttribute('aria-label',`${label}, ${bodyNumber(bodyMetric,d)} ${unit}. Tap for the next measurement, or swipe left or right`);
  }
  function bodyView(){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1)||options.getDate();ringFills=[];cancelAnimationFrame(lens.frame);Object.assign(lens,{frame:0,drag:null,from:null,to:null,target:0,x:{value:0,velocity:0}});
    return `<section class="body-hero"><div class="body-composition" data-body-dial><span id="body-ring" aria-hidden="true">${bodyRing()}</span><span class="body-lens" aria-hidden="true"><span class="body-reading is-current"></span><span class="body-reading is-next"></span></span><button class="body-centre" data-next-body></button></div><span class="sr-only" id="body-announce" aria-live="polite"></span>
    <div class="body-metric-picker glass-track blob-track" role="group" aria-label="Body measurement"><span class="selection-pill glass-indicator" aria-hidden="true"></span>${bodyOrder.map(field=>`<button class="blob-option" data-body-metric="${field}" aria-pressed="${field===bodyMetric}">${bodyFields[field][2]||bodyFields[field][0]}</button>`).join('')}</div></section>
    <section class="body-timeline"><div class="health-section-head"><h2 id="body-trend-title">Weight over time</h2><span id="body-record-count"></span></div><div class="body-period segmented blob-track" role="group" aria-label="Body history period"><span class="selection-pill" aria-hidden="true"></span>${[[7,'7D'],[30,'30D'],[90,'3M'],[365,'1Y']].map(([n,name])=>`<button class="blob-option" data-body-range="${n}" aria-pressed="${bodyRange===n}" aria-label="${n===365?'Last year':n===90?'Last three months':'Last '+n+' days'}">${name}</button>`).join('')}</div>
    <div class="body-readout"><div class="body-readout-text"><p class="body-readout-value"><span id="body-reading"></span><small id="body-unit"></small></p><p class="body-readout-share" id="body-fat-share"></p><p class="body-readout-meta"><output id="body-date"></output></p></div><p id="body-change"></p></div>
    <figure class="body-chart"><svg viewBox="0 0 300 120" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="body-area-fill" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#b69cff" stop-opacity=".24"/><stop offset="1" stop-color="#b69cff" stop-opacity="0"/></linearGradient></defs><path id="body-area" fill="url(#body-area-fill)"/><g id="body-dots" fill="#cdb9ff" fill-opacity=".5"></g><path id="body-line" stroke="#c3a9f7" stroke-width="2" stroke-linecap="round" fill="none"/><text id="body-gap" x="18" y="66" fill="#aeadb9" font-size="9"></text><circle id="body-point" r="4" fill="#efe7ff" stroke="#3c304f" stroke-width="2"/></svg><input id="body-scrub" type="range" min="0" max="${bodyRange-1}" value="${bodyOffset(bodyDate)}" aria-label="Inspect body measurement date"/><figcaption><span id="body-range-start"></span><span id="body-range-middle"></span><span id="body-range-end"></span></figcaption></figure>
    <dl class="body-stats"><div><dt>Average</dt><dd id="body-average"></dd></div><div><dt>Lowest</dt><dd id="body-low"></dd></div><div><dt>Highest</dt><dd id="body-high"></dd></div></dl><p class="body-coverage" id="body-coverage"></p></section><details class="reading-notes"><summary>About these measurements</summary><div class="details-body"><p>Samsung Health supplies these readings. Fat mass uses weight and body fat recorded together. Lean mass is a shared measurement. Muscle remains empty when Samsung does not share it.</p><button class="source-link" data-open="settings">Connection settings<span aria-hidden="true">›</span></button></div></details>`;
  }
  function inspectBody(index){
    const rows=bodyRows();if(!Number.isInteger(index)||!rows[index])return;bodyDate=rows[index];
    const d=sample('body',bodyDate),unit=bodyFields[bodyMetric][1],change=Math.round((d[bodyMetric]-sample('body',rows[0])[bodyMetric])*10)/10,point=bodyPlot(rows)[index];
    q('#body-reading').textContent=bodyNumber(bodyMetric,d);q('#body-unit').textContent=unit;q('#body-date').textContent=stamp(bodyDate+'T12:00:00',true);q('#body-fat-share').textContent=bodyMetric==='fatMass'?d.fat.toFixed(1)+'% of body weight':'';
    q('#body-change').textContent=index?`${change===0?'No change':(change>0?'+':'−')+Math.abs(change).toFixed(1)+' '+unit}\nsince ${stamp(rows[0]+'T12:00:00')}`:'';
    q('#body-scrub').value=bodyOffset(bodyDate);q('#body-scrub').setAttribute('aria-valuetext',`${stamp(bodyDate+'T12:00:00')}: ${bodyNumber(bodyMetric,d)} ${unit}`);
    q('#body-point').setAttribute('cx',point.x);q('#body-point').setAttribute('cy',point.y);
    paintHero();
  }
  function paintBody(animate=false){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1)||options.getDate();
    q('#body-trend-title').textContent=bodyFields[bodyMetric][0]+' over time';q('#body-record-count').textContent=rows.length+' '+(rows.length===1?'reading':'readings');
    q('.body-timeline').classList.toggle('is-sparse',rows.length<2);
    const rounded=rows.map(date=>bodyNumber(bodyMetric,sample('body',date)));q('.body-timeline').classList.toggle('has-variation',rows.length>2&&new Set(rounded).size>1);
    if(!rows.length){
      for(const id of ['#body-line','#body-area'])q(id).setAttribute('d','');q('#body-dots').innerHTML='';
      for(const id of ['#body-point'])q(id).style.display='none';
      q('#body-gap').textContent='No shared measurements in this range';q('#body-reading').textContent='—';q('#body-unit').textContent=bodyFields[bodyMetric][1];
      for(const id of ['#body-date','#body-change','#body-fat-share','#body-range-middle'])q(id).textContent='';
      for(const id of ['#body-average','#body-low','#body-high'])q(id).textContent='—';
      q('#body-range-start').textContent=stamp(dayOffset(options.getDate(),1-bodyRange)+'T12:00:00');q('#body-range-end').textContent=stamp(options.getDate()+'T12:00:00');q('#body-coverage').textContent=HealthData.sourceText();q('#body-scrub').disabled=true;
      document.querySelectorAll('[data-body-range]').forEach(n=>n.setAttribute('aria-pressed',String(Number(n.dataset.bodyRange)===bodyRange)));tracks.get('body-range')?.sync(String(bodyRange));paintHero();return;
    }
    q('#body-scrub').disabled=rows.length<2;for(const id of ['#body-point'])q(id).style.display='';
    const points=bodyPlot(rows),trend=points,smooth=curve(points),end=rows.at(-1),start=rows[0],[label,unit]=bodyFields[bodyMetric],values=rows.map(date=>sample('body',date)[bodyMetric]);
    document.querySelectorAll('[data-body-range]').forEach(n=>n.setAttribute('aria-pressed',String(Number(n.dataset.bodyRange)===bodyRange)));tracks.get('body-range')?.sync(String(bodyRange));
    q('#body-line').setAttribute('d',smooth);q('#body-dots').setAttribute('fill-opacity',bodyRange>30?.32:.55);q('#body-dots').innerHTML=points.map(p=>`<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${bodyRange>30?1.1:bodyRange>7?1.5:2.2}"/>`).join('');q('#body-area').setAttribute('d',`${smooth}L${trend.at(-1).x} 107L${trend[0].x} 107Z`);q('#body-scrub').min=bodyOffset(start);q('#body-scrub').max=Math.max(bodyOffset(start)+1,bodyOffset(end));q('.body-chart').setAttribute('aria-label',label+' history. Drag on the chart or use arrow keys to inspect recorded dates.');
    const axis=date=>stamp(date+'T12:00:00');
    q('#body-range-start').textContent=axis(start);q('#body-range-end').textContent=axis(end);q('#body-range-middle').textContent=rows.length>1?'Slide to inspect':'';q('#body-gap').textContent='';
    for(const [id,value] of [['#body-average',values.reduce((a,b)=>a+b,0)/values.length],['#body-low',Math.min(...values)],['#body-high',Math.max(...values)]])q(id).innerHTML=`${value.toFixed(1)}<small>${unit}</small>`;
    q('#body-coverage').textContent=`${bodyRange===365?'Last year':bodyRange===90?'Last 3 months':'Last '+bodyRange+' days'} · Recorded dates only`;
    inspectBody(rows.indexOf(bodyDate));
    if(animate)SurfaceMotion.shift(q('.body-chart>svg'),{x:0,y:4},220);
  }
  const dayStart=value=>{const d=new Date(value);d.setHours(0,0,0,0);return d};
  const weekStart=value=>{const d=dayStart(value);d.setDate(d.getDate()-(d.getDay()+6)%7);return d};
  const addDays=(date,n)=>{const d=new Date(date);d.setDate(d.getDate()+n);return d};
  const hhmm=ms=>new Date(ms).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'});
  const duration=ms=>{const seconds=Math.floor(ms/1000),minutes=Math.floor(seconds/60);return minutes>=60?`${Math.floor(minutes/60)}h ${minutes%60}m`:minutes?`${minutes}m${seconds%60?' '+seconds%60+'s':''}`:`${seconds}s`};
  function weekBins(){
    if(historyDate===null)historyDate=dayStart(history()[0]?.startedAt??now()).getTime();
    const start=weekStart(historyDate);
    return Array.from({length:7},(_,i)=>{const date=addDays(start,i),end=addDays(date,1).getTime(),records=history().filter(r=>r.startedAt>=date.getTime()&&r.startedAt<end);return {date,records,minutes:records.reduce((n,r)=>n+r.elapsed,0)/60000}});
  }
  function historySessions(bin){
    return `<h3 class="history-day"><span>${bin.date.toLocaleDateString('en-GB',{weekday:'long',day:'numeric',month:'short'})}</span><small>${bin.records.length} ${bin.records.length===1?'workout':'workouts'}</small></h3>${bin.records.length?bin.records.map(r=>{
      const v=WorkoutDetails.readings(r,r.elapsed),metrics=[[duration(r.elapsed),'Duration']];
      if(v.distance!==null)metrics.push([(v.distance/1000).toFixed(2)+' km','Distance']);
      if(v.calories!==null)metrics.push([Math.round(v.calories)+' kcal',r.imported?'Energy':'Energy est.']);
      return `<button class="history-session" data-workout-detail="${HealthData.escape(r.recordKey??r.startedAt)}"><span class="history-session-icon" aria-hidden="true">${activityIcon(r.kind)}</span><span class="history-session-title"><strong>${HealthData.escape(r.kind)}</strong><small>${hhmm(r.startedAt)}${r.imported?' · Samsung Health':''}</small></span><span class="history-session-metrics">${metrics.map(([value,label])=>`<span><b>${value}</b><small>${label}</small></span>`).join('')}</span></button>`;
    }).join(''):'<div class="history-empty"><p>No workouts this day</p><span>Choose a marked date to see a session.</span></div>'}`;
  }
  function historyCalendar(){
    const bins=weekBins(),start=bins[0].date,end=bins[6].date,count=bins.reduce((n,b)=>n+b.records.length,0),ms=bins.reduce((n,b)=>n+b.minutes*60000,0),today=dayStart(now());
    const earliest=weekStart(Math.min(now(),...history().map(r=>r.startedAt))),selected=bins.find(b=>b.date.getTime()===historyDate)||bins[0];
    return `<div class="history-overview"><div class="history-week-nav"><div><h2>${start.getTime()===weekStart(now()).getTime()?'This week':'Training week'}</h2><p>${stamp(start)} – ${stamp(end,true)}</p></div><button data-history-week="-1" aria-label="Previous week" ${start<=earliest?'disabled':''}><svg class="date-arrow date-arrow-back" aria-hidden="true"><use href="#chevron"/></svg></button><button data-history-week="1" aria-label="Next week" ${end>=today?'disabled':''}><svg class="date-arrow" aria-hidden="true"><use href="#chevron"/></svg></button></div>
    <div class="history-week-metrics"><p><strong>${duration(ms)}</strong><small>Recorded time</small></p><p><strong>${count}</strong><small>${count===1?'Session':'Sessions'}</small></p></div>
    <div class="history-days blob-track" role="group" aria-label="Choose workout day"><span class="selection-pill" aria-hidden="true"></span>${bins.map((b,i)=>`<button class="blob-option${b.records.length?' has-records':''}" data-week-day="${i}" aria-pressed="${b.date.getTime()===historyDate}" aria-label="${stamp(b.date,true)}, ${b.records.length} sessions, ${duration(b.minutes*60000)} active" ${b.date>today?'disabled':''}><small>${b.date.toLocaleDateString('en-GB',{weekday:'short'})}</small><strong>${b.date.getDate()}</strong></button>`).join('')}</div></div>
    <div id="history-sessions" aria-live="polite">${historySessions(selected)}</div>`;
  }
  function inspectWeek(index){
    const bin=weekBins()[index];if(!bin||bin.date>dayStart(now()))return;
    historyDate=bin.date.getTime();document.querySelectorAll('[data-week-day]').forEach(n=>n.setAttribute('aria-pressed',String(Number(n.dataset.weekDay)===index)));
    tracks.get('history-day')?.sync(String(index));q('#history-sessions').innerHTML=historySessions(bin);SurfaceMotion.shift(q('#history-sessions'),{x:0,y:5},180);
  }
  function changeWeek(step){
    if(![-1,1].includes(step))return;
    const next=addDays(historyDate,step*7),earliest=weekStart(Math.min(now(),...history().map(r=>r.startedAt)));
    if(weekStart(next)<earliest||weekStart(next)>weekStart(now()))return;
    historyDate=Math.min(next.getTime(),dayStart(now()).getTime());q('#workout-calendar').innerHTML=historyCalendar();bindTracks();window.LiquidGlass?.enhance();
    SurfaceMotion.shift(q('#workout-calendar'),{x:step*10,y:0},200);const nextFocus=q('[data-history-week="'+step+'"]');(nextFocus.disabled?q('[data-week-day][aria-pressed=true]'):nextFocus).focus({preventScroll:true});
  }
  function workoutHome(){
    return `<div class="workout-tabs glass-track blob-track" role="group" aria-label="Workouts view"><span class="selection-pill glass-indicator" aria-hidden="true"></span>${[['train','Train'],['history','History']].map(([id,label])=>`<button class="blob-option" data-workout-tab="${id}" aria-pressed="${workoutTab===id}" aria-controls="workout-hub-content">${label}</button>`).join('')}</div><div id="workout-hub-content">${workoutHub()}</div>`;
  }
  function workoutHub(){
    if(workoutTab==='history'&&storageFault)return '<p class="health-error">Saved workouts could not be read. Your history has been preserved.</p>';
    if(workoutTab==='history')return `<section class="workout-history" id="workout-calendar" aria-label="Workout history">${historyCalendar()}</section>`;
    const start=weekStart(now()).getTime(),end=addDays(start,7).getTime(),records=history().filter(r=>r.startedAt>=start&&r.startedAt<end),ms=records.reduce((n,r)=>n+r.elapsed,0);
    return `<section class="workout-start"><h2 class="workout-start-title">Choose your workout</h2><div class="workout-kinds">${kinds.map(kind=>`<button data-setup="${kind}" ${storageFault?'disabled':''}>${activityIcon(kind)}<strong>${kind}</strong></button>`).join('')}</div></section>${storageFault?'<p class="health-error">Saved workouts could not be read. Recording is disabled to preserve them.</p>':''}<button class="workout-week-link" data-workout-tab="history"><span><small>This week</small><strong>${duration(ms)} <span>· ${records.length} ${records.length===1?'session':'sessions'}</span></strong></span><span class="week-link-label">History</span></button>${history()[0]?`<div class="workout-last"><h2>Last workout</h2>${historySessions({date:new Date(history()[0].startedAt),records:[history()[0]]})}</div>`:''}`;
  }
  function setWorkoutTab(tab){
    if(!['train','history'].includes(tab)||tab===workoutTab)return;
    workoutTab=tab;document.querySelectorAll('.workout-tabs button').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.workoutTab===tab)));tracks.get('workout-tab')?.sync(tab);
    q('#workout-hub-content').innerHTML=workoutHub();bindTracks();window.LiquidGlass?.enhance();q('#health-scroll').scrollTop=0;SurfaceMotion.reveal(q('#workout-hub-content'),{x:tab==='history'?8:-8,y:0},180);
  }
  // One configuration flow for every activity: the header carries the name; the panel holds target, GPS and weight.
  function setupView(){
    const outdoor=setup.kind!=='Strength',timed=Boolean(setup.targetMs);
    return `<section class="workout-setup"><form id="workout-setup-form"><div class="setup-panel"><div class="setup-activity" aria-hidden="true">${activityIcon(setup.kind)}</div><fieldset class="setup-target"><legend>Choose a goal</legend><div class="setup-segments glass-track blob-track"><span class="selection-pill glass-indicator" aria-hidden="true"></span><label class="blob-option"><input type="radio" name="target" value="open" ${timed?'':'checked'}/><span>No target</span></label><label class="blob-option"><input type="radio" name="target" value="time" ${timed?'checked':''}/><span>Time</span></label></div></fieldset><div class="setup-goal"><p class="setup-open-note" ${timed?'hidden':''}>Go at your own pace.<br><span>Finish whenever you’re ready.</span></p><div id="target-options" class="target-options" ${timed?'':'hidden'}><label class="target-minutes"><span>Duration</span><span class="setup-field"><input id="target-minutes" ${timed?'':'disabled'} type="number" min="1" max="1440" step="1" value="${timed?setup.targetMs/60000:30}" inputmode="numeric"/><small>min</small></span></label></div></div>${outdoor?`<label class="tracking-option"><span>Track outdoors<small>Phone GPS · route and distance</small></span><input id="workout-track" type="checkbox" role="switch" ${setup.trackLocation?'checked':''}/></label>`:''}</div><p class="setup-help">${outdoor?'Turn GPS off for an indoor workout.':'Active time and pauses are saved with your workout.'}</p><p class="health-error" id="workout-error" role="alert"></p><button class="workout-primary" type="submit">Start ${HealthData.escape(setup.kind.toLowerCase())}</button></form></section>`;
  }
  function sessionView(){
    const a=store.active,t=elapsed(a),paused=a.resumedAt===null;
    if(!a.targetMs)timerMode='elapsed';
    return `<section data-started-at="${a.startedAt}" class="workout-live ${timerFocused?'timer-focused':''} ${paused?'is-paused':''}"><div class="workout-live-top"><button class="workout-dot-play" data-dot-play aria-label="Change dot pattern">${HeroDots.pattern(dotPattern)}</button><p class="health-eyebrow" id="session-status">${paused?'Paused':'Recording'}</p></div><div class="workout-time-focus"><button class="timer-dial" data-timer-focus aria-pressed="${timerFocused}" aria-label="${timerFocused?'Show workout details':'Focus on timer and music'}"><svg class="timer-orbit" viewBox="0 0 320 300" aria-hidden="true">${Array.from({length:60},(_,i)=>{const a=(i/60*300-240)*Math.PI/180;return `<circle data-timer-dot="${i}" cx="${160+Math.cos(a)*143}" cy="${150+Math.sin(a)*132}" r="${i%5===0?2.5:1.7}"/>`}).join('')}</svg><span class="timer-center"><span id="timer-label">${timerMode==='remaining'?'Remaining':'Duration'}</span><output class="session-time" id="session-time" tabindex="-1" aria-label="Workout ${timerMode} time"><canvas id="focus-time-dots" aria-hidden="true"></canvas><span class="session-matrix">${HeroDots.markup(clock(timerMode==='remaining'?Math.max(0,a.targetMs-t):t))}</span></output><span class="timer-tap-hint">${timerFocused?'Tap to show details':'Tap to focus'}</span></span></button>${a.targetMs?`<div class="timer-mode-picker glass-track blob-track" role="group" aria-label="Timer reading"><span class="selection-pill glass-indicator" aria-hidden="true"></span><button class="blob-option" data-timer-mode="elapsed" aria-pressed="${timerMode==='elapsed'}">Elapsed</button><button class="blob-option" data-timer-mode="remaining" aria-pressed="${timerMode==='remaining'}">Remaining</button></div><progress class="sr-only" id="session-progress" value="${Math.min(t,a.targetMs)}" max="${a.targetMs}" aria-label="Workout target progress"></progress><p class="target-remaining" id="target-remaining">${t>=a.targetMs?'Target reached':clock(a.targetMs-t)+' remaining'} · ${a.targetMs/60000} min target</p>`:'<p class="target-remaining">Open workout</p>'}</div><div class="workout-unconnected" id="live-workout-metrics">${WorkoutDetails.compact(a,t,total(a))}</div><section class="workout-music" aria-label="Music player" hidden></section>${WorkoutDetails.actions(a)}${window.OrbitWorkouts?.notificationStatus&&window.OrbitWorkouts.notificationStatus()==='disabled'?'<button class="notification-enable" data-notifications>Enable live notification</button>':''}</section>`;
  }
  function record(){return workoutRecord==='active'?store.active:history().find(s=>s.imported?s.recordKey===workoutRecord:s.startedAt===workoutRecord)}
  function recordView(){const s=record();return s?`<div id="workout-record-body">${WorkoutDetails.view(s,elapsed(s),total(s),workoutChart)}</div>`:''}
  function paintMetrics(){
    if(page!=='workouts')return;
    if(workoutRecord!==null&&record())WorkoutDetails.updateRecord(q('#workout-record-body'),record(),elapsed(record()),total(record()),workoutChart);
    else if(store.active&&q('#live-workout-metrics'))WorkoutDetails.updateCompact(q('#live-workout-metrics'),store.active,elapsed(store.active),total(store.active));
  }
  function paintLive(){
    const live=page==='workouts'&&workoutRecord===null&&store.active&&q('.workout-live');if(!live||live.dataset.startedAt!==String(store.active.startedAt))return false;
    const paused=store.active.resumedAt===null,button=live.querySelector('[data-session="pause"],[data-session="resume"]');
    live.classList.toggle('is-paused',paused);q('#session-status').textContent=paused?'Paused':'Recording';button.dataset.session=paused?'resume':'pause';button.textContent=paused?'Resume workout':'Pause workout';paintTimer();paintMetrics();return true;
  }
  function render(){
    if(!page)return;
    if(page!=='body'){cancelAnimationFrame(lens.frame);lens.frame=0;lens.drag=null;}
    // A background workout update must not replace a profile form being edited.
    if(page==='settings'&&q('#profile-form'))return;
    const body=q('#health-content'),scroll=q('#health-scroll').scrollTop,focus=page==='workouts'&&workoutRecord===null&&(store.active||setup||countdown!==null);
    q('#health-title').textContent=page==='workouts'?(record()?.kind||store.active?.kind||setup?.kind||titles[page]):titles[page];
    q('#health-back').setAttribute('aria-label',workoutRecord!==null?'Back to workouts':countdown!==null?'Cancel countdown':setup?'Back to workouts':pageTrail.length?'Back to '+titles[pageTrail.at(-1).page]:page==='sleep'?'Back to sleep summary':'Back to dashboard');
    q('#health-page').classList.toggle('workout-focus',Boolean(focus));q('#health-page').dataset.page=page;q('#health-page').dataset.workoutScreen=page!=='workouts'?'':workoutRecord!==null?'record':countdown!==null?'countdown':store.active?'active':setup?'setup':'picker';
    const task=Boolean(focus||workoutRecord==='active');q('.screen').classList.toggle('is-task',task);q('#live-island').inert=task;
    document.querySelectorAll('[data-activity]').forEach(n=>{if(n.dataset.activity===(page==='sleep'?'overview':page))n.setAttribute('aria-current','page');else n.removeAttribute('aria-current')});
    countDots?.stop();countDots=null;WorkoutFocus.dispose();MusicPlayer.stop();
    body.innerHTML=page==='settings'?OrbitSettings.view():page==='body'?bodyView():page==='overview'?HealthDashboard.view(options):page==='sleep'?sleepView():workoutRecord!==null?recordView():countdown!==null?`<div class="workout-countdown"><div class="countdown-play"><canvas id="countdown-dots" aria-hidden="true"></canvas><output class="sr-only" id="workout-count" aria-live="polite">${countdown}</output></div><p class="countdown-invitation">Starts automatically · touch dots to play</p><div class="countdown-stages" aria-hidden="true">${[3,2,1].map(n=>`<i data-count-stage="${n}" class="${countdown<=n?'lit':''}"></i>`).join('')}</div><button data-cancel-countdown>Cancel</button></div>`:store.active?sessionView():setup?setupView():workoutHome();
    bindTracks();
    if(page==='body')paintBody();
    if(page==='settings')OrbitSettings.mount(options.settings);
    if(page==='workouts'&&countdown!==null)countDots=HeroDots.mount(q('#countdown-dots'),countdown);
    if(page==='workouts'&&store.active&&workoutRecord===null&&countdown===null){paintTimer();if(timerFocused)WorkoutFocus.attach(q('.workout-live'),true);MusicPlayer.mount(q('.workout-music'),timerFocused)}
    q('#health-scroll').scrollTop=scroll;frostHead();window.LiquidGlass?.enhance();
  }
  function cancelCountdown(){clearInterval(countTimer);countTimer=0;countdown=null;countDots?.stop();countDots=null}
  const startsIn=()=>store.active?nativeClock?Math.max(0,nativeClock.startsInMs-(performance.now()-nativeClock.at)):Math.max(0,store.active.startedAt-now()):0;
  function countTick(){
    const next=Math.ceil(startsIn()/1000);
    if(next<=0){cancelCountdown();setup=null;OrbitInteraction.haptic('confirm');if(page==='workouts'){SurfaceMotion.change(render);q('#session-time')?.focus({preventScroll:true})}schedule();return}
    if(next===countdown)return;countdown=next;OrbitInteraction.haptic('tick');
    const output=q('#workout-count');if(output)output.textContent=next;countDots?.set(next);document.querySelectorAll('[data-count-stage]').forEach(n=>n.classList.toggle('lit',next<=Number(n.dataset.countStage)));
  }
  function startCountdown(kind,targetMs,recording={}){
    if(store.active||!kinds.includes(kind)||!validTarget(targetMs)||!WorkoutDetails.valid(recording)||recording.trackLocation&&kind==='Strength')return false;
    cancelCountdown();setup={...recording,kind,targetMs};timerMode='elapsed';timerFocused=false;countdown=3;
    if(!action('countdown',kind,targetMs,recording)){cancelCountdown();render();return false}
    SurfaceMotion.change(render);countTimer=setInterval(countTick,100);return true;
  }
  function open(which,source=q('#live-bar'),stage){
    if(!Object.hasOwn(titles,which))return false;
    if(source===q('#live-bar')||source?.matches('[data-activity]'))metricReturn=null;
    if(page===which&&workoutRecord===null){options.beforeOpen();return true}
    if(page&&source?.closest('#health-content'))pageTrail.push({page,scroll:q('#health-scroll').scrollTop});else pageTrail=[];
    const enter=()=>{
      if(!page)returnFocus=source||document.activeElement;page=which;setup=null;workoutRecord=null;if(which==='workouts'){historyDate=null;workoutTab='train'}cancelCountdown();options.beforeOpen();q('#health-page').hidden=false;q('.screen').classList.add('is-detail');
      if(which==='workouts'&&startsIn()>0){setup={kind:store.active.kind};countdown=Math.ceil(startsIn()/1000);countTimer=setInterval(countTick,100)}
      if(which==='sleep'){sleepDate=options.getDate();const night=options.getDaily(sleepDate).sleepTimeline;sleepMinute=SleepTimeline.valid(night)?SleepTimeline.stageMinute(night,stage):0}
      for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island'])q(selector).inert=true;
      render();if(which==='sleep'&&stage&&SleepTimeline.valid(options.getDaily(sleepDate).sleepTimeline))setSleepMinute(sleepMinute);q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true});q('#health-page').setAttribute('aria-label',titles[which]);return true;
    };
    if(page)return SurfaceMotion.change(enter);
    enter();SurfaceMotion.reveal(q('#health-page'));return true;
  }
  function returnFromMetric(){
    if(!metricReturn)return false;
    const prior=metricReturn;options.restoreDate(prior.date);open(prior.page);pageTrail=prior.trail;
    const oxygen=q('.oxygen-tile');if(oxygen&&prior.oxygenOpen){oxygen.open=true;oxygen.querySelector('summary').setAttribute('aria-expanded','true');if(prior.oxygenIndex!==null)HealthDashboard.inspectOxygen(prior.oxygenIndex)}
    q('#health-scroll').scrollTop=prior.scroll;q(`[data-home-metric="${prior.metric}"]`)?.focus({preventScroll:true});return true;
  }
  function close(){
    if(!page)return false;
    cancelAnimationFrame(lens.frame);lens.frame=0;lens.drag=null;
    if(workoutRecord!==null){SurfaceMotion.change(()=>{workoutRecord=null;render();q('#health-scroll').scrollTop=workoutReturn?.scroll||0;workoutReturn=null;q('#health-back').focus({preventScroll:true})});return true}
    if(countdown!==null){const native=window.OrbitWorkouts;if(native?.cancelStart){if(!native.cancelStart()){countTick();return true}readStore()}else if(startsIn()>0){if(!commit({...store,active:null}))return true}else{countTick();return true}SurfaceMotion.change(()=>{cancelCountdown();render();q('.workout-primary')?.focus({preventScroll:true})});schedule();options.onChange();return true}
    if(setup){const kind=setup.kind;SurfaceMotion.change(()=>{setup=null;render();q('#health-scroll').scrollTop=0;q('[data-setup="'+kind+'"]').focus({preventScroll:true})});return true}
    if(pageTrail.length){const prior=pageTrail.pop();SurfaceMotion.change(()=>{page=prior.page;render();q('#health-page').setAttribute('aria-label',titles[page]);q('#health-scroll').scrollTop=prior.scroll;q('#health-back').focus({preventScroll:true})});return true}
    MusicPlayer.stop();WorkoutFocus.dispose();releaseTracks();page=null;SurfaceMotion.dismiss(q('#health-page'),()=>{
      q('#health-page').hidden=true;q('.screen').classList.remove('is-detail','is-task');document.querySelectorAll('[data-activity]').forEach(n=>n.removeAttribute('aria-current'));
      for(const selector of ['.masthead','.hero','#deck-scroll','#stack-open','#utility-island','#live-island'])q(selector).inert=false;
      options.onClose();if(returnFocus?.isConnected&&!returnFocus.closest('[inert]'))returnFocus.focus({preventScroll:true});else q('#live-bar').focus({preventScroll:true});
    });return true;
  }
  function refresh(){const before=store,structure=JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length]);if(window.OrbitWorkouts)readStore();if(before!==store){if(structure!==JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length])){if(workoutRecord==='active'&&!store.active)workoutRecord=store.history[0]?.startedAt??null;if(page==='workouts'&&!paintLive())render();schedule()}else paintMetrics();options.onChange()}}
  function paintTimer(){if(!store.active||!q('#session-time'))return;const t=elapsed(store.active),target=store.active.targetMs||0,reading=clock(timerMode==='remaining'?Math.max(0,target-t):t),ratio=target?Math.min(1,t/target):t%60000/60000;if(q('#session-time').dataset.reading!==reading){const slot=q('#session-time .session-matrix')||q('#session-time');let svg=slot.querySelector('.matrix-reading');if(svg)svg.outerHTML=HeroDots.markup(reading);else slot.insertAdjacentHTML('beforeend',HeroDots.markup(reading));WorkoutFocus.reading(reading);q('#session-time').dataset.reading=reading;q('#session-time').setAttribute('aria-label',`${timerMode==='remaining'?'Remaining':'Duration'}: ${reading}`)}document.querySelectorAll('[data-timer-dot]').forEach(n=>n.classList.toggle('lit',Number(n.dataset.timerDot)/60<ratio));if(target){q('#session-progress').value=Math.min(t,target);q('#target-remaining').textContent=(t>=target?'Target reached':clock(Math.max(0,target-t))+' remaining')+' · '+target/60000+' min target'}}
  function tick(){refresh();if(page==='workouts'){paintTimer();if(workoutRecord!==null&&record())WorkoutDetails.updateTimes(q('#workout-record-body'),record(),elapsed(record()),total(record()));else if(store.active&&q('#live-workout-metrics'))WorkoutDetails.updateCompact(q('#live-workout-metrics'),store.active,elapsed(store.active),total(store.active))}options.onTick()}
  function schedule(){clearInterval(timer);timer=0;if(store.active&&!document.hidden)timer=setInterval(tick,1000)}
  // Reading pages share one soft scroll edge; it stays clear at the top.
  function frostHead(){const frost=q('.health-head-frost');if(frost)frost.style.opacity=String(Math.min(1,q('#health-scroll').scrollTop/24))}
  function setFocus(value){const live=q('.workout-live');if(!live||timerFocused===value)return;timerFocused=value;WorkoutFocus.toggle(live,value)}
  function init(config){
    options=config;HealthDashboard.init(q('#health-content'));MusicPlayer.init(config.notice);readStore();q('#health-back').addEventListener('click',close);
    WorkoutFocus.bind(q('#health-page'),{target:value=>{timerFocused=value}});
    q('#health-content').addEventListener('click',event=>{
      const summary=event.target.closest('summary');if(summary){event.preventDefault();SurfaceMotion.toggleDetails(summary.closest('details'));return}
      if(event.target.closest('.blob-track')&&fromTrack(event)){event.preventDefault();return} // the gesture already committed
      const b=event.target.closest('button');if(!b||HealthDashboard.click(b))return;
      if(b.dataset.session){
        const finish=b.dataset.session==='finish';
        const saved=finish?SurfaceMotion.change(()=>{const saved=action('finish');if(saved){workoutTab='history';workoutRecord=store.history[0].startedAt;historyDate=dayStart(workoutRecord).getTime();workoutChart='route';render();q('#health-scroll').scrollTop=0}return saved}):action(b.dataset.session);
        if(saved){if(finish){options.notice('Workout saved');q('#health-back').focus({preventScroll:true})}else q('#health-content [data-session="'+(store.active.resumedAt===null?'resume':'pause')+'"]').focus({preventScroll:true})}
      }
      else if(b.dataset.setup)SurfaceMotion.change(()=>{setup={kind:b.dataset.setup,targetMs:0,trackLocation:b.dataset.setup!=='Strength',weightKg:OrbitSettings.profile().weightKg||0};if(OrbitSettings.error)options.notice(OrbitSettings.error);render();q('#health-scroll').scrollTop=0;q('input[name="target"]:checked').focus({preventScroll:true})});
      else if(b.dataset.workoutDetail){workoutReturn={scroll:q('#health-scroll').scrollTop};workoutRecord=b.dataset.workoutDetail==='active'?'active':b.dataset.workoutDetail.startsWith('samsung:')?b.dataset.workoutDetail:Number(b.dataset.workoutDetail);if(!record()){workoutRecord=null;return}workoutChart='route';SurfaceMotion.change(()=>{render();q('#health-scroll').scrollTop=0;q('#health-back').focus({preventScroll:true})})}
      else if(b.dataset.workoutChart)setWorkoutChart(b.dataset.workoutChart);
      else if(b.dataset.historyWeek)changeWeek(Number(b.dataset.historyWeek));
      else if(b.dataset.workoutTab)setWorkoutTab(b.dataset.workoutTab);
      else if(b.dataset.weekDay!==undefined)inspectWeek(Number(b.dataset.weekDay));
      else if(b.hasAttribute('data-music-connect'))MusicPlayer.connect();
      else if(b.hasAttribute('data-tracking'))window.OrbitWorkouts?.enableTracking();
      else if(b.dataset.bodyMetric||b.hasAttribute('data-next-body')){if(b.dataset.bodyMetric)goBody(b.dataset.bodyMetric);else goBody(bodyNeighbour(bodyMetric,1),1)}
      else if(b.dataset.bodyRange)setBodyRange(Number(b.dataset.bodyRange));
      else if(b.dataset.nightDate){const date=options.dates[options.dates.indexOf(sleepDate)+Number(b.dataset.nightDate)];if(date){sleepDate=date;sleepMinute=0;render();q('[data-night-date="'+b.dataset.nightDate+'"]').focus({preventScroll:true})}}
      else if(b.dataset.nightStage){const night=options.getDaily(sleepDate).sleepTimeline;setSleepMinute(SleepTimeline.stageMinute(night,b.dataset.nightStage))}
      else if(b.dataset.timerMode)setTimerMode(b.dataset.timerMode);
      else if(b.hasAttribute('data-timer-focus'))setFocus(!timerFocused);
      else if(b.hasAttribute('data-music-expand'))setFocus(true);
      else if(b.hasAttribute('data-dot-play')){dotPattern=(dotPattern+1)%3;HeroDots.reshape(b,dotPattern)}
      else if(b.dataset.open)open(b.dataset.open,b);
      else if(b.dataset.homeMetric){metricReturn={page,trail:pageTrail,scroll:q('#health-scroll').scrollTop,date:options.getDate(),metric:b.dataset.homeMetric,oxygenOpen:Boolean(q('.oxygen-tile')?.open),oxygenIndex:q('#oxygen-scrub')?Number(q('#oxygen-scrub').value):null};pageTrail=[];close();options.showMetric(b.dataset.homeMetric)}
      else if(b.hasAttribute('data-cancel-countdown'))close();
      else if(b.hasAttribute('data-notifications'))window.OrbitWorkouts?.enableNotifications();
    });
    q('#health-content').addEventListener('change',event=>{if(event.target.name==='target'){const open=event.target.value==='time';q('#target-minutes').disabled=!open;q('.setup-open-note').hidden=open;q('#target-options').hidden=!open;SurfaceMotion.reveal(q(open?'#target-options':'.setup-open-note'),{x:0,y:4},180)}});
    q('#health-content').addEventListener('input',event=>{if(event.target.id==='body-scrub'){const day=Number(event.target.value),rows=bodyRows();if(Number.isInteger(day)&&day>=0&&day<bodyRange)inspectBody(rows.reduce((best,date,i)=>Math.abs(bodyOffset(date)-day)<Math.abs(bodyOffset(rows[best])-day)?i:best,0))}if(event.target.id==='night-scrub')setSleepMinute(Number(event.target.value));if(event.target.id==='oxygen-scrub')HealthDashboard.inspectOxygen(Number(event.target.value))});
    q('#health-content').addEventListener('submit',event=>{if(event.target.id!=='workout-setup-form')return;event.preventDefault();const time=q('input[name="target"]:checked').value==='time',minutes=Number(q('#target-minutes').value);if(time&&(!Number.isInteger(minutes)||minutes<1||minutes>1440)){q('#workout-error').textContent='Choose between 1 and 1,440 minutes.';return}const weightKg=OrbitSettings.profile().weightKg||0,trackLocation=Boolean(q('#workout-track')?.checked);if(weightKg!==0&&(!Number.isFinite(weightKg)||weightKg<20||weightKg>350)){q('#workout-error').textContent='Update your weight in Settings before starting.';return}startCountdown(setup.kind,time?minutes*60000:0,{trackLocation,weightKg})});
    document.addEventListener('visibilitychange',()=>{schedule();if(!document.hidden){if(countdown!==null)countTick();tick()}});
    const realign=()=>{if(page)remeasureTracks()};window.addEventListener('resize',realign);document.fonts?.ready.then(realign);
    const content=q('#health-content');for(const [type,fn] of [['pointerdown',lensDown],['pointermove',lensMove]])content.addEventListener(type,fn);for(const type of ['pointerup','pointercancel','lostpointercapture'])content.addEventListener(type,lensUp);
    content.addEventListener('keydown',event=>{if(!event.target.closest?.('[data-body-dial]')||!['ArrowLeft','ArrowRight'].includes(event.key))return;const dir=event.key==='ArrowRight'?1:-1;event.preventDefault();goBody(bodyNeighbour(bodyMetric,dir),dir)});
    OrbitInteraction.swipe(q('#health-page'),target=>{
      if(!page)return null;
      const body=q('#health-content'),head=target.closest('.health-page-head');
      if(head||page==='settings'||page==='overview'||page==='workouts'&&(workoutRecord!==null||setup||store.active||countdown!==null))return {node:body,commit:dir=>{if(dir>0)close()}};
      if(page==='body')return {node:body,commit:dir=>goBody(bodyNeighbour(bodyMetric,-dir),-dir)};
      if(page==='sleep')return {node:body,commit:dir=>q('[data-night-date="'+(-dir)+'"]')?.click()};
      if(page==='workouts')return {node:body,commit:dir=>target.closest('.history-week-nav,.history-week-metrics')?changeWeek(-dir):setWorkoutTab(dir<0?'history':'train')};
      return null;
    });
    q('#health-scroll').addEventListener('scroll',frostHead,{passive:true});
    window.addEventListener('focus',refresh);schedule();
  }
  return {init,open,close,returnFromMetric,render,live,action,sample,elapsed,clock,validSession,validTarget,refresh,startCountdown,bindTracks,get hasMetricReturn(){return Boolean(metricReturn)},get page(){return page},get state(){return store},get tracks(){return tracks}};
})();
