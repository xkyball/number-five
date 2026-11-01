#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace no5 {

// Geographic bounds in the same unwrapped longitude representation as S52's
// BBObj/GetBBox. Unknown bounds are deliberately not made into an empty box.
struct SpatialBounds {
 double minLat=0,minLon=0,maxLat=0,maxLon=0;
 bool known=false;
 static SpatialBounds rectangle(double south,double west,double north,double east){
  return {south,west,north,east,true};
 }
 template<class Box> static SpatialBounds fromBox(const Box& box){
  // Invalid LLBBox coordinates may be uninitialized; do not read them.
  if(!box.GetValid())return {};
  return rectangle(box.GetMinLat(),box.GetMinLon(),box.GetMaxLat(),box.GetMaxLon());
 }
 bool usable()const;
};

struct SpatialRecord {
 // Position in the exact original stable portrayal packet, never a feature UID.
 std::size_t ordinal=0;
 SpatialBounds bounds;
 // True only when the particular pass already rejects this same geographic
 // box before any rule/decoration side effects. Unknown symbol/text/light or
 // part-support contracts must leave this false, even with finite geometry.
 bool canCull=false;
};

struct SpatialQueryStats {
 std::size_t inputRecords=0,indexedRecords=0,fallbackRecords=0;
 std::size_t nodesVisited=0,entryBoundsTested=0;
 std::size_t returnedRecords=0,rejectedRecords=0;
 bool queryFallback=false,revisionMismatch=false;
};

// Immutable data/order index, not a visibility or render-result cache. It owns
// bounds and ordinals only, and does not retain S57Obj/Feature addresses.
class SpatialWorkset {
public:
 struct Key {
  std::uint64_t geometry=0,presentation=0;
  // Exact selected-cell/order identity, not a lossy fingerprint. The caller
  // advances geometry on residency/data replacement and presentation on order
  // changes, matching the packet from which ordinals were assigned.
  std::vector<std::uintptr_t> selection;
  bool operator==(const Key& other)const{
   return geometry==other.geometry&&presentation==other.presentation&&selection==other.selection;
  }
 };
 void rebuild(Key key,std::vector<SpatialRecord> records);
 void clear();
 bool matches(const Key& key)const{return ready&&key_==key;}
 // Each camera is queried afresh. False means stale data/order: caller must
 // use its CURRENT full eligible packet or rebuild, never treat it as empty.
 // On success, result is in exact increasing original ordinal order.
 bool query(const Key&,const SpatialBounds&,std::vector<std::size_t>& result,SpatialQueryStats&)const;
 std::size_t size()const{return ordered.size();}
 std::size_t indexedSize()const{return entries.size();}
 std::size_t storageBytes()const;
private:
 struct Entry {std::size_t ordinal;SpatialBounds bounds;};
 struct Node {
  SpatialBounds bounds;
  std::size_t begin=0,end=0,left=0,right=0;
  bool leaf=false;
 };
 Key key_;
 bool ready=false;
 std::vector<Entry> entries;
 std::vector<Node> nodes;
 std::vector<std::size_t> ordered,fallback;
 std::size_t buildNode(std::size_t begin,std::size_t end);
 void visit(std::size_t node,const SpatialBounds&,std::vector<std::size_t>&,SpatialQueryStats&)const;
};
}
