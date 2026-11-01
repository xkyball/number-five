"""Fresh native KiCad netlist readback; connectivity, not a generic ERC PASS."""
import json,sys,uuid,xml.etree.ElementTree as ET
from collections import defaultdict
from common import ROOT,OUT,TOOLS,read,save,sha
from electronics import circuit
sys.path.insert(0,str(TOOLS/'native-tools'))
from native_tools import run

def uid(s):return str(uuid.uuid5(uuid.NAMESPACE_URL,'iot/venterior/v15/'+s))
def q(s):return json.dumps(str(s),ensure_ascii=False)
def effect():return '(effects (font (size1.0 1.0)))'.replace('size1.0','size 1.0')

def generate(c,folder,pin_types=None,sheet='venterior-v15'):
    """Serialize a circuit; typed mode is used by the independent ERC audit."""
    folder.mkdir(parents=True,exist_ok=True);root=uid(sheet+'root');libs=[];objects=[];refmap={};expected={}
    parts=list(c['parts'])
    if pin_types is not None:
        # This flags the USB return reference, not a guarantee of power or wiring.
        parts += [dict(ref='USB_RETURN_REFERENCE',value='external USB return',pins={'1':'GND'})]
        pin_types={**pin_types,'USB_RETURN_REFERENCE':{'1':'power_out'}}
    for i,p in enumerate(parts,1):
        ref='U'+str(i);refmap[ref]=p['ref'];lib='V15_'+str(i);pins=[];x=50+(i%5)*80;y=50+(i//5)*120
        if pin_types is not None:x=50.8+(i%5)*81.28;y=50.8+(i//5)*121.92
        offset=10 if pin_types is None else 10.16
        for j,(pin,net)in enumerate(p['pins'].items()):
            yy=-j*2.54;wx=round(x-offset,5);wy=round(y-yy,5)
            typ='passive' if pin_types is None else pin_types[p['ref']][pin]
            pins.append(f'(pin {typ} line (at -{offset} {yy} 0) (length5.08) (name {q(pin)} {effect()}) (number {q(pin)} {effect()}))'.replace('length5','length 5'))
            if net is None or p.get('dnp'):objects.append(f'(no_connect (at {wx} {wy}) (uuid {uid(ref+pin+"nc")}))')
            else:
                objects.append(f'(wire (pts (xy {wx} {wy}) (xy {wx-5.08} {wy})) (stroke (width 0) (type default)) (uuid {uid(ref+pin+"wire")}))')
                objects.append(f'(label {q(net)} (at {wx-5.08} {wy} 0) {effect()} (uuid {uid(ref+pin+"label")}))')
                expected[p['ref']+'.'+pin]=net
        props=' '.join(f'(property {q(k)} {q(v)} (at 0 0 0) {effect()})'for k,v in[('Reference','U'),('Value',p['value'])])
        libs.append(f'(symbol "V15:{lib}" (pin_names (offset0.5)) (in_bom yes) (on_board yes) {props} (symbol "{lib}_0_1" (rectangle (start -5 2) (end5 -10) (stroke (width0.2) (type default)) (fill (type background)))) (symbol "{lib}_1_1" '+ ' '.join(pins)+'))')
        pininst=' '.join(f'(pin {q(pin)} (uuid {uid(ref+pin)}))'for pin in p['pins'])
        objects.append(f'(symbol (lib_id "V15:{lib}") (at {x} {y} 0) (unit1) (in_bom yes) (on_board yes) (dnp {"yes"if p.get("dnp")else"no"}) (uuid {uid(ref)}) (property "Reference" "{ref}" (at {x} {y-5} 0) {effect()}) (property "Value" {q(p["value"])} (at {x} {y-2} 0) {effect()}) {pininst} (instances (project "{sheet}" (path "/{root}" (reference "{ref}") (unit1)))))')
    text=f'(kicad_sch (version20250114) (generator "eeschema") (uuid {root}) (paper "User" 500 1800) (lib_symbols '+''.join(libs)+')'+''.join(objects)+' (sheet_instances (path "/" (page "1"))))'
    for a,z in[('version2025','version 2025'),('offset0.5','offset 0.5'),('end5','end 5'),('width0.2','width 0.2'),('unit1','unit 1')]:text=text.replace(a,z)
    source=folder/(sheet+'.kicad_sch');source.write_text(text)
    if pin_types is not None:
        # Resolve the audit's actual symbols locally; do not suppress missing-
        # library warnings or depend on the user's global KiCad libraries.
        symbols=''.join(x.replace('(symbol "V15:','(symbol "') for x in libs)
        for a,z in [('offset0.5','offset 0.5'),('end5','end 5'),('width0.2','width 0.2')]:symbols=symbols.replace(a,z)
        (folder/'V15.kicad_sym').write_text('(kicad_symbol_lib (version 20241209) (generator "kicad_symbol_editor") '+symbols+')')
        (folder/'sym-lib-table').write_text('(sym_lib_table (lib (name "V15") (type "KiCad") (uri "${KIPRJMOD}/V15.kicad_sym") (options "") (descr "Source-bound typed audit symbols")))')
        (folder/(sheet+'.kicad_pro')).write_text('{}\n')
    return source,refmap,expected

def main():
    folder=OUT/'checks/kicad'
    source,refmap,expected=generate(circuit(),folder)
    exe='/Volumes/production/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli';out=folder/'readback.xml'
    r=run([exe,'sch','export','netlist','--format','kicadxml','-o',str(out),str(source)],capture_output=True,text=True,timeout=60)
    (folder/'cli.log').write_text(r.stdout+r.stderr)
    if r.returncode:raise RuntimeError('KiCad readback failed: '+r.stderr)
    rootxml=ET.parse(out).getroot();actual={}
    for net in rootxml.findall('./nets/net'):
        n=net.get('name','').lstrip('/')
        for node in net.findall('node'):
            if node.get('ref')in refmap:actual[refmap[node.get('ref')]+'.'+node.get('pin')]=n
    errors=[dict(pin=k,expected=v,actual=actual.get(k))for k,v in expected.items()if actual.get(k)!=v]
    errors += [dict(unexpected_pin=k,net=v)for k,v in actual.items()if k not in expected and not v.startswith('unconnected-')]
    save(OUT/'checks/kicad-netlist.json',dict(status='PASS'if not errors else'FAIL',errors=errors,live_pin_assignments=len(expected),source_sha256=sha(source),readback_sha256=sha(out),scope='Fresh CLI serialization/readback of every live pin/net. Generic passive pin symbols deliberately do not qualify device pin types, ERC, voltage limits or operation. No PCB fabrication output.'))
    print('KiCad',len(expected),'pin/net assignments',len(errors),'differences',flush=True)
    if errors:raise SystemExit(1)

if __name__=='__main__':main()
