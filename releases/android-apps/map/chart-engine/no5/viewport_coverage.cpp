#include "viewport_coverage.hpp"
#include "render_epoch.hpp"
#include "s52plib.h"
#include <numeric>
#include <set>

namespace no5 {
std::vector<size_t> coverageScaleOrder(const std::vector<ChartTableEntry>& entries,size_t count,double scale){
 if(count>entries.size()||!std::isfinite(scale)||scale<=0)throw std::invalid_argument("Invalid coverage scale input");
 std::vector<size_t> indices(count);std::iota(indices.begin(),indices.end(),0);
 auto suitable=[&](size_t i){return entries[i].scale>0&&scale<=double(entries[i].scale)*4.;};
 std::stable_sort(indices.begin(),indices.end(),[&](size_t a,size_t b){
  if(entries[a].background!=entries[b].background)return !entries[a].background;
  const bool x=suitable(a),y=suitable(b);if(x!=y)return x;
  const int sa=entries[a].scale,sb=entries[b].scale;
  if((sa>0)!=(sb>0))return sa>0;
  return x?sa<sb:sa>sb;
 });return indices;
}
int ViewportCoverage::ownerAt(double x,double y)const{
 if(!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=width||y>=height||owners.empty())return -1;
 return int(owners[size_t(std::floor(y))*width+size_t(std::floor(x))])-1;
}
namespace {
struct Point{double x,y;};
struct Ring{std::vector<Point> points;double minX=0,maxX=0,minY=0,maxY=0;};
constexpr size_t MaxProjectedVertices=1024*1024;
bool projectRing(const std::vector<float>& source,const CoverageCamera& camera,Ring& result,size_t& vertices){
 if(source.size()<6||source.size()%2||source.size()/2>MaxProjectedVertices-vertices)return false;
 const double c=std::cos(camera.rotation),s=std::sin(camera.rotation);
 result.minX=result.minY=std::numeric_limits<double>::infinity();result.maxX=result.maxY=-result.minY;
 result.points.reserve(source.size()/2);
 // OSENC's validator explicitly excludes wrapped extent/topology. Reject it
 // here as well; do not infer a nautical polygon from a 358-degree bbox.
 double minLon=180,maxLon=-180;
 for(size_t i=0;i<source.size();i+=2){
  const double lat=source[i],lon=source[i+1];
  if(!std::isfinite(lat)||!std::isfinite(lon)||std::abs(lat)>85||std::abs(lon)>180)return false;
  minLon=std::min(minLon,lon);maxLon=std::max(maxLon,lon);
  double east,north;toSM_plib(lat,lon,camera.latitude,camera.longitude,&east,&north);
  const Point p{camera.width*.5+camera.ppm*(east*c+north*s),camera.height*.5+camera.ppm*(east*s-north*c)};
  if(!std::isfinite(p.x)||!std::isfinite(p.y))return false;
  result.minX=std::min(result.minX,p.x);result.maxX=std::max(result.maxX,p.x);
  result.minY=std::min(result.minY,p.y);result.maxY=std::max(result.maxY,p.y);result.points.push_back(p);
 }
 if(maxLon-minLon>180)return false;
 vertices+=source.size()/2;return true;
}
void scanRing(const Ring& ring,int y,int xBegin,int xEnd,std::vector<uint8_t>& row,uint8_t value,std::vector<double>& hits){
 const double py=y+.5;if(py<ring.minY||py>=ring.maxY)return;
 hits.clear();
 for(size_t i=0,j=ring.points.size()-1;i<ring.points.size();j=i++){
  const auto a=ring.points[j],b=ring.points[i];
  if((a.y<=py&&b.y>py)||(b.y<=py&&a.y>py))hits.push_back(a.x+(py-a.y)*(b.x-a.x)/(b.y-a.y));
 }
 std::sort(hits.begin(),hits.end());
 for(size_t i=0;i+1<hits.size();i+=2){
  // Convert only after clamping, so distant geometry cannot overflow an int.
  const int begin=int(std::ceil(std::clamp(hits[i]-.5,double(xBegin),double(xEnd))));
  const int end=int(std::ceil(std::clamp(hits[i+1]-.5,double(xBegin),double(xEnd))));
  if(end>begin)std::fill(row.begin()+begin,row.begin()+end,value);
 }
}
}
ViewportCoverage buildViewportCoverage(const std::vector<ChartTableEntry>& entries,size_t count,const CoverageCamera& camera){
 if(count>MAXSTACK||camera.width<1||camera.width>4096||camera.height<1||camera.height>1499||
    !std::isfinite(camera.latitude)||!std::isfinite(camera.longitude)||std::abs(camera.latitude)>85||std::abs(camera.longitude)>180||
    !std::isfinite(camera.rotation)||!std::isfinite(camera.ppm)||camera.ppm<=0)throw std::invalid_argument("Invalid coverage camera");
 auto priority=coverageScaleOrder(entries,count,camera.chartScale);
 ViewportCoverage plan;plan.width=camera.width;plan.height=camera.height;
 plan.owners.assign(size_t(camera.width)*camera.height,0);
 std::vector<uint8_t> row(camera.width);std::vector<double> hits;
 std::vector<bool> visible(count),contributed(count);std::set<int> scales;size_t vertices=0;
 bool hasExclusions=false,hasBackground=false,hasNautical=false;
 for(auto index:priority){
  const auto& table=entries[index];std::vector<Ring> covered,excluded;bool valid=table.scale>0;
  const auto& rings=table.auxiliary;
  if(rings.empty())valid=false; // Extent alone cannot prove valid data coverage.
  for(const auto& src:rings){Ring ring;if(!projectRing(src,camera,ring,vertices)){valid=false;break;}covered.push_back(std::move(ring));}
  if(valid)for(const auto& src:table.exclusions){Ring ring;if(!projectRing(src,camera,ring,vertices)){valid=false;break;}excluded.push_back(std::move(ring));}
  if(!valid){plan.unknownCells++;continue;}
  // Only fully validated projected coverage can prove offscreen rejection.
  // CATCOV2 can remove pixels, never add anything beyond the CATCOV1 union.
  double minX=std::numeric_limits<double>::infinity(),minY=minX,maxX=-minX,maxY=-minX;
  for(const auto& ring:covered){minX=std::min(minX,ring.minX);maxX=std::max(maxX,ring.maxX);minY=std::min(minY,ring.minY);maxY=std::max(maxY,ring.maxY);}
  if(maxX<=0||maxY<=0||minX>=camera.width||minY>=camera.height)continue;
  // Conservative integer enclosure; exact pixel-centre intersections below
  // still decide ownership. Clamp doubles before int conversion.
  const int xBegin=int(std::floor(std::clamp(minX,0.,double(camera.width)))),xEnd=int(std::ceil(std::clamp(maxX,0.,double(camera.width))));
  const int yBegin=int(std::floor(std::clamp(minY,0.,double(camera.height)))),yEnd=int(std::ceil(std::clamp(maxY,0.,double(camera.height))));
  size_t contributes=0;
  for(int y=yBegin;y<yEnd;y++){
   normalRenderCheckpoint(RenderAbortBoundary::CoverageRow);
   plan.scanRows++;plan.ownershipPixelChecks+=size_t(xEnd-xBegin);
   std::fill(row.begin()+xBegin,row.begin()+xEnd,0);
   for(const auto& ring:covered)scanRing(ring,y,xBegin,xEnd,row,1,hits);
   for(const auto& ring:excluded)scanRing(ring,y,xBegin,xEnd,row,0,hits);
   int firstOwned=-1,lastOwned=-1;
   for(int x=xBegin;x<xEnd;x++)if(row[x]){
    visible[index]=true;auto& owner=plan.owners[size_t(y)*camera.width+x];
    if(!owner){owner=uint8_t(index+1);contributes++;if(firstOwned<0)firstOwned=x;lastOwned=x;}
   }
   // Bound only actually assigned pixels, including overlap/exclusion holes.
   // Collect row endpoints while assigning; no extra full-image scan.
   if(firstOwned>=0){auto& b=plan.ownerBounds[index];if(b.empty())b={firstOwned,y,lastOwned+1,y+1};else{b.xBegin=std::min(b.xBegin,firstOwned);b.xEnd=std::max(b.xEnd,lastOwned+1);b.yEnd=y+1;}}
  }
  contributed[index]=contributes>0;if(contributes&&!plan.referenceScale)plan.referenceScale=table.scale;
  if(visible[index]){scales.insert(table.scale);hasExclusions|=!table.exclusions.empty();hasBackground|=table.background;hasNautical|=!table.background;}
 }
 plan.masked=scales.size()>1||hasExclusions||plan.unknownCells||hasBackground;
 // Retain the original same-scale, no-exclusion cohort and its stable S52
 // priority order. Cross-scale ownership must not alter that pixel baseline.
 for(size_t index=0;index<count;index++)if(visible[index]&&(!plan.masked||contributed[index]))plan.selected.push_back(index);
 plan.uncoveredPixels=std::count(plan.owners.begin(),plan.owners.end(),uint8_t(0));
 return plan;
}
#ifdef ocpnUSE_GL
namespace {
GLuint compileCoverage(GLenum type,const char* source){
 auto shader=glCreateShader(type);glShaderSource(shader,1,&source,nullptr);glCompileShader(shader);GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
 if(!ok){glDeleteShader(shader);throw std::runtime_error("Coverage stencil shader compile failed");}return shader;
}
}
void CoverageStencil::init(){
 if(program)return;GLuint vs=0,fs=0,candidate=0;
 try{
  vs=compileCoverage(GL_VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.,1.);}");
  // Draw only owned pixel rectangles. This avoids relying on fragment discard
  // to suppress stencil writes on tile-based GLES drivers. The CPU ownership
  // raster remains authoritative; triangles do not alter a boundary pixel.
  fs=compileCoverage(GL_FRAGMENT_SHADER,"precision mediump float;uniform vec4 fill;void main(){gl_FragColor=fill;}");
  candidate=glCreateProgram();glAttachShader(candidate,vs);glAttachShader(candidate,fs);glBindAttribLocation(candidate,0,"position");glLinkProgram(candidate);GLint ok=0;glGetProgramiv(candidate,GL_LINK_STATUS,&ok);
  if(!ok)throw std::runtime_error("Coverage stencil program link failed");
  glDeleteShader(vs);glDeleteShader(fs);vs=fs=0;program=candidate;candidate=0;
  colorUniform=glGetUniformLocation(program,"fill");if(colorUniform<0)throw std::runtime_error("Coverage fill uniform unavailable");
  glGenRenderbuffers(1,&buffer);
 }catch(...){if(vs)glDeleteShader(vs);if(fs)glDeleteShader(fs);if(candidate)glDeleteProgram(candidate);release();throw;}
}
void CoverageStencil::upload(const ViewportCoverage& plan){
 if(attached)throw std::logic_error("Coverage upload during S52 pass");init();
 GLint oldBuffer=0;glGetIntegerv(GL_RENDERBUFFER_BINDING,&oldBuffer);
 glBindRenderbuffer(GL_RENDERBUFFER,buffer);
 if(width!=plan.width||height!=plan.height){
  GLint limit=0;glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&limit);
  if(plan.width>limit||plan.height>limit){glBindRenderbuffer(GL_RENDERBUFFER,oldBuffer);throw std::runtime_error("Coverage mask exceeds stencil limit");}
  glRenderbufferStorage(GL_RENDERBUFFER,GL_STENCIL_INDEX8,plan.width,plan.height);
 }
 glBindRenderbuffer(GL_RENDERBUFFER,oldBuffer);
 // Compress equal horizontal owner runs vertically; geometry is bounded by
 // the same pixel raster. No per-cell full-screen texture/discard pass remains.
 for(auto& rectangles:ownerRectangles)rectangles.clear();
 struct Run {int left,right,owner;size_t rectangle;};
 std::vector<Run> previous,rowRuns;
 for(int y=0;y<plan.height;y++){
  rowRuns.clear();size_t before=0;
  for(int x=0;x<plan.width;){
   const int owner=plan.owners[size_t(y)*plan.width+x];const int left=x++;
   while(x<plan.width&&plan.owners[size_t(y)*plan.width+x]==owner)++x;
   if(!owner)continue;
   if(owner>MAXSTACK)throw std::invalid_argument("Coverage owner exceeds native stack");
   while(before<previous.size()&&previous[before].left<left)++before;
   auto& rectangles=ownerRectangles[owner-1];size_t index;
   if(before<previous.size()&&previous[before].left==left&&previous[before].right==x&&previous[before].owner==owner){
    index=previous[before].rectangle;rectangles[index].bottom=uint16_t(y+1);
   }else{index=rectangles.size();rectangles.push_back({uint16_t(left),uint16_t(y),uint16_t(x),uint16_t(y+1)});}
   rowRuns.push_back({left,x,owner,index});
  }
  previous.swap(rowRuns);
 }
 width=plan.width;height=plan.height;
 contentsCleared=false;painted.fill(false);current=-1;clears=paints=draws=rasterPixels=0;ownerBounds=plan.ownerBounds;
 if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Coverage mask allocation/upload failed");
}
void CoverageStencil::begin(){
 if(attached)throw std::logic_error("Nested coverage stencil");
 GLint viewport[4];glGetIntegerv(GL_VIEWPORT,viewport);
 if(viewport[0]!=0||viewport[1]!=0||viewport[2]!=width||viewport[3]!=height)throw std::runtime_error("Coverage stencil requires the current whole producer viewport");
 glGetIntegerv(GL_FRAMEBUFFER_BINDING,&savedFramebuffer);
 glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,&savedType);
 glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,&savedName);
 if(savedType!=GL_NONE&&savedType!=GL_RENDERBUFFER)throw std::runtime_error("Unsupported existing stencil attachment");
 glGetIntegerv(GL_RENDERBUFFER_BINDING,&savedRenderbuffer);
 savedEnabled=glIsEnabled(GL_STENCIL_TEST);glGetIntegerv(GL_STENCIL_FUNC,&savedFunc);glGetIntegerv(GL_STENCIL_REF,&savedRef);glGetIntegerv(GL_STENCIL_VALUE_MASK,&savedValueMask);glGetIntegerv(GL_STENCIL_WRITEMASK,&savedWriteMask);glGetIntegerv(GL_STENCIL_FAIL,&savedFail);glGetIntegerv(GL_STENCIL_PASS_DEPTH_FAIL,&savedDepthFail);glGetIntegerv(GL_STENCIL_PASS_DEPTH_PASS,&savedDepthPass);glGetIntegerv(GL_STENCIL_CLEAR_VALUE,&savedClear);
 glGetIntegerv(GL_STENCIL_BACK_FUNC,&backFunc);glGetIntegerv(GL_STENCIL_BACK_REF,&backRef);glGetIntegerv(GL_STENCIL_BACK_VALUE_MASK,&backValueMask);glGetIntegerv(GL_STENCIL_BACK_WRITEMASK,&backWriteMask);glGetIntegerv(GL_STENCIL_BACK_FAIL,&backFail);glGetIntegerv(GL_STENCIL_BACK_PASS_DEPTH_FAIL,&backDepthFail);glGetIntegerv(GL_STENCIL_BACK_PASS_DEPTH_PASS,&backDepthPass);
 glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_RENDERBUFFER,buffer);attached=true;current=-1;
 if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE){end();throw std::runtime_error("Coverage stencil framebuffer incomplete");}
}
void CoverageStencil::cell(size_t index){
 if(!attached||index>=MAXSTACK)throw std::logic_error("Invalid coverage stencil cell");if(current==int(index))return;
 glEnable(GL_STENCIL_TEST);
 if(!painted[index]){
  GLboolean color[4];glGetBooleanv(GL_COLOR_WRITEMASK,color);
  GLint box[4];glGetIntegerv(GL_SCISSOR_BOX,box);
  const auto scissor=glIsEnabled(GL_SCISSOR_TEST),blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST);
  glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glStencilMask(0xff);
  // The owned stencil stores the disjoint owner ID, not a per-cell Boolean.
  // It survives area -> object passes. Clear once per upload; paint each used
  // cell once. Global S52 priorities may then revisit it without a mask draw.
  if(!contentsCleared){glClearStencil(0);glClear(GL_STENCIL_BUFFER_BIT);contentsCleared=true;clears++;}
  const auto& bounds=ownerBounds[index];
  if(!bounds.empty()){
  // CPU ownership starts at the top; GLES scissor starts at the bottom.
  // The one clear above remains full-FBO regardless of incoming scissor.
  glEnable(GL_SCISSOR_TEST);glScissor(bounds.xBegin,height-bounds.yEnd,bounds.xEnd-bounds.xBegin,bounds.yEnd-bounds.yBegin);
  glStencilFunc(GL_ALWAYS,GLint(index+1),0xff);glStencilOp(GL_KEEP,GL_KEEP,GL_REPLACE);glColorMask(GL_FALSE,GL_FALSE,GL_FALSE,GL_FALSE);
  glUseProgram(program);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
  glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);
  // Bound transient client vertices independently of coastline fragmentation.
  std::vector<GLfloat> vertices;vertices.reserve(32760*2);
  auto flush=[&](){if(vertices.empty())return;glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,vertices.data());glDrawArrays(GL_TRIANGLES,0,GLsizei(vertices.size()/2));draws++;vertices.clear();};
  for(const auto& r:ownerRectangles[index]){
   const GLfloat l=2.f*r.left/width-1.f,right=2.f*r.right/width-1.f;
   const GLfloat top=1.f-2.f*r.top/height,bottom=1.f-2.f*r.bottom/height;
   const GLfloat v[]={l,bottom,right,bottom,l,top,l,top,right,bottom,right,top};
   vertices.insert(vertices.end(),std::begin(v),std::end(v));if(vertices.size()>=32760*2)flush();
  }
  flush();glDisableVertexAttribArray(0);glUseProgram(0);
  rasterPixels+=bounds.pixels();
  }
  glColorMask(color[0],color[1],color[2],color[3]);
  glScissor(box[0],box[1],box[2],box[3]);
  if(scissor)glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);if(blend)glEnable(GL_BLEND);if(depth)glEnable(GL_DEPTH_TEST);
  painted[index]=true;paints++;
 }
 glStencilMask(0);glStencilFunc(GL_EQUAL,GLint(index+1),0xff);glStencilOp(GL_KEEP,GL_KEEP,GL_KEEP);current=int(index);
}
void CoverageStencil::fill(size_t index,float red,float green,float blue){
 cell(index);const auto blend=glIsEnabled(GL_BLEND),depth=glIsEnabled(GL_DEPTH_TEST);
 glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glUseProgram(program);glUniform4f(colorUniform,red,green,blue,1.f);
 glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
 const GLfloat quad[]={-1,-1,1,-1,-1,1,1,1};glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,quad);
 glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(0);glUseProgram(0);
 if(blend)glEnable(GL_BLEND);if(depth)glEnable(GL_DEPTH_TEST);
}
void CoverageStencil::end(){
 if(!attached)return;
 // An S52 pass must not silently replace the current colour target.
 GLint currentFramebuffer=0;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&currentFramebuffer);
 glBindFramebuffer(GL_FRAMEBUFFER,savedFramebuffer);
 glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_STENCIL_ATTACHMENT,GL_RENDERBUFFER,savedType==GL_RENDERBUFFER?savedName:0);
 glBindRenderbuffer(GL_RENDERBUFFER,savedRenderbuffer);glStencilFuncSeparate(GL_FRONT,savedFunc,savedRef,savedValueMask);glStencilMaskSeparate(GL_FRONT,savedWriteMask);glStencilOpSeparate(GL_FRONT,savedFail,savedDepthFail,savedDepthPass);glStencilFuncSeparate(GL_BACK,backFunc,backRef,backValueMask);glStencilMaskSeparate(GL_BACK,backWriteMask);glStencilOpSeparate(GL_BACK,backFail,backDepthFail,backDepthPass);glClearStencil(savedClear);
 if(savedEnabled)glEnable(GL_STENCIL_TEST);else glDisable(GL_STENCIL_TEST);
 glBindFramebuffer(GL_FRAMEBUFFER,currentFramebuffer);attached=false;current=-1;
}
void CoverageStencil::release(){end();if(program)glDeleteProgram(program);if(buffer)glDeleteRenderbuffers(1,&buffer);program=buffer=0;width=height=0;contentsCleared=false;painted.fill(false);clears=paints=draws=rasterPixels=0;ownerBounds={};for(auto& rectangles:ownerRectangles)std::vector<OwnerRectangle>().swap(rectangles);}
#endif
}
