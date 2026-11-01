#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <utility>
namespace no5 {
// Ordered GL_LINES indices over the original float client vertex array.
// Preserve every adjacent pair, including degenerates. State, visibility,
// line width and color remain owned by the caller. Fixed ES3 restart index
// 0xffff stays unused; incompatible ranges use the original strip submission.
template<class Submit, std::size_t Capacity=12288> class LineIndexBatch {
 static_assert(Capacity>=2&&Capacity%2==0);
 Submit submit;std::array<std::uint16_t,Capacity> indices;std::size_t used=0;
public:
 explicit LineIndexBatch(Submit value):submit(std::move(value)){}
 void flush(){if(used){submit(indices.data(),used);used=0;}}
 bool appendStrip(int first,int count){
  if(first<0||count<2||first>65534||count>65535-first)return false;
  for(int i=0;i<count-1;i++){
   if(used+2>Capacity)flush();
   indices[used++]=static_cast<std::uint16_t>(first+i);
   indices[used++]=static_cast<std::uint16_t>(first+i+1);
  }
  return true;
 }
};
}
