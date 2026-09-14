/* Samsung Health records only. Missing readings are null, never generated. */
'use strict';
const HealthData=(()=>{
  const source='com.sec.android.app.shealth',finite=n=>typeof n==='number'&&Number.isFinite(n);
  const date=at=>{const d=new Date(at);return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`};
  const today=()=>date(Date.now()),escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const validDate=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&value>='1970-01-01'&&Number.isFinite(Date.parse(value+'T12:00:00Z'))&&new Date(value+'T12:00:00Z').toISOString().slice(0,10)===value;
  const sum=values=>{const known=values.filter(finite);return known.length?known.reduce((a,b)=>a+b,0):null};
  const average=values=>{const known=values.filter(finite);return known.length?sum(known)/known.length:null};
  const display=(value,digits=0)=>finite(value)?value.toLocaleString('en-GB',{minimumFractionDigits:digits,maximumFractionDigits:digits}):'—';
  const dayEmpty=key=>({date:key,steps:null,distance:null,floors:null,energy:null,totalEnergy:null,heart:Array(24).fill(null),heartCount:0,heartSum:0,heartLow:null,heartHigh:null,latest:null,latestTime:0,asleep:null,light:null,deep:null,rem:null,awake:null,meals:[],water:null,nights:[],night:null,body:{weight:null,fat:null,fatMass:null,muscle:null,lean:null},oxygen:{value:null,low:null,high:null}});
  let days=new Map(),hours=[],loadedDate='',workouts=[],meta={},revision='',live=null,liveKey='',contentKey='',info={status:'Connect Samsung Health in Settings',available:false,permitted:false};
  function night(row){
    if(!Array.isArray(row.stages))throw Error('Missing sleep stages');
    const names={0:'unknown',1:'awake',2:'sleeping',3:'awake',4:'light',5:'deep',6:'rem',7:'awake'},segments=[];let cursor=row.start;
    for(const [start,end,type] of row.stages){
      if(!finite(start)||!finite(end)||start<cursor||end<=start||end>row.end||!Object.hasOwn(names,type))throw Error('Invalid sleep interval');
      if(start>cursor)segments.push({start:cursor,end:start,stage:'unknown'});
      segments.push({start,end,stage:names[type]});cursor=end;
    }
    if(cursor<row.end)segments.push({start:cursor,end:row.end,stage:'unknown'});
    return {start:row.start,end:row.end,segments,source:'Samsung Health',id:row.id};
  }
  function accept(data){
    if(data?.schema!==1||!Array.isArray(data.rows)||!Array.isArray(data.workouts)||!validDate(data.date)||data.date>today())throw Error('Invalid health data');
    for(const key of ['firstRecord','lastRecord','lastSync','recordCount'])if(data.meta?.[key]!=null&&(!finite(data.meta[key])||data.meta[key]<0))throw Error('Invalid import coverage');
    const next=new Map(),get=key=>{if(!next.has(key))next.set(key,dayEmpty(key));return next.get(key)},seen=new Set();
    for(const row of data.rows){
      if(row.source!==source||typeof row.type!=='string'||row.date!==undefined&&!validDate(row.date))throw Error('Unexpected health source or date');
      if(row.type==='heartHour'){
        if(!validDate(row.date)||!Number.isInteger(row.hour)||row.hour<0||row.hour>23||![row.count,row.sum,row.low,row.high,row.latest,row.latestTime].every(finite)||!Number.isInteger(row.count)||row.count<=0||row.low<=0||row.high<row.low)throw Error('Invalid heart sample');
        const d=get(row.date);d.heart[row.hour]=row.sum/row.count;d.heartSum+=row.sum;d.heartCount+=row.count;
        d.heartLow=d.heartLow===null?row.low:Math.min(d.heartLow,row.low);d.heartHigh=d.heartHigh===null?row.high:Math.max(d.heartHigh,row.high);
        if(row.latestTime>=d.latestTime){d.latest=row.latest;d.latestTime=row.latestTime}continue;
      }
      if(!row.id||!finite(row.start)||!finite(row.end)||row.start<0||row.end<row.start)throw Error('Invalid record');
      const key=row.type+':'+row.id;if(seen.has(key))throw Error('Duplicate record');seen.add(key);
      const d=get(row.type==='sleep'?date(row.end):row.date||date(row.start));
      if(Object.hasOwn(row,'value')&&(!finite(row.value)||row.value<0))throw Error('Invalid measurement');
      if(!['nutrition','sleep'].includes(row.type)&&!finite(row.value))throw Error('Missing measurement');
      if(row.type.endsWith('Day')){const name=row.type.slice(0,-3);if(!['steps','distance','floors','energy','totalEnergy'].includes(name))throw Error('Invalid daily measurement');d[name]=row.value}
      else if(['weight','fat','lean','height'].includes(row.type)){
        const at=d.body[row.type+'At']??-1;if(row.start>=at){d.body[row.type]=row.value;d.body[row.type+'At']=row.start}
      }else if(row.type==='oxygen'){
        if(row.value>100)throw Error('Invalid oxygen');const o=d.oxygen;
        o.low=o.low===null?row.value:Math.min(o.low,row.value);o.high=o.high===null?row.value:Math.max(o.high,row.value);
        if(row.start>=(o.at??0)){o.value=row.value;o.at=row.start}
      }else if(row.type==='water')d.water=(d.water??0)+row.value;
      else if(row.type==='nutrition'){
        const meal={name:row.name||['Meal','Breakfast','Lunch','Dinner','Snack'][row.mealType]||'Meal',at:row.start};
        for(const key of ['calories','protein','carbs','fat']){if(row[key]!=null&&(!finite(row[key])||row[key]<0))throw Error('Invalid nutrition');meal[key]=row[key]??null}d.meals.push(meal);
      }else if(row.type==='sleep')d.nights.push(night(row));
      else throw Error('Unknown measurement');
    }
    for(const d of next.values()){
      const b=d.body;if(b.weight>0&&b.fat!==null&&b.fat<=100&&Math.abs(b.weightAt-b.fatAt)<=60000)b.fatMass=b.weight*b.fat/100;
      d.nights.sort((a,b)=>a.start-b.start);d.night=d.nights.reduce((best,n)=>!best||n.end-n.start>best.end-best.start?n:best,null);
      // Union reported intervals; overlapping, conflicting stages stay unknown instead of double counting.
      const segments=d.nights.flatMap(n=>n.segments),edges=[...new Set(segments.flatMap(s=>[s.start,s.end]))].sort((a,b)=>a-b),totals={},timeline=[];
      for(let i=1;i<edges.length;i++){
        const start=edges[i-1],end=edges[i],active=segments.filter(s=>s.start<=start&&s.end>=end);
        const stage=!active.length?'unrecorded':active.every(s=>s.stage===active[0].stage)?active[0].stage:'unknown';
        if(active.length)totals[stage]=(totals[stage]??0)+(end-start)/60000;
        if(timeline.at(-1)?.stage===stage)timeline.at(-1).end=end;else timeline.push({start,end,stage});
      }
      d.sleepTimeline=timeline.length?{start:edges[0],end:edges.at(-1),segments:timeline}:null;
      const known=['light','deep','rem','sleeping'].some(k=>Object.hasOwn(totals,k));d.asleep=known?['light','deep','rem','sleeping'].reduce((v,k)=>v+(totals[k]??0),0):null;
      for(const k of ['light','deep','rem','awake'])d[k]=totals[k]??(known&&!totals.sleeping&&!totals.unknown?0:null);
      d.sleepIncomplete=Boolean(totals.unknown);d.meals.sort((a,b)=>a.at-b.at);
    }
    const imported=data.workouts.map(r=>{
      if(r.source!==source||r.type!=='exercise'||!r.id||!finite(r.start)||r.start<0||!finite(r.end)||r.end<=r.start||typeof r.kind!=='string')throw Error('Invalid workout');
      if(Object.values(r.summary||{}).some(n=>!finite(n)||n<0))throw Error('Invalid workout measurement');
      for(const rows of [r.laps||[],r.segments||[]])if(!Array.isArray(rows)||rows.some(s=>!finite(s.start)||!finite(s.end)||s.start<r.start||s.end<s.start||s.end>r.end||s.distanceM!=null&&(!finite(s.distanceM)||s.distanceM<0)))throw Error('Invalid workout interval');
      return {...r,imported:true,recordKey:'samsung:'+r.id,startedAt:r.start,endedAt:r.end,elapsed:r.end-r.start,totalMs:r.end-r.start};
    });
    if(new Set(imported.map(r=>r.recordKey)).size!==imported.length)throw Error('Duplicate workout');
    const stepHours=data.stepHours??[];if(!Array.isArray(stepHours)||stepHours.some(h=>!finite(h.start)||!finite(h.end)||h.end<=h.start||!finite(h.value)||h.value<0))throw Error('Invalid hourly steps');
    days=next;hours=stepHours;loadedDate=data.date;workouts=imported.sort((a,b)=>b.startedAt-a.startedAt);meta=data.meta||{};
  }
  function refresh(){
    let changed=false,liveChanged=false;
    try{if(window.OrbitHealth){
      const snapshot=JSON.parse(window.OrbitHealth.snapshot(revision));if(snapshot.error)throw Error(snapshot.error);
      const next=snapshot.live?.reading??null;
      if(next&&(next.source!==source||!validDate(next.date)||next.date>today()||!Number.isSafeInteger(next.steps)||next.steps<0||!finite(next.at)||next.at<0||!Array.isArray(next.hours)||next.hours.some(h=>!finite(h.start)||!finite(h.end)||h.end<=h.start||!Number.isSafeInteger(h.value)||h.value<0)||next.hours.reduce((s,h)=>s+h.value,0)!==next.steps))throw Error('Invalid live steps');
      if(snapshot.data!==null&&snapshot.data!==undefined){const key=JSON.stringify({...snapshot.data,meta:{...snapshot.data.meta,lastSync:0}});accept(snapshot.data);changed=key!==contentKey;contentKey=key}
      const key=JSON.stringify(next&&[next.date,next.steps,next.hours.map(h=>[h.start,h.value])]);liveChanged=key!==liveKey;liveKey=key;live=next;
      revision=snapshot.revision;info=snapshot;delete info.data;
    }}
    catch{info={...info,status:'Health data could not be read. Saved records are unchanged.'}}
    window.dispatchEvent(new CustomEvent('health-data-ready',{detail:{changed,liveChanged}}));return changed||liveChanged;
  }
  function stepHours(key){
    const direct=live?.date===key&&key===today(),readings=direct?live.hours:key===loadedDate?hours:[];
    if(!readings.length)return Array.from({length:24},(_,h)=>({label:`${String(h).padStart(2,'0')}:00`,value:direct&&h<=new Date(live.at).getHours()?0:null}));
    const start=new Date(key+'T00:00:00').getTime(),end=new Date(key+'T00:00:00');end.setDate(end.getDate()+1);
    return Array.from({length:Math.round((end-start)/3600000)},(_,i)=>{const at=start+i*3600000,h=readings.find(r=>r.start===at);return {label:new Date(at).toLocaleTimeString('en-GB',{hour:'2-digit',minute:'2-digit'}),value:h?.value??(direct&&at<=live.at?0:null)}});
  }
  function load(key){window.OrbitHealth?.load(key)}
  const daily=key=>{const row=days.get(key)||dayEmpty(key);return live?.date===key&&key===today()?{...row,steps:live.steps}:row};
  const body=key=>daily(key).body;
  const bodyDates=()=>[...days.keys()].filter(key=>Object.values(body(key)).some(finite)).sort();
  const latestBody=key=>{const at=bodyDates().filter(d=>d<=key&&body(d).weight!==null).at(-1);return {date:at,...body(at)}};
  function sourceText(){return meta.lastSync||live?'Samsung Health':'Connect Samsung Health in Settings'}
  window.addEventListener('orbit-health-change',refresh);
  return {accept,refresh,load,daily,body,bodyDates,latestBody,stepHours,sum,average,display,escape,today,date,sourceText,
    get workouts(){return workouts},get meta(){return meta},get info(){return info}};
})();
