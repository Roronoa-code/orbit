// Real rendered regressions from the 12 September video. Fresh storage; never phone workout data.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const baseline=process.argv.includes('--baseline');
const out=path.join(__dirname,'video-audit-20260912',baseline?'before':'after');
fs.mkdirSync(out,{recursive:true});
(async()=>{
  const browser=await chromium.launch({headless:true});
  const results=[];
  try{
    for(const width of baseline?[390]:[390,320]){
      const context=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:2});
      const page=await context.newPage(),errors=[];
      page.on('pageerror',e=>errors.push(e.message));
      const run=async(name,fn)=>{try{await fn();results.push({width,name,status:'PASS'})}catch(e){results.push({width,name,status:'FAIL',error:e.message})}};
      const ready=()=>page.waitForFunction(()=>!motionFrame&&!islandFrame&&!SurfaceMotion.active);
      await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html');
      await page.locator('#live-bar').waitFor();await ready();
      await page.screenshot({path:path.join(out,`${width}-home.png`)});
      await run('F05 Explore opens destinations on the first tap from expanded detail',async()=>{
        await page.evaluate(()=>setDeckExpanded(true));await ready();
        await page.locator('#live-bar').click();await ready();
        assert(await page.evaluate(()=>islands.live.open));
      });
      await run('F07 Settings responds at centre and edges after launcher changes',async()=>{
        for(const state of [false,true,false,true,false]){
          await page.evaluate(value=>setLiveOpen(value),state);await ready();
          const button=page.locator('#settings-open');await button.click();await ready();
          assert(await page.evaluate(()=>Health.page==='settings'));
          await page.evaluate(()=>Health.close());await ready();
        }
        const box=await page.locator('#settings-open').boundingBox();assert(box.width>=48&&box.height>=48,'Settings target is smaller than 48 CSS px');
        for(const position of [{x:3,y:24},{x:45,y:24}]){await page.locator('#settings-open').click({position});await ready();assert(await page.evaluate(()=>Health.page==='settings'));await page.evaluate(()=>Health.close());await ready()}
      });
      await page.evaluate(()=>{setLiveOpen(false);metric='intake';days=1;render();setDeckExpanded(true)});await ready();
      await run('F02 displayed averages and delta reconcile',async()=>{
        const values=await page.evaluate(()=>['#current-average','#previous-average','#difference'].map(id=>Number($(id).textContent.replace(/[^\d.-]/g,''))));
        assert.equal(values[2],Math.abs(values[0]-values[1]));
      });
      await run('F01 Intake comparison remains populated through 20 scroll and reversal cycles',async()=>{
        for(let i=0;i<20;i++){
          await page.evaluate(()=>{$('#deck-scroll').scrollTop=$('#deck-scroll').scrollHeight});
          await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(resolve)));
          const state=await page.locator('.comparison').evaluate(node=>{
            const value=node.querySelector('#current-average'),heading=node.querySelector('h2');
            return {value:value.textContent,heading:heading.textContent,opacity:[value,heading].map(el=>{let n=el,a=1;while(n&&n!==node.parentElement){a*=Number(getComputedStyle(n).opacity);n=n.parentElement}return a})};
          });
          assert(state.value.trim()&&state.heading.trim());assert(state.opacity.every(n=>n>.95),JSON.stringify(state));
          await page.evaluate(()=>setDeckExpanded(false));await page.waitForTimeout(75+(i%3)*50);
          await page.evaluate(()=>setDeckExpanded(true));await ready();
        }
        await page.evaluate(()=>{$('#deck-scroll').scrollTop=$('#deck-scroll').scrollHeight});
        await page.screenshot({path:path.join(out,`${width}-intake.png`)});
      });
      await run('F03 sample provenance is visible on every home metric',async()=>{
        for(const key of ['steps','heart','sleep','intake']){
          await page.evaluate(key=>{metric=key;render()},key);
          assert.match(await page.locator('#date-button').innerText(),/Demo.*2025/);
          assert.match(await page.locator('#orb-period').innerText(),/sample/i);
        }
      });
      assert.deepEqual(errors,[]);await context.close();
    }
  }finally{await browser.close()}
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
  for(const result of results)console.log(`${result.width} ${result.status} ${result.name}${result.error?' — '+result.error:''}`);
  if(!baseline&&results.some(r=>r.status==='FAIL'))process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1});
