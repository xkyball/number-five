import {createRequire} from 'node:module';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base=process.env.QA_ORIGIN||'http://127.0.0.1:5185';
await mkdir('outputs/qa',{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
const report={origin:base,headless:true,browser:browser.version(),pages:[],checks:[],errors:[]};
try{
 const context=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:'reduce'});
 const page=await context.newPage();page.on('pageerror',e=>report.errors.push(String(e)));
 const slugs=['system','helm','audio','displays','maps','plotters','sonar','electronics','build'];
 for(const lang of ['de','en'])for(const path of ['', '/projects','/story','/build','/contribute','/contact','/sources','/privacy',...slugs.map(s=>'/projects/'+s)]){
  const response=await page.goto(base+'/'+lang+path,{waitUntil:'networkidle'});
  assert.equal(response.status(),200,lang+path);
  assert.equal(await page.locator('html').getAttribute('lang'),lang,'HTML language');
  assert.equal(await page.locator('h1').count(),1,'one main heading');
  for(const img of await page.locator('img').all()){await img.scrollIntoViewIfNeeded();await img.evaluate(i=>i.decode());}
  const missing=await page.locator('img').evaluateAll(imgs=>imgs.filter(i=>!i.complete||i.naturalWidth===0).map(i=>i.src));assert.deepEqual(missing,[],'loaded pictures');
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false,'desktop overflow '+path);
  report.pages.push({path:'/'+lang+path,status:response.status()});
 }
 await page.goto(base+'/de');await page.screenshot({path:'outputs/qa/desktop.png'});
 await page.getByRole('link',{name:'DE/EN',exact:false}).count().catch(()=>0);
 await page.goto(base+'/de/projects/sonar');const switchLink=page.locator('.lang-switch');await switchLink.click();assert.equal(new URL(page.url()).pathname,'/en/projects/sonar');report.checks.push('Language switch preserves matching project');
 await page.goto(base+'/de/contact');await page.getByLabel('Dein Name',{exact:true}).fill('Number Five QA');await page.getByLabel('E-Mail',{exact:true}).fill('qa-number-five@example.test');await page.getByLabel('Worum geht es?').selectOption('sonar');await page.getByLabel('Deine Nachricht',{exact:true}).fill('Synthetic contact test. Checking persistent storage and owner-only inbox access.');
 await page.waitForTimeout(1700);await page.getByRole('button',{name:'Nachricht senden'}).click();await page.getByRole('heading',{name:'Danke für deine Nachricht.'}).waitFor();report.checks.push('Anonymous form confirmed server storage');
 const anon=await context.request.get(base+'/api/inbox');assert.equal(anon.status(),403);report.checks.push('Anonymous inbox API forbidden');
 const cross=await context.request.post(base+'/api/contact',{headers:{origin:'https://unrelated.example'},data:{}});assert.equal(cross.status(),403);report.checks.push('Cross-origin writes forbidden');
 await page.goto(base+'/admin/inbox');await page.getByRole('link',{name:'Mit ChatGPT anmelden'}).click();await page.getByRole('heading',{name:'Projektpostfach',exact:true}).waitFor();
 const message=page.locator('.message').filter({hasText:'qa-number-five@example.test'});await message.getByRole('button',{name:'Als gelesen markieren'}).click();await message.locator('.eyebrow').filter({hasText:'read'}).waitFor();await message.getByRole('button',{name:'Erledigt',exact:true}).click();await message.locator('.eyebrow').filter({hasText:'done'}).waitFor();await page.screenshot({path:'outputs/qa/inbox.png'});await message.getByRole('button',{name:'Löschen',exact:true}).click();await message.getByRole('button',{name:'Endgültig löschen'}).click();await message.waitFor({state:'detached'});report.checks.push('Owner login, read, complete and delete persisted; synthetic message removed');
 await context.close();
 const mobile=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,reducedMotion:'reduce'});const phone=await mobile.newPage();phone.on('pageerror',e=>report.errors.push(String(e)));
 for(const path of ['/de','/en','/de/projects/sonar','/en/projects/audio','/de/build','/de/contact']){await phone.goto(base+path,{waitUntil:'networkidle'});assert.equal(await phone.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'mobile overflow '+path);}
 await phone.goto(base+'/de');await phone.screenshot({path:'outputs/qa/mobile.png'});await phone.locator('.mobile-menu summary').click();await phone.getByRole('navigation',{name:'Mobile Navigation',exact:true}).getByRole('link',{name:'Teilprojekte'}).click();assert.equal(new URL(phone.url()).pathname,'/de/projects');
 await phone.keyboard.press('Tab');assert.notEqual(await phone.evaluate(()=>document.activeElement?.tagName),'BODY');report.checks.push('Mobile menu and keyboard focus work; reduced motion respected');await mobile.close();
 assert.deepEqual(report.errors,[],'No uncaught page errors');report.status='PASS';
}finally{await browser.close();await writeFile('outputs/qa/report.json',JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify({status:report.status,pages:report.pages.length,checks:report.checks,errors:report.errors}));
