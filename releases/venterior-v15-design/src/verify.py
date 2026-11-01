"""Independent reconstruction of actual strip copper, cuts, holes and bridges."""
import copy,math
from collections import defaultdict
from common import ROOT,OUT,read,save,sha
from electronics import circuit

class UF:
    def __init__(self):self.p={}
    def find(self,x):
        self.p.setdefault(x,x)
        if self.p[x]!=x:self.p[x]=self.find(self.p[x])
        return self.p[x]
    def join(self,a,b):self.p[self.find(a)]=self.find(b)

def reconstruct(c,expected):
    u=UF();q=c['board'];errors=[];points=set()
    def valid(x,y):
        a=q['datum'][0]+(x-1)*q['pitch'];b=q['datum'][1]+(y-1)*q['pitch']
        return all(math.hypot(a-mx,b-my)>=4.2 for mx,my in q.get('layout_mounts',q['mounts']))
    for y in range(1,q['rows']+1):
        for x in range(1,q['cols']+1):
            if valid(x,y):points.add((x,y));u.find((x,y))
    cuts={(a['after_col'],a['y'])for a in c['cuts']}
    for x,y in points:
        if(x+1,y)in points and(x,y)not in cuts:u.join((x,y),(x+1,y))
    actual={}
    for p in c['parts']:
        for pin,pt in p['pad_grid'].items():
            id=p['ref']+'.'+pin
            if tuple(pt)not in points:errors.append('PIN_IN_MOUNT_CLEARANCE:'+id)
            actual[id]=tuple(pt)
    for w in c['bridges']:
        a,z=tuple(w['start']),tuple(w['end'])
        if a not in points or z not in points:errors.append('BRIDGE_OUTSIDE:'+w['id'])
        if a in actual.values()or z in actual.values():errors.append('SHARED_COMPONENT_HOLE:'+w['id'])
        u.join(a,z)
        if w['side']!='component':errors.append('WRONG_WIRE_SIDE:'+w['id'])
    # Purchased Pico GND pads are truly common on its PCB, not an invented
    # connection across an amplifier, resistor or isolated PhotoMOS contact.
    gp=[actual['PICO.'+str(x)]for x in[3,8,13,18,23,28,33,38]]
    for x in gp[1:]:u.join(gp[0],x)
    by_net=defaultdict(list);by_island=defaultdict(set)
    for id,n in expected.items():
        if id not in actual:errors.append('MISSING_PIN:'+id);continue
        rt=u.find(actual[id]);by_net[n].append(rt);by_island[rt].add(n)
    for n,rr in by_net.items():
        if len(set(rr))!=1:errors.append('OPEN:'+n)
    for nn in by_island.values():
        if len(nn)>1:errors.append('SHORT:'+','.join(sorted(nn)))
    ends=[tuple(w[k])for w in c['bridges']for k in['start','end']]
    if len(ends)!=len(set(ends)):errors.append('MULTIPLE_WIRES_ONE_HOLE')
    return errors

def main():
    c=read(OUT/'data/electronics.json');expected={}
    for p in circuit()['parts']:
        for pin,n in p['pins'].items():
            expected[p['ref']+'.'+pin]=n if n is not None and not p.get('dnp')else'NC_'+p['ref']+'_'+pin
    errors=reconstruct(c,expected)
    q=c['board'];stock=q['stock_contract']
    def substrate_ok(cols,rows):return cols<=stock['holes_per_strip']and rows<=stock['strips']and stock['layout_copper_axis']=='X'and q['layout_rotation_deg']==90
    if not substrate_ok(q['cols'],q['rows']):errors.append('SUBSTRATE_TRACK_ORIENTATION')
    for ref in['R_M1','R_M2','R_M3']:
        pp=next(p for p in c['parts']if p['ref']==ref)['pad_grid'];span=math.dist(*pp.values())*q['pitch']
        if span<20:errors.append('2W_RESISTOR_PITCH:'+ref)
    regression=[dict(case='old35x60 layout cannot be cut from38-strip raw board',caught=not substrate_ok(35,60))]
    # Negative tests prove the physical reconstruction rejects a manufacturing
    # omission instead of only agreeing with its own drawing generator.
    negative=copy.deepcopy(c);negative['cuts']=[]
    regression.append(dict(case='all track cuts accidentally omitted',caught=any(x.startswith('SHORT:')for x in reconstruct(negative,expected))))
    negative=copy.deepcopy(c);negative['bridges']=[]
    regression.append(dict(case='top bridges omitted',caught=any(x.startswith('OPEN:')for x in reconstruct(negative,expected))))
    negative=copy.deepcopy(c);s=next(x for x in negative['parts']if x['ref']=='J_SIGNAL');s['pad_grid']['1'],s['pad_grid']['2']=s['pad_grid']['2'],s['pad_grid']['1']
    regression.append(dict(case='signal connector pins1/2 accidentally swapped',caught=bool(reconstruct(negative,expected))))
    # Exact retained core values and pin nets; the expected list is copied from
    # the independently preserved input, not derived from the new placement.
    now={x['ref']:x for x in c['parts']};retained=[]
    for group in['rx','marker-tx']:
        for p in read(ROOT/'inputs/previous'/f'{group}-placement.json')['components']:
            if p['kind']=='connector':continue
            ok=now[p['ref']]['pins']==p['pins']and now[p['ref']]['value']==p['value']
            retained.append(dict(ref=p['ref'],unchanged_values_and_pin_nets=ok))
    if not all(x['unchanged_values_and_pin_nets']for x in retained):errors.append('RETAINED_CORE_CHANGED')
    if not all(x['caught']for x in regression):errors.append('NEGATIVE_REGRESSION_NOT_CAUGHT')
    save(OUT/'checks/electrical-connectivity.json',dict(status='PASS'if not errors else'FAIL',errors=errors,pins=len(expected),cuts=len(c['cuts']),top_wires=len(c['bridges']),physical_board_count=1,regressions=regression,retained_core=retained,source_sha256=sha(OUT/'data/electronics.json'),scope='Independent full strip grid with copper removed around every mounting hole, between-hole cuts, every semantic pin and top bridge; exact preserved RX/marker/TX values. No active device, RF, firmware or real-solder operation qualification.'))
    print('CONNECTIVITY','PASS'if not errors else'FAIL',errors,flush=True)
    if errors:raise SystemExit(1)

if __name__=='__main__':main()
