/* Recorded workout readings. Missing sensors stay missing; estimates say what they use. */
'use strict';
const WorkoutDetails=(()=>{
  const finite=n=>typeof n==='number'&&Number.isFinite(n),positive=n=>finite(n)&&n>=0;
  const optional=(n,check=positive)=>n===undefined||n===null||check(n);
  const altitude=n=>finite(n)&&n>=-12000&&n<=100000;
  const speed=n=>positive(n)&&n<=100;
  const states=['off','permission','searching','tracking','paused','unavailable','error','finished'];
  const met={Walking:3.5,Running:7.5,Cycling:4,Strength:3.5};
  const clock=ms=>{const s=Math.floor(Math.max(0,ms)/1000),h=Math.floor(s/3600);return(h?h+':':'')+String(Math.floor(s/60)%60).padStart(2,'0')+':'+String(s%60).padStart(2,'0')};
  const pace=mps=>mps>0?clock(1000000/mps):'—';
  function valid(s){
    if(s.weightKg!==undefined&&!(s.weightKg===0||finite(s.weightKg)&&s.weightKg>=20&&s.weightKg<=350))return false;
    if(s.trackLocation!==undefined&&typeof s.trackLocation!=='boolean'||!optional(s.totalMs))return false;
    const m=s.metrics;if(m===undefined)return true;
    return m&&states.includes(m.state)&&positive(m.distanceM)&&speed(m.maxSpeedMps)&&optional(m.speedMps,speed)&&optional(m.accuracyM)&&optional(m.altitudeMinM,altitude)&&optional(m.altitudeMaxM,altitude)&&Array.isArray(m.points)&&m.points.length<=4096&&m.points.every(p=>p&&finite(p.lat)&&Math.abs(p.lat)<=90&&finite(p.lon)&&Math.abs(p.lon)<=180&&positive(p.elapsedMs)&&optional(p.altitudeM,altitude)&&optional(p.speedMps,speed)&&typeof p.breakBefore==='boolean');
  }
  function readings(s,activeMs){
    const m=s.metrics,hasFix=Boolean(m?.points?.length),distance=hasFix?m.distanceM:null,average=distance!==null&&activeMs>0?distance/(activeMs/1000):null;
    // ponytail: broad activity MET estimate; use measured effort for individual energy estimates.
    const calories=s.weightKg?met[s.kind]*s.weightKg*3.5/200*(activeMs/60000):null;
    return {distance,average,calories,max:hasFix?m.maxSpeedMps:null};
  }
  const metric=(label,value,unit='')=>`<div class="workout-stat" data-workout-value="${label.toLowerCase().replaceAll(' ','-')}"><dt>${label}</dt><dd><span>${value}</span><small>${unit}</small></dd></div>`;
  function status(s){return {off:'Time only',permission:'Precise location needed',searching:'Finding GPS',tracking:'Phone GPS',paused:'GPS paused',unavailable:'GPS unavailable',error:'Tracking needs attention',finished:'Phone GPS recording'}[s.metrics?.state]||'Time only'}
  function compact(s,activeMs){const r=readings(s,activeMs);return `<dl class="workout-stat-grid">${metric('Distance',r.distance===null?'—':(r.distance/1000).toFixed(2),'km')}${metric('Average pace',pace(r.average),'/km')}${metric('Average speed',r.average===null?'—':(r.average*3.6).toFixed(1),'km/h')}${metric('Energy estimate',r.calories===null?'—':Math.round(r.calories),'kcal')}</dl><div class="tracking-line"><span><i class="${s.metrics?.state==='tracking'?'tracking':''}"></i>${status(s)}</span><button data-workout-detail="active">All details <span>↗</span></button></div>${s.trackLocation&&['permission','unavailable','error'].includes(s.metrics?.state)&&window.OrbitWorkouts?.enableTracking?'<button class="notification-enable" data-tracking>Enable GPS tracking</button>':''}`}
  function route(points){
    if(points.length<2)return '<div class="workout-chart-empty">Your route appears after a few GPS readings.</div>';
    const first=points[0],radians=Math.PI/180,xy=points.map(p=>({x:((p.lon-first.lon+540)%360-180)*Math.cos(first.lat*radians),y:-(p.lat-first.lat),breakBefore:p.breakBefore}));
    const xs=xy.map(p=>p.x),ys=xy.map(p=>p.y),minX=Math.min(...xs),minY=Math.min(...ys),dx=Math.max(...xs)-minX,dy=Math.max(...ys)-minY,scale=Math.min(264/Math.max(dx,.000001),142/Math.max(dy,.000001));
    const map=p=>({x:160+(p.x-minX-dx/2)*scale,y:96+(p.y-minY-dy/2)*scale}),mapped=xy.map(map),path=mapped.map((p,i)=>(i===0||xy[i].breakBefore?'M':'L')+p.x.toFixed(2)+' '+p.y.toFixed(2)).join(' '),start=mapped[0],end=mapped.at(-1);
    return `<svg viewBox="0 0 320 192" role="img" aria-label="Recorded route, north upwards"><path d="M20 48H300M20 96H300M20 144H300M80 18V178M160 18V178M240 18V178" stroke="#ffffff07"/><text x="294" y="21" fill="#bbb4c4" font-size="9">N ↑</text><path d="${path}" fill="none" stroke="#cbb8ed" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/><circle cx="${start.x}" cy="${start.y}" r="5" fill="#232327" stroke="#ece4f5" stroke-width="2"/><circle cx="${end.x}" cy="${end.y}" r="5" fill="#ece4f5" stroke="#232327" stroke-width="2"/></svg>`;
  }
  function plot(points,mode,activeMs){
    const field=mode==='speed'?'speedMps':'altitudeM',validPoints=points.filter(p=>finite(p[field]));
    if(validPoints.length<2)return `<div class="workout-chart-empty">${mode==='speed'?'Speed':'Elevation'} appears when enough GPS readings are available.</div>`;
    const values=validPoints.map(p=>p[field]*(mode==='speed'?3.6:1)),min=mode==='speed'?0:Math.floor(Math.min(...values)),max=Math.max(min+1,...values),span=max-min,time=Math.max(1,activeMs,validPoints.at(-1).elapsedMs);
    let previous=-1;const path=points.map((p,i)=>{if(!finite(p[field]))return '';const x=28+p.elapsedMs/time*270,y=153-(p[field]*(mode==='speed'?3.6:1)-min)/span*121,move=previous<0||p.breakBefore||i!==previous+1;previous=i;return(move?'M':'L')+x.toFixed(2)+' '+y.toFixed(2)}).join(' ');
    return `<svg viewBox="0 0 320 192" role="img" aria-label="${mode==='speed'?'Speed in kilometres per hour':'Elevation in metres'} over active workout time"><path d="M28 32H298M28 92H298M28 153H298" stroke="#ffffff12"/><text x="28" y="21" fill="#c5bdce" font-size="9">${max.toFixed(1)} ${mode==='speed'?'km/h':'m'}</text><path d="${path}" fill="none" stroke="#cbb8ed" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><text x="28" y="173" fill="#b4abbe" font-size="9">00:00</text><text x="298" y="173" text-anchor="end" fill="#b4abbe" font-size="9">${clock(time)}</text></svg>`;
  }
  function chart(s,activeMs,mode){const points=s.metrics?.points||[];return `<div class="workout-chart-tabs" role="group" aria-label="Workout chart">${[['route','Route'],['speed','Speed'],['altitude','Elevation']].map(([key,label])=>`<button data-workout-chart="${key}" aria-pressed="${mode===key}">${label}</button>`).join('')}</div><figure class="workout-record-chart">${mode==='route'?route(points):plot(points,mode,activeMs)}<figcaption>${mode==='route'?'Recorded on this phone · no street map':'Active time · gaps in GPS are left open'}</figcaption></figure>`}
  function view(s,activeMs,totalMs,mode='route'){
    const r=readings(s,activeMs),m=s.metrics,paused=Math.max(0,totalMs-activeMs),date=new Date(s.startedAt),active=s.resumedAt!==undefined;
    return `<section class="workout-result"><p class="health-eyebrow">${active?'In progress':'Workout saved'} · ${date.toLocaleDateString('en-GB',{day:'numeric',month:'short',year:'numeric'})}</p><div class="workout-result-value"><span role="img" aria-label="${r.distance===null?clock(activeMs)+' active time':(r.distance/1000).toFixed(2)+' kilometres'}" data-workout-hero="${r.distance===null?'time':'distance'}">${HeroDots.markup(r.distance!==null?(r.distance/1000).toFixed(2):clock(activeMs))}</span><span>${r.distance!==null?'km':'active time'}</span></div><p>${date.toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'})}${s.endedAt?' — '+new Date(s.endedAt).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'}):''} <span>· ${status(s)}</span></p></section>
      <section class="health-tile workout-detail-group"><h2>Time</h2><dl class="workout-stat-grid">${metric('Active',clock(activeMs))}${metric('Total',clock(totalMs))}${metric('Paused',clock(paused))}${metric('Target',s.targetMs?s.targetMs/60000:'Open',s.targetMs?'min':'')}</dl>${s.targetMs?`<div class="workout-target-track"><i style="width:${Math.min(100,activeMs/s.targetMs*100)}%"></i></div><p class="workout-target-copy">${activeMs>=s.targetMs?'Target reached':clock(s.targetMs-activeMs)+' to target'} · ${Math.round(activeMs/s.targetMs*100)}%</p>`:''}</section>
      ${s.trackLocation||m?.points?.length?`<section class="health-tile workout-route-group">${chart(s,activeMs,mode)}</section><section class="health-tile workout-detail-group"><h2>Movement</h2><dl class="workout-stat-grid">${metric('Average speed',r.average===null?'—':(r.average*3.6).toFixed(1),'km/h')}${metric('Maximum speed',r.max===null?'—':(r.max*3.6).toFixed(1),'km/h')}${metric('Average pace',pace(r.average),'/km')}${metric('Best pace',pace(r.max),'/km')}${metric('Lowest elevation',m?.altitudeMinM==null?'—':Math.round(m.altitudeMinM),'m')}${metric('Highest elevation',m?.altitudeMaxM==null?'—':Math.round(m.altitudeMaxM),'m')}</dl></section>`:''}
      <section class="health-tile workout-energy"><div><h2>Energy estimate</h2><p class="workout-energy-number"><span>${r.calories===null?'—':Math.round(r.calories)}</span><small>kcal</small></p></div><p>${r.calories===null?'Add your weight before a workout to see an estimate.':`Based on ${s.weightKg} kg, active time and a general ${s.kind.toLowerCase()} intensity (${met[s.kind]} MET). Includes resting energy during active time. Actual energy use varies with effort.`}</p></section><p class="health-note workout-source-note">${s.trackLocation?'Distance, pace and elevation use phone GPS. Poor or interrupted fixes are excluded. ':''}Only active time contributes to the energy estimate.</p>`;
  }
  function setValue(root,name,value){const node=root.querySelector('[data-workout-value="'+name+'"] dd>span');if(node)node.textContent=value}
  function updateCompact(root,s,activeMs){const r=readings(s,activeMs);setValue(root,'average-pace',pace(r.average));setValue(root,'average-speed',r.average===null?'—':(r.average*3.6).toFixed(1));setValue(root,'energy-estimate',r.calories===null?'—':Math.round(r.calories))}
  function updateTimes(root,s,activeMs,totalMs){
    setValue(root,'active',clock(activeMs));setValue(root,'total',clock(totalMs));setValue(root,'paused',clock(Math.max(0,totalMs-activeMs)));updateCompact(root,s,activeMs);
    const hero=root.querySelector('[data-workout-hero="time"]'),reading=clock(activeMs);if(hero&&hero.dataset.reading!==reading){hero.innerHTML=HeroDots.markup(reading);hero.dataset.reading=reading;hero.setAttribute('aria-label',reading+' active time')}
    const energy=root.querySelector('.workout-energy-number>span'),r=readings(s,activeMs);if(energy)energy.textContent=r.calories===null?'—':Math.round(r.calories);
    if(s.targetMs){root.querySelector('.workout-target-track i').style.width=Math.min(100,activeMs/s.targetMs*100)+'%';root.querySelector('.workout-target-copy').textContent=(activeMs>=s.targetMs?'Target reached':clock(s.targetMs-activeMs)+' to target')+' · '+Math.round(activeMs/s.targetMs*100)+'%'}
  }
  return {valid,readings,compact,view,chart,clock,pace,status,updateCompact,updateTimes};
})();
