#include "chart.hpp"
#include "gles.hpp"
#include "outlines.hpp"
#include <EGL/eglext.h>
#include "port/senc_cache.hpp"
#include "port/cell_metadata.hpp"
#include "port/no5_area_gpu.hpp"
#include "viewport_reuse_run.hpp"
#include "viewport_reuse_parity.hpp"
#include "current_live_parity.hpp"
#include "render_epoch.hpp"
#include <jni.h>
#include <android/native_window_jni.h>
#include <mutex>
#include <unordered_map>
#include <atomic>
#include <charconv>
#include <thread>
#include <sstream>
using namespace no5;
namespace {
struct Engine {
 std::shared_ptr<BenchmarkRunControl> benchmarkControl=std::make_shared<BenchmarkRunControl>();
 std::shared_ptr<RenderAuthorityControl> renderAuthority=std::make_shared<RenderAuthorityControl>();
 GlesCompositor compositor; No5ChartRenderer chart;
 const std::thread::id ownerThread=std::this_thread::get_id();
 ViewportReuseRun viewportReuse;
 ViewportParityFrame completedParityFrame;
 CurrentLiveParityCapture currentLiveParity;
 uint64_t overlayRevision=0;
 NormalAbortRun normalAbort;
 bool normalAbortEnabled=false;
 RenderAuthority appliedRenderAuthority;
 ViewportParityFrame appliedRenderView;
 int canonicalWidth=0,canonicalHeight=0;
 bool rasterReserve=false;
 explicit Engine(const std::string& d):chart(d){chart.benchmarkRunControl=benchmarkControl;}
 void requireDiagnosticOwner()const{if(std::this_thread::get_id()!=ownerThread)throw std::logic_error("Viewport diagnostic requires native owner thread");}
 ViewportReuseFlags reuseFlags()const{return {chart.renderOptimizations.soundingsPreparation,chart.renderOptimizations.spatialAreas,areaGpuEnabled()};}
 void applyReuseFlags(ViewportReuseFlags flags){chart.renderOptimizations.soundingsPreparation=flags.soundings;chart.renderOptimizations.spatialAreas=flags.spatial;setAreaGpuEnabled(flags.gpu);}
 void reconcileReuse(){requireDiagnosticOwner();auto flags=reuseFlags();if(viewportReuse.reconcile(*benchmarkControl,flags))applyReuseFlags(flags);}
 void reconcileNormalAbort(){requireDiagnosticOwner();normalAbort.reconcile(*benchmarkControl,normalAbortEnabled);}
 void abandonNormalAbort(){requireDiagnosticOwner();if(!normalAbort.active())return;benchmarkControl->cancel(normalAbort.id(),"NORMAL_ABORT_OWNER_REVOKED");normalAbort.restore(normalAbort.id(),normalAbortEnabled);}
 bool configureNormalAbort(const std::string& run,bool enabled){requireDiagnosticOwner();reconcileNormalAbort();return normalAbort.configure(*benchmarkControl,run,enabled,normalAbortEnabled,normalAbortEnabled);}
 bool restoreNormalAbort(const std::string& run){requireDiagnosticOwner();if(benchmarkControl->owns(run))return false;return normalAbort.restore(run,normalAbortEnabled)||!normalAbort.captured();}
 bool bindRenderAuthority(RenderAuthority authority,double lat,double lon,double ppm,double rotation,int w,int h){
  requireDiagnosticOwner();if(!renderAuthority->canBindApplied(authority)||chart.view.clat!=lat||chart.view.clon!=lon||chart.view.view_scale_ppm!=ppm||chart.view.rotation!=rotation||chart.view.pix_width!=w||chart.view.pix_height!=h)return false;
  const auto current=viewportParityFrame(chart,compositor,overlayRevision);
  if(appliedRenderView.valid&&appliedRenderAuthority.epoch==authority.epoch){
   auto previous=appliedRenderView;previous.frame=current.frame;previous.overlayRevision=current.overlayRevision;previous.slot=current.slot;
   // Repeating the same owner apply is harmless; the same epoch may never
   // relabel a different camera/data tuple, even when a caller missed an epoch.
   return appliedRenderAuthority==authority&&previous==current;
  }
  appliedRenderAuthority=authority;appliedRenderView=current;return appliedRenderView.valid;
 }
 std::string normalAbortReport(const std::string& run){
  requireDiagnosticOwner();if(run.empty()||run.size()>128)throw std::invalid_argument("Invalid normal abort run ID");reconcileNormalAbort();std::ostringstream out;
  out<<"{\"schema\":\"no5.normal-render-abort/1\",\"runId\":\""<<jsonEscape(run)<<"\",\"available\":";
  if(!normalAbort.captured()||normalAbort.id()!=run){out<<"false}";return out.str();}
  out<<"true,\"active\":"<<(normalAbort.active()?"true":"false")<<",\"enabled\":"<<(normalAbortEnabled?"true":"false")<<",\"originalEnabled\":"<<(normalAbort.original()?"true":"false")<<",\"defaultEnabled\":false,\"maxCameraAborts\":"<<NormalAbortRun::MaxCameraAborts<<",\"consecutiveCameraAborts\":"<<normalAbort.consecutiveCameraAborts()<<",\"abortedCount\":"<<normalAbort.abortedCount()<<",\"completedCount\":"<<normalAbort.completedCount()<<",\"pinnedCompletedCount\":"<<normalAbort.pinnedCompletedCount()<<",\"eventLimit\":64,\"droppedEvents\":"<<normalAbort.droppedEvents()<<",\"reasonCounts\":{";
  for(size_t i=0;i<5;i++){if(i)out<<',';out<<'"'<<renderSupersededReasonNames[i]<<"\":"<<normalAbort.reasonCount(RenderSupersededReason(i));}out<<"},\"events\":[";
  for(size_t i=0;i<normalAbort.eventCount();i++){if(i)out<<',';const auto& e=normalAbort.event(i);out<<"{\"expectedEpoch\":"<<e.expected<<",\"latestEpoch\":"<<e.latest<<",\"boundary\":\""<<renderAbortBoundaryNames[size_t(e.boundary)]<<"\",\"reason\":\""<<(e.full?"FULL_COMPLETE":renderSupersededReasonNames[size_t(e.reason)])<<"\",\"full\":"<<(e.full?"true":"false")<<",\"cameraPinned\":"<<(e.pinned?"true":"false")<<'}';}
  out<<"],\"starvationScope\":\"at most two camera-only aborts then one finite native full completion while source/surface remain stable; no driver/parser preemption or consumer/display bound\"}";return out.str();
 }
 void abandonReuse(const char* reason){
  requireDiagnosticOwner();if(!viewportReuse.active())return;
  benchmarkControl->cancel(viewportReuse.id(),reason);
  auto flags=reuseFlags();if(viewportReuse.restore(viewportReuse.id(),flags,reason))applyReuseFlags(flags);
 }
 bool configureReuse(const std::string& run,const std::string& variant){
  requireDiagnosticOwner();reconcileReuse();auto flags=reuseFlags();
  if(!viewportReuse.configure(*benchmarkControl,run,variant,flags,flags))return false;
  applyReuseFlags(flags);return true;
 }
 bool restoreReuse(const std::string& run){
  requireDiagnosticOwner();if(benchmarkControl->owns(run))return false;
  auto flags=reuseFlags();if(!viewportReuse.restore(run,flags,"EXPLICIT_RUN_RESTORE"))return !viewportReuse.captured();
  applyReuseFlags(flags);return true;
 }
 std::string reuseReport(const std::string& run){
  requireDiagnosticOwner();if(run.empty()||run.size()>128)throw std::invalid_argument("Invalid viewport diagnostic run ID");reconcileReuse();std::ostringstream out;
  out<<"{\"schema\":\"no5.viewport-reuse-diagnostic/1\",\"runId\":\""<<jsonEscape(run)<<"\",\"available\":";
  if(!viewportReuse.captured()||viewportReuse.id()!=run){out<<"false}";return out.str();}
  auto flags=[&](ViewportReuseFlags f){out<<"{\"soundings\":"<<(f.soundings?"true":"false")<<",\"spatial\":"<<(f.spatial?"true":"false")<<",\"gpu\":"<<(f.gpu?"true":"false")<<"}";};
  out<<"true,\"active\":"<<(viewportReuse.active()?"true":"false")<<",\"variant\":\""<<viewportReuse.variant()<<"\",\"restorationReason\":\""<<viewportReuse.reason()<<"\",\"original\":";flags(viewportReuse.original());out<<",\"current\":";flags(reuseFlags());
  const auto gpu=areaGpuCounters();out<<",\"soundingPreparation\":"<<chart.soundingPreparationJson()<<",\"spatialWorkset\":"<<chart.areaWorksetJson()
     <<",\"stationaryFrameCache\":{\"hits\":"<<chart.staticChartReuseStats.hits<<",\"misses\":"<<chart.staticChartReuseStats.misses<<",\"stores\":"<<chart.staticChartReuseStats.stores<<"}"
     <<",\"areaGpu\":{\"hits\":"<<gpu.hits<<",\"misses\":"<<gpu.misses<<",\"retainedBytes\":"<<gpu.retainedBytes<<",\"vertexUploadBytes\":"<<gpu.vertexUploadBytes<<",\"indexUploadBytes\":"<<gpu.indexUploadBytes<<",\"fallbacks\":"<<gpu.fallbacks<<",\"evictions\":"<<gpu.evictions<<"}}";return out.str();
 }
 void failParity(const std::string& run){requireDiagnosticOwner();if(viewportReuse.active()&&viewportReuse.id()==run)abandonReuse("PARITY_CAPTURE_FAILED");}
 std::string captureParity(const ViewportParityRequest& request){
  requireDiagnosticOwner();
  try{return captureViewportReuseParity(chart,compositor,*benchmarkControl,viewportReuse,ownerThread,request,completedParityFrame,overlayRevision);}
  catch(...){failParity(request.runId);throw;}
 }
 void requireCurrentLiveParityMode(const std::string& run,bool normal,bool full=false)const{
  requireDiagnosticOwner();
  if(!benchmarkControl->owns(run)||viewportReuse.active()||rasterReserve||!compositor.attached()||
     compositor.presentationSlots()!=2||canonicalWidth<1||canonicalHeight<1||!compositor.swapsEnabled()||
     compositor.wantsAreaReuse()||compositor.wantsBaseAreas()||normalAbortEnabled!=normal||
     (normal&&(!normalAbort.active()||normalAbort.id()!=run)))
   throw std::logic_error("Current-live parity requires its existing canonical CONTROL FULL run");
  benchmarkControl->snapshotOwned(run).checkpoint();
  if(full&&normal&&(!(currentLiveParity.applied()==appliedRenderAuthority)||
     !(renderAuthority->current()==currentLiveParity.applied())))
   throw std::logic_error("Current-live parity applied/current render authority changed");
 }
 void requireCurrentLiveParitySource(const std::string& catalog,uint64_t content,uint64_t data)const{
  const auto resident=chart.residentStatus();
  if(!resident.enabled||!resident.complete||resident.catalogGeneration!=catalog||resident.contentRevision!=content||
     chart.diagnosticRevisions()[0]!=data||chart.presentationPending())
   throw std::logic_error("Current-live parity source freeze changed or is incomplete");
 }
 void beginCurrentLiveParity(const std::string& run,const std::string& capture,bool normal,const std::string& catalog,
   uint64_t content,uint64_t data,uint64_t source,uint64_t surface,uint64_t registry){
  requireCurrentLiveParityMode(run,normal);requireCurrentLiveParitySource(catalog,content,data);
  currentLiveParity.begin(*benchmarkControl,run,capture,normal,catalog,content,data,source,surface,registry);
 }
 std::string captureCurrentLiveParity(const std::string& run,const std::string& capture,uint64_t stamp,uint64_t frame,
   uint64_t camera,uint64_t registry,double lat,double lon,double ppm,double rotation,int width,int height,int slot){
  requireDiagnosticOwner();
  try{
   requireCurrentLiveParitySource(currentLiveParity.catalog(),currentLiveParity.contentRevision(),currentLiveParity.dataGeneration());
   auto checkpoint=[&]{requireCurrentLiveParityMode(run,currentLiveParity.expectedNormal(),true);};
   const auto result=currentLiveParity.capture(chart,compositor,*benchmarkControl,ownerThread,run,capture,stamp,frame,camera,registry,
     lat,lon,ppm,rotation,width,height,slot,overlayRevision,checkpoint);
   requireCurrentLiveParitySource(currentLiveParity.catalog(),currentLiveParity.contentRevision(),currentLiveParity.dataGeneration());
   return result;
  }catch(...){currentLiveParity.failOwned(run,capture);throw;}
 }
 void rememberCurrentLiveFull(uint64_t stamp,uint64_t registry){
  if(currentLiveParity.active())currentLiveParity.fullCompleted(stamp,viewportParityFrame(chart,compositor,overlayRevision),
    normalAbortEnabled,appliedRenderAuthority,registry);
 }
 void detach(){completedParityFrame.valid=false;currentLiveParity.reset();appliedRenderView.valid=false;abandonNormalAbort();abandonReuse("CONTEXT_DETACHED");if(compositor.attached()){compositor.activate();stopStartupGlTrace();chart.releaseGlResources();}compositor.detach();canonicalWidth=canonicalHeight=0;rasterReserve=false;}
 void render(bool authorityResult=false,uint64_t expectedAppliedEpoch=0,uint64_t taggedStamp=0,uint64_t taggedRegistry=0){
  completedParityFrame.valid=false;
  currentLiveParity.invalidateFrame();
  reconcileReuse();reconcileNormalAbort();
  if(authorityResult&&!normalAbortEnabled)throw BenchmarkRunCancelled("Normal-abort run ended before render");
  if(normalAbortEnabled&&!authorityResult)throw std::logic_error("Normal abort requires typed render outcome API");
  const auto beforeFrame=compositor.completedFrameId();
  auto epoch=RenderEpochSnapshot{};
  try{
  compositor.activate();const auto fit=compositor.fittedGeometry();
  const int w=canonicalWidth?canonicalWidth:fit.frameWidth,h=canonicalHeight?canonicalHeight:fit.frameHeight;
  if(chart.view.pix_width!=w||chart.view.pix_height!=h)chart.viewport(chart.view.clat,chart.view.clon,chart.view.view_scale_ppm,chart.view.rotation,w,h);
  if(normalAbortEnabled){
   if(rasterReserve||expectedAppliedEpoch!=appliedRenderAuthority.epoch||!appliedRenderView.valid)throw RenderSuperseded(RenderSupersededReason::Unbound,RenderAbortBoundary::Begin,expectedAppliedEpoch,renderAuthority->current().epoch);
   const auto current=viewportParityFrame(chart,compositor,overlayRevision);auto bound=appliedRenderView;bound.frame=current.frame;bound.overlayRevision=current.overlayRevision;bound.slot=current.slot;
   if(!(bound==current))throw RenderSuperseded(RenderSupersededReason::Source,RenderAbortBoundary::Begin,appliedRenderAuthority.epoch,renderAuthority->current().epoch);
   epoch=normalAbort.begin(*benchmarkControl,*renderAuthority,appliedRenderAuthority,normalAbortEnabled);
  }
  RenderEpochScope epochScope(epoch);
  if(rasterReserve)chart.renderGlRaster(compositor,fit.frameWidth,fit.frameHeight);else chart.renderGl(compositor);
  normalAbort.completed(epoch);
  reconcileReuse();
  if(viewportReuse.active())completedParityFrame=viewportParityFrame(chart,compositor,overlayRevision);
  if(compositor.completedFrameId()!=beforeFrame)rememberCurrentLiveFull(taggedStamp,taggedRegistry);
  }catch(const RenderSuperseded& failure){completedParityFrame.valid=false;compositor.abandonFrame();normalAbort.superseded(failure,epoch.pinned());throw;}
  catch(const BenchmarkRunCancelled&){
   if(epoch.attached()&&epoch.committed()&&compositor.completedFrameId()!=beforeFrame){
    // The post-endFrame checkpoint may be the first observer of a stop. Keep
    // that actual native FULL, still under its original applied authority;
    // the exception propagates and does not manufacture an App acknowledgement.
    normalAbort.completed(epoch);
    if(viewportReuse.active())completedParityFrame=viewportParityFrame(chart,compositor,overlayRevision);
    rememberCurrentLiveFull(taggedStamp,taggedRegistry);
   }else if(epoch.attached()&&!epoch.committed())compositor.abandonFrame();
   abandonNormalAbort();abandonReuse("NATIVE_FRAME_CANCELLED");throw;
  }
  catch(...){abandonNormalAbort();abandonReuse("NATIVE_FRAME_FAILED");throw;}
 }
 ~Engine(){try{detach();}catch(...){/* EGL teardown still runs after chart destruction. */}}
};
std::mutex mutex;
BenchmarkRunRegistry benchmarkControls;
RenderAuthorityRegistry renderAuthorities;
std::unordered_map<jlong,std::unique_ptr<Engine>> engines;
jlong nextId=1;
std::map<jlong,std::shared_ptr<const PresentationPacket>> preparedProfiles;
jlong nextProfile=1;
std::string str(JNIEnv* env,jstring s){if(!s)throw std::invalid_argument("null string");const char* v=env->GetStringUTFChars(s,nullptr);if(!v)throw std::runtime_error("UTF8 allocation failed");std::string out(v);env->ReleaseStringUTFChars(s,v);return out;}
Engine& get(jlong id){auto i=engines.find(id);if(i==engines.end())throw std::invalid_argument("Engine is closed");return *i->second;}
void error(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
std::vector<std::string> strings(JNIEnv* env,jobjectArray array){
 if(!array)throw std::invalid_argument("Null resident array");const auto n=env->GetArrayLength(array);
 if(n>20000)throw std::invalid_argument("Resident array too large");std::vector<std::string> result;result.reserve(n);
 for(int i=0;i<n;++i){auto item=static_cast<jstring>(env->GetObjectArrayElement(array,i));try{result.push_back(str(env,item));}catch(...){if(item)env->DeleteLocalRef(item);throw;}env->DeleteLocalRef(item);}return result;
}
int integer(const std::string& text){int value=0;auto result=std::from_chars(text.data(),text.data()+text.size(),value);if(result.ec!=std::errc{}||result.ptr!=text.data()+text.size())throw std::invalid_argument("Invalid resident integer");return value;}
std::vector<ResidentSource> residentSources(JNIEnv* env,jobjectArray sources,jint cells,jlong bytes,jlong loadingBytes){
 if(!sources||cells<1||bytes<=0||loadingBytes<=0)throw std::invalid_argument("Invalid resident catalog transport");
 const auto count=env->GetArrayLength(sources);if(count>20000)throw std::invalid_argument("Resident catalog too large");std::vector<ResidentSource> catalog;catalog.reserve(count);
 for(int i=0;i<count;++i){auto row=static_cast<jobjectArray>(env->GetObjectArrayElement(sources,i));std::vector<std::string> fields;try{fields=strings(env,row);}catch(...){if(row)env->DeleteLocalRef(row);throw;}env->DeleteLocalRef(row);
  if(fields.size()<8||(fields.size()-6)%2)throw std::invalid_argument("Invalid resident source transport");ResidentSource source;source.provider=fields[0];source.name=fields[1];source.sourceKey=fields[2];source.edition=integer(fields[3]);source.update=integer(fields[4]);source.order=integer(fields[5]);size_t offset=6;if(source.provider=="no5-pi-map-cache-v1"){if(fields.size()!=12)throw std::invalid_argument("Invalid private resident source transport");source.product=fields[6];source.licenseHost=fields[7];offset=8;}for(size_t j=offset;j<fields.size();j+=2)source.files.push_back({fields[j],fields[j+1]});catalog.push_back(std::move(source));
 }
 return catalog;
}
void replace(JNIEnv* env,jlong id,jdoubleArray values,int which){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);jsize n=env->GetArrayLength(values);if(n%3)throw std::invalid_argument("projection must be triples");std::vector<double> v(n);env->GetDoubleArrayRegion(values,0,n,v.data());std::vector<Projection> p;for(int i=0;i<n;i+=3){if(!std::isfinite(v[i])||!std::isfinite(v[i+1])||!std::isfinite(v[i+2])||abs(v[i])>85||abs(v[i+1])>180)throw std::invalid_argument("projection position");p.push_back({v[i],v[i+1],v[i+2]});}auto* dst=which==0?&e.chart.ownship:which==1?&e.chart.ais:which==2?&e.chart.route:&e.chart.waypoints;*dst=std::move(p);++e.overlayRevision;}catch(const std::exception& e){error(env,e);}}
}
#define API(name) Java_com_no5_helm_engine_NativeBridge_##name
extern "C" JNIEXPORT jlong JNICALL API(engineCreate)(JNIEnv* env,jobject,jstring data){try{std::lock_guard<std::mutex> lock(mutex);jlong id=nextId++;auto engine=std::make_unique<Engine>(str(env,data));auto control=engine->benchmarkControl;auto authority=engine->renderAuthority;engines.emplace(id,std::move(engine));try{benchmarkControls.add(id,control);renderAuthorities.add(id,authority);}catch(...){benchmarkControls.remove(id);renderAuthorities.remove(id);engines.erase(id);throw;}return id;}catch(const std::exception& e){error(env,e);return 0;}}
extern "C" JNIEXPORT void JNICALL API(engineDestroy)(JNIEnv*,jobject,jlong id){renderAuthorities.remove(id);benchmarkControls.remove(id);std::lock_guard<std::mutex> lock(mutex);engines.erase(id);}
// These methods never acquire the renderer mutex or touch an Engine/GL object.
// In particular CANCEL can signal a run while chartSetOpen/renderRequest holds
// that mutex. A blocked driver/parser call remains cooperatively bounded only.
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkRunBegin)(JNIEnv* env,jobject,jlong id,jstring runId,jlong deadline){try{auto control=benchmarkControls.find(id);return control&&control->begin(str(env,runId),deadline);}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkRunCancel)(JNIEnv* env,jobject,jlong id,jstring expectedRunId,jstring reason){try{auto control=benchmarkControls.find(id);return control&&control->cancel(str(env,expectedRunId),str(env,reason));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkRunEnd)(JNIEnv* env,jobject,jlong id,jstring expectedRunId){try{auto control=benchmarkControls.find(id);return control&&control->end(str(env,expectedRunId));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(renderAuthorityAdvance)(JNIEnv*,jobject,jlong id,jlong epoch,jlong camera,jlong source,jlong surface){try{if(epoch<=0||camera<0||source<0||surface<0)return false;auto control=renderAuthorities.find(id);return control&&control->advance({uint64_t(epoch),uint64_t(camera),uint64_t(source),uint64_t(surface)});}catch(...){return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(renderAuthorityApply)(JNIEnv* env,jobject,jlong id,jlong epoch,jlong camera,jlong source,jlong surface,jdouble lat,jdouble lon,jdouble ppm,jdouble rotation,jint width,jint height){try{if(epoch<=0||camera<0||source<0||surface<0)return false;std::lock_guard<std::mutex> lock(mutex);return get(id).bindRenderAuthority({uint64_t(epoch),uint64_t(camera),uint64_t(source),uint64_t(surface)},lat,lon,ppm,rotation,width,height);}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkNormalAbortConfigure)(JNIEnv* env,jobject,jlong id,jstring run,jboolean enabled){try{std::lock_guard<std::mutex> lock(mutex);return get(id).configureNormalAbort(str(env,run),enabled);}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jstring JNICALL API(benchmarkNormalAbortRead)(JNIEnv* env,jobject,jlong id,jstring run){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).normalAbortReport(str(env,run)).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkNormalAbortRestore)(JNIEnv* env,jobject,jlong id,jstring run){try{std::lock_guard<std::mutex> lock(mutex);return get(id).restoreNormalAbort(str(env,run));}catch(const std::exception& e){error(env,e);return false;}}
// Debug-only callers configure/read/restore on the renderer owner. Unlike the
// cancellation signal above, these access chart state under its existing lock.
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkPreparationConfigure)(JNIEnv* env,jobject,jlong id,jstring expectedRunId,jint workers){try{std::lock_guard<std::mutex> lock(mutex);return get(id).chart.benchmarkPreparationConfigure(str(env,expectedRunId),unsigned(workers));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jstring JNICALL API(benchmarkPreparationRead)(JNIEnv* env,jobject,jlong id,jstring expectedRunId){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.benchmarkPreparationRead(str(env,expectedRunId)).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkPreparationRestore)(JNIEnv* env,jobject,jlong id,jstring expectedRunId){try{std::lock_guard<std::mutex> lock(mutex);return get(id).chart.benchmarkPreparationRestore(str(env,expectedRunId));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkViewportReuseConfigure)(JNIEnv* env,jobject,jlong id,jstring run,jstring variant){try{std::lock_guard<std::mutex> lock(mutex);return get(id).configureReuse(str(env,run),str(env,variant));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jstring JNICALL API(benchmarkViewportReuseRead)(JNIEnv* env,jobject,jlong id,jstring run){try{std::lock_guard<std::mutex> lock(mutex);const auto json=get(id).reuseReport(str(env,run));return env->NewStringUTF(json.c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkViewportReuseRestore)(JNIEnv* env,jobject,jlong id,jstring run){try{std::lock_guard<std::mutex> lock(mutex);return get(id).restoreReuse(str(env,run));}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jstring JNICALL API(benchmarkViewportReuseCapture)(JNIEnv* env,jobject,jlong id,jstring run,jlong frame,jstring catalog,jlong contentRevision,jlong dataGeneration,jdouble lat,jdouble lon,jdouble ppm,jdouble rotation,jint width,jint height){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);const auto expectedRun=str(env,run);
 try{if(frame<=0||contentRevision<0||dataGeneration<0)throw std::invalid_argument("Invalid parity revision transport");
  const ViewportParityRequest request{expectedRun,str(env,catalog),uint64_t(frame),uint64_t(contentRevision),uint64_t(dataGeneration),lat,lon,ppm,rotation,width,height};
  const auto json=e.captureParity(request);auto result=env->NewStringUTF(json.c_str());if(!result)e.failParity(expectedRun);return result;
 }catch(...){e.failParity(expectedRun);throw;}
 }catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkCurrentLiveParityBegin)(JNIEnv* env,jobject,jlong id,jstring run,jstring capture,jboolean normal,jstring catalog,jlong content,jlong data,jlong source,jlong surface,jlong registry){try{
 if(content<0||data<0||source<0||surface<0||registry<=0)throw std::invalid_argument("Invalid current-live parity source/owner transport");
 std::lock_guard<std::mutex> lock(mutex);get(id).beginCurrentLiveParity(str(env,run),str(env,capture),normal==JNI_TRUE,str(env,catalog),
   uint64_t(content),uint64_t(data),uint64_t(source),uint64_t(surface),uint64_t(registry));return true;
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jstring JNICALL API(benchmarkCurrentLiveParityCapture)(JNIEnv* env,jobject,jlong id,jstring run,jstring capture,
 jlong stamp,jlong frame,jlong camera,jlong registry,jdouble lat,jdouble lon,jdouble ppm,jdouble rotation,jint width,jint height,jint slot){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 const auto expectedRun=str(env,run),expectedCapture=str(env,capture);
 try{
  if(stamp<=0||frame<=0||camera<0||registry<=0||slot<0||slot>1)throw std::invalid_argument("Invalid current-live parity FULL transport");
  const auto json=e.captureCurrentLiveParity(expectedRun,expectedCapture,uint64_t(stamp),uint64_t(frame),uint64_t(camera),uint64_t(registry),
    lat,lon,ppm,rotation,width,height,slot);
  auto result=env->NewStringUTF(json.c_str());if(!result)e.currentLiveParity.failOwned(expectedRun,expectedCapture);return result;
 }catch(...){e.currentLiveParity.failOwned(expectedRun,expectedCapture);throw;}
 }catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(benchmarkCurrentLiveParityEnd)(JNIEnv* env,jobject,jlong id,jstring run,jstring capture){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);e.requireDiagnosticOwner();return e.currentLiveParity.end(str(env,run),str(env,capture));
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT void JNICALL API(surfaceAttach)(JNIEnv* env,jobject,jlong id,jobject surface,jint w,jint h){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);e.detach();auto window=ANativeWindow_fromSurface(env,surface);if(!window)throw std::runtime_error("Invalid native surface");e.compositor.attach(window,w,h);auto fit=e.compositor.fittedGeometry();e.chart.viewport(e.chart.view.clat,e.chart.view.clon,e.chart.view.view_scale_ppm,e.chart.view.rotation,fit.frameWidth,fit.frameHeight);}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(surfaceAttachPair)(JNIEnv* env,jobject,jlong id,jobject first,jobject second,jint w,jint h){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(!first||!second)throw std::invalid_argument("Two presentation surfaces required");
 using Window=std::unique_ptr<ANativeWindow,decltype(&ANativeWindow_release)>;
 Window firstWindow(ANativeWindow_fromSurface(env,first),&ANativeWindow_release);
 Window secondWindow(ANativeWindow_fromSurface(env,second),&ANativeWindow_release);
 if(!firstWindow||!secondWindow||firstWindow.get()==secondWindow.get())throw std::runtime_error("Two distinct native presentation surfaces required");
 e.detach();e.compositor.attachPair(firstWindow.release(),secondWindow.release(),w,h);
 auto fit=e.compositor.fittedGeometry();
 e.chart.viewport(e.chart.view.clat,e.chart.view.clon,e.chart.view.view_scale_ppm,e.chart.view.rotation,fit.frameWidth,fit.frameHeight);
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(presentationSlotSelect)(JNIEnv* env,jobject,jlong id,jint slot){try{
 std::lock_guard<std::mutex> lock(mutex);get(id).compositor.selectPresentationSlot(slot);
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(surfaceAttachPairRaster)(JNIEnv* env,jobject,jlong id,jobject first,jobject second,jint w,jint h,jint rasterW,jint rasterH){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);const auto visible=fitSurface(w,h);
 if(!first||!second||rasterW<visible.frameWidth||rasterH<visible.frameHeight||rasterW>MaxChartWidth||rasterH>MaxChartHeight)
  throw std::invalid_argument("Invalid visible/reserve surface geometry");
 using Window=std::unique_ptr<ANativeWindow,decltype(&ANativeWindow_release)>;
 Window firstWindow(ANativeWindow_fromSurface(env,first),&ANativeWindow_release),secondWindow(ANativeWindow_fromSurface(env,second),&ANativeWindow_release);
 if(!firstWindow||!secondWindow||firstWindow.get()==secondWindow.get())throw std::runtime_error("Two distinct native presentation surfaces required");
 e.detach();e.compositor.attachPair(firstWindow.release(),secondWindow.release(),rasterW,rasterH);
 e.canonicalWidth=visible.frameWidth;e.canonicalHeight=visible.frameHeight;
 e.compositor.centeredRaster(visible.frameWidth,visible.frameHeight);
 e.chart.viewport(e.chart.view.clat,e.chart.view.clon,e.chart.view.view_scale_ppm,e.chart.view.rotation,visible.frameWidth,visible.frameHeight);
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(rasterReserveSet)(JNIEnv* env,jobject,jlong id,jboolean enabled){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(enabled&&(!e.canonicalWidth||e.compositor.presentationSlots()!=2))throw std::runtime_error("Reserve needs a padded paired attachment");
 e.rasterReserve=enabled;
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(navigationBoundsRead)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.navigationBoundsJson().c_str());
 }catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT void JNICALL API(surfaceDetach)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);get(id).detach();}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(viewportSet)(JNIEnv* env,jobject,jlong id,jdouble lat,jdouble lon,jdouble ppm,jdouble angle,jint w,jint h){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(e.compositor.attached()){auto fit=e.compositor.fittedGeometry();w=e.canonicalWidth?e.canonicalWidth:fit.frameWidth;h=e.canonicalHeight?e.canonicalHeight:fit.frameHeight;}e.chart.viewport(lat,lon,ppm,angle,w,h);}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(cameraTransform)(JNIEnv* env,jobject,jlong id,jdouble ax,jdouble ay,jdouble px,jdouble py,jdouble zoom,jdouble rotation){try{std::lock_guard<std::mutex> lock(mutex);get(id).chart.transformCamera(ax,ay,px,py,zoom,rotation);}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jdoubleArray JNICALL API(projectCoordinates)(JNIEnv* env,jobject,jlong id,jdoubleArray coordinates){try{std::lock_guard<std::mutex> lock(mutex);auto& c=get(id).chart;jsize n=env->GetArrayLength(coordinates);if(n%2)throw std::invalid_argument("Coordinate pairs required");std::vector<double> v(n),out(n);env->GetDoubleArrayRegion(coordinates,0,n,v.data());for(int i=0;i<n;i+=2){if(!std::isfinite(v[i])||!std::isfinite(v[i+1])||std::abs(v[i])>85||std::abs(v[i+1])>180)throw std::invalid_argument("Invalid WGS84 coordinate");auto p=c.project(v[i],v[i+1]);out[i]=p.m_x;out[i+1]=p.m_y;}auto result=env->NewDoubleArray(n);if(result)env->SetDoubleArrayRegion(result,0,n,out.data());return result;}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jdoubleArray JNICALL API(unprojectCoordinate)(JNIEnv* env,jobject,jlong id,jdouble x,jdouble y){try{std::lock_guard<std::mutex> lock(mutex);auto p=get(id).chart.unproject(x,y);double values[]={p.latitude,p.longitude};auto result=env->NewDoubleArray(2);if(result)env->SetDoubleArrayRegion(result,0,2,values);return result;}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jdouble JNICALL API(distancePixels)(JNIEnv* env,jobject,jlong id,jdouble x0,jdouble y0,jdouble x1,jdouble y1){try{std::lock_guard<std::mutex> lock(mutex);return get(id).chart.distancePixels(x0,y0,x1,y1);}catch(const std::exception& e){error(env,e);return 0;}}
extern "C" JNIEXPORT void JNICALL API(s52SettingsSet)(JNIEnv* env,jobject,jlong id,jstring palette,jdouble safety){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);auto p=str(env,palette);if(p!="DAY"&&p!="DUSK"&&p!="NIGHT")throw std::invalid_argument("Invalid palette");if(!std::isfinite(safety)||safety<0)throw std::invalid_argument("Invalid safety contour");e.chart.settings.palette=p;e.chart.settings.safety=safety;}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(overlaysReplace)(JNIEnv* env,jobject,jlong id,jintArray counts,jdoubleArray positions,jobjectArray labels,jobjectArray ids,jdoubleArray headings,jstring role){try{
 std::lock_guard<std::mutex> lock(mutex);
 if(env->GetArrayLength(counts)!=4)throw std::invalid_argument("Overlay counts");jint n[4];env->GetIntArrayRegion(counts,0,4,n);
 int total=0;for(int value:n){if(value<0||value>5000)throw std::invalid_argument("Overlay budget");total+=value;}if(n[0]>1)throw std::invalid_argument("Ownship count");
 if(env->GetArrayLength(positions)!=total*2||env->GetArrayLength(labels)!=total||env->GetArrayLength(ids)!=total||env->GetArrayLength(headings)!=total)throw std::invalid_argument("Overlay shape");
 std::vector<double> points(total*2),directions(total);env->GetDoubleArrayRegion(positions,0,total*2,points.data());env->GetDoubleArrayRegion(headings,0,total,directions.data());
 std::vector<Projection> groups[4];int at=0;for(int group=0;group<4;group++)for(int i=0;i<n[group];i++,at++){
  const double lat=points[at*2],lon=points[at*2+1];if(!std::isfinite(lat)||!std::isfinite(lon)||std::abs(lat)>85||std::abs(lon)>180)throw std::invalid_argument("Overlay position");
  auto label=(jstring)env->GetObjectArrayElement(labels,at),name=(jstring)env->GetObjectArrayElement(ids,at);
  Projection value{lat,lon};value.label=str(env,label);value.id=str(env,name);env->DeleteLocalRef(label);env->DeleteLocalRef(name);
  if(value.label.size()>1000||value.id.size()>1000)throw std::invalid_argument("Overlay label budget");
  value.direction=std::isfinite(directions[at]);if(value.direction){if(directions[at]<0||directions[at]>=2*M_PI)throw std::invalid_argument("Overlay direction");value.heading=directions[at];}groups[group].push_back(std::move(value));
 }
 const auto mode=str(env,role);if(mode!="SAVED"&&mode!="DRAFT"&&mode!="ACTIVE")throw std::invalid_argument("Overlay route role");
 auto& engine=get(id);auto& chart=engine.chart;chart.ownship=std::move(groups[0]);chart.ais=std::move(groups[1]);chart.waypoints=std::move(groups[2]);chart.route=std::move(groups[3]);chart.routeRole=mode;++engine.overlayRevision;
}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(objectFiltersApply)(JNIEnv* env,jobject,jlong id,jstring category,jobjectArray hidden,jbooleanArray flags){try{
 std::lock_guard<std::mutex> lock(mutex);ObjectFilters value;value.category=str(env,category);
 if(env->GetArrayLength(hidden)>300||env->GetArrayLength(flags)!=7)throw std::invalid_argument("Invalid S52 filter shape");
 for(int i=0;i<env->GetArrayLength(hidden);i++){auto name=(jstring)env->GetObjectArrayElement(hidden,i);value.hidden.insert(str(env,name));env->DeleteLocalRef(name);}
 jboolean f[7];env->GetBooleanArrayRegion(flags,0,7,f);value.soundings=f[0];value.text=f[1];value.important=f[2];value.aton=f[3];value.light=f[4];value.meta=f[5];
 if(!f[6])throw std::invalid_argument("SCAMIN remains enabled");get(id).chart.applyObjectFilters(value);
}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(objectFiltersRead)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.readObjectFilters().c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(chartSetOpen)(JNIEnv* env,jobject,jlong id,jobjectArray paths){try{std::lock_guard<std::mutex> lock(mutex);std::vector<std::string> p;for(int i=0;i<env->GetArrayLength(paths);i++){auto s=(jstring)env->GetObjectArrayElement(paths,i);p.push_back(str(env,s));env->DeleteLocalRef(s);}return get(id).chart.open(p);}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(chartSetOpenWithBackground)(JNIEnv* env,jobject,jlong id,jobjectArray paths,jobjectArray background){try{
 std::lock_guard<std::mutex> lock(mutex);return get(id).chart.open(strings(env,paths),strings(env,background));
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT void JNICALL API(chartSetClose)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);get(id).chart.close();}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(chartSelectionClear)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(e.compositor.attached())e.compositor.activate();e.chart.close();
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(residentCatalogSet)(JNIEnv* env,jobject,jlong id,jstring generation,jobjectArray sources,jint cells,jlong bytes,jlong loadingBytes){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);auto catalog=residentSources(env,sources,cells,bytes,loadingBytes);
 if(e.compositor.attached())e.compositor.activate();e.chart.residentCatalog(str(env,generation),std::move(catalog),{size_t(cells),uint64_t(bytes),uint64_t(loadingBytes)});
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(residentCatalogActivate)(JNIEnv* env,jobject,jlong id,jstring generation,jobjectArray sources,jint cells,jlong bytes,jlong loadingBytes){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);auto catalog=residentSources(env,sources,cells,bytes,loadingBytes);
 if(e.compositor.attached())e.compositor.activate();e.chart.residentActivate(str(env,generation),std::move(catalog),{size_t(cells),uint64_t(bytes),uint64_t(loadingBytes)});
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jboolean JNICALL API(residentDemandSet)(JNIEnv* env,jobject,jlong id,jstring generation,jlong revision,jstring cameraKey,jstring policyKey,jobjectArray required,jobjectArray prefetch){try{
 std::lock_guard<std::mutex> lock(mutex);if(revision<=0)throw std::invalid_argument("Invalid resident camera revision");return get(id).chart.residentDemand({str(env,generation),str(env,cameraKey),str(env,policyKey),uint64_t(revision),strings(env,required),strings(env,prefetch)});
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jint JNICALL API(residentAdvance)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(e.compositor.attached())e.compositor.activate();return jint(e.chart.residentStep());
 }catch(const std::exception& e){error(env,e);return jint(ResidentStep::Failed);}}
extern "C" JNIEXPORT void JNICALL API(residentPolicySet)(JNIEnv* env,jobject,jlong id,jstring policyKey){try{
 std::lock_guard<std::mutex> lock(mutex);get(id).chart.residentPolicy(str(env,policyKey));
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(residentPolicyRestore)(JNIEnv* env,jobject,jlong id,jstring policyKey){try{
 std::lock_guard<std::mutex> lock(mutex);get(id).chart.residentPolicyRestore(str(env,policyKey));
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(residencyRead)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.residentStatus().json().c_str());
 }catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(residentRetiredRelease)(JNIEnv* env,jobject,jlong id,jstring generation){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(e.compositor.attached())e.compositor.activate();return e.chart.releaseRetiredResident(str(env,generation));
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(residentGeometryReset)(JNIEnv* env,jobject,jlong id,jstring generation,jint cells,jlong bytes,jlong loadingBytes,jboolean emptyGeometry){try{
 std::lock_guard<std::mutex> lock(mutex);if(cells<1||bytes<=0||loadingBytes<=0)throw std::invalid_argument("Invalid resident budget transport");auto& e=get(id);if(e.compositor.attached())e.compositor.activate();return e.chart.resetResidentGeometry(str(env,generation),{size_t(cells),uint64_t(bytes),uint64_t(loadingBytes)},emptyGeometry);
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jdouble JNICALL API(residentPixelsPerMillimetre)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);return get(id).chart.portrayal.GetPPMM();
 }catch(const std::exception& e){error(env,e);return 0;}}
extern "C" JNIEXPORT jdoubleArray JNICALL API(residentPlanningViewport)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);auto& view=e.chart.view;int width=view.pix_width,height=view.pix_height;
 if(e.rasterReserve&&e.compositor.attached()){const auto geometry=e.compositor.fittedGeometry();width=geometry.frameWidth;height=geometry.frameHeight;}
 const double pose[]={view.clat,view.clon,view.view_scale_ppm,view.rotation,double(width),double(height)};auto result=env->NewDoubleArray(6);if(result)env->SetDoubleArrayRegion(result,0,6,pose);return result;
 }catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT void JNICALL API(chartFileValidate)(JNIEnv* env,jobject,jlong id,jstring path,jstring cache){try{std::lock_guard<std::mutex> lock(mutex);auto& chart=get(id).chart;ChartTableEntry metadata;s57chart model;auto objects=SencCache::load(str(env,path),chart.dataDirectory,str(env,cache),metadata,model);if(objects.empty())throw std::runtime_error("S57 cell has no geometry");}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(chartFileValidateMetadata)(JNIEnv* env,jobject,jlong id,jstring path,jstring cache){try{std::lock_guard<std::mutex> lock(mutex);auto& chart=get(id).chart;auto metadata=validatePublicCellMetadata(str(env,path),chart.dataDirectory,str(env,cache));return env->NewStringUTF(metadata.json().c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL API(featureQuery)(JNIEnv* env,jobject,jlong id,jdouble x,jdouble y){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.query(x,y).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL API(objectQuery)(JNIEnv* env,jobject,jlong id,jdouble x,jdouble y,jdouble radius,jint offset){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.queryObjects(x,y,radius,offset).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(objectSelect)(JNIEnv* env,jobject,jlong id,jstring source,jint index,jdouble radius){try{std::lock_guard<std::mutex> lock(mutex);return get(id).chart.selectObject(str(env,source),index,radius);}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT void JNICALL API(projectionOwnshipReplace)(JNIEnv* env,jobject,jlong id,jdoubleArray v){replace(env,id,v,0);}
extern "C" JNIEXPORT void JNICALL API(projectionAisReplace)(JNIEnv* env,jobject,jlong id,jdoubleArray v){replace(env,id,v,1);}
extern "C" JNIEXPORT void JNICALL API(projectionRouteReplace)(JNIEnv* env,jobject,jlong id,jdoubleArray v){replace(env,id,v,2);}
extern "C" JNIEXPORT void JNICALL API(projectionWaypointsReplace)(JNIEnv* env,jobject,jlong id,jdoubleArray v){replace(env,id,v,3);}
extern "C" JNIEXPORT void JNICALL API(worldUnderlaySet)(JNIEnv* env,jobject,jlong id,jboolean enabled){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);e.requireDiagnosticOwner();
 e.chart.worldUnderlay=enabled;e.compositor.worldUnderlay(enabled);
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(renderRequest)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(e.compositor.attached()){e.compositor.activate();e.render();}}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(renderRequestWithoutPresent)(JNIEnv* env,jobject,jlong id){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(!e.compositor.attached())throw std::runtime_error("P0 Surface unavailable");
 struct RestoreSwaps{GlesCompositor& gpu;bool before;~RestoreSwaps(){gpu.enableSwaps(before);}} restore{e.compositor,e.compositor.swapsEnabled()};
 e.compositor.enableSwaps(false);e.render();
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT void JNICALL API(diagnosticMarkerSet)(JNIEnv* env,jobject,jlong id,jboolean enabled,jdouble lat,jdouble lon){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(enabled&&(!std::isfinite(lat)||!std::isfinite(lon)||abs(lat)>85||abs(lon)>180))throw std::runtime_error("Invalid public marker");
 e.chart.diagnosticMarkers.clear();if(enabled)e.chart.diagnosticMarkers.push_back({lat,lon,0});
 e.compositor.stageProbe(enabled);
 }catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(statusRead)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).chart.status().c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}

extern "C" JNIEXPORT jstring JNICALL API(rendererDiagnostics)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);return env->NewStringUTF((e.compositor.diagnostics()+"\n\nPresentation: "+e.chart.presentationStatus()).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}

extern "C" JNIEXPORT jlong JNICALL API(presentationPrepare)(JNIEnv* env,jobject,jstring data,jstring style,jstring symbols){try{auto packet=PresentationPacket::load(str(env,data),str(env,style),str(env,symbols));std::lock_guard<std::mutex> lock(mutex);if(preparedProfiles.size()>=4)throw std::runtime_error("Too many prepared presentation requests");jlong token=nextProfile++;preparedProfiles[token]=std::move(packet);return token;}catch(const std::exception& e){error(env,e);return 0;}}
extern "C" JNIEXPORT void JNICALL API(presentationDiscard)(JNIEnv*,jobject,jlong token){std::lock_guard<std::mutex> lock(mutex);preparedProfiles.erase(token);}
extern "C" JNIEXPORT jboolean JNICALL API(presentationApply)(JNIEnv* env,jobject,jlong id,jlong token,jboolean relief,jlong generation){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(!e.compositor.attached())throw std::runtime_error("Attach the chart surface before applying a presentation profile");auto it=preparedProfiles.find(token);if(it==preparedProfiles.end())throw std::invalid_argument("Unknown prepared presentation");auto packet=it->second;preparedProfiles.erase(it);e.compositor.activate();return e.chart.applyPresentationProfile(std::move(packet),relief,generation);}catch(const std::exception& e){error(env,e);return false;}}

extern "C" JNIEXPORT jstring JNICALL API(frameTimingRead)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);auto json=e.compositor.timingJson();json.pop_back();json+=",\"landWorker\":"+e.chart.landWorkJson()+"}";return env->NewStringUTF(json.c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}

extern "C" JNIEXPORT void JNICALL API(captureModeSet)(JNIEnv* env,jobject,jlong id,jint mode){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);e.compositor.captureMode(mode);e.chart.captureWork(mode==1);}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jstring JNICALL API(rendererIdentityRead)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(get(id).compositor.identityJson().c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}

extern "C" JNIEXPORT jstring JNICALL API(outlineGeometryRead)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);return env->NewStringUTF(outlineGeometry(get(id).chart).c_str());}catch(const std::exception& e){error(env,e);return nullptr;}}
extern "C" JNIEXPORT jboolean JNICALL API(presentationTimestampAvailable)(JNIEnv* env,jobject,jlong id){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(!e.compositor.attached())return false;e.compositor.activate();const auto extensions=eglQueryString(eglGetCurrentDisplay(),EGL_EXTENSIONS);return extensions&&std::string(extensions).find("EGL_ANDROID_presentation_time")!=std::string::npos&&eglGetProcAddress("eglPresentationTimeANDROID");}catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT void JNICALL API(renderRequestTagged)(JNIEnv* env,jobject,jlong id,jlong stamp){try{std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);if(!e.compositor.attached())throw std::runtime_error("Touch Surface unavailable");e.compositor.activate();using PresentTime=EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID);auto present=reinterpret_cast<PresentTime>(eglGetProcAddress("eglPresentationTimeANDROID"));if(!present||!present(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),EGLnsecsANDROID(stamp)))throw std::runtime_error("Touch buffer timestamp unavailable");e.render(false,0,stamp>0?uint64_t(stamp):0);}catch(const std::exception& e){error(env,e);}}
extern "C" JNIEXPORT jint JNICALL API(renderRequestTaggedWithAuthority)(JNIEnv* env,jobject,jlong id,jlong stamp,jlong expectedEpoch,jlong bufferGeneration,jint reservedSlot,jint frontSlot){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);e.requireDiagnosticOwner();e.reconcileNormalAbort();
 if(!e.normalAbortEnabled||!e.compositor.attached()||stamp<=0||expectedEpoch<=0||bufferGeneration<=0||e.rasterReserve)throw std::logic_error("Typed normal-abort render requires its enabled canonical full-frame run and reservation");
 const NormalRenderPresentationPermit permit{e.appliedRenderAuthority,uint64_t(bufferGeneration),uint64_t(stamp),reservedSlot,frontSlot};
 GlesCompositor::NormalPresentationScope permitScope(e.compositor,permit);
 e.compositor.activate();using PresentTime=EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID);auto present=reinterpret_cast<PresentTime>(eglGetProcAddress("eglPresentationTimeANDROID"));
 if(!present||!present(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),EGLnsecsANDROID(stamp)))throw std::runtime_error("Normal-abort buffer timestamp unavailable");
 try{e.render(true,uint64_t(expectedEpoch),uint64_t(stamp),uint64_t(bufferGeneration));return 0;}catch(const RenderSuperseded&){return 1;}
 }catch(const std::exception& e){error(env,e);return -1;}}
extern "C" JNIEXPORT jboolean JNICALL API(renderRequestAreasFirst)(JNIEnv* env,jobject,jlong id,jlong base,jlong full){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(!e.compositor.attached()||base<=0||full<=base)throw std::runtime_error("Invalid base-area publication");
 e.compositor.activate();using PresentTime=EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID);
 auto present=reinterpret_cast<PresentTime>(eglGetProcAddress("eglPresentationTimeANDROID"));
 if(!present||!present(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),EGLnsecsANDROID(full)))throw std::runtime_error("Full-stage timestamp unavailable");
 struct RestoreTags{GlesCompositor& gpu;~RestoreTags(){gpu.progressiveTags(0,0);}} restore{e.compositor};

 e.compositor.progressiveTags(base,full);e.render();return e.compositor.baseWasPublished();
 }catch(const std::exception& e){error(env,e);return false;}}
extern "C" JNIEXPORT jboolean JNICALL API(renderRequestAreasReuse)(JNIEnv* env,jobject,jlong id,jlong stamp){try{
 std::lock_guard<std::mutex> lock(mutex);auto& e=get(id);
 if(!e.compositor.attached()||stamp<=0)throw std::runtime_error("Invalid area reuse publication");
 e.compositor.activate();using PresentTime=EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID);
 auto present=reinterpret_cast<PresentTime>(eglGetProcAddress("eglPresentationTimeANDROID"));
 if(!present||!present(eglGetCurrentDisplay(),eglGetCurrentSurface(EGL_DRAW),EGLnsecsANDROID(stamp)))throw std::runtime_error("Area reuse timestamp unavailable");
 struct RestoreReuse{GlesCompositor& gpu;~RestoreReuse(){gpu.areaReuse(false);}} restore{e.compositor};

 e.compositor.areaReuse(true);e.render();return e.compositor.areaWasReused();
 }catch(const std::exception& e){error(env,e);return false;}}
#ifdef NO5_OUTLINE_PROBE
// Only compiled by the explicitly opted-in separate probe application.
#include "../../../map-outline-probe/backend/probe_jni.inc"
#endif
