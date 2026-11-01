import { env } from 'cloudflare:workers';
import { boundedJson, digest, issueToken, sameOrigin, validateContact, verifyToken } from '../../../lib/contact-core';
export const dynamic='force-dynamic';
const reply=(body: unknown,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
export async function GET(){
  if(!env.DB || !env.CONTACT_SECRET) return reply({error:'unavailable'},503);
  return reply({token:await issueToken(env.CONTACT_SECRET)});
}
export async function POST(request: Request){
  if(!sameOrigin(request)) return reply({error:'origin'},403);
  if(!env.DB || !env.CONTACT_SECRET) return reply({error:'unavailable'},503);
  let input: unknown;
  try{input=await boundedJson(request);}catch{return reply({error:'invalid'},400);}
  const data=validateContact(input);
  if(!data || !await verifyToken(env.CONTACT_SECRET,data.token)) return reply({error:'invalid'},400);
  const now=Date.now(); const submissionKey=await digest(env.CONTACT_SECRET,`token:${data.token}`);
  const payloadHash=await digest(env.CONTACT_SECRET,JSON.stringify([data.name,data.email,data.project,data.message,data.language]));
  const ipHash=await digest(env.CONTACT_SECRET,`ip:${request.headers.get('cf-connecting-ip') || 'local'}`);
  try{
    const previous=await env.DB.prepare('SELECT id,payload_hash FROM contact_messages WHERE submission_key=?').bind(submissionKey).first<{id:string;payload_hash:string}>();
    if(previous) return previous.payload_hash===payloadHash ? reply({ok:true,reference:previous.id}) : reply({error:'reused'},409);
    const id=crypto.randomUUID();
    const result=await env.DB.prepare(`INSERT INTO contact_messages (id,created_at,name,email,project,message,language,status,ip_hash,submission_key,payload_hash)
      SELECT ?,?,?,?,?,?,?,'unread',?,?,? WHERE
      (SELECT COUNT(*) FROM contact_messages WHERE ip_hash=? AND created_at>?)<5 AND
      (SELECT COUNT(*) FROM contact_messages WHERE email=? AND created_at>?)<10 AND
      (SELECT COUNT(*) FROM contact_messages WHERE created_at>?)<100`).bind(id,now,data.name,data.email,data.project,data.message,data.language,ipHash,submissionKey,payloadHash,ipHash,now-3600000,data.email,now-86400000,now-86400000).run();
    if(!result.meta.changes) return reply({error:'rate'},429);
    return reply({ok:true,reference:id},201);
  }catch{return reply({error:'unavailable'},503);}
}
