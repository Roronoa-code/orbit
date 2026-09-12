// Compare the last animated frame with resting CSS, including glyphs inside moving controls.
const {chromium}=require('playwright'),{seedMedia}=require('./video-audit-scenes.cjs'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.join(__dirname,'..'),out=path.join(__dirname,'player-settings-20260912','endpoints');fs.mkdirSync(out,{recursive:true});
(async()=>{const b=await chromium.launch({headless:true}),results=[];try{
 for(const width of [390,384,320]){const p=await b.newPage({viewport:{width,height:832}});
  await p.route('**/workout-focus.js',r=>r.fulfill({contentType:'application/javascript',body:fs.readFileSync(path.join(root,'workout-focus.js'),'utf8').replace('return {toggle,layout,','window.focusProbe={begin,apply,finish,get current(){return morph}};return {toggle,layout,')}));
  await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await seedMedia(p);await p.evaluate(()=>{Health.action('start','Strength',1800000);Health.open('workouts')});await p.waitForTimeout(900);
  for(const pauseDelay of [0,500,80]){const paused=pauseDelay>0;if(paused){await p.evaluate(()=>Health.action('resume'));await p.waitForTimeout(400);await p.evaluate(()=>Health.action('pause'));await p.waitForTimeout(pauseDelay)}
   for(const open of [true,false]){
    const result=await p.evaluate(open=>{
     const selectors=['#session-time','.session-matrix','#focus-time-dots','.timer-mode-picker','.timer-mode-picker button','.timer-mode-picker .selection-pill','.session-actions button','.music-heading h2','.music-heading p','.music-controls button','.music-controls button svg','.workout-stat dt','.workout-stat dd'];
     const capture=()=>Object.fromEntries(selectors.flatMap(selector=>[...document.querySelectorAll(selector)].map((n,i)=>{
      let opacity=1;for(let a=n;a;a=a.parentElement){const s=getComputedStyle(a);opacity*=Number(s.opacity);if(s.visibility==='hidden')opacity=0}
      const r=n.getBoundingClientRect(),style=getComputedStyle(n);let text=null;
      if(n.firstChild?.nodeType===Node.TEXT_NODE&&n.firstChild.textContent.trim()){const range=document.createRange();range.setStart(n.firstChild,0);range.setEnd(n.firstChild,1);text=range.getBoundingClientRect().toJSON()}
      return [selector+':'+i,{rect:r.toJSON(),opacity:r.width&&r.height?opacity:0,text,weight:style.fontWeight}]
     })));
     const m=focusProbe.begin(document.querySelector('.workout-live'));m.p=open?1:0;m.target=open;focusProbe.apply();const held=capture();focusProbe.finish();const rested=capture();return {held,rested};
    },open);
    const deltas=[];for(const [name,a]of Object.entries(result.held)){const z=result.rested[name];if(a.opacity<.5||z.opacity<.5)continue;for(const key of ['x','y','width','height']){const d=Math.abs(a.rect[key]-z.rect[key]);if(d>.6)deltas.push({name,part:'box',key,delta:d})}if(a.text&&z.text)for(const key of ['x','y','width','height']){const d=Math.abs(a.text[key]-z.text[key]);if(d>.6)deltas.push({name,part:'glyph',key,delta:d})}if(a.text&&a.weight!==z.weight)deltas.push({name,part:'weight',from:a.weight,to:z.weight})}
    results.push({width,paused,pauseDelay,open,deltas});await p.waitForTimeout(500);
   }
  }await p.close();
 }fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results));assert(results.every(r=>!r.deltas.length),'Workout content changes size or position when animation hands over to resting CSS');
}finally{await b.close()}})().catch(e=>{console.error(e);process.exitCode=1});
