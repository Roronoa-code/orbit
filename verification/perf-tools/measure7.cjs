// Which DOM nodes paint images repeatedly during a scenario? Group PaintImage trace events by node.
const {chromium}=require('playwright');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const scenario=process.argv[2]||'deck',css=process.argv[3]||'';
(async()=>{
 const browser=await chromium.launch({headless:true});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:3,isMobile:true,hasTouch:true});
 const page=await context.newPage();
 const cdp=await context.newCDPSession(page);await cdp.send('DOM.enable');
 await page.goto('http://127.0.0.1:8784/signal-orbit-steps/index.html?perf-audit');
 await page.waitForSelector('#live-bar');if(css&&css.startsWith('js:'))await page.evaluate(css.slice(3));else if(css)await page.addStyleTag({content:css});await sleep(1500);
 await cdp.send('DOM.getDocument',{depth:0});
 await browser.startTracing(page,{screenshots:false,categories:['disabled-by-default-devtools.timeline','blink','cc']});
 if(scenario==='deck'){await page.click('#stack-open');await sleep(900);await page.evaluate(()=>document.querySelector('#live-bar').click());await sleep(900)}
 else if(scenario==='live'){await page.click('#live-bar');await sleep(700);await page.click('#live-bar');await sleep(700)}
 else await sleep(2000);
 const trace=JSON.parse((await browser.stopTracing()).toString('utf8'));
 const byNode=new Map(),other=new Map();
 for(const e of trace.traceEvents){
  if(e.name==='PaintImage'){const d=e.args?.data||{};const k=d.nodeId||d.nodeName||'?';const r=byNode.get(k)||{count:0,w:d.srcWidth,h:d.srcHeight,url:(d.url||'').slice(0,40)};r.count++;byNode.set(k,r)}
  if(e.name==='Paint'&&e.args?.data){const d=e.args.data;const k=(d.nodeId||'?')+'|'+(d.layerId||'');const r=other.get(k)||{count:0,clip:JSON.stringify(d.clip||[]).slice(0,60)};r.count++;other.set(k,r)}
 }
 console.log('--- PaintImage by node ---');
 for(const [k,r] of [...byNode].sort((a,b)=>b[1].count-a[1].count).slice(0,12)){
  let desc=String(k);
  if(typeof k==='number'){try{const {node}=await cdp.send('DOM.describeNode',{backendNodeId:k});const a=node.attributes||[];const g=n=>{const i=a.indexOf(n);return i>=0?a[i+1]:''};desc=`${node.nodeName.toLowerCase()}${g('id')?'#'+g('id'):''}${g('class')?'.'+g('class').split(' ').slice(0,3).join('.'):''}${node.pseudoType?'::'+node.pseudoType:''}`}catch(err){desc+=' (unresolved)'}}
  console.log(String(r.count).padStart(5),desc.slice(0,80),`${r.w}x${r.h}`,r.url);
 }
 console.log('--- Paint events by node|layer ---');
 for(const [k,r] of [...other].sort((a,b)=>b[1].count-a[1].count).slice(0,10)){
  const id=Number(k.split('|')[0]);let desc=k;
  if(id){try{const {node}=await cdp.send('DOM.describeNode',{backendNodeId:id});const a=node.attributes||[];const g=n=>{const i=a.indexOf(n);return i>=0?a[i+1]:''};desc=`${node.nodeName.toLowerCase()}${g('id')?'#'+g('id'):''}${g('class')?'.'+g('class').split(' ').slice(0,3).join('.'):''}`}catch{}}
  console.log(String(r.count).padStart(5),desc.slice(0,70),r.clip);
 }
 await browser.close();
})().catch(e=>{console.error(e);process.exitCode=1});
