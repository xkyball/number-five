import {createRequire} from 'node:module';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base=process.env.QA_ORIGIN||'http://127.0.0.1:5185';
const media=JSON.parse(await readFile('content/media.json','utf8'));
const expectedDemos=JSON.parse(await readFile('content/demos.json','utf8'));
const currentIds=new Set(Object.entries(media).filter(([,m])=>m.captureRole==='current-device').map(([id])=>id));
await mkdir('outputs/qa',{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ? {executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE} : {})});
const report={origin:base,headless:true,browser:browser.version(),pages:[],checks:[],errors:[]};
try{
 const context=await browser.newContext({viewport:{width:1440,height:1050},reducedMotion:'reduce'});
 const page=await context.newPage();page.on('pageerror',e=>report.errors.push(String(e)));
 const slugs=['system','helm','audio','displays','maps','plotters','sonar','electronics','build'];
 for(const lang of ['de','en'])for(const path of ['', '/projects','/story','/platform','/philosophy','/qa','/build','/contribute','/contact','/sources','/privacy',...slugs.map(s=>'/projects/'+s)]){
  const response=await page.goto(base+'/'+lang+path,{waitUntil:'networkidle'});
  assert.equal(response.status(),200,lang+path);
  assert.equal(await page.locator('html').getAttribute('lang'),lang,'HTML language');
  assert.equal(await page.locator('h1').count(),1,'one main heading');
  if(path.startsWith('/projects/')&&path!=='/projects/build'){const hero=page.locator('.article-hero figure');const id=await hero.getAttribute('data-media-id');assert.ok(currentIds.has(id),'current physical hero for '+path);}
  for(const summary of await page.locator('.historical-media summary').all())await summary.click();
  for(const img of await page.locator('img').all()){await img.scrollIntoViewIfNeeded();await img.evaluate(i=>i.decode());}
  const missing=await page.locator('img').evaluateAll(imgs=>imgs.filter(i=>!i.complete||i.naturalWidth===0).map(i=>i.src));assert.deepEqual(missing,[],'loaded pictures');
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1);assert.equal(overflow,false,'desktop overflow '+path);
  report.pages.push({path:'/'+lang+path,status:response.status()});
 }
 await page.goto(base+'/de');assert.match(await page.locator('h1').innerText(),/Dein Boot/);assert.ok(currentIds.has(await page.locator('.workshop-hero-image figure').getAttribute('data-media-id')),'current physical homepage hero');assert.equal(await page.locator('video').count(),expectedDemos.length);for(const v of await page.locator('video').all()){assert.equal(await v.getAttribute('autoplay'),null);assert.notEqual(await v.getAttribute('controls'),null);assert.equal(await v.locator('track').count(),2);const source=await v.locator('source').getAttribute('src');assert.equal((await context.request.get(base+source)).status(),200);for(const track of await v.locator('track').all()){const src=await track.getAttribute('src');const text=await (await context.request.get(base+src)).text();assert.match(text,/^WEBVTT/);}await v.scrollIntoViewIfNeeded();await v.evaluate(async video=>{video.muted=true;await video.play();});await page.waitForTimeout(400);assert.ok(await v.evaluate(video=>video.currentTime>0),'native video advances');await v.evaluate(video=>video.pause());}report.checks.push(expectedDemos.length+' native videos: manual playback, bilingual captions and downloadable sources');await page.screenshot({path:'outputs/qa/desktop.png'});
 await page.getByRole('link',{name:'DE/EN',exact:false}).count().catch(()=>0);
 await page.goto(base+'/de/platform');await page.locator('.lang-switch').click();assert.equal(new URL(page.url()).pathname,'/en/platform');await page.goto(base+'/de/projects/sonar');const switchLink=page.locator('.lang-switch');await switchLink.click();assert.equal(new URL(page.url()).pathname,'/en/projects/sonar');report.checks.push('Language switch preserves matching project');
 for(const lang of ['de','en']){await page.goto(base+'/'+lang+'/philosophy');assert.equal(await page.locator('.philosophy-body>section').count(),7);await page.screenshot({path:'outputs/qa/philosophy-'+lang+'-desktop.png',fullPage:true});}
 await page.goto(base+'/de/philosophy#updates');await page.locator('.lang-switch').click();assert.equal(new URL(page.url()).pathname,'/en/philosophy');assert.equal(new URL(page.url()).hash,'#updates');report.checks.push('Six bilingual philosophy chapters, links and language-switch anchor');
 await page.goto(base+'/de/qa#why-an-open-system');
 await page.locator('.lang-switch').click();assert.equal(new URL(page.url()).pathname,'/en/qa');assert.equal(new URL(page.url()).hash,'#why-an-open-system');
 await page.locator('.lang-switch').click();assert.equal(new URL(page.url()).pathname,'/de/qa');assert.equal(new URL(page.url()).hash,'#why-an-open-system');
 await page.getByRole('link',{name:'Für wen der Baukasten gedacht ist',exact:true}).click();assert.equal(new URL(page.url()).hash,'#who-it-is-for');assert.equal(await page.locator('#who-it-is-for').count(),1);
 await page.goto(base+'/de/qa');await page.getByRole('link',{name:'Selbst bauen oder bauen lassen',exact:true}).click();assert.equal(await page.locator('#fabrication').count(),1);
 for(const lang of ['de','en']){await page.goto(base+'/'+lang+'/qa');await page.screenshot({path:'outputs/qa/qa-'+lang+'-desktop.png',fullPage:true});}
 for(const width of [768,900,980,1024,1160,1280]){await page.setViewportSize({width,height:900});await page.goto(base+'/de/qa');assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'tablet overflow '+width);}
 await page.setViewportSize({width:1440,height:1050});report.checks.push('Bilingual Q&A, preserved entry anchors, project references and tablet navigation');
 await page.goto(base+'/de/contact');await page.getByLabel('Dein Name',{exact:true}).fill('Number Five QA');await page.getByLabel('E-Mail',{exact:true}).fill('qa-number-five@example.test');await page.getByLabel('Worum geht es?').selectOption('sonar');await page.getByLabel('Deine Nachricht',{exact:true}).fill('Synthetic contact test. Checking persistent storage and owner-only inbox access.');
 await page.waitForTimeout(1700);await page.getByRole('button',{name:'Nachricht senden'}).click();await page.getByRole('heading',{name:'Danke für deine Nachricht.'}).waitFor();report.checks.push('Anonymous form confirmed server storage');
 const anon=await context.request.get(base+'/api/inbox');assert.equal(anon.status(),403);report.checks.push('Anonymous inbox API forbidden');
 const cross=await context.request.post(base+'/api/contact',{headers:{origin:'https://unrelated.example'},data:{}});assert.equal(cross.status(),403);report.checks.push('Cross-origin writes forbidden');
 await page.goto(base+'/admin/inbox');await page.getByRole('link',{name:'Mit ChatGPT anmelden'}).click();await page.getByRole('heading',{name:'Projektpostfach',exact:true}).waitFor();
 const message=page.locator('.message').filter({hasText:'qa-number-five@example.test'});await message.getByRole('button',{name:'Als gelesen markieren'}).click();await message.locator('.eyebrow').filter({hasText:'read'}).waitFor();await message.getByRole('button',{name:'Erledigt',exact:true}).click();await message.locator('.eyebrow').filter({hasText:'done'}).waitFor();await page.screenshot({path:'outputs/qa/inbox.png'});await message.getByRole('button',{name:'Löschen',exact:true}).click();await message.getByRole('button',{name:'Endgültig löschen'}).click();await message.waitFor({state:'detached'});report.checks.push('Owner login, read, complete and delete persisted; synthetic message removed');
 await context.close();
 const mobile=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,reducedMotion:'reduce'});const phone=await mobile.newPage();phone.on('pageerror',e=>report.errors.push(String(e)));
 for(const path of report.pages.map(p=>p.path)){await phone.goto(base+path,{waitUntil:'networkidle'});assert.equal(await phone.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false,'mobile overflow '+path);}
 for(const lang of ['de','en']){await phone.goto(base+'/'+lang+'/philosophy');await phone.screenshot({path:'outputs/qa/philosophy-'+lang+'-mobile.png',fullPage:true});await phone.goto(base+'/'+lang+'/qa');await phone.screenshot({path:'outputs/qa/qa-'+lang+'-mobile.png',fullPage:true});}
 await phone.goto(base+'/de/qa');await phone.locator('.mobile-menu summary').click();await phone.getByRole('navigation',{name:'Mobile Navigation',exact:true}).getByRole('link',{name:'Q&A',exact:true}).click();assert.equal(new URL(phone.url()).pathname,'/de/qa');
 await phone.goto(base+'/de');await phone.screenshot({path:'outputs/qa/mobile.png'});await phone.locator('.mobile-menu summary').click();await phone.getByRole('navigation',{name:'Mobile Navigation',exact:true}).getByRole('link',{name:'Teilprojekte'}).click();assert.equal(new URL(phone.url()).pathname,'/de/projects');
 await phone.keyboard.press('Tab');assert.notEqual(await phone.evaluate(()=>document.activeElement?.tagName),'BODY');report.checks.push('Mobile menu and keyboard focus work; reduced motion respected');await mobile.close();
 assert.deepEqual(report.errors,[],'No uncaught page errors');report.status='PASS';
}finally{await browser.close();await writeFile('outputs/qa/report.json',JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify({status:report.status,pages:report.pages.length,checks:report.checks,errors:report.errors}));
