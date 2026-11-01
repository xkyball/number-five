#pragma once
#include "viewport_reuse_parity.hpp"
#include <unordered_set>

namespace no5 {
// This authority does not enable reuse, render, publish or acknowledge a slot.
// Java's registry/camera/source/surface counters remain explicit owner joins.
class CurrentLiveParityCapture {
 std::string run_,id_,catalog_;
 std::string captureHistoryRun;
 std::unordered_set<std::string> usedCaptureIds;
 uint64_t content_=0,data_=0,ownerSource_=0,ownerSurface_=0,initialRegistry_=0;
 bool active_=false,failed_=false,normal_=false;
 struct Full {
  ViewportParityFrame frame;
  uint64_t stamp=0,registry=0;
  RenderAuthority applied;
 } completed;
 std::array<std::pair<uint64_t,uint64_t>,6> delivered{};
 size_t deliveredCount=0,deliveredBytes=0;
public:
 static constexpr size_t MaxFrames=6,MaxFrameBytes=16*1024,MaxSetBytes=256*1024;
 bool active()const{return active_;}
 bool expectedNormal()const{return normal_;}
 const std::string& runId()const{return run_;}
 const std::string& captureId()const{return id_;}
 bool matches(const std::string& run,const std::string& id)const{return run_==run&&id_==id;}
 const std::string& catalog()const{return catalog_;}
 uint64_t contentRevision()const{return content_;}
 uint64_t dataGeneration()const{return data_;}
 uint64_t ownerSource()const{return ownerSource_;}
 uint64_t ownerSurface()const{return ownerSurface_;}
 void begin(BenchmarkRunControl& control,const std::string& run,const std::string& id,bool normal,
   const std::string& catalog,uint64_t content,uint64_t data,uint64_t source,uint64_t surface,uint64_t registry){
  if(active_||run.empty()||run.size()>128||id.empty()||id.size()>128||catalog.size()>512||!registry||!control.owns(run))
   throw std::logic_error("Current-live parity requires a new bounded capture and its existing run");
  control.snapshotOwned(run).checkpoint();
  if(captureHistoryRun!=run){captureHistoryRun=run;usedCaptureIds.clear();}
  if(usedCaptureIds.size()>=6||!usedCaptureIds.insert(id).second)throw std::logic_error("Current-live parity capture identity reused or bounded history exhausted");
  run_=run;id_=id;catalog_=catalog;content_=content;data_=data;ownerSource_=source;ownerSurface_=surface;initialRegistry_=registry;
  normal_=normal;failed_=false;deliveredCount=deliveredBytes=0;completed={};active_=true;
 }
 bool end(const std::string& run,const std::string& id){
  // Cleanup remains possible after cancellation/deadline, but never releases
  // a different capture, even when it reuses the same BenchmarkRunControl.
  if(run_!=run||id_!=id)return false;
  active_=false;completed={};return true;
 }
 void reset(){active_=false;failed_=true;completed={};}
 void invalidateFrame()noexcept{completed={};}
 void fail()noexcept{failed_=true;completed={};}
 void failOwned(const std::string& run,const std::string& id)noexcept{if(matches(run,id))fail();}
 void fullCompleted(uint64_t stamp,const ViewportParityFrame& frame,bool normal,
   RenderAuthority applied={},uint64_t registry=0)noexcept{
  completed={};
  if(!active_||failed_||!stamp||!frame.valid||normal!=normal_||
     (normal&&(!applied.valid()||!registry)))return;
  completed={frame,stamp,registry,applied};
 }
 void require(BenchmarkRunControl& control,const std::string& run,const std::string& id,
   uint64_t stamp,uint64_t frame,uint64_t camera,uint64_t registry,int slot)const{
  if(!active_||failed_||run_!=run||id_!=id||!control.owns(run))throw std::logic_error("Current-live parity capture/run authority ended");
  control.snapshotOwned(run).checkpoint();
  if(!completed.frame.valid||completed.stamp!=stamp||completed.frame.frame!=frame||completed.frame.slot!=slot||!registry||
     completed.frame.revisions[0]!=data_)
   throw std::logic_error("Current-live parity requires its exact successful tagged FULL");
  if(deliveredCount>=MaxFrames||deliveredBytes>=MaxSetBytes)throw std::logic_error("Current-live parity capture bound exhausted");
  if(normal_&&(completed.registry!=registry||completed.applied.cameraRevision!=camera||
     completed.applied.source!=ownerSource_||completed.applied.surface!=ownerSurface_))
   throw std::logic_error("Current-live parity applied native authority changed");
  for(size_t i=0;i<deliveredCount;i++)if(delivered[i]==std::make_pair(stamp,frame))throw std::logic_error("Current-live parity frame already captured");
 }
 const RenderAuthority& applied()const{return completed.applied;}
 std::string capture(No5ChartRenderer& chart,GlesCompositor& gpu,BenchmarkRunControl& control,const std::thread::id& owner,
   const std::string& run,const std::string& id,uint64_t stamp,uint64_t frame,uint64_t camera,uint64_t registry,
   double lat,double lon,double ppm,double rotation,int width,int height,int slot,uint64_t overlayRevision,
   const std::function<void()>& ownerCheckpoint){
  try{
   auto checkpoint=[&]{require(control,run,id,stamp,frame,camera,registry,slot);ownerCheckpoint();};
   checkpoint();
   const ViewportParityRequest request{run,catalog_,frame,content_,data_,lat,lon,ppm,rotation,width,height};
   auto result=captureCompleteViewportParity(chart,gpu,control,owner,request,completed.frame,overlayRevision,checkpoint,"no5.current-live-full-parity/1");
   std::ostringstream extra;extra<<",\"captureId\":\""<<jsonEscape(id_)<<"\",\"normalAbortEnabled\":"<<(normal_?"true":"false")
    <<",\"fullBufferStamp\":"<<stamp<<",\"ownerCameraRevision\":"<<camera<<",\"bufferGeneration\":"<<registry
    <<",\"ownerSourceGeneration\":"<<ownerSource_<<",\"ownerSurfaceGeneration\":"<<ownerSurface_
    <<",\"initialOwnerBufferGeneration\":"<<initialRegistry_
    <<",\"ownerDomainJoin\":\"Kotlin exact completed FULL registry reference before/after; registry/camera/source/surface are not nativeFrame domains\",\"nativeAppliedAuthority\":";
   if(normal_)extra<<"{\"epoch\":"<<completed.applied.epoch<<",\"cameraRevision\":"<<completed.applied.cameraRevision<<",\"sourceGeneration\":"<<completed.applied.source<<",\"surfaceGeneration\":"<<completed.applied.surface<<'}';
   else extra<<"null";
   extra<<",\"captureFrameIndex\":"<<deliveredCount<<",\"captureFrameLimit\":"<<MaxFrames<<",\"captureFrameByteLimit\":"<<MaxFrameBytes<<",\"captureSetByteLimit\":"<<MaxSetBytes<<'}';
   result.pop_back();result+=extra.str();
   if(result.size()>MaxFrameBytes||result.size()>MaxSetBytes-deliveredBytes)throw std::runtime_error("Current-live parity report exceeds bounded capture budget");
   checkpoint();delivered[deliveredCount++]={stamp,frame};deliveredBytes+=result.size();return result;
  }catch(...){failOwned(run,id);throw;}
 }
};
}
