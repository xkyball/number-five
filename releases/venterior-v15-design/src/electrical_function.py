"""Typed ERC, datasheet limits and bounded primitive-circuit simulations.

Manufacturer input models, nominal values and analysis assumptions are kept
separate. No whole-device or firmware simulation is claimed.
"""
import copy, itertools, math, re, sys
from common import ROOT, OUT, TOOLS, read, save, sha
from plausibility_common import AUDIT, CONTRACT, finding, report
from kicad_check import generate
sys.path.insert(0,str(TOOLS/'native-tools'))
from native_tools import run
from spice_batch import execute

KICAD='/Volumes/production/Applications/KiCad/KiCad.app/Contents/MacOS/kicad-cli'

def value(p):
    # Only leading numeric component values; do not parse package/rating numbers.
    m=re.match(r'\s*([0-9.]+)\s*(meg|[pnumkM])?',p['value'])
    if not m:raise ValueError('No supported numeric value: '+p['ref'])
    return float(m[1])*{None:1,'p':1e-12,'n':1e-9,'u':1e-6,'m':1e-3,'k':1e3,'M':1e6,'meg':1e6}[m[2]]

def pin_types(c,contract):
    typed={};rules=contract['pin_types']
    for p in c['parts']:
        special=rules.get(p['ref'],rules.get(p['kind'],{}));typed[p['ref']]={}
        for pin,net in p['pins'].items():
            if pin in special:typ=special[pin]
            elif p['ref']=='PICO':typ='bidirectional'
            elif p['kind'] in ('dual_opamp','dip14','dip16'):raise ValueError('Missing active pin rule: '+p['ref']+'.'+pin)
            else:typ='passive'
            typed[p['ref']][pin]=typ
    return typed

def critical_errors(c,contract):
    by={p['ref']:p for p in c['parts']};bad=[]
    for ref,pins in contract['critical_pin_nets'].items():
        for pin,net in pins.items():
            actual=by.get(ref,{}).get('pins',{}).get(pin)
            if actual!=net:bad.append(dict(ref=ref,pin=pin,expected=net,actual=actual))
    return bad

def erc(c,types,folder):
    source,refs,expected=generate(c,folder,types,sheet='venterior-v15-typed')
    target=folder/'erc.json'
    result=run([KICAD,'sch','erc','--format','json','--severity-all','--exit-code-violations','-o',str(target),str(source)],capture_output=True,text=True,timeout=60)
    (folder/'cli.log').write_text(result.stdout+result.stderr)
    if not target.exists():raise RuntimeError('ERC produced no report: '+result.stdout+result.stderr)
    data=read(target)
    if 'sheets' not in data:raise RuntimeError('Unsupported KiCad ERC report schema')
    violations=[dict(v,sheet=s['path']) for s in data['sheets'] for v in s.get('violations',[])]
    version=run([KICAD,'version'],capture_output=True,text=True,timeout=15)
    return dict(status='FAIL' if violations or result.returncode else 'PASS',returncode=result.returncode,
                violations=violations,source_sha256=sha(source),report_sha256=sha(target),
                reference_map=refs,version=version.stdout.strip(),typed_pins=types)

def simulate_primitives(parts,contract,folder):
    """Independent endpoint/RC and charge-sharing checks using builtins only."""
    folder.mkdir(parents=True,exist_ok=True)
    ri=value(parts['R_T_IN']);rg=value(parts['R_T_G']);cap=value(parts['C_T_IN']);gain=rg/(ri+rg);tau=ri*rg/(ri+rg)*cap
    sr=value(parts['R_SUP_IN']);sg=value(parts['R_SUP_G']);cc=value(parts['C_SUP']);rth=sr*sg/(sr+sg)
    lines=['V15 actual passive subcircuits; input sources are test stimuli, not qualified original device models',
           'Vin raw 0 PWL(0 0 1m 0 1.000001m 3.3 100m 3.3)',f'Rin raw temp {ri}',f'Rg temp 0 {rg}',f'Ctemp temp 0 {cap}',
           'V6 src6 0 6',f'Rsu src6 sup {sr}',f'Rsg sup 0 {sg}',f'Csu sup 0 {cc}',
           'Vdead rail0 0 0',f'Rlive raw unpowered_input {ri}',f'Rliveg unpowered_input 0 {rg}']
    # Reservoir initially settled. Typical 20pF sample capacitor initially at
    # opposite rail, 1kohm typical switch: a passive input-model experiment.
    lines += ['V33 v33 0 3.3',f'Rcharge v33 reservoir {rth}',f'Cres reservoir 0 {cc} IC=3.3',
              'Rswitch reservoir sample 1000','Csample sample 0 20p IC=0']
    for label,leak in [('leak_sink',1e-6),('leak_source',-1e-6)]:
        lines += [f'Ru_{label} src6 {label} {sr}',f'Rg_{label} {label} 0 {sg}',f'C_{label} {label} 0 {cc}',f'I_{label} {label} 0 {leak}']
    lines += [
              '.tran 1u 100m 0 1u uic',
              '.meas tran temp_settled FIND v(temp) AT=100m',
              f'.meas tran temp_tau FIND v(temp) AT={.001+tau}',
              '.meas tran supply_settled FIND v(sup) AT=100m',
              '.meas tran unpowered_external_input FIND v(unpowered_input) AT=100m',
              '.meas tran acquisition_sample FIND v(sample) AT=15u',
              '.meas tran leak_sink FIND v(leak_sink) AT=100m',
              '.meas tran leak_source FIND v(leak_source) AT=100m','.end']
    steady=3.3*gain;one_tau=steady*(1-math.exp(-1))
    expected_supply=6*sg/(sr+sg)*(1-math.exp(-.1/(rth*cc)))
    deck=folder/'passive-transient.cir';deck.write_text('\n'.join(lines)+'\n')
    measurements=[dict(name='temp_settled',min=steady-.001,max=steady+.001,unit='V'),
                  dict(name='temp_tau',min=one_tau-.002,max=one_tau+.002,unit='V'),
                  dict(name='supply_settled',min=expected_supply-.002,max=expected_supply+.002,unit='V'),
                  dict(name='unpowered_external_input',min=steady-.001,max=steady+.001,unit='V'),
                  dict(name='acquisition_sample',min=3.29,max=3.3,unit='V')]
    for label,sign in [('leak_sink',-1),('leak_source',1)]:
        v=(6*sg/(sr+sg)+sign*rth*1e-6)*(1-math.exp(-.1/(rth*cc)))
        measurements.append(dict(name=label,min=v-.002,max=v+.002,unit='V'))
    plan=dict(schema='iot.spice-plan/1',deck=deck.name,deck_sha256=sha(deck),model_status='builtin',models=[],measurements=measurements,timeout_seconds=30,
              scope='Actual passive component values, ideal original-device stimuli. Typical ADC input equivalent only; not TS272, HC221, PhotoMOS, full MCP3204 or powered-off pin-clamp qualification.')
    save(folder/'plan.json',plan);result=execute(folder/'plan.json',folder/'result')
    return result,dict(temperature_gain=gain,temperature_tau_s=tau,supply_thevenin_ohm=rth,
                       supply_tau_s=rth*cc,adc_lsb_v=3.3/4096,
                       worst_datasheet_leakage_error_bound_v=rth*1e-6,
                       acquisition_time_at_100khz_s=1.5/100000)

def main():
    c=read(OUT/'data/electronics.json');contract=read(CONTRACT)['electrical'];parts={p['ref']:p for p in c['parts']};rows=[]
    types=pin_types(c,contract);native=erc(c,types,AUDIT/'erc/nominal')
    save(AUDIT/'typed-erc.json',native)
    rows.append(finding('TYPED_ERC',native['status'],'all populated active pins',
        'Electrical pin rules must be checked by native KiCad, not passive-only serialization',
        dict(violations=native['violations'],version=native['version'],source_sha256=native['source_sha256']),
        'Elektrische Anschlusskonflikte müssen geklärt werden.' if native['status']=='FAIL' else 'Die geprüften Pinarten bestehen KiCad-ERC; Pegel, Zeitverhalten und Bauteilfunktion werden gesondert geprüft.'))
    errors=critical_errors(c,contract)
    rows.append(finding('CRITICAL_PINOUT','FAIL' if errors else 'PASS','active pin functions and simulated passive topology',
        'Independent function-to-pin contract agrees with actual pin nets',errors,
        'Pin-/Funktionszuordnung stimmt im geprüften Umfang.' if not errors else 'Mindestens ein funktionskritischer Pin ist falsch zugeordnet.'))
    regressions=[]
    negative=copy.deepcopy(c);adc=next(p for p in negative['parts'] if p['ref']=='U_ADC');adc['pins']['9'],adc['pins']['10']=adc['pins']['10'],adc['pins']['9']
    regressions.append(dict(case='SPI DIN/DOUT swapped',caught=bool(critical_errors(negative,contract))))
    negative=copy.deepcopy(c);next(p for p in negative['parts'] if p['ref']=='R_T_G')['pins']['2']='TEMP_RAW'
    regressions.append(dict(case='Temperature divider return miswired',caught=bool(critical_errors(negative,contract))))
    badtypes=copy.deepcopy(types);badtypes['U_ADC']['8']='output'
    neg=erc(c,badtypes,AUDIT/'erc/negative-output-conflict')
    regressions.append(dict(case='ADC CS declared output against Pico output',caught=any(v.get('type')=='pin_to_pin' for v in neg['violations']),native_violations=neg['violations']))
    no_usb=copy.deepcopy(c);next(p for p in no_usb['parts'] if p['ref']=='PICO')['pins']['40']=None
    neg_supply=erc(no_usb,types,AUDIT/'erc/negative-missing-usb-source')
    regressions.append(dict(case='USB 5V source disconnected',caught=any(v.get('type')=='power_pin_not_driven' for v in neg_supply['violations']),native_violations=neg_supply['violations']))
    save(AUDIT/'erc-regressions.json',regressions)
    rows.append(finding('ERC_NEGATIVE_TESTS','PASS' if all(x['caught'] for x in regressions) else 'FAIL','independent pin/driver fault cases',
        'Known swapped pins and output conflicts are rejected',regressions,'Nachgewiesen ist die Erkennung dieser Fehlerfälle.'))
    rails=contract['rails_v'];limits=contract['limits']
    for ref,kind,pin in [('U_RX','TS272','8'),('U_AUX','TS272','8'),('U_ADC','MCP3204','14'),('U_LIMIT','CD74HC221','16')]:
        net=parts[ref]['pins'][pin];v=rails.get(net);lim=limits[kind]
        status='OPEN' if v is None else 'PASS' if lim['supply_min_v']<=v<=lim['supply_max_v'] else 'FAIL'
        rows.append(finding('SUPPLY_'+ref,status,ref,'Nominal supply inside operating range',dict(net=net,nominal_v=v,allowed_v=[lim['supply_min_v'],lim['supply_max_v']],source=contract['sources'][kind]),'Nur die nominale Versorgung ist geprüft; Versorgungstoleranz und Einschaltverlauf sind gesondert offen.'))
    result,calc=simulate_primitives(parts,contract,AUDIT/'spice')
    rows.append(finding('PASSIVE_TRANSIENT',result['status'],'temperature/supply/ADC input equivalent',
        'Passive transient responses agree with independent closed-form expectations',dict(measurements=result.get('measurements'),calculation=calc,model_scope='builtin passives; ideal stimuli; typical ADC input equivalent'),
        'Teilnetzverhalten geprüft; dies bestätigt keine aktive Verstärker- oder Gesamtfunktion.'))
    rtol=contract['assumed_analysis_tolerances']['resistor_fraction'];ctol=contract['assumed_analysis_tolerances']['capacitor_fraction']
    rc=value(parts['R_TIM'])*value(parts['C_TIM']);times=[.7*rc*(1+s*rtol)*(1+t*ctol) for s,t in itertools.product([-1,1],repeat=2)]
    rows.append(finding('HC221_TIMING','OPEN','U_LIMIT/R_TIM/C_TIM',
        'At actual 3.3V, pulse must satisfy measured ON/OFF window and remain below unwanted mode threshold under faults/tolerances',
        dict(nominal_at_4v5_s=.7*rc,analysis_corners_at_4v5_s=[min(times),max(times)],coefficient_voltage_v=4.5,actual_voltage_v=3.3,tolerances='assumptions',qualified_3v3_model=None),
        'Die 4,5-V-Formel garantiert keinen maximalen 3,3-V-Tastimpuls; reale Tastzeiten und ein qualifizierter Nachweis fehlen.'))
    led=(3.3-1.25-.2)/value(parts['R_K_LED'])*1000
    rows.append(finding('PHOTOMOS_DRIVER','OPEN','K1/Q_K1/U_LIMIT',
        'LED current and HC221/base drive must hold over actual device corners',
        dict(nominal_led_ma=led,assumed_vf_v=1.25,assumed_vce_v=.2,operate_max_ma=3,recommended_led_ma=[5,10],driver_output_at_3v3='unqualified',original_contact_load='unmeasured'),
        'Nomineller LED-Strom ist plausibel; Treiberreserve, Grenzwerte und Originaltasterlast sind noch nicht belegt.'))
    max_cm=3.3-limits['TS272']['common_mode_top_headroom_v'];raw_boundary=max_cm/(330000/1330000)
    rows.append(finding('SIGNAL_RANGES','OPEN','U_RX/U_AUX/Pico ADC',
        'Real original-device signal ranges stay inside common-mode, output and ADC ranges',
        dict(external_ranges=contract['external_ranges'],aux_common_mode_upper_v=max_cm,aux_raw_nominal_boundary_v=raw_boundary,rx_input_source_impedance='unknown',rx_bandwidth_and_noise='unqualified'),
        'Empfangsdynamik und Messgenauigkeit lassen sich ohne Originalpegel und Verstärkermodell nicht freigeben.'))
    rows.append(finding('ADC_ACQUISITION','OPEN','U_ADC channels 0..3',
        'Source impedance, leakage, settling and reference error meet an explicit measurement error budget',
        dict(**calc,discard_policy=c['adc_contract']['initial_discard_after_channel_change'],measurement_error_budget=None),
        'Der hochohmige Versorgungsteiler benötigt einen Genauigkeitsnachweis; eine 12-Bit-Auflösung allein belegt keine 12-Bit-Genauigkeit.'))
    # Scenarios are individual report entries; no blanket whole-system PASS.
    for scenario in contract['scenarios']:
        evidence=dict(model='OPEN',firmware='unchanged/out of scope')
        if scenario in ('reset','command_stuck_high'):
            evidence.update(arm_pulldown_ohm=value(parts['R_ARM']),command_pulldown_ohm=value(parts['R_CMD']),reset_pin_net=parts['U_LIMIT']['pins']['3'],timing='HC221_TIMING')
        if scenario=='USB_off_device_on':
            evidence.update(passive_live_input_example_v=3.3*330000/1330000,aux_supply_v=0,upper_input_clamp_present=False,note='Demonstrates an unprotected live-input path; actual injection behaviour and external levels unqualified')
        if scenario in ('state_stuck_low','signal_lead_open','ground_lead_open','device_supply_open'):
            evidence.update(required_state='UNKNOWN unless qualified signatures distinguish this fault',supervision='device-end EOL, STATE_TEST, Kelvin supply and separate return exist; real fault signatures unqualified')
        rows.append(finding('SCENARIO_'+scenario,'OPEN',scenario,'Required operating/fault state has a qualified bounded electrical and control response',evidence,'Dieser Betriebs-/Fehlerfall bleibt bis zu geeignetem Modell oder realer Prüfung offen.'))
    models=ROOT/'inputs/plausibility/manufacturer/ts27x_family.zip'
    rows.append(finding('ACTIVE_MODEL_QUALIFICATION','OPEN','TS272/HC221/PhotoMOS/MCP3204',
        'Model provenance, pins, conditions and validation precede functional claims',
        dict(ts272_vendor_archive_sha256=sha(models) if models.exists() else None,ts272_vendor_archive='retrieved, not qualified' if models.exists() else 'download OPEN',other_active_models='not qualified'),
        'Herstellerangebot oder Download allein qualifiziert kein Modell; aktive Gesamtfunktion bleibt offen.'))
    save(AUDIT/'electrical.json',report('electrical',rows,scope='Native typed ERC and independent pin contract; passive transients; datasheet nominal limits. No active full-system or firmware acceptance.'))
    print('Electrical functional audit',report('electrical',rows)['status'],flush=True)

if __name__=='__main__':main()
