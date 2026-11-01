#pragma once
#include "gles.hpp"
#include "viewport_reuse_run.hpp"
#include "port/sha256.hpp"
#include <iomanip>
#include <list>
#include <limits>
#include <sstream>
#include <thread>

namespace no5 {
std::string jsonEscape(const std::string&);
// This is an owner-only observation, separate from timing. The native frame
// ID is not a SurfaceFlinger frame, fence, camera revision or display proof.
struct ViewportParityFrame {
 bool valid=false;
 uint64_t frame=0,overlayRevision=0;
 std::array<uint64_t,3> revisions{};
 double latitude=0,longitude=0,ppm=0,rotation=0;
 int width=0,height=0,slot=0;
 std::array<char,8> palette{};
 std::array<double,3> depthSettings{};
 bool operator==(const ViewportParityFrame& b)const{
  return valid==b.valid&&frame==b.frame&&overlayRevision==b.overlayRevision&&revisions==b.revisions&&
   latitude==b.latitude&&longitude==b.longitude&&ppm==b.ppm&&rotation==b.rotation&&width==b.width&&height==b.height&&slot==b.slot&&
   palette==b.palette&&depthSettings==b.depthSettings;
 }
};
inline ViewportParityFrame viewportParityFrame(const No5ChartRenderer& chart,const GlesCompositor& gpu,uint64_t overlayRevision){
 ViewportParityFrame f;f.valid=chart.loaded&&gpu.attached();f.frame=gpu.completedFrameId();f.overlayRevision=overlayRevision;f.revisions=chart.diagnosticRevisions();
 f.latitude=chart.view.clat;f.longitude=chart.view.clon;f.ppm=chart.view.view_scale_ppm;f.rotation=chart.view.rotation;f.width=chart.view.pix_width;f.height=chart.view.pix_height;f.slot=gpu.presentationSlot();
 f.valid=f.valid&&chart.settings.palette.size()<f.palette.size();if(f.valid)std::copy(chart.settings.palette.begin(),chart.settings.palette.end(),f.palette.begin());
 f.depthSettings={chart.settings.safety,chart.settings.shallow,chart.settings.deep};return f;
}
struct ViewportParityRequest {
 std::string runId,catalogGeneration;
 uint64_t frame=0,contentRevision=0,dataGeneration=0;
 double latitude=0,longitude=0,ppm=0,rotation=0;
 int width=0,height=0;
};
inline void requireViewportParityFrame(const ViewportParityRequest& request,const ViewportParityFrame& completed,const ViewportParityFrame& current){
 if(request.runId.empty()||request.runId.size()>128||request.catalogGeneration.size()>512||!request.frame||request.width<1||request.width>MaxChartWidth||request.height<1||request.height>MaxChartHeight||
    !std::isfinite(request.latitude)||!std::isfinite(request.longitude)||!std::isfinite(request.ppm)||!std::isfinite(request.rotation)||request.ppm<=0||
    !completed.valid||!(completed==current)||request.frame!=current.frame||request.dataGeneration!=current.revisions[0]||
    request.latitude!=current.latitude||request.longitude!=current.longitude||request.ppm!=current.ppm||request.rotation!=current.rotation||request.width!=current.width||request.height!=current.height)
  throw std::logic_error("Parity frame/camera/data authority changed");
}
// Byte-for-byte algorithms from the existing viewport_reuse_probe oracle.
// No source-ID, attribute, ordering, index or query normalization is performed.
inline std::string viewportParitySemanticHash(const No5ChartRenderer& chart,const std::function<void()>& checkpoint){
 Sha256 hash;
 for(const auto& feature:chart.features){checkpoint();const auto& object=*feature.object;
  hash.update(feature.id+":"+feature.name+":"+std::to_string(object.Primitive_type));
  for(const auto& field:feature.attributes)hash.update(field.first+":"+field.second+"\n");
 }
 for(const auto& cell:chart.catalogue.entries){checkpoint();hash.update(std::to_string(cell.scale));
  for(const auto& polygons:{cell.auxiliary,cell.exclusions})for(const auto& polygon:polygons)hash.update(polygon.data(),polygon.size()*sizeof(float));
 }
 return hash.hex();
}
inline std::string viewportParityAssociationHash(No5ChartRenderer& chart,const std::function<void()>& checkpoint){
 Sha256 hash;
 for(auto& feature:chart.features){checkpoint();if(feature.name!="SOUNDG"&&feature.name!="WRECKS"&&feature.name!="OBSTRN"&&feature.name!="UWTROC")continue;
  hash.update(feature.id+":"+feature.name+":");
  std::unique_ptr<std::list<S57Obj*>> objects(feature.cell->GetAssociatedObjects(feature.object.get()));
  if(objects)for(auto* object:*objects){checkpoint();hash.update(std::to_string(object->Index)+",");}
  hash.update("\n");
 }
 return hash.hex();
}
// Shared unchanged FULL-frame oracle. Admission remains in the two distinct
// callers: the original reuse/OFF reader and the explicit current-live reader.
inline std::string captureCompleteViewportParity(No5ChartRenderer& chart,GlesCompositor& gpu,BenchmarkRunControl& control,
 const std::thread::id& owner,const ViewportParityRequest& request,const ViewportParityFrame& completed,uint64_t overlayRevision,
 const std::function<void()>& extraCheckpoint={},const char* schema="no5.viewport-reuse-parity/1"){
 if(std::this_thread::get_id()!=owner)throw std::logic_error("Parity capture requires native owner thread");
 const auto run=control.snapshot();
 auto checkpoint=[&]{if(extraCheckpoint)extraCheckpoint();run.checkpoint();if(!control.owns(request.runId))throw std::logic_error("Parity run ended");};
 auto authority=[&]{checkpoint();requireViewportParityFrame(request,completed,viewportParityFrame(chart,gpu,overlayRevision));
  const auto resident=chart.residentStatus();
  if(resident.enabled?(resident.catalogGeneration!=request.catalogGeneration||resident.contentRevision!=request.contentRevision||!resident.complete):(!request.catalogGeneration.empty()||request.contentRevision!=0))
   throw std::logic_error("Parity resident source revision changed or incomplete");
 };
 authority();
 const auto image=gpu.readCompletedDiagnosticFrame(request.frame,request.width,request.height);authority();
 if(image.rgba.size()!=size_t(request.width)*request.height*4)throw std::logic_error("Parity RGBA size mismatch");
 Sha256 pixels;pixels.update(image.rgba.data(),image.rgba.size());
 const auto semantic=viewportParitySemanticHash(chart,checkpoint),associations=viewportParityAssociationHash(chart,checkpoint);authority();
 std::ostringstream out;out<<std::setprecision(std::numeric_limits<double>::max_digits10)
  <<"{\"schema\":\""<<schema<<"\",\"runId\":\""<<jsonEscape(request.runId)<<"\",\"frame\":"<<request.frame
  <<",\"catalogGeneration\":\""<<jsonEscape(request.catalogGeneration)<<"\",\"contentRevision\":"<<request.contentRevision<<",\"dataGeneration\":"<<request.dataGeneration
  <<",\"presentationGeneration\":"<<completed.revisions[1]<<",\"filterRevision\":"<<completed.revisions[2]<<",\"overlayRevision\":"<<overlayRevision<<",\"presentationSlot\":"<<completed.slot
  <<",\"latitude\":"<<request.latitude<<",\"longitude\":"<<request.longitude<<",\"ppm\":"<<request.ppm<<",\"rotation\":"<<request.rotation<<",\"width\":"<<image.width<<",\"height\":"<<image.height
  <<",\"rgbaSHA256\":\""<<pixels.hex()<<"\",\"rgbaBytes\":"<<image.rgba.size()<<",\"semanticSHA256\":\""<<semantic<<"\",\"associationsSHA256\":\""<<associations<<"\",\"featureCount\":"<<chart.features.size()<<",\"status\":"<<chart.status()
  <<",\"queryRecipe\":\"viewport-center-five/1: x=w/2+(i-2)*w/8, y=h/2+(i%2)*h/8, radius=24, offset=0\",\"queries\":[";
 for(int i=0;i<5;i++){authority();const double x=request.width*.5+(i-2)*request.width*.125,y=request.height*.5+(i%2)*request.height*.125;
  const auto query=chart.queryObjects(x,y,24);if(query.size()>256*1024)throw std::runtime_error("Parity query exceeds exact diagnostic budget");Sha256 hash;hash.update(query);
  if(i)out<<',';out<<"{\"x\":"<<x<<",\"y\":"<<y<<",\"radius\":24,\"offset\":0,\"jsonBytes\":"<<query.size()<<",\"sha256\":\""<<hash.hex()<<"\"}";
 }
 authority();out<<"],\"pixelSource\":\"completed native output FBO, full 1:1 RGBA; no post-swap window read\",\"readback\":\"blocking CPU diagnostic outside timing; not display/GPU time\",\"normalization\":\"NONE\",\"extraDraws\":0,\"extraSwaps\":0,\"consumerAcknowledgements\":0}";
 const auto result=out.str();if(result.size()>16*1024)throw std::runtime_error("Parity report exceeds bounded diagnostic budget");checkpoint();return result;
}
inline std::string captureViewportReuseParity(No5ChartRenderer& chart,GlesCompositor& gpu,BenchmarkRunControl& control,const ViewportReuseRun& reuse,
 const std::thread::id& owner,const ViewportParityRequest& request,const ViewportParityFrame& completed,uint64_t overlayRevision,
 const std::function<void()>& extraCheckpoint={}){
 if(std::this_thread::get_id()!=owner)throw std::logic_error("Parity capture requires native owner thread");
 if(!reuse.active()||reuse.id()!=request.runId||!control.owns(request.runId))throw std::logic_error("Parity capture requires its active viewport diagnostic run");
 return captureCompleteViewportParity(chart,gpu,control,owner,request,completed,overlayRevision,extraCheckpoint);
}
}
