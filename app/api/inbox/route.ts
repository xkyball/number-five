import { env } from 'cloudflare:workers';
import { getChatGPTUser } from '../../chatgpt-auth';
import { boundedJson,isOwner,sameOrigin } from '../../../lib/contact-core';
export const dynamic='force-dynamic';
const reply=(body: unknown,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'private, no-store','X-Robots-Tag':'noindex, nofollow'}});
async function allowed(){return isOwner(await getChatGPTUser(),env.OWNER_EMAIL);}
export async function GET(){
  if(!await allowed()) return reply({error:'forbidden'},403);
  if(!env.DB) return reply({error:'unavailable'},503);
  try{const rows=await env.DB.prepare('SELECT id,created_at,name,email,project,message,language,status FROM contact_messages ORDER BY created_at DESC LIMIT 200').all(); return reply({messages:rows.results});}catch{return reply({error:'unavailable'},503);}
}
export async function PATCH(request: Request){
  if(!sameOrigin(request) || !await allowed()) return reply({error:'forbidden'},403);
  let value: Record<string,unknown>;try{value=await boundedJson(request,1024);}catch{return reply({error:'invalid'},400);}
  if(typeof value.id!=='string' || !/^[a-f0-9-]{36}$/.test(value.id) || !['unread','read','done'].includes(String(value.status))) return reply({error:'invalid'},400);
  try{const r=await env.DB!.prepare('UPDATE contact_messages SET status=? WHERE id=?').bind(value.status,value.id).run();return reply({ok:!!r.meta.changes},r.meta.changes?200:404);}catch{return reply({error:'unavailable'},503);}
}
export async function DELETE(request: Request){
  if(!sameOrigin(request) || !await allowed()) return reply({error:'forbidden'},403);
  let value: Record<string,unknown>;try{value=await boundedJson(request,1024);}catch{return reply({error:'invalid'},400);}
  if(typeof value.id!=='string' || !/^[a-f0-9-]{36}$/.test(value.id)) return reply({error:'invalid'},400);
  try{const r=await env.DB!.prepare('DELETE FROM contact_messages WHERE id=?').bind(value.id).run();return reply({ok:!!r.meta.changes},r.meta.changes?200:404);}catch{return reply({error:'unavailable'},503);}
}
