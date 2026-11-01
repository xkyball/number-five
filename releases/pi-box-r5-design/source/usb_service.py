"""Continuous planning-case USB paths: library corridor, analytic bends, length root solve."""
import math,sys
import numpy as np
from scipy.optimize import brentq
from common import ROOT,read,save,sha
from l4_layout import route

def curve(fn,n=101):return np.asarray([fn(t)for t in np.linspace(0,1,n)],float)
def line(a,z):return np.asarray([a,z],float)
def join(parts):return np.vstack([parts[0],*[p[1:]for p in parts[1:]]])
def length(points):return float(np.linalg.norm(np.diff(points,axis=0),axis=1).sum())
def sm(t):return 3*t*t-2*t*t*t
def s_curve(start,u,v,delta,r):
 a=math.acos(1-delta/(2*r));u=np.asarray(u);v=np.asarray(v);s=np.asarray(start);mid=s+r*math.sin(a)*u+r*(1-math.cos(a))*v
 first=curve(lambda t:s+r*math.sin(a*t)*u+r*(1-math.cos(a*t))*v)
 second=curve(lambda t:mid+r*(math.sin(a)-math.sin(a*(1-t)))*u+r*(math.cos(a*(1-t))-math.cos(a))*v)
 return join([first,second])
def host(cx):
 r=22;delta=8;a=math.acos(1-delta/(2*r));advance=2*r*math.sin(a);y0=55-advance-r;rs=(54-y0)/2;yc=(54+y0)/2
 return join([
  curve(lambda t:[168+22*math.sin(math.pi*t),76+22*math.cos(math.pi*t),77],201),
  line([168,54,77],[cx,54,77]),
  curve(lambda t:[cx-rs*math.sin(math.pi*t),yc+rs*math.cos(math.pi*t),77+5*sm(t)],251),
  line([cx,y0,82],[162,y0,82]),
  curve(lambda t:[162+r*math.sin(t*math.pi/2),y0+r*(1-math.cos(t*math.pi/2)),82]),
  s_curve([184,y0+r,82],[0,1,0],[0,0,1],8,22)])
def can(radius):
 center=np.array([138.,42.7]);s=np.array([137,116,77]);v=np.array([0,-2**-.5,2**-.5]);r=30
 p1=curve(lambda t:s+r*math.sin(t*math.pi/2)*np.array([1,0,0])+r*(1-math.cos(t*math.pi/2))*v)
 p2=curve(lambda t:p1[-1]+np.array([0,-r*(math.sin(math.pi/4)-math.sin(math.pi/4*(1-t))),r*(math.cos(math.pi/4*(1-t))-math.cos(math.pi/4))]))
 dx=center[0]+radius-p2[-1,0];assert dx>0
 p3=s_curve(p2[-1],[0,-1,0],[1,0,0],dx,30);assert p3[-1,1]>=center[1]
 p4=line(p3[-1],[center[0]+radius,center[1],107]);turns=2.75
 p5=curve(lambda t:[center[0]+radius*math.cos(turns*2*math.pi*t),center[1]-radius*math.sin(turns*2*math.pi*t),107+23.7*sm(t)],1501)
 a=p5[-1];p6=curve(lambda t:a+np.array([30*math.sin(t*math.pi/2),30*(1-math.cos(t*math.pi/2)),0]));p7=line(p6[-1],[p6[-1,0],143,130.7])
 p8=curve(lambda t:p7[-1]+np.array([-30*(1-math.cos(t*math.pi/2)),30*math.sin(t*math.pi/2),0]));p9=line(p8[-1],[98,173,130.7])
 p10=curve(lambda t:[98-30*math.sin(t*math.pi),173,100.7+30*math.cos(t*math.pi)],301)
 return join([p1,p2,p3,p4,p5,p6,p7,p8,p9,p10])
def min_radius(points):
 # Circumradius on consecutive sampled points; skip zero/straight and tiny duplicates.
 a=points[1:-1]-points[:-2];z=points[2:]-points[1:-1];aa=np.linalg.norm(a,axis=1);zz=np.linalg.norm(z,axis=1);cross=np.linalg.norm(np.cross(a,z),axis=1);keep=(aa>1e-6)&(zz>1e-6)&(cross>1e-8);rr=aa[keep]*zz[keep]*np.linalg.norm(a[keep]+z[keep],axis=1)/(2*cross[keep]);return float(rr.min())
def main():
 cx=brentq(lambda x:length(host(x))-270,120,150,xtol=1e-9);hp=host(cx);rad=brentq(lambda x:length(can(x))-1000,35,37,xtol=1e-9);cp=can(rad)
 assert np.linalg.norm(hp[0]-[168,98,77])<1e-7 and np.linalg.norm(hp[-1]-[184,55,90])<1e-7
 assert np.linalg.norm(cp[0]-[137,116,77])<1e-7 and np.linalg.norm(cp[-1]-[98,173,70.7])<1e-7
 hr=min_radius(hp);cr=min_radius(cp);assert hr>21.9 and cr>29.9,(hr,cr)
 # libavoid retains an orthogonal corridor record; analytic arcs round that corridor.
 corridor=route(dict(links=[dict(id='HOST_1',start=[168,98],end=[190,98]),dict(id='HOST_2',start=[190,98],end=[190,54]),dict(id='HOST_3',start=[190,54],end=[cx,54]),dict(id='CAN_APPROACH',start=[167,73.574],end=[138+rad,42.7])],buffer=0))
 result=dict(revision='PI-2L-5',status='PASS_CONTINUOUS_REFERENCE_CASES_COLLISION_CHECK_PENDING',source_design_sha256=sha(ROOT/'design.json'),libraries=['libavoid-js0.5.0-beta.5','SciPy brentq and numerical path geometry'],corridor=corridor,paths=[dict(id='USB_HOST',points_mm=hp.tolist(),length_mm=length(hp),nominal_length_mm=270,diameter_mm=4.5,minimum_planned_bend_radius_mm=22,numerical_minimum_radius_mm=hr,service_bow_center_x=cx,source='270mm retained reference; diameter/R22 planning assumptions',real_qualification='OPEN_SUPPLIED_DIAMETER_BEND_AND_PORT_DATUM'),dict(id='USB_CAN',points_mm=cp.tolist(),length_mm=length(cp),nominal_length_mm=1000,diameter_mm=5,minimum_planned_bend_radius_mm=30,numerical_minimum_radius_mm=cr,coil_radius_mm=rad,coil_center_xy=[138,42.7],coil_turns=2.75,coil_z=[107,130.7],source='Conditional1m capacity case; actual supplied length not established. A shorter cable needs fewer storage turns. This is not a claim that the owned cable measures1m.',real_qualification='OPEN_SUPPLIED_LENGTH_DIAMETER_BEND_AND_PORT_DATUM')],no_cut=True,case_excluded=True)
 save(ROOT/'delivery/daten/usb-service.json',result);print('USB LENGTH/BENDS',round(cx,3),round(rad,3),[(p['id'],round(p['length_mm'],4),round(p['numerical_minimum_radius_mm'],3))for p in result['paths']],flush=True)
if __name__=='__main__':main()
