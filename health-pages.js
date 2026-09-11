/* Visual health summaries and app-owned workouts. Measurements remain labelled demo data. */
'use strict';
const Health = (() => {
  const titles={body:'Body',workouts:'Workouts',overview:'Health',sleep:'Your night'},kinds=['Walking','Running','Cycling','Strength'];
  const key='orbit-workouts-v1',q=s=>document.querySelector(s);
  let page=null,store={active:null,history:[]},storageFault=false,options,timer=0,returnFocus;
  let bodyMetric='weight',bodyRange=30,bodyDate=null,setup=null,countdown=null,countTimer=0,countDots=null;
  let timerMode='elapsed',timerFocused=false,dotPattern=0,weekDay=6;
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
  function changed(){if(!paintLive())render();options.onChange();schedule()}
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
  // Weight first, then its parts from smallest to largest. Fat is tracked in kg; its share of weight is shown alongside.
  const bodyFields={weight:['Weight','kg'],fatMass:['Fat','kg'],muscle:['Muscle','kg'],lean:['Lean mass','kg']};
  function sample(which,date){
    const ago=Math.round((Date.parse(options.dates.at(-1)+'T12:00:00Z')-Date.parse(date+'T12:00:00Z'))/86400000),weight=75.8+ago*.025,fat=18.2+Math.sin(ago)*.3;
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
  function bodyRows(){const end=options.getDate(),start=dayOffset(end,1-bodyRange);return (options.historyDates||options.dates).filter(date=>date>=start&&date<=end)}
  function sleepView(){const index=options.dates.indexOf(sleepDate);return `<div class="sleep-date-nav"><button data-night-date="-1" aria-label="Previous night" ${index<=0?'disabled':''}>‹</button><span>${stamp(sleepDate+'T12:00:00',true)}</span><button data-night-date="1" aria-label="Next night" ${index>=options.dates.length-1?'disabled':''}>›</button></div>`+SleepTimeline.view(options.getDaily(sleepDate).night,sleepMinute)}
  // Each of the ring's 100 dots is 1% of body weight: purple is the selected measurement's share (fat for Weight).
  const bodyOrder=Object.keys(bodyFields),bodyTone={purple:[182,156,255],lean:[220,219,227],rest:[52,50,60]};
  const bodyShare=(field,d)=>field==='weight'||field==='fatMass'?d.fat/100:d[field]/d.weight;
  const bodyRest=field=>field==='weight'?bodyTone.lean:bodyTone.rest;
  const bodyIndex=field=>bodyOrder.indexOf(field),bodyNumber=(field,d)=>d[field].toFixed(1),percent=n=>Math.round(n*100)+'%';
  const mixTone=(a,b,t)=>a.map((v,i)=>Math.round(v+(b[i]-v)*t));
  let ringFills=[];
  function bodyCaption(field,d){
    if(field==='weight')return `<i class="is-lean"></i>${percent(d.lean/d.weight)} lean<i></i>${percent(d.fat/100)} fat`;
    return `<i></i>${field==='fatMass'?d.fat.toFixed(1)+'%':percent(bodyShare(field,d))} of body weight`;
  }
  function bodyReading(field,date){const d=sample('body',date),[label,unit]=bodyFields[field],latest=date===bodyRows().at(-1);return `<span class="body-measure-name">${label}${latest?'':' · '+stamp(date+'T12:00:00')}</span><span class="body-value">${HeroDots.markup(bodyNumber(field,d))}<small>${unit}</small></span><span class="body-share">${bodyCaption(field,d)}</span>`}
  function bodyRing(){return `<svg viewBox="0 0 300 300" aria-hidden="true"><circle cx="150" cy="150" r="117" fill="none" stroke="#ffffff08"/>${Array.from({length:100},(_,i)=>{const a=(i/100*360-90)*Math.PI/180;return `<circle data-mix-dot="${i}" cx="${(150+Math.cos(a)*134).toFixed(2)}" cy="${(150+Math.sin(a)*134).toFixed(2)}" r="2.6"/>`}).join('')}</svg>`}
  // A fractional share lights the boundary dot partly, so the arc grows smoothly while it moves.
  function paintRing(share,rest){const lit=share*100;document.querySelectorAll('[data-mix-dot]').forEach((dot,i)=>{const fill=`rgb(${mixTone(rest,bodyTone.purple,Math.max(0,Math.min(1,lit-i)))})`;if(ringFills[i]!==fill){ringFills[i]=fill;dot.setAttribute('fill',fill)}})}
  const bodyOffset=date=>Math.round((Date.parse(date+'T12:00:00Z')-Date.parse(dayOffset(options.getDate(),1-bodyRange)+'T12:00:00Z'))/86400000);
  function bodyPlot(rows){
    const values=rows.map(date=>sample('body',date)[bodyMetric]),min=Math.min(...values),span=Math.max(.1,Math.max(...values)-min),start=Date.parse(dayOffset(options.getDate(),1-bodyRange)+'T12:00:00Z');
    return values.map((value,i)=>({x:10+(Date.parse(rows[i]+'T12:00:00Z')-start)/86400000/Math.max(1,bodyRange-1)*280,y:97-(value-min)/span*65}));
  }
  // Long ranges draw weekly averages through the daily readings (the dots), so day-to-day scatter does not read as a zigzag.
  function bodyTrend(rows,points){
    if(bodyRange<90)return points;
    const last=Date.parse(rows.at(-1)+'T12:00:00Z'),weeks=new Map();
    rows.forEach((date,i)=>{const week=Math.floor((last-Date.parse(date+'T12:00:00Z'))/604800000);if(!weeks.has(week))weeks.set(week,[]);weeks.get(week).push(points[i])});
    const means=[...weeks.values()].map(group=>({x:group.reduce((sum,p)=>sum+p.x,0)/group.length,y:group.reduce((sum,p)=>sum+p.y,0)/group.length}));
    return [{x:points[0].x,y:means[0].y},...means,{x:points.at(-1).x,y:means.at(-1).y}].filter((p,i,all)=>!i||p.x>all[i-1].x+.01);
  }
  function positionBodySelection(picker){
    const selected=picker?.querySelector('[aria-pressed="true"]');if(!selected)return;
    picker.style.setProperty('--selection-x',selected.offsetLeft+'px');picker.style.setProperty('--selection-y',selected.offsetTop+'px');picker.style.setProperty('--selection-width',selected.offsetWidth+'px');picker.style.setProperty('--selection-height',selected.offsetHeight+'px');
    void picker.offsetWidth;picker.classList.add('is-ready');
  }
  // The ring is a lens over a looping row of measurements. A horizontal drag slides the reading, grows or shrinks the
  // purple arc and carries the selector capsule together; a tap on the centre or on the selector runs the same move.
  // x is in lens widths: 0 rests on `from`, -1 has moved one place on (to the next measurement), +1 one place back.
  // toSlot is where the capsule heads: one past either end of the row when the move loops round.
  const lens={x:{value:0,velocity:0},target:0,from:null,to:null,toSlot:0,pillFrom:null,frame:0,last:0,width:200,drag:null,dragged:0};
  const pick={drag:null,slot:{value:0,velocity:0},target:0,frame:0,last:0};
  const lensLayers=()=>{const root=q('.body-lens');return [root.querySelector('.is-current'),root.querySelector('.is-next')]};
  const bodyNeighbour=(field,dir)=>bodyOrder[(bodyIndex(field)+dir+bodyOrder.length)%bodyOrder.length];
  // Two capsules one track-width apart let the selection run off one end and back in at the other when the row loops.
  function placePillSlot(slot,stretch=0){
    const picker=q('.body-metric-picker'),pills=picker?.querySelectorAll('.selection-pill')||[],b=picker?.querySelector('[data-body-metric]');if(!b||!pills.length)return;
    // The second capsule shows only while the first crosses the right end, or its edge would peek into the track's padding.
    // Measured as fractions: the grid's columns are not whole pixels, and rounded offsets drift a pixel per column.
    const n=bodyOrder.length,m=(slot%n+n)%n,base=picker.getBoundingClientRect(),cell=b.getBoundingClientRect();
    pills.forEach((pill,k)=>Object.assign(pill.style,{visibility:k&&m<=n-1?'hidden':'',width:cell.width+'px',height:cell.height+'px',top:cell.top-base.top+'px',transform:`translate3d(${(cell.left-base.left+(m-k*n)*cell.width).toFixed(2)}px,0,0) scaleX(${(1+stretch).toFixed(3)})`}));
  }
  const placePill=()=>placePillSlot(bodyIndex(bodyMetric));
  function paintLens(){
    const [current,next]=lensLayers(),x=lens.x.value,t=Math.min(1,Math.abs(x)),w=lens.width,side=x<0?1:-1,from=lens.from||bodyMetric,to=lens.to||from,d=sample('body',bodyDate);
    current.style.transform=`translate3d(${(x*w).toFixed(2)}px,0,0) scale(${(1-.05*t).toFixed(4)})`;current.style.opacity=String(1-t*.6);
    next.style.transform=`translate3d(${((x+side)*w).toFixed(2)}px,0,0) scale(${(.95+.05*t).toFixed(4)})`;next.style.opacity=String(lens.to?.4+t*.6:0);
    paintRing(bodyShare(from,d)+(bodyShare(to,d)-bodyShare(from,d))*t,mixTone(bodyRest(from),bodyRest(to),t));
    if(!pick.drag?.active&&!pick.frame){const a=lens.pillFrom??bodyIndex(from),b=lens.to?lens.toSlot:a;placePillSlot(a+(b-a)*t)}
  }
  function settleLens(){
    const [current,next]=lensLayers();lens.frame=0;
    if(lens.target&&lens.to)current.innerHTML=next.innerHTML;
    next.innerHTML='';lens.x.value=lens.x.velocity=lens.target=0;lens.from=lens.to=lens.pillFrom=null;
    for(const layer of [current,next]){layer.style.transform='';layer.style.opacity=''}
    q('.body-lens').classList.remove('is-moving');paintHero();
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
    if(field!==bodyMetric){bodyMetric=field;paintBody(true);const d=sample('body',bodyDate);q('#body-announce').textContent=`${bodyFields[field][0]}, ${bodyNumber(field,d)} ${bodyFields[field][1]}`}
    runLens();
  }
  // dir (+1 or -1) steps round the loop as a swipe does; without it a selector tap slides straight to the chosen label.
  // pillFrom starts the capsule where a selector drag left it.
  function goBody(field,dir=0,pillFrom=null){
    if(!Object.hasOwn(bodyFields,field)||lens.drag)return;
    const [current,next]=lensLayers();
    if(lens.frame&&lens.to&&Math.abs(lens.x.value)>=.5){current.innerHTML=next.innerHTML;lens.x.value+=lens.x.value<0?1:-1;lens.from=lens.to;lens.to=null}
    else if(!lens.frame)lens.from=bodyMetric;
    if(field===lens.from){if(lens.frame)aimLens(0);return}
    const from=bodyIndex(lens.from);lens.pillFrom=pillFrom;lens.to=field;lens.toSlot=dir?from+dir:bodyIndex(field);
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
  function runPick(){
    if(pick.frame)return;pick.last=performance.now();
    const step=time=>{
      const dt=Math.min(.034,Math.max(.001,(time-pick.last)/1000));pick.last=time;springStep(pick.slot,pick.target,dt,26);
      if(!pick.drag&&Math.abs(pick.slot.value-pick.target)<.003&&Math.abs(pick.slot.velocity)<.02){pick.frame=0;placePill();return}
      placePillSlot(pick.slot.value,Math.min(.07,Math.abs(pick.slot.velocity)*.02));pick.frame=requestAnimationFrame(step);
    };
    pick.frame=requestAnimationFrame(step);
  }
  // The target segments obey the same press/drag/release rule as the Body selector: a short tap selects, a held
  // horizontal move carries the capsule over the fixed labels, and the release commits whatever sits under the
  // finger. A cancelled gesture, or a release away from the track, restores the selection the press started from.
  const seg={drag:null,dragged:0};
  const segLabels=track=>[...track.querySelectorAll('label')];
  function segFraction(track,x){const labels=segLabels(track),first=labels[0].getBoundingClientRect(),last=labels.at(-1).getBoundingClientRect(),a=first.left+first.width/2,b=last.left+last.width/2;return Math.max(0,Math.min(labels.length-1,(x-a)/Math.max(1,(b-a)/(labels.length-1))))}
  function segPlace(track,value){track.querySelector('.setup-capsule').style.setProperty('--seg-x',value.toFixed(4))}
  function segRest(track){track.classList.remove('is-dragging');getComputedStyle(track).transform;track.querySelector('.setup-capsule').style.removeProperty('--seg-x');segLabels(track).forEach(label=>label.classList.remove('is-over','is-pressed'))}
  function segDown(event){
    const track=event.target.closest?.('.setup-segments');if(!track||!event.isPrimary||seg.drag)return;
    const label=event.target.closest('label');label?.classList.add('is-pressed');
    seg.drag={id:event.pointerId,x:event.clientX,y:event.clientY,track,label,active:false,from:track.querySelector('input:checked')?.value||'open'};
  }
  function segMove(event){
    const g=seg.drag;if(!g||event.pointerId!==g.id)return;
    if(!g.active){
      const dx=event.clientX-g.x,dy=event.clientY-g.y;
      if(Math.abs(dy)>8&&Math.abs(dy)>Math.abs(dx)){g.label?.classList.remove('is-pressed');seg.drag=null;return} // a vertical gesture stays a scroll
      if(Math.abs(dx)<6)return;
      g.active=true;try{g.track.setPointerCapture(g.id)}catch{}g.track.classList.add('is-dragging');g.label?.classList.remove('is-pressed');
    }
    const at=segFraction(g.track,event.clientX);segPlace(g.track,at);
    segLabels(g.track).forEach((label,i)=>label.classList.toggle('is-over',i===Math.round(at)));
  }
  function segUp(event){
    const g=seg.drag;if(!g||event.pointerId!==g.id||event.type==='lostpointercapture'&&event.target!==g.track)return;
    seg.drag=null;const track=g.track,labels=segLabels(track);
    if(!g.active){labels.forEach(label=>label.classList.remove('is-pressed'));return} // a tap is left to the label and its radio
    seg.dragged=performance.now();
    const bounds=track.getBoundingClientRect(),inside=event.type==='pointerup'&&event.clientY>bounds.top-44&&event.clientY<bounds.bottom+44;
    const index=inside?Math.round(segFraction(track,event.clientX)):labels.findIndex(label=>label.querySelector('input').value===g.from);
    const input=labels[Math.max(0,index)].querySelector('input');
    if(!input.checked){input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}))}
    segRest(track); // the capsule travels to its resting place from wherever the finger left it
  }
  function pickDown(event){
    const track=event.target.closest?.('.body-metric-picker');if(!track||!event.isPrimary||pick.drag||lens.drag)return;
    pick.drag={id:event.pointerId,x:event.clientX,y:event.clientY,active:false,track};
  }
  function pickMove(event){
    const g=pick.drag;if(!g||event.pointerId!==g.id)return;
    if(!g.active){
      const dx=event.clientX-g.x,dy=event.clientY-g.y;
      if(Math.abs(dy)>8&&Math.abs(dy)>Math.abs(dx)){pick.drag=null;return}
      if(Math.abs(dx)<6)return;
      const first=g.track.querySelector('[data-body-metric]'),n=bodyOrder.length,t=Math.min(1,Math.abs(lens.x.value)),a=lens.pillFrom??bodyIndex(lens.from||bodyMetric),b=lens.to?lens.toSlot:a;
      g.active=true;g.left=first.getBoundingClientRect().left;g.col=first.getBoundingClientRect().width;try{g.track.setPointerCapture(g.id)}catch{}g.track.classList.add('is-dragging');
      if(!pick.frame)pick.slot={value:((lens.frame?a+(b-a)*t:bodyIndex(bodyMetric))%n+n)%n,velocity:0}; // from wherever the capsule is shown
    }
    pick.target=Math.max(0,Math.min(bodyOrder.length-1,(event.clientX-g.left)/g.col-.5));
    g.track.querySelectorAll('[data-body-metric]').forEach((label,i)=>label.classList.toggle('is-over',i===Math.round(pick.target)));runPick();
  }
  function pickUp(event){
    const g=pick.drag;if(!g||event.pointerId!==g.id||event.type==='lostpointercapture'&&event.target!==g.track)return;pick.drag=null;if(!g.active)return;
    lens.dragged=performance.now();g.track.classList.remove('is-dragging');g.track.querySelectorAll('.is-over').forEach(label=>label.classList.remove('is-over'));
    const field=event.type==='pointerup'?bodyOrder[Math.round(pick.target)]:bodyMetric;
    if(field===bodyMetric&&!lens.frame){pick.target=bodyIndex(field);runPick();return} // the capsule glides back under the current label
    cancelAnimationFrame(pick.frame);pick.frame=0;goBody(field,0,pick.slot.value);
  }
  function paintHero(){
    const d=sample('body',bodyDate),[label,unit]=bodyFields[bodyMetric];
    if(!lens.frame&&!lens.drag){lensLayers()[0].innerHTML=bodyReading(bodyMetric,bodyDate);paintRing(bodyShare(bodyMetric,d),bodyRest(bodyMetric));if(!pick.drag?.active&&!pick.frame)placePill()}
    document.querySelectorAll('[data-body-metric]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.bodyMetric===bodyMetric)));
    q('[data-next-body]').setAttribute('aria-label',`${label}, ${bodyNumber(bodyMetric,d)} ${unit}. Tap for the next measurement, or swipe left or right`);
  }
  function bodyView(){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1);ringFills=[];cancelAnimationFrame(lens.frame);Object.assign(lens,{frame:0,drag:null,from:null,to:null,target:0,x:{value:0,velocity:0}});
    const chevron='<svg aria-hidden="true"><use href="#chevron"/></svg>';
    return `<section class="body-hero"><div class="body-composition" data-body-dial><span id="body-ring" aria-hidden="true">${bodyRing()}</span><span class="body-lens" aria-hidden="true"><span class="body-reading is-current"></span><span class="body-reading is-next"></span></span><button class="body-centre" data-next-body></button></div><span class="sr-only" id="body-announce" aria-live="polite"></span>
    <div class="body-metric-picker health-glass" role="group" aria-label="Body measurement"><span class="selection-pill" aria-hidden="true"></span><span class="selection-pill" aria-hidden="true"></span>${bodyOrder.map(field=>`<button data-body-metric="${field}" aria-pressed="${field===bodyMetric}">${bodyFields[field][2]||bodyFields[field][0]}</button>`).join('')}</div></section>
    <section class="health-tile health-glass body-timeline"><div class="health-section-head"><h2>History</h2><div class="segmented" role="group" aria-label="Body history period"><span class="selection-pill" aria-hidden="true"></span>${[[7,'7D'],[30,'30D'],[90,'3M'],[365,'1Y']].map(([n,name])=>`<button data-body-range="${n}" aria-pressed="${bodyRange===n}" aria-label="${n===365?'Last year':n===90?'Last three months':'Last '+n+' days'}">${name}</button>`).join('')}</div></div>
    <div class="body-readout"><div class="body-readout-text"><p class="body-readout-value"><span id="body-reading"></span><small id="body-unit"></small></p><p class="body-readout-share" id="body-fat-share"></p><p class="body-readout-meta"><output id="body-date"></output><span id="body-change"></span></p></div><div class="body-date-stepper"><button data-body-step="-1" aria-label="Previous measurement">${chevron}</button><button data-body-step="1" aria-label="Next measurement">${chevron}</button></div></div>
    <figure class="body-chart"><svg viewBox="0 0 300 120" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="body-area-fill" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#b69cff" stop-opacity=".24"/><stop offset="1" stop-color="#b69cff" stop-opacity="0"/></linearGradient><linearGradient id="body-guide-fill" gradientUnits="userSpaceOnUse" x1="0" y1="6" x2="0" y2="107"><stop stop-color="#cbb8ff" stop-opacity="0"/><stop offset=".45" stop-color="#cbb8ff" stop-opacity=".55"/><stop offset="1" stop-color="#cbb8ff" stop-opacity=".08"/></linearGradient></defs><path d="M10 32H290M10 97H290" stroke="#ffffff0c"/><path id="body-area" fill="url(#body-area-fill)"/><g id="body-dots" fill="#cdb9ff" fill-opacity=".5"></g><path id="body-line" stroke="#c3a9f7" stroke-width="2" stroke-linecap="round" fill="none"/><text id="body-gap" x="18" y="66" fill="#aeadb9" font-size="9"></text><path id="body-guide" stroke="url(#body-guide-fill)" stroke-width="1.2"/><circle id="body-halo" r="9" fill="#b69cff24"/><circle id="body-point" r="4" fill="#efe7ff" stroke="#3c304f" stroke-width="2"/></svg><input id="body-scrub" type="range" min="0" max="${bodyRange-1}" value="${bodyOffset(bodyDate)}" aria-label="Inspect body measurement date"/><figcaption><span id="body-range-start"></span><span id="body-range-middle"></span><span id="body-range-end"></span></figcaption></figure>
    <dl class="body-stats"><div><dt>Average</dt><dd id="body-average"></dd></div><div><dt>Lowest</dt><dd id="body-low"></dd></div><div><dt>Highest</dt><dd id="body-high"></dd></div></dl><p class="body-coverage" id="body-coverage"></p></section><p class="health-note">Each ring dot is 1% of body weight. Fat and lean mass are calculated from weight and body fat; muscle is part of lean mass.</p>`;
  }
  function inspectBody(index){
    const rows=bodyRows();if(!Number.isInteger(index)||!rows[index])return;bodyDate=rows[index];
    const d=sample('body',bodyDate),unit=bodyFields[bodyMetric][1],change=Math.round((d[bodyMetric]-sample('body',rows[0])[bodyMetric])*10)/10,point=bodyPlot(rows)[index];
    q('#body-reading').textContent=bodyNumber(bodyMetric,d);q('#body-unit').textContent=unit;q('#body-date').textContent=stamp(bodyDate+'T12:00:00',true);q('#body-fat-share').textContent=bodyMetric==='fatMass'?d.fat.toFixed(1)+'% of body weight':'';
    q('#body-change').textContent=index?`${change>0?'+':change<0?'−':'±'}${Math.abs(change).toFixed(1)} ${unit} since ${stamp(rows[0]+'T12:00:00')}`:'First measurement in range';
    q('#body-scrub').value=bodyOffset(bodyDate);q('#body-scrub').setAttribute('aria-valuetext',`${stamp(bodyDate+'T12:00:00')}: ${bodyNumber(bodyMetric,d)} ${unit}`);
    for(const id of ['#body-point','#body-halo']){q(id).setAttribute('cx',point.x);q(id).setAttribute('cy',point.y)}q('#body-guide').setAttribute('d',`M${point.x} 6V107`);
    q('[data-body-step="-1"]').disabled=index===0;q('[data-body-step="1"]').disabled=index===rows.length-1;
    paintHero();
  }
  function paintBody(animate=false){
    const rows=bodyRows();if(!rows.includes(bodyDate))bodyDate=rows.at(-1);
    const points=bodyPlot(rows),trend=bodyTrend(rows,points),smooth=curve(trend),end=options.getDate(),start=dayOffset(end,1-bodyRange),[label,unit]=bodyFields[bodyMetric],values=rows.map(date=>sample('body',date)[bodyMetric]);
    document.querySelectorAll('[data-body-range]').forEach(n=>n.setAttribute('aria-pressed',String(Number(n.dataset.bodyRange)===bodyRange)));positionBodySelection(q('.body-timeline .segmented'));
    q('#body-line').setAttribute('d',smooth);q('#body-dots').setAttribute('fill-opacity',bodyRange>30?.32:.55);q('#body-dots').innerHTML=points.map(p=>`<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${bodyRange>30?1.1:bodyRange>7?1.5:2.2}"/>`).join('');q('#body-area').setAttribute('d',`${smooth}L${trend.at(-1).x} 107L${trend[0].x} 107Z`);q('#body-scrub').max=bodyRange-1;q('.body-chart').setAttribute('aria-label',label+' history. Drag on the chart or use arrow keys to inspect recorded dates.');
    const axis=date=>bodyRange===365?dateLabel(date,{month:'short',year:'numeric'}):stamp(date+'T12:00:00');
    q('#body-range-start').textContent=axis(start);q('#body-range-end').textContent=axis(end);q('#body-range-middle').textContent=bodyRange>=90?axis(dayOffset(start,Math.floor(bodyRange/2))):'Drag to inspect';q('#body-gap').textContent=points[0].x>110?'No earlier measurements':'';
    for(const [id,value] of [['#body-average',values.reduce((a,b)=>a+b,0)/values.length],['#body-low',Math.min(...values)],['#body-high',Math.max(...values)]])q(id).innerHTML=`${value.toFixed(1)}<small>${unit}</small>`;
    q('#body-coverage').textContent=`${rows.length} demonstration measurements · ${stamp(rows[0]+'T12:00:00')}–${stamp(rows.at(-1)+'T12:00:00')}${bodyRange>=90?' · line shows weekly averages':''}`;
    inspectBody(rows.indexOf(bodyDate));
    if(animate)SurfaceMotion.reveal(q('.body-chart>svg'),{x:0,y:6},360);
  }
  // Each of the seven days can be inspected for its own date, recorded minutes and session count; the week's
  // total keeps its own place above the chart, and a day without records says so instead of reading as zero data.
  function weekBins(){return Array.from({length:7},(_,i)=>{const date=new Date(now());date.setHours(0,0,0,0);date.setDate(date.getDate()-6+i);const next=new Date(date);next.setDate(next.getDate()+1);const records=store.history.filter(r=>r.endedAt>=date.getTime()&&r.endedAt<next.getTime());return {date,records,minutes:records.reduce((n,r)=>n+r.elapsed,0)/60000}})}
  const hhmm=ms=>new Date(ms).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'});
  const weekText=b=>`${stamp(b.date,true)} · ${b.records.length?Math.round(b.minutes)+' min · '+b.records.length+(b.records.length===1?' session':' sessions'):'no sessions recorded'}`;
  function inspectWeek(index){
    const bins=weekBins(),scrub=q('#week-scrub');if(!scrub||!bins[index])return;
    weekDay=index;q('#week-readout').textContent=weekText(bins[index]);
    scrub.value=String(index);scrub.setAttribute('aria-valuetext',weekText(bins[index]));
    document.querySelectorAll('[data-week-day]').forEach(n=>n.classList.toggle('is-selected',Number(n.dataset.weekDay)===index));
  }
  function workoutHome(){
    const recent=store.history[0],bins=weekBins(),count=bins.reduce((n,b)=>n+b.records.length,0),mins=Math.round(bins.reduce((n,b)=>n+b.minutes,0)),peak=Math.max(1,...bins.map(b=>b.minutes));
    const groups=[];for(const r of store.history){const day=new Date(r.startedAt);day.setHours(0,0,0,0);if(groups.at(-1)?.key!==day.getTime())groups.push({key:day.getTime(),date:day,items:[]});groups.at(-1).items.push(r)}
    return `<section class="workout-start"><h2 class="workout-start-title">Start a session</h2><div class="workout-kinds">${kinds.map(kind=>{const last=store.history.find(r=>r.kind===kind);return `<button data-setup="${kind}" ${storageFault?'disabled':''}>${activityIcon(kind)}<span><strong>${kind}</strong><small>${last?'Last '+stamp(last.endedAt)+' · '+clock(last.elapsed):'No sessions yet'}</small></span></button>`}).join('')}</div></section>${storageFault?'<p class="health-error">Saved workouts could not be read. Recording is disabled to preserve them.</p>':''}<section class="health-tile workout-week"><div class="health-section-head"><h2>Last 7 days</h2><span>${count} ${count===1?'session':'sessions'} in total</span></div><p class="tile-value">${mins}<small>min</small></p><div class="week-chart"><div class="week-bars" aria-hidden="true">${bins.map((b,i)=>`<span data-week-day="${i}"><i style="height:${Math.max(3,b.minutes/peak*64)}px;opacity:${b.minutes?1:.25}"></i><small>${b.date.toLocaleDateString('en-GB',{weekday:'narrow'})}</small></span>`).join('')}</div><input id="week-scrub" class="chart-input" type="range" min="0" max="6" step="1" value="${weekDay}" aria-label="Inspect a day in the last seven days"/></div><p class="week-readout" id="week-readout"></p></section><details class="health-tile workout-history"><summary><span><h2>Workout history</h2><small>${store.history.length?`${store.history.length} ${store.history.length===1?'session':'sessions'} · latest ${stamp(recent.endedAt)}`:'Completed sessions appear here'}</small></span><span aria-hidden="true">⌄</span></summary><div class="details-body">${store.history.length?groups.map(g=>`<h3 class="history-day">${stamp(g.date,true)}</h3><ol>${g.items.map(r=>`<li><button data-workout-detail="${r.startedAt}"><span>${r.kind}<small>${hhmm(r.startedAt)}${r.targetMs?' · '+r.targetMs/60000+' min target':''}</small></span><strong>${clock(r.elapsed)} <small>↗</small></strong></button></li>`).join('')}</ol>`).join(''):'<p class="health-note">Choose an activity to get started.</p>'}</div></details>`;
  }
  // One configuration flow for every activity: the header carries the name; the panel holds target, GPS and weight.
  function setupView(){
    const outdoor=setup.kind!=='Strength',timed=Boolean(setup.targetMs);
    return `<section class="workout-setup"><form id="workout-setup-form"><div class="setup-panel"><fieldset class="setup-target"><legend>Workout target</legend><div class="setup-segments"><span class="setup-capsule" aria-hidden="true"></span><label><input type="radio" name="target" value="open" ${timed?'':'checked'}/><span>Open workout</span></label><label><input type="radio" name="target" value="time" ${timed?'checked':''}/><span>Time target</span></label></div></fieldset><div id="target-options" class="target-options" ${timed?'':'hidden'}><label class="target-minutes"><span>Duration</span><span class="setup-field"><input id="target-minutes" ${timed?'':'disabled'} type="number" min="1" max="1440" step="1" value="${timed?setup.targetMs/60000:30}" inputmode="numeric"/><small>min</small></span></label></div>${outdoor?`<label class="tracking-option"><span>Track outdoors<small>Phone GPS · route and distance</small></span><input id="workout-track" type="checkbox" role="switch" ${setup.trackLocation?'checked':''}/></label>`:''}<label class="workout-weight"><span>Weight<small>Optional · energy estimate</small></span><span class="setup-field"><input id="workout-weight" type="number" min="20" max="350" step="0.1" value="${setup.weightKg||''}" inputmode="decimal" placeholder="—"/><small>kg</small></span></label></div><p class="setup-help">${outdoor?'GPS is used only during this workout. Turn it off for an indoor session.':'Records active time, pauses and your session history.'}</p><p class="health-error" id="workout-error" role="alert"></p><button class="workout-primary" type="submit">Start ${setup.kind.toLowerCase()}</button></form>${window.OrbitMusic?'<button class="music-access" data-music-connect>Music access</button>':''}</section>`;
  }
  function sessionView(){
    const a=store.active,t=elapsed(a),paused=a.resumedAt===null;
    if(!a.targetMs)timerMode='elapsed';
    return `<section data-started-at="${a.startedAt}" class="workout-live ${timerFocused?'timer-focused':''} ${paused?'is-paused':''}"><div class="workout-live-top"><button class="workout-dot-play" data-dot-play aria-label="Change dot pattern">${HeroDots.pattern(dotPattern)}</button><p class="health-eyebrow" id="session-status">${paused?'Paused':'Recording'}</p></div><div class="workout-time-focus"><button class="timer-dial" data-timer-focus aria-pressed="${timerFocused}" aria-label="${timerFocused?'Show workout details':'Focus on timer and music'}"><svg class="timer-orbit" viewBox="0 0 320 300" aria-hidden="true">${Array.from({length:60},(_,i)=>{const a=(i/60*300-240)*Math.PI/180;return `<circle data-timer-dot="${i}" cx="${160+Math.cos(a)*143}" cy="${150+Math.sin(a)*132}" r="${i%5===0?2.5:1.7}"/>`}).join('')}</svg><span class="timer-center"><span id="timer-label">${timerMode==='remaining'?'Remaining':'Duration'}</span><output class="session-time" id="session-time" tabindex="-1" aria-label="Workout ${timerMode} time"><canvas id="focus-time-dots" aria-hidden="true"></canvas><span class="session-matrix">${HeroDots.markup(clock(timerMode==='remaining'?Math.max(0,a.targetMs-t):t))}</span></output><span class="timer-tap-hint">${timerFocused?'Tap to show details':'Tap to focus'}</span></span></button>${a.targetMs?`<div class="timer-mode-picker" role="group" aria-label="Timer reading"><button data-timer-mode="elapsed" aria-pressed="${timerMode==='elapsed'}">Elapsed</button><button data-timer-mode="remaining" aria-pressed="${timerMode==='remaining'}">Remaining</button></div><progress class="sr-only" id="session-progress" value="${Math.min(t,a.targetMs)}" max="${a.targetMs}" aria-label="Workout target progress"></progress><p class="target-remaining" id="target-remaining">${t>=a.targetMs?'Target reached':clock(a.targetMs-t)+' remaining'} · ${a.targetMs/60000} min target</p>`:'<p class="target-remaining">Open workout</p>'}</div><div class="workout-unconnected" id="live-workout-metrics">${WorkoutDetails.compact(a,t,total(a))}</div><section class="workout-music" aria-label="Music player" hidden></section><div class="session-actions"><button data-session="${paused?'resume':'pause'}">${paused?'Resume':'Pause'}</button><button data-session="finish">Finish</button></div>${window.OrbitWorkouts?.notificationStatus&&window.OrbitWorkouts.notificationStatus()==='disabled'?'<button class="notification-enable" data-notifications>Enable live notification</button>':''}</section>`;
  }
  function record(){return workoutRecord==='active'?store.active:store.history.find(s=>s.startedAt===workoutRecord)}
  function recordView(){const s=record();return s?`<div id="workout-record-body">${WorkoutDetails.view(s,elapsed(s),total(s),workoutChart)}</div>`:''}
  function paintMetrics(){
    if(page!=='workouts')return;
    if(workoutRecord!==null&&record()){const focused=document.activeElement?.dataset?.workoutChart;q('#workout-record-body').innerHTML=WorkoutDetails.view(record(),elapsed(record()),total(record()),workoutChart);if(focused)q('[data-workout-chart="'+focused+'"]').focus({preventScroll:true})}
    else if(store.active&&q('#live-workout-metrics')){const root=q('#live-workout-metrics'),focused=root.contains(document.activeElement)?document.activeElement:null,selector=focused?.hasAttribute('data-tracking')?'[data-tracking]':focused?.hasAttribute('data-workout-detail')?'[data-workout-detail]':null;root.innerHTML=WorkoutDetails.compact(store.active,elapsed(store.active),total(store.active));if(selector)(root.querySelector(selector)||root.querySelector('[data-workout-detail]')).focus({preventScroll:true})}
  }
  function paintLive(){
    const live=page==='workouts'&&workoutRecord===null&&store.active&&q('.workout-live');if(!live||live.dataset.startedAt!==String(store.active.startedAt))return false;
    const paused=store.active.resumedAt===null,button=live.querySelector('[data-session="pause"],[data-session="resume"]');
    live.classList.toggle('is-paused',paused);q('#session-status').textContent=paused?'Paused':'Recording';button.dataset.session=paused?'resume':'pause';button.textContent=paused?'Resume':'Pause';paintTimer();paintMetrics();return true;
  }
  function render(){
    if(!page)return;
    const body=q('#health-content'),scroll=q('#health-scroll').scrollTop,focus=page==='workouts'&&workoutRecord===null&&(store.active||setup||countdown!==null);
    q('#health-title').textContent=page==='workouts'?(record()?.kind||store.active?.kind||setup?.kind||titles[page]):titles[page];
    q('#health-back').setAttribute('aria-label',workoutRecord!==null?'Back to workouts':countdown!==null?'Cancel countdown':setup?'Back to workouts':page==='sleep'?'Back to sleep summary':'Back to dashboard');
    q('#health-source').textContent=page==='workouts'?'':page==='sleep'?'Demo night · stage timing illustration':'Demo data · '+stamp(options.getDate()+'T12:00:00',true);
    q('#health-source').hidden=page==='workouts';q('#health-page').classList.toggle('workout-focus',Boolean(focus));q('#health-page').dataset.page=page;q('#health-page').dataset.workoutScreen=page!=='workouts'?'':workoutRecord!==null?'record':countdown!==null?'countdown':store.active?'active':setup?'setup':'picker';
    countDots?.stop();countDots=null;WorkoutFocus.dispose();MusicPlayer.stop();
    body.innerHTML=page==='body'?bodyView():page==='overview'?overview():page==='sleep'?sleepView():workoutRecord!==null?recordView():countdown!==null?`<div class="workout-countdown"><p class="countdown-heading">${setup.kind}</p><div class="countdown-play"><canvas id="countdown-dots" aria-hidden="true"></canvas><output class="sr-only" id="workout-count" aria-live="polite">${countdown}</output></div><p class="countdown-invitation">Touch the dots</p><div class="countdown-stages" aria-hidden="true">${[3,2,1].map(n=>`<i data-count-stage="${n}" class="${countdown<=n?'lit':''}"></i>`).join('')}</div><button data-cancel-countdown>Cancel</button></div>`:store.active?sessionView():setup?setupView():workoutHome();
    if(page==='body')paintBody();
    if(q('#week-scrub'))inspectWeek(Math.max(0,Math.min(6,weekDay)));
    if(page==='workouts'&&countdown!==null)countDots=HeroDots.mount(q('#countdown-dots'),countdown);
    if(page==='workouts'&&store.active&&workoutRecord===null){paintTimer();if(timerFocused)WorkoutFocus.attach(q('.workout-live'),true);MusicPlayer.mount(q('.workout-music'),timerFocused)}focusChrome();
    q('#health-scroll').scrollTop=scroll;frostHead();
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
  function refresh(){const before=JSON.stringify(store),structure=JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length]);if(window.OrbitWorkouts)readStore();if(before!==JSON.stringify(store)){if(structure!==JSON.stringify([store.active?.startedAt,store.active?.resumedAt,store.history.length])){if(workoutRecord==='active'&&!store.active)workoutRecord=store.history[0]?.startedAt??null;if(!paintLive())render();schedule()}else paintMetrics();options.onChange()}}
  function paintTimer(){if(!store.active||!q('#session-time'))return;const t=elapsed(store.active),target=store.active.targetMs||0,reading=clock(timerMode==='remaining'?Math.max(0,target-t):t),ratio=target?Math.min(1,t/target):t%60000/60000;if(q('#session-time').dataset.reading!==reading){const slot=q('#session-time .session-matrix')||q('#session-time');let svg=slot.querySelector('.matrix-reading');if(svg)svg.outerHTML=HeroDots.markup(reading);else slot.insertAdjacentHTML('beforeend',HeroDots.markup(reading));WorkoutFocus.reading(reading);q('#session-time').dataset.reading=reading;q('#session-time').setAttribute('aria-label',`${timerMode==='remaining'?'Remaining':'Duration'}: ${reading}`)}document.querySelectorAll('[data-timer-dot]').forEach(n=>n.classList.toggle('lit',Number(n.dataset.timerDot)/60<ratio));if(target){q('#session-progress').value=Math.min(t,target);q('#target-remaining').textContent=(t>=target?'Target reached':clock(Math.max(0,target-t))+' remaining')+' · '+target/60000+' min target'}}
  function tick(){refresh();if(page==='workouts'){paintTimer();if(workoutRecord!==null&&record())WorkoutDetails.updateTimes(q('#workout-record-body'),record(),elapsed(record()),total(record()));else if(store.active&&q('#live-workout-metrics'))WorkoutDetails.updateCompact(q('#live-workout-metrics'),store.active,elapsed(store.active),total(store.active))}options.onTick()}
  function schedule(){clearInterval(timer);timer=0;if(store.active&&!document.hidden)timer=setInterval(tick,1000)}
  // Focus has its own way back to the workout view, mirroring the back button; the small player's cover opens focus.
  // While the two views move into each other WorkoutFocus fades the chevron; a freshly rendered page sets it here.
  function focusChrome(){const button=q('#health-minimize'),show=page==='workouts'&&workoutRecord===null&&Boolean(store.active)&&timerFocused;if(button.hidden!==!show)button.hidden=!show}
  // Content scrolling under the Body header gets a soft frosted edge; at the top of the page the header stays clear.
  function frostHead(){const frost=q('.health-head-frost');if(frost)frost.style.opacity=String(Math.min(1,q('#health-scroll').scrollTop/24))}
  function setFocus(value){const live=q('.workout-live');if(!live||timerFocused===value)return;timerFocused=value;WorkoutFocus.toggle(live,value)}
  function init(config){
    options=config;MusicPlayer.init(config.notice);readStore();q('#health-back').addEventListener('click',close);q('#health-minimize').addEventListener('click',()=>setFocus(false));
    WorkoutFocus.bind(q('#health-page'),{target:value=>{timerFocused=value}});
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
      else if(b.dataset.bodyMetric||b.hasAttribute('data-next-body')){if(event.detail&&performance.now()-lens.dragged<400)return;if(b.dataset.bodyMetric)goBody(b.dataset.bodyMetric);else goBody(bodyNeighbour(bodyMetric,1),1)}
      else if(b.dataset.bodyRange){const next=Number(b.dataset.bodyRange);if(![7,30,90,365].includes(next)||next===bodyRange)return;bodyRange=next;paintBody(true)}
      else if(b.dataset.bodyStep)inspectBody(bodyRows().indexOf(bodyDate)+Number(b.dataset.bodyStep));
      else if(b.dataset.nightDate){const date=options.dates[options.dates.indexOf(sleepDate)+Number(b.dataset.nightDate)];if(date){sleepDate=date;sleepMinute=0;render();q('[data-night-date="'+b.dataset.nightDate+'"]').focus({preventScroll:true})}}
      else if(b.dataset.nightPart){const night=options.getDaily(sleepDate).night;sleepMinute=SleepTimeline.inspect(night,SleepTimeline.part(night,sleepMinute,Number(b.dataset.nightPart)))}
      else if(b.dataset.nightStage){const night=options.getDaily(sleepDate).night;sleepMinute=SleepTimeline.inspect(night,SleepTimeline.stageMinute(night,b.dataset.nightStage))}
      else if(b.dataset.timerMode){timerMode=b.dataset.timerMode;document.querySelectorAll('[data-timer-mode]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.timerMode===timerMode)));q('#timer-label').textContent=timerMode==='remaining'?'Remaining':'Duration';paintTimer()}
      else if(b.hasAttribute('data-timer-focus'))setFocus(!timerFocused);
      else if(b.hasAttribute('data-music-expand'))setFocus(true);
      else if(b.hasAttribute('data-dot-play')){dotPattern=(dotPattern+1)%3;HeroDots.reshape(b,dotPattern)}
      else if(b.dataset.oxygenDay){const date=b.dataset.oxygenDay;if(options.dates.includes(date)){q('#oxygen-date').textContent=stamp(date+'T12:00:00')+' · '+sample('oxygen',date).value+'%';document.querySelectorAll('[data-oxygen-day]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.oxygenDay===date)))}}
      else if(b.dataset.open)open(b.dataset.open,b);
      else if(b.hasAttribute('data-cancel-countdown'))close();
      else if(b.hasAttribute('data-notifications'))window.OrbitWorkouts?.enableNotifications();
    });
    q('#health-content').addEventListener('change',event=>{if(event.target.name==='target'){const open=event.target.value==='time';q('#target-minutes').disabled=!open;SurfaceMotion.expand(q('#target-options'),open)}});
    q('#health-content').addEventListener('input',event=>{if(event.target.id==='body-scrub'){const day=Number(event.target.value),rows=bodyRows();if(Number.isInteger(day)&&day>=0&&day<bodyRange)inspectBody(rows.reduce((best,date,i)=>Math.abs(bodyOffset(date)-day)<Math.abs(bodyOffset(rows[best])-day)?i:best,0))}if(event.target.id==='week-scrub'){const day=Number(event.target.value);if(Number.isInteger(day)&&day>=0&&day<7)inspectWeek(day)}if(event.target.id==='night-scrub')sleepMinute=SleepTimeline.inspect(options.getDaily(sleepDate).night,Number(event.target.value))});
    q('#health-content').addEventListener('submit',event=>{if(event.target.id!=='workout-setup-form')return;event.preventDefault();const time=q('input[name="target"]:checked').value==='time',minutes=Number(q('#target-minutes').value);if(time&&(!Number.isInteger(minutes)||minutes<1||minutes>1440)){q('#workout-error').textContent='Choose between 1 and 1,440 minutes.';return}const weightKg=Number(q('#workout-weight').value||0),trackLocation=Boolean(q('#workout-track')?.checked);if(weightKg!==0&&(!Number.isFinite(weightKg)||weightKg<20||weightKg>350)){q('#workout-error').textContent='Enter a weight from 20 to 350 kg, or leave it empty.';return}startCountdown(setup.kind,time?minutes*60000:0,{trackLocation,weightKg})});
    document.addEventListener('visibilitychange',()=>{if(document.hidden&&countdown!==null){cancelCountdown();render()}schedule();if(!document.hidden)tick()});
    const alignBody=()=>{if(page==='body'){placePill();positionBodySelection(q('.body-timeline .segmented'))}};window.addEventListener('resize',alignBody);document.fonts?.ready.then(alignBody);
    const content=q('#health-content');for(const [type,fn] of [['pointerdown',pickDown],['pointerdown',lensDown],['pointerdown',segDown],['pointermove',pickMove],['pointermove',lensMove],['pointermove',segMove]])content.addEventListener(type,fn);for(const type of ['pointerup','pointercancel','lostpointercapture']){content.addEventListener(type,lensUp);content.addEventListener(type,pickUp);content.addEventListener(type,segUp)}
    content.addEventListener('click',event=>{if(event.detail&&event.target.closest('.setup-segments')&&performance.now()-seg.dragged<400){event.preventDefault();event.stopPropagation()}},true);
    content.addEventListener('keydown',event=>{if(!event.target.closest?.('[data-body-dial]')||!['ArrowLeft','ArrowRight'].includes(event.key))return;const dir=event.key==='ArrowRight'?1:-1;event.preventDefault();goBody(bodyNeighbour(bodyMetric,dir),dir)});
    q('#health-scroll').addEventListener('scroll',frostHead,{passive:true});
    window.addEventListener('focus',refresh);schedule();
  }
  return {init,open,close,render,live,action,sample,elapsed,clock,validSession,validTarget,refresh,startCountdown,get page(){return page},get state(){return store}};
})();
