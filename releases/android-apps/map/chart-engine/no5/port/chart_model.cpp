#include "chart_model.hpp"
// The facade owns the same geometry allocations as s57chart's upstream dtor.
s57chart::~s57chart(){
 free(m_line_vertex_buffer);
 for(auto p:m_pcs_vector)delete p;
 for(auto p:m_pve_vector)delete p;
 for(auto [id,p]:m_ve_hash)if(p){free(p->pPoints);delete p;}
 for(auto [id,p]:m_vc_hash)if(p){free(p->pPoint);delete p;}
}
