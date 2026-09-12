// UI acceptance with isolated demonstration media and workout storage.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const out=path.join(__dirname,'video-audit-20260912','scenes');fs.mkdirSync(out,{recursive:true});
async function seedMedia(page){
  await page.evaluate(()=>{
    window.auditCover=tone=>{const c=document.createElement('canvas');c.width=c.height=600;const x=c.getContext('2d');x.fillStyle=tone==='dark'?'#15121c':tone==='bright'?'#fff':'#a77989';x.fillRect(0,0,600,600);if(tone!=='dark'){for(let i=0;i<24;i++){x.fillStyle=i%2?'#fff':'#1e263b';x.fillRect(i*25,0,12,600)}x.fillStyle='#c88b69';x.beginPath();x.arc(300,180,125,0,Math.PI*2);x.fill()}return c.toDataURL('image/jpeg')};
    window.auditMedia={status:'ready',id:'test-session',artKey:'busy',art:auditCover('busy'),title:'Busy artwork',artist:'Verification fixture',source:'Test music app',position:58000,duration:180000,playing:false,buffering:false,canToggle:true,canPrevious:true,canNext:true,canSeek:true,canOpen:true};
    window.auditCommands=[];window.auditAcknowledge=true;
    window.OrbitMusic={read(key){const s={...auditMedia};if(key===s.artKey)delete s.art;return JSON.stringify(s)},command(id,action,value){auditCommands.push({id,action,value});if(auditAcknowledge){if(action==='play')auditMedia.playing=true;if(action==='pause')auditMedia.playing=false;if(action==='seek')auditMedia.position=value}return true},connect(){}};
  });
}
async function materialBoard(page){
  await page.evaluate(()=>{
    const rail=document.querySelector('#live-island').cloneNode(true),body=document.querySelector('.body-metric-picker')?.cloneNode(true);
    rail.removeAttribute('id');rail.classList.add('is-open');rail.style.cssText='position:relative;inset:auto;height:134px;width:100%;opacity:1;pointer-events:auto';
    rail.querySelector('.live-island-body').style.cssText='opacity:1;transform:none';rail.querySelectorAll('.activity-choice').forEach(n=>n.style.cssText='opacity:1;transform:none');
    rail.querySelector('.island-frost').style.setProperty('--live-frost-height','134px');rail.querySelector('.island-surface').style.cssText='transform:none;border-radius:28px';
    rail.querySelectorAll('[id]').forEach(n=>n.removeAttribute('id'));
    const collapsed=rail.cloneNode(true);collapsed.classList.remove('is-open');collapsed.style.height='62px';collapsed.querySelector('.live-island-body').style.display='none';collapsed.querySelector('.island-frost').style.setProperty('--live-frost-height','62px');
    const sample=document.createElement('main');sample.id='audit-material-board';
    const timer='<div class="timer-mode-picker glass-track"><span class="selection-pill glass-indicator" style="width:50%;height:48px;top:4px;left:4px"></span><button aria-pressed="true">Elapsed</button><button>Remaining</button></div>';
    const round='<button class="icon-button glass-control" aria-label="Back">‹</button>';
    const card='<section class="glass-panel audit-card"><h2>Daily average</h2><strong>2,078 <small>kcal</small></strong><p>Sample · 2–8 Sept 2025</p><svg viewBox="0 0 250 45"><path d="M0 40H250" stroke="#ffffff14"/><path d="M0 30L35 20L80 25L135 10L190 18L250 6" stroke="#c6b0ee" fill="none"/></svg></section>';
    for(const name of ['Black','Text and shapes','Artwork']){
      const section=document.createElement('section');section.className='audit-material-scene';section.dataset.underlay=name;
      const under=document.createElement('div');under.className='audit-underlay';if(name==='Artwork')under.style.backgroundImage=`url(${auditCover('busy')})`;if(name==='Text and shapes')under.innerHTML='<p>ORBIT<br>0123456789<br>READINGS<br>0123456789<br>ORBIT<br>READINGS</p>';
      section.append(under);const parts=document.createElement('div');parts.className='audit-material-parts';parts.innerHTML='<h2>'+name+'</h2>'+collapsed.outerHTML+rail.outerHTML+(body?body.outerHTML:'')+timer+round+card;section.append(parts);sample.append(section);
    }
    document.querySelector('.phone').hidden=true;document.body.append(sample);
    const style=document.createElement('style');style.textContent=`body{overflow:auto}#audit-material-board{display:flex;gap:16px;padding:16px;width:max-content;font-size:14px}.audit-material-scene{position:relative;width:350px;overflow:hidden;border-radius:24px;background:#0a0a0c;isolation:isolate}.audit-underlay{position:absolute;inset:-50px;background-size:cover;background-position:center;z-index:-1}.audit-underlay p{color:#fff;font-size:48px;line-height:1.8;background:repeating-linear-gradient(90deg,#000 0 24px,#56377e 24px 48px)}.audit-material-parts{padding:16px;display:flex;flex-direction:column;gap:24px}.audit-material-parts>h2{font-size:18px;text-shadow:0 1px 5px #000}.audit-material-parts .body-metric-picker{margin:0;min-height:56px}.audit-material-parts .timer-mode-picker{margin:0;align-self:center}.audit-material-parts .timer-mode-picker button{z-index:1;flex:1}.audit-material-parts .icon-button{width:48px;height:48px;font-size:24px}.audit-card{border-radius:22px;padding:18px}.audit-card h2,.audit-card p{font-size:12px}.audit-card strong{display:block;font-size:28px;margin:12px 0}.audit-card small{font-size:12px}.audit-card svg{width:100%;height:55px}`;document.head.append(style);
  });
}
async function main(){
  const browser=await chromium.launch({headless:true}),results=[];
  try{
    for(const width of [390,320]){
      const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:2,recordVideo:{dir:out,size:{width,height:844}}});
      const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
      await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
      const settle=()=>page.waitForFunction(()=>!WorkoutFocus.active&&!SurfaceMotion.active&&!islandFrame&&!motionFrame);
      const shot=name=>page.screenshot({path:path.join(out,`${width}-${name}.png`)});
      await seedMedia(page);await page.evaluate(()=>Health.open('body'));await settle();
      for(const field of ['fatMass','lean','weight','muscle']){await page.locator(`[data-body-metric="${field}"]`).click();await page.waitForTimeout(650);assert.equal(await page.locator(`[data-body-metric="${field}"]`).getAttribute('aria-pressed'),'true');assert(await page.locator('.body-reading.is-current .matrix-reading').count())}
      for(const range of [7,30,90,365]){await page.locator(`[data-body-range="${range}"]`).click();await settle();assert(!await page.locator('#body-line').evaluate(n=>/NaN|Infinity/.test(n.getAttribute('d'))))}
      assert.match(await page.locator('#body-coverage').innerText(),/90 demonstration/);await shot('body');results.push({width,test:'F11/F12 Body metric and range identity',status:'PASS'});
      await page.evaluate(()=>Health.close());await settle();
      await page.evaluate(()=>Health.open('workouts'));await settle();await page.locator('[data-setup="Strength"]').click();await settle();await shot('setup');
      assert.match(await page.locator('[data-music-connect]').innerText(),/connected/);
      await page.locator('.setup-segments label').last().click();await settle();await page.locator('.workout-primary').click();await page.waitForFunction(()=>Boolean(Health.state.active));await settle();
      await page.waitForFunction(()=>document.querySelector('.music-cover img')?.complete);await page.waitForTimeout(650);await shot('normal');
      const started=await page.evaluate(()=>Health.state.active.startedAt),normalColour=await page.locator('.session-actions button:first-child').evaluate(n=>getComputedStyle(n).backgroundColor);
      for(let i=0;i<4;i++){
        await page.locator('[data-music-expand]').click();await page.waitForTimeout(120);
        if(i===0)await shot('focus-mid');
        await page.locator('#health-minimize').evaluate(n=>n.click());await page.waitForTimeout(90);
        await page.evaluate(()=>document.querySelector('[data-timer-focus]').click());await settle();
        if(!await page.locator('.timer-focused').count()){await page.locator('[data-timer-focus]').click();await settle()}
        assert.equal(await page.evaluate(()=>Health.state.active.startedAt),started);
        await page.locator('#health-minimize').click();await settle();assert.equal(await page.locator('.is-held').count(),0);
      }
      await page.locator('[data-music-expand]').click();await settle();await shot('focus');
      assert.equal(await page.locator('.session-actions button:first-child').evaluate(n=>getComputedStyle(n).backgroundColor),normalColour);
      assert.equal(await page.locator('.timer-scrim').evaluate(n=>getComputedStyle(n).opacity),'1');
      for(const tone of ['bright','dark']){await page.evaluate(tone=>{auditMedia.art=auditCover(tone);auditMedia.artKey=tone;MusicPlayer.refresh()},tone);await page.waitForTimeout(700);await shot(`focus-${tone}`)}
      await page.locator('#health-content [data-session="pause"]').click();assert(await page.locator('#health-content [data-session="resume"]').count());await shot('paused');
      const paused=await page.evaluate(()=>Health.live().elapsed);await page.locator('[data-music="toggle"]').click();assert.equal(await page.evaluate(()=>Health.live().elapsed),paused);assert.equal(await page.locator('[data-music="toggle"]').getAttribute('aria-label'),'Pause music');
      await page.evaluate(()=>{auditMedia.playing=false;auditMedia.buffering=true;MusicPlayer.refresh()});assert.match(await page.locator('.music-source').innerText(),/Buffering/);
      await page.evaluate(()=>{auditMedia.buffering=false;auditMedia.playback='error';MusicPlayer.refresh()});assert.match(await page.locator('.music-source').innerText(),/Playback error/);
      await page.evaluate(()=>{delete auditMedia.playback;MusicPlayer.refresh()});await page.locator('#health-content [data-session="resume"]').click();
      await page.locator('#health-minimize').click();await settle();await page.locator('[data-workout-detail="active"]').click();await settle();await shot('details');
      const dock=await page.locator('#workout-record-body .session-actions').boundingBox();assert(dock.bottom<=844||dock.y+dock.height<=844);
      await page.locator('#health-back').click();await settle();assert.equal(await page.evaluate(()=>Health.state.active.startedAt),started);
      await page.locator('#health-content [data-session="finish"]').click();await settle();assert.equal(await page.evaluate(started=>Health.state.history.filter(s=>s.startedAt===started).length,started),1);await shot('saved');
      await page.locator('#health-back').click();await settle();await page.locator('.workout-history summary').click();await settle();assert.match(await page.locator('.workout-history').innerText(),/Started.*\d\d:\d\d/s);assert.match(await page.locator('.workout-history').innerText(),/min.*sec/s);await shot('history');
      await page.evaluate(()=>Health.open('overview'));await settle();await page.locator('.oxygen-tile summary').click();await settle();await shot('health');
      assert.match(await page.locator('#health-content').innerText(),/demonstration.*recorded on this phone/s);
      await page.evaluate(()=>Health.close());await settle();await page.locator('#more').click();await settle();await page.locator('#reduce-motion').click();await page.evaluate(()=>{closeUtility();setDeckExpanded(true)});assert.equal(await page.evaluate(()=>deckProgress),1);await page.evaluate(()=>{setLiveOpen(true)});assert.equal(await page.evaluate(()=>islands.live.value),1);
      await page.reload();assert(await page.evaluate(()=>SurfaceMotion.reduced));await page.evaluate(()=>SurfaceMotion.setReduced(false));
      results.push({width,test:'F10/F17/F20-F28 focus reversals, media states, pause, details, save, history, Health, reduced motion',status:'PASS'});
      if(width===390){await seedMedia(page);await page.evaluate(()=>Health.open('body'));await settle();await materialBoard(page);await page.setViewportSize({width:1140,height:850});await page.screenshot({path:path.join(out,'material-board.png'),fullPage:true});fs.writeFileSync(path.join(out,'material-board.html'),(await page.content()).replace(/<script[\s\S]*?<\/script>/g,'').replace('<head>','<head><base href="../../../">'));}
      assert.deepEqual(errors,[]);await context.close();
    }
  }finally{await browser.close()}
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
}
module.exports={seedMedia,materialBoard};
if(require.main===module)main().catch(e=>{console.error(e);process.exitCode=1});
