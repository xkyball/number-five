import planningData from '../../content/planning.json';
import searchData from '../../content/search.json';
import { costRows } from '../../content/editorial';
import { t, type Lang, type Local } from '../../content/projects';
import { Architecture, Arrow, Picture } from './site';

type GuideSection = { id: string; title: Local; paragraphs: Local[]; bullets?: Local[]; links: {path: string; label: Local}[] };
type Guide = { kicker: Local; title: Local; deck: Local; image: string; answer: Local; sections: GuideSection[] };
const guides = planningData as Record<'refit'|'fabrication', Guide>;

export function PlanningLinks({lang}:{lang:Lang}) {
  return <section className="wrap planning-links" aria-label={t(lang,'Nachrüstung und Fertigung','Refit and fabrication')}>
    <div><p className="eyebrow">{t(lang,'FÜR DEIN BOOT','FOR YOUR BOAT')}</p><h2><a href={`/${lang}/refit`}>{t(lang,'Was möchtest du nachrüsten?','What would you like to upgrade?')} <Arrow/></a></h2><p>{t(lang,'Vorhandene Instrumente weiterverwenden, ein Display ergänzen oder Audio verbinden: vom eigenen Wunsch zu den passenden Bausteinen.','Keep existing instruments, add a display or connect your audio: start with your needs and find the right components.')}</p></div>
    <div><p className="eyebrow">{t(lang,'MIT WERKSTATTPARTNERN','WITH WORKSHOP PARTNERS')}</p><h2><a href={`/${lang}/fabrication`}>{t(lang,'Selbst bauen. Bauen lassen.','Build it. Have it built.')} <Arrow/></a></h2><p>{t(lang,'Ein individueller Steuerstand, Gehäuse und wartbare Anschlüsse. Für Bootseigner, Fertiger und Menschen, die den Einbau praktisch umsetzen.','A custom helm, enclosures and serviceable connections. For boat owners, fabricators and the people who make installations happen.')}</p></div>
  </section>;
}

export function PlanningQuestions({lang,slug}:{lang:Lang;slug:string}) {
  const questions = (searchData.questions as Record<string,{question:Local;answer:Local}[]>)[slug];
  if (!questions?.length) return null;
  return <section id="planning" className="planning-questions"><p className="eyebrow">{t(lang,'PLANUNGSFRAGEN','PLANNING QUESTIONS')}</p><p className="small-note">{t(lang,'Redaktionelle Orientierung für deinen Aufbau. Diese Fragen sind keine veröffentlichten Besuchernachrichten.','Editorial guidance for your setup. These questions are not published visitor messages.')}</p>{questions.map((q,i)=><div key={i}><h2>{q.question[lang]}</h2><p>{q.answer[lang]}</p></div>)}<div className="planning-related"><a className="text-link" href={`/${lang}/refit`}>{t(lang,'Die eigene Nachrüstung planen','Plan your refit')} <Arrow/></a><a className="text-link" href={`/${lang}/fabrication`}>{t(lang,'Fertigung und Einbau','Fabrication and installation')} <Arrow/></a></div></section>;
}

export function PlanningPage({lang,kind}:{lang:Lang;kind:'refit'|'fabrication'}) {
  const guide = guides[kind];
  return <>
    <header className="page-intro wrap"><a className="breadcrumb" href={`/${lang}`}>Number Five <span>/</span></a><p className="eyebrow">{guide.kicker[lang]}</p><h1>{guide.title[lang]}</h1><p className="page-deck">{guide.deck[lang]}</p></header>
    <div className="wrap planning-hero"><Picture id={guide.image} lang={lang} eager/></div>
    <div className="wrap planning-layout"><aside><p className="eyebrow">{t(lang,'DEIN WEG ZUM EINBAU','YOUR ROUTE TO INSTALLATION')}</p><nav aria-label={t(lang,'Auf dieser Seite','On this page')}>{guide.sections.map((s,i)=><a key={s.id} href={`#${s.id}`}><span>0{i+1}</span>{s.title[lang]}</a>)}{kind==='refit'&&<a href="#costs"><span>06</span>{t(lang,'Kostenbausteine','Cost components')}</a>}</nav><p className="small-note">{t(lang,'Textstand: 2. November 2026. Geräte- und Quellenstände stehen bei den jeweiligen Aufnahmen und Paketen.','Text updated 2 November 2026. Device and source revisions are identified with each capture and package.')}</p></aside>
      <article className="prose planning-body"><p className="planning-answer">{guide.answer[lang]}</p>{kind==='refit'&&<Architecture lang={lang}/>}{guide.sections.map(s=><section id={s.id} key={s.id}><h2>{s.title[lang]}</h2>{s.paragraphs.map((p,i)=><p key={i}>{p[lang]}</p>)}{s.bullets&&<ul>{s.bullets.map((b,i)=><li key={i}>{b[lang]}</li>)}</ul>}<div className="planning-related">{s.links.map(l=><a className="text-link" href={`/${lang}/${l.path}`} key={l.path}>{l.label[lang]} <Arrow/></a>)}</div></section>)}</article>
    </div>
    {kind==='refit'&&<section className="wrap planning-costs" id="costs"><p className="eyebrow">{t(lang,'WAS KOSTET DIE NACHRÜSTUNG?','WHAT DOES A REFIT COST?')}</p><h2>{t(lang,'75 Euro fürs Display. Die übrigen Bausteine separat.','€75 for the display. Account for the other components separately.')}</h2><p>{t(lang,'Der Displaypreis ist meine eigene Kaufangabe. Die folgende vorhandene Kostenaufstellung vom 1. November 2026 ersetzt kein Angebot für dein Boot. NMEA- und Geräteadapter hängen von den tatsächlich vorhandenen Anschlüssen ab.','The display price is my own purchase report. This existing cost breakdown from 1 November 2026 is not a quotation for your boat. NMEA and device adapters depend on the connections actually present.')}</p><div className="cost-table-wrap"><table className="cost-table"><caption>{t(lang,'Bestandteile und bekannte Preisangaben','Components and known price information')}</caption><thead><tr><th scope="col">{t(lang,'Baustein','Component')}</th><th scope="col">{t(lang,'Was dazugehört','What is included')}</th><th scope="col">{t(lang,'Preisstand','Price information')}</th></tr></thead><tbody>{costRows.map(([name,detail,cost])=><tr key={name.en}><th scope="row">{name[lang]}</th><td>{detail[lang]}</td><td>{cost[lang]}</td></tr>)}</tbody></table></div></section>}
    <section className="wrap planning-contact"><p className="eyebrow">{t(lang,'VON DER IDEE ZUM GEMEINSAMEN AUFBAU','FROM IDEA TO A SHARED BUILD')}</p><h2>{t(lang,'Was passt zu deinem Boot?','What fits your boat?')}</h2><p>{t(lang,'Beschreibe dein Boot, die vorhandenen Geräte und deinen Wunsch. Oder erzähle, welche Teile du fertigen und welche Einbauarbeiten du beitragen kannst.','Describe your boat, existing devices and what you want to achieve. Or tell us which parts you can fabricate and which installation skills you can contribute.')}</p><div className="hero-actions"><a className="button" href={`/${lang}/contact`}>{t(lang,'Einbauidee besprechen','Discuss an installation idea')} <Arrow/></a><a className="text-link" href={`/${lang}/contact`}>{t(lang,'Fertigung oder Einbau beitragen','Contribute fabrication or installation')} <Arrow/></a></div></section>
  </>;
}
