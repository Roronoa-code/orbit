/* One clock-time timeline per day; session gaps and conflicting records remain explicit. */
'use strict';
const SleepTimeline=(()=>{
  const stages={awake:['Awake','#ff8098'],rem:['REM','#63c8ed'],light:['Light','#488bfa'],deep:['Deep','#6654db'],sleeping:['Sleep','#91a2c3'],unknown:['Unknown','#92909b'],unrecorded:['Not recorded','#66636e']};
  const time=value=>new Date(value).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit',hour12:false});
  const duration=minutes=>{const n=Math.round(minutes),h=Math.floor(n/60);return `${h?h+'h ':''}${n%60}min`};
  const q=selector=>document.querySelector(selector);
  function valid(night){return night&&Number.isFinite(night.start)&&Number.isFinite(night.end)&&night.end>night.start&&Array.isArray(night.segments)&&night.segments.length>0&&night.segments.every((s,i)=>Object.hasOwn(stages,s.stage)&&Number.isFinite(s.start)&&Number.isFinite(s.end)&&s.end>s.start&&s.start===(i?night.segments[i-1].end:night.start)&&s.end<=night.end)&&night.segments.at(-1).end===night.end}
  function locate(night,minute){const at=night.start+Math.max(0,Math.min((night.end-night.start)/60000-.001,minute))*60000;return Math.max(0,night.segments.findIndex(s=>at>=s.start&&at<s.end))}
  function totals(night){return Object.fromEntries(Object.keys(stages).map(stage=>[stage,night.segments.filter(s=>s.stage===stage).reduce((sum,s)=>sum+(s.end-s.start)/60000,0)]))}
  function selection(night,index){const s=night.segments[index];return `<span><i style="background:${stages[s.stage][1]}"></i>${stages[s.stage][0]}<strong>${duration((s.end-s.start)/60000)}</strong></span><small>${time(s.start)} – ${time(s.end)}</small>`}
  function view(night,minute=0,navigation=''){
    const available=valid(night),all=available?totals(night):{},asleep=Math.round((all.light||0)+(all.deep||0)+(all.rem||0)+(all.sleeping||0));
    const summary=`<header class="sleep-summary"><span>TIME ASLEEP</span><p>${available&&['awake','light','deep','rem','sleeping'].some(k=>all[k]>0)?`<strong>${Math.floor(asleep/60)}</strong>h <strong>${asleep%60}</strong>min`:'<strong>—</strong>'}</p>${navigation}</header>`;
    if(!available)return summary+'<p class="sleep-empty">No sleep recorded for this day.</p>';
    const index=locate(night,minute),selected=night.segments[index],span=(night.end-night.start)/60000;
    const lanes=['awake','rem','light','deep',...['sleeping','unknown'].filter(k=>all[k]>0)],height=lanes.length*43;
    // Clock-aligned margins give natural ticks without stretching a short nap across a whole day.
    const hour=3600000,start=new Date(night.start),end=new Date(night.end);
    start.setMinutes(0,0,0);end.setHours(end.getHours()+1,0,0,0);
    const x=t=>8+(t-start.getTime())/(end-start)*324,y=stage=>lanes.indexOf(stage)*43+27;
    const tickHours=Math.max(1,Math.ceil((end-start)/hour/4)),axis=[];
    for(let at=+start;at<=+end;at+=tickHours*hour)axis.push(at);
    const bars=night.segments.map((s,i)=>{
      if(s.stage==='unrecorded')return '';
      const left=x(s.start),width=x(s.end)-left,top=y(s.stage),previous=night.segments[i-1];
      const connected=previous&&!['unrecorded','unknown'].includes(previous.stage)&&s.stage!=='unknown'&&previous.stage!==s.stage;
      return `${connected?`<path d="M${left} ${y(previous.stage)}V${top}" stroke="${stages[s.stage][1]}" stroke-opacity=".16" stroke-width="1.5"/>`:''}<rect data-night-segment="${i}" x="${left}" y="${top-7}" width="${Math.max(.35,width)}" height="14" rx="${Math.min(3,width/2)}" fill="${stages[s.stage][1]}" opacity="1"/>`;
    }).join('');
    return summary+`<section class="sleep-night" aria-label="Sleep stages">
      <div id="night-selection" class="night-inspection" data-index="${index}" aria-live="polite"><span>Sleep stages</span><small>${time(night.start)} – ${time(night.end)}</small></div>
      <figure class="night-chart" aria-label="Sleep stages. Hold and slide to inspect the recorded intervals."><svg viewBox="0 0 340 ${height+23}" aria-hidden="true">
      ${axis.map(t=>`<path d="M${x(t)} 0V${height}" stroke="#ffffff10" stroke-dasharray="2 3"/><text x="${x(t)}" y="${height+17}" text-anchor="${t===axis[0]?'start':'middle'}" fill="#939199" font-size="11">${time(t)}</text>`).join('')}
      ${lanes.map((stage,i)=>`<text x="8" y="${i*43+12}" fill="#b6b3bc" font-size="12">${stages[stage][0]}</text><path d="M8 ${(i+1)*43}H332" stroke="#ffffff12"/>`).join('')}${bars}
      <path id="night-cursor" d="M${x(selected.start)} 0V${height}" stroke="#f0edf7" stroke-width=".8" opacity="0"/></svg>
      <input id="night-scrub" type="range" min="0" max="${Math.max(0,span-.001)}" step="any" value="${minute}" style="left:${x(night.start)/3.4}%;width:${(x(night.end)-x(night.start))/3.4}%;height:${height/(height+23)*100}%" aria-label="Inspect time during the night" aria-valuetext="${stages[selected.stage][0]}, ${time(selected.start)} to ${time(selected.end)}" data-axis-start="${+start}" data-axis-end="${+end}" data-height="${height}"/>
      </figure></section>
      <section class="sleep-breakdown" aria-label="Time in each stage">${Object.entries(stages).filter(([stage])=>stage!=='unrecorded'&&lanes.includes(stage)).map(([stage,[label,color]])=>`<button data-night-stage="${stage}" aria-pressed="false" ${!all[stage]?'disabled':''} style="--stage-color:${color}"><i></i><span>${label}</span><strong>${duration(all[stage]||0)}</strong></button>`).join('')}</section>
      <p class="sleep-source">Samsung Health${all.unrecorded?' · Gaps are not recorded':''}${all.unknown?' · Some stage timings are unavailable':''}</p>`;
  }
  function inspect(night,minute){
    if(!valid(night)||!Number.isFinite(minute))return 0;
    minute=Math.max(0,Math.min((night.end-night.start)/60000-.001,minute));const index=locate(night,minute),s=night.segments[index],input=q('#night-scrub');
    input.value=minute;input.setAttribute('aria-valuetext',`${time(night.start+minute*60000)}: ${stages[s.stage][0]}, ${time(s.start)} to ${time(s.end)}, ${duration((s.end-s.start)/60000)}`);
    const label=q('#night-selection');
    if(label.dataset.index!==String(index)||!label.classList.contains('is-inspecting')){label.innerHTML=selection(night,index);label.dataset.index=String(index);label.classList.add('is-inspecting')}
    const x=8+(night.start+minute*60000-Number(input.dataset.axisStart))/(Number(input.dataset.axisEnd)-Number(input.dataset.axisStart))*324;
    q('#night-cursor').setAttribute('d',`M${x} 0V${input.dataset.height}`);q('#night-cursor').setAttribute('opacity','1');
    document.querySelectorAll('[data-night-stage]').forEach(n=>n.setAttribute('aria-pressed',String(n.dataset.nightStage===s.stage)));return minute;
  }
  function stageMinute(night,stage){if(!valid(night))return 0;const s=night.segments.find(s=>s.stage===stage);return s?(s.start-night.start)/60000:0}
  return {valid,view,inspect,stageMinute,locate,totals};
})();
