import type { Metadata } from 'next';
import searchData from '../content/search.json';
import demoData from '../content/demos.json';
import mediaData from '../content/media.json';
import { currentProjectMedia } from '../content/showcase';
import { siteOrigin, type Lang, type Local } from '../content/projects';

type SearchPage = {title: Local; description: Local};
export const searchPages = searchData.pages as Record<string, SearchPage>;
export const publicPagePaths = Object.keys(searchPages);
export const pageUrl = (lang:Lang,path:string) => `${siteOrigin}/${lang}${path ? '/'+path : ''}`;

export function searchMetadata(lang:Lang,path:string): Metadata {
  const entry = searchPages[path];
  if (!entry) return {robots:{index:false,follow:false}};
  const slug = path.startsWith('projects/') ? path.slice(9) : '';
  const imageId = currentProjectMedia[slug]?.hero || (path==='fabrication' ? 'enclosure-exploded' : 'map-current-overview');
  const image = (mediaData as Record<string,{src:string;title:Local}>)[imageId];
  return {
    title: entry.title[lang], description: entry.description[lang],
    alternates:{canonical:pageUrl(lang,path),languages:{de:pageUrl('de',path),en:pageUrl('en',path)}},
    openGraph:{type:'website',url:pageUrl(lang,path),title:`${entry.title[lang]} · Number Five`,description:entry.description[lang],siteName:'Number Five',images:[{url:siteOrigin+image.src,alt:image.title[lang]}],locale:lang==='de'?'de_DE':'en_GB',alternateLocale:lang==='de'?'en_GB':'de_DE'},
    twitter:{card:'summary_large_image',title:`${entry.title[lang]} · Number Five`,description:entry.description[lang],images:[siteOrigin+image.src]},
  };
}

export function pageStructuredData(lang:Lang,path:string) {
  const entry = searchPages[path];
  if (!entry) return null;
  const url = pageUrl(lang,path);
  const website = `${siteOrigin}/#website`;
  const person = `${siteOrigin}/#christoph`;
  const webPageId = `${url}#webpage`;
  const graph: Record<string,unknown>[] = [
    {'@type':'WebSite','@id':website,url:siteOrigin+'/',name:'Number Five',inLanguage:['de','en'],publisher:{'@id':person}},
    {'@type':'Person','@id':person,name:'Christoph Hoffmann',url:pageUrl(lang,'philosophy'),sameAs:['https://github.com/xkyball']},
    {'@type':'WebPage','@id':webPageId,url,name:entry.title[lang],description:entry.description[lang],inLanguage:lang,isPartOf:{'@id':website}},
  ];
  const projectSlug = path.startsWith('projects/') ? path.slice(9) : '';
  if(path) {
    const crumbs = [{name:'Number Five',item:pageUrl(lang,'')}];
    if(projectSlug) crumbs.push({name:lang==='de'?'Teilprojekte':'Projects',item:pageUrl(lang,'projects')});
    crumbs.push({name:entry.title[lang],item:url});
    graph.push({'@type':'BreadcrumbList','@id':url+'#breadcrumbs',itemListElement:crumbs.map((c,i)=>({'@type':'ListItem',position:i+1,...c}))});
  }
  if(projectSlug||['refit','fabrication','story','platform','philosophy'].includes(path)) {
    const imageId=currentProjectMedia[projectSlug]?.hero||(path==='fabrication'?'enclosure-exploded':'map-current-overview');
    const image=(mediaData as Record<string,{src:string}>)[imageId];
    const updated=projectSlug||['refit','fabrication'].includes(path)?searchData.updated:'2026-11-01';
    graph.push({'@type':'Article','@id':url+'#article',headline:entry.title[lang],description:entry.description[lang],inLanguage:lang,author:{'@id':person},publisher:{'@id':person},mainEntityOfPage:{'@id':webPageId},datePublished:['refit','fabrication'].includes(path)?searchData.updated:'2026-11-01',dateModified:updated,image:siteOrigin+image.src});
  }
  const shownDemos=demoData.filter(d=>path===''||projectSlug==='maps'&&d.id.startsWith('map')||projectSlug==='audio'&&d.id.startsWith('audio')||projectSlug==='sonar'&&d.id.startsWith('sonar'));
  for(const demo of shownDemos) graph.push({'@type':'VideoObject','@id':`${url}#demo-${demo.id}`,name:demo.title[lang],description:demo.description[lang],thumbnailUrl:[siteOrigin+demo.poster],contentUrl:siteOrigin+demo.src,uploadDate:demo.date,duration:`PT${Math.round(demo.durationSeconds*1000)/1000}S`,inLanguage:['de','en'],encodingFormat:'video/mp4',isPartOf:{'@id':webPageId},creator:{'@id':person}});
  return {'@context':'https://schema.org','@graph':graph};
}
