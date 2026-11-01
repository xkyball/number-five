"""Counterexamples and valid counterparts exercise the actual audit checkers."""
import copy,csv,subprocess
import build123d as b
from audit_support import R,OUT,CAD,read,sha,finding,report,save
from inspect_mechanics import box,cylinder,fastener_measure,moving_path
from connectivity import compare
from kicad_audit import execute
from package import assess, main as package_main
import tempfile, pathlib, zipfile, json

def main():
    cases=[]
    def expect(name,actual,expected,evidence):
        cases.append(dict(name=name,expected=expected,actual=actual,passed=actual==expected,evidence=evidence))
    board=box([0,0,10],[20,20,12]).cut(cylinder([10,10,9],1.7,4,[0,0,1]))
    base=box([0,0,0],[20,20,10]).cut(cylinder([10,10,3],1.25,8,[0,0,1]))
    def screw(length):
        return cylinder([10,10,12],1.5,length,[0,0,-1]).fuse(cylinder([10,10,12],3,3,[0,0,1]))
    for name,length,anchor,bearing,expected in [('valid screw',8,base,board,'PASS'),('short screw',1,base,board,'FAIL'),('blind-hole bottom',10,base,board,'FAIL'),('missing head bearing',8,base,board.cut(cylinder([10,10,9],3.5,4,[0,0,1])),'FAIL'),('declared mate without engagement',8,box([0,0,0],[20,20,10]).cut(cylinder([10,10,-1],2,12,[0,0,1])),board,'FAIL')]:
        r=fastener_measure(screw(length),anchor,bearing);expect(name,r['status'],expected,r)
    # Antirotation alone: the nut travels together with the screw and module.
    hexagon=b.extrude(b.RegularPolygon(5,6),3).moved(b.Location((15,15,5)))
    nut=hexagon.cut(cylinder([15,15,4],1.5,5,[0,0,1]))
    module=box([8,8,10],[22,22,12]).cut(cylinder([15,15,9],1.7,4,[0,0,1]))
    bolt=cylinder([15,15,5],1.5,7,[0,0,1]).fuse(cylinder([15,15,12],3,3,[0,0,1]))
    for shoulder in [False,True]:
        pocket=b.extrude(b.RegularPolygon(5.1,6),5 if shoulder else 10).moved(b.Location((15,15,3)))
        body=box([0,0,0],[30,30,10]).cut(pocket,cylinder([15,15,-1],1.8,12,[0,0,1]))
        r=moving_path(dict(base=body,nut=nut,module=module,bolt=bolt),['nut','module','bolt'],['base'],[0,0,4])
        actual='FAIL' if r['clear'] else 'PASS' if r['collisions'] else 'OPEN'
        expect('nut pocket '+('with shoulder' if shoulder else 'open antirotation only'),actual,'PASS' if shoulder else 'FAIL',r)
    for stop in [False,True]:
        guide=box([0,0,0],[2,20,10]).fuse(box([12,0,0],[14,20,10]))
        if stop:guide=guide.fuse(box([0,12,0],[14,14,10]))
        plug=box([3,3,2],[11,10,8]);r=moving_path(dict(guide=guide,plug=plug),['plug'],['guide'],[0,10,0])
        actual='FAIL' if r['clear'] else 'PASS' if r['collisions'] else 'OPEN'
        expect('connector '+('retained' if stop else 'open extraction'),actual,'PASS' if stop else 'FAIL',r)
    d=read(R/'inputs/design.json');layout=read(R/'delivery/stripboard.json');harness=list(csv.DictReader((R/'delivery/harness.csv').open()))
    expect('valid copper',compare(layout,d,harness)[0]['status'],'PASS',{})
    for name in ['missing cut','missing jumper','swapped pins','wrong return']:
        q=copy.deepcopy(layout);h=copy.deepcopy(harness)
        if name=='missing cut':
            for cut in layout['cuts']:
                q=copy.deepcopy(layout);q['cuts'].remove(cut)
                if compare(q,d,h)[0]['status']=='FAIL':break
        elif name=='missing jumper':q['jumpers'].pop(0)
        elif name=='swapped pins':
            c=next(c for c in q['components'] if c['ref']=='J1');c['pads']['1']['hole'],c['pads']['2']['hole']=c['pads']['2']['hole'],c['pads']['1']['hole']
        else:next(w for w in h if w['id']=='BUCK_2')['end']='J5.1'
        result=compare(q,d,h)[0];expect(name,result['status'],'FAIL',result)
    for name in ['output tie','removed supply']:
        q=copy.deepcopy(d)
        if name=='output tie':next(c for c in q['components'] if c['ref']=='U2')['pins']['1']='DRIVE'
        else:q['components']=[c for c in q['components'] if c['ref']!='U4']
        r=execute(q,OUT/'regression-kicad'/name.replace(' ','-'))
        types=[v['type'] for v in r['violations']]
        detected=r['erc_returncode']!=0 and ('pin_to_pin' in types if name=='output tie' else 'power_pin_not_driven' in types)
        expect(name,'FAIL' if detected else 'MISSED','FAIL',dict(returncode=r['erc_returncode'],violation_types=types))
    # Delivery remains allowed for every recorded design result and incomplete run.
    with tempfile.TemporaryDirectory(prefix='trim-r3-package-regression-') as td:
        root=pathlib.Path(td);(root/'inputs').mkdir();(root/'reports').mkdir();(root/'inputs/design.json').write_text('{}')
        for name in ['PASS','FAIL','OPEN','missing report','stale source','stale checker','failed tool run','interrupted run']:
            p=root/'reports/validity.json'
            if p.exists():p.unlink()
            if name!='missing report':
                if name=='interrupted run':p.write_text('{partial')
                else:p.write_text(json.dumps(dict(status=name if name in ['PASS','FAIL','OPEN']else'PASS',source_sha256='stale'if name=='stale source'else sha(root/'inputs/design.json'),checker_sha256={'missing-checker.py':'stale'}if name=='stale checker'else{},run_status='FAIL'if name=='failed tool run'else'PASS')))
            result=package_main(root);path=root/'trim-open-carrier-r3.zip'
            valid=path.exists()and zipfile.ZipFile(path).testzip()is None and result['delivery_allowed']
            recorded=result['checks'][0]['status'];wanted=name if name in ['PASS','FAIL','OPEN']else'OPEN'
            expect('advisory delivery '+name,'PASS'if valid and recorded==wanted else'FAIL','PASS',dict(delivery_allowed=result['delivery_allowed'],recorded=recorded,expected_recorded=wanted))
    # Semantic regression: all three representations may agree on a wrong label.
    # A1 must remain physically connected to the output divider RP2.2.
    from connectivity import reconstruct,role_checks
    q=copy.deepcopy(layout)
    c=next(c for c in q['components']if c['ref']=='U1')
    c['pads']['20']['hole']=[50,23]  # deliberate disconnected A1
    g,_=reconstruct(q,harness)
    role=next(c for c in role_checks(g)if c['a']=='U1.20')
    expect('disconnected A1 terminal role','FAIL'if not role['connected']else'MISSED','FAIL',role)
    save(R/'reports/regressions.json',dict(status='PASS'if all(c['passed']for c in cases)else'FAIL',source_sha256=sha(R/'inputs/design.json'),cases=cases,scope='Separate bad fixtures and valid counterparts; advisory delivery replaces historical gate tests.'))
    print('REGRESSIONS',len(cases),[c['name']for c in cases if not c['passed']])

if __name__=='__main__':main()
