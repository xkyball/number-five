"""Independent meshes, exact wire segment checks and offscreen SVG rasterization."""
import sys,math,struct,xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
import numpy as np
import trimesh
from common import ROOT,OUT,TOOLS,read,save,sha
sys.path.insert(0,str(TOOLS/'native-tools'))
from native_tools import run

def main():
    c=read(OUT/'data/electronics.json');errors=[];meshes=[]
    for p in sorted((OUT/'prints').glob('*.stl')):
        m=trimesh.load_mesh(p,process=True);good=m.is_watertight and m.is_winding_consistent and m.volume>0 and len(m.split())==1
        # Volume/winding/topology checks are independent of the BRep validator.
        meshes.append(dict(id=p.stem,watertight=m.is_watertight,winding=m.is_winding_consistent,components=len(m.split()),volume_mm3=m.volume,status='PASS'if good else'FAIL'))
        if not good:errors.append('MESH:'+p.stem)
    save(OUT/'checks/mesh-independent.json',dict(status='PASS'if not errors else'FAIL',parts=meshes,healing=False))
    if '--mesh-only'in sys.argv:
        print('Independent meshes',len(meshes),'PASS'if not errors else'FAIL',flush=True)
        if errors:raise SystemExit(1)
        return
    edges=set();wireids=[]
    for w in c['bridges']:
        for a,z in zip(w['points'],w['points'][1:]):
            dx,dy=z[0]-a[0],z[1]-a[1]
            if dx and dy:errors.append('NONAXIAL:'+w['id']);continue
            steps=round((abs(dx)+abs(dy))*2);sx=0 if dx==0 else(1 if dx>0 else-1);sy=0 if dy==0 else(1 if dy>0 else-1)
            for i in range(steps):
                u=(round(a[0]*2)+sx*i,round(a[1]*2)+sy*i);v=(u[0]+sx,u[1]+sy);key=(w['height_lane'],*sorted((u,v)))
                if key in edges:errors.append('COINCIDENT_WIRE:'+w['id'])
                edges.add(key)
        if w['points'][0]!=w['start']or w['points'][-1]!=w['end']:errors.append('WIRE_END:'+w['id'])
    # Reconstruct every half-grid crossing independently of the stack author.
    crossings={};max_top=0
    for w in c['bridges']:
        profile={tuple(p['point']):p['center_height_mm']for p in w.get('crossover_profile',[])}
        for a,z in zip(w['points'],w['points'][1:]):
            steps=round((abs(z[0]-a[0])+abs(z[1]-a[1]))*2)
            for v in np.linspace(a,z,steps+1):
                pt=tuple(float(x)for x in v);h=profile.get(pt,w['height_mm']);crossings.setdefault(pt,{})[w['id']]=h;max_top=max(max_top,h+.6)
    shared={pt:ww for pt,ww in crossings.items()if len(ww)>1}
    for pt,ww in shared.items():
        hh=sorted(ww.values())
        if any(b-a<1.49 for a,b in zip(hh,hh[1:])):errors.append('CROSSING_HEIGHT:'+str(pt))
    if max_top>8.4:errors.append('PICO_WIRE_HEIGHT')
    save(OUT/'checks/wire-forming.json',dict(status='FAIL'if any(x.startswith(('CROSSING','PICO_WIRE'))for x in errors)else'PASS_DESIGN_GRID',checked_crossing_nodes=len(shared),maximum_wire_top_mm=round(max_top,2),minimum_pico_gap_mm=8.5,physical_forming='OPEN',scope='Independent pointwise stack reconstruction; continuous bending, process retention and full3D collision remain unqualified.'))
    (OUT/'png').mkdir(exist_ok=True);renders=[]
    for svg in sorted((OUT/'svg').glob('*.svg')):
        root=ET.parse(svg).getroot();ids=[n.get('data-wire-id')for n in root.iter()if n.get('data-wire-id')];wireids+=ids
        dest=OUT/'png'/(svg.stem+'.png');r=run(['/opt/homebrew/bin/rsvg-convert','-w','1400','-o',str(dest),str(svg)],capture_output=True,text=True,timeout=60)
        if r.returncode:errors.append('RENDER:'+svg.name)
        else:
            size=struct.unpack('>II',dest.read_bytes()[16:24]);renders.append(dict(svg=svg.name,png=dest.name,pixels=list(size),sha256=sha(dest)))
    expected=Counter(w['id']for w in c['bridges'])
    if Counter(wireids)!=expected:errors.append('SVG_WIRE_COVERAGE:'+str(Counter(wireids)-expected))
    # Contact sheet is only an internal review aid; never opened automatically.
    thumbw,thumbh=360,520;sheet=[f'<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="{thumbh*math.ceil(len(renders)/5)}"><rect width="100%" height="100%" fill="#e4e9e9"/>']
    for i,r in enumerate(renders):
        x=(i%5)*thumbw+6;y=(i//5)*thumbh+30
        sheet.append(f'<text x="{x}" y="{y-12}" font-size="16">{r["svg"]}</text><image href="png/{r["png"]}" x="{x}" y="{y}" width="348" height="475" preserveAspectRatio="xMinYMin meet"/>')
    source=OUT/'internal-review.svg';source.write_text(''.join(sheet+['</svg>']))
    r=run(['/opt/homebrew/bin/rsvg-convert','-o',str(OUT/'checks/svg-contact-sheet.png'),str(source)],capture_output=True,text=True,timeout=60)
    if r.returncode:errors.append('CONTACT_SHEET_RENDER')
    save(OUT/'checks/svg-render.json',dict(status='FAIL'if errors else'PASS_STRUCTURAL_PIXELS_RENDERED',errors=errors,rendered=renders,unique_wire_count=len(expected),same_lane_coincident_segments=0 if not any(x.startswith('COINCIDENT')for x in errors)else None,visual_review='PENDING_INTERNAL_PIXEL_INSPECTION',scope='AllSVG parsed and rasterized offscreen, every bridge exactly once in layer sheets, no same-lane coincident wire segment; visual review recorded separately.'))
    print('Artifacts',len(meshes),'meshes',len(renders),'SVG renders',errors,flush=True)
    if errors:raise SystemExit(1)

if __name__=='__main__':main()
