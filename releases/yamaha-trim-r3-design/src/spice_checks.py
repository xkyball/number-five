"""R3 schematic-derived ngspice checks via the shared batch runner.
Numerical behavior, vendor reference qualification and physical qualification are
reported separately. No MCU, buck or real relay is claimed to be fully simulated.
"""
import json,sys,shutil,re,math,itertools,csv
from pathlib import Path
from contract import R,D,OUT,save,sha
COMMON=next(p for p in R.parents if p.name=='no5-rebuild')/'tools/native-tools'
sys.path.insert(0,str(COMMON))
from spice_batch import execute
F=OUT/'spice';F.mkdir(exist_ok=True)
MODELS='''* Explicit diagnostic models; not qualified stock-part macromodels.
.model N_SMALL NPN(IS=1e-14 BF=110 VAF=50 IKF=.1 CJE=12p CJC=6p TF=.6n RB=10)
.model N_DRIVER NPN(IS=1e-14 BF=50 VAF=50 IKF=.3 CJE=25p CJC=10p TF=.6n RB=5)
.model D_SMALL D(IS=2e-9 RS=1 N=1.7 CJO=4p BV=75)
.model D_REVERSE D(IS=1e-6 RS=.05 N=1.05 BV=50)
.model D_TVS D(IS=1e-9 RS=.5 N=1.5 BV=18 IBV=1m)
.model CONTACT SW(Ron=.1 Roff=1e12 Vt=5.3 Vh=4.1)
.subckt DIAG_AMP inp inn vp vn out
Eopen err vn inp inn 100000
Rdom err dom 1000
Cdom dom vn 22.736u
Bdrive drive vn V=max(0,min(v(vp)-1.5,v(dom)))
Rout drive out 50
.ends
'''
(F/'diagnostic.lib').write_text(MODELS)
def sim(name,body,measures,tran=None,tail='',model=None,classification='behavioral',compat=None):
 folder=F/name;folder.mkdir(exist_ok=True);deck=folder/'circuit.cir';model=model or F/'diagnostic.lib';shutil.copyfile(model,folder/model.name)
 text='* Trim R3 '+name+'\n.include "'+model.name+'"\n'+body+'\n'+('.tran 100u '+str(tran)+'\n'if tran else'.op\n')+'.control\nset num_threads=1\nrun\n'+tail+'\n.endc\n.end\n'
 deck.write_text(text);plan=dict(schema='iot.spice-plan/1',deck=deck.name,deck_sha256=sha(deck),model_status=classification,models=[dict(path=model.name,sha256=sha(model),status='unqualified')],timeout_seconds=35,measurements=[dict(name=n,min=lo,max=hi,unit=u)for n,lo,hi,u in measures])
 if compat:plan['compatibility_mode']=compat
 save(folder/'plan.json',plan);v=execute(folder/'plan.json',R/'reports/spice'/name);save(folder/'result.json',v)
 return dict(name=name,**v)
def readings(r):return {v['name']:v.get('value')for v in r.get('measurements',[])}
def wave(wave_stop=.35,stuck=0):
 pts=[(0,0)]
 for i in range(100):
  t=.001+i*.02
  for a,v in[(t,0),(t+.000001,5),(t+.01,5),(t+.010001,0)]:
   if a<wave_stop:pts.append((a,v))
 pts.extend([(wave_stop,stuck),(.8,stuck)])
 return 'PWL('+' '.join(str(t)+' '+str(v)for t,v in sorted(pts))+')'
def circuit(v5='5',enable='PWL(0 0 .2 0 .200001 5 .8 5)',heartbeat=None,sensor='100',wrong=None,short=None,source=12.4,cap_scale=1,coil_r=320,coil_l=.2,amp_model='diagnostic'):
 lines=['V5 V5 0 '+v5,'Vboat FUSED12 0 '+str(source),'Venable RELAY_ENABLE 0 '+enable,'Vhb HEARTBEAT 0 '+(heartbeat or wave(.8)), 'Vgate INHIBIT_GPIO 0 0','Vdac DAC_OUT 0 1.665', 'Ipower BOAT12_PROTECTED 0 .05']
 if wrong:lines.append('Vwrong SENSOR_RAW 0 '+str(wrong))
 else:lines.append('Rsender SENSOR_RAW 0 '+str(sensor))
 for c in D['components']:
  p=c['pins'];ref=c['ref'];kind=c['kind']
  if kind=='resistor':lines.append(ref+' '+p['1']+' '+p['2']+' '+str(c['resistance_ohm']))
  elif kind=='capacitor':
   v=re.match(r'([0-9.]+[nu])',c['value'])[1]
   if ref in['CH1','CH2']:v=str(10*cap_scale)+'u'
   lines.append(ref+' '+p['1']+' '+p['2']+' '+v)
  elif kind=='npn':
   if ref==short:lines.append('Rshort_'+ref+' '+p['C']+' '+p['E']+' .001')
   else:lines.append(ref+' '+p['C']+' '+p['B']+' '+p['E']+' '+('N_DRIVER'if'2N2222'in c['sku']else'N_SMALL'))
  elif kind in['diode','tvs']:lines.append(ref+' '+p['A']+' '+p['K']+' '+('D_REVERSE'if ref=='D1'else'D_TVS'if ref=='D2'else'D_SMALL'))
 # Reproduce each LM358 section using its declared pin identities.
 for ref in['U3','U5']:
  p=next(c['pins']for c in D['components']if c['ref']==ref)
  for inp,inn,out in[('3','2','1'),('5','6','7')]:
   if ref=='U3'and out=='1':lines.append('X'+ref+out+' '+p[inp]+' '+p[inn]+' V5 0 '+p[out]+' DIAG_AMP')
   else:lines.append('B'+ref+out+' '+p[out]+' 0 V=(v(V5)>3?max(0,min(v(V5)-1.5,v('+p[inp]+'))):0)')
 lines.extend(['Rcoil BOAT12_PROTECTED COIL_L '+str(coil_r),'Lcoil COIL_L COIL_LOW '+str(coil_l),'Bcoil coil_ctrl 0 V=i(Lcoil)*320','Vecu ecu 0 5.027','Rsource ecu PINK 201.9219','Scontact PINK PINK_INT coil_ctrl 0 CONTACT','.options method=gear'])
 return '\n'.join(lines)
def main():
 cases=[];findings=[]
 # Re-test original manufacturer macro without granting trust to the old adaptation.
 vendor=R/'references/lm358-current-official.lib'
 r=sim('vendor-reference-follower',f'Vcc vp 0 5\nVin vi 0 1\nX1 vi vo vp 0 vo LMX58_LM2904\nRL vo 0 10000\n',[('follower_v',.975,1.025,'V')],tail='let follower_v=v(vo)\nprint follower_v',model=vendor,classification='qualified',compat='psa');cases.append(r)
 vendor_ok=r.get('electrical_status')=='PASS'
 findings.append(dict(id='E-LM358-MODEL',status='PASS'if vendor_ok else'OPEN',requirement='Official macro passes independent follower reference',evidence=dict(model_sha256=sha(vendor),case=r['name'],readings=readings(r)),consequence='Only a bounded model reference; stock manufacturer and dynamic behavior remain unknown. Failed macro is not used for circuit qualification.'))
 for name,sensor in[('zero','.001'),('nominal','100'),('end500','500'),('open','1e12')]:
  rr=float(sensor)*20000/(float(sensor)+20000);goal=rr/(1000+rr)
  r=sim('sensor-'+name,circuit(sensor=sensor),[('ratio',max(0,goal-.0002),goal+.0002,'ratio')],tail='let ratio=2*v(sensor_buffer)/(3*v(excite_buffer))\nprint ratio');cases.append(r)
 findings.append(dict(id='E-SENSOR',status='PASS'if all(c.get('electrical_status')=='PASS'for c in cases[1:])else'FAIL',requirement='Buffered sender/excitation ratio follows physical resistor network',evidence=[dict(case=c['name'],value=readings(c))for c in cases[1:]],consequence='Numerical zero,500ohm and open discrimination; offsets and actual ADC accuracy remain separate.'))
 r=sim('sender12V-powerOFF',circuit(v5='0',enable='0',heartbeat='0',wrong=12),[('backfeed',0,1e-6,'A')],tail='let backfeed=abs(i(V5))\nprint backfeed');cases.append(r)
 findings.append(dict(id='E-BACKFEED',status='PASS'if r.get('electrical_status')=='PASS'else'FAIL',requirement='No original12mA direct sender-to5V reverse-feed path',evidence=dict(case=r['name'],readings=readings(r),basis='Actual R/D connections; LM358 no input diode to positive supply per TI SLOA277B2.2. Buffered outputs power-off behavior diagnostic only.'),consequence='Direct R1 backfeed is removed. Real clone, leakage, negative wiring and USB need measurement.'))
 scenarios=[('healthy',dict(heartbeat=wave(.8))),('enable-stuck-HIGH',dict(heartbeat=wave(.35))),('heartbeat-stuck-HIGH',dict(heartbeat=wave(.35,5))),('heartbeat-stuck-LOW',dict(heartbeat=wave(.35,0))),('Q3-short',dict(heartbeat=wave(.35),short='Q3')),('Q4-short',dict(enable='PWL(0 0 .2 0 .200001 5 .35 5 .350001 0 .8 0)',heartbeat=wave(.8),short='Q4')),('supply-lost',dict(v5='PWL(0 5 .35 5 .350001 0 .8 0)',heartbeat=wave(.35)))]
 for name,kw in scenarios:
  offlimit=.06 if name=='healthy'else.001
  r=sim('coil-'+name,circuit(**kw),[('on_current',.020,.060,'A'),('late_current',0,offlimit,'A')],tran=.8,tail='meas tran on_current AVG i(Lcoil) FROM=.25 TO=.30\nmeas tran late_current MAX i(Lcoil) FROM=.60 TO=.8\nprint i(Lcoil)\nprint v(watch_hold)\nprint v(pink)');cases.append(r)
  samples={};var=None
  if r.get('log'):
   for line in Path(r['log']).read_text(errors='replace').splitlines():
    f=line.split()
    if f and f[0]=='Index':var=f[-1]
    elif len(f)==3 and f[0].isdigit()and var:
     try:samples.setdefault(int(f[0]),{'time':float(f[1])})[var]=float(f[2])
     except ValueError:pass
  with(F/('coil-'+name)/'waveform.csv').open('w')as f:
   w=csv.writer(f);w.writerow(['time_s','coil_A','hold_V','Yamaha_PINK_V'])
   for _,v in sorted(samples.items()):
    if all(k in v for k in['i(lcoil)','v(watch_hold)','v(pink)']):w.writerow([v['time'],v['i(lcoil)'],v['v(watch_hold)'],v['v(pink)']])
 relay=[c for c in cases if c['name'].startswith('coil-')]
 findings.append(dict(id='E-STUCK-RELAY',status='PASS'if all(c.get('electrical_status')=='PASS'for c in relay)else'FAIL',requirement='Independent controls remove coil holding current after stuck pin/one driver short',evidence=[dict(case=c['name'],readings=readings(c),model_status=c.get('qualification_status'))for c in relay],consequence='Diagnostic actual component topology, not measured contact timing or welded-contact protection.'))
 # Tolerance corner for pulse hold/drop: electrolytics -20%, temperature-equivalent coil resistance.
 for i,(supply,cap,cr)in enumerate(itertools.product([4.6,5.25],[.8,1.2],[320*.9,320*1.1*1.118])):
  r=sim('coil-corner-'+str(i),circuit(v5=str(supply),heartbeat=wave(.35),cap_scale=cap,coil_r=cr),[('on_current',.020,.060,'A'),('late_current',0,.001,'A')],tran=.8,tail='meas tran on_current AVG i(Lcoil) FROM=.25 TO=.30\nmeas tran late_current MAX i(Lcoil) FROM=.60 TO=.8');cases.append(r)
 findings.append(dict(id='E-COIL-CORNERS',status='PASS'if all(c.get('electrical_status')=='PASS'for c in cases if c['name'].startswith('coil-corner-'))else'FAIL',requirement='Pulse hold/drop under stated component/supply sensitivity corners',evidence=dict(V5=[4.6,5.25],cap_fraction=[.8,1.2],coil_ohm=[288,393.536],driver_BF=50,buffer_BF=110,coil_inductance_assumption_H=.2),consequence='Coil inductance, real capacitor tolerance and contact release remain unqualified.'))
 # The divider claim follows actual three resistors in each branch.
 by={c['ref']:c for c in D['components']};ratios={k:by['R'+k+'3']['resistance_ohm']/sum(by['R'+k+str(i)]['resistance_ohm']for i in[1,2,3])for k in['P','D','X']}
 margin=4.6-2-5.5*ratios['P']
 findings.append(dict(id='E-COMMON-MODE',status='PASS'if margin>.1 else'FAIL',requirement='LM358 output-loop inputs stay below actual minimum-supply common-mode limit',evidence=dict(divider_ratios=ratios,worst_ECU_V=5.5,min_V5=4.6,margin_V=margin),consequence='Corrects R2 counterexample; external overvoltage behavior and stock vendor remain separate.'))
 findings.extend([dict(id='E-ACTIVE-LOOP',status='OPEN',requirement='Real loop stability and5–500ohm output range',evidence='Diagnostic amp only; official macro reference result above',consequence='No qualified loop/lowest endpoint claim. Oscilloscope and endpoint measurement recommended.'),dict(id='E-TOLERANCES',status='OPEN',requirement='Whole-system accuracy',evidence='Resistor tolerances/temperature, actual LM358 offset, calibrated bandgap, DAC errors and required Yamaha accuracy not all measured',consequence='No claimed overall percentage accuracy.'),dict(id='E-POWER-REAL',status='OPEN',requirement='USB, board transients, clone ground/power paths',evidence='No real clone or converter opened/measured; USB procedure disconnects J2/J3/JP1',consequence='Digital models do not replace a power/backfeed or marine-operation measurement.')])
 report=dict(status='FAIL'if any(f['status']=='FAIL'for f in findings)else'OPEN',run_status='PASS',source_sha256=sha(R/'inputs/design.json'),findings=findings,cases=[dict(name=c['name'],status=c['status'],electrical_status=c.get('electrical_status'),qualification_status=c.get('qualification_status'),measurements=c.get('measurements'),error=c.get('error'))for c in cases],classification='Numerical/structural checks with explicit diagnostic models; physical qualification is separate.')
 save(R/'reports/spice-summary.json',report);print('SPICE',report['status'],len(cases),'cases',[(f['id'],f['status'])for f in findings])
if __name__=='__main__':main()
