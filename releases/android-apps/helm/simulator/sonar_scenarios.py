"""Original deterministic single-beam test scenes. No biological TS or density claim.

Scenario motion is an explicit test transect, separate from HELM and replay speed.
All echoes are generated as ranges/amplitudes before any echogram rendering.
"""
import math

SCENARIOS = ('navigation', 'sand_fish', 'school_edge', 'rock_weed', 'noise_second')

def bottom(name, x, y=0.0):
    if name == 'sand_fish': return 12.0 + .35*math.sin(x*.03)
    if name == 'school_edge': return 10.0 + 6.0*(.5+.5*math.sin(x*.035))
    if name == 'noise_second': return 9.0+1.2*math.sin(x*.04)
    base=14.0+1.3*math.sin(x*.055)
    cell=math.floor(x/18)
    for k in range(cell-1,cell+2):
        rx=k*18+5; ry=1.2*math.sin(k*2.1)
        base-=(1.4+.7*math.cos(k))*math.exp(-.5*((x-rx)/1.5)**2-.5*((y-ry)/2.0)**2)
    return base

def scene(name, elapsed, beam_degrees, targets):
    x=elapsed*1.6  # Deliberate test transect, never a substituted boat position.
    depth=bottom(name,x)
    half=math.radians(beam_degrees*.5)
    fish=[]
    if targets and name in ('sand_fish','school_edge'):
        spacing=18 if name=='sand_fish' else 38
        cell=math.floor(x/spacing)
        for k in range(cell-1,cell+2):
            count=1 if name=='sand_fish' else 14
            for j in range(count):
                tx=k*spacing+7+(j%7-3)*.72+.35*math.sin(elapsed*.25+j)
                ty=.4*math.sin(k+j*1.7) if count==1 else (j//7-.5)*1.05
                z=6.0+.55*math.sin(k) if count==1 else 6.5+.8*math.sin(j*1.9+k)
                lateral=math.hypot(x-tx,ty)
                angle=math.atan2(lateral,z)
                if angle<half*1.8 and z<depth-.8:
                    # Two-way illustrative beam envelope; slant range creates the arch.
                    fish.append((math.hypot(z,lateral),21000*math.exp(-4*(angle/half)**2)))
    footprint=depth*math.tan(half)
    rocks=[]
    if name=='rock_weed':
        for offset in (-.8,-.4,0,.4,.8):
            dx=offset*footprint
            rocks.append((math.hypot(bottom(name,x+dx),dx), .11))
    return depth,fish,rocks,{'scenario':name,'transectEastM':x,'targetCountInBeam':len(fish),
                              'targetSlantRangesM':[p[0] for p in fish]}
