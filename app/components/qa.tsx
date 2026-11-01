import qa from '../../content/qa.json';
import { t, type Lang } from '../../content/projects';
import { Arrow } from './site';

export function QaTeaser({lang}:{lang:Lang}) {
  return <section className="wrap qa-teaser" aria-labelledby="qa-teaser-title">
    <div><p className="eyebrow">Q&A / {t(lang,'DIE WERKSTATT HÖRT ZU','THE WORKSHOP IS LISTENING')}</p>
      <h2 id="qa-teaser-title">{t(lang,'Fragen mit Gegenwind.','Questions worth asking.')}</h2></div>
    <div><p>{t(lang,'Warum überhaupt eine Alternative? Muss ich selbst löten? Und was ist wirklich erprobt? Hier greifen wir Rückmeldungen auf und zeigen, was wir daraus machen. Die Comiccrew darf fragen, wir müssen antworten.','Why build an alternative? Do I have to solder? And what has actually been tested? We take up feedback and explain what we do with it. The comic crew gets to ask; we have to answer.')}</p>
      <a className="text-link" href={`/${lang}/qa`}>{t(lang,'Fragen und Antworten lesen','Read the questions and answers')} <Arrow/></a></div>
  </section>;
}

export function QaPage({lang}:{lang:Lang}) {
  return <>
    <header className="page-intro wrap"><a className="breadcrumb" href={`/${lang}`}>Number Five <span>/</span></a>
      <p className="eyebrow">Q&A / {t(lang,'FRAGEN AUS DER COMMUNITY','QUESTIONS FROM THE COMMUNITY')}</p>
      <h1>{t(lang,'Fragen mit Gegenwind.','Questions worth asking.')}</h1>
      <p className="page-deck">{t(lang,'Gute Ideen halten Gegenfragen aus. Hier erklären wir unsere Entscheidungen, nehmen berechtigte Einwände auf und sagen, wo noch Arbeit vor uns liegt.','Good ideas can stand up to questions. Here we explain our decisions, take valid concerns seriously and identify the work still ahead.')}</p>
    </header>
    <div className="wrap qa-layout">
      <aside className="qa-guide"><p className="eyebrow">{t(lang,'AM WERKSTATTTISCH','AT THE WORKBENCH')}</p>
        <nav aria-label={t(lang,'Fragen auf dieser Seite','Questions on this page')}>{qa.entries.map((entry,index)=><a key={entry.id} href={`#${entry.id}`}><span>{String(index+1).padStart(2,'0')}</span>{entry.question[lang]}</a>)}</nav>
        <p className="qa-editorial-note">{qa.notice[lang]}</p>
      </aside>
      <div className="qa-entries">{qa.entries.map((entry,index)=><article className="qa-entry" id={entry.id} key={entry.id} aria-labelledby={`${entry.id}-title`}>
        <div className="qa-entry-top"><p className="eyebrow">{String(index+1).padStart(2,'0')} / {t(lang,'DER EINWAND','THE QUESTION')}</p><a href={`#${entry.id}`} aria-label={t(lang,'Direktlink zu dieser Frage','Direct link to this question')}>#</a></div>
        <p className="qa-character"><span aria-hidden="true">?</span><span><small>{t(lang,'Fiktive Fragenrolle','Fictional question role')}</small><strong>{entry.character[lang]}</strong></span></p>
        <h2 id={`${entry.id}-title`}>{entry.question[lang]}</h2>
        <div className="qa-short-answer"><p className="eyebrow">{t(lang,'DIE KURZE ANTWORT','THE SHORT ANSWER')}</p><p>{entry.summary[lang]}</p></div>
        <div className="prose qa-answer">{entry.sections.map((section,i)=><section key={i}><h3>{section.heading[lang]}</h3><p>{section.body[lang]}</p></section>)}</div>
        <div className="qa-consequence"><h3>{t(lang,'Was wir daraus machen','What we do with it')}</h3><p>{entry.action[lang]}</p><h3>{t(lang,'Was offen bleibt','What remains open')}</h3><p>{entry.open[lang]}</p></div>
        <nav className="qa-related" aria-label={t(lang,'Zum Thema weiterlesen','Read more on this topic')}>{entry.links.map(link=><a className="text-link" key={link.path} href={`/${lang}/${link.path}`}>{link.label[lang]} <Arrow/></a>)}</nav>
      </article>)}</div>
    </div>
    <section className="wrap qa-invitation"><p className="eyebrow">{t(lang,'DEINE FRAGE FEHLT?','YOUR QUESTION IS MISSING?')}</p>
      <h2>{t(lang,'Auch Gegenwind bringt uns weiter.','Questions help us move forward.')}</h2>
      <p>{t(lang,'Schreib uns, was dich überzeugt, was nicht zusammenpasst oder welchen Nachweis du vermisst. Neue Fragen bereiten wir redaktionell auf Deutsch und Englisch auf. Deine Originalnachricht und Kontaktdaten bleiben privat.','Tell us what convinces you, what does not add up or what evidence you are missing. We prepare new questions editorially in German and English. Your original message and contact details stay private.')}</p>
      <a className="button" href={`/${lang}/contact`}>{t(lang,'Eine Frage einbringen','Bring a question')} <Arrow/></a>
    </section>
  </>;
}
