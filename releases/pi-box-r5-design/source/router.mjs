// Headless, pinned library boundary. Geometry/contacts are supplied as data.
import { AvoidLib } from 'libavoid-js';
import ELK from 'elkjs/lib/elk.bundled.js';
let raw=''; for await (const chunk of process.stdin) raw+=chunk;
const q=JSON.parse(raw);
try {
 if(q.engine==='elk') {
  const elk=new ELK(); const result=await elk.layout(q.graph);
  process.stdout.write(JSON.stringify(result));
 } else {
  await AvoidLib.load(); const A=AvoidLib.getInstance();
  const router=new A.Router(A.RouterFlag.OrthogonalRouting.value);
  for(const [k,v] of Object.entries({segmentPenalty:25,crossingPenalty:100,shapeBufferDistance:q.buffer??0.2,idealNudgingDistance:q.spacing??0.8}))router.setRoutingParameter(A.RoutingParameter[k],v);
  const shapes=new Map();
  for(const o of q.obstacles??[]) {
   const [x,y,w,h]=o.rect;
   shapes.set(o.id,{o,shape:new A.ShapeRef(router,new A.Rectangle(new A.Point(x,y),new A.Point(x+w,y+h)))});
  }
  let pin=1; const pins=new Map();
  function end(p,owner) {
   const s=shapes.get(owner);
   if(s) {
    const [x,y,w,h]=s.o.rect; const key=owner+':'+p.join(','); let n=pins.get(key);
    if(n===undefined){n=pin++;const cp=new A.ShapeConnectionPin(s.shape,n,p[0]-x,p[1]-y,false,0,15);cp.setExclusive(false);pins.set(key,n);}
    return new A.ConnEnd(s.shape,n);
   }
   return new A.ConnEnd(new A.Point(...p));
  }
  const refs=q.links.map(l=>{
   const c=new A.ConnRef(router,end(l.start,l.start_owner),end(l.end,l.end_owner));
   c.setRoutingType(A.ConnType.ConnType_Orthogonal);return {l,c};
  });
  router.processTransaction();
  const routes=refs.map(({l,c})=>{
   const r=c.displayRoute(),points=[];
   for(let i=0;i<r.size();i++){const p=r.at(i);points.push([p.x,p.y]);}
   if(points.length<2)throw Error('No route: '+l.id);
   if(points.some((p,i)=>i>0&&Math.abs(p[0]-points[i-1][0])>1e-6&&Math.abs(p[1]-points[i-1][1])>1e-6))throw Error('Non-orthogonal: '+l.id);
   return {id:l.id,points};
  });
  process.stdout.write(JSON.stringify({engine:'libavoid-js',version:'0.5.0-beta.5',routes}));
 }
}catch(e){process.stderr.write('ROUTER_ERROR: '+e.message+'\n');process.exitCode=1;}
