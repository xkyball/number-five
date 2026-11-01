import philosophy from '../../content/philosophy.json';
import { t, type Lang } from '../../content/projects';
import { Arrow } from './site';

export function PhilosophyTeaser({lang}:{lang:Lang}) {
  return <section className="wrap philosophy-teaser"><p className="eyebrow">{t(lang,'DIE HALTUNG DAHINTER','THE THINKING BEHIND IT')}</p><h2>{philosophy.title[lang]}</h2><p>{philosophy.principle[lang]}</p><a className="text-link" href={`/${lang}/philosophy`}>{t(lang,'Warum ich Number Five baue','Why I am building Number Five')} <Arrow/></a></section>;
}

export function PhilosophyPage({lang}:{lang:Lang}) {
  return <>
    <header className="page-intro wrap"><a className="breadcrumb" href={`/${lang}`}>Number Five <span>/</span></a><p className="eyebrow">{t(lang,'MEINE PHILOSOPHIE','MY PHILOSOPHY')}</p><h1>{philosophy.title[lang]}</h1><p className="page-deck">{philosophy.principle[lang]}</p></header>
    <div className="wrap philosophy-layout"><aside><p className="eyebrow">{t(lang,'SECHS GEDANKEN','SIX IDEAS')}</p><nav aria-label={t(lang,'Kapitel der Philosophie','Philosophy chapters')}>{philosophy.sections.map((section,i)=><a href={`#${section.id}`} key={section.id}><span>{String(i+1).padStart(2,'0')}</span>{section.title[lang]}</a>)}</nav></aside><article className="prose philosophy-body"><p className="philosophy-intro">{philosophy.intro[lang]}</p>{philosophy.sections.map((section,i)=><section id={section.id} key={section.id}><p className="eyebrow">{String(i+1).padStart(2,'0')}</p><h2>{section.title[lang]}</h2>{section.paragraphs.map((p,j)=><p key={j}>{p[lang]}</p>)}</section>)}<section className="philosophy-invitation"><h2>{philosophy.invitation[lang]}</h2><div className="hero-actions"><a className="button" href={`/${lang}/contact`}>{t(lang,'Lass uns darüber sprechen','Let’s talk about it')} <Arrow/></a><a className="text-link" href={`/${lang}/projects`}>{t(lang,'Einen Baustein auswählen','Choose a component')} <Arrow/></a></div></section></article></div>
  </>;
}
