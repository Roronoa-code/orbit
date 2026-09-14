// Local-only dense/multiple-session sleep and browser hold regression.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),path=require('node:path');
const {fixture}=require('./samsung-import.cjs');
function dense(){
 const data=fixture(),day=data.date,base=data.rows.find(r=>r.type==='sleep');data.rows=data.rows.filter(r=>r!==base);
 const start=new Date(day+'T07:35:00').getTime(),end=new Date(day+'T14:09:00').getTime(),stages=[];
 let cursor=start,i=0;const pattern=[[4,24],[5,19],[4,31],[1,1],[4,8],[6,12],[1,1],[4,3],[6,4],[1,1],[4,13],[1,1],[4,2]];
 while(cursor<end){const [type,minutes]=pattern[i++%pattern.length],next=Math.min(end,cursor+minutes*60000);stages.push([cursor,next,type]);cursor=next}
 data.rows.push({...base,start,end,stages});
 const nap=new Date(day+'T14:10:00').getTime();data.rows.push({...base,id:'second-sleep',start:nap,end:nap+88*60000,stages:[[nap,nap+88*60000,4]]});return data;
}
async function run(){const browser=await chromium.launch({headless:true});try{
 for(const width of [390,320]){
  const p=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];
  p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(data=>{window.sleepFixture=data;window.OrbitHealth={snapshot:known=>JSON.stringify({revision:'1',available:true,permitted:true,data:known==='1'?null:data}),load(){}}},dense());
  await p.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await p.locator('#orb-button').tap();await p.evaluate(()=>Health.open('sleep'));await p.waitForTimeout(350);
  assert.equal(await p.locator('.sleep-date-nav').count(),1);assert.equal(await p.locator('[data-sleep-session]').count(),0);
  assert.equal(await p.locator('[data-night-part]').count(),0);assert.equal(await p.locator('.sleep-breakdown button').count(),4);
  const model=await p.evaluate(()=>{const d=HealthData.daily(sleepFixture.date),t=SleepTimeline.totals(d.sleepTimeline);return {sessions:d.nights.length,valid:SleepTimeline.valid(d.sleepTimeline),asleep:d.asleep,total:t.light+t.rem+t.deep,gap:t.unrecorded,bars:d.sleepTimeline.segments.filter(s=>s.stage!=='unrecorded').length}});
  assert.equal(model.sessions,2);assert(model.valid);assert.equal(model.asleep,model.total);assert.equal(model.gap,1);assert.equal(await p.locator('[data-night-segment]').count(),model.bars);
  await p.screenshot({path:path.join(__dirname,'samsung-import',`sleep-apple-${width}.png`)});
  const cdp=await p.context().newCDPSession(p);
  async function holdDrag(selector,dx=0){const r=await p.locator(selector).boundingBox(),x=r.x+r.width/2,y=r.y+r.height/2;await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y}]});await p.waitForTimeout(650);for(let i=1;i<=10;i++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:x+dx*i/10,y}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})}
  await holdDrag('.sleep-summary p');assert.equal(await p.evaluate(()=>String(getSelection())), '');
  await holdDrag('#night-scrub',60);assert.equal(await p.locator('#night-cursor').getAttribute('opacity'),'1');assert.match(await p.locator('#night-scrub').getAttribute('aria-valuetext'),/\d\d:\d\d/);
  await p.locator('[data-night-stage=deep]').tap();assert.equal(await p.locator('[data-night-stage=deep]').getAttribute('aria-pressed'),'true');
  const firstDate=await p.locator('.sleep-date-nav>span').innerText();await p.locator('[data-night-date="-1"]').tap();await p.locator('[data-night-date="1"]').tap();assert.equal(await p.locator('.sleep-date-nav>span').innerText(),firstDate);
  await p.evaluate(()=>{const data=structuredClone(sleepFixture),r=data.rows.find(r=>r.id==='s-'+data.date);data.rows.push({...r,id:'overlap',stages:[[r.start,r.end,6]]});HealthData.accept(data);const d=HealthData.daily(data.date),t=SleepTimeline.totals(d.sleepTimeline);if(!SleepTimeline.valid(d.sleepTimeline)||t.unknown<=0||t.light+t.deep+t.rem!==d.asleep)throw Error('Overlap union lost data integrity')});
  await p.evaluate(()=>Health.open('settings'));await p.waitForTimeout(350);
  await p.locator('#profile-name').tap();await p.locator('#profile-name').fill('Local name');await holdDrag('#profile-name');assert.equal(await p.locator('#profile-name').inputValue(),'Local name');
  assert(await p.locator('#profile-name').evaluate(n=>{const e=new MouseEvent('contextmenu',{bubbles:true,cancelable:true});return !n.dispatchEvent(e)}));
  await p.locator('#profile-name').press('End');await p.locator('#profile-name').press('Backspace');assert.equal(await p.locator('#profile-name').inputValue(),'Local nam');
  assert(await p.locator('body').evaluate(n=>getComputedStyle(n).userSelect==='none'));
  await p.evaluate(()=>{HealthData.accept({schema:1,date:sleepFixture.date,rows:[],workouts:[],meta:{}});Health.open('sleep')});assert(await p.locator('.sleep-empty').isVisible());assert.equal(await p.locator('.sleep-date-nav').count(),1);
  assert.deepEqual(errors,[]);console.log(width,'PASS: dense daily union, session gap, conflict, one navigation, hold/scrub, editable text and empty day');await p.close();
 }
}finally{await browser.close()}}
if(require.main===module)run().catch(e=>{console.error(e);process.exitCode=1});
module.exports={dense};
