"""The only V15 geometry source. Direct build123d, millimetres, rigid transforms.

No mirrored parts, SCAD translations, Fusion jobs or 3D conductor solids.
Reference purchased geometry is explicitly nominal, not an OEM accuracy claim.
"""
import copy,math
import build123d as b
from common import ROOT,OUT,read

def box(x,y,z,w,d,h):return b.Solid.make_box(w,d,h,b.Plane(origin=(x,y,z)))
def cyl(x,y,z,r,h,direction=(0,0,1)):return b.Solid.make_cylinder(r,h,b.Plane(origin=(x,y,z),z_dir=direction))
def union(*ss):
    s=ss[0].fuse(*ss[1:])if len(ss)>1 else ss[0]
    return b.Compound(children=list(s))if isinstance(s,b.ShapeList)else s
def cut(s,*tools):
    if not tools:return s
    s=s.cut(*tools);return b.Compound(children=list(s))if isinstance(s,b.ShapeList)else s
def slot(x,y,z,length,width,h,axis='x'):
    r=width/2;travel=length-width
    if axis=='x':return union(box(x-travel/2,y-r,z,travel,width,h),cyl(x-travel/2,y,z,r,h),cyl(x+travel/2,y,z,r,h))
    return union(box(x-r,y-travel/2,z,width,travel,h),cyl(x,y-travel/2,z,r,h),cyl(x,y+travel/2,z,r,h))

def make():
    p=read(ROOT/'parameters.json');e=read(OUT/'data/electronics.json');W,D,H=p['body'];t=p['wall'];floor=p['floor'];f=p['fastening'];fix=p['mechanical_fix'];parts={};meta=[];fasteners=[];final_base_pilots=[];cages=[]
    def add(id,s,kind,owner,color,**kw):
        s.label=id;s.color=b.Color(color);parts[id]=s
        meta.append(dict(id=id,kind=kind,owner=owner,color=color,**kw));return s
    def screw(id,x,y,z,ln,owner,direction=-1,target=None,omit=()):
        # z is head bearing plane. The shaft is a nominal thread envelope.
        s=union(cyl(x,y,z,1.45,ln,(0,0,direction)),cyl(x,y,z,3,2.4,(0,0,-direction)))
        # Recess for actual screwdriver, represented in purchased reference.
        s=cut(s,box(x-1.1,y-.45,z+.5 if direction<0 else z-2.5,2.2,.9,2))
        add(id,s,'hardware',owner,'#a5a9ae',thread='nominal envelope; direct plastic engagement physically OPEN')
        fasteners.append(dict(id=id,owner=owner,axis=[x,y,z],direction=direction,length=ln,target=target,omit=list(omit),tool_diameter=8))
    # Floor and outside mounting tabs form one printable body. Posts are
    # continuous material from the floor; never hovering inserts or supports.
    base=box(0,0,0,W,D,floor);tabs=[]
    outside_axes=[(20,-5),(W-20,-5),(80,D+5),(W-20,D+5)]
    for x,y in outside_axes:
        a=union(box(x-9,-5 if y<0 else D-3,0,18,8,3),cyl(x,y,0,7,3))
        a=cut(a,cyl(x,y,-.1,2.2,3.2),slot(x,y+(-3 if y<0 else 3),-.1,8,2.4,3.2))
        tabs.append(a)
    base=union(base,*tabs)
    axes=[(6,6),(W-6,6),(6,D-6),(W-6,D-6)]
    base=cut(base,*[cyl(x,y,-.1,1.7,3.3)for x,y in axes],*[cyl(x,y,-.1,3.25,2.5)for x,y in axes])
    # Three raised edge pads seat the hood without trapping a second floor.
    for x,y,w,d in[(0,0,W,1.8),(0,D-1.8,W,1.8),(0,1.8,1.8,D-3.6),(W-1.8,1.8,1.8,D-3.6)]:base=union(base,box(x,y,3,w,d,.4))
    board=p['board'];bx,by,bz=board['origin'];bw,bd,bt=board['size']
    bm=[(bx+x,by+y)for x,y in board['mounts']]
    for i,(x,y)in enumerate(bm,1):
        post=cut(cyl(x,y,2.8,4.5,bz-2.8),cyl(x,y,2,1.15,bz))
        base=union(base,post);screw(f'PCB_SCREW_{i}',x,y,bz+bt,10,'BOARD',target='BASE')
        final_base_pilots.append(cyl(x,y,fix['board_pilot_floor_z'],f['pilot']/2,bz-fix['board_pilot_floor_z']+.1))
    co=p['converter'];cx,cy,cz=co['origin'];cl,cw,ct=co['size']
    for i,(xx,yy)in enumerate(co['mounts'],1):
        x,y=cx+xx,cy+yy;base=union(base,cut(cyl(x,y,2.8,4.5,cz-2.8),cyl(x,y,2,1.15,cz)))
        screw(f'BUCK_SCREW_{i}',x,y,cz+ct,10,'BUCK',target='BASE')
    # Four front adapters. Each uses its own side-specific coordinates, a
    # fixed tower screw and a real12mm adjustable slot for the original boss.
    front=p['front'];fx,fy,fz=front['origin'];fw,fd,fh=front['size'];front_axes=[]
    adapter_z=fz-4
    for i,(xx,yy)in enumerate(front['bosses'],1):
        x,y=fx+xx,fy+yy;left=xx<fw/2;ax=12 if left else 97
        base=union(base,cut(cyl(ax,y,2.8,5,adapter_z-2.8),cyl(ax,y,adapter_z-9,1.15,10)))
        sx=min(ax-5,x-10);sw=max(ax+5,x+10)-sx
        plate=box(sx,y-9,adapter_z,sw,18,4)
        plate=cut(plate,slot(ax,y,adapter_z-.1,9.4,3.4,4.2,axis='y'),slot(x,y,adapter_z-.1,15.4,3.4,4.2))
        add(f'FRONT_ADAPTER_{i}',plate,'print','FRONT','#788e9b',print_rotation=[0,0,0],adjustment_xy_mm=[12,6],original_screw='Retain original shell screw; diameter/length are unmeasured, reference only')
        screw(f'ADAPTER_SCREW_{i}',ax,y,fz,12,'FRONT',target='BASE',omit=['FRONT'])
        screw(f'ORIGINAL_SCREW_{i}',x,y,adapter_z,9,'FRONT',direction=1,target='FRONT_SHELL',omit=['BASE'])
        # Actual access holes in floor for installing original screws upwards.
        base=cut(base,cyl(x,y,-.1,4.5,3.4));front_axes.append([x,y])
    # PCB: all regular holes and mounting holes are actual cut geometry.
    holes=[cyl(bx+96-(board['datum'][1]+i*2.54),by+board['datum'][0]+j*2.54,bz-.1,.5,bt+.2)for i in range(board['rows'])for j in range(board['cols'])]
    pcb=cut(box(bx,by,bz,bw,bd,bt),*holes,*[cyl(x,y,bz-.1,1.7,bt+.2)for x,y in bm])
    add('MAIN_STRIPBOARD',pcb,'purchased','BOARD','#bd9652',item_id=326,stock_cut=[bw,bd],copper_geometry='Exact copper islands and cuts are the electrical SVG contract, not separate3D conductors')
    def mm(x,y):return bx+board['datum'][0]+(x-1)*2.54,by+board['datum'][1]+(y-1)*2.54
    # Nominal populated components, lead axes and mechanically meaningful sockets.
    for c in e['parts']:
        if c.get('dnp'):continue
        pp={pin:mm(*xy)for pin,xy in c['pad_grid'].items()};zs=bz+bt;kind=c['kind'];ref=c['ref']
        if kind=='pico':
            x,y=pp['1'];right=pp['40'][0]
            for side,sx in enumerate([x,right],1):
                sock=cut(box(sx-1.27,y-1.27,zs,2.54,50.8,8.5),*[cyl(sx,y+i*2.54,zs+5.5,.48,3.2)for i in range(20)])
                add('PICO_SOCKET_'+str(side),sock,'purchased','BOARD','#252f39',planning_height=8.5)
            py=y-2;pz=zs+8.5;px=x-1.61
            pico=cut(box(px,py,pz,21,51,1),*[cyl(px+hx,py+hy,pz-.1,1.05,1.2)for hx,hy in[(4.8,2),(16.2,2),(4.8,49),(16.2,49)]])
            add('PICO',pico,'purchased','BOARD','#26765b',item_id=79)
            add('PICO_MCU',box(px+6,py+20,pz+1,7,7,1),'purchased','BOARD','#363b42')
            add('PICO_USB',box(px+6.5,py-1,pz+1,8,6,3),'purchased','BOARD','#b4bbc2')
            add('PICO_BOOTSEL',box(px+5,py+9,pz+1,3.5,4,2),'purchased','BOARD','#c7c2af')
            usb=dict(origin=[px+10.5,py-1,pz+2.5],plug_envelope=[12,22,9],cable_od_assumption=4)
        elif kind in['dual_opamp','dip14','dip16','dip4']:
            n=len(pp);x,y=pp['1'];h=(n//2-1)*2.54+4.6;w=6.6 if kind=='dual_opamp'else 7.1
            add(ref+'_SOCKET',box(x-1.1,y-2.3,zs,9.8,h,3),'purchased','BOARD','#303942')
            body=cut(box(x+.25,y-2.3,zs+3,w,h,3.4),cyl(x+3.8,y-2.3,zs+4,1,3))
            add(ref,body,'purchased','BOARD','#42484a',value=c['value'],source=c.get('source'))
        elif kind=='header16':
            x,y=pp['1'];shell=cut(box(x-3.5,y-2,zs,7,42.1,10),box(x-2.4,y-1,zs+2,4.8,40.1,8.2),box(x-3.6,y+16,zs+4,2,6,6.2))
            add(ref,shell,'purchased','BOARD','#29323a',value=c['value'],mating_identity='Polarized1x16 KK254 family; exact local mating housing/contacts OPEN')
            add('SIGNAL_MATING_PLUG',box(x-2.1,y-.8,zs+3,4.2,39.7,9),'purchased','BOARD','#53616a',reference='Friction lock, nominal family envelope; cable absent in3D')
        elif kind=='npn':
            xs=[a[0]for a in pp.values()];ys=[a[1]for a in pp.values()]
            add(ref,box(min(xs)-1.6,min(ys)-.2,zs+2,3.2,max(ys)-min(ys)+.4,4.5),'purchased','BOARD','#383f44',value=c['value'])
        else:
            xy=list(pp.values());x=xy[0][0];y0,y1=sorted(a[1]for a in xy)
            rad=(2.5 if c.get('item_id')==86 else 1.1)if kind=='resistor'else .95 if kind=='diode'else 1.3
            height=min(y1-y0-1,(15 if c.get('item_id')==86 else 6.3)if kind=='resistor'else 4)
            if ref=='C_TIM':
                add(ref,box(x-4,(y0+y1)/2-5,zs+1,8,10,13),'purchased','BOARD','#8e945a',value=c['value'],physical_package='4.7uF film/non-polar,5.08mm lead pitch, conservative8x10x13 reserve; exact local package OPEN')
            elif kind=='jumper':
                add(ref,box(x-1.27,y0-1.27,zs,2.54,y1-y0+2.54,2.5),'purchased','BOARD','#343c42',state='shunt absent')
            elif ref=='C_RX2':add(ref,cyl(x,(y0+y1)/2,zs+1,2.5,11),'purchased','BOARD','#786e52',value=c['value'],qualification='Conservative5x11 electrolytic envelope, lead forming to5.08mm')
            elif kind=='capacitor':add(ref,box(x-2.5,(y0+y1)/2-1,zs+1,5,2,5),'purchased','BOARD','#9e874a',value=c['value'])
            else:add(ref,cyl(x,(y0+y1)/2-height/2,zs+rad+1,rad,height,(0,1,0)),'purchased','BOARD','#bbab7d'if kind=='resistor'else'#b78e55',value=c['value'])
        if kind!='pico':
            for pin,(x,y)in pp.items():
                lead=cyl(x,y,bz-1,.24 if kind!='header16'else .3,bt+3.1)
                add(ref+'_PIN_'+pin,lead,'component_pin','BOARD','#c3b492',pin_id=ref+'.'+pin)
    # Proper +90-degree rigid rotation: the long copper axis follows the
    # physical160mm board edge. Pins, bodies and sockets share one transform.
    for m in meta:
        if m['owner']=='BOARD'and m['kind']not in['hardware']and m['id']!='MAIN_STRIPBOARD':
            parts[m['id']]=parts[m['id']].rotate(b.Axis((bx,by,0),(0,0,1)),90).moved(b.Location((96,0,0)))
            parts[m['id']].label=m['id'];parts[m['id']].color=b.Color(m['color'])
    lx,ly,lz=usb['origin'];usb['origin']=[bx+96-(ly-by),by+(lx-bx),lz];usb['exit_direction']=[1,0,0]
    ux0,uy0,uz0=usb['origin'];add('USB_CABLE_PLUG',box(ux0+.2,uy0-6,uz0-4.5,22,12,9),'reference','USB','#37424a',qualification='22x12x9 assumed MicroB cable plug reserve; actual cable dimensions OPEN')
    # Retained original shell, board, display and keypad are reference geometry.
    shell=union(cut(box(fx,fy,fz,fw,fd,fh),box(fx+2,fy+2,fz-.1,fw-4,fd-4,fh-2)),box(fx+2,fy+2,fz+fh-2,fw-4,fd-4,2))
    # Visible LCD/key windows, no replacement zebra-contact construction.
    shell=cut(shell,box(fx+17,fy+15,fz+fh-3,42,50,4),box(fx+10,fy+87,fz+fh-3,56,37,4))
    for x,y in front_axes:shell=union(shell,cut(cyl(x,y,fz,3.9,7),cyl(x,y,fz-.1,1.05,6)))
    add('FRONT_SHELL',shell,'reference','FRONT','#2f3a42',qualification=front['source'])
    ox,oy,oz=front['pcb_offset'];vp=box(fx+ox,fy+oy,fz+oz,60,110,1.6)
    add('VENTERIOR_PCB',vp,'reference','FRONT','#316951',item_id=112,dimensions='Confirmed60x110; location in original shell estimated')
    add('ORIGINAL_LCD',box(fx+17.5,fy+15.5,fz+fh-3,41,49,1.5),'reference','FRONT','#afbcb4',description='Retained complete LCD assembly; internal zebra geometry deliberately not reconstructed')
    add('ORIGINAL_KEYPAD',box(fx+10.5,fy+87.5,fz+fh-3,55,36,1.5),'reference','FRONT','#d8aa35')
    for x,y in[(19,96),(57,96),(29,107),(46,107),(19,117),(57,117)]:add(f'BUTTON_{x}_{y}',cyl(fx+x,fy+y,fz+fh-1.5,4.3,1),'reference','FRONT','#d0a22d')
    # Confirmed converter board/mounts, nominal component envelope within13mm.
    buck=cut(box(cx,cy,cz,cl,cw,ct),*[cyl(cx+x,cy+y,cz-.1,1.5,ct+.2)for x,y in co['mounts']])
    add('XL6009_BOARD',buck,'purchased','BUCK','#356c84',item_id=45,dimensions=[51,60])
    add('XL6009_COIL',box(cx+18,cy+7,cz+ct,12,12,8),'reference','BUCK','#41474a')
    for i,(x,y)in enumerate([(8,16),(39,8)],1):add('XL6009_CAP_'+str(i),cyl(cx+x,cy+y,cz+ct,4.3,11.4),'reference','BUCK','#606564')
    add('XL6009_TRIM',box(cx+34,cy+8,cz+ct,7,7,4),'reference','BUCK','#4b73a0',qualification='Nominal depiction inside module envelope; actual component access remains physical OPEN')
    # Openable cages capture the complete mated pair. The 26x30x24 capacity
    # is a planning envelope, never a fabricated OEM connector surface.
    def cage(prefix,px,py,pz,from_below=False):
        nonlocal base
        capid=prefix+'_CAP';insertid=prefix+'_INSERT';cid=prefix+'_CRADLE'
        cx=px+9.16;inside_left=cx-13.3;inside_right=cx+13.3
        top=pz+fix['connector_capacity_mm'][2]+.5
        body=union(box(px-13,py-3,pz-3,47,36,3),
                   box(inside_left-2.5,py-3,pz-3,2.5,33.5,top-(pz-3)),
                   box(inside_right,py-3,pz-3,2.5,33.5,top-(pz-3)),
                   box(inside_left-2.5,py-3,pz,31.6,2.7,top-pz))
        # Insulated tails pass through clearance holes; they are not anchors.
        body=cut(body,*[cyl(px+2.54+5.08*i,py-3.1,pz+4,.85,3,(0,1,0))for i in range(4)])
        for x in [px-8,px+29]:
            body=cut(body,cyl(x,py+2,pz-3.1,1.7,3.2))
            body=union(body,cyl(x,py+14,pz-3,4.5,top-(pz-3)))
            # The bore is cut AFTER union, preserving a real blind bottom.
            body=cut(body,cyl(x,py+14,top-7.5,1.15,7.6))
            if not from_below:
                # A real 8mm screwdriver corridor through the side wall,
                # with the removable keeper opened, reaches the base screw.
                body=cut(body,cyl(x,py+2,pz,4.1,top-pz+.1))
        cap=cut(box(px-13,py-3,top,47,36,3),*[cyl(x,py+14,top-.1,1.7,3.2)for x in[px-8,px+29]])
        # Exchangeable L-shaped insert: front end stop plus height spacer.
        # The front wire window is smaller than the nominal connector body.
        insert=union(box(inside_left+.1,py+26.3,pz+.1,26.4,2.5,top-pz-.1),
                     box(px+1,py+3,pz+12.3,18,25.8,top-(pz+12.3)))
        insert=cut(insert,box(px+4,py+26.2,pz+3,12,2.7,6))
        add(capid,cap,'print','POWER_KEEPER','#7c96a3',print_rotation=[180,0,0],service='Remove both direct screws, lid and insert before unplugging')
        add(insertid,insert,'print','POWER_KEEPER','#a0b6bd',print_rotation=[180,0,0],qualification='Nominal removable housing stop/height spacer; real YC100 contact faces unmeasured')
        for i,x in enumerate([px-8,px+29],1):
            screw(prefix+'_CAP_SCREW_'+str(i),x,py+14,top+3,10,'POWER_KEEPER',target=cid,omit=['FRONT']if from_below else[])
        if from_below:
            for x in[px-8,px+29]:
                body=union(body,cut(cyl(x,py+2,pz-.2,4.5,10),cyl(x,py+2,pz-3.1,1.15,12.1)))
        add(cid,body,'print','POWER','#788e9b',print_rotation=[0,0,0],nominal_capacity_mm=fix['connector_capacity_mm'],qualification=fix['geometry_assumptions'])
        cages.append(dict(id=prefix,cradle=cid,cap=capid,insert=insertid,origin=[px,py,pz],capacity_mm=fix['connector_capacity_mm'],cap_screws=[prefix+'_CAP_SCREW_1',prefix+'_CAP_SCREW_2'],housing_stop='fixed rear wall and removable front insert; plastic body only',actual_connector_fit='OPEN',rear_component_clearance='OPEN'if from_below else'not under original PCB'))
        return body

    px,py,pz=27,180,12
    cage('POWER',px,py,pz)
    for i,x in enumerate([px-8,px+29],1):
        base=union(base,cut(cyl(x,py+2,2.8,4.5,6.2),cyl(x,py+2,1,1.15,9)))
        screw('POWER_SCREW_'+str(i),x,py+2,12,10,'POWER',target='BASE',omit=['POWER_KEEPER'])
        final_base_pilots.append(cyl(x,py+2,fix['power_pilot_floor_z'],1.15,9.1-fix['power_pilot_floor_z']))
    add('POWER_PANEL_HEADER',box(px,py,pz,20.32,10,12),'purchased','POWER','#4f8968',item_id=237,retention='Closed nominal cage with removable front stop; real body fit OPEN')
    add('POWER_CABLE_PLUG',box(px,py+10,pz,20.32,16,12),'purchased','POWER','#3b7157',item_id=237)
    for i in range(4):add('POWER_TAIL_'+str(i+1),cyl(px+2.54+5.08*i,py-2,pz+4,.45,3,(0,1,0)),'component_pin','POWER','#c0b286')
    # Separate internal6V connector. Reuse the same purchased family and
    # cradle recipe, mounted BEFORE the retained front is fitted. Its two
    # screws enter its fixed bosses from below, no loose nut or thin-floor
    # self-tapping engagement. The approved taller cage leaves nominally
    # 9.5mm below the PCB at43mm. The earlier assumed20mm rear-component
    # reserve is not confirmed; actual rear-component fit remains OPEN.
    cage('DEVICE_POWER',40,50,6,True)
    for i,x in enumerate([32,69],1):
        base=cut(base,cyl(x,52,-.1,1.7,3.3),cyl(x,52,-.1,3.25,2.5))
        screw('DEVICE_POWER_SCREW_'+str(i),x,52,2.4,12,'POWER',direction=1,target='DEVICE_POWER_CRADLE',omit=['BASE'])
    add('DEVICE_POWER_HEADER',box(40,50,6,20.32,10,12),'purchased','POWER','#4f8968',item_id=237,role='X6V,pin3=6V,pin4=loadGND,pins1/2unusedNC; X12V uses1+/2-,3/4NC')
    add('DEVICE_POWER_PLUG',box(40,60,6,20.32,16,12),'purchased','POWER','#3b7157',item_id=237)
    # Hood: an open-bottom cap, no captive nuts. Bottom screws enter pilots
    # in continuous corner bosses. All exterior openings cut right through.
    hood=cut(box(0,0,3.4,W,D,H-3.4),box(t,t,3.3,W-2*t,D-2*t,H-p['roof']-3.3))
    hood=cut(hood,box(fx-.5,fy-.5,H-p['roof']-.1,fw+1,fd+1,p['roof']+.2))
    for x,y in axes:
        hood=union(hood,cyl(x,y,3.4,4.3,fix['hood_boss_top_z']-3.4))
        hood=cut(hood,cyl(x,y,3.3,1.15,fix['hood_pilot_end_z']-3.3))
        screw(f'HOOD_SCREW_{axes.index((x,y))+1}',x,y,2.4,12,'HOOD',direction=1,target='HOOD',omit=['BASE'])
    # USB exits towards front in line with the retained Pico socket. Not a
    # USB panel adapter; a removable split clamp grips the cable insulation.
    ux,uy,uz=usb['origin'];hood=cut(hood,box(W-t-.1,uy-12.4,3.3,t+.2,24.8,uz+5.5-3.3))
    hood=cut(hood,box(px-13.4,D-t-.1,3.3,47.8,t+.2,37))
    # Original transducer cable at left wall:8mm split exit with printed
    # edge guard. Actual original leadOD remains a fit check.
    hood=cut(hood,box(-.1,35,3.3,t+.2,10,11.7),cyl(-.1,40,15,5,t+.2,(1,0,0)))
    add('HOOD',hood,'print','HOOD','#c2cbd0',print_rotation=[180,0,0])
    add('BASE',base,'print','BASE','#71848e',print_rotation=[0,0,0])
    # Replaceable, end-captured jaws. A 0.6mm closing gap prevents the carrier
    # faces from bottoming before the assumed 4mm cable meets the 3.8mm bore.
    jaw_ids=[]
    for i in range(2):
        z=uz-5 if i==0 else uz+fix['usb_closing_gap_mm'];height=5 if i==0 else 5-fix['usb_closing_gap_mm']
        s=box(203,uy-12,z,12,24,height)
        pocket_z=uz-3 if i==0 else z-.1;pocket_h=3.1 if i==0 else 3.1
        s=cut(s,cyl(202,uy,uz,2.4,14,(1,0,0)),box(204,uy-4.2,pocket_z,10,8.4,pocket_h),*[cyl(211,uy+d,z-.1,1.7,height+.2)for d in[-8,8]])
        jz=uz-3 if i==0 else z;jh=3
        jaw=cut(box(204.2,uy-4,jz,9.6,8,jh),cyl(202,uy,uz,fix['usb_jaw_bore_mm']/2,14,(1,0,0)))
        jid='USB_JAW_'+str(i+1);jaw_ids.append(jid)
        add('USB_CLAMP_'+str(i+1),s,'print','USB','#728997',print_rotation=[0,0,0]if i==0 else[180,0,0])
        add(jid,jaw,'print','USB','#a0b6bd',print_rotation=[0,0,0]if i==0 else[180,0,0],qualification='Exchangeable jaw; actual cable diameter and pull force OPEN')
    # Both halves are carried by real towers; the cable clamp cannot float.
    for i,dx in enumerate([-8,8],1):
        base=union(base,box(W-2,uy+dx-4.4,0,11.4,8.8,3),cut(cyl(211,uy+dx,2.8,4.4,uz-7.8),cyl(211,uy+dx,9,1.15,uz)))
        screw('USB_SCREW_'+str(i),211,uy+dx,uz+5,20,'USB',target='BASE',omit=['HOOD'])
        pilot_floor=uz+5-20-f['pilot_tip_clearance_mm']
        final_base_pilots.append(cyl(211,uy+dx,pilot_floor,1.15,uz-5-pilot_floor+.1))
    # Final cuts cannot be filled by a later union with the original floor.
    base=cut(base,*final_base_pilots)
    usb['clamp']=dict(lower_carrier='USB_CLAMP_1',upper_carrier='USB_CLAMP_2',jaws=jaw_ids,closing_gap_mm=fix['usb_closing_gap_mm'],nominal_cable_mm=fix['usb_cable_nominal_mm'],nominal_bore_mm=fix['usb_jaw_bore_mm'],virtual_cable_origin=[203,uy,uz],virtual_cable_length_mm=12,actual_grip='OPEN')
    parts['BASE']=base;parts['BASE'].label='BASE';parts['BASE'].color=b.Color('#71848e')
    coupon=box(230,0,0,36,22,12)
    for i,d in enumerate([2.2,2.3,2.4]):
        coupon=cut(coupon,cyl(236+12*i,10,3,d/2,9.2))
        # One/two/three edge notches identify the pilot without ink.
        for j in range(i+1):coupon=cut(coupon,box(233+12*i+j*2,-.1,0,1,2,12.2))
    add('SCREW_COUPON',coupon,'print','TEST','#879b8f',print_rotation=[0,0,0],assembled=False,pilots_mm=[2.2,2.3,2.4],purpose='First qualify chosenST2.9 screw and PETG torque/repeat assembly; not a box component')
    # Free-space paths are checks, not conductor geometry.
    for m in meta:
        bb=parts[m['id']].bounding_box();m['bounds']=[list(bb.min),list(bb.max)]
    return parts,dict(revision=p['revision'],parameters=p,parts=meta,fasteners=fasteners,hood_axes=axes,outside_mount_axes=outside_axes,front_axes=front_axes,usb=usb,cages=cages,board_mounts=bm,body_mm=p['body'],no_3d_wires=True,source_references=dict(front='inputs/original-front-open.jpg',shell='inputs/original-shell.png',dimensions='inputs/inventory.json'),assembly_order=['Populate and electrically inspect unchanged main board off carrier','Fit both empty cage bases with keepers removed; mount adapters to original front','Fit both mated connector pairs, inserts and directly screwed caps before fitting original front','Fit unchanged converter, main board and original front adapters','Seat replaceable USB jaws; tighten through the cable load path, verify real strain relief','Lower hood and secure from below; remove original front before servicing internal6V keeper'],physical_fit=False)

def assembly(parts,c,view):
    groups={}
    for m in c['parts']:
        if m.get('assembled')is False:continue
        s=copy.copy(parts[m['id']]);owner=m['owner'];d=[0,0,0]
        if view=='open'and owner=='HOOD':d=[0,0,90]
        if view=='exploded':d=[0,0,{'HOOD':150,'FRONT':90,'BOARD':45,'BUCK':35,'USB':65,'POWER':25,'POWER_KEEPER':55}.get(owner,0)]
        s=s.moved(b.Location(d));s.label=m['id'];groups.setdefault(owner,[]).append(s)
    return b.Compound(label='VENTERIOR_V15_'+view,children=[b.Compound(label=k,children=v)for k,v in groups.items()])
