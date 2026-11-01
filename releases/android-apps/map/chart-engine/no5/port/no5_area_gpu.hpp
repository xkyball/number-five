#pragma once
#include "no5_area_gpu_plan.hpp"
#include <EGL/egl.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <stdexcept>
#include <string>
#include <unordered_map>

// Include after no5_platform.hpp: all GL operations retain the normal No5
// delegate, profiling and first-presentation error attribution.
namespace no5 {
struct AreaGpuCounters {
  std::uint64_t hits=0,misses=0,vertexUploads=0,indexUploads=0;
  std::uint64_t vertexUploadBytes=0,indexUploadBytes=0,draws=0;
  std::uint64_t fallbacks=0,allocationFailures=0,evictions=0;
  std::uint64_t entries=0,retainedBytes=0,contextForgets=0;
};
namespace area_gpu_detail {
inline constexpr std::size_t MinimumVertices=256;
// An application retention budget, not a GLES or chart-format requirement.
inline constexpr std::size_t ContextBudget=16*1024*1024;
inline constexpr std::size_t DrawCapacity=12288; // Existing TriangleBatch limit.
struct Entry {
  const void* geometry=nullptr;
  const void* vertices=nullptr;
  std::size_t vertexBytes=0,charge=0;
  std::vector<AreaGpuPrimitive> primitives;
  AreaGpuPlan plan;
  std::vector<unsigned char> selected;
  std::vector<std::uint16_t> submitted;
  bool dirty=false;
  GLuint vertexBuffer=0,indexBuffer=0;
  std::uint64_t used=0;
};
struct Context {
  std::unordered_map<const void*,Entry> objects;
  std::size_t bytes=0;
};
inline thread_local std::unordered_map<EGLContext,Context> contexts;
inline thread_local AreaGpuCounters counters;
inline thread_local std::uint64_t clock=0;
inline thread_local bool enabled=[] {
#ifndef __ANDROID__
  const auto* value=std::getenv("NO5_AREA_GPU");
  if(value)return std::string(value)!="0";
#endif
#if defined(NO5_VIEWPORT_REUSE) && NO5_VIEWPORT_REUSE
  return true;
#else
  return false;
#endif
}();
inline void destroy(Context& context,decltype(Context::objects)::iterator it){
  auto& entry=it->second;
  if(entry.vertexBuffer)glDeleteBuffers(1,&entry.vertexBuffer);
  if(entry.indexBuffer)glDeleteBuffers(1,&entry.indexBuffer);
  context.bytes-=entry.charge;counters.retainedBytes-=entry.charge;
  --counters.entries;context.objects.erase(it);
}
inline bool upload(GLenum target,GLuint buffer,const void* bytes,std::size_t count,GLenum usage){
  glBindBuffer(target,buffer);
  glBufferData(target,static_cast<GLsizeiptr>(count),bytes,usage);
  return glGetError()==GL_NO_ERROR;
}
}
inline AreaGpuCounters areaGpuCounters()noexcept{return area_gpu_detail::counters;}
inline void resetAreaGpuCounters()noexcept {
  auto& c=area_gpu_detail::counters;const auto entries=c.entries,bytes=c.retainedBytes;
  c={};c.entries=entries;c.retainedBytes=bytes;
}
inline void setAreaGpuEnabled(bool enabled)noexcept{area_gpu_detail::enabled=enabled;}
inline bool areaGpuEnabled()noexcept{return area_gpu_detail::enabled;}
inline bool hasAreaGpu(const void* owner)noexcept {
  for(const auto& pair:area_gpu_detail::contexts)if(pair.second.objects.count(owner))return true;
  return false;
}
// Intentional in-place ENC edits retain object/buffer addresses. Their owner
// invalidates the CPU-derived caches without needing an EGL context here.
inline void invalidateAreaGpu(const void* owner)noexcept {
  for(auto& pair:area_gpu_detail::contexts){
    auto found=pair.second.objects.find(owner);
    if(found!=pair.second.objects.end())found->second.dirty=true;
  }
}
// The renderer/cell owner releases an object before its CPU geometry can die or
// be reused. Never delete a numeric GL name in a different/current-less context.
inline void releaseAreaGpu(const void* owner){
  const auto current=eglGetCurrentContext();
  for(const auto& pair:area_gpu_detail::contexts)
    if(pair.second.objects.count(owner)&&pair.first!=current)
      throw std::logic_error("Area GPU release requires its current owner context");
  auto found=area_gpu_detail::contexts.find(current);
  if(found!=area_gpu_detail::contexts.end()){
    auto it=found->second.objects.find(owner);
    if(it!=found->second.objects.end())area_gpu_detail::destroy(found->second,it);
  }
}
inline void releaseAreaGpuContext(EGLContext context){
  auto found=area_gpu_detail::contexts.find(context);if(found==area_gpu_detail::contexts.end())return;
  if(context==EGL_NO_CONTEXT||context!=eglGetCurrentContext())
    throw std::logic_error("Area GPU context release requires current owner");
  while(!found->second.objects.empty())area_gpu_detail::destroy(found->second,found->second.objects.begin());
  area_gpu_detail::contexts.erase(found);
}
// EGL owns the lost buffers. Forget CPU names without issuing GL calls.
inline void forgetAreaGpuContext(EGLContext context)noexcept {
  auto found=area_gpu_detail::contexts.find(context);if(found==area_gpu_detail::contexts.end())return;
  for(const auto& pair:found->second.objects){--area_gpu_detail::counters.entries;area_gpu_detail::counters.retainedBytes-=pair.second.charge;}
  area_gpu_detail::contexts.erase(found);++area_gpu_detail::counters.contextForgets;
}

// Returns false without drawing if client arrays must handle this object. CPU
// vertices remain owned by the original PolyTriGroup, unchanged for queries.
// `geometry` plus owner/buffer/topology bind retained tessellation; intentional
// in-place edits must call invalidateAreaGpu before the next submission.
// actual viewport primitive admission is supplied afresh on every call.
inline bool submitAreaGpu(const void* owner,const void* geometry,
                          const void* vertices,std::size_t vertexBytes,
                          const std::vector<AreaGpuPrimitive>& primitives,
                          const std::vector<unsigned char>& selected,GLint position){
  using namespace area_gpu_detail;
  const auto context=eglGetCurrentContext();
  const auto* glContext=currentGlLocationCache();
  if(!enabled||!owner||!geometry||!vertices||vertexBytes%(2*sizeof(float))||
     vertexBytes/(2*sizeof(float))<MinimumVertices||
     vertexBytes/(2*sizeof(float))>65535||position<0||
     primitives.size()!=selected.size()||context==EGL_NO_CONTEXT||!glContext||
     position>=glContext->maxAttributes){
    ++counters.fallbacks;return false;
  }
  auto& cache=contexts[context];auto found=cache.objects.find(owner);
  if(found!=cache.objects.end()&&
     (found->second.dirty||found->second.geometry!=geometry||found->second.vertices!=vertices||
      found->second.vertexBytes!=vertexBytes||found->second.primitives!=primitives)){
    destroy(cache,found);found=cache.objects.end();
  }
  if(found==cache.objects.end()){
    Entry entry;entry.geometry=geometry;entry.vertices=vertices;entry.vertexBytes=vertexBytes;
    entry.primitives=primitives;
    if(!buildAreaGpuPlan(vertexBytes/(2*sizeof(float)),primitives,entry.plan)){
      ++counters.fallbacks;return false;
    }
    // Vertex GPU storage + canonical CPU indices + maximal selected CPU/GPU
    // indices + topology/selection bookkeeping. Capacity is bounded per context.
    entry.charge=vertexBytes+entry.plan.indices.size()*sizeof(std::uint16_t)*3+
                 primitives.size()*(sizeof(AreaGpuPrimitive)+sizeof(AreaGpuSpan)+1);
    if(entry.charge>ContextBudget){++counters.fallbacks;return false;}
    while(cache.bytes>ContextBudget-entry.charge){
      auto victim=std::min_element(cache.objects.begin(),cache.objects.end(),
        [](const auto& a,const auto& b){return a.second.used<b.second.used;});
      if(victim==cache.objects.end()){++counters.fallbacks;return false;}
      destroy(cache,victim);++counters.evictions;
    }
    GLuint ids[2]={};glGenBuffers(2,ids);entry.vertexBuffer=ids[0];entry.indexBuffer=ids[1];
    if(!ids[0]||!ids[1]||!upload(GL_ARRAY_BUFFER,ids[0],vertices,vertexBytes,GL_STATIC_DRAW)){
      if(ids[0])glDeleteBuffers(1,&ids[0]);if(ids[1])glDeleteBuffers(1,&ids[1]);
      glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
      ++counters.allocationFailures;++counters.fallbacks;return false;
    }
    ++counters.misses;++counters.vertexUploads;counters.vertexUploadBytes+=vertexBytes;
    found=cache.objects.emplace(owner,std::move(entry)).first;
    cache.bytes+=found->second.charge;counters.retainedBytes+=found->second.charge;++counters.entries;
  }else ++counters.hits;
  auto& entry=found->second;entry.used=++clock;
  if(entry.selected!=selected){
    std::vector<std::uint16_t> next;next.reserve(entry.plan.indices.size());
    for(std::size_t i=0;i<selected.size();++i)if(selected[i]){
      const auto span=entry.plan.spans[i];
      next.insert(next.end(),entry.plan.indices.begin()+span.first,entry.plan.indices.begin()+span.first+span.count);
    }
    if(!next.empty()&&!upload(GL_ELEMENT_ARRAY_BUFFER,entry.indexBuffer,next.data(),next.size()*sizeof(std::uint16_t),GL_DYNAMIC_DRAW)){
      destroy(cache,found);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
      ++counters.allocationFailures;++counters.fallbacks;return false;
    }
    if(!next.empty()){++counters.indexUploads;counters.indexUploadBytes+=next.size()*sizeof(std::uint16_t);}
    entry.submitted=std::move(next);entry.selected=selected;
  }
  glBindBuffer(GL_ARRAY_BUFFER,entry.vertexBuffer);
  glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,entry.indexBuffer);
  glVertexAttribPointer(static_cast<GLuint>(position),2,GL_FLOAT,GL_FALSE,0,nullptr);
  for(std::size_t first=0;first<entry.submitted.size();first+=DrawCapacity){
    const auto count=std::min(DrawCapacity,entry.submitted.size()-first);
    glDrawElements(GL_TRIANGLES,static_cast<GLsizei>(count),GL_UNSIGNED_SHORT,
                   reinterpret_cast<const void*>(first*sizeof(std::uint16_t)));++counters.draws;
  }
  glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
  return true;
}
}
