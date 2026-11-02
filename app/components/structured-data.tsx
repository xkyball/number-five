import { pageStructuredData } from '../../lib/search';
import type { Lang } from '../../content/projects';

export function StructuredData({lang,path}:{lang:Lang;path:string}) {
  const data = pageStructuredData(lang,path);
  if (!data) return null;
  return <script type="application/ld+json" dangerouslySetInnerHTML={{__html:JSON.stringify(data).replace(/</g,'\\u003c')}}/>;
}
