from kicad_support import q,fmt,prop,effect

def symbol(c,typ):
 names=list(c['pins']);height=max(10.16,(len(names)+1)//2*3.81);loc={};graphic=[];lengths={}
 def line(points,width=.254,fill='none'):
  return '(polyline (pts '+''.join(f'(xy {fmt(x)} {fmt(y)})'for x,y in points)+f') (stroke (width {width}) (type default)) (fill (type {fill})))'
 kind=c['kind']
 if kind in {'resistor','capacitor','diode','tvs','fuse'}:
  loc={names[0]:(-10.16,0,0),names[1]:(10.16,0,180)}
  if kind=='resistor':
   graphic=['(rectangle (start -5.08 1.27) (end 5.08 -1.27) (stroke (width .254) (type default)) (fill (type none)))'];ends=[-5.08,5.08]
  elif kind=='capacitor':
   graphic=[line([(-1.27,-3.81),(-1.27,3.81)]),line([(1.27,-3.81),(1.27,3.81)])];ends=[-1.27,1.27]
   if c.get('polarized'):graphic.append(f'(text "+" (at -3.81 3.81 0) {effect(1.2)})')
  elif kind in {'diode','tvs'}:
   graphic=[line([(-3.81,-2.54),(-3.81,2.54),(3.81,0),(-3.81,-2.54)]),line([(3.81,-2.54),(3.81,2.54)])];ends=[-3.81,3.81]
   if kind=='tvs':graphic.append(line([(2.54,-3.81),(3.81,-2.54),(3.81,2.54),(5.08,3.81)]))
  else:
   graphic=['(rectangle (start -5.08 1.27) (end 5.08 -1.27) (stroke (width .254) (type default)) (fill (type none)))',line([(-5.08,0),(5.08,0)])];ends=[-5.08,5.08]
  graphic.extend([line([(-5.08,0),(ends[0],0)]),line([(ends[1],0),(5.08,0)])])
 elif kind=='npn':
  loc={'B':(-10.16,0,0),'C':(7.62,7.62,270),'E':(7.62,-7.62,90)};lengths={'C':2.54,'E':2.54}
  graphic=[line([(-5.08,0),(-1.27,0)]),line([(-1.27,-3.81),(-1.27,3.81)],.508),line([(-1.27,2.54),(7.62,5.08)]),line([(-1.27,-2.54),(7.62,-5.08)]),line([(2.5,-3.6),(5.8,-5.6),(5.5,-2.8),(2.5,-3.6)],.254,'outline')]
 elif kind=='dual_opamp':
  height=45.72;loc={'3':(-15.24,12.7,0),'2':(-15.24,7.62,0),'1':(15.24,10.16,180),'5':(-15.24,-7.62,0),'6':(-15.24,-12.7,0),'7':(15.24,-10.16,180),'8':(0,22.86,270),'4':(0,-22.86,90)}
  for y in [10.16,-10.16]:
   graphic.append(line([(-10.16,y+7.62),(-10.16,y-7.62),(10.16,y),(-10.16,y+7.62)]))
   for a,b in [(y+2.54,'+'),(y-2.54,'−')]:graphic.append(f'(text {q(b)} (at -7.62 {fmt(a)} 0) {effect(1.2)})')
 else:
  for j,p in enumerate(names):
   left=j<(len(names)+1)//2;row=j if left else j-(len(names)+1)//2
   loc[p]=(-17.78 if left else 17.78,height/2-3.81-row*3.81,0 if left else 180)
  graphic=[f'(rectangle (start -12.7 {height/2}) (end 12.7 {-height/2}) (stroke (width .25) (type default)) (fill (type background)))']
 ps=[]
 for p,(x,y,a) in loc.items():
  electrical='passive'
  if kind=='dual_opamp':electrical='output'if p in {'1','7'}else'power_in'if p in {'4','8'}else'input'
  if kind=='npn':electrical='passive'
  ps.append(f'(pin {electrical} line (at {fmt(x)} {fmt(y)} {a}) (length {lengths.get(p,5.08)}) (name {q(c.get("pin_names",{}).get(p,p))} {effect(.8)}) (number {q(p)} {effect(.8)}))')
 return f'(symbol "Yamaha:{typ}" (pin_names (offset .7)) (in_bom yes) (on_board yes) {prop("Reference",c["ref"],0,height/2+3)} {prop("Value",c["value"],0,height/2+6)} (symbol "{typ}_0_1" '+''.join(graphic)+f') (symbol "{typ}_1_1" '+''.join(ps)+'))',height
