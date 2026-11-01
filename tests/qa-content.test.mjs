import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const qa=JSON.parse(await readFile(new URL('../content/qa.json',import.meta.url),'utf8'));
const keys=(object,expected)=>assert.deepEqual(Object.keys(object).sort(),expected.sort());
function paired(value){keys(value,['de','en']);for(const lang of ['de','en'])assert.ok(typeof value[lang]==='string'&&value[lang].trim().length>0);}

test('published Q&A has bilingual content and no source-identity fields',()=>{
  keys(qa,['notice','entries']);paired(qa.notice);
  const ids=new Set();
  for(const entry of qa.entries){
    keys(entry,['id','character','question','summary','sections','action','open','links']);
    assert.match(entry.id,/^[a-z][a-z0-9-]*$/);assert.ok(!ids.has(entry.id));ids.add(entry.id);
    for(const field of ['character','question','summary','action','open'])paired(entry[field]);
    assert.ok(entry.sections.length>0);
    for(const section of entry.sections){keys(section,['heading','body']);paired(section.heading);paired(section.body);}
    for(const link of entry.links){keys(link,['path','label']);paired(link.label);assert.match(link.path,/^[a-z][a-z0-9/-]*(#[a-z][a-z0-9-]*)?$/);}
  }
  assert.ok(ids.size>0);
  const published=JSON.stringify(qa);
  assert.doesNotMatch(published,/[\w.+-]+@[\w.-]+\.[a-z]{2,}/i,'no email addresses in public Q&A');
  assert.doesNotMatch(published,/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}/i,'no private message identifiers');
  assert.doesNotMatch(published,/https?:\/\/(?:outlook|mail\.google)/i,'no private mailbox links');
});
