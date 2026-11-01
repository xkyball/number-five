'use client';
import { useEffect,useState, type FormEvent } from 'react';
import { projects,t,type Lang } from '../../content/projects';
export default function ContactForm({lang}: {lang:Lang}){
  const [token,setToken]=useState(''); const [state,setState]=useState('loading'); const [error,setError]=useState('');
  useEffect(()=>{let active=true;fetch('/api/contact',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(d=>{if(active){const value=(d as {token?:string}).token;if(!value)throw Error();setToken(value);setState('ready');}}).catch(()=>{if(active)setState('offline');});return()=>{active=false;};},[]);
  async function submit(e: FormEvent<HTMLFormElement>){
    e.preventDefault(); const form=e.currentTarget;const d=new FormData(form);setState('sending');setError('');
    try{const r=await fetch('/api/contact',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({name:d.get('name'),email:d.get('email'),project:d.get('project'),message:d.get('message'),website:d.get('website'),language:lang,token})});
      if(!r.ok){setError(r.status===429?t(lang,'Zu viele Anfragen. Bitte versuche es in einer Stunde erneut.','Too many requests. Please try again in an hour.'):t(lang,'Deine Nachricht wurde nicht bestätigt. Prüfe die Angaben und versuche es erneut. Bei einem langen geöffneten Formular bitte die Seite neu laden.','Your message was not confirmed. Check the fields and try again. If the form has been open a long time, reload the page.'));setState('ready');return;}
      const result=await r.json();if(!(result as {ok?:boolean}).ok)throw Error();setState('sent');form.reset();
    }catch{setError(t(lang,'Keine Speicherbestätigung erhalten. Du kannst es erneut versuchen; dieselbe Nachricht wird dabei nicht doppelt angelegt.','No storage confirmation received. You can retry; the same message will not be stored twice.'));setState('ready');}
  }
  if(state==='sent') return <div className="form-success" role="status"><span className="eyebrow">{t(lang,'GESPEICHERT','SAVED')}</span><h2>{t(lang,'Danke für deine Nachricht.','Thanks for your message.')}</h2><p>{t(lang,'Sie ist in meinem privaten Projektpostfach angekommen. Wenn du eine Antwort möchtest, nutze ich die angegebene E-Mail-Adresse.','It has reached my private project inbox. If you would like a reply, I will use the email address you provided.')}</p><a className="text-link" href={`/${lang}`}>{t(lang,'Zurück zum Projekt','Back to the project')} ↗</a></div>;
  return <form onSubmit={submit} className="contact-form">
    <div className="form-pair"><label>{t(lang,'Dein Name','Your name')}<input name="name" autoComplete="name" required minLength={2} maxLength={100}/></label><label>E-Mail<input name="email" type="email" autoComplete="email" required maxLength={254}/></label></div>
    <label>{t(lang,'Worum geht es?','What is it about?')}<select name="project" defaultValue="other"><option value="other">{t(lang,'Das Gesamtprojekt / etwas anderes','The overall project / something else')}</option>{projects.map(p=><option key={p.slug} value={p.slug}>{p.title[lang]}</option>)}</select></label>
    <label>{t(lang,'Deine Nachricht','Your message')}<textarea name="message" required minLength={20} maxLength={5000} rows={7} aria-describedby="message-hint"/></label><small id="message-hint">{t(lang,'20–5.000 Zeichen. Bitte keine Passwörter oder Zugangsschlüssel senden.','20–5,000 characters. Please do not send passwords or access keys.')}</small>
    <div className="honeypot" aria-hidden="true"><label>Website<input name="website" autoComplete="off" tabIndex={-1}/></label></div>
    <p className="form-privacy">{t(lang,'Deine Originalnachricht und Kontaktdaten bleiben privat. Fragen können wir sinngemäß und ohne persönliche Einzelheiten in der Q&A-Sektion beantworten.','Your original message and contact details stay private. We may answer questions in the Q&A section in paraphrased form, with personal details removed.')} <a href={`/${lang}/privacy`}>{t(lang,'Datenschutz','Privacy')}</a>.</p>
    {state==='offline'&&<p role="alert" className="form-error">{t(lang,'Das Kontaktpostfach ist gerade nicht erreichbar. Bitte versuche es später erneut.','The contact inbox is currently unavailable. Please try again later.')}</p>}
    {error&&<p role="alert" className="form-error">{error}</p>}
    <button className="button" disabled={!token||state==='sending'} type="submit">{state==='loading'?t(lang,'Formular wird geladen …','Loading form …'):state==='sending'?t(lang,'Wird gespeichert …','Saving …'):t(lang,'Nachricht senden','Send message')} <span aria-hidden="true">↗</span></button>
  </form>;
}
