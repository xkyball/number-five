import { test } from 'node:test';
import assert from 'node:assert/strict';
import { issueToken,verifyToken,validateContact,isOwner,sameOrigin,boundedJson } from '../lib/contact-core.ts';
const secret='isolated-test-key-not-a-runtime-secret';
const base={name:'Test builder',email:'builder@example.test',project:'sonar',message:'I would like to reproduce this sonar experiment.',language:'en',token:'x',website:''};
test('signed tokens reject edits, wrong keys, premature and expired submissions',async()=>{
 const now=1793520000000;const token=await issueToken(secret,now);
 assert.equal(await verifyToken(secret,token,now+2000),true);
 assert.equal(await verifyToken(secret,token,now+1000),false);
 assert.equal(await verifyToken(secret,token,now+7200001),false);
 assert.equal(await verifyToken('different-key',token,now+2000),false);
 assert.equal(await verifyToken(secret,token.replace(token[15],token[15]==='a'?'b':'a'),now+2000),false);
 assert.equal(await verifyToken(secret,'garbage',now+2000),false);
});
test('owner access fails closed for anonymous, foreign and unconfigured identities',()=>{
 assert.equal(isOwner(null,'owner@example.test'),false);
 assert.equal(isOwner({email:'owner@example.test'},'owner@example.test'),false);
 assert.equal(isOwner({userId:'other',email:'other@example.test'},'owner@example.test'),false);
 assert.equal(isOwner({userId:'owner',email:'owner@example.test'},undefined),false);
 assert.equal(isOwner({userId:'owner',email:'owner@example.test'},'owner@example.test'),true);
});
test('cross-origin browser mutations are rejected',()=>{
 assert.equal(sameOrigin(new Request('https://boat.test/api/contact',{headers:{origin:'https://evil.test'}})),false);
 assert.equal(sameOrigin(new Request('https://boat.test/api/contact',{headers:{origin:'https://boat.test','sec-fetch-site':'cross-site'}})),false);
 assert.equal(sameOrigin(new Request('https://boat.test/api/contact',{headers:{origin:'https://boat.test'}})),true);
 assert.equal(sameOrigin(new Request('https://boat.test/api/contact')),false);
});
test('invalid content and bot honeypots are rejected without creating messages',()=>{
 assert.ok(validateContact(base));
 for(const change of [{website:'spam.test'},{project:'unknown'},{email:'not-email'},{message:'x'},{message:'x'.repeat(5001)},{name:'A'},{language:'xx'}])assert.equal(validateContact({...base,...change}),null);
});
test('oversize streamed JSON is refused before parsing',async()=>{
 const r=new Request('https://boat.test',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({message:'x'.repeat(17000)})});
 await assert.rejects(()=>boundedJson(r),/size/);
});
