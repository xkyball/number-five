#pragma once
#include "chart.hpp"
#include <set>
#include <sstream>
#include <iomanip>
#include <cstdint>
namespace no5 {
// Loaded COALNE edges already assembled by open(). No land/coverage, rule traversal or file read.
inline std::string outlineGeometry(const No5ChartRenderer& chart) {
 std::ostringstream out;out<<std::setprecision(15)<<"{\"origin\":["<<chart.view.clat<<","<<chart.view.clon<<"],\"coast\":[";
 bool first=true;size_t vertices=0;std::set<std::pair<uintptr_t,uintptr_t>> seen;
 for(const auto& feature:chart.features){if(feature.name!="COALNE")continue;
  auto cell=feature.cell;if(!cell||!feature.object||!cell->m_line_vertex_buffer)continue;
  for(auto segment=feature.object->m_ls_list;segment;segment=segment->next){
   bool edge=segment->ls_type==TYPE_EE||segment->ls_type==TYPE_EE_REV;
   const void* id=edge?static_cast<const void*>(segment->pedge):static_cast<const void*>(segment->pcs);
   if(!id||!seen.insert({reinterpret_cast<uintptr_t>(cell),reinterpret_cast<uintptr_t>(id)}).second)continue;
   size_t count=edge?segment->pedge->nCount:2;size_t offset=edge?segment->pedge->vbo_offset:segment->pcs->vbo_offset;
   if(count<2)continue;
   if(offset%alignof(float)||offset>cell->m_vbo_byte_length||count>(cell->m_vbo_byte_length-offset)/(2*sizeof(float)))throw std::runtime_error("Outline edge exceeds loaded vertex buffer");
   if(count>200000-vertices)throw std::runtime_error("Outline geometry exceeds bounded load-time cache");vertices+=count;
   const float* p=reinterpret_cast<const float*>(reinterpret_cast<const unsigned char*>(cell->m_line_vertex_buffer)+offset);
   if(!first)out<<",";first=false;out<<"[";
   for(size_t i=0;i<count;i++){double lat,lon;fromSM_plib(p[2*i],p[2*i+1],cell->ref_lat,cell->ref_lon,&lat,&lon);if(i)out<<",";out<<"["<<lat<<","<<lon<<"]";}out<<"]";
  }
 }
 out<<"]}";return out.str();
}
}
