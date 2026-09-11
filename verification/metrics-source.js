const metricOrder=['steps','heart','sleep','intake'];
const metricNames={steps:'Steps',heart:'Heart rate',sleep:'Sleep',intake:'Intake'};
let metric='steps';
const stepsFacts=$('.facts').innerHTML;
const average=values=>values.reduce((a,b)=>a+b,0)/values.length;
const duration=minutes=>{const n=Math.round(minutes);return `${Math.floor(n/60)}h${n%60?' '+String(n%60).padStart(2,'0')+'m':''}`};
const extras=new Map(records.map((r,i)=>{
  const ago=records.length-1-i,asleep=ago===0?600:405+(ago*19%115),deep=Math.round(asleep*.2),rem=Math.round(asleep*.25);
  const heart=Array.from({length:24},(_,h)=>h>18&&ago===0?null:Math.round((h<7?55:69)+ago%5+5*Math.sin(h*.7)+(h===18?26:0)));
  const meals=['Breakfast','Lunch','Dinner','Snack'].map((name,j)=>{const protein=[25,35,42,14][j]+ago%4,carbs=[60,75,65,28][j]+ago%7,fat=[15,20,19,13][j]+ago%3;return {name,protein,carbs,fat,calories:protein*4+carbs*4+fat*9}});
  return [r.date,{heart,latest:ago===0?64:heart.at(-1),asleep,deep,rem,light:asleep-deep-rem,awake:ago===0?60:22+ago%5*3,meals,water:ago===0?1800:1500+ago%6*150}];
}));
function metricText(value){return metric==='sleep'?duration(value):fmt(value)}
function metricUnit(){return {steps:'steps',heart:'bpm',sleep:'',intake:'kcal'}[metric]}
function axisText(value){if(metric==='sleep')return +(value/60).toFixed(1)+'h';return value>=1000?+(value/1000).toFixed(1)+'K':String(Math.round(value))}
function chartCeiling(rows,line=false){const largest=Math.max(...rows.map(r=>r.value??0));if(metric==='heart')return Math.max(120,Math.ceil(largest/20)*20);if(metric==='sleep')return Math.max(240,Math.ceil(largest/120)*120);if(metric==='intake')return Math.max(500,Math.ceil(largest/500)*500);return Math.max(line?12000:4000,Math.ceil(largest/2000)*2000)}
function dailyMetric(r){const x=extras.get(r.date);return metric==='steps'?r.steps:metric==='heart'?average(x.heart.filter(n=>n!==null)):metric==='sleep'?x.asleep:x.meals.reduce((s,m)=>s+m.calories,0)}
function extraMovement(){if(days>1)return windowRows(days).map(r=>({label:dateLabel(r.date),value:dailyMetric(r)}));const x=extras.get(selected);if(metric==='heart')return x.heart.map((value,i)=>({label:`${String(i).padStart(2,'0')}:00 – ${String(i+1).padStart(2,'0')}:00`,value}));if(metric==='sleep')return ['light','deep','rem','awake'].map(k=>({label:k==='rem'?'REM':k[0].toUpperCase()+k.slice(1),value:x[k]}));return x.meals.map(m=>({label:m.name,value:m.calories}))}
function render(){
  $('.facts').innerHTML=stepsFacts;
  renderSteps();
  $('h1').textContent=metricNames[metric];
  $('.periods').setAttribute('aria-label',metricNames[metric]+' period');
  $('.hero').setAttribute('aria-label',metricNames[metric]);
  $('#more').setAttribute('aria-label','Metric options');
  $('#orb-hint').textContent='Swipe for '+metricNames[metricOrder[(metricOrder.indexOf(metric)+1)%4]]+' →';
  $('.orb-dots').innerHTML=metricOrder.map(k=>`<i class="${k===metric?'active':''}"></i>`).join('');
  $('#orb-button').setAttribute('aria-label',metricNames[metric]+'. Swipe left or right, tap, or use arrow keys to change metric.');
  $('#line-range').setAttribute('aria-label','Inspect daily '+metricNames[metric]);
  if(metric==='steps')return;
  const rows=windowRows(days),list=rows.map(r=>extras.get(r.date)),x=extras.get(selected);
  const total=rows.reduce((s,r)=>s+dailyMetric(r),0),primary=metric==='heart'?(days===1?x.latest:total/rows.length):metric==='sleep'?total/rows.length:total;
  $('#steps').textContent=metricText(primary);
  $('#hero-label').textContent=metric==='sleep'?(days===1?'Time asleep':'Average sleep'):metric==='heart'?(days===1?'Heart rate':'Average heart rate'):(days===1?'Intake':days+'-day intake');
  $('#goal-label').textContent=metric==='heart'?'bpm · '+(days===1?'latest reading':'period average'):metric==='sleep'?(days===1?'asleep · '+duration(x.awake)+' awake':'per night'):'kcal logged';
  const avg=k=>average(list.map(v=>v[k]));
  let cards,footer;
  if(metric==='heart'){
    const samples=list.flatMap(v=>v.heart.filter(n=>n!==null));
    cards=[['Average',fmt(average(samples)),'bpm','target'],['Lowest',fmt(Math.min(...samples)),'bpm','target'],['Highest',fmt(Math.max(...samples)),'bpm','flame'],['Readings',fmt(samples.length),'hourly','target']];
    footer=[['Hourly averages','Demonstration readings'],[days===1?(selected===anchor?'18:42':'23:59'):rows.length+' days',days===1?'Latest recorded':'In this period']];
    $('#movement-heading').textContent=days===1?'Heart rate through the day':'Daily average heart rate';
  } else if(metric==='sleep'){
    cards=[['Light',duration(avg('light')),'','target'],['Deep',duration(avg('deep')),'','target'],['REM',duration(avg('rem')),'','target'],['Awake',duration(avg('awake')),'','target']];
    footer=[[duration(avg('asleep')+avg('awake')),days===1?'Recorded sleep window':'Average sleep window'],[rows.length+' '+(rows.length===1?'night':'nights'),'In this period']];
    $('#movement-heading').textContent=days===1?'Your sleep stages':'Time asleep each night';
  } else {
    const meals=list.flatMap(v=>v.meals),sum=k=>meals.reduce((a,m)=>a+m[k],0);
    cards=[['Protein',fmt(sum('protein')),'g','target'],['Carbs',fmt(sum('carbs')),'g','flame'],['Fat',fmt(sum('fat')),'g','target'],['Water',(list.reduce((a,v)=>a+v.water,0)/1000).toFixed(1),'L','target']];
    footer=[[fmt(meals.length)+' meals','Logged in this period'],[fmt(primary/rows.length)+' kcal','Daily average']];
    $('#movement-heading').textContent=days===1?'Intake through the day':'Calories logged each day';
  }
  $('.facts').innerHTML='<div class="metrics">'+cards.map(([label,value,unit,icon])=>`<div class="metric"><svg aria-hidden="true"><use href="#${icon}"/></svg><p class="metric-summary-value">${value} <span>${unit}</span></p><small class="metric-summary-label">${label}</small></div>`).join('')+'</div><div class="facts-footer">'+footer.map(([value,label])=>`<div><strong>${value}</strong><small>${label}</small></div>`).join('')+'</div>';
  $('#movement-subtitle').textContent=days===1?(metric==='heart'?'Hourly averages · bpm':metric==='sleep'?'Stage totals · time asleep excludes awake':'Four demonstration food entries'):'Select a reading to explore this period';
  $('#week-heading').textContent=(metric==='sleep'?'Last ':'Last ')+(days===30?'30':'7')+(metric==='sleep'?' nights':' days');
  const count=days===30?30:7,a=average(windowRows(count).map(dailyMetric)),b=average(windowRows(count,dayOffset(selected,-count)).map(dailyMetric)),delta=a-b;
  $('#current-average').innerHTML=metricText(a)+` <span>${metricUnit()}</span>`;
  $('#previous-average').innerHTML=metricText(b)+` <span>${metricUnit()}</span>`;
  $('#difference').textContent=metricText(Math.abs(delta));
  $('#direction').textContent=delta<0?'▼':delta>0?'▲':'–';
  $('#difference-label').textContent=(metricUnit()+' '+(delta<0?'lower':delta>0?'higher':'no change')).trim();
}
function changeMetric(delta){metric=metricOrder[(metricOrder.indexOf(metric)+delta+metricOrder.length)%metricOrder.length];render();$('#metric-announcement').textContent=metricNames[metric]+', '+$('#steps').textContent+', '+$('#goal-label').textContent;if(!reduced.matches)$('.readout').animate([{opacity:.35},{opacity:1}],{duration:140})}
function initOrb(){
  const button=$('#orb-button'),readout=$('.readout');let start=null,ignoreClick=false;
  button.addEventListener('pointerdown',e=>{if(!e.isPrimary||e.button!==0)return;start={x:e.clientX,y:e.clientY,id:e.pointerId};ignoreClick=false;button.setPointerCapture(e.pointerId)});
  button.addEventListener('pointermove',e=>{if(!start||e.pointerId!==start.id)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;if(Math.abs(dx)>Math.abs(dy)*1.25&&!reduced.matches)readout.style.transform=`translateX(${Math.max(-48,Math.min(48,dx*.3))}px)`});
  button.addEventListener('pointerup',e=>{if(!start||e.pointerId!==start.id)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;ignoreClick=Math.hypot(dx,dy)>12;start=null;readout.style.transform='';if(button.hasPointerCapture(e.pointerId))button.releasePointerCapture(e.pointerId);if(Math.abs(dx)>45&&Math.abs(dx)>Math.abs(dy)*1.25)changeMetric(dx<0?1:-1)});
  button.addEventListener('pointercancel',()=>{start=null;ignoreClick=true;readout.style.transform=''});
  button.addEventListener('click',()=>{if(ignoreClick){ignoreClick=false;return}changeMetric(1)});
  button.addEventListener('keydown',e=>{if(!['ArrowLeft','ArrowRight'].includes(e.key))return;e.preventDefault();changeMetric(e.key==='ArrowRight'?1:-1)});
}
