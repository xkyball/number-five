"""Copper graph reconstruction without using any target-net name for an edge."""
import copy,csv,json
from audit_support import R,OUT,read,save,Graph,finding,report

def reconstruct(layout, harness=None):
    board=layout['board'];g=Graph();pins={};removed=set();cuts=set()
    for x in range(board['columns']):
        for y in range(board['rows']):
            px,py=[board['origin'][i]+board['pitch']*v for i,v in enumerate((x,y))]
            if any(abs(px-m['xy'][0])<=m['copper_clearance']/2 and abs(py-m['xy'][1])<=m['copper_clearance']/2 for m in board['mounts']): removed.add((x,y))
    for c in layout['cuts']: cuts.add((c['row'],min(c['between'])))
    for y in range(board['rows']):
        for x in range(board['columns']-1):
            if (x,y) not in removed and (x+1,y) not in removed and (y,x) not in cuts:g.add(('hole',x,y),('hole',x+1,y))
    for w in layout['jumpers']:
        g.add(('hole',*w['start']),('hole',*w['end']))
    for c in layout['components']:
        for pin in c['pins']:
            ident=c['ref']+'.'+pin;pins[ident]=ident
            if pin in c.get('pads',{}):g.add(ident,('hole',*c['pads'][pin]['hole']))
        for bus in c.get('buses',[]):
            for pin in bus[1:]:g.add(c['ref']+'.'+bus[0],c['ref']+'.'+pin)
    if harness is not None:
        # U4 is off-board, consequently absent from the physical board export.
        # Reconstruct its four terminal identities from the delivered harness.
        mapping={'IN+':'U4.1','IN-':'U4.2','OUT+':'U4.3','OUT-':'U4.4'}
        for ident in mapping.values():pins[ident]=ident
        g.add('U4.2','U4.4')
        for wire in harness:
            if wire['id'].startswith('BUCK_'):g.add(mapping[wire['start'][3:]],wire['end'])
    # Canonical partitions of terminal identities; net labels are absent here.
    groups=set()
    for ident in pins:groups.add(tuple(sorted(p for p in g.group(ident) if p in pins)))
    return g,groups

def target_groups(design):
    target={}
    for c in design['components']:
        for pin,net in c['pins'].items():target.setdefault(net if net else 'NC:'+c['ref']+'.'+pin,set()).add(c['ref']+'.'+pin)
    return set(tuple(sorted(v)) for v in target.values())

def compare(layout,design,harness):
    g,groups=reconstruct(layout,harness);expected=target_groups(design)
    return dict(status='PASS' if groups==expected else 'FAIL',unexpected_partitions=sorted(groups-expected),missing_partitions=sorted(expected-groups)),g

def role_checks(g):
    roles=[('U1.19','R18.2','Gebermessung A0'),('U1.20','RP2.2','Yamaha-Rückmessung A1'),('U1.22','RX4.2','Geber-Speisespannung A3'),('U1.25','RB7.2','12-V-Messung A6'),('U1.7','R15.1','Normale Relaisfreigabe D4'),('U1.8','RH4.1','Lebenszeichen D5'),('U1.21','R14.1','Ausgangssperre A2'),('U2.1','RD1.1','DAC-Ausgang'),('U3.1','R9.1','Stromsenken-Treiber'),('K1.8','RP1.1','Messung hinter dem offenen Kontakt')]
    return [dict(a=a,b=b,function=name,connected=b in g.group(a))for a,b,name in roles]

def main():
    d=read(R/'inputs/design.json');layout=read(R/'delivery/stripboard.json')
    harness=list(csv.DictReader((R/'delivery/harness.csv').open()));result,g=compare(layout,d,harness)
    # Independently read the build tables that the user will actually follow.
    cuts=list(csv.DictReader((R/'delivery/cuts.csv').open()));jumpers=list(csv.DictReader((R/'delivery/jumpers.csv').open()))
    import ast
    tables=copy.deepcopy(layout)
    tables['cuts']=[dict(id=c['id'],row=int(c['row']),between=ast.literal_eval(c['between'])) for c in cuts]
    tables['jumpers']=[dict(id=c['id'],start=ast.literal_eval(c['start']),end=ast.literal_eval(c['end'])) for c in jumpers]
    table_result,_=compare(tables,d,harness)
    fs=[finding('E-COPPER','B1, Brücken und Wandlerkabel','Physische Verbindungspartitionen entsprechen dem Sollnetzvertrag',result['status'],result,'Erkennt Kurzschlüsse und fehlende Verbindungen unabhängig von den Sollnetznamen.'),finding('E-TABLES','Ausgelieferte Aufbau-Tabellen','CSV-Unterbrechungen und Brücken ergeben dieselben korrekten Verbindungen',table_result['status'],table_result,'Die tatsächlich nachbaubaren Tabellen werden separat eingelesen.')]
    external=g.group('J2.1');contacts=sorted(x for x in external if isinstance(x,str));expected=['J2.1','K1.1','K1.12']
    fs.append(finding('E-RELAY-ISOLATION','Yamaha PINK','Offener Kontakt trennt auch den Messspannungsteiler','PASS' if contacts==expected else 'FAIL',dict(external_contacts=contacts),'Gemeinsame Masse bleibt bestehen; keine galvanische Trennung.'))
    # Independent functional intent: terminal numbers, not copied net labels.
    checks=role_checks(g)
    fs.append(finding('E-TERMINAL-ROLES','Arduino, DAC und Relais','Funktionsanschlüsse stimmen unabhängig von den Sollnetznamen','PASS'if all(c['connected']for c in checks)else'FAIL',checks,'Erkennt auch einen konsistent falsch bezeichneten oder unverbundenen Messeingang.'))
    fs.append(finding('E-PURCHASE-BUSES','Nano/DAC/Wandler/Relais','Deklarierte interne Verbindungen stimmen mit dem gelieferten Teil überein','OPEN',dict(buses={c['ref']:c.get('buses') for c in layout['components'] if c.get('buses')}),'Graphprüfung ist an diese Modulverträge gebunden; Original-Pinfolge und Durchgang sind vor Bestromung zu prüfen.',scope='physical'))
    report('connectivity',fs,partitions=[list(x) for x in sorted(reconstruct(layout,harness)[1])],edge_source='Raw copper adjacency minus actual cuts/clearances; jumper endpoints; purchased buses; harness endpoints. No target net used to add edges.')

if __name__=='__main__':main()
