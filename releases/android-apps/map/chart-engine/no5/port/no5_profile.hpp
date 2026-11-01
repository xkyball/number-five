#pragma once
#include "../detail_selection.hpp"
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <time.h>
namespace no5 {
// Wall != GPU time. Thread CPU includes driver CPU but excludes thread waits.
// Top-level phases partition a frame; detailed samples are nested/non-additive.
enum class RenderStage { Prepare, FrameSetup, Areas, AreaEffect, Land, LinePriority, Objects, Overlays, Present, Swap, CoverageKey, CoverageBuild, CoverageUpload, PortrayalBounds, QueryEligibility, Count };
enum class RenderDetail { RuleReset, Selection, Visibility, Conditional, Tessellation, Text, Symbols, Soundings, SimpleLines, DashedLines, ComplexLines, SolidAreas, PatternAreas, FilterCount, GlDraw, GlState, GlQueries, GlErrors, GlUploads, DashSetup, DashSegments, DashSubmit, TextLayout, TextTexture, TextSubmit, AreaTarget, AreaDraw, AreaActiveTexture, AreaInputBind, AreaInputFilter, AreaOutputCreate, AreaOutputBind, AreaOutputStorage, Count };
inline constexpr const char* stageNames[]={"prepare","FBO setup","areas","S01/R04 area treatment","land presentation","line priorities","objects","overlays","presentation","swap","coverage key lookup","coverage build","coverage stencil upload","current portrayal bounds","completed query eligibility"};
inline constexpr const char* detailNames[]={"rule reset","selection/order","visibility/rules","conditional rules","deferred tessellation","text","symbols","soundings","simple lines","dashed lines","complex lines","solid areas","pattern areas","filter count","GL draws","GL state/uniforms","GL queries","GL error queries","texture uploads","dash setup","dash projection/clip/loop","dash GL submit","text layout/overlap","text texture creation (including upload)","text quad GL submit","S01 target setup","S01 shader submission","S01 active texture","S01 input texture bind","S01 input sampling filter","S01 output object creation","S01 output framebuffer bind","S01 output storage/attachment"};
struct ProfileValue {double wallMs=0,cpuMs=0;std::uint64_t calls=0;};
// Reset with each frame. These are bounded CPU work/transfer sizes, not GPU
// timings. Only the outer GLES coverage check is counted here; the repeated
// preparation/query key check retains its existing enclosing phase.
struct CoverageProfileWork {
 std::uint64_t keyLookups=0,keyHits=0,builds=0,residentCells=0,rasterPixels=0,scanRows=0,ownershipPixelChecks=0,stencilUploads=0,stencilUploadBytes=0;
};
struct RenderProfile {
 std::array<ProfileValue,static_cast<std::size_t>(RenderStage::Count)> stages{};
 std::array<ProfileValue,static_cast<std::size_t>(RenderDetail::Count)> details{};
 std::uint64_t frame=0;bool detailed=false,cpuAvailable=true,detailCpuAvailable=false;
 std::uint64_t textureUploadBytes=0,textureAllocationBytes=0,bufferUploadBytes=0,bufferAllocationBytes=0,uploadCalls=0,unknownUploadSizes=0,submittedVertices=0;
 // Counts valid secondary labels; absent mandatory attributes produce no label.
 std::uint64_t secondaryTextCacheHits=0,secondaryTextCacheMisses=0,textTexturesCreated=0;
 // Subset of actual total cache work performed before drawing for current extents.
 std::uint64_t portrayalSecondaryTextHits=0,portrayalSecondaryTextMisses=0,portrayalTextTexturesCreated=0;
 std::uint64_t dashSegmentsSubmitted=0,dashBatchesSubmitted=0;
 std::uint64_t areaTargetAllocations=0,areaTargetSwitches=0;
 CoverageProfileWork coverageWork{};
 int width=0,height=0;double latitude=0,longitude=0,ppm=0,rotation=0;
};
inline thread_local RenderProfile currentProfile;
inline thread_local bool profileActive=false;
inline thread_local const char* activeRenderStage="context initialization";
using ProfileClock=std::chrono::steady_clock;
inline double threadCpuMs(){timespec t{};if(clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t)!=0)return -1;return t.tv_sec*1000.0+t.tv_nsec/1e6;}
// 0 preserves release sampling; 1 captures every detail with thread CPU;
// 2 is the matched A/B baseline with detail instrumentation disabled.
inline void beginProfile(std::uint64_t frame,int detailMode=0){currentProfile={};currentProfile.frame=frame;currentProfile.detailed=detailMode==1||(detailMode==0&&(frame==1 || frame%30==0));currentProfile.detailCpuAvailable=detailMode==1;profileActive=true;
#ifndef __ANDROID__
 const char* enabled=std::getenv("NO5_RENDER_PROFILE");if(enabled&&enabled[0]=='0')profileActive=false;
#endif
}
class StageScope {
 ProfileValue* value=nullptr;ProfileClock::time_point wall;double cpu=0;
 const char* previousStage=nullptr;
public:
 explicit StageScope(RenderStage stage,bool enabled=true){if(enabled){previousStage=activeRenderStage;activeRenderStage=stageNames[static_cast<std::size_t>(stage)];if(profileActive){value=&currentProfile.stages[static_cast<std::size_t>(stage)];wall=ProfileClock::now();cpu=threadCpuMs();}}}
 void finish(){if(previousStage){activeRenderStage=previousStage;previousStage=nullptr;}if(value){double end=threadCpuMs();value->wallMs+=std::chrono::duration<double,std::milli>(ProfileClock::now()-wall).count();if(cpu>=0&&end>=cpu)value->cpuMs+=end-cpu;else currentProfile.cpuAvailable=false;++value->calls;value=nullptr;}}
 ~StageScope(){finish();}
};
class DetailScope {
 ProfileValue* value=nullptr;ProfileClock::time_point wall;double cpu=-1;
public:
 explicit DetailScope(RenderDetail detail){if(profileActive&&currentProfile.detailed){value=&currentProfile.details[static_cast<std::size_t>(detail)];wall=ProfileClock::now();if(currentProfile.detailCpuAvailable)cpu=threadCpuMs();}}
 void finish(){if(value){double end=currentProfile.detailCpuAvailable?threadCpuMs():-1;value->wallMs+=std::chrono::duration<double,std::milli>(ProfileClock::now()-wall).count();if(cpu>=0&&end>=cpu)value->cpuMs+=end-cpu;else if(currentProfile.detailCpuAvailable)currentProfile.detailCpuAvailable=false;++value->calls;value=nullptr;}}
 ~DetailScope(){finish();}
};
template<class Function> inline auto profileGl(RenderDetail bucket,Function&& call)->decltype(call()) {DetailScope scope(bucket);return call();}
}
