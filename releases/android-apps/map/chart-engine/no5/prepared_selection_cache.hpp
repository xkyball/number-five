#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <list>
#include <utility>
#include <vector>

namespace no5 {
// A packet contains only references to the already loaded, owner-thread S52
// geometry. It does not cache visibility, conditional rules or date eligibility.
// Those still run for each view. No camera revision can cancel this data work.
template<class FeatureType> class PreparedSelectionCache {
public:
 struct Key {
  uint64_t geometry=0,presentation=0;
  std::vector<std::uintptr_t> cells;
  bool operator==(const Key& other)const{
   return geometry==other.geometry&&presentation==other.presentation&&cells==other.cells;
  }
 };
 static constexpr size_t DefaultBudgetBytes=1024*1024;
 static constexpr size_t DefaultPacketLimit=4;
 explicit PreparedSelectionCache(size_t budget=DefaultBudgetBytes,size_t limit=DefaultPacketLimit)
  :budgetBytes(budget),packetLimit(limit){}
 const std::vector<FeatureType*>* find(const Key& key){
  const auto found=std::find_if(packets.begin(),packets.end(),[&](const Entry& entry){return entry.key==key;});
  if(found==packets.end())return nullptr;
  packets.splice(packets.end(),packets,found);
  return &packets.back().features;
 }
 // On rejection, the caller keeps its single active scratch packet. Returning
 // a pointer avoids copying a retained packet into another per-frame vector.
 const std::vector<FeatureType*>* insert(Key key,std::vector<FeatureType*>&& features){
  const auto cost=storageBytes(key,features);
  if(packetLimit==0||cost>budgetBytes)return nullptr;
  for(auto it=packets.begin();it!=packets.end();){
   if(it->key==key){usedBytes-=it->bytes;it=packets.erase(it);}else ++it;
  }
  while(!packets.empty()&&(packets.size()>=packetLimit||usedBytes>budgetBytes-cost)){
   usedBytes-=packets.front().bytes;packets.pop_front();
  }
  packets.push_back({std::move(key),std::move(features),cost});usedBytes+=cost;
  return &packets.back().features;
 }
 void clear(){packets.clear();usedBytes=0;}
 size_t bytes()const{return usedBytes;}
 size_t size()const{return packets.size();}
 size_t budget()const{return budgetBytes;}
 static size_t storageBytes(const Key& key,const std::vector<FeatureType*>& features){
  // Include allocated capacity, packet metadata and the list's two links.
  return sizeof(Entry)+2*sizeof(void*)+key.cells.capacity()*sizeof(std::uintptr_t)+
    features.capacity()*sizeof(FeatureType*);
 }
private:
 struct Entry {Key key;std::vector<FeatureType*> features;size_t bytes=0;};
 size_t budgetBytes,packetLimit,usedBytes=0;
 std::list<Entry> packets;
};
}
