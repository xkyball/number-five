import assert from 'node:assert/strict';
import {readFile, mkdir, writeFile} from 'node:fs/promises';
import path from 'node:path';

const origin=(process.env.QA_ORIGIN || 'http://127.0.0.1:5187').replace(/\/$/,'');
const reportPath=process.env.QA_REPORT || 'outputs/qa-metadata/http.json';
const search=JSON.parse(await readFile(new URL('../content/search.json',import.meta.url),'utf8'));
const demos=JSON.parse(await readFile(new URL('../content/demos.json',import.meta.url),'utf8'));
const userAgent='NumberFiveMetadataQA/1.0';
const report={origin,headless:true,scope:'Raw HTTP HTML, not an indexing or ranking measurement',pages:[],agentChecks:[],checks:[],status:'RUNNING'};
const decode=s=>s.replace(/&(?:amp|quot|apos|lt|gt|#39|#x27);/g,x=>({'&amp;':'&','&quot;':'"','&apos;':"'",'&lt;':'<','&gt;':'>','&#39;':"'",'&#x27;':"'"}[x]));
function tags(html,name){return [...html.matchAll(new RegExp('<'+name+'\\b[^>]*>','gi'))].map(([tag])=>Object.fromEntries([...tag.matchAll(/([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)].map(([,key,a,b])=>[key.toLowerCase(),decode(a??b)])));}
async function get(route,agent=userAgent){const response=await fetch(origin+route,{headers:{'user-agent':agent},signal:AbortSignal.timeout(30000)});return {status:response.status,html:await response.text()};}
function checkHead(html,lang,route){
  const head=html.match(/<head\b[^>]*>([\s\S]*?)<\/head>/i)?.[1];
  assert.ok(head,route+' has an initial head');
  const entry=search.pages[route];
  const title=[...head.matchAll(/<title\b[^>]*>([\s\S]*?)<\/title>/gi)].map(m=>decode(m[1]));
  assert.deepEqual(title,[entry.title[lang]+' · Number Five'],'one specific initial-head title');
  const meta=tags(head,'meta'),links=tags(head,'link');
  assert.deepEqual(meta.filter(m=>m.name==='description').map(m=>m.content),[entry.description[lang]],'one initial-head description');
  const canonical=`https://no5.christophhoffmann.net/${lang}${route?'/'+route:''}`;
  assert.deepEqual(links.filter(l=>l.rel==='canonical').map(l=>l.href),[canonical],'one canonical in initial head');
  for(const other of ['de','en'])assert.deepEqual(links.filter(l=>l.rel==='alternate'&&l.hreflang===other).map(l=>l.href),[`https://no5.christophhoffmann.net/${other}${route?'/'+route:''}`],'language alternative in initial head');
  assert.doesNotMatch(html,/data-vinext-streamed-icon=/,'no route-shaped streamed metadata marker');
  return canonical;
}
try{
  const titles=new Set(),descriptions=new Set(),videoIds=new Set();
  for(const lang of ['de','en'])for(const route of Object.keys(search.pages)){
    const url=`/${lang}${route?'/'+route:''}`;
    const {status,html}=await get(url);assert.equal(status,200,url);
    const canonical=checkHead(html,lang,route);
    assert.ok(!titles.has(search.pages[route].title[lang]),'unique title');titles.add(search.pages[route].title[lang]);
    assert.ok(!descriptions.has(search.pages[route].description[lang]),'unique description');descriptions.add(search.pages[route].description[lang]);
    const nodes=[...html.matchAll(/<script\b[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)].flatMap(m=>{const data=JSON.parse(m[1]);return data['@graph']||[data];});
    const videos=nodes.filter(n=>n['@type']==='VideoObject');
    for(const video of videos){
      const demo=demos.find(d=>canonical+`#demo-${d.id}`===video['@id']);assert.ok(demo,'video has a media source');
      assert.equal(video.uploadDate,demo.publishedAt,'publication time, not capture day');
      assert.match(video.uploadDate,/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/,'timezone-qualified DateTime');
      assert.ok(Number.isFinite(Date.parse(video.uploadDate)),'valid publication timestamp');videoIds.add(demo.id);
    }
    report.pages.push({path:url,status,canonicalInInitialHead:true,videoObjects:videos.length});
  }
  assert.equal(report.pages.length,44);assert.equal(videoIds.size,5);
  // These requests simulate user agents; they are not actual crawler visits.
  for(const agent of ['Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)','Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/149.0.0.0 Safari/537.36'])for(const lang of ['de','en']){
    const {status,html}=await get(`/${lang}/refit`,agent);assert.equal(status,200);checkHead(html,lang,'refit');report.agentChecks.push({agent,path:`/${lang}/refit`,simulated:true,canonicalInInitialHead:true});
  }
  const missing=await get('/de/privacy:13fu6ge');assert.equal(missing.status,404,'metadata identifier remains a true missing page');
  const contact=await get('/api/contact');assert.equal(contact.status,200,'contact token endpoint');
  const inbox=await get('/api/inbox');assert.equal(inbox.status,403,'anonymous inbox stays private');
  report.checks.push('44 initial HTML heads: unique title/description, canonical and paired languages','Five original publication timestamps on all visible video objects','No streamed icon route markers','Ordinary browser and simulated Googlebot receive initial-head metadata','Unknown URLs return 404; contact token works; anonymous inbox denied');
  report.status='PASS';
}catch(error){report.status='FAIL';report.error=String(error);throw error;}
finally{await mkdir(path.dirname(reportPath),{recursive:true});await writeFile(reportPath,JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify({status:report.status,pages:report.pages.length,report:reportPath,error:report.error}));}
