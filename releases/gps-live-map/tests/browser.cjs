const {chromium}=require('playwright');
const {spawn}=require('node:child_process');
const path=require('node:path');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {once}=require('node:events');
(async()=>{
  const server=spawn('python3',['-B',path.join(__dirname,'fixture.py')],{stdio:['ignore','pipe','inherit']});
  let browser;
  try {
    const [chunk]=await once(server.stdout,'data');
    const base=`http://127.0.0.1:${String(chunk).trim()}`;
    browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE || undefined,args:['--disable-gpu','--renderer-process-limit=2']});
    const page=await browser.newPage({viewport:{width:1200,height:800}});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    let tilesOnline=true;
    await page.route('https://tile.openstreetmap.org/**',r=>tilesOnline ? r.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#dce8d7"/><path d="M0 50L256 210M60 0L190 256" stroke="#fff" stroke-width="9"/></svg>'}) : r.abort());
    let count=3, mode='normal', stamp=null, requests=0;
    function data(){
      const now=Date.now(), t=stamp || now;
      const sources=Array.from({length:count},(_,i)=>({sourceId:`gps-${i}`,label:`GPS ${i+1}`,status:'LIVE',observedAtEpochMs:t,
        position:{latitudeDegrees:50+(mode==='coincident'?0:i*0.00002),longitudeDegrees:4+(mode==='coincident'?0:i*0.00002)}}));
      if(mode==='outlier')sources.at(-1).position.latitudeDegrees+=0.01;
      return {version:1,serverNowEpochMs:now,fetchedAtEpochMs:now,serverSessionId:'synthetic',upstreamAvailable:true,
        liveForMs:5000,total:count+(mode==='truncated'?5:0),omitted:mode==='truncated'?5:0,sources,
        combination:{available:true,status:mode==='no-combination'?'INVALID':'LIVE',mode:'CONSENSUS',reason:mode==='no-combination'?'SOURCE_DISAGREEMENT':'OK',observedAtEpochMs:t,
          position:mode==='no-combination'?null:{latitudeDegrees:50,longitudeDegrees:4},contributors:[{sourceId:'gps-0'}],excluded:[{sourceId:'gps-2',reason:'POSITION_OUTLIER'}]}};
    }
    await page.route('**/api/positions',async r=>{
      requests++;
      if(mode==='disconnect')return r.abort();
      return r.fulfill({json:data()});
    });
    await page.goto(base);
    await page.waitForFunction(()=>document.querySelectorAll('.source').length===4);
    assert.equal(await page.locator('.diamond-icon').count(),1);
    assert.match(await page.locator('#details').innerText(),/GPS 1/);
    async function waitTick(){await page.waitForTimeout(1200);}
    async function markersInside(){
      assert.ok(await page.evaluate(()=>{
        const b=document.getElementById('map').getBoundingClientRect();
        return [...document.querySelectorAll('.leaflet-interactive,.diamond-icon')].every(e=>{
          const r=e.getBoundingClientRect();return r.left>=b.left-1&&r.right<=b.right+1&&r.top>=b.top-1&&r.bottom<=b.bottom+1;
        });
      }),'all current markers fit within map');
    }
    await markersInside();
    await page.waitForFunction(()=>document.querySelectorAll('.leaflet-tile-loaded').length>0);
    assert.ok(await page.locator('#context-map .leaflet-tile-loaded').count()>0);
    assert.equal(await page.locator('#map.leaflet-fade-anim').count(),0);
    tilesOnline=false;
    await page.reload();await page.waitForFunction(()=>document.querySelectorAll('.source').length===4);
    await page.locator('.leaflet-control-zoom-in').click();
    assert.equal(await page.locator('#fit').getAttribute('aria-pressed'),'false');
    await page.locator('#fit').click();assert.equal(await page.locator('#fit').getAttribute('aria-pressed'),'true');
    mode='coincident';await waitTick();await markersInside();
    assert.equal(await page.locator('.diamond-icon').count(),1);
    mode='outlier';await waitTick();await markersInside();
    mode='no-combination';await waitTick();assert.equal(await page.locator('.diamond-icon').count(),0);
    mode='truncated';count=40;await waitTick();assert.equal(await page.locator('.source').count(),41);
    assert.match(await page.locator('#notice').innerText(),/Quellenliste gekürzt/);
    mode='normal';count=1;await waitTick();assert.equal(await page.locator('.source').count(),2);
    stamp=Date.now()-10000;await waitTick();assert.equal(await page.locator('.source.stale').count(),2);
    assert.match(await page.locator('#notice').innerText(),/Keine aktuellen/);
    stamp=null;count=3;await waitTick();
    mode='disconnect';await waitTick();assert.equal(await page.locator('.source.stale').count(),4);
    assert.match(await page.locator('#notice').innerText(),/unterbrochen/);
    mode='normal';await waitTick();assert.equal(await page.locator('.source:not(.stale)').count(),4);
    assert.match(await page.locator('#notice').innerText(),/Kartenhintergrund/);
    await page.setViewportSize({width:390,height:844});await waitTick();
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.setViewportSize({width:1200,height:800});await waitTick();
    const output=process.env.GPS_MAP_TEST_OUTPUT || '/tmp/no5-gps-map-browser';fs.mkdirSync(output,{recursive:true});
    await page.screenshot({path:path.join(output,'synthetic-desktop.png')});
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({status:'PASS',headless:true,requests,scenarios:['1/3/40 sources','coincident points','outlier retained','no combination','truncation','stale clocks','disconnect/recovery','tile failure','manual/auto fit','mobile layout'],pageErrors:errors}));
  } finally {if(browser)await browser.close();server.kill('SIGTERM');await once(server,'exit');}
})().catch(e=>{console.error(e);process.exitCode=1;});
