/* Recorded stage intervals, including explicit unrecorded gaps. */
'use strict';
const SleepTimeline=(()=>{
  const stages={awake:['Awake','#ff669c'],rem:['REM','#c2adff'],light:['Light','#875aff'],deep:['Deep','#512cbe'],sleeping:['Sleep','#9176b3'],unknown:['Unknown','#5b5961']};
  const time=value=>new Date(value).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit',hour12:false});
  const q=selector=>document.querySelector(selector);
  function valid(night){return night&&Number.isFinite(night.start)&&Number.isFinite(night.end)&&night.end>night.start&&Array.isArray(night.segments)&&night.segments.length>0&&night.segments.every((s,i)=>Object.hasOwn(stages,s.stage)&&Number.isFinite(s.start)&&Number.isFinite(s.end)&&s.end>s.start&&s.start===(i?night.segments[i-1].end:night.start)&&s.end<=night.end)&&night.segments.at(-1).end===night.end}
  function locate(night,minute){const at=night.start+Math.max(0,Math.min((night.end-night.start)/60000-1,minute))*60000;return Math.max(0,night.segments.findIndex(s=>at>=s.start&&at<s.end))}
  function totals(night){return Object.fromEntries(Object.keys(stages).map(stage=>[stage,night.segments.filter(s=>s.stage===stage).reduce((sum,s)=>sum+(s.end-s.start)/60000,0)]))}
  function selection(night,index){const s=night.segments[index];return `<div class="night-selection-label"><i style="background:${stages[s.stage][1]}"></i><h2>${stages[s.stage][0]}</h2><strong>${duration((s.end-s.start)/60000)}</strong></div><p class="night-selection-time">${time(s.start)} – ${time(s.end)}</p>`}
  function view(night,minute=0){
    if(!valid(night))return '<p class="health-note">Stage timing is unavailable for this night.</p>';
    const all=totals(night),asleep=all.light+all.deep+all.rem+all.sleeping,span=(night.end-night.start)/60000,index=locate(night,minute),selected=night.segments[index];
    const x=t=>40+(t-night.start)/(night.end-night.start)*290,shown=Object.keys(stages).filter(k=>all[k]>0),y=stage=>({awake:16,rem:48,light:80,deep:112,sleeping:80,unknown:132})[stage];
    const axis=Array.from({length:5},(_,i)=>night.start+(night.end-night.start)*i/4);
    const bars=night.segments.map((s,i)=>{
      const left=x(s.start),right=x(s.end),top=y(s.stage),previous=night.segments[i-1],gap=s.stage==='unknown';
      return `${previous&&previous.stage!=='unknown'&&!gap?`<path d="M${left} ${y(previous.stage)}V${top}" stroke="${stages[s.stage][1]}" stroke-width="1"/>`:''}<rect data-night-segment="${i}" data-night-stage-name="${s.stage}" x="${left}" y="${top-6}" width="${Math.max(.5,right-left)}" height="12" rx="${right-left>5?2:0}" fill="${stages[s.stage][1]}" opacity="1"/>`;
    }).join('');
    return `<header class="sleep-summary"><span>Time asleep</span><p>${asleep?`<strong>${Math.floor(asleep/60)}</strong>h <strong>${Math.round(asleep%60)}</strong>m`:'<strong>—</strong>'}</p><small>${time(night.start)} – ${time(night.end)}</small></header>
      <section class="sleep-night"><div class="health-section-head"><h2>Sleep stages</h2><span>${shown.includes('unknown')?'Includes unrecorded time':''}</span></div>
      <figure class="night-chart" aria-label="Sleep stages from ${time(night.start)} to ${time(night.end)}. Select a time to hear the stage and exact interval."><svg viewBox="0 0 340 157" preserveAspectRatio="none" aria-hidden="true">${Object.entries(stages).filter(([stage])=>shown.includes(stage)).map(([stage,[label]])=>`<text x="0" y="${y(stage)+3}" fill="#aaa6b1" font-size="8">${label}</text><path d="M40 ${y(stage)}H330" stroke="#ffffff1a" stroke-dasharray="1 4"/>`).join('')}${bars}${axis.map((t,i)=>`<text x="${x(t)}" y="151" text-anchor="${i===0?'start':i===4?'end':'middle'}" fill="#aaa6b1" font-size="8">${time(t)}</text>`).join('')}</svg><input id="night-scrub" type="range" min="0" max="${span-1}" step="1" value="${minute}" aria-label="Inspect time during the night" aria-valuetext="${stages[selected.stage][0]}, ${time(selected.start)} to ${time(selected.end)}, ${duration((selected.end-selected.start)/60000)}"/></figure>
      <div class="night-selected"><button data-night-part="-1" aria-label="Previous sleep interval" ${index===0?'disabled':''}>‹</button><div id="night-selection" data-index="${index}" aria-live="polite">${selection(night,index)}</div><button data-night-part="1" aria-label="Next sleep interval" ${index===night.segments.length-1?'disabled':''}>›</button></div>
      <div class="night-stage-totals glass-track blob-track" style="--stage-count:${shown.length}" role="group" aria-label="Explore a sleep stage"><span class="selection-pill glass-indicator" aria-hidden="true"></span>${Object.entries(stages).filter(([stage])=>shown.includes(stage)).map(([stage,[label,color]])=>`<button class="blob-option" data-night-stage="${stage}" aria-pressed="${stage===selected.stage}"><i style="background:${color}"></i><span>${label}<strong>${duration(all[stage])}</strong></span></button>`).join('')}</div></section><p class="sleep-source">Samsung Health${all.unknown?' · Some stage timings are unavailable':''}</p>`;
  }
  function inspect(night,minute){
    if(!valid(night)||!Number.isFinite(minute))return 0;
    minute=Math.max(0,Math.min((night.end-night.start)/60000-1,Math.round(minute)));const index=locate(night,minute),s=night.segments[index];
    q('#night-scrub').value=minute;q('#night-scrub').setAttribute('aria-valuetext',`${time(night.start+minute*60000)}: ${stages[s.stage][0]}, ${time(s.start)} to ${time(s.end)}, ${duration((s.end-s.start)/60000)}`);
    if(q('#night-selection').dataset.index!==String(index)){q('#night-selection').innerHTML=selection(night,index);q('#night-selection').dataset.index=String(index)}q('[data-night-part="-1"]').disabled=index===0;q('[data-night-part="1"]').disabled=index===night.segments.length-1;
    document.querySelectorAll('[data-night-segment]').forEach(n=>{n.setAttribute('opacity','1');n.setAttribute('stroke','none')});
    document.querySelectorAll('[data-night-stage]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.nightStage===s.stage)));return minute;
  }
  function part(night,minute,step){const next=night.segments[locate(night,minute)+step];return next?(next.start-night.start)/60000:minute}
  function stageMinute(night,stage){if(!valid(night))return 0;const s=night.segments.find(s=>s.stage===stage);return s?(s.start-night.start)/60000:0}
  return {valid,view,inspect,part,stageMinute,locate,totals};
})();
