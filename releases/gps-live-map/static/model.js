export const COMBINED = '@combined';
export function stateOf(row, now, liveForMs, available) {
  const age = now - row.observedAtEpochMs;
  if (row.status === 'LIVE' && row.position && row.observedAtEpochMs && age >= -1000 && age <= liveForMs && available) return 'LIVE';
  return row.position ? 'STALE' : 'UNAVAILABLE';
}
export function rowsOf(data) {
  return [...(data.sources || []), ...(data.combination ? [{...data.combination, sourceId: COMBINED, label: 'Kombiniert'}] : [])];
}
export class Trails {
  constructor() { this.series = new Map(); this.session = undefined; }
  update(rows, now, liveForMs, available, session) {
    if (this.session !== session) this.series.clear();
    this.session = session;
    const present = new Set(rows.map(r => r.sourceId));
    for (const key of this.series.keys()) if (!present.has(key)) this.series.delete(key);
    for (const row of rows) {
      let s = this.series.get(row.sourceId);
      if (!s) { s = {points: [], lastStamp: null, broken: true}; this.series.set(row.sourceId, s); }
      s.points = s.points.filter(p => now - p.stamp <= 60000);
      if (stateOf(row, now, liveForMs, available) !== 'LIVE') { s.broken = true; continue; }
      const stamp = row.observedAtEpochMs;
      if (stamp === s.lastStamp) continue;
      if (s.lastStamp !== null && stamp < s.lastStamp) { s.broken = true; continue; }
      const previous = s.points.at(-1);
      const split = s.broken || !previous || stamp - previous.stamp > liveForMs;
      s.points.push({stamp, position: row.position, split});
      // Memory bounded even with a faulty high-rate clock. Normal UI is 1 Hz.
      if (s.points.length > 120) s.points.shift();
      s.lastStamp = stamp; s.broken = false;
    }
  }
  segments(id) {
    const segments = [];
    for (const p of this.series.get(id)?.points || []) {
      if (p.split || !segments.length) segments.push([]);
      segments.at(-1).push([p.position.latitudeDegrees, p.position.longitudeDegrees]);
    }
    return segments.filter(s => s.length > 1);
  }
}
// Choose the smallest longitude interval, including receivers around ±180°.
export function wrapPoints(points) {
  if (!points.length) return [];
  const lons = points.map(p => ((p[1] % 360) + 360) % 360).sort((a,b) => a-b);
  let gap = -1, start = lons[0];
  lons.forEach((lon, i) => {
    const next = lons[(i+1) % lons.length] + (i === lons.length-1 ? 360 : 0);
    if (next-lon > gap) { gap = next-lon; start = next % 360; }
  });
  const center = start + (360-gap)/2;
  const shift = center > 180 ? -360 : 0;
  return points.map(([lat,lon]) => { let x = ((lon % 360)+360)%360; if (x < start) x += 360; return [lat,x+shift]; });
}
