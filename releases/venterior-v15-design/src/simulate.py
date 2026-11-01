"""Qualified builtin SPICE primitives only; explicit ideal-device scope."""
import sys
from common import ROOT,OUT,TOOLS,save,sha
sys.path.insert(0,str(TOOLS/'native-tools'))
from spice_batch import execute

def main():
    folder=ROOT/'inputs/simulation';folder.mkdir(exist_ok=True);lines=['V15 passive supervision and attenuation, ideal endpoint sources only'];meas=[]
    def metric(name,node,value,tolerance=.0001):
        lines.append(f'.meas tran {name} FIND v({node}) AT=1');meas.append(dict(name=name,min=value-tolerance,max=value+tolerance,unit='V'))
    for name,raw,test in[('off0',0,0),('off1',0,3.3),('on0',3.3,0),('on1',3.3,3.3),('open0',None,0),('open1',None,3.3)]:
        n='s_'+name;lines.extend([f'Vt_{name} t_{name} 0 {test}',f'Rt_{name} t_{name} {n} 4.7meg',f'Rg_{name} {n} 0 330k',f'C_{name} {n} 0 10n'])
        if raw is not None:lines.extend([f'Vd_{name} d_{name} 0 {raw}',f'Re_{name} d_{name} 0 100k',f'Ri_{name} d_{name} {n} 1meg'])
        g=1/330000+1/4700000+(1/1000000 if raw is not None else 0)
        value=(test/4700000+(raw/1000000 if raw is not None else 0))/g
        metric(name,n,value)
    lines+=['V33 v33 0 3.3','Rl1 v33 loop_ok 100k','Rl2 loop_ok 0 100k','Cl loop_ok 0 100n','Rlo v33 loop_open 100k','Clo loop_open 0 100n','Rls v33 loop_short 100k','Vls loop_short 0 0','V6 v6 0 6','Rsu v6 sup 1meg','Rsd sup 0 330k','Csu sup 0 100n','Vtemp t_raw 0 1.912','Rtu t_raw temp 1meg','Rtd temp 0 330k','Ct temp 0 10n']
    for name,node,value in[('loop_ok','loop_ok',1.65),('loop_open','loop_open',3.3),('loop_short','loop_short',0),('supply','sup',6*330/1330),('temp17','temp',1.912*330/1330)]:metric(name,node,value)
    lines+=['.tran 1m 1','.end'];deck=folder/'passive.cir';deck.write_text('\n'.join(lines)+'\n')
    plan=dict(schema='iot.spice-plan/1',deck=deck.name,deck_sha256=sha(deck),model_status='builtin',models=[],measurements=meas,timeout_seconds=30,scope='Ideal DC endpoint cases, passive divider/RC and loop supervision only. Not TS272, MCP3204, PhotoMOS, HC221, original device, startup, backfeed or fault coverage qualification.')
    save(folder/'plan.json',plan);r=execute(folder/'plan.json',OUT/'checks/simulation-passive')
    save(OUT/'checks/electrical-function-scope.json',dict(status='OPEN_REAL_FUNCTION',passive_network=r['status'],passive_scope=plan['scope'],open=['Original K1 actual grouping and qualified2s/3s press window below unwanted simulation mode','HC221 maximum pulse at3.3V over actual RC tolerances, reset and stuck-command behaviour','PhotoMOS LED current and K1 on/off load, USB loss/default arm and backfeed','TS272 input/output ranges, actual clamps and noise with converters','MCP3204 acquisition/reference error and SPI scheduling coexistence with RX DMA','Supervised STATE test signatures over ON/OFF, every lead/return and device-end supply interruption; no thresholds released','Original yellow voltage-to-temperature calibration outside two LCD reference points','12V source fuse, polarity, transients and6V output coordination'],automatic_operation_released=False,firmware_changed=False))
    print('SPICE passive',r['status'],'full operation OPEN',flush=True)
    if r['status']!='PASS':raise SystemExit(1)

if __name__=='__main__':main()
