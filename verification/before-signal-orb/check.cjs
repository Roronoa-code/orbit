// Run with Node: node verification/check.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const script = html.match(/<script id="app">([\s\S]*?)<\/script>/)[1];
const nodes = new Map(), frames = new Map(); let nextFrame = 0;
const noop = () => {};
const ctx = new Proxy({}, { get: (_, k) => k === 'createRadialGradient' ? () => ({addColorStop: noop}) : noop });
function node(id) {
  if (!nodes.has(id)) nodes.set(id, {textContent:'', style:{setProperty:noop}, classList:{remove:noop,add:noop,toggle:noop}, addEventListener:noop, setAttribute:noop, getContext:()=>ctx,querySelectorAll:()=>[],contains:()=>false,focus:noop,clientHeight:400});
  return nodes.get(id);
}
const storage = new Map();
const document = {querySelector:node,querySelectorAll:()=>[],addEventListener:noop,hidden:false};
const reduced = {matches:false,addEventListener:noop};
const sandbox = {document,localStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)},matchMedia:()=>reduced,ResizeObserver:class{observe(){}},IntersectionObserver:class{observe(){}},requestAnimationFrame:fn=>{frames.set(++nextFrame,fn);return nextFrame},cancelAnimationFrame:id=>frames.delete(id),setTimeout,clearTimeout};
vm.createContext(sandbox);
vm.runInContext(script+`;globalThis.model={set:(d,n,k='steps')=>{selected=d;days=n;metric=k},windowRows,movement,week,dayOffset,anchor,restart,render,extras,deckSwipeTarget,liveSummary,setDeckExpanded,pause:p=>{paused=p},hide:h=>{document.hidden=h}}`,sandbox);
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
  }
}
m.restart();assert.equal(frames.size,1);
m.restart();assert.equal(frames.size,1);
m.pause(true);m.restart();assert.equal(frames.size,0);
m.pause(false);reduced.matches=true;m.restart();assert.equal(frames.size,0);
reduced.matches=false;m.hide(true);m.restart();assert.equal(frames.size,0);
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
console.log('PASS: 4 metrics × 30 dates × 3 periods; coherent totals; prior periods; one animation loop; reduced-motion/hidden-page stop; live summaries; vertical stack intent, scroll guard, expand/collapse state; embedded font.');
