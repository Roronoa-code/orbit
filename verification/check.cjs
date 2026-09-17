// Run with Node: node verification/check.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const script = html.match(/<script id="app">([\s\S]*?)<\/script>/)[1];
const nodes = new Map(), frames = new Map(); let nextFrame = 0;
const noop = () => {};
function events(target={}) { const handlers={}; return Object.assign(target,{addEventListener:(name,fn)=>(handlers[name]??=[]).push(fn),removeEventListener:(name,fn)=>{handlers[name]=(handlers[name]||[]).filter(item=>item!==fn)},fire:(name,event={})=>(handlers[name]??[]).forEach(fn=>fn(event))}); }
function node(id) {
  if (!nodes.has(id)) nodes.set(id, events({textContent:'', style:{setProperty:noop,removeProperty:noop,getPropertyValue:()=>''}, classList:{remove:noop,add:noop,toggle:noop}, id:id.replace('#',''),dataset:{},getAttribute:()=>null,setAttribute:noop,querySelector:s=>node(id+' '+s),querySelectorAll:()=>[],contains:()=>false,focus:noop,animate:noop,hasPointerCapture:()=>false,setPointerCapture:noop,releasePointerCapture:noop,clientWidth:390,clientHeight:790,offsetHeight:180,scrollTop:0}));
  return nodes.get(id);
}
node('#period-content').querySelectorAll=()=>['.facts','.hourly','.weekly','.comparison'].map(node);
node('#live-choices').firstElementChild=node('.live-island-content');
const storage = new Map();
const document = events({querySelector:node,querySelectorAll:()=>[],hidden:false});
const reduced = events({matches:false});
const drawn=[];
const ctx={setTransform:noop,clearRect:()=>{drawn.length=0},beginPath:noop,arc:(...args)=>drawn.push(args),fill:noop};
const canvas={clientWidth:340,clientHeight:260,getContext:()=>ctx};
node('#orb-button').querySelector=()=>canvas;
const observers=[];
let testTime=0;
const sandbox = {setInterval:()=>1,clearInterval:noop,document,getComputedStyle:()=>({marginTop:'2',marginBottom:'10',bottom:'82px'}),window:events({innerHeight:900}),AbortController,performance:{timeOrigin:100000,now:()=>testTime},localStorage:{getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)},matchMedia:()=>reduced,ResizeObserver:class{observe(){}},IntersectionObserver:class{constructor(fn){observers.push(fn)}observe(){}},requestAnimationFrame:fn=>{frames.set(++nextFrame,fn);return nextFrame},cancelAnimationFrame:id=>frames.delete(id),setTimeout,clearTimeout};
sandbox.CustomEvent=class{constructor(type,init){this.type=type;Object.assign(this,init)}};
sandbox.window.dispatchEvent=event=>sandbox.window.fire(event.type,event);
const healthFixture=require('./samsung-import.cjs').fixture();
sandbox.window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',status:'Local fixture',available:true,permitted:true,data:known==='1'?null:healthFixture}),load(){}};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','health-data.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','settings-store.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','surface-motion.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','orbit-interaction.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','signal-orb.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','hero-dots.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','sleep-timeline.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','blob-track.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','workout-focus.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','workout-details.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','orbit-settings.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','health-dashboard.js'),'utf8'),sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname,'..','health-pages.js'),'utf8'),sandbox);
vm.runInContext(script+`;globalThis.model={set:(d,n,k='steps')=>{selected=d;days=n;metric=k},periodComparison,windowRows,movement,week,dayOffset,anchor,render,renderCharts,HealthData,deckSwipeTarget,liveSummary,setDeckExpanded,SignalOrb,springStep,reveal,deckMotion,orbPose,getProgress:()=>deckProgress,getExpanded:()=>deckExpanded,islands,setLiveOpen,closeInline,show,Health,cyclePeriod,getPeriod:()=>days,setDateChoice,dateOptions,pause:p=>orb.setPaused(p),hide:h=>{document.hidden=h;document.fire('visibilitychange')}}`,sandbox);
const m=sandbox.model;
for(const [a,b,delta] of [[[2077.6],[2085.5],-8],[[1.5],[1.49],1],[[1.49],[1.5],-1],[[0],[0],0],[[3.4],[3.49],0],[[],[4],null],[[4],[],null]])assert.equal(m.periodComparison(a,b).delta,delta);
assert.equal(nodes.get('#steps').textContent, '8,420');
for(const metric of ['steps','heart','sleep','intake'])for(let ago=0;ago<30;ago++)for(const days of [1,7,30]){
 const date=m.dayOffset(m.anchor,-ago);m.set(date,days,metric);m.render();m.renderCharts();
 assert.equal(m.week().length,days===30?30:7);assert(!nodes.get('#line').innerHTML.includes('NaN'));assert(!nodes.get('#bars').innerHTML.includes('NaN'));
 assert(!nodes.get('#steps').textContent.includes('NaN'));assert.equal(m.windowRows(days).length,days);
}
m.pause(false);assert.equal(frames.size,1);
m.pause(false);assert.equal(frames.size,1);
m.pause(true);assert.equal(frames.size,0);
m.pause(false);reduced.matches=true;reduced.fire('change');assert.equal(frames.size,1,'Orbit keeps animation enabled regardless of the OS motion preference');
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
assert.equal(nodes.get('#steps').textContent,'80');
button.fire('click',{detail:1,preventDefault:noop,stopPropagation:noop});assert.equal(nodes.get('#steps').textContent,'80');
button.fire('keydown',{key:'ArrowRight',preventDefault:noop});assert.equal(nodes.get('#steps').textContent,'7h');
button.fire('keydown',{key:'ArrowRight',preventDefault:noop});assert.equal(nodes.get('#steps').textContent,'550');
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
m.set(m.anchor,1,'steps');m.render();assert.equal(m.liveSummary().title,'Explore');
m.set(m.anchor,1,'steps');m.render();assert.equal(m.liveSummary().title,'Explore');
m.set(m.anchor,1,'steps');m.render();assert.equal(m.liveSummary().title,'Explore');
m.set(m.anchor,1,'steps');m.render();assert.equal(m.liveSummary().title,'Explore');
m.setDeckExpanded(true);assert.equal(nodes.get('#stack-open').hidden,true);
m.setDeckExpanded(false);assert.equal(nodes.get('#stack-open').hidden,false);
// Android releases implicit pointer capture before the final touchend.
// That notification must not cancel the independently tracked touch gesture.
const deckNode=nodes.get('#panel-deck');
const barTarget={closest:s=>s==='#live-bar'||s==='#live-island'?node(s):null};
const finger=(x,y)=>({identifier:9,clientX:x,clientY:y});
const stackTarget={closest:s=>s==='#stack-open'?nodes.get('#stack-open'):null};
function touchSwipe(from,to,target=barTarget){
 deckNode.fire('touchstart',{touches:[finger(200,from)],target});
 deckNode.fire('touchmove',{touches:[finger(200,to)],cancelable:true,preventDefault:noop});
 deckNode.fire('lostpointercapture',{pointerType:'touch',pointerId:9});
 deckNode.fire('touchend',{changedTouches:[finger(200,to)]});
}
touchSwipe(600,520,stackTarget);assert.equal(m.getExpanded(),true,'finger-up must commit upward swipe');
const closeTarget=barTarget;
deckNode.fire('touchstart',{touches:[finger(340,300)],target:closeTarget});deckNode.fire('touchend',{changedTouches:[finger(340,300)]});
nodes.get('#live-bar').fire('click',{detail:1});assert.equal(m.getExpanded(),true,'Explore remains available over expanded cards');assert.equal(m.islands.live.open,true,'Explore must also open destinations on that same tap');
touchSwipe(600,520,stackTarget);assert.equal(m.getExpanded(),true);
touchSwipe(520,600);assert.equal(m.getExpanded(),true,'A live-bar pull must not fold the cards');assert.equal(m.islands.live.open,false,'pulling down on the bar closes only its launcher');
touchSwipe(600,520);assert.equal(m.islands.live.open,true,'swiping up on the bar opens the launcher');
const shortcutTarget={closest:s=>s==='#live-island'?node(s):s==='button,a,summary'?node('.activity-choice'):null};
touchSwipe(520,600,shortcutTarget);assert.equal(m.islands.live.open,false,'swiping down anywhere on the open launcher, over a shortcut too, closes it');
touchSwipe(520,600);assert.equal(m.islands.live.open,false,'a downward pull never opens the launcher');
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
reduced.matches=true;reduced.fire('change');m.setDeckExpanded(false);assert(frames.size>0,'Deck must keep animating');for(let t=32;t<2400;t+=16)step(t);assert.equal(nodes.get('#deck-scroll').scrollTop,0);assert.equal(m.getProgress(),0);assert.equal(frames.size,0);
const coarse={value:.3,velocity:.8},fine={...coarse};m.springStep(coarse,1,.05);for(let i=0;i<5;i++)m.springStep(fine,1,.01);
assert(Math.abs(coarse.value-fine.value)<1e-12);assert(Math.abs(coarse.velocity-fine.velocity)<1e-12);
assert.equal(m.reveal(.3,.35,.94),0);assert.equal(m.reveal(1,.35,.94),1);
// Slower spring response, while preserving continuous retargeting and complete endpoints.
const paced={value:0,velocity:0};m.springStep(paced,1,.1);assert(paced.value>.45&&paced.value<.6);
m.setLiveOpen(true);assert(m.islands.live.open);assert.equal(m.islands.live.body.inert,false);
for(const date of m.HealthData.bodyDates()){m.set(date,1);const b=m.Health.sample('body',date),o=m.Health.sample('oxygen',date);assert.equal(b.muscle,null);assert(Math.abs(b.fatMass-b.weight*b.fat/100)<1e-9);assert.equal(b.lean,62);assert(o.low<=o.value&&o.value<=o.high)}
for(const page of ['body','workouts','overview']){assert(m.Health.open(page));assert.equal(m.Health.page,page);assert.equal(nodes.get('#health-page').hidden,false);assert(m.closeInline());assert.equal(m.Health.page,null);assert.equal(nodes.get('#health-page').hidden,true)}
assert.equal(m.Health.open('unknown'),false);
m.setLiveOpen(false);assert.equal(m.islands.live.body.inert,true);
assert(!html.includes('stack-close'));assert(!html.includes('dot-value'));assert(!fs.readFileSync(path.join(__dirname,'..','health-pages.js'),'utf8').includes('dot-value'));
assert(!html.includes('<dialog')&&!html.includes('showModal')&&!html.includes('pillMorphFrames'));
for(const date of m.dateOptions){m.setDateChoice(date);assert.equal(nodes.get('#date-input').value,date);assert(nodes.get('#date-range').value>=0&&nodes.get('#date-range').value<m.dateOptions.length)}
const valid=nodes.get('#date-input').value;m.setDateChoice('2025-08-32');assert.equal(nodes.get('#date-input').value,valid);
assert(!html.includes('type="date"'));assert(html.includes('utility-island'));
assert(html.includes('data:font/ttf;base64,'));
assert(!html.includes('__FONT__'));
assert(!/class="(?:tagline|whisper|orb-hint)|id="orb-hint"/.test(html));
// Tap changes only the time period; swipes above still change only the metric.
m.set(m.anchor,1,'steps');m.render();
for(const expected of [7,30,1]){button.fire('click',{detail:0});assert.equal(m.getPeriod(),expected);assert.equal(nodes.get('h1').textContent,'Steps')}
assert(!html.includes('class="periods"')&&!html.includes('data-days'));
assert.equal(m.Health.live(),null);assert.equal(m.Health.action('start','untrusted'),false);
assert(m.Health.action('start','Walking'));assert(!m.Health.action('start','Running'));
testTime=65000;assert.equal(m.Health.live().elapsed,'01:05');assert(m.Health.action('pause'));
testTime=95000;assert.equal(m.Health.live().elapsed,'01:05');assert(m.Health.action('resume'));
testTime=130000;assert.equal(m.Health.live().elapsed,'01:40');
const activeBefore=m.Health.state.active;const save=sandbox.localStorage.setItem;sandbox.localStorage.setItem=()=>{throw Error('full')};
assert.equal(m.Health.action('finish'),false);assert.equal(m.Health.state.active,activeBefore);sandbox.localStorage.setItem=save;
assert(m.Health.action('finish'));assert.equal(m.Health.live(),null);assert.equal(m.Health.state.history[0].elapsed,100000);
assert.equal(m.Health.elapsed({elapsed:5000,resumedAt:2000},1000),5000,'Backwards clock cannot subtract saved time');
assert.equal(m.Health.elapsed({elapsed:120000,endedAt:140000,startedAt:0}),120000,'A completed record without resumedAt must keep its saved duration');
assert.equal(m.Health.validSession({kind:'<script>',startedAt:1,elapsed:0,resumedAt:null},true),false);
// The phone confirms the native disk save before changing the visible session state.
let nativeValue=null,nativeFail=false;
sandbox.window.OrbitWorkouts={read:()=>nativeValue,write:value=>{if(nativeFail)return false;nativeValue=value;return true}};
const loadNative=()=>{const health=vm.runInContext('(function(){'+fs.readFileSync(path.join(__dirname,'..','health-pages.js'),'utf8')+';return Health;})()',sandbox);health.init({notice:noop,onChange:noop,onTick:noop});return health};
let nativeHealth=loadNative();assert.equal(nativeHealth.state.history[0].elapsed,100000,'Existing browser history migrates');assert(nativeValue);
assert(nativeHealth.action('start','Running'));nativeHealth=loadNative();assert.equal(nativeHealth.live().title,'Running','A fresh instance restores the confirmed start');
nativeFail=true;assert.equal(nativeHealth.action('pause'),false);assert.equal(nativeHealth.live().paused,false);
nativeFail=false;assert(nativeHealth.action('pause'));nativeHealth=loadNative();assert.equal(nativeHealth.live().paused,true);
assert(nativeHealth.action('finish'));nativeHealth=loadNative();assert.equal(nativeHealth.live(),null);assert.equal(nativeHealth.state.history.length,2);
nativeValue='broken';nativeHealth=loadNative();assert.equal(nativeHealth.action('start','Walking'),false);assert.equal(nativeValue,'broken','Corrupt native storage is preserved');
// Native notifications can change the session while the page is out of view.
const remote={active:{kind:'Cycling',startedAt:1000,elapsed:12000,resumedAt:null,targetMs:60000},history:[]};
nativeValue=JSON.stringify(remote);let nativeActionFailed=false;const calls=[];
sandbox.window.OrbitWorkouts={read:()=>nativeValue,write:()=>{throw Error('Actions must not replace the store')},elapsedMs:()=>12000,action:(name,kind,target)=>{calls.push([name,kind,target]);return nativeActionFailed?null:nativeValue}};
nativeHealth=loadNative();assert.equal(nativeHealth.live().elapsed,'00:12');assert.equal(nativeHealth.live().paused,true);
remote.active.resumedAt=13000;nativeValue=JSON.stringify(remote);nativeHealth.refresh();assert.equal(nativeHealth.live().paused,false,'Resume from notification must refresh the visible state');
assert(nativeHealth.action('resume'));assert.equal(calls.at(-1)[0],'resume');
nativeActionFailed=true;const last=nativeHealth.state;assert.equal(nativeHealth.action('finish'),false);assert.equal(nativeHealth.state,last,'Unconfirmed native actions must not alter the page state');
remote.active=null;nativeValue=JSON.stringify(remote);nativeHealth.refresh();assert.equal(nativeHealth.live(),null,'Finish from notification must remove the live bar');
delete sandbox.window.OrbitWorkouts;
assert(!m.Health.validTarget(-1));assert(!m.Health.validTarget(60001.5));assert(m.Health.validTarget(86400000));assert(!m.Health.validTarget(86400001));
// A rapid reopen cancels dismissal; the OS motion preference cannot disable Orbit transitions.
const surfaces=vm.runInContext('SurfaceMotion',sandbox),panel=node('#health-page'),pageAnimations=[];
panel.getBoundingClientRect=()=>({top:0,left:0,right:390,bottom:844,width:390,height:844});
panel.animate=(keyframes,options)=>{const animation={keyframes,options,cancel(){this.cancelled=true}};pageAnimations.push(animation);return animation};
reduced.matches=false;surfaces.reveal(panel);let closed=false;surfaces.dismiss(panel,()=>closed=true);assert(pageAnimations[0].cancelled);assert(!closed);surfaces.reveal(panel);assert(pageAnimations[1].cancelled);pageAnimations.at(-1).onfinish();assert(!closed,'Reopening must cancel a stale dismissal callback');
surfaces.dismiss(panel,()=>closed=true);pageAnimations.at(-1).onfinish();assert(closed);assert.equal(surfaces.active,0);
assert(pageAnimations.every(a=>a.options.duration<=200&&a.keyframes.every(f=>!Object.hasOwn(f,'clipPath'))));
reduced.matches=true;let preferredClosed=false;surfaces.reveal(panel);surfaces.dismiss(panel,()=>preferredClosed=true);assert(!preferredClosed);assert(surfaces.active>0);pageAnimations.at(-1).onfinish();assert(preferredClosed);assert.equal(surfaces.active,0);
// Countdown dots keep their animation and stop on disposal.
// Sleep chronology must preserve all displayed totals and cross midnight without gaps.
const sleep=vm.runInContext('SleepTimeline',sandbox);
for(const date of m.dateOptions){const daily=m.HealthData.daily(date),night=daily.night;if(!night)continue;assert(sleep.valid(night));const sum=sleep.totals(night);for(const stage of ['awake','light','deep','rem'])assert.equal(sum[stage],daily[stage]);assert.equal((night.end-night.start)/60000,daily.asleep+daily.awake)}
const night=m.HealthData.daily(m.anchor).night;assert.equal(night.segments.length,4);assert.equal(sleep.stageMinute(night,'light'),60);assert.equal(sleep.locate(night,479),3);assert.equal(sleep.totals(night).light,120);assert.equal(new Date(night.start).getDate()+1,new Date(night.end).getDate());
assert(!sleep.valid({...night,segments:night.segments.slice(1)}));assert(sleep.view(null).includes('No sleep recorded'));assert(!html.includes('id="bar-marker"'));
// Five-minute rendering changes presentation only, including at gaps and partial window edges.
const base=Date.parse('2026-09-13T23:35:00Z');
const recorded=parts=>{let at=base;return {start:base,end:base+parts.reduce((sum,[,minutes])=>sum+minutes,0)*60000,segments:parts.map(([stage,minutes])=>{const start=at;at+=minutes*60000;return {stage,start,end:at}})}};
const fragmented=recorded(Array.from({length:20},()=>[['light',4],['awake',1]]).flat()),original=JSON.stringify(fragmented);
const grouped=sleep.blocks(fragmented);assert.equal(grouped.length,1);assert.equal(grouped[0].stage,'light');assert.equal(grouped[0].end,fragmented.end);assert.equal(sleep.totals(fragmented).awake,20);assert.equal(JSON.stringify(fragmented),original);
const sustained=recorded([['light',5],['awake',10],['deep',5]]);assert.equal(sleep.blocks(sustained).map(s=>s.stage).join(','),'light,awake,deep');
const gaps=recorded([['light',4],['unrecorded',1],['light',3],['unknown',1],['deep',6]]),gapBlocks=sleep.blocks(gaps);
for(const missing of gaps.segments.filter(s=>['unrecorded','unknown'].includes(s.stage))){const shown=gapBlocks.find(s=>s.stage===missing.stage);assert.equal(shown.start,missing.start);assert.equal(shown.end,missing.end)}
assert.equal(sleep.blocks(recorded([['light',5],['deep',2.5],['light',2.5]]))[0].end,base+600000,'Equal-duration ties retain the preceding stage');
const weighted=recorded([['awake',1],['light',3],['awake',1]]);assert.equal(sleep.blocks(weighted)[0].stage,'light','Duration, not interval count, determines the block');
const partial=recorded([['light',3],['deep',5],['rem',3]]);partial.start+=120000;partial.segments[0].start=partial.start;assert(sleep.valid(partial));const clipped=sleep.blocks(partial);assert.equal(clipped[0].start,partial.start);assert.equal(clipped.at(-1).end,partial.end);
assert.equal(sleep.blocks(null).length,0);
const dots=vm.runInContext('HeroDots',sandbox),dotsDrawn=[];
for(const value of ['75.8','8,420','00:01','24:59:59','3','2','1']){const glyph=dots.layout(value);assert(glyph.points.length>0);assert(glyph.points.every(p=>p.x>0&&p.x<glyph.width&&p.y>0&&p.y<7));assert(dots.markup(value).includes('<title>'+value+'</title>'))}
assert(!dots.markup('<script>').includes('<script>'));
const dotCanvas=events({getContext:()=>({scale:noop,clearRect:()=>dotsDrawn.length=0,beginPath:noop,arc:(x,y)=>dotsDrawn.push([x,y]),fill:noop}),getBoundingClientRect:()=>({left:0,top:0,width:300,height:300}),setPointerCapture:noop,hasPointerCapture:()=>true});
reduced.matches=false;let dotsTime=testTime;const advanceDots=ms=>{const end=dotsTime+ms;while(dotsTime<end){dotsTime+=16;testTime=dotsTime;step(dotsTime)}};
const dotController=dots.mount(dotCanvas,3);advanceDots(1500);const resting=dotsDrawn.map(p=>[...p]);assert(resting.length>10);
dotCanvas.fire('pointerdown',{isPrimary:true,pointerId:1,clientX:170,clientY:150});advanceDots(500);assert(dotsDrawn.some((p,i)=>Math.hypot(p[0]-resting[i][0],p[1]-resting[i][1])>10),'Touch must displace the countdown dots');
dotCanvas.fire('pointercancel',{});advanceDots(1500);assert(dotsDrawn.every((p,i)=>Math.hypot(p[0]-resting[i][0],p[1]-resting[i][1])<1),'Cancelled touch must return to the glyph');
dotController.set(2);advanceDots(64);reduced.matches=true;reduced.fire('change');assert(frames.size>0,'OS preference must not disable particle animation');
dotController.stop();dotCanvas.fire('pointerdown',{isPrimary:true,pointerId:1,clientX:170,clientY:150});assert.equal(frames.size,0,'Disposed countdown must release pointer handlers');
const wideClock=dots.mount(dotCanvas,'24:59:59',true);advanceDots(1800);assert(dotsDrawn.length>100&&dotsDrawn.every(([x,y])=>x>0&&x<390&&y>0&&y<116),'A long focused clock must fit the wide canvas');const stableClock=dotsDrawn.map(p=>[...p]);wideClock.set('24:59:58');advanceDots(64);assert(stableClock.every((p,i)=>Math.hypot(dotsDrawn[i][0]-p[0],dotsDrawn[i][1]-p[1])<.2),'Changing a second must preserve existing clock cells instead of reshuffling the whole reading');wideClock.stop();
const music=vm.runInContext('MusicPlayer',sandbox);
const media={status:'ready',id:'1:2',artKey:'2',title:'Track',artist:'Artist',source:'Player',position:1000,duration:30000,playing:true,buffering:false,canToggle:true,canPrevious:true,canNext:false,canSeek:true,canOpen:true,art:''};
assert(music.valid(media));assert(music.valid({status:'permission'}));assert(!music.valid({...media,position:-1}));assert(!music.valid({...media,playing:'yes'}));assert(!music.valid({...media,art:'https://untrusted.example/cover.jpg'}));assert(!music.valid({...media,title:{html:'<script>'}}));assert(music.valid({...media,art:'data:image/jpeg;base64,/9j/'}));
const pattern=dots.pattern(0);assert.equal((pattern.match(/<circle/g)||[]).length,72);assert.notEqual(pattern,dots.pattern(1));assert.notEqual(pattern,dots.pattern(2));
for(const phrase of ['make it yours','In your rhythm','Find your','Your pace.','keep going'])assert(!fs.readFileSync(path.join(__dirname,'..','health-pages.js'),'utf8').includes(phrase));
assert(!html.includes('Body<br>composition'));assert(!html.includes('box-shadow:inset 0 1px 1px #ffffff5c'));
console.log('PASS: health-backed metric rendering, charts, input, workout persistence, clock, particles, gestures and surface lifecycle.');
