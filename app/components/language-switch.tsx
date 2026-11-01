'use client';

import type { Lang } from '../../content/projects';

export default function LanguageSwitch({lang,path}:{lang:Lang;path:string}) {
  const other=lang==='de'?'en':'de';
  return <a className="lang-switch" href={`/${other}${path?'/'+path:''}`} hrefLang={other}
    aria-label={other==='de'?'Auf Deutsch lesen':'Read in English'}
    onClick={event=>{event.currentTarget.hash=window.location.hash;}}>
    <span className={lang==='de'?'active':''}>DE</span><span className="lang-divider">/</span><span className={lang==='en'?'active':''}>EN</span>
  </a>;
}
