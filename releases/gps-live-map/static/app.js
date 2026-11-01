import {COMBINED, stateOf, rowsOf, Trails, wrapPoints} from './model.js';
const $ = id => document.getElementById(id);
const map = L.map('map', {maxZoom:24, zoomSnap:0.1, zoomDelta:1, attributionControl:true,fadeAnimation:false,zoomAnimation:false,markerZoomAnimation:false});
const tiles = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxNativeZoom:19, maxZoom:24, attribution:'© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
});
const context=L.map('context-map',{zoomControl:false,attributionControl:false,dragging:false,scrollWheelZoom:false,doubleClickZoom:false,boxZoom:false,keyboard:false,touchZoom:false,fadeAnimation:false,zoomAnimation:false});
const contextTiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19});
let detailWindow=null;
function syncOverview() {
  if(!map.hasLayer(tiles)) return;
  context.setView(map.getCenter(),17,{animate:false});
  if(!context.hasLayer(contextTiles)) contextTiles.addTo(context);
  if(!detailWindow) detailWindow=L.rectangle(map.getBounds(),{color:'#146958',weight:2,fillOpacity:.12,interactive:false}).addTo(context);
  else detailWindow.setBounds(map.getBounds());
}
map.on('moveend',syncOverview);
L.control.scale({imperial:false, maxWidth:180}).addTo(map);
const overlay = L.layerGroup().addTo(map), trails = new Trails();
const palette = ['#1975bc','#c45e20','#7b4fbd','#16806b','#b03e65','#827121'];
const knownSources = {'gps1_gpsd.GP':['USB-GPS',palette[0]],'lumina_gps.GP':['Lumina',palette[1]],'lumina_gps_lumina2.GP':['Lumina 2',palette[2]]};
let latest = null, received = 0, offset = 0, transportOK = false, autoFit = true, fitting = false, tileFailure = false, lastViewKey = '';
const reasonLabels = {
  OK:'übereinstimmende Quellen', SINGLE_SOURCE:'nur eine beitragende Quelle', NO_USABLE_SOURCE:'keine verwendbare Quelle',
  POSITION_OUTLIER:'räumlicher Ausreißer', TIME_OUTLIER:'Zeitversatz', NO_FRESH_POSITION:'keine frische Position',
  SOURCE_UNAVAILABLE:'Quelle fehlt', UNVERIFIED_FIX_CLOCK:'Messzeit nicht bestätigt', NO_VALID_FIX_QUALITY:'kein gültiger GPS-Fix',
  SOURCE_DISAGREEMENT:'Quellen widersprechen sich', SOURCE_CATALOG_TRUNCATED:'Quellenliste unvollständig',
  IMPLAUSIBLE_MOVEMENT:'unplausibler Positionssprung', IMPLAUSIBLE_SPEED:'unplausible Geschwindigkeit',
  COMBINATION_NOT_CONFIGURED:'Kombination nicht eingerichtet', TIME_MISMATCH:'Messzeiten passen nicht',
  NO_FRESH_FIX_QUALITY:'Fixqualität veraltet', FIX_QUALITY_TIME_MISMATCH:'Fixqualität zeitlich unpassend', UNSAFE_FIX:'unsicherer Fix'
};
const explain = value => reasonLabels[value] || value || 'nicht verfügbar';
function color(id) {
  if (id === COMBINED) return '#123d35';
  if (knownSources[id]) return knownSources[id][1];
  let hash=0;for(const character of id) hash=((hash*31)+character.charCodeAt(0))>>>0;
  return palette[hash % palette.length];
}
const label = row => row.sourceId===COMBINED ? 'Kombiniert' : knownSources[row.sourceId]?.[0] || row.label || row.sourceId;
const sourceName = id => knownSources[id]?.[0] || latest?.sources.find(s=>s.sourceId===id)?.label || id;
function node(tag, cls, text) { const e = document.createElement(tag); if(cls) e.className=cls; if(text!==undefined) e.textContent=text; return e; }
function pause() { if(fitting) return; autoFit=false; $('fit').setAttribute('aria-pressed','false'); }
map.on('dragstart zoomstart', pause);
$('fit').onclick = () => { autoFit=true; lastViewKey=''; $('fit').setAttribute('aria-pressed','true'); render(); };
tiles.on('tileerror', () => { tileFailure=true; });
tiles.on('tileload', () => { tileFailure=false; });
function ageText(row, now) {
  if (!row.observedAtEpochMs) return 'keine Messzeit';
  return `${Math.max(0,(now-row.observedAtEpochMs)/1000).toFixed(1)} s alt`;
}
function render() {
  if(!latest) return;
  const now=Date.now()+offset;
  const available=transportOK && Date.now()-received<3500 && latest.upstreamAvailable;
  const rows=rowsOf(latest), liveFor=latest.liveForMs || 5000;
  trails.update(rows, now, liveFor, available, latest.serverSessionId);
  const combo=latest.combination || {}, contributors=new Set((combo.contributors || []).map(x=>x.sourceId));
  const excluded=new Map((combo.excluded || []).map(x=>[x.sourceId, x.reason]));
  const current=rows.filter(r=>stateOf(r,now,liveFor,available)==='LIVE');
  const raw=current.map(r=>[r.position.latitudeDegrees,r.position.longitudeDegrees]);
  const wrapped=wrapPoints(raw), byId=new Map(current.map((r,i)=>[r.sourceId,wrapped[i]]));
  const alerts=[];
  if (!available) alerts.push('Verbindung zu den GPS-Daten unterbrochen. Letzte Werte sind veraltet.');
  else if(!current.length) alerts.push('Keine aktuellen GPS-Positionen.');
  if(latest.simulated) alerts.push('SIMULATION – keine realen Positionsdaten.');
  if(latest.omitted) alerts.push(`Quellenliste gekürzt: ${latest.omitted} Quellen fehlen.`);
  if(tileFailure) alerts.push('Kartenhintergrund nicht erreichbar; Positionsanzeige läuft weiter.');
  if(raw.some(p=>Math.abs(p[0])>85.05112878)) alerts.push('Position außerhalb der darstellbaren Kartenbreite. Originalkoordinaten stehen in der Liste.');
  $('notice').textContent=alerts.join(' ');
  $('connection').textContent=available ? `${current.filter(r=>r.sourceId!==COMBINED).length} Quellen live` : 'Datenverbindung fehlt';
  $('count').textContent=`${latest.sources.length}${latest.total>latest.sources.length ? ` / ${latest.total}`:''} Quellen`;
  $('sources').replaceChildren(); overlay.clearLayers();
  const visible=[];
  // Largest receiver rings first; smaller rings and the combined diamond stay visible at identical coordinates.
  const markerRows=[...rows.filter(r=>r.sourceId!==COMBINED)].reverse().concat(rows.filter(r=>r.sourceId===COMBINED));
  for(const row of rows) {
    const state=stateOf(row,now,liveFor,available), isCombined=row.sourceId===COMBINED;
    const card=node('article',`source ${state==='LIVE'?'':'stale'}`);
    card.style.setProperty('--source-color',color(row.sourceId));
    const head=node('div','source-head'), swatch=node('span',`swatch ${isCombined?'combined':''}`);
    head.append(swatch,node('span','',label(row))); card.append(head);
    card.append(node('div','coords',row.position ? `${row.position.latitudeDegrees.toFixed(7)}, ${row.position.longitudeDegrees.toFixed(7)}` : 'Keine Position'));
    let role=isCombined ? explain(combo.reason) : contributors.has(row.sourceId) ? 'trägt zur Kombination bei' : excluded.has(row.sourceId) ? `ausgeschlossen: ${explain(excluded.get(row.sourceId))}` : 'kein Beitrag zur Kombination';
    card.append(node('div','source-meta',`${state==='LIVE'?'Aktuell':state==='STALE'?'Veraltet':'Nicht verfügbar'} · ${ageText(row,now)} · ${role}`));
    $('sources').append(card);
  }
  for(const row of markerRows) {
    const state=stateOf(row,now,liveFor,available), isCombined=row.sourceId===COMBINED;
    const ink=state==='LIVE'?color(row.sourceId):'#889493';
    const currentPoint=byId.get(row.sourceId);
    const anchor=currentPoint?.[1] ?? row.position?.longitudeDegrees ?? 0;
    const align=([lat,lon])=>[Math.max(-85.05112878,Math.min(85.05112878,lat)),lon+360*Math.round((anchor-lon)/360)];
    for(const segment of trails.segments(row.sourceId)) L.polyline(segment.map(align),{color:ink,weight:isCombined?2.5:1.5,opacity:0.55,interactive:false}).addTo(overlay);
    if(!row.position) continue;
    const point=align(currentPoint || [row.position.latitudeDegrees,row.position.longitudeDegrees]);
    const opts={color:ink,weight:isCombined?2.5:2,fillColor:ink,fillOpacity:isCombined?0.12:0.05};
    let marker;
    if(isCombined) {
      marker=L.marker(point,{icon:L.divIcon({className:'diamond-icon',html:'<span></span>',iconSize:[18,18],iconAnchor:[9,9]})});
    } else {
      const index=latest.sources.findIndex(s=>s.sourceId===row.sourceId);
      marker=L.circleMarker(point,{...opts,radius:6+Math.min(index,12)*3});
    }
    marker.addTo(overlay).bindTooltip(node('span','',`${label(row)} · ${ageText(row,now)}`));
    if(isCombined) marker.getElement().style.color=ink;
    if(state==='LIVE') visible.push(point);
  }
  $('details').replaceChildren(node('div','',`Kombination: ${explain(combo.reason)}`),node('div','',`Beitragende Quellen: ${(combo.contributors || []).map(s=>sourceName(s.sourceId)).join(', ') || 'keine'}`));
  if(autoFit && visible.length) {
    const bounds=L.latLngBounds(visible);
    const top=Math.max($('notice').textContent ? $('notice').offsetTop+$('notice').offsetHeight+26 : 48, $('overview').offsetTop+$('overview').offsetHeight+26);
    const caption=document.querySelector('.map-caption');
    const viewKey=JSON.stringify([visible,top,map.getSize()]);
    if(viewKey!==lastViewKey) {
      fitting=true;
      map.fitBounds(bounds,{paddingTopLeft:[56,top],paddingBottomRight:[56,28+caption.offsetHeight+26],maxZoom:24,animate:false});
      fitting=false;lastViewKey=viewKey;
    }
    if(!map.hasLayer(tiles)) tiles.addTo(map);
    syncOverview();
  }
}
// Start neutral, never invent a boat position while waiting for data.
fitting=true; map.setView([0,0],1); fitting=false;
async function refresh() {
  const start=Date.now();
  try {
    const response=await fetch('/api/positions',{cache:'no-store',signal:AbortSignal.timeout(3000)});
    if(!response.ok) throw new Error('Unavailable');
    const data=await response.json();
    if(!Array.isArray(data.sources) || !Number.isFinite(data.serverNowEpochMs)) throw new Error('Invalid data');
    latest=data; received=Date.now(); offset=data.serverNowEpochMs-(start+received)/2; transportOK=true;
  } catch { transportOK=false; $('connection').textContent='Datenverbindung fehlt'; if(!latest) $('notice').textContent='GPS-Daten noch nicht erreichbar. Verbindung wird automatisch erneut versucht.'; }
  render(); setTimeout(refresh,Math.max(0,1000-(Date.now()-start)));
}
setInterval(render,1000);
map.on('resize',()=>render());
refresh();
