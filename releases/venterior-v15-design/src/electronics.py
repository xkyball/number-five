"""One real stripboard, semantic pins and retained RX/TX circuits.

MCP3204 slow telemetry leaves the RP2040 ADC0/DMA stream alone. A 221
non-retriggerable one-shot bounds a stuck command; a PhotoMOS provides the
normally-open floating K1 contact. These are new design candidates, not measured
or installed hardware. GPIO2 is a separate default-low hardware arm input.
"""
import csv, dataclasses, io, json, math, sys, time
from collections import defaultdict
from common import ROOT, OUT, read, save, sha

def circuit():
    parts=[]
    def add(ref,kind,value,pins,group,item=None,**kw):
        c=dict(ref=ref,kind=kind,value=value,pins={str(k):v for k,v in pins.items()},group=group,item_id=item,**kw);parts.append(c);return c
    for group in ['rx','marker-tx','aux-sense']:
        for c in read(ROOT/'inputs/previous'/f'{group}-placement.json')['components']:
            if c['kind']=='connector':continue
            c={k:c[k]for k in ['ref','kind','value','pins','dnp']if k in c}
            if group=='marker-tx'and c['ref']=='R_M5':
                c['dnp']=True # Actual breadboard provenance says this leg is unpopulated.
            if group=='aux-sense':
                c['pins']={p:{'GP27_TEMP_ADC1':'TEMP_ADC','GP28_STATE_ADC2':'STATE_ADC'}.get(n,n)for p,n in c['pins'].items()}
            item=125 if c['kind']=='dual_opamp'else 87 if c['kind']=='diode'else 86 if c['ref']in['R_M1','R_M2','R_M3']else None
            add(c.pop('ref'),c.pop('kind'),c.pop('value'),c.pop('pins'),group,item,**c)
    pico=read(ROOT/'inputs/previous/pico-placement.json')['components'][0]['pins']
    pico={k:v for k,v in pico.items()}
    pico.update({'4':'POWER_ARM','6':'SPI_MISO','7':'SPI_CS','9':'SPI_CLK','10':'SPI_MOSI','11':'STATE_TEST','32':None,'34':None})
    add('PICO','pico','Raspberry Pi Pico RP2040, 2 x 20 socket',pico,'pico',79)
    add('U_ADC','dip14','MCP3204-CI/P',{'1':'TEMP_ADC','2':'STATE_ADC','3':'SUPPLY_ADC','4':'LOOP_ADC','5':None,'6':None,'7':'GND','8':'SPI_CS','9':'SPI_MOSI','10':'SPI_MISO','11':'SPI_CLK','12':'GND','13':'PICO_3V3','14':'PICO_3V3'},'telemetry',source='https://ww1.microchip.com/downloads/en/DeviceDoc/21298E.pdf')
    add('U_LIMIT','dip16','CD74HC221E',{'1':'GND','2':'GP20_POWER','3':'POWER_ARM','4':None,'5':None,'6':None,'7':None,'8':'GND','9':'GND','10':'GND','11':'GND','12':None,'13':'K1_PULSE','14':'TIMER_C','15':'TIMER_RC','16':'PICO_3V3'},'power',source='https://www.ti.com/lit/ds/symlink/cd74hc221.pdf')
    add('K1','dip4','AQY212EH',{'1':'K1_LED_A','2':'K1_LED_K','3':'K1_A','4':'K1_B'},'power',source='https://industry.panasonic.eu/pd-379914/relays/relays/photomos/photomos-ge-dip4-1-form-a/aqy212eh')
    add('Q_K1','npn','BC547',{'C':'K1_LED_K','B':'K1_BASE','E':'GND'},'power',21)
    def resistor(ref,value,a,b,group='telemetry'):return add(ref,'resistor',value,{'1':a,'2':b},group)
    def cap(ref,value,a,b,group='telemetry'):return add(ref,'capacitor',value,{'1':a,'2':b},group)
    resistor('R_ARM','100000 ohm','POWER_ARM','GND','power')
    resistor('R_CMD','100000 ohm','GP20_POWER','GND','power')
    resistor('R_TIM','1000000 ohm','PICO_3V3','TIMER_RC','power')
    cap('C_TIM','4.7 uF film/non-polar','TIMER_RC','TIMER_C','power')
    resistor('R_K_BASE','4700 ohm','K1_PULSE','K1_BASE','power')
    resistor('R_K_OFF','100000 ohm','K1_BASE','GND','power')
    resistor('R_K_LED','220 ohm','PICO_3V3','K1_LED_A','power')
    cap('C_LIMIT','100 nF','PICO_3V3','GND','power')
    cap('C_ADC','100 nF','PICO_3V3','GND')
    resistor('R_CS','100000 ohm','SPI_CS','PICO_3V3')
    resistor('R_SUP_IN','1000000 ohm','SUPPLY_KELVIN','SUPPLY_ADC')
    resistor('R_SUP_G','330000 ohm','SUPPLY_ADC','GND')
    cap('C_SUP','100 nF','SUPPLY_ADC','GND')
    resistor('R_LOOP','100000 ohm','PICO_3V3','LOOP_ADC')
    cap('C_LOOP','100 nF','LOOP_ADC','GND')
    resistor('R_STATE_TEST','4700000 ohm','STATE_TEST','STATE_SENSE')
    add('JP_TX18','jumper','2.54 mm enable link, normally REMOVED',{'1':'GP18_SOURCE','2':'GP18_TX'},'marker-tx',73)
    add('JP_TX22','jumper','2.54 mm enable link, normally REMOVED',{'1':'GP22_SOURCE','2':'GP22_TX'},'marker-tx',73)
    add('J_SIGNAL','header16','Molex KK254 1x16 friction-lock,2.54mm;22-23-2161 reference',dict(enumerate(['ECHO_AD','GND','T1_BLUE','GND','TX_INJECT_Q10_3','GND','TEMP_RAW','GND','STATE_RAW','GND','K1_A','K1_B','SUPPLY_KELVIN','LOOP_ADC',None,None],1)),'connector')
    # End-of-line resistors are at the original device, NOT another stripboard.
    remote=[dict(ref='RE_STATE',value='100000 ohm 1%',pins={'1':'STATE_RAW','2':'GND'},placement='At original STATE tap, insulated axial resistor; never on main board'),dict(ref='RE_LOOP',value='100000 ohm 1%',pins={'1':'LOOP_ADC','2':'GND'},placement='At original device GND solder point, separate loop wire; never on main board')]
    return dict(parts=parts,remote=remote,unused_adc_pins=['GP27','GP28'],adc_contract=dict(bus='SPI0, mode0',clk_gpio=6,mosi_gpio=7,miso_gpio=4,cs_gpio=5,clock_hz=100000,samples_per_second_per_channel=10,initial_discard_after_channel_change=1,channels=['TEMP_ADC','STATE_ADC','SUPPLY_ADC','LOOP_ADC'],acquisition_policy='Keep RP2040 ADC0/DMA unchanged; SPI telemetry in CPU time outside critical capture scheduling; firmware not implemented'),power_contract=dict(command_gpio=20,arm_gpio=2,default='ARM low, command low, PhotoMOS open; no TX authority',one_shot='Non-retriggerable CD74HC221E,1M /4.7uF film/non-polar',nominal_4v5_seconds=.7*1000000*4.7e-6,timing_3v3='OPEN: characterize actual RC and K1 minimum/maximum press time before release. Existing manual reference2sON/3sOFF/5sSIMULATION is not measured acceptance. ARM terminates a qualified shorter pulse; hardware maximum must remain below unwanted mode threshold including tolerances.',states=['ON','OFF_QUALIFIED','UNKNOWN'],start_stop='Pi sonar start requests ON; stop requests OFF, both only after qualified fresh state; repeated blind toggles prohibited',feedback='Two-level STATE_TEST test plus device-end EOL; Kelvin supply plus separate GND loop; real joint/lead and supply-fault injection mandatory. No OFF qualification inferred from low D alone.',automatic_operation_released=False),source_changes=['Removed six physical logic daughterboards; all functions and Pico sockets on one new board','GP27/28 retired from telemetry, replaced by MCP3204 SPI; U_AUX buffers retained','Replaced unbounded non-floating BC547 K1 contact by isolated PhotoMOS + non-retriggerable one-shot; BC547 drives LED only','R_M5 deliberately DNP from existing breadboard provenance; no implicit population','Added device-end supervision, Kelvin supply input and hardware default-low arm'])

def footprint(c):
    kind=c['kind']; pins=c['pins']
    if c['ref']=='C_TIM':return {'1':(0,0),'2':(0,2)},[(-2,-1,2,3)]
    if c.get('item_id')==86:return {'1':(0,0),'2':(0,8)},[(-1,1,1,7)]
    if kind=='pico':
        return {str(i):(0,i-1)for i in range(1,21)}|{str(i):(7,40-i)for i in range(21,41)},[(-1,-1,8,20)]
    if kind=='dual_opamp'or kind.startswith('dip'):
        n=8 if kind=='dual_opamp'else int(kind[3:]); half=n//2
        return {str(i):(0,i-1)for i in range(1,half+1)}|{str(i):(3,n-i)for i in range(half+1,n+1)},[(1,-1,2,half)]
    if kind=='header16':return {str(i):(0,i-1)for i in range(1,17)},[(-1,-1,1,16)]
    if kind=='npn':return {'C':(0,0),'B':(0,1),'E':(0,2)},[(1,0,1,2)]
    if kind=='jumper':return {'1':(0,0),'2':(0,1)},[]
    keys=list(pins);gap=4 if kind=='resistor'or kind=='diode'else 2
    return {keys[0]:(0,0),keys[1]:(0,gap)},[(0,1,0,gap-1)]

def place(c,cols,rows):
    # Packages and signal exit are placed first. Remaining two-lead parts are
    # distributed by function, with genuine body keepouts consumed by the router.
    fixed={'PICO':(3,9),'U_RX':(15,5),'U_AUX':(15,18),'U_ADC':(30,4),'U_LIMIT':(42,4),'K1':(42,22),'Q_K1':(54,26),'JP_TX18':(50,27),'JP_TX22':(54,31),'J_SIGNAL':(56,3),'C_TIM':(46,31),'R_M1':(24,3),'R_M2':(24,15),'R_M3':(36,17)}
    occupied=set();placed=[]
    def cells(c,x,y):
        pp,rr=footprint(c);q={(x+a,y+b)for a,b in pp.values()}
        for x0,y0,x1,y1 in rr:q|={(x+a,y+b)for a in range(x0,x1+1)for b in range(y0,y1+1)}
        if c['kind']=='pico':q|={(x+a,y+b)for a in range(0,9)for b in range(-8,-1)} # USB plug,22x12mm, before port
        if c['kind']=='dual_opamp'or c['kind'].startswith('dip'):
            # Two outside columns are reserved for accessible fan-out solder
            # holes; these are placement clearances, not physical body blocks.
            half=len(pp)//2;q|={(x+a,y+b)for a in range(-2,6)for b in range(-1,half+1)}
        if c['kind'] in ['resistor','diode','capacitor']:
            q|={(x+a,y+b)for a in [-1,1]for b in range(max(z[1]for z in pp.values())+1)}
        return q
    def legal(q):return all(1<=x<=cols and 1<=y<=rows for x,y in q)and not occupied.intersection(q)
    for p in sorted(c['parts'],key=lambda p:p['ref']not in fixed):
        pp,rr=footprint(p)
        if p['ref']in fixed:
            x,y=fixed[p['ref']]
            if not legal(cells(p,x,y)):raise ValueError('Fixed package outside/overlap '+p['ref'])
        else:
            target={'rx':(16,12),'marker-tx':(23,13),'aux-sense':(17,26),'power':(44,25),'telemetry':(31,16)}[p['group']]
            candidates=[(x,y)for y in range(2,rows-5)for x in range(2,cols-1)]
            candidates.sort(key=lambda q:abs(q[0]-target[0])+abs(q[1]-target[1]))
            x,y=next(q for q in candidates if legal(cells(p,*q)))
        occupied|=cells(p,x,y);placed.append({**p,'origin':[x,y],'pad_grid':{pin:[x+a,y+b]for pin,(a,b)in pp.items()},'body_keepouts':[[x+a,y+b,x+d,y+e]for a,b,d,e in rr]})
    return placed

def inputs(c,cols,rows):
    from stripboard.router import ComponentType,ComponentInstance,PinDef,Rect,Net
    parts=place(c,cols,rows);instances=[];nets=defaultdict(set)
    for p in parts:
        pp,rr=footprint(p)
        typ=ComponentType(p['ref'],tuple(PinDef(k,v)for k,v in pp.items()),tuple(Rect(*q)for q in rr))
        instances.append(ComponentInstance(p['ref'],typ,tuple(p['origin']),locked=True))
        for pin,n in p['pins'].items():
            if n is not None and not p.get('dnp'):nets[n].add((p['ref'],pin))
    # DNP pads and unused device pins are explicit private nets to keep copper
    # clear; no fabricated electrical internal connection is used for routing.
    known={pin for refs in nets.values()for pin in refs}
    for p in parts:
        for pin in p['pins']:
            if(p['ref'],pin)not in known:nets['NC_'+p['ref']+'_'+pin].add((p['ref'],pin))
    return parts,instances,[Net(n,frozenset(v))for n,v in nets.items()]

def main():
    from stripboard.router import Board,route,RouteOptions,PlacementOptions,RipupOptions,to_dict
    t=time.monotonic();c=circuit();p=read(ROOT/'parameters.json');q=p['board']
    parts,instances,nets=inputs(c,q['cols'],q['rows'])
    opts=RouteOptions(placement=PlacementOptions(sa_iterations=0,brute_force_budget=1),ripup=RipupOptions(max_attempts=18,patience=4),max_placement_attempts=1)
    result=route(Board(q['cols'],q['rows']),instances,nets,seed=15,options=opts)
    print('Router',result.status,'nets',len(nets),'unrouted',[n.net_id for n in result.net_status if not n.routed],flush=True)
    save(ROOT/'evidence/router-library.json',dict(package='stripboard==0.6.0',source='https://github.com/Stocko-2073/stripboard-py',source_commit='346efd8425cc13cbde22ab8261df6c8cbd817973',seconds=time.monotonic()-t,result=to_dict(result)))
    c.update(parts=parts,board=q,router_status=str(result.status),router_ok=result.ok)
    c['strips']=[dataclasses.asdict(x)for x in result.routing.all_strips()]
    c['bridges']=[dict(id='W'+str(i),**dataclasses.asdict(x),side='component')for i,x in enumerate(result.routing.all_jumpers(),1)]
    c['cuts']=[list(x)for x in sorted(result.physical_cuts)]
    save(OUT/'data/electronics.json',c)
    # The beta solver only cuts at holes and cannot route adjacent IDC pins.
    # Its partial result is evidence, never a build instruction. The final
    # layout uses between-hole cuts and independently verified top-side wires.
    from final_routing import complete
    complete(c)

if __name__=='__main__':main()
