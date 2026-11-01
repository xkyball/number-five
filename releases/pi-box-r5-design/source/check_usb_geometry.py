"""Standard OCCT distances on continuous USB planning paths; no wire CAD export."""
import sys,math
import numpy as np
import build123d as b
from common import ROOT,read,save,sha
def main():
 data=read(ROOT/'delivery/daten/usb-service.json');m=read(ROOT/'output/rest-mechanical.json');cache={};tests=0;findings=[];minimum=[]
 for path in data['paths']:
  points=np.asarray(path['points_mm']);r=path['diameter_mm']/2;least=1e9;close=[];portals={'HOST_USB_PLUG':points[0],'HUB':points[-1]}if path['id']=='USB_HOST'else{'CAN_PI_USB_PLUG':points[0],'CAN_USB_PLUG':points[-1]}
  for row in m['rows']:
   if row['level']=='external':continue
   a,z=np.asarray(row['bounds_mm']);mask=np.all((np.maximum(points[:-1],points[1:])+r+.01>=a)&(np.minimum(points[:-1],points[1:])-r-.01<=z),axis=1);indices=np.flatnonzero(mask)
   if not len(indices):continue
   id=row['id'];sh=cache.setdefault(id,b.import_brep(row['brep']['path']))
   for i in indices:
    aa,zz=points[i:i+2]
    if np.linalg.norm(zz-aa)<1e-7:continue
    # Electrical cable portals deliberately enter their original housings at the endpoint.
    if id in portals and min(np.linalg.norm(aa-portals[id]),np.linalg.norm(zz-portals[id]))<5:continue
    edge=b.Edge.make_line(tuple(aa),tuple(zz));dist=edge.distance_to(sh);tests+=1;least=min(least,dist-r)
    if dist<r-.025:findings.append(dict(cable=path['id'],part=id,segment=int(i),clearance_mm=round(dist-r,5),from_mm=aa.tolist(),to_mm=zz.tolist()))
   if tests%200==0:print(path['id'],id,tests,'edge/solid tests',flush=True)
  minimum.append(dict(cable=path['id'],minimum_sampled_segment_surface_clearance_mm=least))
 # A simple case must detect a displaced cable through a known solid.
 block=b.Solid.make_box(5,5,5,b.Plane(origin=(0,0,0)));bad=b.Edge.make_line((-1,2,2),(6,2,2));assert bad.distance_to(block)<1e-8
 out=dict(revision='PI-2L-5',status='PASS_REFERENCE_CASE_USB_BODY_CLEARANCE'if not findings else'FAIL',source_usb_sha256=sha(ROOT/'delivery/daten/usb-service.json'),mechanical_sha256=sha(ROOT/'output/rest-mechanical.json'),edge_solid_tests=tests,collisions=findings,minimum_clearance=minimum,positive_contact_tolerance_mm=.025,scope='OCCT distance from fine polygonal approximations of analytic curved centrelines to actual BReps; insulated radii2.25/2.5. Original housing portals within5mm excluded. Intended seating on the service rail allowed with0.025mm numerical tolerance. Family model and cable diameter/bend limits remain provisional.',physical_acceptance='OPEN_REAL_LENGTH_DIAMETER_MINIMUM_BEND_PORTS_AND_TIE_PULL',negative_control_detected=True)
 save(ROOT/'output/usb-geometry-check.json',out);print(out['status'],tests,'tests',len(findings),'hits',findings[:6],flush=True)
if __name__=='__main__':main()
