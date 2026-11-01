"""Typed derived audit schematic, real CLI ERC, and XML terminal partitions."""
import copy,json,re,sys,uuid,xml.etree.ElementTree as ET
from audit_support import R,OUT,KICAD,read,save,sha,finding,report
from native_tools import run
sys.path.insert(0,str(R/'src/vendor'))
from kicad_symbols import symbol
from kicad_support import q,fmt,effect,prop,pins,balanced
from connectivity import target_groups

def pin_type(c,p):
    kind=c['kind'];name=c.get('pin_names',{}).get(p,'')
    if kind=='nano':
        if name in ('GND','5V','VIN'):return 'power_in'
        if name=='3V3':return 'power_out'
        if name in ('A0','A1','A3','A6','A7','AREF','RESET','RX'):return 'input'
        if name in ('D4','D5','A2','TX'):return 'output'
        return 'bidirectional'
    if kind=='dac':return {'1':'output','2':'power_in','3':'input','4':'bidirectional','5':'power_in','6':'power_in'}[p]
    if kind=='buck':return {'1':'power_in','2':'power_in','3':'power_out','4':'passive'}[p]
    if kind=='dual_opamp':return 'output' if p in ('1','7') else 'power_in' if p in ('4','8') else 'input'
    return 'passive'

def typed_symbol(c,typ):
    lib,h=symbol(c,typ)
    replacements=[]
    for m in re.finditer(r'\(pin\s+\w+\s+\w+\s+\(at\s+',lib):
        block=balanced(lib,m.start());p=re.search(r'\(number\s+"([^"\n]+)"',block)[1]
        replacements.append((block,re.sub(r'^\(pin\s+\w+', '(pin '+pin_type(c,p),block)))
    for old,new in replacements:lib=lib.replace(old,new)
    return lib,h

def create(design,folder,source_present=True):
    folder.mkdir(parents=True,exist_ok=True);ns=uuid.UUID('c5a1a581-29ae-4864-bada-03bd5e2602bf');uid=lambda x:str(uuid.uuid5(ns,x));rid=uid('root');name='functional-audit';libs={};body=[];types=[]
    for i,c in enumerate(design['components']):
        ref=c['ref'];typ='P_'+ref;lib,h=typed_symbol(c,typ);libs[typ]=lib;x=round((55+90*(i%5))/.635)*.635;y=round((60+75*(i//5))/.635)*.635
        inst=[f'(symbol(lib_id "Yamaha:{typ}")(at {x} {y} 0)(unit 1)(in_bom yes)(on_board yes)(dnp no)(uuid {uid(ref)})',prop('Reference',ref,x,y-h/2-6),prop('Value',c['value'],x,y-h/2-3)]
        for p,px,py,angle,pname in pins(lib):
            xx,yy=x+px,y-py;net=c['pins'][p];inst.append(f'(pin {q(p)}(uuid {uid(ref+p)}))');types.append(dict(contact=ref+'.'+p,type=pin_type(c,p),net=net))
            if net is None:body.append(f'(no_connect(at {fmt(xx)} {fmt(yy)})(uuid {uid(ref+p+"NC")}))')
            else:
                ex=xx+(-5.08 if px<0 else 5.08);body.append(f'(wire(pts(xy {fmt(xx)} {fmt(yy)})(xy {fmt(ex)} {fmt(yy)}))(stroke(width 0)(type default))(uuid {uid(ref+p+"wire")}))');body.append(f'(label {q(net)}(at {fmt(ex)} {fmt(yy)} 0){effect(.85,"right" if px<0 else "left")}(uuid {uid(ref+p+"label")}))')
        inst.append(f'(instances(project {q(name)}(path "/{rid}"(reference {q(ref)})(unit 1)))))');body.append(' '.join(inst))
    libs['POWER_PROOF']='(symbol "Yamaha:POWER_PROOF"(pin_names(offset 0)hide)(pin_numbers hide)(in_bom no)(on_board no)'+prop('Reference','#SRC',0,0,True)+prop('Value','POWER_PROOF',0,0,True)+'(symbol "POWER_PROOF_0_1"(polyline(pts(xy -1.27 1.27)(xy 0 2.54)(xy 1.27 1.27))(stroke(width .254)(type default))(fill(type none))))(symbol "POWER_PROOF_1_1"(pin power_out line(at 0 0 90)(length 0)(name "pwr"'+effect()+')(number "1"'+effect()+'))))'
    byref={c['ref']:c for c in design['components']};proofs=[]
    if source_present:proofs=[dict(net='FUSED12',basis='Explicit normal-mode external source at J3.1'),dict(net='GND',basis='Explicit external return at J3.4')]
    if source_present and byref.get('D1',{}).get('pins')=={'A':'FUSED12','K':'BOAT12_PROTECTED'}:
        proofs.append(dict(net='BOAT12_PROTECTED',basis='J3.1 -> D1.A/K; diode transfer, not a second supply'))
    if source_present and 'U4' in byref and 'JP1' in byref and byref['U4']['pins']=={'1':'BOAT12_PROTECTED','2':'GND','3':'V5_BUCK','4':'GND'} and len(proofs)==3:
        proofs.append(dict(net='V5',basis='Normal-mode U4.OUT+ -> J5.3 -> JP1 closed shunt -> V5; USB absent. U4 removed suppresses this driver.'))
    for i,proof in enumerate(proofs):
        x,y=round(520/.635)*.635,round((40+i*12)/.635)*.635;ref='#SRC'+str(i+1);body.append(f'(symbol(lib_id "Yamaha:POWER_PROOF")(at {x} {y} 0)(unit 1)(in_bom no)(on_board no)(dnp no)(uuid {uid(ref)})'+prop('Reference',ref,x,y,True)+prop('Value','POWER_PROOF',x,y-4,True)+f'(pin "1"(uuid {uid(ref+"pin")}))(instances(project {q(name)}(path "/{rid}"(reference {q(ref)})(unit 1)))))');body.append(f'(label {q(proof["net"])}(at {x} {y} 0){effect(.85,"left")}(uuid {uid(proof["net"]+"proof")}))')
    sch=folder/(name+'.kicad_sch');sch.write_text(f'(kicad_sch(version 20250114)(generator "eeschema")(uuid {rid})(paper "A0" portrait)(title_block(title "R3 functional audit; typed pins; normal supply mode")(rev "AUDIT1"))(lib_symbols'+''.join(libs.values())+')'+''.join(body)+')\n')
    (folder/(name+'.kicad_pro')).write_text(json.dumps({'meta':{'filename':name+'.kicad_pro','version':1},'schematic':{'connection_grid_size':25.0}})+'\n')
    (folder/'yamaha.kicad_sym').write_text('(kicad_symbol_lib(version 20241209)(generator "kicad_symbol_editor")'+''.join(v.replace('"Yamaha:','"',1) for v in libs.values())+')\n')
    (folder/'sym-lib-table').write_text('(sym_lib_table\n (version 7)\n (lib (name "Yamaha") (type "KiCad") (uri "${KIPRJMOD}/yamaha.kicad_sym") (options "") (descr "Typed audit symbols"))\n)\n')
    save(folder/'pin-types-and-supply-proofs.json',dict(pins=types,supply_proofs=proofs,mode='Bordnetz on; JP1 closed; USB absent'))
    return sch

def execute(design,folder,source_present=True):
    sch=create(design,folder,source_present)
    erc=run([KICAD,'sch','erc','--format','json','--severity-all','--exit-code-violations','-o',str(folder/'erc.json'),str(sch)],capture_output=True,text=True,timeout=60)
    (folder/'erc.log').write_text(erc.stdout+erc.stderr)
    export=run([KICAD,'sch','export','netlist','--format','kicadxml','-o',str(folder/'netlist.xml'),str(sch)],capture_output=True,text=True,timeout=60)
    (folder/'netlist.log').write_text(export.stdout+export.stderr)
    groups=set()
    if export.returncode==0:
        tree=ET.parse(folder/'netlist.xml')
        for net in tree.findall('.//nets/net'):
            nodes=tuple(sorted(n.attrib['ref']+'.'+n.attrib['pin'] for n in net.findall('node') if not n.attrib['ref'].startswith('#')))
            if nodes:groups.add(nodes)
    expected=target_groups(design)
    violations=[v for sheet in read(folder/'erc.json').get('sheets',[]) for v in sheet.get('violations',[])] if (folder/'erc.json').exists() else []
    return dict(erc_returncode=erc.returncode,export_returncode=export.returncode,net_match=groups==expected,unexpected=sorted(groups-expected),missing=sorted(expected-groups),schematic_sha256=sha(sch),violations=violations)

def main():
    d=read(R/'inputs/design.json');result=execute(d,OUT/'kicad')
    fs=[finding('E-ERC','KiCad-Prüfschaltung','Echte Regelprüfung mit funktionsgerechten Pinarten','PASS' if result['erc_returncode']==0 else 'FAIL',result,'Keine reine passive Symbolprüfung; Versorgungshinweise sind an den beschriebenen Bordnetzpfad gebunden.'),finding('E-KICAD-NETS','KiCad-XML-Netzliste','Exportierte terminalbezogene Verbindungen entsprechen dem Sollvertrag','PASS' if result['net_match'] and result['export_returncode']==0 else 'FAIL',dict(unexpected=result['unexpected'],missing=result['missing']),'Unabhängige Auswertung der tatsächlich exportierten KiCad-Verbindungen.'),finding('E-ERC-SCOPE','Kaufmodule und USB-Modus','Schaltplan entspricht der konkreten Clone-/Modul-Innenschaltung','OPEN',dict(mode='Normal supply mode; USB absent',remaining=['USB diode/regulator topology of actual Nano clone','I2C pull-ups and exact module input/output buses']),'Korrekte IC-Pinarten belegen keine unbekannte Modul-Innenschaltung.')]
    report('kicad',fs,result=result)

if __name__=='__main__':main()
