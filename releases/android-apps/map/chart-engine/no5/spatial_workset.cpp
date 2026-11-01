#include "spatial_workset.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <utility>

namespace no5 {
namespace {
// Exactly ObjectRenderCheckPos's inclusive latitude and 0/+360/-360 longitude
// tests. Do not substitute LLBBox::IntersectOut: it has different epsilon rules.
bool overlaps(const SpatialBounds& view,const SpatialBounds& object){
 if(view.maxLat<object.minLat||view.minLat>object.maxLat)return false;
 return (view.maxLon>=object.minLon&&view.minLon<=object.maxLon)||
        (view.maxLon>=object.minLon+360&&view.minLon<=object.maxLon+360)||
        (view.maxLon>=object.minLon-360&&view.minLon<=object.maxLon-360);
}
bool covers(const SpatialBounds& view,const SpatialBounds& all){
 if(view.minLat>all.minLat||view.maxLat<all.maxLat)return false;
 return (view.minLon<=all.minLon&&view.maxLon>=all.maxLon)||
        (view.minLon<=all.minLon+360&&view.maxLon>=all.maxLon+360)||
        (view.minLon<=all.minLon-360&&view.maxLon>=all.maxLon-360);
}
SpatialBounds unite(const SpatialBounds& a,const SpatialBounds& b){
 return SpatialBounds::rectangle(std::min(a.minLat,b.minLat),std::min(a.minLon,b.minLon),
                                 std::max(a.maxLat,b.maxLat),std::max(a.maxLon,b.maxLon));
}
double centre(const SpatialBounds& b,bool longitude){
 // Half before addition also handles finite extrema near DBL_MAX.
 return longitude?b.minLon*.5+b.maxLon*.5:b.minLat*.5+b.maxLat*.5;
}
}

bool SpatialBounds::usable()const{
 return known&&std::isfinite(minLat)&&std::isfinite(minLon)&&
        std::isfinite(maxLat)&&std::isfinite(maxLon)&&minLat<=maxLat&&minLon<=maxLon;
}

void SpatialWorkset::rebuild(Key key,std::vector<SpatialRecord> records){
 // Publish the new index only after construction succeeds. A rejected packet
 // must not silently pair old ordinals with a new key.
 SpatialWorkset next;next.key_=std::move(key);
 next.entries.reserve(records.size());next.ordered.reserve(records.size());
 for(const auto& record:records){
  next.ordered.push_back(record.ordinal);
  if(record.canCull&&record.bounds.usable())next.entries.push_back({record.ordinal,record.bounds});
  else next.fallback.push_back(record.ordinal);
 }
 std::sort(next.ordered.begin(),next.ordered.end());
 if(std::adjacent_find(next.ordered.begin(),next.ordered.end())!=next.ordered.end())
  throw std::invalid_argument("Spatial workset requires unique original ordinals");
 std::sort(next.fallback.begin(),next.fallback.end());
 next.nodes.reserve(next.entries.size());
 if(!next.entries.empty())next.buildNode(0,next.entries.size());
 next.ready=true;*this=std::move(next);
}

std::size_t SpatialWorkset::buildNode(std::size_t begin,std::size_t end){
 const auto index=nodes.size();nodes.push_back({});
 auto bounds=entries[begin].bounds;
 for(auto i=begin+1;i<end;i++)bounds=unite(bounds,entries[i].bounds);
 nodes[index].bounds=bounds;nodes[index].begin=begin;nodes[index].end=end;
 if(end-begin<=8){nodes[index].leaf=true;return index;}
 const bool longitude=(bounds.maxLon-bounds.minLon)>=(bounds.maxLat-bounds.minLat);
 const auto middle=begin+(end-begin)/2;
 std::nth_element(entries.begin()+begin,entries.begin()+middle,entries.begin()+end,
  [longitude](const Entry& a,const Entry& b){
   const auto ac=centre(a.bounds,longitude),bc=centre(b.bounds,longitude);
   return ac<bc||(ac==bc&&a.ordinal<b.ordinal);
  });
 // Reacquire the node by index after recursion can reallocate its vector.
 const auto left=buildNode(begin,middle),right=buildNode(middle,end);
 nodes[index].left=left;nodes[index].right=right;return index;
}

void SpatialWorkset::visit(std::size_t at,const SpatialBounds& bounds,
                          std::vector<std::size_t>& result,SpatialQueryStats& stats)const{
 const auto& node=nodes[at];stats.nodesVisited++;
 if(!overlaps(bounds,node.bounds))return;
 if(node.leaf){
  for(auto i=node.begin;i<node.end;i++){
   stats.entryBoundsTested++;
   if(overlaps(bounds,entries[i].bounds))result.push_back(entries[i].ordinal);
  }
 }else{
  visit(node.left,bounds,result,stats);visit(node.right,bounds,result,stats);
 }
}

bool SpatialWorkset::query(const Key& key,const SpatialBounds& bounds,
                          std::vector<std::size_t>& result,SpatialQueryStats& stats)const{
 result.clear();stats={};
 if(!matches(key)){stats.revisionMismatch=true;return false;}
 stats.inputRecords=ordered.size();stats.indexedRecords=entries.size();stats.fallbackRecords=fallback.size();
 if(!bounds.usable()){
  stats.queryFallback=true;result.assign(ordered.begin(),ordered.end());
 }else if(!nodes.empty()&&covers(bounds,nodes.front().bounds)){
  // A full overview cannot reject any member of the root hull. Retain the
  // existing order directly rather than visit/sort the complete dataset.
  stats.nodesVisited=1;result.assign(ordered.begin(),ordered.end());
 }else{
  result.insert(result.end(),fallback.begin(),fallback.end());
  if(!nodes.empty())visit(0,bounds,result,stats);
  // Spatial traversal must never become painter order, even across equal
  // priorities, overlapping areas, cells or unknown-support fallbacks.
  if(result.size()==ordered.size())result.assign(ordered.begin(),ordered.end());
  else std::sort(result.begin(),result.end());
 }
 stats.returnedRecords=result.size();stats.rejectedRecords=ordered.size()-result.size();return true;
}

void SpatialWorkset::clear(){
 ready=false;key_={};entries.clear();nodes.clear();ordered.clear();fallback.clear();
}

std::size_t SpatialWorkset::storageBytes()const{
 return sizeof(*this)+entries.capacity()*sizeof(Entry)+nodes.capacity()*sizeof(Node)+
        (ordered.capacity()+fallback.capacity())*sizeof(std::size_t)+
        key_.selection.capacity()*sizeof(std::uintptr_t);
}
}
