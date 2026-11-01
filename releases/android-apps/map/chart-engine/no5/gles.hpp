#pragma once
#include "chart.hpp"
#include "surface_geometry.hpp"
#include "gpu_timer.hpp"
#include "render_epoch.hpp"
#include <chrono>
#ifdef __ANDROID__
#include <android/native_window.h>
#endif
#include <EGL/egl.h>
#include <GLES2/gl2.h>
namespace no5 {
struct FrameTiming {
  double submitMs=0,swapMs=0,intervalMs=0;
  bool hasInterval=false;
  double submitCpuMs=0,swapCpuMs=0;bool cpuAvailable=false;
};
// Snapshot of the producer owner's *actual*, still-unacquired reservation.
// Registry generation is deliberately not the source/surface authority domain.
struct NormalRenderPresentationPermit {
  RenderAuthority authority;
  std::uint64_t registryGeneration=0,stamp=0;
  int reservedSlot=-1,frontSlot=-1;
};
// The retained S52 GL methods draw into this framebuffer directly. No complete
// CPU chart image is allocated, copied, uploaded or read back on Android.
class GlesCompositor final : public No5RenderTarget {
  EGLDisplay display=EGL_NO_DISPLAY;
  bool displayRetained=false;
  EGLContext context=EGL_NO_CONTEXT;
  EGLConfig config=nullptr;
  // The selected output is only an alias. Both window queues share this one
  // context and all retained chart/S52 objects; selecting a queue never detaches.
  std::array<EGLSurface,2> presentationSurfaces={EGL_NO_SURFACE,EGL_NO_SURFACE};
  int presentationSurfaceCount=0,selectedPresentationSlot=0;
  EGLSurface surface=EGL_NO_SURFACE;
#ifdef __ANDROID__
  std::array<ANativeWindow*,2> windows={nullptr,nullptr};
#endif
  GLuint program=0,lineProgram=0,texture=0,framebuffer=0;
  GLuint areaTexture=0,areaFramebuffer=0,outputTexture=0,outputFramebuffer=0;
  int areaWidth=0,areaHeight=0;
  GLuint retainedChartTexture=0;
  int retainedChartWidth=0,retainedChartHeight=0;
  std::string retainedChartKey;
  bool baseNearest=false;
  bool centeredRasterOutput=false;
  int visibleRasterWidth=0,visibleRasterHeight=0;
  int width=0,height=0,frameWidth=0,frameHeight=0;
  std::string vendor,renderer,version,eglVersion,surfaceKind;
  unsigned long long presentedFrames=0,lastDrawCalls=0;
  bool surfaceSwapEnabled=true,lastSurfaceSwapSubmitted=false;
  bool completedOutputValid=false;
  bool transparentNoData=false;
  const NormalRenderPresentationPermit* normalPresentationPermit=nullptr;
  unsigned long long withheldFrames=0;
  bool stageProbeEnabled=false,baseAreasSubmitted=false,baseLandPending=false;
  bool areaReuseRequested=false,areaReuseHit=false;
  long long baseAreasStamp=0,fullStageStamp=0;
  long long lastBaseAreasStamp=0,lastFullStageStamp=0;
  double baseReadyMs=0,baseSwapEndMs=0,baseSwapMs=0,baseExtraMs=0;
  using Clock=std::chrono::steady_clock;
  Clock::time_point renderStarted{},previousPresent{};
  FrameTiming lastTiming{};
  double renderCpuStarted=-1;
  GlQueryCounts queryStart{},lastQueries{};
  GlSetterProfile setterStart{},lastSetters{};
  RenderProfile lastProfile{},lastDetailedProfile{};
  bool lastProfileEnabled=false;
  double totalSubmitMs=0,totalSwapMs=0;
  int captureDetailMode=0;
  AsyncGpuTimer gpuTimer;
  std::string lastGpuJson="\"gpuDurationMs\":null,\"gpuMeasurement\":\"inactive\",\"gpuSamples\":[]";
  void preserveGpuIncomingError();
  void snapshotGpu();
  void initialize(EGLNativeWindowType,int,int,bool);
  EGLSurface createPresentationSurface(EGLNativeWindowType,bool);
  void makeCurrent();
  void blitOutput(bool baseStage=false);
  void requireNormalRenderPresentation(bool frameReady)const;
public:
  void worldUnderlay(bool enabled){if(transparentNoData!=enabled){transparentNoData=enabled;retainedChartKey.clear();completedOutputValid=false;}}
  // Owner-stack lifetime only: a permit cannot authorize another selected
  // slot and is removed on return/exception from this one tagged render.
  class NormalPresentationScope {
    GlesCompositor& target;
    const NormalRenderPresentationPermit value;
    const NormalRenderPresentationPermit* previous;
  public:
    NormalPresentationScope(GlesCompositor& gpu,const NormalRenderPresentationPermit& permit)
      :target(gpu),value(permit),previous(gpu.normalPresentationPermit){gpu.normalPresentationPermit=&value;}
    ~NormalPresentationScope(){target.normalPresentationPermit=previous;}
    NormalPresentationScope(const NormalPresentationScope&)=delete;
    NormalPresentationScope& operator=(const NormalPresentationScope&)=delete;
  };
  ~GlesCompositor(){detach();}
#ifdef __ANDROID__
  // These methods take ownership of the incoming ANativeWindow references,
  // including on initialization failure. The producer owner controls which
  // acknowledged/free slot may receive the next immutable image.
  void attach(ANativeWindow*,int,int);
  void attachPair(ANativeWindow*,ANativeWindow*,int,int);
#else
  void attachOffscreen(int,int);
  void attachOffscreenPair(int,int);
#endif
  void selectPresentationSlot(int);
  int presentationSlots()const{return presentationSurfaceCount;}
  int presentationSlot()const{return selectedPresentationSlot;}
  void centeredRaster(int visibleWidth,int visibleHeight);
#if !defined(__ANDROID__) || defined(NO5_LINE_BATCH_PROBE)
  RgbaTarget readback();
  RgbaTarget readPresentation();
#endif
  // Explicit diagnostic only, after a complete 1:1 native frame. Blocking CPU
  // readback of the retained output, never the undefined post-swap window FBO.
  // Does not draw, swap, acknowledge a consumer or provide display/GPU timing.
  RgbaTarget readCompletedDiagnosticFrame(std::uint64_t expectedFrame,int expectedWidth,int expectedHeight);
  void detach();
  std::string diagnostics() const;
  std::string timingJson()const;
  std::string identityJson()const;
  void captureMode(int mode){if(mode<0||mode>2)throw std::invalid_argument("Invalid capture mode");captureDetailMode=mode;gpuTimer.sampling(mode==1);if(mode!=1){gpuTimer.resetFrameStats();snapshotGpu();}}
  SurfaceGeometry fittedGeometry() const {return fitSurface(width,height);}
  FrameTiming timing() const {return lastTiming;}
  unsigned long long drawCalls() const {return lastDrawCalls;}
  RenderProfile profile() const {return lastProfile;}
  std::uint64_t completedFrameId()const{return lastProfile.frame;}
#ifndef __ANDROID__
  bool retainedFrameAvailableForTests()const{return !retainedChartKey.empty();}
  std::uint64_t submittedFramesForTests()const{return presentedFrames;}
#endif
  GlSetterProfile setterProfile() const {return lastSetters;}
  void startRender() override;
  void activate(){makeCurrent();}
  bool attached()const{return surface!=EGL_NO_SURFACE;}
  bool swapsEnabled()const{return surfaceSwapEnabled;}
  void enableSwaps(bool enabled){surfaceSwapEnabled=enabled;}
  void progressiveTags(long long base,long long full){
    if(base>0&&presentationSurfaceCount>1)throw std::logic_error("Paired slots accept one immutable publication before consumer acknowledgement");
    baseAreasStamp=base;fullStageStamp=full;
  }
  void stageProbe(bool enabled){stageProbeEnabled=enabled;}
  void areaReuse(bool enabled){areaReuseRequested=enabled;}
  bool areaWasReused()const{return areaReuseHit;}
  bool wantsAreaReuse()const override{return areaReuseRequested;}
  bool reuseChartFrame(const std::string&,const std::array<wxPoint2DDouble,4>&)override;
  bool baseWasPublished()const{return baseAreasSubmitted;}
  bool wantsBaseAreas()const override{return baseAreasStamp>0;}
  void presentBaseAreas(bool decorativeLandPending=false)override;
  void beginFrame(int,int,S52color)override;
  bool restoreChartFrame(const std::string&,int,int,S52color)override;
  bool rememberChartFrame(const std::string&)override;
  GLuint beginAreaEffect()override;
  void drawLine(wxPoint,wxPoint,S52color,int)override;
  void drawOverlayLine(wxPoint,wxPoint,S52color,int)override;
  void drawSymbol(wxPoint,S52color)override;
  void replaceChartRgb(const std::vector<uint8_t>&)override;
  void endFrame()override;
  void abandonFrame()noexcept override;
};
}
