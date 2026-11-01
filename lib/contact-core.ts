export const projectSlugs = ['system','helm','audio','displays','maps','plotters','sonar','electronics','build','other'];
export type ContactInput = { name: string; email: string; project: string; message: string; language: 'de'|'en'; token: string; website: string };
export function validateContact(input: unknown): ContactInput | null {
  if (!input || typeof input !== 'object') return null;
  const v = input as Record<string, unknown>;
  for (const k of ['name','email','project','message','language','token','website']) if (typeof v[k] !== 'string') return null;
  const data = Object.fromEntries(Object.entries(v).filter(([k]) => ['name','email','project','message','language','token','website'].includes(k)).map(([k,x]) => [k,(x as string).trim()])) as ContactInput;
  data.email = data.email.toLowerCase();
  if (data.name.length < 2 || data.name.length > 100 || data.email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email) || data.message.length < 20 || data.message.length > 5000 || !projectSlugs.includes(data.project) || !['de','en'].includes(data.language) || data.token.length > 256 || data.website) return null;
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(data.message + data.name)) return null;
  return data;
}
export function isOwner(user: { userId?: string; email?: string } | null, ownerEmail?: string) {
  return !!user?.userId && !!ownerEmail && user.email?.toLowerCase() === ownerEmail.toLowerCase();
}
export function sameOrigin(request: Request) {
  const origin = request.headers.get('origin');
  return !!origin && origin === new URL(request.url).origin && !['cross-site','same-site'].includes(request.headers.get('sec-fetch-site') || '');
}
const encode = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');
async function key(secret: string) { return crypto.subtle.importKey('raw',new TextEncoder().encode(secret),{name:'HMAC',hash:'SHA-256'},false,['sign','verify']); }
export async function digest(secret: string, value: string) { return encode(await crypto.subtle.sign('HMAC',await key(secret),new TextEncoder().encode(value))); }
export async function issueToken(secret: string, now = Date.now()) {
  const value = `${now}.${crypto.randomUUID()}`;
  return `${value}.${await digest(secret, value)}`;
}
export async function verifyToken(secret: string, token: string, now = Date.now()) {
  const parts=token.split('.');
  if (parts.length!==3 || !/^\d{13}$/.test(parts[0]) || !/^[a-f0-9-]{36}$/.test(parts[1])) return false;
  const age=now-Number(parts[0]);
  if(age<1500 || age>2*60*60*1000) return false;
  try {
    const raw=parts[2].replace(/-/g,'+').replace(/_/g,'/');
    const sig=Uint8Array.from(atob(raw),c=>c.charCodeAt(0));
    return await crypto.subtle.verify('HMAC',await key(secret),sig,new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
  } catch { return false; }
}
export async function boundedJson(request: Request, max=16384) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) throw new Error('type');
  const reader=request.body?.getReader(); if(!reader) throw new Error('body');
  let size=0; const chunks: Uint8Array[]=[];
  while(true) { const {done,value}=await reader.read(); if(done) break; size+=value.length; if(size>max){await reader.cancel();throw new Error('size');} chunks.push(value); }
  const data=new Uint8Array(size); let offset=0; for(const c of chunks){data.set(c,offset);offset+=c.length;}
  return JSON.parse(new TextDecoder().decode(data));
}
