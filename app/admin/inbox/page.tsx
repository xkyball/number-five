import { env } from 'cloudflare:workers';
import { getChatGPTUser,chatGPTSignInPath,chatGPTSignOutPath } from '../../chatgpt-auth';
import { isOwner } from '../../../lib/contact-core';
import Inbox from '../../components/inbox';
export const dynamic='force-dynamic';
export const metadata={title:'Privates Projektpostfach',robots:{index:false,follow:false}};
export default async function InboxPage(){
 const user=await getChatGPTUser();
 if(!user)return <main className="private-page"><a href="/de" className="brand">NUMBER FIVE<span className="brand-mark">05</span></a><p className="eyebrow">PRIVATES PROJEKTPOSTFACH</p><h1>Nur für den Projekteigentümer.</h1><p>Kontaktanfragen sind nicht öffentlich. Melde dich mit dem Eigentümerkonto an.</p><a className="button" href={chatGPTSignInPath('/admin/inbox')} target="_top">Mit ChatGPT anmelden ↗</a></main>;
 if(!isOwner(user,env.OWNER_EMAIL))return <main className="private-page"><h1>Kein Zugriff</h1><p>Dieses Konto ist nicht für das Projektpostfach freigeschaltet.</p><a href={chatGPTSignOutPath('/de')} target="_top">Abmelden</a></main>;
 let rows;try{rows=await env.DB!.prepare('SELECT id,created_at,name,email,project,message,language,status FROM contact_messages ORDER BY created_at DESC LIMIT 200').all();}catch{return <main className="private-page"><h1>Postfach vorübergehend nicht erreichbar</h1><p>Bitte später erneut versuchen.</p></main>;}
 return <main className="private-page"><div className="message-top"><a href="/de" className="brand">NUMBER FIVE<span className="brand-mark">05</span></a><a href={chatGPTSignOutPath('/de')} target="_top">Abmelden</a></div><p className="eyebrow">NUR FÜR DICH · KEINE E-MAIL-BENACHRICHTIGUNGEN</p><h1>Projektpostfach</h1><p>Die neuesten 200 Anfragen. Antworten öffnest du über die jeweilige E-Mail-Adresse in deinem Mailprogramm.</p><Inbox initial={rows.results as any}/></main>;
}
