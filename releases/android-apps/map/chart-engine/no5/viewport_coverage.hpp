#pragma once
#include "port/chart_database.hpp"
#include <cstdint>
#include <functional>

namespace no5 {
struct CoverageCamera {
 double latitude=0,longitude=0,ppm=0,rotation=0,chartScale=0;
 int width=0,height=0;
};
// Source order is a deterministic tie-breaker, never an identity. This is the
// adopted S57 scale heuristic, not an assertion of nautical detail sufficiency.
std::vector<size_t> coverageScaleOrder(const std::vector<ChartTableEntry>&,size_t,double chartScale);
struct CoveragePixelBounds {
 int xBegin=0,yBegin=0,xEnd=0,yEnd=0;
 bool empty()const{return xBegin>=xEnd||yBegin>=yEnd;}
 size_t pixels()const{return empty()?0:size_t(xEnd-xBegin)*size_t(yEnd-yBegin);}
};
struct ViewportCoverage {
 int width=0,height=0,referenceScale=0;
 bool masked=false;
 size_t unknownCells=0,uncoveredPixels=0;
 // Bounded algorithm-operation counts; tests distinguish spatial pruning from
 // an unmeasured device timing/FPS claim. No per-feature instrumentation.
 size_t scanRows=0,ownershipPixelChecks=0;
 std::vector<size_t> selected;
 // One byte per pixel, zero = no chart, otherwise stable active cell index + 1.
 // Active native residency remains limited to MAXSTACK (100), not catalog size.
 std::vector<uint8_t> owners;
 std::array<CoveragePixelBounds,MAXSTACK> ownerBounds{};
 int ownerAt(double x,double y)const;
 bool owns(size_t cell,double x,double y)const{return ownerAt(x,y)==int(cell);}
};
// Actual projected rings, pixel-centre scan conversion, CATCOV1 union minus
// CATCOV2. No bbox or centre sample is accepted as proof of coverage.
ViewportCoverage buildViewportCoverage(const std::vector<ChartTableEntry>&,size_t,const CoverageCamera&);

#ifdef ocpnUSE_GL
// Owns one GLES2 stencil renderbuffer and bounded, pixel-aligned ownership rectangles. Attaches
// only while a S52 pass is running, restoring the actual current FBO's previous
// stencil attachment/state. No chart-colour readback/upload or extra colour FBO.
class CoverageStencil {
 GLuint buffer=0,program=0;
 GLint colorUniform=-1;
 struct OwnerRectangle {uint16_t left,top,right,bottom;};
 std::array<std::vector<OwnerRectangle>,MAXSTACK> ownerRectangles;
 int width=0,height=0;
 bool attached=false;
 GLint savedFramebuffer=0,savedType=GL_NONE,savedName=0,savedRenderbuffer=0;
 GLint savedFunc=GL_ALWAYS,savedRef=0,savedValueMask=0,savedWriteMask=0;
 GLint savedFail=GL_KEEP,savedDepthFail=GL_KEEP,savedDepthPass=GL_KEEP,savedClear=0;
 GLint backFunc=GL_ALWAYS,backRef=0,backValueMask=0,backWriteMask=0,backFail=GL_KEEP,backDepthFail=GL_KEEP,backDepthPass=GL_KEEP;
 GLboolean savedEnabled=GL_FALSE;
 int current=-1;
 bool contentsCleared=false;
 std::array<bool,MAXSTACK> painted{};
 std::array<CoveragePixelBounds,MAXSTACK> ownerBounds{};
 size_t clears=0,paints=0,draws=0,rasterPixels=0;
 void init();
public:
 void upload(const ViewportCoverage&);
 void begin();
 void cell(size_t);
 void fill(size_t,float red,float green,float blue); // Only the exact owned pixels.
 void end();
 size_t clearCount()const{return clears;}
 size_t paintCount()const{return paints;}
 size_t drawCount()const{return draws;}
 size_t rasterPixelBudget()const{return rasterPixels;}
 void release(); // Explicit on the owning current EGL context, like S52.
};
#endif
}
