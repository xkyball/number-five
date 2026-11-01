#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <utility>

// Ordered index batches over an unchanged GLES client vertex array. The caller
// owns GL state and must flush before changing it or submitting another primitive.
// No geometry or visibility decisions live here; strip degenerates are retained.
namespace no5 {
template<class Submit, std::size_t Capacity = 12288>
class TriangleBatch {
  static_assert(Capacity >= 3 && Capacity % 3 == 0);
  Submit submit;
  std::array<std::uint16_t, Capacity> indices;
  std::size_t used = 0;
  void triangle(int a,int b,int c) {
    if(used + 3 > Capacity)flush();
    indices[used++]=static_cast<std::uint16_t>(a);
    indices[used++]=static_cast<std::uint16_t>(b);
    indices[used++]=static_cast<std::uint16_t>(c);
  }
public:
  explicit TriangleBatch(Submit callback):submit(std::move(callback)){}
  void flush(){if(used){submit(indices.data(),used);used=0;}}
  // GLES enum values: TRIANGLES=4, TRIANGLE_STRIP=5, TRIANGLE_FAN=6.
  // False means no indices consumed; caller flushes and uses the original draw.
  bool append(unsigned mode,int first,int count) {
    // Keep 0xffff unused even on ES3 drivers with fixed-index restart enabled.
    if(first<0 || count<0 || first>65534 || count>65535-first ||
       (mode!=4 && mode!=5 && mode!=6) || (mode==4 && count%3))return false;
    if(mode==4){for(int i=0;i<count;i+=3)triangle(first+i,first+i+1,first+i+2);}
    else if(mode==5){for(int i=0;i<count-2;i++){
      if(i%2)triangle(first+i+1,first+i,first+i+2);
      else triangle(first+i,first+i+1,first+i+2);
    }}
    else {for(int i=1;i<count-1;i++)triangle(first,first+i,first+i+1);}
    return true;
  }
};
}
