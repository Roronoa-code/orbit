// Use the installed Playwright package (NODE_PATH); every run owns a fresh browser context.
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const output=path.join(__dirname,'seven-ux-browser');fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  for(const name of process.argv.slice(2).length?process.argv.slice(2):['ux-motion','focus-music'])for(const width of [390,384,320]){
   const context=await browser.newContext({viewport:{width:1100,height:1000}}),page=await context.newPage(),errors=[];
   page.on('pageerror',error=>errors.push(error.message));
   try{
    await page.goto(`http://127.0.0.1:8784/signal-orbit-steps/verification/${name}.html`);
    await page.frameLocator('#live').locator('#live-bar').waitFor();
    await page.locator(width===320?'#narrow':'#wide').click();if(width===384)await page.locator('#live').evaluate(node=>node.style.width='384px');await page.locator('#run').click();
    await page.waitForFunction(()=>{const text=document.querySelector('#result').textContent;return text.includes('"status": "PASS"')||/Error:|FAIL:/.test(text)},{},{timeout:150000});
    const text=await page.locator('#result').innerText();
    if(!text.includes('"status": "PASS"'))throw Error(text+'\nPage errors: '+errors.join('; '));
    fs.writeFileSync(path.join(output,`${name}-${width}.json`),text+'\n');
    console.log(`${name} ${width}px PASS`);
   }finally{await context.close()}
  }
 }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
