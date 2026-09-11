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
  if (!nodes.has(id)) nodes.set(id, events({textContent:'', style:{setProperty:noop}, classList:{remove:noop,add:noop,toggle:noop}, setAttribute:noop,querySelectorAll:()=>[],contains:()=>false,focus:noop,animate:noop,hasPointerCapture:()=>false,setPointerCapture:noop,releasePointerCapture:noop,clientHeight:400}));
  return nodes.get(id);
}
const storage = new Map();
const document = events({querySelector:node,querySelectorAll:()=>[],hidden:false});
const reduced = events({matches:false});
const particles=Array.from({length:646},()=>({attrs:{},setAttribute(k,v){this.attrs[k]=v}}));
node('#orb-button').querySelectorAll=()=>particles;
const observers=[];
const sandbox = {document,window:events(),AbortController,performance:{now:()=>0},localStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)},matchMedia:()=>reduced,ResizeObserver:class{observe(){}},IntersectionObserver:class{constructor(fn){observers.push(fn)}observe(){}},requestAnimationFrame:fn=>{frames.set(++nextFrame,fn);return nextFrame},cancelAnimationFrame:id=>frames.delete(id),setTimeout,clearTimeout};
vm.createContext(sandbox);
vm.runInContext(script+`;globalThis.model={set:(d,n,k='steps')=>{selected=d;days=n;metric=k},windowRows,movement,week,dayOffset,anchor,render,extras,deckSwipeTarget,liveSummary,setDeckExpanded,SignalOrb,pause:p=>orb.setPaused(p),hide:h=>{document.hidden=h;document.fire('visibilitychange')}}`,sandbox);
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
assert(particles.every(p=>['cx','cy','r','opacity'].every(k=>Number.isFinite(Number(p.attrs[k])))));
assert.equal((nodes.get('#globe').innerHTML.match(/<circle/g)||[]).length,647);
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
assert(html.includes('data:font/ttf;base64,'));
assert(!html.includes('__FONT__'));
assert(!/class="(?:tagline|whisper|orb-hint)|id="orb-hint"/.test(html));
console.log('PASS: 4 metrics × 30 dates × 3 periods; 30-day calorie averages in orb, summary and live bar; coherent totals; 646 Signal particles; swipe and keyboard metric cycle; animation stops; stack expand/collapse; removed decorative text.');
