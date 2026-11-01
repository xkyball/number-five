#pragma once
#include "s52plib.h"
#include "mygeom.h"
// Non-UI instance data consumed by the three retained OpenCPN geometry
// selection/association methods. No ChartBase, ChartCanvas or app lifecycle.
class s57chart {
public:
 double ref_lat=0,ref_lon=0;
 bool m_bLinePrioritySet=false;
 size_t m_vbo_byte_length=0;
 float* m_line_vertex_buffer=nullptr;
 int m_LineVBO_name=-1;
 std::unordered_map<unsigned,VE_Element*> m_ve_hash;
 std::unordered_map<unsigned,VC_Element*> m_vc_hash;
 std::vector<connector_segment*> m_pcs_vector;
 std::vector<VE_Element*> m_pve_vector;
 ~s57chart();
 void AssembleLineGeometry();
 void SetLinePriorities();
 ObjRazRules* razRules[PRIO_NUM][5]{};
 std::list<S57Obj*>* GetAssociatedObjects(S57Obj*);
 bool IsPointInObjArea(float,float,float,S57Obj*);
 bool DoesLatLonSelectObject(float,float,float,S57Obj*);
};
