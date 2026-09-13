// Local data-boundary and rendered checks. All generated records exist only in this test.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function fixture(){
 const today=new Date(),date=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`,key=date(today),rows=[];
 const row=(type,id,start,value,extra={})=>({source:'com.sec.android.app.shealth',type,id,start,end:start,value,...extra});
 for(let ago=0;ago<90;ago++){
  const at=new Date(key+'T08:00:00');at.setDate(at.getDate()-ago);const stamp=at.getTime(),day=date(at);
  rows.push(row('stepsDay','steps-'+day,stamp,ago?1000+ago:8420,{date:day}),row('distanceDay','distance-'+day,stamp,ago?800+ago:5710,{date:day}),row('energyDay','energy-'+day,stamp,270,{date:day}),row('floorsDay','floors-'+day,stamp,2,{date:day}));
  rows.push(row('weight','w-'+day,stamp,75.8+ago*.01),row('fat','f-'+day,stamp,18),row('lean','l-'+day,stamp,62),row('oxygen','o-'+day,stamp,98));
  rows.push({type:'heartHour',source:'com.sec.android.app.shealth',date:day,hour:8,count:2,sum:150,low:70,high:80,latest:80,latestTime:stamp+1000});
  rows.push(row('nutrition','n-'+day,stamp,undefined,{name:'<img src=x onerror=alert(1)>',calories:550,protein:25,carbs:70,fat:null}),row('water','h-'+day,stamp,300));
  const end=new Date(day+'T07:00:00').getTime(),start=end-8*3600000;
  rows.push(row('sleep','s-'+day,start,undefined,{end,stages:[[start,start+3600000,1],[start+3600000,start+3*3600000,4],[start+3*3600000,start+5*3600000,5],[start+5*3600000,end,6]]}));
 }
 const start=new Date(key+'T10:00:00').getTime()-86400000;
 return {schema:1,date:key,rows,stepHours:[{start:new Date(key+'T08:00:00').getTime(),end:new Date(key+'T09:00:00').getTime(),value:8420}],workouts:[row('exercise','actual-1',start,undefined,{end:start+1800000,kind:'Swimming',title:'<img src=x onerror=alert(1)>',notes:'A recorded session',summary:{distance:500,energy:120,heartAverage:112},laps:[],segments:[]})],meta:{recordCount:rows.length,firstRecord:rows.at(-1).start,lastSync:Date.now(),historyAllowed:true}};
}
function model(){
 const context={window:{addEventListener(){},dispatchEvent(){}},CustomEvent:class{},console};vm.createContext(context);
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../health-data.js'),'utf8')+';globalThis.data=HealthData;',context);
 const data=context.data,input=JSON.parse(JSON.stringify(fixture())),key=input.date;
 assert.equal(data.daily(key).steps,null);data.accept(input);assert.equal(data.daily(key).steps,8420);assert.equal(data.daily(key).distance,5710);assert.equal(data.daily(key).latest,80);
 assert.equal(data.daily(key).heartSum/data.daily(key).heartCount,75);assert.equal(data.daily(key).asleep,420);assert.equal(data.body(key).muscle,null);assert(Math.abs(data.body(key).fatMass-13.644)<.0001);
 assert.equal(data.daily(key).meals[0].fat,null);assert.equal(data.workouts[0].kind,'Swimming');data.accept(input);assert.equal(data.workouts.length,1);
 const invalid=structuredClone(input);invalid.rows[0].source='other.app';assert.throws(()=>data.accept(invalid));assert.equal(data.daily(key).steps,8420);
 const duplicate=structuredClone(input);duplicate.rows.push(duplicate.rows[0]);assert.throws(()=>data.accept(duplicate));assert.equal(data.daily(key).steps,8420);
 const sparse=structuredClone(input);sparse.rows=sparse.rows.filter(r=>r.type==='sleep').slice(0,1);const sleep=sparse.rows[0];sleep.stages=[[sleep.start,sleep.start+600000,4],[sleep.start+1200000,sleep.end,2]];data.accept(sparse);
 assert.equal(data.daily(key).steps,null);assert.equal(data.daily(key).deep,null);assert(data.daily(key).sleepIncomplete);assert.equal(data.daily(key).asleep,470);
 console.log('PASS: source filter, replay deduplication, atomic rejection, real units, missing data, weighted heart rate and sleep gaps');
}
async function browser(){
 const {chromium}=require('playwright'),browser=await chromium.launch({headless:true}),out=path.join(__dirname,'samsung-import');fs.mkdirSync(out,{recursive:true});
 try{for(const width of [390,320])for(const populated of [false,true]){
  const page=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true}),errors=[];page.on('pageerror',e=>errors.push(e.stack));
  await page.addInitScript(({data,populated})=>{
   localStorage.setItem('orbit-workouts-v1',JSON.stringify({active:{kind:'Strength',startedAt:1,elapsed:5000,resumedAt:1},history:[]}));
   let revision='1';window.OrbitHealth={snapshot:known=>JSON.stringify({revision,available:true,permitted:populated,status:populated?'Samsung Health imported':'Connect Samsung Health',data:known===revision?null:populated?data:{schema:1,date:data.date,rows:[],workouts:[],meta:{}}}),load(){},connect(){window.connected=true},sync(){},permissions(){}};
  },{data:JSON.parse(JSON.stringify(fixture())),populated});
  await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');await page.waitForTimeout(500);
  assert.equal(await page.locator('#steps').innerText(),populated?'8,420':'—');assert.equal(await page.evaluate(()=>Health.state.active),null,'Old demo workouts must not resurrect');
  for(const metric of ['steps','heart','sleep','intake'])for(const period of [1,7,30]){
   await page.evaluate(({metric:m,period})=>{metric=m;days=period;render();renderCharts()},{metric,period});
   assert(!/NaN|undefined|Infinity/.test(await page.locator('#period-content').innerText()));
   assert(!/NaN|undefined|Infinity/.test(await page.locator('#bars').innerHTML()));assert(!/NaN|undefined|Infinity/.test(await page.locator('#line').innerHTML()));
  }
  for(const section of ['overview','body','sleep','workouts','settings']){
   await page.evaluate(which=>Health.open(which),section);await page.waitForTimeout(250);
   assert(!/NaN|undefined|Infinity/.test(await page.locator('#health-content').innerText()),section);
   if(section==='body')for(const field of ['fatMass','muscle','lean','weight']){await page.locator(`[data-body-metric=${field}]`).click();await page.waitForTimeout(350);assert(!/NaN|undefined|Infinity/.test(await page.locator('#health-content').innerText()))}
   if(section==='workouts'&&populated){await page.locator('.workout-tabs [data-workout-tab=history]').click();await page.waitForTimeout(250);await page.locator('[data-workout-detail^="samsung:"]').click();await page.waitForTimeout(250);assert.match(await page.locator('#health-content').innerText(),/Samsung Health/);assert.equal(await page.locator('#health-content img').count(),0);assert.equal(await page.locator('#health-content [data-session=finish]').count(),0)}
   if(section==='settings'){await page.locator('#profile-name').fill('Unsaved name');await page.evaluate(()=>HealthData.refresh());assert.equal(await page.locator('#profile-name').inputValue(),'Unsaved name')}
   await page.screenshot({path:path.join(out,`${populated?'records':'empty'}-${section}-${width}.png`)});
  }
  assert.deepEqual(errors,[]);await page.close();console.log(width,populated?'populated':'empty','PASS');
 }}finally{await browser.close()}
}
module.exports={fixture};if(require.main===module){model();browser().catch(e=>{console.error(e);process.exitCode=1})}
