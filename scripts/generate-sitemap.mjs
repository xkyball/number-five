import { readFile, writeFile } from 'node:fs/promises';
const data = JSON.parse(await readFile(new URL('../content/search.json',import.meta.url),'utf8'));
const origin = 'https://no5.christophhoffmann.net';
const url = (lang,path) => `${origin}/${lang}${path?'/'+path:''}`;
const escape = value => value.replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
const entries = ['de','en'].flatMap(lang=>Object.keys(data.pages).map(path=>`<url><loc>${escape(url(lang,path))}</loc>\n<lastmod>${data.updated}</lastmod>\n${['de','en'].map(other=>`<xhtml:link rel="alternate" hreflang="${other}" href="${escape(url(other,path))}"/>`).join('\n')}\n</url>`));
await writeFile(new URL('../public/sitemap.xml',import.meta.url),`<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${entries.join('\n')}\n</urlset>\n`);
console.log(`Sitemap: ${entries.length} paired language pages.`);
