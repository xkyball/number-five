"""Pure exact no-ink owner certificate; no source files or native libraries.

Only complete ALL-context AC/AP/simple-LS Kind3 owners qualify. A stricter
64-sourcepixel geometry exclusion protects the32pixel query/render halo.
"""
from dataclasses import dataclass
import re
from .locality_parts import projection_safety

ROOT=2147483649
TOKEN=re.compile(rb'(?:AC\([A-Z0-9_]+\)|AP\([A-Z0-9_]+\)|LS\((?:SOLD|DASH),[12],[A-Z0-9_]+\))')
USAGES=frozenset((8,9,20,21,24,25))

def eligible(kind,programmes):
 if kind!=3 or not programmes:return False
 for program in programmes:
  tokens=program.split(b';')
  if tokens and not tokens[-1]:tokens.pop()
  if not tokens or any(TOKEN.fullmatch(token)is None for token in tokens):return False
 return True

def orient(a,b,c):return(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def contact(a,b,c,d):
 if max(a[0],b[0])<min(c[0],d[0])or max(c[0],d[0])<min(a[0],b[0])or max(a[1],b[1])<min(c[1],d[1])or max(c[1],d[1])<min(a[1],b[1]):return False
 return orient(a,b,c)*orient(a,b,d)<=0 and orient(c,d,a)*orient(c,d,b)<=0

@dataclass(frozen=True)
class Domain:
 denominator:int
 xmin:int
 ymin:int
 xmax:int
 ymax:int
 @classmethod
 def source(cls,tile,guard_pixels):
  level,x,y=tile
  if not(0<=level<=15 and 0<=x<1<<level and 0<=y<1<<level):raise ValueError('ORIGINAL_NATIVE_SOURCE_LEVEL_0_TO_15')
  den=256*(1<<level)
  return cls(den,(256*x-guard_pixels)*ROOT,(256*y-guard_pixels)*ROOT,(256*(x+1)+guard_pixels)*ROOT,(256*(y+1)+guard_pixels)*ROOT)
 def serial(self):return dict(denominator=self.denominator,xmin=self.xmin,ymin=self.ymin,xmax=self.xmax,ymax=self.ymax)
 def hits(self,a,b):
  a=(a[0]*self.denominator,a[1]*self.denominator);b=(b[0]*self.denominator,b[1]*self.denominator)
  if any(self.xmin<=q[0]<=self.xmax and self.ymin<=q[1]<=self.ymax for q in(a,b)):return True
  r=[(self.xmin,self.ymin),(self.xmax,self.ymin),(self.xmax,self.ymax),(self.xmin,self.ymax)]
  return any(contact(a,b,c,d)for c,d in zip(r,r[1:]+r[:1]))
 def winding(self,points):
  # Exact rational centre scaled by 2*denominator.
  px,py=self.xmin+self.xmax,self.ymin+self.ymax;den=2*self.denominator;w=0
  for a,b in zip(points,points[1:]):
   a=(a[0]*den,a[1]*den);b=(b[0]*den,b[1]*den);cross=orient(a,b,(px,py))
   if a[1]<=py<b[1]and cross>0:w+=1
   if b[1]<=py<a[1]and cross<0:w-=1
  return w

def certify(kind,programmes,parts,tile,guard_pixels=64):
 if not eligible(kind,programmes)or not parts:return None
 if guard_pixels<64:raise ValueError('GEOMETRY64_GUARD_PROTECTS_QUERY32_AND_ROUNDING')
 domain=Domain.source(tile,guard_pixels);rows=[]
 for part in parts:
  points=part['points']
  if part['rootFlag']!=1 or part['usage']not in USAGES or len(points)<4 or points[0]!=points[-1]:return None
  if any(not(0<=x<1<<32 and 0<=y<1<<32)for x,y in points):return None
  if any(domain.hits(a,b)for a,b in zip(points,points[1:]))or domain.winding(points)!=0:return None
  safety=projection_safety(points,tile,max_level=29)
  if safety is None:return None
  rows.append(dict(nativeProjectionSafety=safety,partOrdinal=part['partOrdinal'],usage=part['usage'],points=len(points),allSegmentsDisjointFromClosedGuard=True,interiorWinding=0))
 # Root U32/Q lies in[0,2). Original F64 transforms followed by F32/Qt4
 # rounding have a conservative axis error bound 2**max(L-12,0)+8 pixels.
 # Before F32, |pixel| < 2**(L+9). The bound reserves four F32 ULPs
 # (including the Qt negative branch's two rounded arithmetic operations),
 # eight integer pixels, and ample margin for the preceding F64 operations.
 # The64-versus32sourcepixel gap is at least32*2**(L-sourceL) pixels;
 # the simple width1/2LS support (including AA) is bounded by3pixels.
 # This keeps every edge and winding unchanged throughout the inner32halo.
 levels=[]
 for requested in range(tile[0],30):
  error=(1<<max(requested-12,0))+8;gap=(guard_pixels-32)*(1<<(requested-tile[0]));support=3
  if error+support>=gap:return None
  levels.append(dict(level=requested,conservativeAxisProjectionQtErrorPixels=error,simpleStrokeSupportPixels=support,protectedHaloGapPixels=gap))
 return dict(domain=domain.serial(),geometryGuardSourcePixels=guard_pixels,protectedQueryHaloSourcePixels=32,
             sourcePixelBasisLevel=tile[0],parts=rows,projectionErrorBounds=levels,
             conclusion='NO_FILL_LINE_OR_QUERY_GEOMETRY_IN_PROTECTED_SOURCE_DOMAIN')
