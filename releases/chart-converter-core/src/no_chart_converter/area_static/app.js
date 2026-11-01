'use strict';
const $ = id => document.getElementById(id);
const Q = 2 ** 31, R = 6371008.8;
const number = n => new Intl.NumberFormat('de-DE').format(n);
const byteSize = n => n == null ? '—' : n >= 1e9 ? `${(n / 1e9).toLocaleString('de-DE', { maximumFractionDigits: 2 })} GB` : n >= 1e6 ? `${(n / 1e6).toLocaleString('de-DE', { maximumFractionDigits: 1 })} MB` : `${number(Math.round(n / 1e3))} kB`;
const safeText = (id, value) => { $(id).textContent = value; };
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
function world([lng, lat]) { const s = Math.sin(clamp(lat, -85, 85) * Math.PI / 180); return [(lng + 180) / 360, .5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)]; }
function geographic([x, y]) { return [x * 360 - 180, Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180 / Math.PI]; }
function validBBox(b) { return Array.isArray(b) && b.length === 4 && b.every(Number.isFinite) && -180 <= b[0] && b[0] < b[2] && b[2] <= 180 && -85 <= b[1] && b[1] < b[3] && b[3] <= 85; }
function coordinate(lng, lat) { return `${Math.abs(lat).toFixed(5)}° ${lat < 0 ? 'S' : 'N'}  ${Math.abs(lng).toFixed(5)}° ${lng < 0 ? 'W' : 'E'}`; }
let saved = null;
try { saved = JSON.parse(localStorage.getItem('no5.area-picker.selection.v1')); } catch {}
const state = {
  bbox: validBBox(saved?.bbox) ? saved.bbox : [18.405, -33.914, 18.434, -33.894],
  center: saved?.center?.length === 2 && saved.center.every(Number.isFinite) ? saved.center : [18.4178, -33.9033],
  zoom: Number.isFinite(saved?.zoom) ? clamp(saved.zoom, 3, 18) : 14,
  name: typeof saved?.name === 'string' ? saved.name.slice(0, 80) : 'Granger Bay',
  margin: Number.isFinite(saved?.margin) ? clamp(saved.margin, 0, 20) : .5,
  context: null, estimate: null, job: null, token: null, drag: null, tiles: new Map(), tileFailures: 0,
};
let estimateTimer, estimateAbort, renderFrame, pollTimer, estimateVersion = 0, coverageRenderKey = '';
const map = $('map');
const coverageLayer = document.createElement('canvas');
function persist() { try { localStorage.setItem('no5.area-picker.selection.v1', JSON.stringify({ bbox: state.bbox, center: state.center, zoom: state.zoom, name: state.name, margin: state.margin })); } catch {} }
function mapSize() { return [map.clientWidth, map.clientHeight]; }
function toScreen(p) { const c = world(state.center), v = world(p), [w, h] = mapSize(), scale = 256 * 2 ** state.zoom; return [(v[0] - c[0]) * scale + w / 2, (v[1] - c[1]) * scale + h / 2]; }
function fromScreen([x, y]) { const c = world(state.center), [w, h] = mapSize(), scale = 256 * 2 ** state.zoom; return geographic([c[0] + (x - w / 2) / scale, c[1] + (y - h / 2) / scale]); }
function marginBBox() {
  const actual = state.estimate?.exportBBox;
  if (validBBox(actual) && state.estimateKey === JSON.stringify([state.bbox,state.margin])) return actual;
  const [w, s, e, n] = state.bbox, dLat = state.margin / 60, dLng = dLat / Math.max(.1, Math.cos((s + n) / 2 * Math.PI / 180));
  return [Math.max(-180, w - dLng), Math.max(-85, s - dLat), Math.min(180, e + dLng), Math.min(85, n + dLat)];
}
function areaKm2() { const [w, s, e, n] = state.bbox; return R * R * (e - w) * Math.PI / 180 * Math.abs(Math.sin(n * Math.PI / 180) - Math.sin(s * Math.PI / 180)) / 1e6; }
function updateSelection() {
  const [w, s, e, n] = state.bbox;
  safeText('northwest', coordinate(w, n)); safeText('southeast', coordinate(e, s));
  for (const [id, v] of [['west', w], ['south', s], ['east', e], ['north', n]]) if (document.activeElement !== $(id)) $(id).value = v.toFixed(6);
  safeText('area-size', `${areaKm2().toLocaleString('de-DE', { maximumFractionDigits: 1 })} km²`);
  $('selection-label').firstChild.textContent = state.name || 'Gebietsauswahl';
  for (const b of $('presets').children) b.classList.toggle('active', b.dataset.name === state.name);
  requestRender(); persist();
}
function requestRender() { if (!renderFrame) renderFrame = requestAnimationFrame(() => { renderFrame = null; render(); }); }
function render() {
  renderTiles(); renderCoverage();
  const [width, height] = mapSize(), [w, s, e, n] = state.bbox;
  const nw = toScreen([w, n]), se = toScreen([e, s]);
  const rect = (node, b) => { const a = toScreen([b[0], b[3]]), z = toScreen([b[2], b[1]]); node.setAttribute('x', a[0]); node.setAttribute('y', a[1]); node.setAttribute('width', Math.max(0, z[0] - a[0])); node.setAttribute('height', Math.max(0, z[1] - a[1])); };
  rect($('selection-box'), state.bbox);
  const mb = marginBBox(), ma = toScreen([mb[0], mb[3]]), mz = toScreen([mb[2], mb[1]]);
  $('margin-box').setAttribute('d', `M${ma[0]} ${ma[1]}H${mz[0]}V${mz[1]}H${ma[0]}Z M${nw[0]} ${nw[1]}H${se[0]}V${se[1]}H${nw[0]}Z`);
  $('selection-dim').setAttribute('d', `M0 0H${width}V${height}H0Z M${nw[0]} ${nw[1]}H${se[0]}V${se[1]}H${nw[0]}Z`);
  Object.assign($('selection-drag').style, { left: `${nw[0]}px`, top: `${nw[1]}px`, width: `${Math.max(0, se[0] - nw[0])}px`, height: `${Math.max(0, se[1] - nw[1])}px` });
  for (const [id, x, y] of [['nw', nw[0], nw[1]], ['ne', se[0], nw[1]], ['sw', nw[0], se[1]], ['se', se[0], se[1]]]) Object.assign($(`corner-${id}`).style, { left: `${x}px`, top: `${y}px` });
  Object.assign($('selection-label').style, { left: `${clamp(nw[0] + 12, 75, Math.max(75, width - 230))}px`, top: `${clamp(nw[1] + 12, 100, Math.max(100, height - 115))}px` });
  const mPerPixel = Math.cos(state.center[1] * Math.PI / 180) * 2 * Math.PI * R / (256 * 2 ** state.zoom);
  const desired = mPerPixel * 70, power = 10 ** Math.floor(Math.log10(desired));
  const distance = [1, 2, 5, 10].map(v => v * power).filter(v => v <= desired).at(-1) || power;
  $('scale-line').style.width = `${distance / mPerPixel}px`; safeText('scale-label', distance >= 1000 ? `${distance / 1000} km` : `${distance} m`);
}
function renderTiles() {
  const template = state.context?.tileUrl;
  if (!template) return;
  const [width, height] = mapSize(), z = Math.floor(state.zoom), fraction = 2 ** (state.zoom - z), count = 2 ** z, c = world(state.center), tileSize = 256 * fraction;
  const x0 = Math.floor(c[0] * count - width / (2 * tileSize)), x1 = Math.floor(c[0] * count + width / (2 * tileSize));
  const y0 = Math.floor(c[1] * count - height / (2 * tileSize)), y1 = Math.floor(c[1] * count + height / (2 * tileSize));
  const wanted = new Set();
  for (let y = Math.max(0, y0); y <= Math.min(count - 1, y1); y++) for (let x = Math.max(0, x0); x <= Math.min(count - 1, x1); x++) {
    const key = `${z}/${x}/${y}`; wanted.add(key); let img = state.tiles.get(key);
    if (!img) { img = new Image(); img.alt = ''; img.draggable = false; img.onload = () => { state.tileFailures = 0; showMapNotice(false); }; img.onerror = () => { state.tileFailures++; showMapNotice(true, 'Basiskarte nicht erreichbar. Auswahl und lokale Abdeckung bleiben verfügbar.'); }; img.src = template.replace('{z}', z).replace('{x}', x).replace('{y}', y); state.tiles.set(key, img); $('tiles').append(img); }
    Object.assign(img.style, { left: `${(x - c[0] * count) * tileSize + width / 2}px`, top: `${(y - c[1] * count) * tileSize + height / 2}px`, width: `${tileSize + .2}px`, height: `${tileSize + .2}px` });
  }
  for (const [key, img] of state.tiles) if (!wanted.has(key)) { img.remove(); state.tiles.delete(key); }
}
function polygonLists(geo) {
  if (!geo) return [];
  if (geo.type === 'FeatureCollection') return geo.features.flatMap(polygonLists);
  if (geo.type === 'Feature') return polygonLists(geo.geometry);
  if (geo.type === 'Polygon') return [geo.coordinates];
  if (geo.type === 'MultiPolygon') return geo.coordinates;
  if (geo.type === 'GeometryCollection') return geo.geometries.flatMap(polygonLists);
  return [];
}
function renderCoverage() {
  const canvas = $('coverage-canvas'), [w, h] = mapSize(), dpr = window.devicePixelRatio || 1;
  const renderKey = JSON.stringify([w,h,dpr,state.center,state.zoom,state.context?.coverage?.source?.sha256,state.context?.coverage?.coverageGeoJSON?.features?.length]);
  if (renderKey === coverageRenderKey) return;
  coverageRenderKey = renderKey;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
  const ctx = canvas.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h);
  const geo = state.context?.coverage?.coverageGeoJSON;
  const trace = (c, polygon) => { c.beginPath(); for (const ring of polygon) { ring.forEach((point, i) => { const p = toScreen(point); i ? c.lineTo(...p) : c.moveTo(...p); }); c.closePath(); } };
  const features = geo?.type === 'FeatureCollection' ? geo.features : [];
  if (features.length && features.every(f => f.properties?.sourceCell && ['covered','excluded'].includes(f.properties?.role))) {
    if (coverageLayer.width !== canvas.width || coverageLayer.height !== canvas.height) { coverageLayer.width = canvas.width; coverageLayer.height = canvas.height; }
    const layer = coverageLayer.getContext('2d'), groups = new Map();
    for (const feature of features) { const key = feature.properties.sourceCell; if (!groups.has(key)) groups.set(key, []); groups.get(key).push(feature); }
    for (const group of groups.values()) {
      layer.setTransform(dpr, 0, 0, dpr, 0, 0); layer.clearRect(0, 0, w, h); layer.fillStyle = '#3f8870';
      layer.globalCompositeOperation = 'source-over';
      for (const f of group.filter(f => f.properties.role === 'covered')) for (const p of polygonLists(f)) { trace(layer, p); layer.fill('evenodd'); }
      layer.globalCompositeOperation = 'destination-out';
      for (const f of group.filter(f => f.properties.role === 'excluded')) for (const p of polygonLists(f)) { trace(layer, p); layer.fill('evenodd'); }
      layer.globalCompositeOperation = 'source-over'; ctx.globalAlpha = .045; ctx.drawImage(coverageLayer, 0, 0, w, h); ctx.globalAlpha = 1;
    }
    ctx.strokeStyle = 'rgba(56,119,103,.42)'; ctx.lineWidth = .8;
    for (const feature of features) { ctx.setLineDash(feature.properties.role === 'excluded' ? [3,3] : []); for (const p of polygonLists(feature)) { trace(ctx,p); ctx.stroke(); } }
    ctx.setLineDash([]);
  } else {
    ctx.fillStyle = 'rgba(63,136,112,.045)'; ctx.strokeStyle = 'rgba(56,119,103,.5)'; ctx.lineWidth = .8;
    for (const polygon of polygonLists(geo)) { trace(ctx,polygon); ctx.fill('evenodd'); ctx.stroke(); }
  }
}
function showMapNotice(show, text = '') { $('map-notice').hidden = !show; if (text) safeText('map-notice', text); }
function fitBBox(b, padding = 90) {
  const [width, height] = mapSize(), a = world([b[0], b[3]]), z = world([b[2], b[1]]);
  state.center = geographic([(a[0] + z[0]) / 2, (a[1] + z[1]) / 2]);
  state.zoom = clamp(Math.log2(Math.min(Math.max(100, width - 2 * padding) / ((z[0] - a[0]) * 256), Math.max(100, height - 2 * padding) / ((z[1] - a[1]) * 256))), 3, 18);
  requestRender(); persist();
}
function zoom(delta, anchor) { const [w, h] = mapSize(); anchor ||= [w / 2, h / 2]; const target = world(fromScreen(anchor)); state.zoom = clamp(state.zoom + delta, 3, 18); const scale = 256 * 2 ** state.zoom; state.center = geographic([target[0] - (anchor[0] - w / 2) / scale, target[1] - (anchor[1] - h / 2) / scale]); requestRender(); persist(); }
function position(event) { const r = map.getBoundingClientRect(); return [event.clientX - r.left, event.clientY - r.top]; }
map.addEventListener('pointerdown', event => {
  if (event.button !== 0 || event.target.closest('.map-controls,a')) return;
  const corner = event.target.closest('.corner'), selection = event.target.closest('#selection-drag');
  state.drag = { mode: corner ? corner.id.slice(7) : selection ? 'selection' : 'map', point: position(event), center: world(state.center), bbox: [...state.bbox] };
  map.setPointerCapture(event.pointerId); event.preventDefault();
});
map.addEventListener('pointermove', event => {
  const p = position(event), geo = fromScreen(p); safeText('cursor-coords', coordinate(...geo));
  const d = state.drag; if (!d) return;
  const scale = 256 * 2 ** state.zoom, dx = (p[0] - d.point[0]) / scale, dy = (p[1] - d.point[1]) / scale;
  if (d.mode === 'map') { state.center = geographic([clamp(d.center[0] - dx, 0, 1), clamp(d.center[1] - dy, 0, 1)]); requestRender(); return; }
  if (d.mode === 'selection') {
    const a = world([d.bbox[0], d.bbox[3]]), z = world([d.bbox[2], d.bbox[1]]), da = geographic([a[0] + dx, a[1] + dy]), dz = geographic([z[0] + dx, z[1] + dy]);
    const candidate = [da[0], dz[1], dz[0], da[1]]; if (validBBox(candidate)) state.bbox = candidate;
  } else {
    const b = [...d.bbox], epsilon = 1e-6;
    if (d.mode.includes('w')) b[0] = clamp(geo[0], -180, b[2] - epsilon); else b[2] = clamp(geo[0], b[0] + epsilon, 180);
    if (d.mode.includes('n')) b[3] = clamp(geo[1], b[1] + epsilon, 85); else b[1] = clamp(geo[1], -85, b[3] - epsilon);
    state.bbox = b;
  }
  updateSelection(); staleEstimate();
});
map.addEventListener('pointerup', () => { if (state.drag?.mode !== 'map') scheduleEstimate(); state.drag = null; persist(); });
map.addEventListener('pointercancel', () => { state.drag = null; scheduleEstimate(); });
map.addEventListener('wheel', event => { event.preventDefault(); zoom(event.deltaY > 0 ? -.5 : .5, position(event)); }, { passive: false });
map.addEventListener('dblclick', event => { if (!event.target.closest('button,a')) zoom(1, position(event)); });
map.addEventListener('keydown', event => {
  if (event.target !== map && !event.target.classList.contains('corner') && event.target.id !== 'selection-drag') return;
  if (['+', '=','-'].includes(event.key)) { event.preventDefault(); zoom(event.key === '-' ? -1 : 1); return; }
  const step = event.shiftKey ? 10 : 1, changes = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }, delta = changes[event.key]; if (!delta) return;
  event.preventDefault(); const scale = 256 * 2 ** state.zoom;
  if (event.target === map) { const c = world(state.center); state.center = geographic([c[0] + delta[0] * 60 / scale, c[1] + delta[1] * 60 / scale]); requestRender(); persist(); return; }
  const b = [...state.bbox], mode = event.target.id.slice(7);
  if (event.target.id === 'selection-drag') { const a = world([b[0], b[3]]), z = world([b[2], b[1]]), aa = geographic([a[0] + delta[0] / scale, a[1] + delta[1] / scale]), zz = geographic([z[0] + delta[0] / scale, z[1] + delta[1] / scale]); state.bbox = [aa[0], zz[1], zz[0], aa[1]]; }
  else { const g = world([mode.includes('w') ? b[0] : b[2], mode.includes('n') ? b[3] : b[1]]), c = geographic([g[0] + delta[0] / scale, g[1] + delta[1] / scale]); b[mode.includes('w') ? 0 : 2] = c[0]; b[mode.includes('n') ? 3 : 1] = c[1]; if (validBBox(b)) state.bbox = b; }
  updateSelection(); scheduleEstimate();
});
new ResizeObserver(requestRender).observe(map);
$('zoom-in').onclick = () => zoom(1); $('zoom-out').onclick = () => zoom(-1);
$('fit-selection').onclick = () => fitBBox(marginBBox());
$('fit-coverage').onclick = () => { const b = state.context?.coverage?.bounds; if (validBBox(b)) fitBBox(b); };
$('area-name').value = state.name; $('margin').value = state.margin;
$('area-name').addEventListener('input', event => { state.name = event.target.value; updateSelection(); scheduleEstimate(); });
$('margin').addEventListener('input', event => { state.margin = clamp(Number(event.target.value) || 0, 0, 20); updateSelection(); scheduleEstimate(); });
$('apply-coordinates').onclick = () => { const b = ['west','south','east','north'].map(id => Number($(id).value)); if (!validBBox(b)) return showError('Bitte ein gültiges Rechteck eingeben: West < Ost und Süd < Nord.'); state.bbox = b; showError(null); updateSelection(); fitBBox(marginBBox()); scheduleEstimate(); };
$('search-form').onsubmit = event => {
  event.preventDefault(); const input = $('search').value.trim(), norm = text => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
  const preset = state.context?.presets.find(p => norm(p.name).includes(norm(input)) || norm(p.id).includes(norm(input)));
  if (preset && input) return choosePreset(preset);
  const parts = input.replaceAll('−', '-').split(/[\s,;]+/).filter(Boolean).map(Number);
  if (parts.length === 2 && parts.every(Number.isFinite) && Math.abs(parts[0]) < 85 && Math.abs(parts[1]) <= 180) { state.center = [parts[1], parts[0]]; state.zoom = 14; safeText('search-note', 'Koordinaten gefunden · Auswahlrechteck bleibt erhalten.'); requestRender(); persist(); return; }
  safeText('search-note', 'Bitte einen gespeicherten Ort oder Breite, Länge eingeben.');
};
function choosePreset(preset) { state.bbox = [...preset.bbox]; state.center = [...preset.center]; state.name = preset.name.split(' ·')[0]; state.zoom = preset.zoom; $('area-name').value = state.name; $('search').value = ''; safeText('search-note', 'Lokale Orte oder z. B. −33.9033, 18.4178'); updateSelection(); fitBBox(marginBBox()); scheduleEstimate(); }
function showError(text) { $('error-box').hidden = !text; if (text) safeText('error-box', text); }
async function request(path, value, signal) {
  const response = await fetch(path, value === undefined ? { signal } : { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-No5-Token': state.token }, body: JSON.stringify(value), signal });
  const json = await response.json(); if (!response.ok) throw new Error(json.error || `HTTP ${response.status}`); return json;
}
function selectionRequest() {
  const levels = state.context?.coverage?.supportedLevels;
  return { bbox: [...state.bbox], name: state.name || 'Gebietskarte', marginNm: state.margin,
    minLevel: Array.isArray(levels) && levels.length ? Math.min(...levels) : 0,
    maxLevel: Array.isArray(levels) && levels.length ? Math.max(...levels) : 21 };
}
function staleEstimate() { estimateVersion++; state.estimate = null; $('export-button').disabled = true; safeText('estimate-size', '—'); safeText('node-count', '—'); safeText('estimate-state', 'Auswahl geändert'); }
function scheduleEstimate() { staleEstimate(); clearTimeout(estimateTimer); estimateAbort?.abort(); estimateTimer = setTimeout(estimate, 420); }
async function estimate() {
  if (!state.context?.backendAvailable || !state.context.coverage?.index?.ready || state.job) return;
  const version = ++estimateVersion; estimateAbort = new AbortController(); safeText('estimate-state', 'wird berechnet …');
  try {
    const result = await request('/api/estimate', selectionRequest(), estimateAbort.signal); if (version !== estimateVersion) return;
    state.estimate = result; state.estimateKey = JSON.stringify([state.bbox,state.margin]); const bytes = result.estimatedChartBytes ?? result.chartBytesEstimate ?? result.estimatedBytes ?? result.byteEstimate ?? result.bytes?.estimated;
    const exactSize = result.estimateKind === 'exact_materialized_wire_bytes_same_parent_and_request';
    safeText('estimate-size', byteSize(bytes)); safeText('estimate-state', bytes == null ? 'Daten geprüft' : exactSize ? 'Berechnet' : 'Grobe Schätzung');
    safeText('estimate-caption', bytes == null ? 'Die Größe folgt aus dem Exportindex.' : exactSize ? 'Aus den tatsächlich ausgewählten Daten berechnet' : 'Dateigröße kann abweichen · keine obere Grenze');
    safeText('node-count', number(result.candidateNodes ?? result.nodes ?? 0));
    const levels = result.levels ?? result.supportedLevels ?? state.context.coverage.supportedLevels;
    safeText('level-count', Array.isArray(levels) ? `${Math.min(...levels)}–${Math.max(...levels)} (${levels.length})` : 'alle vorhandenen');
    safeText('estimate-note', result.message || `${number(result.recordOccurrences ?? result.records ?? 0)} Objektvorkommen · ausgewählte Objekte behalten ihre vollständige Geometrie und Originalabfragen.`);
    $('export-button').disabled = result.canExport === false || result.indexReady === false || (result.candidateNodes ?? result.nodes) === 0;
    requestRender();
    showError(null);
  } catch (error) { if (error.name !== 'AbortError') { safeText('estimate-state', 'nicht verfügbar'); safeText('estimate-size', '—'); showError(error.message); } }
}
function applyContext(context) {
  state.context = context; state.token = context.sessionToken; const coverage = context.coverage, store = coverage?.outputStore;
  const profile = context.profile;
  if (profile) {
    safeText('profile-tag', `LOKAL · ${profile.id.toUpperCase()}${profile.atlasNames ? ' / ' + profile.atlasNames : ''}`);
    safeText('source-parent', `Ausgangskarte: ${profile.label}`);
    document.title = `No5 · Gebietskarten · ${profile.id.toUpperCase()}${profile.atlasNames ? ' / ' + profile.atlasNames : ''}`;
  }
  const good = context.backendAvailable && store?.available; $('storage-status').className = `storage ${good ? 'ready' : 'error'}`;
  $('storage-status').lastElementChild.textContent = good ? 'Samsung · Exportablage bereit' : 'Samsung / Exportablage nicht verfügbar';
  $('presets').replaceChildren(...context.presets.map(preset => { const button = document.createElement('button'); button.textContent = preset.name.split(' ·')[0]; button.dataset.name = preset.name.split(' ·')[0]; button.onclick = () => choosePreset(preset); return button; }));
  $('prepare-button').hidden = !context.backendAvailable || coverage?.index?.ready;
  $('prepare-button').disabled = !store?.available;
  if (!context.backendAvailable || context.backendError) showError(context.backendError || 'Das Exportbackend ist nicht verfügbar.');
  else if (!store?.available) showError('Bitte das verifizierte Samsung-Laufwerk anschließen und die Seite neu laden.');
  else if (!coverage?.index?.ready) { safeText('estimate-state', 'Index fehlt'); safeText('estimate-note', 'Einmalig den lokalen Exportindex im gewählten Ausgabeordner vorbereiten. Die Originalkarte bleibt unverändert.'); }
  if (!context.tileUrl) { $('attribution').hidden = true; showMapNotice(true, 'Basiskarte deaktiviert · lokale Kartenabdeckung'); }
  else { $('attribution').hidden = false; $('attribution').textContent = context.tileAttribution || 'Kartenquelle'; $('attribution').href = context.tileAttributionUrl; }
  updateSelection();
}
async function pollJob(id) {
  try {
    const job = await request(`/api/exports/${id}`); state.job = job;
    $('job-section').hidden = false; safeText('job-title', job.kind === 'prepare' ? 'Index wird vorbereitet' : 'Export läuft');
    const p = job.progress || {}, elapsed = Math.max(0, (Date.now() / 1000) - (job.startedAt || job.createdAt)); safeText('job-time', `${Math.floor(elapsed / 60)}:${String(Math.floor(elapsed % 60)).padStart(2, '0')}`);
    safeText('job-message', progressLabel(p) || (job.status === 'queued' ? 'Wartet auf Ausführung …' : 'Kartendaten werden verarbeitet …'));
    const fraction = Number.isFinite(p.completed) && p.total > 0 ? clamp(p.completed / p.total, 0, 1) : null;
    $('progress-fill').parentElement.classList.toggle('indeterminate', fraction === null && !['complete', 'failed'].includes(job.status)); $('progress-fill').style.width = job.status === 'complete' ? '100%' : fraction === null ? '' : `${fraction * 100}%`;
    $('job-events').replaceChildren(...job.events.slice(-3).map(e => { const li = document.createElement('li'); li.textContent = progressLabel(e) || 'Arbeitsschritt'; return li; }));
    if (job.status === 'complete') { state.job = null; safeText('job-title', job.kind === 'prepare' ? 'Exportindex bereit' : 'Export abgeschlossen'); if (job.kind === 'export') showResult(job); applyContext(await request('/api/context')); if (!reuseCompletedExtent(job)) scheduleEstimate(); return; }
    if (job.status === 'failed') { state.job = null; safeText('job-title', 'Vorgang angehalten'); showError(job.error); $('prepare-button').disabled = false; scheduleEstimate(); return; }
    pollTimer = setTimeout(() => pollJob(id), 750);
  } catch (error) { showError(error.message); pollTimer = setTimeout(() => pollJob(id), 2000); }
}
function progressLabel(event) {
  const phases = { verify_source: 'Ausgangskarte prüfen', spatial_index: 'Räumlichen Exportindex vorbereiten', materialize: 'Ausgewählte Kartendaten schreiben', complete: 'Datei gespeichert und geprüft', lumina_package: 'Passendes Lumina-Paket erzeugen', lumina_package_complete: 'RX5 und Lumina-Paket gespeichert und gebunden', lumina_package_failed: 'RX5 bereit · Lumina-Paket fehlgeschlagen' };
  const title = phases[event.phase] || event.message;
  if (!title) return '';
  if (!Number.isFinite(event.completed) || !(event.total > 0)) return title;
  return `${title} · ${event.phase === 'verify_source' ? byteSize(event.completed) + ' / ' + byteSize(event.total) : number(event.completed) + ' / ' + number(event.total)}`;
}
function showResult(job) {
  const result = job.result, chart = result.chart; if (!chart?.path) return showError('Der Export meldet keine gebundene RX5-Datei.');
  $('result-section').hidden = false; safeText('result-title', job.request.name); safeText('result-size', `${byteSize(chart.bytes)} · SHA256 geprüft`);
  safeText('result-path', chart.path); safeText('result-sha', chart.sha256); $('download-link').href = `/api/exports/${job.id}/download`; $('manifest-link').href = `/api/exports/${job.id}/manifest`;
  $('package-link').hidden = !result.package; if (result.package) $('package-link').href = `/api/exports/${job.id}/package`;
  safeText('package-note', result.package ? `Lumina-Paket für RX5-App ${result.luminaPackage.requiredAppVersionCode} · Geräteprüfung offen` : result.packageError ? 'RX5 ist bereit. Lumina-Paket: ' + result.packageError : '');
  $('package-note').hidden = !result.package && !result.packageError;
}
function reuseCompletedExtent(job) {
  if (job?.kind !== 'export' || job.status !== 'complete' || JSON.stringify(job.request) !== JSON.stringify(selectionRequest())) return false;
  state.estimate = { ...job.result, estimatedChartBytes: job.result.chart.bytes };
  state.estimateKey = JSON.stringify([state.bbox,state.margin]);
  safeText('estimate-size', byteSize(job.result.chart.bytes)); safeText('estimate-state', 'Erstellte Datei');
  safeText('estimate-caption', 'Tatsächliche Dateigröße für diese Auswahl'); safeText('node-count', number(job.result.candidateNodes));
  $('export-button').disabled = false; requestRender(); return true;
}
$('prepare-button').onclick = async () => { showError(null); $('prepare-button').disabled = true; try { const job = await request('/api/prepare', {}); state.job = job; pollJob(job.id); } catch (error) { showError(error.message); $('prepare-button').disabled = false; } };
$('export-button').onclick = async () => { showError(null); $('export-button').disabled = true; $('result-section').hidden = true; try { const job = await request('/api/exports', selectionRequest()); state.job = job; pollJob(job.id); } catch (error) { showError(error.message); scheduleEstimate(); } };
async function initialize() { updateSelection(); try { const context = await request('/api/context'); applyContext(context); const running = context.jobs.find(j => ['queued', 'running'].includes(j.status)); if (running) { state.job = running; pollJob(running.id); } else { const complete = context.jobs.filter(j => j.kind === 'export' && j.status === 'complete').at(-1); if (complete) showResult(complete); if (!reuseCompletedExtent(complete)) scheduleEstimate(); } } catch (error) { showError(error.message); safeText('estimate-state', 'Server nicht erreichbar'); } }
initialize();
