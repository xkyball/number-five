#pragma once
#include <algorithm>
#include <stdexcept>

namespace no5 {
// Keep in sync with ChartSurfaceGeometry; S52 edge arrays have 1500 entries.
constexpr int MaxChartWidth=4096,MaxChartHeight=1499;
struct SurfaceGeometry {
  int width,height,frameWidth,frameHeight;
  double scale,left,top;
};
inline SurfaceGeometry surfaceGeometry(int width,int height,int frameWidth,int frameHeight) {
  if(width<1||height<1||frameWidth<1||frameWidth>MaxChartWidth||frameHeight<1||frameHeight>MaxChartHeight)
    throw std::invalid_argument("Invalid Surface/frame size");
  double scale=std::min(double(width)/frameWidth,double(height)/frameHeight);
  return {width,height,frameWidth,frameHeight,scale,(width-frameWidth*scale)/2,(height-frameHeight*scale)/2};
}
inline SurfaceGeometry fitSurface(int width,int height) {
  if(width<1||height<1)throw std::invalid_argument("Invalid Surface size");
  double reduction=std::min({1.0,double(MaxChartWidth)/width,double(MaxChartHeight)/height});
  return surfaceGeometry(width,height,std::clamp(int(width*reduction),1,MaxChartWidth),
    std::clamp(int(height*reduction),1,MaxChartHeight));
}
}
