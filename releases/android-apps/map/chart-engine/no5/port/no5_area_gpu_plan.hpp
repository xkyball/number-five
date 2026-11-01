#pragma once
#include "no5_triangle_batch.hpp"
#include <cstddef>
#include <cstdint>
#include <vector>

namespace no5 {
struct AreaGpuPrimitive {
  unsigned mode=0;
  int first=0,count=0;
  bool operator==(const AreaGpuPrimitive& other)const noexcept {
    return mode==other.mode&&first==other.first&&count==other.count;
  }
};
struct AreaGpuSpan {std::size_t first=0,count=0;};
struct AreaGpuPlan {
  std::vector<std::uint16_t> indices;
  std::vector<AreaGpuSpan> spans;
};
// OSENC allocates one extra float vertex after the referenced primitive data.
// That allocation capacity is not an extra drawable vertex. Retain/upload
// only the validated contiguous primitive prefix, leaving CPU padding intact.
inline bool areaGpuUsedVertexBytes(std::size_t vertices,std::size_t allocationBytes,
                                   std::size_t& usedBytes)noexcept {
  constexpr auto stride=2*sizeof(float);
  if(!vertices||vertices>65535||allocationBytes%stride||
     vertices>allocationBytes/stride)return false;
  usedBytes=vertices*stride;return true;
}
// Expand precisely the existing TriangleBatch order, including strip
// degenerates and the alternating strip winding. No new tessellation occurs.
inline bool buildAreaGpuPlan(std::size_t vertices,
                            const std::vector<AreaGpuPrimitive>& primitives,
                            AreaGpuPlan& result) {
  if(!vertices||vertices>65535||primitives.empty())return false;
  AreaGpuPlan next;
  auto append=[&](const std::uint16_t* data,std::size_t count){
    next.indices.insert(next.indices.end(),data,data+count);
  };
  TriangleBatch<decltype(append)> batch(append);
  for(const auto& primitive:primitives){
    if(primitive.first<0||primitive.count<0||
       static_cast<std::size_t>(primitive.first)>vertices||
       static_cast<std::size_t>(primitive.count)>vertices-primitive.first)return false;
    const auto start=next.indices.size();
    if(!batch.append(primitive.mode,primitive.first,primitive.count))return false;
    batch.flush();
    next.spans.push_back({start,next.indices.size()-start});
  }
  if(next.indices.empty())return false;
  result=std::move(next);return true;
}
}
