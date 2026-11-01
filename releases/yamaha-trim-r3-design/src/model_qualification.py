"""Explicit single-token TI model compatibility experiment; no hidden ideal swap."""
from pathlib import Path
from contract import R,save,sha
from spice_checks import sim,readings

def main():
 original=R/'references/lm358-current-official.lib';adapted=R/'references/lm358-ngspice-temperature.lib'
 src=original.read_text();assert src.count('T_ABS=-273.15')==1
 adapted.write_text('* Compatibility adaptation ONLY: noise resistor temperature0.01K instead of0K. No device topology/value change.\n'+src.replace('T_ABS=-273.15','T_ABS=-273.14'))
 cases=[]
 for v,sig,temp in[(5,1,25),(4.6,.04,25),(4.6,1.8,50),(5.25,2.5,50)]:
  r=sim('adapter-reference-'+str(len(cases)),f'Vcc vp 0 {v}\nVin vi 0 {sig}\nX1 vi vo vp 0 vo LMX58_LM2904\nRL vo 0 10000\n.temp {temp}\n',[('follower_v',sig-.010,sig+.010,'V')],tail='let follower_v=v(vo)\nprint follower_v',model=adapted,classification='qualified',compat='psa');cases.append(r)
  if r.get('electrical_status')!='PASS':break
 passed=len(cases)==4 and all(r.get('electrical_status')=='PASS'for r in cases)
 save(R/'reports/model-qualification.json',dict(status='PASS_BOUNDED_REFERENCES'if passed else'OPEN',original_sha256=sha(original),adapter_sha256=sha(adapted),exact_change='R_NOISELESS T_ABS -273.15C -> -273.14C (0K ->0.01K). Noise analysis not qualified. No other model change.',reference_cases=[dict(name=c['name'],electrical_status=c.get('electrical_status'),readings=readings(c),status=c['status'])for c in cases],limits='Does not identify stock manufacturer or qualify supply-off, saturation recovery or full loop dynamics.'))
 print('MODEL ADAPTER',passed,[(c['name'],readings(c),c.get('electrical_status'))for c in cases])
if __name__=='__main__':main()
