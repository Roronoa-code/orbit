// Run with Node: node verification/check.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const script = html.match(/<script id="app">([\s\S]*?)<\/script>/)[1];
const nodes = new Map(), frames = new Map(); let nextFrame = 0;
const noop = () => {};
function events(target={}) { const handlers={}; return Object.assign(target,{addEventListener:(name,fn)=>(handlers[name]??=[]).push(fn),fire:(name,event={})=>(handlers[name]??[]).forEach(fn=>fn(event))}); }
function node(id) {
  if (!nodes.has(id)) nodes.set(id, events({textContent:'', style:{setProperty:noop}, classList:{remove:noop,add:noop,toggle:noop}, id:id.replace('#',''),dataset:{},setAttribute:noop,querySelector:s=>node(id+' '+s),querySelectorAll:()=>[],contains:()=>false,focus:noop,animate:noop,hasPointerCapture:()=>false,setPointerCapture:noop,releasePointerCapture:noop,clientWidth:390,clientHeight:790,offsetHeight:180,scrollTop:0}));
  return nodes.get(id);
}
node('#period-content').querySelectorAll=()=>['.facts','.hourly','.weekly','.comparison'].map(node);
const storage = new Map();
const document = events({querySelector:node,querySelectorAll:()=>[],hidden:false});
const reduced = events({matches:false});
const drawn=[];
const ctx={setTransform:noop,clearRect:()=>{drawn.length=0},beginPath:noop,arc:(...args)=>drawn.push(args),fill:noop};
const canvas={clientWidth:340,clientHeight:260,getContext:()=>ctx};
node('#orb-button').querySelector=()=>canvas;
const observers=[];
const sandbox = {document,getComputedStyle:()=>({marginTop:'2',marginBottom:'10',bottom:'82px'}),window:events({innerHeight:900}),AbortController,performance:{now:()=>0},localStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)},matchMedia:()=>reduced,ResizeObserver:class{observe(){}},IntersectionObserver:class{constructor(fn){observers.push(fn)}observe(){}},requestAnimationFrame:fn=>{frames.set(++nextFrame,fn);return nextFrame},cancelAnimationFrame:id=>frames.delete(id),setTimeout,clearTimeout};
vm.createContext(sandbox);
vm.runInContext(script+`;globalThis.model={set:(d,n,k='steps')=>{selected=d;days=n;metric=k},windowRows,movement,week,dayOffset,anchor,render,extras,deckSwipeTarget,liveSummary,setDeckExpanded,SignalOrb,springStep,reveal,deckMotion,orbPose,getProgress:()=>deckProgress,getExpanded:()=>deckExpanded,islands,setLiveOpen,closeInline,show,activityData,chooseActivity:key=>{activityChoice=key;renderActivity();updateLiveBar()},setDateChoice,dateOptions,pause:p=>orb.setPaused(p),hide:h=>{document.hidden=h;document.fire('visibilitychange')}}`,sandbox);
const m=sandbox.model;
assert.equal(nodes.get('#steps').textContent, '8,420');
assert.equal(nodes.get('#current-average').innerHTML, '6,794 <span>steps</span>');
assert.equal(nodes.get('#previous-average').innerHTML, '7,520 <span>steps</span>');
for(let ago=0;ago<30;ago++) {
  const date=m.dayOffset(m.anchor,-ago);
  m.set(date,1);
  const hourly=Array.from(m.movement());
  assert.equal(hourly.reduce((s,r)=>s+(r.value??0),0),m.windowRows(1)[0].steps);
  assert(hourly.every(r=>r.value===null||Number.isInteger(r.value)&&r.value>=0));
  assert.equal(hourly.filter(r=>r.value===null).length,ago===0?5:0);
  for(const days of [1,7,30]) {
    m.set(date,days);m.render();
    const period=days===30?30:7;
    assert.equal(m.windowRows(days).length,days);
    assert.equal(m.windowRows(period,m.dayOffset(date,-period)).length,period);
    assert.equal(m.week().length,period);
    assert(!nodes.get('#line').innerHTML.includes('NaN'));
  }
}
for (const metric of ['heart','sleep','intake']) for(let ago=0;ago<30;ago++) {
  const date=m.dayOffset(m.anchor,-ago);
  const x=m.extras.get(date);
  assert.equal(x.light+x.deep+x.rem,x.asleep);
  assert(x.meals.every(v=>v.calories===v.protein*4+v.carbs*4+v.fat*9));
  for(const days of [1,7,30]) {
    m.set(date,days,metric);m.render();
    assert(!nodes.get('#steps').textContent.includes('NaN'));
    assert(!nodes.get('#line').innerHTML.includes('NaN'));
    assert(!nodes.get('#bars').innerHTML.includes('NaN'));
    assert.equal(m.week().length,days===30?30:7);
    if(metric==='intake') {
      const total=Array.from(m.windowRows(days)).reduce((sum,r)=>sum+m.extras.get(r.date).meals.reduce((s,meal)=>s+meal.calories,0),0);
      const expected=Math.round(total/(days===30?30:1)).toLocaleString('en-GB');
      assert.equal(nodes.get('#steps').textContent,expected);
      assert.equal(m.liveSummary().title,expected+(days===30?' kcal / day':' kcal logged'));
      assert(nodes.get('.facts').innerHTML.includes(Math.round(total/days).toLocaleString('en-GB')+' kcal'));
      if(days===30){assert.equal(nodes.get('#hero-label').textContent,'Average daily intake');assert.equal(nodes.get('#goal-label').textContent,'kcal / day · last 30 days');assert.equal(nodes.get('#metric-announcement').textContent,'Intake, '+expected+', kcal / day · last 30 days');}
    }
  }
}
m.pause(false);assert.equal(frames.size,1);
m.pause(false);assert.equal(frames.size,1);
m.pause(true);assert.equal(frames.size,0);
m.pause(false);reduced.matches=true;reduced.fire('change');assert.equal(frames.size,0);
reduced.matches=false;reduced.fire('change');m.hide(true);assert.equal(frames.size,0);
m.hide(false);assert.equal(frames.size,1);
observers[0]([{isIntersecting:false}]);assert.equal(frames.size,0);
observers[0]([{isIntersecting:true}]);assert.equal(frames.size,1);
assert.equal(m.SignalOrb.points.length,646);
assert.equal(m.SignalOrb.direction(-60,0),1);
assert.equal(m.SignalOrb.direction(60,0),-1);
assert.equal(m.SignalOrb.direction(10,1),0);
assert.equal(m.SignalOrb.direction(-15,-.6),1);
assert.equal(m.SignalOrb.next('sleep',1),'intake');
assert.equal(m.SignalOrb.next('intake',1),'steps');
assert.equal(drawn.length,646);assert(drawn.every(args=>args.every(Number.isFinite)));
assert(nodes.get('#globe').innerHTML.includes('<canvas'));assert(!nodes.get('#globe').innerHTML.includes('ellipse'));
assert(!html.includes('data-table')&&!html.includes('id="readings"'));
m.set(m.anchor,1,'steps');m.render();
const button=nodes.get('#orb-button');
const pointer=(x,y,t)=>({isPrimary:true,button:0,pointerId:1,clientX:x,clientY:y,timeStamp:t,preventDefault:noop});
button.fire('pointerdown',pointer(200,150,0));button.fire('pointermove',pointer(120,150,40));button.fire('pointerup',pointer(120,150,60));
assert.equal(nodes.get('#steps').textContent,'64');
button.fire('click',{detail:1,preventDefault:noop,stopPropagation:noop});assert.equal(nodes.get('#steps').textContent,'64');
button.fire('keydown',{key:'ArrowRight',preventDefault:noop});assert.equal(nodes.get('#steps').textContent,'10h');
button.fire('keydown',{key:'ArrowRight',preventDefault:noop});assert.equal(nodes.get('#steps').textContent,'1,979');
button.fire('keydown',{key:'ArrowRight',preventDefault:noop});assert.equal(nodes.get('#steps').textContent,'8,420');
button.fire('pointerdown',pointer(200,150,100));button.fire('pointermove',pointer(200,230,150));button.fire('pointerup',pointer(200,230,170));
button.fire('click',{detail:1,preventDefault:noop,stopPropagation:noop});assert.equal(nodes.get('#steps').textContent,'8,420');
assert.equal(m.deckSwipeTarget(false,0,-80),true);
assert.equal(m.deckSwipeTarget(true,0,80),false);
assert.equal(m.deckSwipeTarget(true,0,80,false,50),null);
assert.equal(m.deckSwipeTarget(true,0,80,true,50),false);
assert.equal(m.deckSwipeTarget(false,80,-40),null);
assert.equal(m.deckSwipeTarget(false,0,-20),null);
assert.equal(m.deckSwipeTarget(false,0,80),null);
assert.equal(m.deckSwipeTarget(true,0,-80),null);
m.set(m.anchor,1,'steps');m.render();assert.equal(m.liveSummary().title,'1,580 steps to go');
m.set(m.anchor,1,'heart');m.render();assert.equal(m.liveSummary().title,'64 bpm');
m.set(m.anchor,1,'sleep');m.render();assert.equal(m.liveSummary().title,'10h asleep');
m.set(m.anchor,1,'intake');m.render();assert.equal(m.liveSummary().title,'1,979 kcal logged');
m.setDeckExpanded(true);assert.equal(nodes.get('#stack-open').hidden,true);
m.setDeckExpanded(false);assert.equal(nodes.get('#stack-open').hidden,false);
// Android releases implicit pointer capture before the final touchend.
// That notification must not cancel the independently tracked touch gesture.
const deckNode=nodes.get('#panel-deck');
const barTarget={closest:s=>s==='#live-bar'?nodes.get('#live-bar'):null};
const finger=(x,y)=>({identifier:9,clientX:x,clientY:y});
function touchSwipe(from,to){
 deckNode.fire('touchstart',{touches:[finger(200,from)],target:barTarget});
 deckNode.fire('touchmove',{touches:[finger(200,to)],cancelable:true,preventDefault:noop});
 deckNode.fire('lostpointercapture',{pointerType:'touch',pointerId:9});
 deckNode.fire('touchend',{changedTouches:[finger(200,to)]});
}
touchSwipe(600,520);assert.equal(m.getExpanded(),true,'finger-up must commit upward swipe');
touchSwipe(520,600);assert.equal(m.getExpanded(),false,'finger-up must commit downward swipe');assert.equal(m.islands.live.open,true,'pulling down on the bar opens activity choices');
touchSwipe(520,600);assert.equal(m.islands.live.open,false,'a second downward pull closes the activity area');
deckNode.fire('touchstart',{touches:[finger(200,520)],target:barTarget});deckNode.fire('touchmove',{touches:[finger(200,570)],cancelable:true,preventDefault:noop});deckNode.fire('touchcancel');assert.equal(m.islands.live.open,false,'cancelled activity pull must return closed');
// Retarget while opening: preserve both position and velocity, then settle without a jump.
m.pause(true);m.setDeckExpanded(true);
const step=now=>{const pending=[...frames.values()];frames.clear();pending.forEach(fn=>fn(now))};
step(16);step(32);step(48);step(64);step(80);
assert(m.getProgress()>0&&m.getProgress()<1);assert(m.orbPose.value>0&&m.orbPose.value<m.getProgress());
const before={...m.deckMotion};m.setDeckExpanded(false);
assert.equal(m.deckMotion.value,before.value);assert.equal(m.deckMotion.velocity,before.velocity);
step(96);assert(Math.abs(m.deckMotion.value-before.value)<.1);
for(let t=112;t<2200;t+=16)step(t);
assert.equal(m.getProgress(),0);assert.equal(m.orbPose.value,0);assert.equal(frames.size,0);
m.setDeckExpanded(true);for(let t=16;t<2200;t+=16)step(t);
assert.equal(m.getProgress(),1);assert.equal(m.orbPose.value,1);assert.equal(frames.size,0);
nodes.get('#deck-scroll').scrollTop=200;m.setDeckExpanded(false);assert.equal(nodes.get('#deck-scroll').scrollTop,200);step(16);assert(nodes.get('#deck-scroll').scrollTop<200&&nodes.get('#deck-scroll').scrollTop>0);
reduced.matches=true;reduced.fire('change');m.setDeckExpanded(false);assert.equal(nodes.get('#deck-scroll').scrollTop,0);assert.equal(m.getProgress(),0);assert.equal(frames.size,0);
const coarse={value:.3,velocity:.8},fine={...coarse};m.springStep(coarse,1,.05);for(let i=0;i<5;i++)m.springStep(fine,1,.01);
assert(Math.abs(coarse.value-fine.value)<1e-12);assert(Math.abs(coarse.velocity-fine.velocity)<1e-12);
assert.equal(m.reveal(.3,.35,.94),0);assert.equal(m.reveal(1,.35,.94),1);
// Slower spring response, while preserving continuous retargeting and complete endpoints.
const paced={value:0,velocity:0};m.springStep(paced,1,.1);assert(paced.value>.45&&paced.value<.6);
m.setLiveOpen(true);assert(m.islands.live.open);assert.equal(m.islands.live.body.inert,false);
for(const activity of ['body','workouts','oxygen'])for(const date of m.dateOptions){m.set(date,1);m.chooseActivity(activity);const data=m.activityData();assert.equal(data.stats.length,3);assert(data.stats.every(row=>Number.isFinite(Number(row[1]))));assert(nodes.get('#live-title').textContent===data.summary)}
m.chooseActivity(null);m.setLiveOpen(false);assert.equal(m.islands.live.body.inert,true);
assert(!html.includes('<dialog')&&!html.includes('showModal')&&!html.includes('pillMorphFrames'));
for(const date of m.dateOptions){m.setDateChoice(date);assert.equal(nodes.get('#date-input').value,date);assert(nodes.get('#date-range').value>=0&&nodes.get('#date-range').value<30)}
const valid=nodes.get('#date-input').value;m.setDateChoice('2025-08-32');assert.equal(nodes.get('#date-input').value,valid);
assert(!html.includes('type="date"'));assert(html.includes('utility-island'));
assert(html.includes('data:font/ttf;base64,'));
assert(!html.includes('__FONT__'));
assert(!/class="(?:tagline|whisper|orb-hint)|id="orb-hint"/.test(html));
console.log('PASS: 4 metrics × 30 dates × 3 periods; 30-day calorie averages in orb, summary and live bar; coherent totals; 646 Signal particles; swipe and keyboard metric cycle; animation stops; stack expand/collapse; removed decorative text; touch-up regression, distinct activity choices, inline surfaces, slower continuous orb/card motion, reversal momentum, exact spring timing, staged reveal and reduced-motion endpoints.');
