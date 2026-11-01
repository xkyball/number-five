#include "gles.hpp"
#include "render_epoch.hpp"
#include <EGL/eglext.h>
#include "port/s52_context.hpp"
#include <stdexcept>
#include <sstream>
#include <cctype>
#include <iomanip>
#include <mutex>
#include <unordered_map>
namespace no5 {
namespace {
std::mutex displayMutex;
std::unordered_map<EGLDisplay, size_t> displayOwners;
bool retainDisplay(EGLDisplay display) {
 std::lock_guard<std::mutex> lock(displayMutex);
 auto found=displayOwners.find(display);
 if(found!=displayOwners.end()){++found->second;return true;}
 if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr))return false;
 displayOwners.emplace(display,1);return true;
}
void releaseDisplay(EGLDisplay display) {
 std::lock_guard<std::mutex> lock(displayMutex);
 auto found=displayOwners.find(display);
 if(found!=displayOwners.end()&&!--found->second){eglTerminate(display);displayOwners.erase(found);}
}
}
static std::uint64_t gpuNowNanos() { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
void GlesCompositor::preserveGpuIncomingError() { auto error=gpuTimer.takeIncomingError(); if(no5FirstGlError==GL_NO_ERROR) no5FirstGlError=error; }
void GlesCompositor::requireNormalRenderPresentation(bool frameReady)const {
 if(!activeRenderEpoch)return;
 const auto reject=[&](RenderSupersededReason reason){throw RenderSuperseded(reason,
   frameReady?RenderAbortBoundary::Publication:RenderAbortBoundary::Begin,
   activeRenderEpoch->expected().epoch,activeRenderEpoch->latestEpoch());};
 if(frameReady&&(areaReuseRequested||baseAreasStamp>0||!surfaceSwapEnabled))reject(RenderSupersededReason::Unbound);
 if(normalPresentationPermit){
  const auto& permit=*normalPresentationPermit;
  if(!permit.registryGeneration||!permit.stamp||!(permit.authority==activeRenderEpoch->expected()))reject(RenderSupersededReason::Unbound);
  if(presentationSurfaceCount==1){
   if(permit.reservedSlot!=-1||permit.frontSlot!=-1)reject(RenderSupersededReason::Unbound);
  }else if(presentationSurfaceCount!=2||permit.reservedSlot<0||permit.reservedSlot>1||
     permit.frontSlot<0||permit.frontSlot>1||permit.reservedSlot!=1-permit.frontSlot||
     selectedPresentationSlot!=permit.reservedSlot)reject(RenderSupersededReason::Unbound);
 }
 if(!centeredRasterOutput)return; // Preserve the existing plain FULL path.
 // Only the canonical, 1:1 CONTROL image in the reserved back queue is added.
 // Producer-reserve drawing is separately refused by Engine::render.
 if(!normalPresentationPermit||presentationSurfaceCount!=2||
    presentationSurfaces[0]==EGL_NO_SURFACE||presentationSurfaces[1]==EGL_NO_SURFACE||
    presentationSurfaces[0]==presentationSurfaces[1]||surface!=presentationSurfaces[selectedPresentationSlot]||
    visibleRasterWidth<1||visibleRasterHeight<1||visibleRasterWidth>width||visibleRasterHeight>height||
    stageProbeEnabled||fullStageStamp>0)reject(RenderSupersededReason::Unbound);
 if(frameReady&&(frameWidth!=visibleRasterWidth||frameHeight!=visibleRasterHeight))reject(RenderSupersededReason::Unbound);
 // A TextureView may change its queue geometry independently. Recheck both
 // outputs before touching the private FBO and immediately before publication.
 for(int slot=0;slot<2;slot++){
  EGLint actualWidth=0,actualHeight=0;
  if(!eglQuerySurface(display,presentationSurfaces[slot],EGL_WIDTH,&actualWidth)||
     !eglQuerySurface(display,presentationSurfaces[slot],EGL_HEIGHT,&actualHeight)||
     actualWidth!=width||actualHeight!=height)reject(RenderSupersededReason::Surface);
 }
}
void GlesCompositor::startRender() {
 if(activeRenderEpoch&&(areaReuseRequested||baseAreasStamp>0||!surfaceSwapEnabled))throw std::logic_error("Normal abort requires a complete non-reused published frame");
 requireNormalRenderPresentation(false);
 completedOutputValid=false;
 areaReuseHit=false;
 baseAreasSubmitted=false;baseLandPending=false;baseReadyMs=baseSwapEndMs=baseSwapMs=baseExtraMs=0;
 lastBaseAreasStamp=0;lastFullStageStamp=fullStageStamp;
 gpuTimer.resetFrameStats(); gpuTimer.poll(gpuNowNanos()); preserveGpuIncomingError();
 renderCpuStarted=threadCpuMs(); renderStarted=Clock::now(); auto cache=glLocationCaches.find(context);
 queryStart=cache==glLocationCaches.end()?GlQueryCounts{}:cache->second.queries;
 setterStart=cache==glLocationCaches.end()?GlSetterProfile{}:cache->second.setters;
 beginProfile(presentedFrames+1,captureDetailMode);
}
void GlesCompositor::snapshotGpu() {
 std::ostringstream out;out<<std::fixed<<std::setprecision(6)<<"\"gpuDurationMs\":";
 bool current=false;
 for(std::size_t i=0;i<gpuTimer.sampleCount();i++) if(gpuTimer.samples()[i].frame==presentedFrames) { out<<gpuTimer.samples()[i].nanoseconds/1e6;current=true;break; }
 if(!current)out<<"null";
 out<<",\"gpuMeasurement\":\""<<jsonEscape(gpuTimer.status())<<"\",\"gpuSupported\":"<<(gpuTimer.isSupported()?"true":"false")
 <<",\"gpuSamplingEnabled\":"<<(gpuTimer.samplingEnabled()?"true":"false")<<",\"gpuCounterBits\":"<<gpuTimer.bits()<<",\"gpuPendingQueries\":"<<gpuTimer.pendingCount();
 out<<",\"gpuProbe\":{\"pollWallMs\":"<<gpuTimer.pollWallMs<<",\"issueWallMs\":"<<gpuTimer.issueWallMs<<",\"pollCpuMs\":";
 if(gpuTimer.cpuAvailable)out<<gpuTimer.pollCpuMs;else out<<"null";
 out<<",\"issueCpuMs\":";if(gpuTimer.cpuAvailable)out<<gpuTimer.issueCpuMs;else out<<"null";
 out<<",\"availabilityQueries\":"<<gpuTimer.availabilityQueries<<",\"resultQueries\":"<<gpuTimer.resultQueries<<",\"disjointQueries\":"<<gpuTimer.disjointQueries<<",\"errorQueries\":"<<gpuTimer.errorQueries<<",\"capabilityQueries\":"<<gpuTimer.capabilityQueries<<",\"allocationCalls\":"<<gpuTimer.allocationCalls<<"}";
 out<<",\"gpuContextTotals\":{\"skippedPoolFull\":"<<gpuTimer.skipped<<",\"disjointDiscarded\":"<<gpuTimer.disjointDiscarded<<",\"overflowDiscarded\":"<<gpuTimer.overflowDiscarded<<",\"driverFailures\":"<<gpuTimer.driverFailures<<",\"sampleCapacityDiscarded\":"<<gpuTimer.sampleCapacityDiscarded<<"},\"gpuSamples\":[";
 for(std::size_t i=0;i<gpuTimer.sampleCount();i++) { if(i)out<<",";auto v=gpuTimer.samples()[i];out<<"{\"frame\":"<<v.frame<<",\"durationMs\":"<<v.nanoseconds/1e6<<"}"; }
 out<<"]";lastGpuJson=out.str();
}

static GLuint shader(GLenum kind,const char* source) {
  GLuint s=glCreateShader(kind);glShaderSource(s,1,&source,nullptr);glCompileShader(s);
  GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
  if(!ok){glDeleteShader(s);throw std::runtime_error("No5 GLES shader compilation failed");}return s;
}
static GLuint link(const char* vertex,const char* fragment) {
  auto vs=shader(GL_VERTEX_SHADER,vertex),fs=shader(GL_FRAGMENT_SHADER,fragment);
  auto p=glCreateProgram();glAttachShader(p,vs);glAttachShader(p,fs);
  glBindAttribLocation(p,0,"p");glBindAttribLocation(p,1,"uv");glLinkProgram(p);
  glDeleteShader(vs);glDeleteShader(fs);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);
  if(!ok){glDeleteProgram(p);throw std::runtime_error("No5 GLES shader link failed");}return p;
}
void GlesCompositor::makeCurrent(){if(!attached()||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("No5 EGL context unavailable");activateS52Context(context);}
void GlesCompositor::selectPresentationSlot(int slot) {
  if(slot<0||slot>=presentationSurfaceCount||presentationSurfaces[slot]==EGL_NO_SURFACE)
    throw std::invalid_argument("Invalid presentation slot");
  if(renderStarted!=Clock::time_point{})throw std::logic_error("Cannot change presentation slot during a frame");
  const auto oldSurface=surface;
  surface=presentationSurfaces[slot];
  try{makeCurrent();}
  catch(...){surface=oldSurface;throw;}
  selectedPresentationSlot=slot;
  // EGL swap interval belongs to the current draw surface, not the GL context.
  if(!eglSwapInterval(display,1))throw std::runtime_error("Presentation swap interval failed");
}
#ifdef __ANDROID__
static void configureWindowRaster(ANativeWindow* window,int width,int height,int format,const char* slot) {
 // TextureView may restore its visible-size consumer default when rebuilding
 // a retained layer. The producer owns the fixed reserve raster independently.
 const auto result=ANativeWindow_setBuffersGeometry(window,width,height,format);
 if(result!=0){
  std::ostringstream message;message<<"Native window raster geometry failed slot="<<slot
   <<" expected="<<width<<'x'<<height<<" window="<<ANativeWindow_getWidth(window)<<'x'<<ANativeWindow_getHeight(window)
   <<" result="<<result;throw std::runtime_error(message.str());
 }
}
void GlesCompositor::attach(ANativeWindow* w,int x,int y) {
  detach();windows[0]=w;try{
    if(!w)throw std::invalid_argument("Invalid native presentation window");
    initialize(static_cast<EGLNativeWindowType>(w),x,y,false);
  }catch(...){detach();throw;}
}
void GlesCompositor::attachPair(ANativeWindow* first,ANativeWindow* second,int x,int y) {
  detach();windows={first,second};try{
    if(!first||!second||first==second)throw std::invalid_argument("Two distinct native presentation windows required");
    initialize(static_cast<EGLNativeWindowType>(first),x,y,false);
    EGLint format=0;
    if(!eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format))
      throw std::runtime_error("Replacement window format failed");
    configureWindowRaster(second,x,y,format,"replacement");
    presentationSurfaces[1]=createPresentationSurface(static_cast<EGLNativeWindowType>(second),false);
    presentationSurfaceCount=2;surfaceKind="Android window Surface pair";
  }catch(...){detach();throw;}
}
#else
void GlesCompositor::attachOffscreen(int x,int y) {
  detach();try{initialize(EGLNativeWindowType{},x,y,true);}catch(...){detach();throw;}
}
void GlesCompositor::attachOffscreenPair(int x,int y) {
  detach();try{
    initialize(EGLNativeWindowType{},x,y,true);
    presentationSurfaces[1]=createPresentationSurface(EGLNativeWindowType{},true);
    presentationSurfaceCount=2;surfaceKind="Pbuffer pair (host test)";
  }catch(...){detach();throw;}
}
#endif
EGLSurface GlesCompositor::createPresentationSurface(EGLNativeWindowType native,bool offscreen) {
  const EGLint size[]={EGL_WIDTH,width,EGL_HEIGHT,height,EGL_NONE};
  auto created=offscreen?eglCreatePbufferSurface(display,config,size):eglCreateWindowSurface(display,config,native,nullptr);
  if(created==EGL_NO_SURFACE)throw std::runtime_error("No5 EGL presentation surface failed");
  EGLint actualWidth=0,actualHeight=0;
  const bool queriedWidth=eglQuerySurface(display,created,EGL_WIDTH,&actualWidth);
  const bool queriedHeight=eglQuerySurface(display,created,EGL_HEIGHT,&actualHeight);
#ifdef NO5_COMPUTE_MAC_METAL
  const bool zeroBootstrap=offscreen&&actualWidth==0&&actualHeight==0;
#else
  const bool zeroBootstrap=false;
#endif
  if(!queriedWidth||!queriedHeight||((actualWidth!=width||actualHeight!=height)&&!zeroBootstrap)){
    std::ostringstream message;message<<"EGL Surface size changed; wait for surfaceChanged before rendering"
      <<" expected="<<width<<'x'<<height<<" actual="<<actualWidth<<'x'<<actualHeight
      <<" queriedWidth="<<queriedWidth<<" queriedHeight="<<queriedHeight;
#ifdef __ANDROID__
    if(!offscreen){auto* window=static_cast<ANativeWindow*>(native);
      message<<" window="<<ANativeWindow_getWidth(window)<<'x'<<ANativeWindow_getHeight(window);}
#endif
    eglDestroySurface(display,created);
    throw std::runtime_error(message.str());
  }
  return created;
}
void GlesCompositor::initialize(EGLNativeWindowType native,int x,int y,bool offscreen) {
  if(x<=0||y<=0)throw std::invalid_argument("Invalid native Surface size");
  width=x;height=y;
#ifdef NO5_COMPUTE_MAC_METAL
  const char* extensions=eglQueryString(EGL_NO_DISPLAY,EGL_EXTENSIONS);
  if(!extensions||std::string(extensions).find("EGL_ANGLE_platform_angle_metal")==std::string::npos)throw std::runtime_error("ANGLE Metal unavailable");
  using GetDisplay=EGLDisplay(*)(EGLenum,void*,const EGLint*);
  auto getDisplay=reinterpret_cast<GetDisplay>(eglGetProcAddress("eglGetPlatformDisplayEXT"));
  if(!getDisplay)throw std::runtime_error("ANGLE display entry unavailable");
  const EGLint metal[]={0x3203,0x3489,EGL_NONE};display=getDisplay(0x3202,nullptr,metal);
#else
  display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
#endif
  displayRetained=retainDisplay(display);
  if(!displayRetained||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("No5 EGL initialize failed");
  const EGLint attributes[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,EGL_SURFACE_TYPE,offscreen?EGL_PBUFFER_BIT:EGL_WINDOW_BIT,
    EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
  EGLint count;if(!eglChooseConfig(display,attributes,&config,1,&count)||!count)throw std::runtime_error("No5 EGL config failed");
#ifdef __ANDROID__
  EGLint format=0;
  if(!eglGetConfigAttrib(display,config,EGL_NATIVE_VISUAL_ID,&format))throw std::runtime_error("Native window format failed");
  configureWindowRaster(windows[0],width,height,format,"primary");
#endif
  EGLint ca[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};context=eglCreateContext(display,config,EGL_NO_CONTEXT,ca);
  if(context==EGL_NO_CONTEXT)throw std::runtime_error("No5 EGL context failed");
  presentationSurfaces[0]=createPresentationSurface(native,offscreen);
  surface=presentationSurfaces[0];presentationSurfaceCount=1;selectedPresentationSlot=0;
  makeCurrent();registerGlLocationContext(context,width,height);eglSwapInterval(display,1);
  GLint viewportLimit[2]={0,0},textureLimit=0;
  glGetIntegerv(GL_MAX_VIEWPORT_DIMS,viewportLimit);glGetIntegerv(GL_MAX_TEXTURE_SIZE,&textureLimit);
  if(width>viewportLimit[0]||height>viewportLimit[1]||width>textureLimit||height>textureLimit)
    throw std::runtime_error("Producer raster exceeds GPU viewport/texture limits");
  auto glString=[](GLenum name){auto value=glGetString(name);return value?std::string(reinterpret_cast<const char*>(value)):std::string("unavailable");};
  vendor=glString(GL_VENDOR);renderer=glString(GL_RENDERER);version=glString(GL_VERSION);
#ifdef NO5_COMPUTE_MAC_METAL
  if(renderer.find("Metal")==std::string::npos||renderer.find("Software")!=std::string::npos)throw std::runtime_error("Hardware Metal unverified");
#endif
  const char* ev=eglQueryString(display,EGL_VERSION);eglVersion=ev?ev:"unavailable";
  surfaceKind=offscreen?"Pbuffer (host test)":"Android window Surface";

  program=link("attribute vec2 p;attribute vec2 uv;varying vec2 t;void main(){t=uv;gl_Position=vec4(p,0.0,1.0);}",
    "precision mediump float;varying vec2 t;uniform sampler2D frame;void main(){gl_FragColor=texture2D(frame,t);}");
  lineProgram=link("attribute vec2 p;uniform vec2 size;void main(){gl_Position=vec4(p.x*2.0/size.x-1.0,1.0-p.y*2.0/size.y,0.0,1.0);}",
    "precision mediump float;uniform vec4 color;void main(){gl_FragColor=color;}");
  glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
  glGenFramebuffers(1,&framebuffer);
  gpuTimer.configure(glString(GL_EXTENSIONS));gpuTimer.sampling(captureDetailMode==1);snapshotGpu();
}
void GlesCompositor::detach() {
  if(display!=EGL_NO_DISPLAY) {
    if(context!=EGL_NO_CONTEXT&&surface!=EGL_NO_SURFACE&&eglMakeCurrent(display,surface,surface,context)) {
      activateS52Context(context);
      gpuTimer.release();
      stopStartupGlTrace(); // Cleanup must remain possible after a latched failure.
      releaseS52Context(context);
      if(framebuffer)glDeleteFramebuffers(1,&framebuffer);if(texture)glDeleteTextures(1,&texture);
      if(retainedChartTexture)glDeleteTextures(1,&retainedChartTexture);
      if(areaFramebuffer)glDeleteFramebuffers(1,&areaFramebuffer);if(areaTexture)glDeleteTextures(1,&areaTexture);
      if(program)glDeleteProgram(program);if(lineProgram)glDeleteProgram(lineProgram);
    }
    forgetS52Context(context);unregisterGlLocationContext(context);
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    for(auto ownedSurface:presentationSurfaces)if(ownedSurface!=EGL_NO_SURFACE)eglDestroySurface(display,ownedSurface);
    if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
    if(displayRetained)releaseDisplay(display);
  }
  gpuTimer.forget();captureDetailMode=0;snapshotGpu();
  display=EGL_NO_DISPLAY;context=EGL_NO_CONTEXT;surface=EGL_NO_SURFACE;
  config=nullptr;presentationSurfaces={EGL_NO_SURFACE,EGL_NO_SURFACE};
  presentationSurfaceCount=0;selectedPresentationSlot=0;
  displayRetained=false;
  program=lineProgram=texture=framebuffer=0;frameWidth=frameHeight=0;
  retainedChartTexture=0;retainedChartWidth=retainedChartHeight=0;retainedChartKey.clear();
  areaTexture=areaFramebuffer=outputTexture=outputFramebuffer=0;areaWidth=areaHeight=0;
  baseNearest=false;
  centeredRasterOutput=false;visibleRasterWidth=visibleRasterHeight=0;
  presentedFrames=lastDrawCalls=0;vendor.clear();renderer.clear();version.clear();eglVersion.clear();surfaceKind.clear();
  renderStarted=previousPresent=Clock::time_point{};lastTiming={};queryStart=lastQueries={};totalSubmitMs=totalSwapMs=0;
  setterStart=lastSetters={};
  lastProfile={};lastDetailedProfile={};lastProfileEnabled=false;profileActive=false;
  completedOutputValid=false;
  normalPresentationPermit=nullptr;
  no5FirstGlError=GL_NO_ERROR;
#ifdef __ANDROID__
  for(auto window:windows)if(window)ANativeWindow_release(window);windows={nullptr,nullptr};
#endif
}
void GlesCompositor::beginFrame(int w,int h,S52color background) {
  surfaceGeometry(width,height,w,h); // Validate S52 bounds before allocating.
  if(renderStarted==Clock::time_point{})startRender();
  if(activeRenderEpoch&&centeredRasterOutput&&(w!=visibleRasterWidth||h!=visibleRasterHeight))
    throw RenderSuperseded(RenderSupersededReason::Unbound,RenderAbortBoundary::Prepared,
      activeRenderEpoch->expected().epoch,activeRenderEpoch->latestEpoch());
  no5DrawCalls=0;
  makeCurrent();gpuTimer.begin(presentedFrames+1,gpuNowNanos());preserveGpuIncomingError();
  glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
  if(frameWidth!=w||frameHeight!=h) {
    GLint maxSize;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&maxSize);if(w>maxSize||h>maxSize)throw std::runtime_error("Chart frame exceeds GPU texture limit");
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("No5 chart framebuffer incomplete");
    frameWidth=w;frameHeight=h;
  }
  outputTexture=texture;outputFramebuffer=framebuffer;
  glBindFramebuffer(GL_FRAMEBUFFER,outputFramebuffer);
  glViewport(0,0,w,h);glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_TEST);glDisable(GL_STENCIL_TEST);
  worldUnderlayAlpha=transparentNoData;
  glDisable(GL_BLEND);glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
  glClearColor(transparentNoData?0.f:background.R/255.f,transparentNoData?0.f:background.G/255.f,transparentNoData?0.f:background.B/255.f,transparentNoData?0.f:1.f);glClear(GL_COLOR_BUFFER_BIT);
}
bool GlesCompositor::rememberChartFrame(const std::string& key){
  retainedChartKey.clear();
  if(!retainedChartTexture){glGenTextures(1,&retainedChartTexture);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,retainedChartTexture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);}
  glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,retainedChartTexture);
  if(retainedChartWidth!=frameWidth||retainedChartHeight!=frameHeight){glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,frameWidth,frameHeight,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);retainedChartWidth=frameWidth;retainedChartHeight=frameHeight;}
  glBindFramebuffer(GL_FRAMEBUFFER,outputFramebuffer);
  // GPU-side exact copy before any dynamic overlay. No readback or CPU upload.
  glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,frameWidth,frameHeight);
  if(glGetError()!=GL_NO_ERROR||no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("Chart retention failed");
  retainedChartKey=key;return true;
}
bool GlesCompositor::restoreChartFrame(const std::string& key,int w,int h,S52color background){
  if(!retainedChartTexture||retainedChartKey!=key||retainedChartWidth!=w||retainedChartHeight!=h)return false;
  beginFrame(w,h,background);
  glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,retainedChartTexture);glUniform1i(glGetUniformLocation(program,"frame"),0);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
  glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
  const float vertices[]={-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1};
  glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices+2);glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
  if(glGetError()!=GL_NO_ERROR||no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("Chart restore failed");
  return true;
}
bool GlesCompositor::reuseChartFrame(const std::string& key,const std::array<wxPoint2DDouble,4>& quad){
 if(!areaReuseRequested||!retainedChartTexture||retainedChartKey!=key||
    retainedChartWidth!=frameWidth||retainedChartHeight!=frameHeight)return false;
 for(const auto& p:quad)if(!std::isfinite(p.m_x)||!std::isfinite(p.m_y)||
    std::abs(p.m_x)>1e7||std::abs(p.m_y)>1e7)return false;
 // No framebuffer switch, copy, readback, storage allocation or extra swap.
 glViewport(0,0,frameWidth,frameHeight);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);
 glDisable(GL_DEPTH_TEST);glDisable(GL_STENCIL_TEST);glDisable(GL_CULL_FACE);
 glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
 glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,retainedChartTexture);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
 glUniform1i(glGetUniformLocation(program,"frame"),0);
 glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
 float vertices[16];const float uv[]={0,0,1,0,0,1,1,1};
 for(size_t i=0;i<quad.size();i++){
  vertices[4*i]=float(quad[i].m_x*2/frameWidth-1);vertices[4*i+1]=float(1-quad[i].m_y*2/frameHeight);
  vertices[4*i+2]=uv[2*i];vertices[4*i+3]=uv[2*i+1];
 }
 glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices);
 glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices+2);
 glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
 if(glGetError()!=GL_NO_ERROR||no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("Chart reprojection failed");
 areaReuseHit=true;return true;
}
GLuint GlesCompositor::beginAreaEffect() {
  DetailScope scope(RenderDetail::AreaTarget);
  if(outputFramebuffer!=framebuffer||!frameWidth||!frameHeight)throw std::logic_error("S01 requires completed base areas");
  // Same nearest/clamped categorical sampling as the former GPU snapshot.
  // These nested samples include the managed delegate and any driver wait;
  // they are not GPU timings and retain the existing detail-sampling cadence.
  {DetailScope inputUnit(RenderDetail::AreaActiveTexture);glActiveTexture(GL_TEXTURE0);}
  {DetailScope inputBind(RenderDetail::AreaInputBind);glBindTexture(GL_TEXTURE_2D,texture);}
  if(!baseNearest){DetailScope filter(RenderDetail::AreaInputFilter);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);baseNearest=true;}
  if(!areaTexture){
    DetailScope create(RenderDetail::AreaOutputCreate);
    glGenTextures(1,&areaTexture);glBindTexture(GL_TEXTURE_2D,areaTexture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1,&areaFramebuffer);
  }
  {DetailScope outputBind(RenderDetail::AreaOutputBind);glBindFramebuffer(GL_FRAMEBUFFER,areaFramebuffer);}
  if(areaWidth!=frameWidth||areaHeight!=frameHeight){
    DetailScope storage(RenderDetail::AreaOutputStorage);
    glBindTexture(GL_TEXTURE_2D,areaTexture);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,frameWidth,frameHeight,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,areaTexture,0);
    if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("S01 output framebuffer incomplete");
    areaWidth=frameWidth;areaHeight=frameHeight;
    if(profileActive)currentProfile.areaTargetAllocations++;
  }
  // The unchanged shader writes every pixel; subsequent S52 objects and
  // overlays continue into this attachment. There is no framebuffer copy.
  outputTexture=areaTexture;outputFramebuffer=areaFramebuffer;
  if(profileActive)currentProfile.areaTargetSwitches++;
  return texture;
}
void GlesCompositor::drawLine(wxPoint a,wxPoint b,S52color color,int width) {
  glUseProgram(lineProgram);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);glDisable(GL_BLEND);
  glUniform2f(glGetUniformLocation(lineProgram,"size"),frameWidth,frameHeight);
  glUniform4f(glGetUniformLocation(lineProgram,"color"),color.R/255.f,color.G/255.f,color.B/255.f,1);
  GLfloat range[2];glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE,range);glLineWidth(std::clamp(float(width),range[0],range[1]));
  const float points[]={float(a.x),float(a.y),float(b.x),float(b.y)};
  glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,points);glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);glDrawArrays(GL_LINES,0,2);
}
void GlesCompositor::drawOverlayLine(wxPoint a,wxPoint b,S52color color,int width) {
  const double dx=double(b.x)-a.x,dy=double(b.y)-a.y,length=std::hypot(dx,dy);
  if(length==0)return;
  const double half=std::clamp(width,1,256)/2.0,nx=-dy/length*half,ny=dx/length*half;
  const float vertices[]={float(a.x+nx),float(a.y+ny),float(a.x-nx),float(a.y-ny),
                          float(b.x+nx),float(b.y+ny),float(b.x-nx),float(b.y-ny)};
  glUseProgram(lineProgram);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);glDisable(GL_BLEND);
  glUniform2f(glGetUniformLocation(lineProgram,"size"),frameWidth,frameHeight);
  glUniform4f(glGetUniformLocation(lineProgram,"color"),color.R/255.f,color.G/255.f,color.B/255.f,1);
  glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,vertices);glEnableVertexAttribArray(0);glDisableVertexAttribArray(1);
  glDrawArrays(GL_TRIANGLE_STRIP,0,4);
}
void GlesCompositor::drawSymbol(wxPoint p,S52color c){if(p.x==INVALID_COORD||p.y==INVALID_COORD)return;drawLine({p.x-5,p.y},{p.x+5,p.y},c,1);drawLine({p.x,p.y-5},{p.x,p.y+5},c,1);}
void GlesCompositor::replaceChartRgb(const std::vector<uint8_t>&){throw std::logic_error("CPU chart upload is not a direct GLES frame");}
void GlesCompositor::centeredRaster(int visibleWidth,int visibleHeight){
 if(visibleWidth<1||visibleHeight<1||visibleWidth>width||visibleHeight>height||!attached())
  throw std::invalid_argument("Invalid canonical visible raster dimensions");
 for(int slot=0;slot<presentationSurfaceCount;slot++){
  EGLint actualWidth=0,actualHeight=0;
  const bool queried=eglQuerySurface(display,presentationSurfaces[slot],EGL_WIDTH,&actualWidth)&&
    eglQuerySurface(display,presentationSurfaces[slot],EGL_HEIGHT,&actualHeight);
#if defined(NO5_COMPUTE_MAC_METAL) && !defined(__ANDROID__)
  // Match createPresentationSurface's lazy ANGLE-Metal pbuffer bootstrap.
  // Physical Android windows must always report the requested queue size.
  const bool zeroBootstrap=surfaceKind.rfind("Pbuffer",0)==0&&actualWidth==0&&actualHeight==0;
#else
  const bool zeroBootstrap=false;
#endif
  if(!queried||((actualWidth!=width||actualHeight!=height)&&!zeroBootstrap))
   throw std::runtime_error("Actual EGL output dimensions differ from requested reserve queue: slot="+
     std::to_string(slot)+" requested="+std::to_string(width)+"x"+std::to_string(height)+
     " actual="+std::to_string(actualWidth)+"x"+std::to_string(actualHeight)+" queried="+(queried?"true":"false"));
 }
 centeredRasterOutput=true;visibleRasterWidth=visibleWidth;visibleRasterHeight=visibleHeight;
}
void GlesCompositor::blitOutput(bool baseStage) {
  {StageScope scope(RenderStage::Present);
  // Blending leaves fractional alpha in an offscreen attachment. The chart is
  // an opaque display, so finish its alpha channel without touching RGB.
  if(!transparentNoData){
   glDisable(GL_SCISSOR_TEST);glColorMask(GL_FALSE,GL_FALSE,GL_FALSE,GL_TRUE);
   glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
   glColorMask(GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE);
  }
  glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);
  // Match the opaque wrapper so the same video gap proxy sees control padding
  // and transformed gaps. Padded pixels are still excluded from prepared coverage.
  if(transparentNoData)glClearColor(0,0,0,0);
  else if(centeredRasterOutput)glClearColor(8/255.f,27/255.f,43/255.f,1);else glClearColor(0,0,0,1);
  glClear(GL_COLOR_BUFFER_BIT);
  const auto geometry=surfaceGeometry(width,height,frameWidth,frameHeight);
  // Reserve queues retain all producer pixels. The control FBO is copied 1:1
  // into its central rectangle; only TextureView crops for the visible window.
  const auto outputScale=centeredRasterOutput?1.0:geometry.scale;
  const float x=float(frameWidth*outputScale/width),y=float(frameHeight*outputScale/height);
  glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,outputTexture);
  // A native-size pass copies texels exactly. Linear filtering can blend across
  // land boundaries even at 1:1 because interpolated UVs are not exact centers.
  const auto filter=(centeredRasterOutput||(frameWidth==width&&frameHeight==height))?GL_NEAREST:GL_LINEAR;
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,filter);
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,filter);
  if(outputTexture==texture)baseNearest=(filter==GL_NEAREST);
  glUniform1i(glGetUniformLocation(program,"frame"),0);glBindBuffer(GL_ARRAY_BUFFER,0);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,0);
  const float vertices[]={-x,-y,0,0, x,-y,1,0, -x,y,0,1, x,y,1,1};
  glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices);glVertexAttribPointer(1,2,GL_FLOAT,GL_FALSE,4*sizeof(float),vertices+2);
  glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
  // Public-run-only pixel evidence for the actually published stage. Draw into
  // the window, never into the retained chart attachment or normal app content.
  if(stageProbeEnabled){glEnable(GL_SCISSOR_TEST);
    const int markerRight=centeredRasterOutput?(width+visibleRasterWidth)/2:width;
    const int markerTop=centeredRasterOutput?(height+visibleRasterHeight)/2:height;
    glScissor(markerRight-24,markerTop-12,24,12);
    glClearColor(baseStage?1.f:0.f,baseStage?.375f:1.f,baseStage?0.f:1.f,1.f);
    glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);}
  glGetError();if(no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("GLES chart draw failed: "+std::to_string(no5FirstGlError));
  }
}
void GlesCompositor::presentBaseAreas(bool decorativeLandPending) {
 if(!baseAreasStamp||!surfaceSwapEnabled)return;
 baseReadyMs=std::chrono::duration<double,std::milli>(Clock::now()-renderStarted).count();
 const auto extraStart=Clock::now();
 // Only one small state snapshot per intermediate publication. Attribute
 // pointers must be restored with their own buffer binding, not the current VBO.
 struct Attribute{GLint size,type,normalized,stride,buffer,enabled;void* pointer;};
 Attribute a[2]{};GLint fbo,oldProgram,array,element,active,binding0,viewport[4];
 GLfloat clear[4];GLboolean mask[4];
 glGetIntegerv(GL_FRAMEBUFFER_BINDING,&fbo);glGetIntegerv(GL_CURRENT_PROGRAM,&oldProgram);
 glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&array);glGetIntegerv(GL_ELEMENT_ARRAY_BUFFER_BINDING,&element);
 glGetIntegerv(GL_ACTIVE_TEXTURE,&active);glActiveTexture(GL_TEXTURE0);glGetIntegerv(GL_TEXTURE_BINDING_2D,&binding0);
 glGetIntegerv(GL_VIEWPORT,viewport);glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
 const bool blend=glIsEnabled(GL_BLEND),scissor=glIsEnabled(GL_SCISSOR_TEST);
 GLint scissorBox[4];glGetIntegerv(GL_SCISSOR_BOX,scissorBox);
 for(GLuint i=0;i<2;i++){
  glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_SIZE,&a[i].size);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_TYPE,&a[i].type);
  glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,&a[i].normalized);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_STRIDE,&a[i].stride);
  glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,&a[i].buffer);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&a[i].enabled);
  glGetVertexAttribPointerv(i,GL_VERTEX_ATTRIB_ARRAY_POINTER,&a[i].pointer);
 }
 using PresentTime=EGLBoolean(*)(EGLDisplay,EGLSurface,EGLnsecsANDROID);
 auto present=reinterpret_cast<PresentTime>(eglGetProcAddress("eglPresentationTimeANDROID"));
#ifdef __ANDROID__
 if(!present||!present(display,surface,EGLnsecsANDROID(baseAreasStamp)))throw std::runtime_error("Base-area timestamp unavailable");
#else
 if(present)present(display,surface,EGLnsecsANDROID(baseAreasStamp));
#endif
 blitOutput(true);const auto swapStart=Clock::now();
 if(!eglSwapBuffers(display,surface))throw std::runtime_error("Base-area swap failed");
 baseSwapMs=std::chrono::duration<double,std::milli>(Clock::now()-swapStart).count();
 baseSwapEndMs=std::chrono::duration<double,std::milli>(Clock::now()-renderStarted).count();
 baseAreasSubmitted=true;baseLandPending=decorativeLandPending;lastBaseAreasStamp=baseAreasStamp;
#ifdef __ANDROID__
 if(!present(display,surface,EGLnsecsANDROID(fullStageStamp)))throw std::runtime_error("Full-stage timestamp unavailable");
#else
 if(present)present(display,surface,EGLnsecsANDROID(fullStageStamp));
#endif
 for(GLuint i=0;i<2;i++){glBindBuffer(GL_ARRAY_BUFFER,a[i].buffer);
  glVertexAttribPointer(i,a[i].size,a[i].type,a[i].normalized,a[i].stride,a[i].pointer);
  if(a[i].enabled)glEnableVertexAttribArray(i);else glDisableVertexAttribArray(i);}
 glBindBuffer(GL_ARRAY_BUFFER,array);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,element);
 glUseProgram(oldProgram);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,binding0);glActiveTexture(active);
 glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
 glColorMask(mask[0],mask[1],mask[2],mask[3]);glClearColor(clear[0],clear[1],clear[2],clear[3]);
 glScissor(scissorBox[0],scissorBox[1],scissorBox[2],scissorBox[3]);
 if(blend)glEnable(GL_BLEND);else glDisable(GL_BLEND);if(scissor)glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);
 baseExtraMs=std::chrono::duration<double,std::milli>(Clock::now()-extraStart).count();
}
void GlesCompositor::endFrame() {
  requireNormalRenderPresentation(true);
  normalRenderCheckpoint(RenderAbortBoundary::Publication);
  const auto submissions=no5DrawCalls;
  blitOutput(areaReuseHit);
  gpuTimer.end();preserveGpuIncomingError();
  if(no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("GLES chart draw failed: "+std::to_string(no5FirstGlError));
  const double swapCpuStarted=threadCpuMs();
  const auto swapStarted=Clock::now();
  {StageScope scope(RenderStage::Swap);
  if(surfaceSwapEnabled){if(!eglSwapBuffers(display,surface))throw std::runtime_error("No5 frame swap failed");}
  else glFlush(); // Submit the same complete draw workload without publishing a new buffer.
  }
  finishStartupGlTrace();
  const auto presented=Clock::now();
  const double presentedCpu=threadCpuMs();
  lastProfileEnabled=profileActive;lastProfile=currentProfile;
  if(profileActive&&currentProfile.detailed)lastDetailedProfile=currentProfile;
  profileActive=false;
  auto ms=[](Clock::duration value){return std::chrono::duration<double,std::milli>(value).count();};
  lastTiming={ms(swapStarted-renderStarted),ms(presented-swapStarted),
    surfaceSwapEnabled&&presentedFrames?ms(presented-previousPresent):0,surfaceSwapEnabled&&presentedFrames>0};
  lastTiming.cpuAvailable=renderCpuStarted>=0&&swapCpuStarted>=renderCpuStarted&&presentedCpu>=swapCpuStarted;
  if(lastTiming.cpuAvailable){lastTiming.submitCpuMs=swapCpuStarted-renderCpuStarted;lastTiming.swapCpuMs=presentedCpu-swapCpuStarted;}
  totalSubmitMs+=lastTiming.submitMs;totalSwapMs+=lastTiming.swapMs;
  lastSurfaceSwapSubmitted=surfaceSwapEnabled;
  completedOutputValid=surfaceSwapEnabled;
  if(surfaceSwapEnabled){previousPresent=presented;++presentedFrames;}else ++withheldFrames;
  renderStarted=Clock::time_point{};lastDrawCalls=submissions;
  if(captureDetailMode==1){gpuTimer.poll(gpuNowNanos());preserveGpuIncomingError();snapshotGpu();}
  if(no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("GLES chart draw failed: "+std::to_string(no5FirstGlError));
  if(auto* c=currentGlLocationCache()) {
    lastQueries={c->queries.locations-queryStart.locations,c->queries.state-queryStart.state,c->queries.capabilityInit-queryStart.capabilityInit,c->queries.validation-queryStart.validation,c->queries.errors-queryStart.errors,c->queries.debug-queryStart.debug};
    for(std::size_t i=0;i<lastSetters.size();++i){const auto& end=c->setters[i];const auto& start=setterStart[i];lastSetters[i]={end.requested-start.requested,end.forwarded-start.forwarded,end.skipped-start.skipped};}
    if(c->verifyAfterFrame)verifyGlDelegateState(); // explicit host debug, outside measured frame/profile
  }
}
void GlesCompositor::abandonFrame()noexcept{
 // GL pass guards already unwound before the chart calls this. Keep immutable
 // geometry/storage, but never expose a partial FBO as the last complete output
 // or retained full-chart basis. The next beginFrame clears the private FBO.
 try{gpuTimer.abandon();preserveGpuIncomingError();}catch(...){/* No partial-frame sample may escape cleanup. */}
 completedOutputValid=false;retainedChartKey.clear();renderStarted=Clock::time_point{};profileActive=false;
}
std::string GlesCompositor::identityJson()const{
 std::ostringstream out;out<<"{\"vendor\":\""<<jsonEscape(vendor)<<"\",\"renderer\":\""<<jsonEscape(renderer)<<"\",\"glVersion\":\""<<jsonEscape(version)<<"\",\"eglVersion\":\""<<jsonEscape(eglVersion)<<"\",\"surfaceKind\":\""<<jsonEscape(surfaceKind)<<"\",\"surfaceWidth\":"<<width<<",\"surfaceHeight\":"<<height<<",\"frameWidth\":"<<frameWidth<<",\"frameHeight\":"<<frameHeight<<",\"centeredRasterOutput\":"<<(centeredRasterOutput?"true":"false")<<",\"visibleFrameWidth\":"<<(centeredRasterOutput?visibleRasterWidth:frameWidth)<<",\"visibleFrameHeight\":"<<(centeredRasterOutput?visibleRasterHeight:frameHeight)<<",\"presentationSlots\":"<<presentationSurfaceCount<<",\"presentationSlot\":"<<selectedPresentationSlot<<",\"solidLineIndexBatchEnabled\":"<<(batchSolidLines()?"true":"false")<<"}";return out.str();
}
std::string GlesCompositor::timingJson()const{
 // A completed draw is not a submitted Surface buffer when P0 withholds swaps.
 std::ostringstream out;out<<std::fixed<<std::setprecision(6)<<"{\"frame\":"<<lastProfile.frame<<",\"renderSubmitWallMs\":"<<lastTiming.submitMs<<",\"eglSwapWallMs\":"<<lastTiming.swapMs<<",\"activeFrameWallMs\":"<<lastTiming.submitMs+lastTiming.swapMs<<",\"presentIntervalMs\":";if(lastTiming.hasInterval)out<<lastTiming.intervalMs;else out<<"null";
 out<<",\"surfaceSwapSubmitted\":"<<(lastSurfaceSwapSubmitted?"true":"false")<<",\"withheldFrames\":"<<withheldFrames;
 out<<",\"presentationSlots\":"<<presentationSurfaceCount<<",\"presentationSlot\":"<<selectedPresentationSlot;
 out<<",\"producerRasterWidth\":"<<frameWidth<<",\"producerRasterHeight\":"<<frameHeight<<",\"bufferQueueWidth\":"<<width<<",\"bufferQueueHeight\":"<<height;
 out<<",\"visibleFrameWidth\":"<<(centeredRasterOutput?visibleRasterWidth:frameWidth)<<",\"visibleFrameHeight\":"<<(centeredRasterOutput?visibleRasterHeight:frameHeight);
 out<<",\"baseAreasPresentation\":{\"submitted\":"<<(baseAreasSubmitted?"true":"false")<<",\"decorativeLandPending\":"<<(baseLandPending?"true":"false")<<",\"baseStamp\":"<<lastBaseAreasStamp<<",\"fullStamp\":"<<lastFullStageStamp<<",\"readyFromRenderStartMs\":"<<baseReadyMs<<",\"swapEndFromRenderStartMs\":"<<baseSwapEndMs<<",\"swapWallMs\":"<<baseSwapMs<<",\"extraPresentationWallMs\":"<<baseExtraMs<<"}";
 out<<",\"areaReuse\":{\"hit\":"<<(areaReuseHit?"true":"false")<<",\"extraTextureStorageBytes\":0}";
 out<<",\"renderThreadCpuMs\":";if(lastTiming.cpuAvailable)out<<lastTiming.submitCpuMs;else out<<"null";
 out<<",\"eglSwapThreadCpuMs\":";if(lastTiming.cpuAvailable)out<<lastTiming.swapCpuMs;else out<<"null";
 out<<","<<lastGpuJson<<",\"drawCalls\":"<<lastDrawCalls<<",\"secondaryTextHits\":"<<lastProfile.secondaryTextCacheHits<<",\"secondaryTextMisses\":"<<lastProfile.secondaryTextCacheMisses<<",\"wholeLabelTexturesCreated\":"<<lastProfile.textTexturesCreated;
 out<<",\"portrayalPreparation\":{\"secondaryTextHits\":"<<lastProfile.portrayalSecondaryTextHits<<",\"secondaryTextMisses\":"<<lastProfile.portrayalSecondaryTextMisses<<",\"wholeLabelTexturesCreated\":"<<lastProfile.portrayalTextTexturesCreated<<"}";
 out<<",\"submittedVerticesOrIndices\":"<<lastProfile.submittedVertices<<",\"textureUploadBytes\":"<<lastProfile.textureUploadBytes<<",\"textureAllocationBytes\":"<<lastProfile.textureAllocationBytes<<",\"bufferUploadBytes\":"<<lastProfile.bufferUploadBytes<<",\"bufferAllocationBytes\":"<<lastProfile.bufferAllocationBytes<<",\"uploadCalls\":"<<lastProfile.uploadCalls<<",\"unknownUploadSizes\":"<<lastProfile.unknownUploadSizes;
 out<<",\"dashSegmentsSubmitted\":"<<lastProfile.dashSegmentsSubmitted<<",\"dashBatchesSubmitted\":"<<lastProfile.dashBatchesSubmitted;
 out<<",\"solidLineIndexBatchEnabled\":"<<(batchSolidLines()?"true":"false");
 out<<",\"areaTargetAllocations\":"<<lastProfile.areaTargetAllocations<<",\"areaTargetSwitches\":"<<lastProfile.areaTargetSwitches<<",\"areaFramebufferCopies\":0";
 const auto& coverage=lastProfile.coverageWork;
 out<<",\"coverageWork\":{\"scope\":\"outer GLES coverage check; repeated preparation/query checks retain their enclosing phase\",\"keyLookups\":"<<coverage.keyLookups<<",\"keyHits\":"<<coverage.keyHits<<",\"builds\":"<<coverage.builds<<",\"residentCells\":"<<coverage.residentCells<<",\"rasterPixels\":"<<coverage.rasterPixels<<",\"scanRows\":"<<coverage.scanRows<<",\"ownershipPixelChecks\":"<<coverage.ownershipPixelChecks<<",\"stencilUploads\":"<<coverage.stencilUploads<<",\"stencilUploadBytes\":"<<coverage.stencilUploadBytes<<"}";
 out<<",\"glQueries\":{\"state\":"<<lastQueries.state<<",\"locations\":"<<lastQueries.locations<<",\"capabilities\":"<<lastQueries.capabilityInit<<",\"validation\":"<<lastQueries.validation<<",\"errors\":"<<lastQueries.errors<<"},\"glSetters\":{";
 for(size_t i=0;i<lastSetters.size();i++){if(i)out<<",";const auto& v=lastSetters[i];out<<"\""<<glSetterNames[i]<<"\":{\"requested\":"<<v.requested<<",\"forwarded\":"<<v.forwarded<<",\"skipped\":"<<v.skipped<<"}";}
 out<<"},\"stages\":{";for(size_t i=0;i<lastProfile.stages.size();i++){if(i)out<<",";const auto& v=lastProfile.stages[i];out<<"\""<<stageNames[i]<<"\":{\"wallMs\":"<<v.wallMs<<",\"cpuMs\":";if(lastProfile.cpuAvailable)out<<v.cpuMs;else out<<"null";out<<",\"calls\":"<<v.calls<<"}";}
 out<<"},\"detailsMeasured\":"<<(lastProfile.detailed?"true":"false")<<",\"detailsNonAdditive\":true,\"details\":{";
 if(lastProfile.detailed)for(size_t i=0;i<lastProfile.details.size();i++){if(i)out<<",";const auto& v=lastProfile.details[i];out<<"\""<<detailNames[i]<<"\":{\"wallMs\":"<<v.wallMs<<",\"cpuMs\":";if(lastProfile.detailCpuAvailable)out<<v.cpuMs;else out<<"null";out<<",\"calls\":"<<v.calls<<"}";}
 out<<"}}";return out.str();
}
std::string GlesCompositor::diagnostics() const {
  if(!attached())return "Surface/context: detached; no active rendering. No CPU fallback.";
  std::string identity=vendor+" "+renderer;for(char& c:identity)c=std::tolower(static_cast<unsigned char>(c));
  bool software=false;for(auto token:{"llvmpipe","softpipe","swiftshader","software","swrast"})if(identity.find(token)!=std::string::npos)software=true;
  bool translated=false;for(auto token:{"angle","emulator","translator","virgl","gfxstream"})if(identity.find(token)!=std::string::npos)translated=true;
  std::ostringstream out;
  out<<std::fixed<<std::setprecision(3);
  out<<"Surface: "<<surfaceKind<<" "<<width<<"x"<<height
     <<"; output slot "<<selectedPresentationSlot<<" of "<<presentationSurfaceCount
     <<"\nContext: EGL "<<eglVersion<<" / "<<version
     <<"\nGL_VENDOR: "<<vendor<<"\nGL_RENDERER: "<<renderer
     <<"\nDriver: "<<(software?"SOFTWARE GL (not device GPU)":translated?"translated/emulated; host GPU unverified":"hardware candidate; verify on physical device")
     <<"\nPath: "<<(presentedFrames?"S52 direct GLES -> FBO -> Surface":"awaiting first successful GLES frame")
     <<"\nFBO: "<<frameWidth<<"x"<<frameHeight<<"; presented frames: "<<presentedFrames
     <<"\nGL draw calls before presentation (last frame): "<<lastDrawCalls
     <<"\nPresentation: "<<(centeredRasterOutput?"centered 1:1 producer raster; TextureView applies visible crop":"uniform aspect fit; no axis stretching")
     <<"\nVisible canonical frame: "<<(centeredRasterOutput?visibleRasterWidth:frameWidth)<<"x"<<(centeredRasterOutput?visibleRasterHeight:frameHeight)
     <<"\nCPU render/submit wall ms (last / mean): ";
  if(presentedFrames)out<<lastTiming.submitMs<<" / "<<totalSubmitMs/presentedFrames;else out<<"unavailable";
  out<<"\nEGL swap wall ms (last / mean): ";
  if(presentedFrames)out<<lastTiming.swapMs<<" / "<<totalSwapMs/presentedFrames;else out<<"unavailable";
  out<<"\nPresent interval ms (includes idle): ";
  if(lastTiming.hasInterval)out<<lastTiming.intervalMs;else out<<"unavailable (first frame)";
  out<<"\nGPU timer: "<<gpuTimer.status()<<"; sampling="<<(gpuTimer.samplingEnabled()?"on":"off")<<"; pending="<<gpuTimer.pendingCount()
     <<"\nEGL swap includes driver work and waits; wall and thread CPU are not GPU duration. Means since context creation."
     <<"\nCPU chart bitmap upload: prohibited; CPU fallback: none"
     <<"\nTextures for symbols/text/patterns are expected. Driver strings alone are not hardware proof.";
  auto cache=glLocationCaches.find(context);
  if(cache!=glLocationCaches.end()) {
    out<<"\nStartup GL checks: "<<cache->second.startupErrorChecks
       <<"; "<<(cache->second.startupTrace?"active until first successful presentation":"inactive (warm frames unprobed)");
    if(!cache->second.startupFailure.empty())out<<"\nFirst startup failure: "<<cache->second.startupFailure;
    out<<"\nShader location cache hits / driver queries (context total): "<<cache->second.hits<<" / "<<cache->second.driverQueries;
    out<<"\nGL delegate driver queries last frame: state="<<lastQueries.state<<" locations="<<lastQueries.locations<<" capabilities="<<lastQueries.capabilityInit;
    out<<"\nGL validation last frame: framebuffer/compile-link="<<lastQueries.validation<<" errors="<<lastQueries.errors<<"; debug verification total="<<cache->second.queries.debug;
    out<<"\nGL delegate managed state reads / forwarded commands (context total): "<<cache->second.stateHits<<" / "<<cache->second.commands;
    GlSetterCounts totals;for(const auto& value:lastSetters){totals.requested+=value.requested;totals.forwarded+=value.forwarded;totals.skipped+=value.skipped;}
    out<<"\nGL setter dedup: "<<(cache->second.setterDedup?"enabled":"disabled")<<"; last frame requested="<<totals.requested<<" forwarded="<<totals.forwarded<<" skipped="<<totals.skipped;
    out<<"\nGL setters last frame (requested / forwarded / skipped; actual driver calls only in GL state/uniforms timing):";
    for(std::size_t i=0;i<lastSetters.size();++i){const auto& value=lastSetters[i];if(value.requested)out<<"\nsetter "<<glSetterNames[i]<<": "<<value.requested<<" / "<<value.forwarded<<" / "<<value.skipped;}
    if(!cache->second.specialStateQueries.empty()){out<<"\nUnmanaged state queries (context total):";for(auto [name,count]:cache->second.specialStateQueries)out<<" 0x"<<std::hex<<name<<std::dec<<"="<<count;}
  }
  if(lastProfileEnabled&&presentedFrames){
    out<<"\nSecondary text cache last frame (valid labels): hits="<<lastProfile.secondaryTextCacheHits<<" misses="<<lastProfile.secondaryTextCacheMisses
       <<"; whole-label textures created="<<lastProfile.textTexturesCreated;
    out<<"\nStages for frame "<<lastProfile.frame<<" (wall / thread CPU ms; additive):";
    for(std::size_t i=0;i<lastProfile.stages.size();++i){const auto& value=lastProfile.stages[i];out<<"\n"<<stageNames[i]<<": "<<value.wallMs<<" / ";if(lastProfile.cpuAvailable)out<<value.cpuMs;else out<<"unavailable";}
    if(lastDetailedProfile.frame){
      const auto& sample=lastDetailedProfile;
      out<<"\nDeep sample frame "<<sample.frame<<" (first/every30; nested, DO NOT SUM)"
         <<"\nSample viewport "<<sample.width<<"x"<<sample.height<<" lat/lon "<<std::setprecision(6)<<sample.latitude<<","<<sample.longitude<<" ppm "<<sample.ppm<<" rotation "<<sample.rotation<<std::setprecision(3);
      for(std::size_t i=0;i<sample.details.size();++i){const auto& value=sample.details[i];out<<"\n"<<detailNames[i]<<": "<<value.wallMs<<" ms / "<<value.calls<<" calls";}
    }
  out<<"\nThread CPU includes driver CPU; wall minus CPU is NOT GPU time. Selected GL APIs only. Sampling has overhead; no glFinish/readback added.";
  if(lastProfile.areaTargetSwitches)out<<"\nS01: distinct output attachment; framebuffer copies=0; target storage allocations this frame="<<lastProfile.areaTargetAllocations<<".";
  }else out<<"\nStage profiling: unavailable/disabled";
  return out.str();
}
RgbaTarget GlesCompositor::readCompletedDiagnosticFrame(std::uint64_t expectedFrame,int expectedWidth,int expectedHeight){
 auto requireCapture=[](bool condition,const char* key){if(!condition)
  throw std::logic_error(std::string("Parity capture requires the current complete unscaled native frame without reserve/reprojection/marker; condition=")+key);};
 requireCapture(attached(),"ATTACHED");
 requireCapture(completedOutputValid,"COMPLETED_OUTPUT");
 requireCapture(expectedFrame>0&&lastProfile.frame==expectedFrame,"EXACT_COMPLETED_FRAME");
 requireCapture(renderStarted==Clock::time_point{},"NO_FRAME_IN_PROGRESS");
 requireCapture(lastSurfaceSwapSubmitted,"REAL_SURFACE_SWAP");
 requireCapture(outputFramebuffer!=0,"OWNED_OUTPUT_FBO");
 requireCapture(expectedWidth>=1&&expectedWidth<=MaxChartWidth&&expectedHeight>=1&&expectedHeight<=MaxChartHeight,"BOUNDED_EXPECTED_GEOMETRY");
 requireCapture(frameWidth==expectedWidth&&frameHeight==expectedHeight,"EXACT_NATIVE_FBO_GEOMETRY");
 requireCapture(lastProfile.width==expectedWidth&&lastProfile.height==expectedHeight,"EXACT_COMPLETED_PROFILE_GEOMETRY");
 // Queue padding is not a larger native FBO: OFF renders the complete canonical
 // image into its owned FBO and copies it 1:1 into the padded queue's centre.
 const bool ordinaryOneToOne=!centeredRasterOutput&&width==expectedWidth&&height==expectedHeight;
 const bool centeredCanonicalOneToOne=centeredRasterOutput&&visibleRasterWidth==expectedWidth&&visibleRasterHeight==expectedHeight&&
  width>=expectedWidth&&height>=expectedHeight;
 requireCapture(ordinaryOneToOne||centeredCanonicalOneToOne,"ONE_TO_ONE_CANONICAL_OUTPUT");
 requireCapture(!areaReuseHit,"NO_REPROJECTION");
 requireCapture(!stageProbeEnabled,"NO_STAGE_MARKER");
 makeCurrent();
 if(no5FirstGlError!=GL_NO_ERROR)throw std::runtime_error("Parity capture refuses a prior GLES error");
 GLint previousFramebuffer=0,previousPack=4;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&previousFramebuffer);glGetIntegerv(GL_PACK_ALIGNMENT,&previousPack);
 struct RestoreReadState{GLint framebuffer,pack;~RestoreReadState(){glPixelStorei(GL_PACK_ALIGNMENT,pack);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);}} restore{previousFramebuffer,previousPack};
 RgbaTarget result;result.width=expectedWidth;result.height=expectedHeight;
 result.rgba.resize(size_t(expectedWidth)*expectedHeight*4);
 glBindFramebuffer(GL_FRAMEBUFFER,outputFramebuffer);glPixelStorei(GL_PACK_ALIGNMENT,1);
 glReadPixels(0,0,expectedWidth,expectedHeight,GL_RGBA,GL_UNSIGNED_BYTE,result.rgba.data());
 if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Parity output-frame readback failed");
 const size_t rowBytes=size_t(expectedWidth)*4;
 for(int y=0;y<expectedHeight/2;y++)std::swap_ranges(result.rgba.begin()+size_t(y)*rowBytes,result.rgba.begin()+size_t(y+1)*rowBytes,result.rgba.begin()+size_t(expectedHeight-1-y)*rowBytes);
 return result;
}
#if !defined(__ANDROID__) || defined(NO5_LINE_BATCH_PROBE)
RgbaTarget GlesCompositor::readPresentation() {
  makeCurrent();RgbaTarget result;result.width=width;result.height=height;
  std::vector<uint8_t> bottomUp(size_t(width)*height*4);result.rgba.resize(bottomUp.size());
  glBindFramebuffer(GL_FRAMEBUFFER,0);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,bottomUp.data());
  for(int y=0;y<height;y++)std::copy_n(bottomUp.data()+size_t(height-y-1)*width*4,size_t(width)*4,result.rgba.data()+size_t(y)*width*4);
  if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Presentation test readback failed");return result;
}
RgbaTarget GlesCompositor::readback() {
  makeCurrent();RgbaTarget result;result.width=frameWidth;result.height=frameHeight;
  result.rgba.resize(size_t(frameWidth)*frameHeight*4);std::vector<uint8_t> bottomUp(result.rgba.size());
  glBindFramebuffer(GL_FRAMEBUFFER,outputFramebuffer);glReadPixels(0,0,frameWidth,frameHeight,GL_RGBA,GL_UNSIGNED_BYTE,bottomUp.data());
  for(int y=0;y<frameHeight;y++)std::copy_n(bottomUp.data()+size_t(frameHeight-y-1)*frameWidth*4,size_t(frameWidth)*4,result.rgba.data()+size_t(y)*frameWidth*4);
  glBindFramebuffer(GL_FRAMEBUFFER,0);if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("GPU test readback failed");return result;
}
#endif
}
