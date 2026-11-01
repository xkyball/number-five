#pragma once
#include <array>
#include <chrono>
#include <cstdint>
#include <memory>
#include <time.h>
namespace no5 {
enum class LandWorkPhase {Total,MaskAllocation,Raster,LandDistance,BuildingDistance,Heights,BlurX,BlurY,LightingPacking,Count};
inline constexpr const char* landWorkNames[]={"total","maskAllocation","raster","landDistanceTransform","buildingDistanceTransform","heights","blurX","blurY","lightingPacking"};
struct LandWorkTiming {double wallMs=0,cpuMs=-1;unsigned calls=0;};
// Written by the land job only; copied/exported after future.get() synchronizes.
// Render-thread geometry and wait fields use separate members, never shared phases.
struct LandWorkTelemetry {
 std::uint64_t jobId=0,captureSession=0;int width=0,height=0;std::size_t triangles=0;
 bool completed=false,produced=false,accepted=false;const char* outcome="running";
 std::array<LandWorkTiming,std::size_t(LandWorkPhase::Count)> phases{};
 LandWorkTiming geometryHarvest{},futureWait{};
};
inline double landThreadCpuMs() { timespec t{};return clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t)==0?t.tv_sec*1000.0+t.tv_nsec/1e6:-1; }
class LandWorkScope {
 LandWorkTiming* timing=nullptr;std::chrono::steady_clock::time_point started{};double cpu=-1;
public:
 explicit LandWorkScope(LandWorkTiming* value):timing(value){if(timing){started=std::chrono::steady_clock::now();cpu=landThreadCpuMs();}}
 LandWorkScope(const std::shared_ptr<LandWorkTelemetry>& value,LandWorkPhase phase):LandWorkScope(value?&value->phases[std::size_t(phase)]:nullptr){}
 ~LandWorkScope(){if(!timing)return;auto end=landThreadCpuMs();timing->wallMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
  if(cpu>=0&&end>=cpu&&(timing->calls==0||timing->cpuMs>=0)){if(timing->calls==0)timing->cpuMs=0;timing->cpuMs+=end-cpu;}else timing->cpuMs=-1;timing->calls++;}
};
}
