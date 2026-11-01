import test from 'node:test';
import assert from 'node:assert/strict';
import {Trails,stateOf,wrapPoints,rowsOf} from '../static/model.js';
const now=100000;
const row=(stamp=now, lat=50)=>({sourceId:'one',status:'LIVE',observedAtEpochMs:stamp,position:{latitudeDegrees:lat,longitudeDegrees:4}});
test('original clock ages even when HTTP snapshots repeat, including loss and future timestamps',()=>{
  assert.equal(stateOf(row(),now,5000,true),'LIVE');
  assert.equal(stateOf(row(),now+5001,5000,true),'STALE');
  assert.equal(stateOf(row(),now,5000,false),'STALE');
  assert.equal(stateOf(row(now+5000),now,5000,true),'STALE');
  assert.equal(stateOf({...row(),position:null},now,5000,true),'UNAVAILABLE');
});
test('60-second history deduplicates original stamps, splits outage and removes expired entries',()=>{
  const t=new Trails();
  const update=(rows,time,available=true)=>t.update(rows,time,5000,available,'a');
  update([row()],now); update([row()],now+1000);
  assert.equal(t.series.get('one').points.length,1);
  update([row(now+1000)],now+1000);
  assert.equal(t.segments('one').length,1);
  update([row(now+1000)],now+2000,false);
  update([row(now+3000)],now+3000);
  update([row(now+4000)],now+4000);
  assert.equal(t.segments('one').length,2);
  update([row(now+65000)],now+65000);
  assert.equal(t.series.get('one').points.length,1);
  assert.equal(t.segments('one').length,0);
});
test('session change clears history; out of order source stamp does not join backwards',()=>{
  const t=new Trails();t.update([row()],now,5000,true,'a');
  t.update([row(now-1000)],now,5000,true,'a');
  assert.equal(t.series.get('one').points.length,1);
  t.update([row(now+1000)],now+1000,5000,true,'b');
  assert.equal(t.series.get('one').points.length,1);
});
test('one, three and many sources with distinct combination, without fixed receiver limit',()=>{
  for(const n of [1,3,40]) {
    const data={sources:Array.from({length:n},(_,i)=>({...row(),sourceId:String(i)})),combination:{position:null,status:'INVALID'}};
    assert.equal(rowsOf(data).length,n+1);
    assert.equal(rowsOf(data).at(-1).sourceId,'@combined');
  }
});
test('coincident points stay exact and date-line bounds use narrow interval',()=>{
  assert.deepEqual(wrapPoints([[50,4],[50,4]]),[[50,4],[50,4]]);
  const points=wrapPoints([[0,179.999],[0,-179.999]]);
  assert.ok(Math.abs(points[0][1]-points[1][1])<0.003);
});
