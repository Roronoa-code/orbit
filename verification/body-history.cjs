// Real touch dispatch catches implicit pointer-capture transfers that synthetic PointerEvents miss.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.join(__dirname,'player-settings-20260912','body-history');fs.mkdirSync(out,{recursive:true});
async function gestures(p,c){
  const d=await c.newCDPSession(p),selected=()=>p.locator('[data-body-metric][aria-pressed=true]').getAttribute('data-body-metric');
  async function swipe(selector,dx,hold=0){
    await p.locator(selector).scrollIntoViewIfNeeded();const r=await p.locator(selector).boundingBox(),x=r.x+r.width/2,y=r.y+r.height/2;
    await d.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});if(hold)await p.waitForTimeout(hold);
    for(let i=1;i<=10;i++){await d.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*i/10,y}]});await p.waitForTimeout(8)}
    await d.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  }
  await p.evaluate(()=>Health.open('body'));await p.waitForTimeout(700);
  const first=await p.locator('[data-body-metric=weight]').boundingBox(),last=await p.locator('[data-body-metric=lean]').boundingBox();
  await swipe('[data-body-metric=weight]',last.x-first.x,500);await p.waitForTimeout(700);assert.equal(await selected(),'lean','Hold and drag must commit on touch release');
  const fills=await p.evaluate(async()=>{const a=[],lit=()=>[...document.querySelectorAll('[data-mix-dot]')].filter(n=>n.getAttribute('fill')==='rgb(182,156,255)').length;document.querySelector('[data-body-metric=weight]').click();const start=performance.now();while(performance.now()-start<900){await new Promise(requestAnimationFrame);a.push(lit())}return a});
  assert.equal(fills.at(-1),100,'Weight must fill the ring');assert(fills.every((n,i)=>!i||n>=fills[i-1]),'Lean to Weight must never drain the ring');
  const rapid=[];for(let i=0;i<3;i++){await swipe('[data-body-dial]',145);rapid.push(await selected())}assert.deepEqual(rapid,['lean','muscle','fatMass'],'Every quick swipe must advance');await p.waitForTimeout(700);
  for(let i=0;i<3;i++)await swipe('[data-body-dial]',-145);assert.equal(await selected(),'weight','Fast reverse swipes must work');await p.waitForTimeout(700);
  const ranges=await p.locator('[data-body-range]').all(),a=await ranges[1].boundingBox(),z=await ranges[3].boundingBox();await swipe('[data-body-range="30"]',z.x-a.x,500);await p.waitForTimeout(700);assert.equal(await ranges[3].getAttribute('aria-pressed'),'true','Shared history selector drag');
  await p.locator('#health-scroll').evaluate(n=>n.scrollTop=0);
  const title=await p.locator('#health-title').boundingBox(),hero=await p.locator('.body-hero').boundingBox();assert(hero.y>=title.y+title.height+4,'Body reading overlaps title');assert.equal(await p.locator('#health-source').count(),0,'Source prose belongs with measurement notes');assert.match(await p.locator('.reading-notes').textContent(),/Samsung Health/);
  return {rapid,ring:[fills[0],fills.at(-1)],title,hero};
}
async function main(){
  const browser=await chromium.launch(),results=[];
  try{for(const width of [390,320]){
    const c=await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true,timezoneId:'Europe/London'}),p=await c.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));
    await p.addInitScript(data=>{window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:true,status:'Local fixture',data:known==='1'?null:data}),load(){}}},require('./samsung-import.cjs').fixture());await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
    await p.addStyleTag({content:':root{--orbit-inset-top:34px}.status,.home-indicator{display:none}.screen{height:100dvh!important;min-height:0!important}'});
    const result=await gestures(p,c);await p.screenshot({path:path.join(out,width+'-body.png')});
    await p.evaluate(()=>Health.close());await p.waitForTimeout(600);await p.evaluate(()=>setDeckExpanded(true));await p.waitForTimeout(700);
    await p.locator('#deck-scroll').evaluate(n=>n.scrollTop=150);await p.waitForTimeout(200);assert.equal(await p.locator('.deck-frost').evaluate(n=>getComputedStyle(n).opacity),'1');assert.match(await p.locator('.deck-frost').evaluate(n=>getComputedStyle(n).backdropFilter),/blur/);await p.screenshot({path:path.join(out,width+'-home.png')});
    await p.locator('#deck-scroll').evaluate(n=>n.scrollTop=0);await p.waitForTimeout(60);assert.equal(await p.locator('.deck-frost').evaluate(n=>getComputedStyle(n).opacity),'0');
    await p.evaluate(()=>Health.open('settings'));await p.waitForTimeout(600);assert.equal(await p.locator('#profile-birth').getAttribute('type'),null);
    await p.locator('#profile-birth').pressSequentially('29022000');assert.equal(await p.locator('#profile-birth').inputValue(),'29/02/2000');await p.locator('#profile-form button').click();assert.equal(await p.evaluate(()=>OrbitSettings.profile().birthDate),'2000-02-29');
    await p.locator('#profile-birth').fill('29/02/2001');await p.locator('#profile-form button').click();assert.match(await p.locator('#profile-error').textContent(),/valid birth/);assert.equal(await p.evaluate(()=>OrbitSettings.profile().birthDate),'2000-02-29');
    await p.locator('#profile-birth').fill('29/02/2000');await p.locator('#profile-form button').click();await p.screenshot({path:path.join(out,width+'-settings.png')});
    // Several weeks and a midnight crossing: calendar membership follows the local start date.
    await p.evaluate(()=>{
      const t=new Date();t.setHours(10,0,0,0);window.testHistory=[0,0,2,7,14].map((ago,i)=>{const d=new Date(t);d.setDate(d.getDate()-ago);d.setMinutes(i*10);return {kind:i%2?'Walking':'Strength',startedAt:d.getTime(),endedAt:d.getTime()+1800000,elapsed:1800000,weightKg:75,targetMs:0}});
      localStorage.setItem('orbit-workouts-v2',JSON.stringify({active:null,history:testHistory}));
    });await p.reload();await p.evaluate(()=>Health.open('workouts'));await p.waitForTimeout(300);await p.locator('.workout-tabs [data-workout-tab=history]').click();await p.waitForTimeout(350);
    assert.equal(await p.locator('details.workout-history').count(),0);assert.equal(await p.locator('[data-week-day]').count(),7);assert.equal(await p.locator('.history-session').count(),2);
    assert.match(await p.locator('.history-week-metrics').textContent(),/Recorded time/);await p.locator('#workout-calendar').scrollIntoViewIfNeeded();await p.screenshot({path:path.join(out,width+'-history.png')});
    await p.locator('[data-history-week="-1"]').click();await p.waitForTimeout(350);assert.equal(await p.locator('.history-session').count(),1);
    const label=await p.locator('.history-week-nav p').textContent();await p.locator('.history-session').click();await p.waitForTimeout(500);assert.equal(await p.locator('#workout-record-body').count(),1);
    await p.locator('#health-back').click();await p.waitForTimeout(500);assert.equal(await p.locator('.history-week-nav p').textContent(),label);assert.equal(await p.locator('.history-session').count(),1);
    await p.locator('[data-history-week="1"]').click();await p.waitForTimeout(350);assert(await p.locator('[data-history-week="1"]').isDisabled());
    // On Monday the current week has no other selectable day. Inspect a complete week.
    await p.locator('[data-history-week="-1"]').click();await p.waitForTimeout(350);
    const empty=await p.locator('[data-week-day]:not([disabled])').all();for(const n of empty){await n.click();if(await p.locator('.history-empty').count())break}assert.equal(await p.locator('.history-empty').count(),1);
    assert.equal(await p.evaluate(()=>JSON.parse(localStorage.getItem('orbit-workouts-v2')).history.length),5);assert.deepEqual(errors,[]);
    results.push({width,status:'PASS',...result});await c.close();
  }}finally{await browser.close()}
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results));
}
if(require.main===module)main().catch(e=>{console.error(e);process.exitCode=1});
module.exports={gestures};
