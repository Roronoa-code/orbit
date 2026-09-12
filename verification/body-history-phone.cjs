// Authorized Galaxy only; the Audit package owns every profile/session changed by this check.
const {chromium}=require('playwright'),{execFileSync}=require('node:child_process'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {gestures}=require('./body-history.cjs'),serial=process.env.ORBIT_AUDIT_SERIAL;if(!serial)throw Error('Set the authorized Galaxy serial');
const adb=path.join(process.env.LOCALAPPDATA,'Android','Sdk','platform-tools','adb.exe'),cmd=(...args)=>execFileSync(adb,['-s',serial,...args],{encoding:'utf8',windowsHide:true}).trim(),out=path.join(__dirname,'player-settings-20260912','body-history');
(async()=>{
 assert.equal(cmd('shell','getprop ro.serialno'),'R5CY13S5F8D');cmd('forward','tcp:9224','localabstract:webview_devtools_remote_'+cmd('shell','pidof com.mani.orbit.audit'));
 const b=await chromium.connectOverCDP('http://127.0.0.1:9224'),p=b.contexts()[0].pages()[0],results={};
 const settle=()=>p.waitForFunction(()=>!SurfaceMotion.active&&!WorkoutFocus.active&&!islandFrame&&!motionFrame);
 const shot=name=>{cmd('shell','screencap','-p','/sdcard/orbit-body-history.png');cmd('pull','/sdcard/orbit-body-history.png',path.join(out,'phone-'+name+'.png'))};
 try{
  await p.reload();results.gestures=await gestures(p,p.context());shot('body');
  await p.evaluate(()=>Health.open('settings'));await settle();
  await p.locator('#profile-name').fill('Motion check');await p.locator('#profile-birth').fill('');await p.locator('#profile-birth').pressSequentially('29022000');assert.equal(await p.locator('#profile-birth').inputValue(),'29/02/2000');
  await p.locator('#profile-height').fill('179.5');await p.locator('#profile-weight').fill('78.1');await p.locator('#profile-form button').click();assert.equal(await p.locator('#profile-saved').textContent(),'Profile saved');
  await p.reload();await p.evaluate(()=>Health.open('settings'));await settle();assert.equal(await p.locator('#profile-birth').inputValue(),'29/02/2000');assert.equal(await p.locator('#profile-height').inputValue(),'179.5');assert.equal(await p.locator('#profile-weight').inputValue(),'78.1');shot('settings');results.profile='Native persistence and numeric birth-date entry PASS';
  await p.evaluate(()=>{if(Health.state.active)throw Error('Unexpected active Audit workout');Health.open('workouts')});await settle();await p.locator('[data-setup="Strength"]').click();await settle();assert.equal(await p.locator('#workout-weight').inputValue(),'78.1');
  await p.locator('#workout-setup-form button[type=submit]').click();await p.waitForFunction(()=>Health.state.active);await settle();await p.waitForTimeout(1000);await p.locator('#health-content [data-session="finish"]').click();await settle();assert.equal(await p.locator('#workout-record-body').count(),1);
  await p.locator('#health-back').click();await settle();assert.equal(await p.locator('[data-week-day]').count(),7);assert(await p.locator('.history-session').count()>0);await p.locator('#workout-calendar').scrollIntoViewIfNeeded();shot('history');
  const label=await p.locator('.history-week-nav p').textContent();await p.locator('.history-session').first().click();await settle();assert.equal(await p.locator('#workout-record-body').count(),1);await p.locator('#health-back').click();await settle();assert.equal(await p.locator('.history-week-nav p').textContent(),label);results.history='Native workout saved, compact daily summaries, full detail and calendar return PASS';
  await p.evaluate(()=>Health.close());await settle();await p.evaluate(()=>setDeckExpanded(true));await settle();await p.locator('#deck-scroll').evaluate(n=>n.scrollTop=150);await p.waitForTimeout(250);shot('home');assert.equal(await p.locator('.deck-frost').evaluate(n=>getComputedStyle(n).opacity),'1');results.fog='Native scroll fog with fade above and below the boundary PASS';
 }finally{await p.evaluate(()=>Health.close());cmd('shell','rm','-f','/sdcard/orbit-body-history.png');fs.writeFileSync(path.join(out,'phone-results.json'),JSON.stringify(results,null,2));await b.close()}
 console.log(JSON.stringify(results));
})().catch(e=>{console.error(e);process.exitCode=1});
