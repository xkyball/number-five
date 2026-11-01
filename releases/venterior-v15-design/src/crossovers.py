"""Forming plan for real insulated top wires, represented only in SVG/CSV data."""
from collections import defaultdict
import csv,math
from common import OUT,read,save

def annotate(c):
    occupancy=defaultdict(list);walks={};rows=[]
    for w in c['bridges']:
        walk=[]
        for a,z in zip(w['points'],w['points'][1:]):
            dx,dy=z[0]-a[0],z[1]-a[1];steps=round((abs(dx)+abs(dy))*2)
            for i in range(steps):walk.append((round(a[0]*2)+int(math.copysign(i,dx))if dx else round(a[0]*2),round(a[1]*2)+int(math.copysign(i,dy))if dy else round(a[1]*2)))
        walk.append(tuple(round(x*2)for x in w['end']));walks[w['id']]=walk
        for p in set(walk):occupancy[p].append(w)
    profiles={w['id']:{p:w['height_mm']for p in walks[w['id']]}for w in c['bridges']}
    for p,ww in sorted(occupancy.items()):
        if len(ww)<2:continue
        last=-1e9;stack=[]
        for w in sorted(ww,key=lambda w:(w['height_mm'],w['id'])):
            h=round(max(w['height_mm'],last+1.5),1);last=h;profiles[w['id']][p]=h;stack.append(dict(wire=w['id'],center_height_mm=h))
        rows.append(dict(point=[p[0]/2,p[1]/2],stack=stack))
    for w in c['bridges']:
        hh=[profiles[w['id']][p]for p in walks[w['id']]];extra=sum(abs(a-b)for a,b in zip(hh,hh[1:]))+hh[0]+hh[-1]-2*w['height_mm']
        w['base_length_mm']=w.get('base_length_mm',w['length_mm']);w['forming_allowance_mm']=round(extra,1);w['length_mm']=round(w['base_length_mm']+extra,1)
        w['crossover_profile']=[dict(point=[p[0]/2,p[1]/2],center_height_mm=profiles[w['id']][p])for p in walks[w['id']]if profiles[w['id']][p]>w['height_mm']]
    top=max(v for pp in profiles.values()for v in pp.values())+.6
    if top>8.4:raise ValueError('Crossing stack exceeds minimum8.5mm Pico socket reserve')
    c['crossovers']=rows;c['wire_forming_contract']=dict(status='DESIGN_PROFILE_REAL_FORMING_OPEN',wire_max_OD_mm=1.2,center_separation_mm=1.5,maximum_top_mm=round(top,1),pico_socket_nominal_mm=8.5,minimum_nominal_top_clearance_mm=round(8.5-top,1),scope='Half-grid wire intersection stack, later equal-lane wire passes above earlier wire. Continuous formed bends and insulation require bench acceptance; no3D conductor solids or full bend qualification. Lengths conservatively add vertical excursions, then end/service allowance.')
    return c

def export_csv(c):
    with(OUT/'data/kreuzungen.csv').open('w',newline='')as f:
        wr=csv.writer(f);wr.writerow(['Loch_X','Loch_Y','Draht_von_unten_nach_oben','Drahtmittenhoehe_ab_Bestueckung_mm','Aussendurchmesser_max_mm'])
        for x in c['crossovers']:
            for s in x['stack']:wr.writerow([*x['point'],s['wire'],s['center_height_mm'],1.2])

if __name__=='__main__':c=annotate(read(OUT/'data/electronics.json'));save(OUT/'data/electronics.json',c);export_csv(c);print('Crossing nodes',len(c['crossovers']),'max top',c['wire_forming_contract']['maximum_top_mm'])
