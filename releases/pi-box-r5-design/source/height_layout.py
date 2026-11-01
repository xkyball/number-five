"""Height release: retain the large paired envelope; move neither tray in XY."""
import sys,copy
from common import ROOT,read,save,sha
sys.path.insert(0,'/Users/chris/Library/Application Support/IoT-Fusion-Enclosures/pi-box-l3-routing-runtime')
from ortools.sat.python import cp_model

def main():
 d=read(ROOT/'checkpoints/adapter-before-height-release/design.json');e=read(ROOT/'inputs/l4-contact-baseline.json')
 model=cp_model.CpModel();pairs=[p for p in e['connector_pairs']if p.get('board')and p['board']not in ['GPIO_A','HF1_A','HF2_A']];order=['PI_BANK','X_PI_RAW','X_PI_DR','HUB_BANK','X_HUB_D','X_INPUT'];pairs.sort(key=lambda p:order.index(p['id']));vs=[];cost=[]
 for i,p in enumerate(pairs):
  n=len(p['pins']);w=n*5.08+1.5+5 # actual nominal cradle width, larger than conservative paired box
  y=model.new_int_var(80,1740-round(w*10),p['id']);vs.append((p,y,w));cost.append(y)
  if p['id']=='HUB_BANK':model.add(y>=860)
  if p['id']=='X_INPUT':model.add(y>=1450)
  if i:model.add(y>=vs[i-1][1]+round(vs[i-1][2]*10)+20)
 model.minimize(sum(cost));s=cp_model.CpSolver();s.parameters.num_search_workers=1;s.parameters.max_time_in_seconds=5;status=s.solve(model);assert status in [cp_model.OPTIMAL,cp_model.FEASIBLE]
 rows=[]
 for p,y,w in vs:
  yy=s.value(y)/10;ww=26-(4-len(p['pins']))*5.08
  xx=158 if p['id']=='PI_BANK'else 167
  rows.append(dict(id=p['id'],board=p['board'],poles=len(p['pins']),origin=[xx,yy+2.5,38],bounds_mm=[[xx,yy+(w-ww)/2,34],[xx+30,yy+(w-ww)/2+ww,58]],cradle_y=yy,cradle_width=w,mating_direction='+X',unplug_mm=10,wire_exit_reserve_mm=15))
 for id,bid,y in [('X_HF1','HF1_A',120),('X_HF2','HF2_A',157),('X_GPIO','GPIO_A',140.5)]:
  ww=15.84;z=74 if bid=='GPIO_A'else 38;x=70 if bid=='GPIO_A'else 123
  rows.append(dict(id=id,board=bid,poles=2,origin=[x,y+2.5,z],bounds_mm=[[x,y+(16.66-ww)/2,z-4],[x+30,y+(16.66-ww)/2+ww,z+20]],cradle_y=y,cradle_width=16.66,mating_direction='+X',unplug_mm=10,wire_exit_reserve_mm=15))
 d['height_release']=dict(user_authorization='Use manufacturer family reference provisionally; additional tier height allowed; actual connector dimensions and outer shell follow later.',source_reference='inputs/yc100-family-reference.json',planning_envelope_4p_mm=[30,26,24],family_body_mm=[15.1,20.32,18.1],not_exact_sku=True,upper_tray_lift_mm=20,lower_connector_envelope_top_z=58,upper_tray_bottom_z=60,clearance_mm=2,frame_riser_count=4,fastener_strategy='Two M3x12 joints per riser, with closed captured-nut roof. Existing four screws retained; four new screws and nuts.',old_outer_shell_status='DEFERRED_HEIGHT_INVALIDATED_NOT_PRINTABLE_WITH_R5_STACK')
 d['levels']['devices'].update(tray_z=60,module_z=67,reserved_top=98);d['levels']['supply']['reserved_top']=58
 for key in ['pi','hub','can']:d[key]['origin'][2]+=20
 d['hub']['origin'][2]+=15
 d['boards']['GPIO_A']['origin'][2]+=20
 for key in ['levels','can_wall']:d['connectors'][key]['origin'][2]+=20
 d['connectors']['levels']['origin'][2]+=6;d['connectors']['can_wall']['origin'][2]+=7
 d['usb_service']=dict(hub_lift_mm=15,reason='Measured50mm combined Pi/Hub projection leaves5.5mm lateral gap; lifted hub provides lower cable passage.',host_length_mm=270,host_bend_radius_mm=22,host_diameter_mm=4.5,host_basis='length retained measured/source; radius and diameter planning assumptions',can_storage_capacity_mm=1000,can_loop_radius_mm=30,can_diameter_mm=5,can_loop_center_xy=[141,37],can_loop_base_z=107,can_max_turns=5,can_length_status='Actual supplied cable length not established; capacity is an upper planning case, no cable is cut.',future_case_height_status='Final shell follows measured connector and USB stack; old82mm shell invalid.')
 d['connector_mounts']=rows;d['frame']['upper_head_seat_z']=65.6
 save(ROOT/'design.json',d);save(ROOT/'output/elevated-layout.json',dict(status=s.status_name(status),revision='PI-2L-5',engine='Google OR-Tools CP-SAT',source_sha256=sha(ROOT/'design.json'),placements=rows,unchanged_xy=True,old_height_conflict_resolved_by='20mm printed risers',real_connector_fit='OPEN_PROVISIONAL_MANUFACTURER_FAMILY'))
 print('HEIGHT LAYOUT',s.status_name(status),[(r['id'],r['origin'])for r in rows])
if __name__=='__main__':main()
