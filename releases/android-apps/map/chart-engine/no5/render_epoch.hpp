#pragma once
#include "benchmark_run.hpp"
#include <array>
#include <limits>
#include <thread>

namespace no5 {
struct RenderAuthority {
 uint64_t epoch=0,cameraRevision=0,source=0,surface=0;
 bool valid()const{const auto max=uint64_t(std::numeric_limits<int64_t>::max());return epoch>0&&epoch<max&&cameraRevision<=max&&source<=max&&surface<=max;}
 bool operator==(const RenderAuthority& b)const{return epoch==b.epoch&&cameraRevision==b.cameraRevision&&source==b.source&&surface==b.surface;}
};
enum class RenderAbortBoundary { Begin, CoverageRow, CoverageCommit, Prepared, AreaObject, LinePriorityCell, Object, LandObject, FullCache, Publication, Portrayal, Count };
inline constexpr const char* renderAbortBoundaryNames[]={"begin","coverage row","coverage commit","prepared","area object","line priority cell","object","land object","full cache","publication","portrayal object"};
enum class RenderSupersededReason { Camera, Source, Surface, Closed, Unbound };
inline constexpr const char* renderSupersededReasonNames[]={"NEW_CAMERA","SOURCE_CHANGED","SURFACE_CHANGED","ENGINE_CLOSED","APPLIED_AUTHORITY_UNBOUND"};
class RenderSuperseded final:public std::runtime_error {
public:
 const RenderSupersededReason reason;const RenderAbortBoundary boundary;const uint64_t expected,latest;
 RenderSuperseded(RenderSupersededReason why,RenderAbortBoundary at,uint64_t old,uint64_t now):std::runtime_error("SUPERSEDED_RENDER"),reason(why),boundary(at),expected(old),latest(now){}
};
struct RenderAuthorityState {
 std::mutex mutex;
 std::atomic<uint64_t> latestEpoch{0};
 std::atomic<bool> closed{false};
 RenderAuthority latest;
};
class RenderEpochSnapshot;
class RenderAuthorityControl {
 std::shared_ptr<RenderAuthorityState> state=std::make_shared<RenderAuthorityState>();
 friend class RenderEpochSnapshot;
public:
 // Independent of the renderer mutex, GL and model. Reordered stale advances
 // never move authority backwards or relabel an existing epoch.
 bool advance(RenderAuthority target){
  if(!target.valid())return false;std::lock_guard<std::mutex> lock(state->mutex);
  if(state->closed.load(std::memory_order_acquire))return false;
  if(target.epoch==state->latest.epoch)return target==state->latest;
  if(target.epoch<state->latest.epoch||target.cameraRevision<state->latest.cameraRevision||target.source<state->latest.source||target.surface<state->latest.surface)return false;
  state->latest=target;state->latestEpoch.store(target.epoch,std::memory_order_release);return true;
 }
 bool canBindApplied(RenderAuthority applied)const{
  if(!applied.valid())return false;std::lock_guard<std::mutex> lock(state->mutex);
  return !state->closed.load(std::memory_order_acquire)&&applied.epoch<=state->latest.epoch&&
   (applied.epoch!=state->latest.epoch||applied==state->latest);
 }
 RenderAuthority current()const{std::lock_guard<std::mutex> lock(state->mutex);return state->latest;}
 void close(){std::lock_guard<std::mutex> lock(state->mutex);state->closed.store(true,std::memory_order_release);}
};
class RenderAuthorityRegistry {
 std::mutex mutex;std::unordered_map<int64_t,std::shared_ptr<RenderAuthorityControl>> controls;
public:
 void add(int64_t id,const std::shared_ptr<RenderAuthorityControl>& control){std::lock_guard<std::mutex> lock(mutex);if(id<=0||!control||!controls.emplace(id,control).second)throw std::invalid_argument("Invalid render authority registration");}
 std::shared_ptr<RenderAuthorityControl> find(int64_t id){std::lock_guard<std::mutex> lock(mutex);const auto at=controls.find(id);return at==controls.end()?nullptr:at->second;}
 bool remove(int64_t id){std::lock_guard<std::mutex> lock(mutex);const auto at=controls.find(id);if(at==controls.end())return false;at->second->close();controls.erase(at);return true;}
};
class RenderEpochSnapshot {
 std::shared_ptr<RenderAuthorityState> state;
 BenchmarkRunSnapshot run;
 RenderAuthority expected_;
 bool pinned_=false,committed_=false;
 void changed(RenderAbortBoundary boundary){
  std::lock_guard<std::mutex> lock(state->mutex);const auto latest=state->latest;
  if(state->closed.load(std::memory_order_acquire))throw RenderSuperseded(RenderSupersededReason::Closed,boundary,expected_.epoch,latest.epoch);
  if(latest.source!=expected_.source)throw RenderSuperseded(RenderSupersededReason::Source,boundary,expected_.epoch,latest.epoch);
  if(latest.surface!=expected_.surface)throw RenderSuperseded(RenderSupersededReason::Surface,boundary,expected_.epoch,latest.epoch);
  if(latest.epoch!=expected_.epoch&&!pinned_)throw RenderSuperseded(RenderSupersededReason::Camera,boundary,expected_.epoch,latest.epoch);
 }
public:
 RenderEpochSnapshot()=default;
 RenderEpochSnapshot(const RenderAuthorityControl& control,RenderAuthority applied,BenchmarkRunSnapshot benchmark,bool pin)
  :state(control.state),run(std::move(benchmark)),expected_(applied),pinned_(pin){if(!applied.valid())throw RenderSuperseded(RenderSupersededReason::Unbound,RenderAbortBoundary::Begin,0,control.state->latestEpoch.load());}
 bool attached()const{return bool(state);}
 bool pinned()const{return pinned_;}
 bool committed()const{return committed_;}
 const RenderAuthority& expected()const{return expected_;}
 uint64_t latestEpoch()const{return state?state->latestEpoch.load(std::memory_order_acquire):0;}
 void checkpoint(RenderAbortBoundary boundary){
  if(!state||committed_)return;run.checkpoint();
  if(state->closed.load(std::memory_order_acquire)||state->latestEpoch.load(std::memory_order_acquire)!=expected_.epoch)changed(boundary);
 }
 void publicationCommit(){
  if(!state||committed_)return;run.checkpoint();
  // Linear publication boundary. Advance remains nonblocking with respect to
  // driver work: this lock is released BEFORE any endFrame/eglSwapBuffers.
  changed(RenderAbortBoundary::Publication);committed_=true;
 }
};
inline thread_local RenderEpochSnapshot* activeRenderEpoch=nullptr;
#ifndef __ANDROID__
inline thread_local std::function<void(RenderAbortBoundary)> renderAbortTestHook;
#endif
inline void normalRenderCheckpoint(RenderAbortBoundary at){
 if(!activeRenderEpoch)return;
#ifndef __ANDROID__
 if(renderAbortTestHook)renderAbortTestHook(at);
#endif
 if(at==RenderAbortBoundary::Publication)activeRenderEpoch->publicationCommit();else activeRenderEpoch->checkpoint(at);
}
class RenderEpochScope {
 RenderEpochSnapshot* previous;
public:
 explicit RenderEpochScope(RenderEpochSnapshot& snapshot):previous(activeRenderEpoch){activeRenderEpoch=snapshot.attached()?&snapshot:nullptr;}
 ~RenderEpochScope(){activeRenderEpoch=previous;}
};
struct NormalAbortEvent {uint64_t expected=0,latest=0;RenderAbortBoundary boundary=RenderAbortBoundary::Begin;RenderSupersededReason reason=RenderSupersededReason::Camera;bool full=false,pinned=false;};
// Owner state only. This independent candidate is DEFAULT_OFF and never an
// implicit fourth member of the existing OFF/SOUNDINGS/SPATIAL/GPU/ALL set.
class NormalAbortRun {
 std::string id_;bool captured_=false,active_=false,original_=false;
 unsigned cameraAborts_=0;size_t eventCount_=0,eventNext_=0;
 uint64_t aborted_=0,completed_=0,pinnedCompleted_=0,totalEvents_=0;
 std::array<uint64_t,5> reasonCounts_{};
 std::array<NormalAbortEvent,64> events_{};
 void record(NormalAbortEvent e){events_[eventNext_]=e;eventNext_=(eventNext_+1)%events_.size();eventCount_=std::min(eventCount_+1,events_.size());++totalEvents_;}
public:
 static constexpr unsigned MaxCameraAborts=2;
 bool configure(const BenchmarkRunControl& control,const std::string& run,bool requested,bool current,bool& next){
  if(run.empty()||run.size()>128||!control.owns(run))return false;control.snapshotOwned(run).checkpoint();
  if(active_&&id_!=run)return false;
  if(!active_){id_=run;original_=current;captured_=active_=true;cameraAborts_=0;eventCount_=eventNext_=0;events_={};aborted_=completed_=pinnedCompleted_=totalEvents_=0;reasonCounts_={};}
  next=requested;return true;
 }
 bool restore(const std::string& run,bool& next){if(!captured_||id_!=run)return false;if(active_){next=original_;active_=false;cameraAborts_=0;}return true;}
 bool reconcile(const BenchmarkRunControl& control,bool& next){if(!active_)return false;if(!control.owns(id_)){restore(id_,next);return true;}try{control.snapshot().checkpoint();}catch(const BenchmarkRunCancelled&){restore(id_,next);return true;}return false;}
 RenderEpochSnapshot begin(const BenchmarkRunControl& control,const RenderAuthorityControl& authority,RenderAuthority applied,bool enabled){
  if(!enabled)return {};if(!active_||!control.owns(id_))throw std::logic_error("Normal abort requires its active diagnostic run");return {authority,applied,control.snapshotOwned(id_),cameraAborts_>=MaxCameraAborts};
 }
 void superseded(const RenderSuperseded& failure,bool pinned){++aborted_;++reasonCounts_[size_t(failure.reason)];if(failure.reason==RenderSupersededReason::Camera)cameraAborts_=std::min(cameraAborts_+1,MaxCameraAborts);else cameraAborts_=0;record({failure.expected,failure.latest,failure.boundary,failure.reason,false,pinned});}
 void completed(const RenderEpochSnapshot& snapshot){if(!snapshot.attached())return;if(!snapshot.committed())throw std::logic_error("Normal-abort full completion without publication commit");++completed_;if(snapshot.pinned())++pinnedCompleted_;cameraAborts_=0;record({snapshot.expected().epoch,snapshot.latestEpoch(),RenderAbortBoundary::Publication,RenderSupersededReason::Camera,true,snapshot.pinned()});}
 bool active()const{return active_;}bool captured()const{return captured_;}bool original()const{return original_;}unsigned consecutiveCameraAborts()const{return cameraAborts_;}
 const std::string& id()const{return id_;}size_t eventCount()const{return eventCount_;}
 uint64_t abortedCount()const{return aborted_;}uint64_t completedCount()const{return completed_;}uint64_t pinnedCompletedCount()const{return pinnedCompleted_;}uint64_t droppedEvents()const{return totalEvents_-eventCount_;}uint64_t reasonCount(RenderSupersededReason reason)const{return reasonCounts_[size_t(reason)];}
 const NormalAbortEvent& event(size_t i)const{if(i>=eventCount_)throw std::out_of_range("Normal abort event");return events_[(eventNext_+events_.size()-eventCount_+i)%events_.size()];}
};
}
