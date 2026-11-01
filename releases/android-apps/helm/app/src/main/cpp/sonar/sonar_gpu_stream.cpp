#include <jni.h>
#include <android/native_window_jni.h>
#include <android/trace.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr int bins=1024,page=256,maximum=12000;
std::atomic<int> contexts{},surfaces{},textureCount{},ownerCount{};
std::atomic<int64_t> allocatedPayloadBytes{},nextId{1};
struct TraceScope{explicit TraceScope(const char* name){ATrace_beginSection(name);}~TraceScope(){ATrace_endSection();}};
void check(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
void glcheck(){check(glGetError()==GL_NO_ERROR,"SONAR_GLES_ERROR");}
void fail(JNIEnv* e,const std::exception& x){auto c=e->FindClass("java/lang/IllegalStateException");if(c)e->ThrowNew(c,x.what());}
const char* vertex=R"(#version 300 es
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0,1);}
)";
const char* common=R"(#version 300 es
precision highp float;precision highp int;precision highp usampler2DArray;
uniform highp usampler2DArray raw,means;uniform highp sampler2D meta;
uniform int capacity,head,count,autoMask;uniform float newest,gain,inverseGamma,threshold,gainOffset;
int slot(int age){return (head-1-age+capacity)%capacity;}
vec4 row(int s){return texelFetch(meta,ivec2(s%256,s/256),0);}
vec4 detail(int s){return texelFetch(meta,ivec2(s%256,s/256+(capacity+255)/256),0);}
float ageMs(int age){float d=newest-row(slot(age)).x;return d<0.0?d+16777216.0:d;}
bool valid(int s){return row(s).z>0.0&&row(s).w>0.0;}
bool compatible(int newer,int older){vec4 a=row(slot(newer)),b=row(slot(older));float gap=ageMs(older)-ageMs(newer);return a.z>0.0&&a.z==b.z&&a.w==b.w&&gap>0.0&&gap<=500.0;}
// The smoothing cache stores calibrated linear amplitude, independent of display controls.
float linearAmplitude(int s,int bin){return clamp(float(texelFetch(raw,ivec3(clamp(bin,0,1023),s%256,s/256),0).r)/max(row(s).w,1.0),0.0,1.0);}
float transfer(int s,float v){
 vec4 tone=texelFetch(meta,ivec2(s%256,s/256+2*((capacity+255)/256)),0);
 float g=(autoMask&1)!=0&&tone.w>0.5?clamp(tone.x*gainOffset,0.063095734,63.095734):gain;
 float ig=(autoMask&2)!=0&&tone.w>0.5?tone.y:inverseGamma;
 float t=(autoMask&4)!=0&&tone.w>0.5?tone.z:threshold;
 return pow(clamp((v*g-t)/max(1.0-t,0.000001),0.0,1.0),ig);
}
float intensity(int s,int bin){return transfer(s,linearAmplitude(s,bin));}
float linearSignal(int s,float bin){int a=int(floor(bin));return mix(linearAmplitude(s,a),linearAmplitude(s,min(a+1,1023)),fract(bin));}
float signal(int s,float bin){int a=int(floor(bin));return mix(intensity(s,a),intensity(s,min(a+1,1023)),fract(bin));}
float smoothed(int s,float bin){int a=int(floor(bin));float x=float(texelFetch(means,ivec3(clamp(a,0,1023),s%256,s/256),0).r)/65535.0;float y=float(texelFetch(means,ivec3(clamp(a+1,0,1023),s%256,s/256),0).r)/65535.0;return mix(x,y,fract(bin));}
)";
const char* meanBody=R"(
uniform int layer;layout(location=0)out highp uint result;
void main(){int s=layer*256+int(gl_FragCoord.y),bin=int(gl_FragCoord.x);result=0u;if(s>=capacity||!valid(s))return;
 int age=(head-1-s+capacity)%capacity;if(age>=count)return;
 float fallback=linearAmplitude(s,bin);result=uint(floor(fallback*65535.0+0.5));
 if(age<3||age+3>=count||ageMs(age+3)-ageMs(age-3)>2500.0)return;
 for(int i=-3;i<3;i++)if(!compatible(age+i,age+i+1))return;
 vec4 target=row(s),anchor=detail(s);float sum=0.0;
 for(int i=-3;i<=3;i++){int n=slot(age+i);vec4 m=row(n),b=detail(n);float at=float(bin);
  if(target.y>0.0&&m.y>0.0){float depth=float(bin)*target.y;if(anchor.y>0.5&&b.y>0.5)depth+=b.x-anchor.x;at=depth/m.y;}
  else if(target.y!=m.y)return;
  if(at<0.0||at>1023.0)return;sum+=linearSignal(n,at);
 }
 result=uint(floor(clamp(sum/7.0,0.0,1.0)*65535.0+0.5));
}
)";
const char* timeBody=R"(
uniform float horizon,plotLeft,plotRight;layout(location=0)out highp uvec4 mapping;
int lowerBound(float t,bool inclusive){int lo=0,hi=count;for(int i=0;i<15;i++){if(lo>=hi)break;int m=(lo+hi)/2;float a=ageMs(m);if(inclusive?a<t:a<=t)lo=m+1;else hi=m;}return lo;}
void main(){mapping=uvec4(0u);float x=gl_FragCoord.x;if(count<=0||x<plotLeft||x>=plotRight)return;
 float duration=horizon*1000.0,w=plotRight-plotLeft;
 float youngest=max(0.0,(plotRight-(x+0.5))/w*duration),oldest=(plotRight-(x-0.5))/w*duration;
 int first=lowerBound(youngest,true),end=lowerBound(oldest,false);
 if(first<end){mapping=uvec4(uint(first),uint(end-1),1u,0u);return;}
 float t=(plotRight-x)/w*duration;int older=lowerBound(t,true),n=older-1;
 if(n<0||older>=count||!compatible(n,older))return;
 float blend=clamp((t-ageMs(n))/max(ageMs(older)-ageMs(n),0.001),0.0,1.0);
 mapping=uvec4(uint(n),uint(older),2u,uint(floor(blend*65535.0+0.5)));
}
)";
const char* imageBody=R"(
uniform highp usampler2D timeMap;uniform highp sampler2D palette;
uniform vec4 plot,background;uniform float surfaceHeight,binTop,binBottom,axisMeters,scale,horizon;
uniform int smoothing,scope,bottomOverlay,trackCount;uniform float tracks[64];uniform vec4 accent;
out vec4 color;
float physicalBin(int s,float axisBin,float reference){vec4 m=row(s),b=detail(s);if(m.y==axisMeters)return axisBin;
 if(m.y>0.0&&axisMeters>0.0){float depth=axisBin*axisMeters;if(smoothing!=0&&reference>0.0&&b.y>0.5)depth+=b.x-reference;return depth/m.y;}
 return -1.0;}
float sampleValue(int age,float axisBin,float reference){int s=slot(age);if(!valid(s))return -1.0;
 float at=physicalBin(s,axisBin,reference);if(smoothing!=0&&reference>0.0&&detail(s).y>0.5&&row(s).y==axisMeters&&axisMeters>0.0)at+=(detail(s).x-reference)/axisMeters;
 if(at<0.0||at>1023.0)return -1.0;return smoothing!=0?transfer(s,smoothed(s,at)):signal(s,at);}
vec4 paint(float v){return texture(palette,vec2((clamp(v,0.0,1.0)*255.0+0.5)/256.0,0.5));}
void main(){vec2 p=vec2(gl_FragCoord.x,surfaceHeight-gl_FragCoord.y);color=vec4(0.0);
 if(p.y<plot.y||p.y>=plot.w)return;
 float axisBin=mix(binTop,binBottom,(p.y-plot.y)/(plot.w-plot.y));
 if(scope!=0&&p.x>=plot.z&&p.x<2000.0*scale){color=background;if(count<=0)return;
  int s=slot(0);float at=physicalBin(s,axisBin,0.0);if(!valid(s)||at<0.0||at>1023.0)return;
  float v=signal(s,at),lineX=plot.z+8.0*scale+v*max(1.0,2000.0*scale-plot.z-16.0*scale);
  if(abs(p.x-lineX)<=1.5*scale)color=accent;
  if(bottomOverlay!=0&&detail(s).y>0.5&&axisMeters>0.0){float y=plot.y+(detail(s).x/axisMeters-binTop)/(binBottom-binTop)*(plot.w-plot.y);if(abs(p.y-y)<=1.5*scale)color=accent;}return;
 }
 if(p.x<plot.x||p.x>=plot.z)return;color=background;
 uvec4 map=texelFetch(timeMap,ivec2(int(gl_FragCoord.x),0),0);
 float value=-1.0,reference=0.0;
 if(map.z!=0u){int a=int(map.x),b=int(map.y);vec4 da=detail(slot(a)),db=detail(slot(b));
  float weight=map.z==2u?float(map.w)/65535.0:clamp(((plot.z-p.x)/(plot.z-plot.x)*horizon*1000.0-ageMs(a))/max(ageMs(b)-ageMs(a),0.001),0.0,1.0);
  if(da.y>0.5&&db.y>0.5&&row(slot(a)).z==row(slot(b)).z)reference=mix(da.x,db.x,weight);
  if(map.z==1u){float total=0.0,peak=-1.0;int used=0;float context=row(slot(a)).z;
   for(int age=a;age<=b;age++){if(row(slot(age)).z!=context)break;float v=sampleValue(age,axisBin,reference);if(v>=0.0){total+=v;peak=max(peak,v);used++;}}
   if(used>0)value=smoothing!=0?total/float(used):peak;
  }else{float x=sampleValue(a,axisBin,reference),y=sampleValue(b,axisBin,reference);
   if(x>=0.0&&y>=0.0)value=smoothing!=0?mix(x,y,weight):(weight<0.5?x:y);
  }
 }
 if(value>=0.0)color=paint(value);
 vec2 grid=(p-plot.xy)/vec2((plot.z-plot.x)/8.0,(plot.w-plot.y)/6.0);
 if(min(fract(grid.x),1.0-fract(grid.x))<(4.0/max(plot.z-plot.x,1.0))||min(fract(grid.y),1.0-fract(grid.y))<(3.0/max(plot.w-plot.y,1.0)))color=mix(color,accent,0.13);
 if(bottomOverlay!=0&&reference>0.0&&axisMeters>0.0){float y=plot.y+(reference/axisMeters-binTop)/(binBottom-binTop)*(plot.w-plot.y);if(abs(p.y-y)<=1.5*scale)color=accent;}
 for(int i=0;i<64;i++){if(i>=trackCount)break;float y=plot.y+(tracks[i]-binTop)/(binBottom-binTop)*(plot.w-plot.y);if(p.x>=plot.z-30.0*scale&&p.x<plot.z-4.0*scale&&abs(p.y-y)<=1.5*scale)color=accent;}
}
)";
GLuint compile(GLenum kind,const std::string& source){GLuint s=glCreateShader(kind);check(s,"SONAR_SHADER_CREATE_FAILED");const char* p=source.c_str();glShaderSource(s,1,&p,nullptr);glCompileShader(s);GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
 if(!ok){std::array<char,2048> log{};glGetShaderInfoLog(s,log.size(),nullptr,log.data());glDeleteShader(s);throw std::runtime_error(std::string("SONAR_SHADER_COMPILE_FAILED: ")+log.data());}return s;}
GLuint program(const char* body){GLuint v=compile(GL_VERTEX_SHADER,vertex),f=0,p=0;try{f=compile(GL_FRAGMENT_SHADER,std::string(common)+body);p=glCreateProgram();check(p,"SONAR_PROGRAM_CREATE_FAILED");glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);GLint ok=0;glGetProgramiv(p,GL_LINK_STATUS,&ok);check(ok,"SONAR_PROGRAM_LINK_FAILED");}catch(...){if(p)glDeleteProgram(p);if(f)glDeleteShader(f);glDeleteShader(v);throw;}glDeleteShader(v);glDeleteShader(f);return p;}

struct Renderer {
 ANativeWindow* window=nullptr;EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;EGLConfig config{};
 std::array<GLuint,5> textures{};GLuint vao=0,fbo=0,rasterFbo=0,rasterColor=0,meanProgram=0,timeProgram=0,imageProgram=0;
 int capacity=0,pages=0,width=0,height=0,mapWidth=0,rasterWidth=0,rasterHeight=0,drawnMode=0,previousHead=-1,previousCount=-1;
 float rasterScale=1,designWidth=0,newest=0,gain=1,inverseGamma=1,threshold=0,gainOffset=1;int autoMask=0;
 std::array<jint,256> previousPalette{};bool paletteReady=false;
 std::array<float,4> logicalPlot{},plotBounds{};bool scope=false,bottomOverlay=false;
 std::vector<float> tracks;std::vector<uint8_t> dirty;std::vector<int> dirtySlots;bool allDirty=true;
 int64_t payload=0,rawRows=0,metadataRows=0,meanRows=0,frames=0;
 std::unordered_map<GLuint,std::unordered_map<std::string,GLint>> uniformLocations;int64_t uniformDriverQueries=0;
 Renderer(JNIEnv* env,jobject target,int cap):capacity(cap),pages((cap+255)/256),dirty(cap,0){
  check(cap>0&&cap<=maximum,"SONAR_CAPACITY_OUTSIDE_BUDGET");
  try{window=ANativeWindow_fromSurface(env,target);check(window,"SONAR_WINDOW_UNAVAILABLE");display=eglGetDisplay(EGL_DEFAULT_DISPLAY);check(display!=EGL_NO_DISPLAY&&eglInitialize(display,nullptr,nullptr),"SONAR_EGL_INITIALIZE_FAILED");
   const EGLint attrs[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};EGLint found=0;check(eglChooseConfig(display,attrs,&config,1,&found)&&found==1,"SONAR_EGL_CONFIG_FAILED");
   const EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};context=eglCreateContext(display,config,EGL_NO_CONTEXT,ctx);check(context!=EGL_NO_CONTEXT,"SONAR_EGL_CONTEXT_FAILED");contexts++;
   surface=eglCreateWindowSurface(display,config,window,nullptr);check(surface!=EGL_NO_SURFACE,"SONAR_EGL_SURFACE_FAILED");surfaces++;current();eglSwapInterval(display,1);
   const char* renderer=reinterpret_cast<const char*>(glGetString(GL_RENDERER));check(renderer&&!std::strstr(renderer,"SwiftShader")&&!std::strstr(renderer,"llvmpipe")&&!std::strstr(renderer,"Software"),"SONAR_SOFTWARE_GPU_REJECTED");
   GLint size=0,layers=0;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&size);glGetIntegerv(GL_MAX_ARRAY_TEXTURE_LAYERS,&layers);check(size>=2048&&layers>=pages,"SONAR_GPU_LIMIT_UNSUPPORTED");
   meanProgram=program(meanBody);timeProgram=program(timeBody);imageProgram=program(imageBody);glGenVertexArrays(1,&vao);glBindVertexArray(vao);glGenFramebuffers(1,&fbo);glGenFramebuffers(1,&rasterFbo);glGenRenderbuffers(1,&rasterColor);
   glGenTextures(textures.size(),textures.data());check(std::all_of(textures.begin(),textures.end(),[](GLuint t){return t!=0;}),"SONAR_TEXTURE_ALLOCATION_FAILED");textureCount+=textures.size();
   for(int i=0;i<5;i++){GLenum type=(i==0||i==2)?GL_TEXTURE_2D_ARRAY:GL_TEXTURE_2D;glActiveTexture(GL_TEXTURE0+i);glBindTexture(type,textures[i]);glTexParameteri(type,GL_TEXTURE_MIN_FILTER,i==3?GL_LINEAR:GL_NEAREST);glTexParameteri(type,GL_TEXTURE_MAG_FILTER,i==3?GL_LINEAR:GL_NEAREST);glTexParameteri(type,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(type,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    if(i==0||i==2)glTexStorage3D(type,1,GL_R16UI,bins,page,pages);else if(i==1)glTexStorage2D(type,1,GL_RGBA32F,page,pages*3);else if(i==3)glTexStorage2D(type,1,GL_RGBA8,256,1);
   }
   payload=static_cast<int64_t>(pages)*page*(bins*4+48)+1024;allocatedPayloadBytes+=payload;
   for(GLuint p:{meanProgram,timeProgram,imageProgram}){glUseProgram(p);integer(p,"raw",0);integer(p,"meta",1);integer(p,"means",2);integer(p,"palette",3);integer(p,"timeMap",4);}
   glcheck();
  }catch(...){release();throw;}
 }
 // Linked-program locations never change until this owned program is deleted.
 GLint location(GLuint p,const char* name){auto& linked=uniformLocations[p];auto found=linked.find(name);if(found!=linked.end())return found->second;GLint value=glGetUniformLocation(p,name);uniformDriverQueries++;linked.emplace(name,value);return value;}
 void integer(GLuint p,const char* name,int n){glUniform1i(location(p,name),n);}
 void scalar(GLuint p,const char* name,float v){glUniform1f(location(p,name),v);}
 void current(){check(context!=EGL_NO_CONTEXT&&surface!=EGL_NO_SURFACE,"SONAR_EGL_OWNER_CLOSED");if(eglGetCurrentContext()!=context||eglGetCurrentSurface(EGL_DRAW)!=surface||eglGetCurrentSurface(EGL_READ)!=surface)check(eglMakeCurrent(display,surface,surface,context),"SONAR_EGL_MAKE_CURRENT_FAILED");}
 void release()noexcept{
  if(display!=EGL_NO_DISPLAY&&context!=EGL_NO_CONTEXT){if(surface!=EGL_NO_SURFACE&&eglMakeCurrent(display,surface,surface,context)){
    for(GLuint p:{meanProgram,timeProgram,imageProgram})if(p)glDeleteProgram(p);if(vao)glDeleteVertexArrays(1,&vao);if(fbo)glDeleteFramebuffers(1,&fbo);if(rasterFbo)glDeleteFramebuffers(1,&rasterFbo);if(rasterColor)glDeleteRenderbuffers(1,&rasterColor);if(textures[0])glDeleteTextures(textures.size(),textures.data());
   }eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);}
  if(textures[0]){textureCount-=textures.size();textures.fill(0);}if(surface!=EGL_NO_SURFACE){eglDestroySurface(display,surface);surface=EGL_NO_SURFACE;surfaces--;}if(context!=EGL_NO_CONTEXT){eglDestroyContext(display,context);context=EGL_NO_CONTEXT;contexts--;}
  if(window){ANativeWindow_release(window);window=nullptr;}allocatedPayloadBytes-=payload;payload=0;
  // The default EGL display also belongs to Android/other renderers: never terminate it.
 }
 ~Renderer(){release();}
 void mark(int s){s=(s%capacity+capacity)%capacity;if(!dirty[s]){dirty[s]=1;dirtySlots.push_back(s);}}
 void changed(int first,int n){for(int s=first;s<first+n;s++)for(int k=-3;k<=3;k++)mark(s+k);}
 void upload(JNIEnv* e,int first,int n,jshortArray data){check(first>=0&&n>0&&n<=256&&first+n<=capacity&&first/256==(first+n-1)/256&&e->GetArrayLength(data)==n*bins,"SONAR_UPLOAD_BOUNDS");current();auto p=e->GetShortArrayElements(data,nullptr);check(p,"SONAR_UPLOAD_ALLOCATION_FAILED");glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[0]);glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,first%256,first/256,bins,n,1,GL_RED_INTEGER,GL_UNSIGNED_SHORT,p);e->ReleaseShortArrayElements(data,p,JNI_ABORT);rawRows+=n;changed(first,n);glcheck();}
 void metadata(JNIEnv* e,int first,int n,jfloatArray values){check(first>=0&&n>0&&n<=256&&first+n<=capacity&&first/256==(first+n-1)/256&&e->GetArrayLength(values)==n*8,"SONAR_METADATA_BOUNDS");current();std::array<float,2048> input{};e->GetFloatArrayRegion(values,0,n*8,input.data());check(!e->ExceptionCheck(),"SONAR_METADATA_COPY_FAILED");std::array<float,1024>a{},b{};
  for(int i=0;i<n;i++)for(int j=0;j<4;j++){a[i*4+j]=input[i*8+j];b[i*4+j]=input[i*8+4+j];}
  glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);glTexSubImage2D(GL_TEXTURE_2D,0,first%256,first/256,n,1,GL_RGBA,GL_FLOAT,a.data());glTexSubImage2D(GL_TEXTURE_2D,0,first%256,first/256+pages,n,1,GL_RGBA,GL_FLOAT,b.data());metadataRows+=n;changed(first,n);glcheck();
 }
 void toneRows(JNIEnv* e,int first,int n,jfloatArray values){
  check(first>=0&&n>0&&n<=256&&first+n<=capacity&&first/256==(first+n-1)/256&&e->GetArrayLength(values)==n*4,"SONAR_TONE_BOUNDS");
  current();std::array<float,1024> input{};e->GetFloatArrayRegion(values,0,n*4,input.data());check(!e->ExceptionCheck(),"SONAR_TONE_COPY_FAILED");
  for(int i=0;i<n;i++)check(std::isfinite(input[i*4])&&input[i*4]>0&&std::isfinite(input[i*4+1])&&input[i*4+1]>0&&std::isfinite(input[i*4+2])&&input[i*4+2]>=0&&input[i*4+2]<1,"SONAR_TONE_INVALID");
  glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);glTexSubImage2D(GL_TEXTURE_2D,0,first%256,first/256+pages*2,n,1,GL_RGBA,GL_FLOAT,input.data());glcheck();
 }
 void configure(JNIEnv* e,float db,float gamma,float cut,jintArray palette){check(std::isfinite(db)&&std::isfinite(gamma)&&gamma>0&&std::isfinite(cut)&&cut>=0&&cut<1&&e->GetArrayLength(palette)==256,"SONAR_TRANSFER_INVALID");current();float nextGain=std::pow(10.f,db/20.f),nextGamma=1.f/gamma;
  if(gain!=nextGain||inverseGamma!=nextGamma||threshold!=cut){gain=nextGain;inverseGamma=nextGamma;threshold=cut;}
  std::array<jint,256> colors{};std::array<uint8_t,1024> rgba{};e->GetIntArrayRegion(palette,0,256,colors.data());check(!e->ExceptionCheck(),"SONAR_PALETTE_COPY_FAILED");if(paletteReady&&colors==previousPalette)return;previousPalette=colors;paletteReady=true;for(int i=0;i<256;i++){auto v=static_cast<uint32_t>(colors[i]);rgba[i*4]=v>>16;rgba[i*4+1]=v>>8;rgba[i*4+2]=v;rgba[i*4+3]=v>>24;}
  glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,textures[3]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,1,GL_RGBA,GL_UNSIGNED_BYTE,rgba.data());glcheck();
 }
 void uniforms(GLuint p,int count,int head){glUseProgram(p);integer(p,"capacity",capacity);integer(p,"count",count);integer(p,"head",head);scalar(p,"newest",newest);scalar(p,"gain",gain);scalar(p,"inverseGamma",inverseGamma);scalar(p,"threshold",threshold);integer(p,"autoMask",autoMask);scalar(p,"gainOffset",gainOffset);}
 void prepareMeans(int count,int head){TraceScope trace("sonar.gpu.linear_means");
  if(allDirty){for(int s=0;s<capacity;s++)mark(s);allDirty=false;}
  if(previousHead!=head||previousCount!=count){for(int a=0;a<4&&a<count;a++){mark(head-1-a);mark(head-count+a);}previousHead=head;previousCount=count;}
  if(dirtySlots.empty())return;std::sort(dirtySlots.begin(),dirtySlots.end());uniforms(meanProgram,count,head);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,bins,page);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D_ARRAY,0);glEnable(GL_SCISSOR_TEST);
  for(size_t i=0;i<dirtySlots.size();){int first=dirtySlots[i],last=first;size_t j=i+1;while(j<dirtySlots.size()&&dirtySlots[j]==last+1&&dirtySlots[j]/256==first/256){last++;j++;}
   glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,textures[2],0,first/256);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_MEAN_FRAMEBUFFER_FAILED");integer(meanProgram,"layer",first/256);glScissor(0,first%256,bins,last-first+1);glDrawArrays(GL_TRIANGLES,0,3);meanRows+=last-first+1;i=j;
  }
  for(int s:dirtySlots)dirty[s]=0;dirtySlots.clear();glDisable(GL_SCISSOR_TEST);glBindFramebuffer(GL_FRAMEBUFFER,0);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[2]);glcheck();
 }
 // A bounded single-frame color target, not another RAW/Mean/Freeze history.
 // Keep final Window/overlay geometry exact; scale only this GPU raster before composition.
 void ensureRaster(){
  float ratio=std::min(1.f,1000.f/float(width));int rw=std::max(1,int(std::ceil(width*ratio))),rh=std::max(1,int(std::ceil(height*ratio)));
  if(rw==rasterWidth&&rh==rasterHeight)return;
  glBindRenderbuffer(GL_RENDERBUFFER,rasterColor);glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,rw,rh);
  glBindFramebuffer(GL_FRAMEBUFFER,rasterFbo);glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,rasterColor);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_COLOR_RASTER_FRAMEBUFFER_FAILED");glBindFramebuffer(GL_FRAMEBUFFER,0);
  int64_t delta=(static_cast<int64_t>(rw)*rh-static_cast<int64_t>(rasterWidth)*rasterHeight)*4;allocatedPayloadBytes+=delta;payload+=delta;rasterWidth=rw;rasterHeight=rh;glcheck();
 }
 void ensureMap(){if(mapWidth==rasterWidth)return;glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_2D,textures[4]);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32UI,rasterWidth,1,0,GL_RGBA_INTEGER,GL_UNSIGNED_INT,nullptr);allocatedPayloadBytes+=(rasterWidth-mapWidth)*16;payload+=(rasterWidth-mapWidth)*16;mapWidth=rasterWidth;glcheck();}
 int64_t draw(int count,int head,float time,float seconds,float top,float bottom,float meters,int mode,int background){TraceScope trace("sonar.gpu.raster");
  check(count>=0&&count<=capacity&&head>=0&&head<capacity&&seconds>0&&top>=0&&bottom<=1023&&top<bottom,"SONAR_DRAW_BOUNDS");current();newest=time;width=ANativeWindow_getWidth(window);height=ANativeWindow_getHeight(window);check(width>0&&height>0&&width<=16384&&height<=16384,"SONAR_VIEWPORT_INVALID");
  EGLint w=0,h=0;check(eglQuerySurface(display,surface,EGL_WIDTH,&w)&&eglQuerySurface(display,surface,EGL_HEIGHT,&h),"SONAR_EGL_BUFFER_QUERY_FAILED");
  if(w!=width||h!=height){check(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT),"SONAR_EGL_RESIZE_UNBIND_FAILED");check(eglDestroySurface(display,surface),"SONAR_EGL_RESIZE_DESTROY_FAILED");surface=EGL_NO_SURFACE;surfaces--;surface=eglCreateWindowSurface(display,config,window,nullptr);check(surface!=EGL_NO_SURFACE,"SONAR_EGL_RESIZE_CREATE_FAILED");surfaces++;current();check(eglQuerySurface(display,surface,EGL_WIDTH,&w)&&eglQuerySurface(display,surface,EGL_HEIGHT,&h)&&w==width&&h==height,"SONAR_EGL_BUFFER_VIEWPORT_MISMATCH");}
  if(designWidth>0){rasterScale=width/designWidth;for(int i=0;i<4;i++)plotBounds[i]=logicalPlot[i]*rasterScale;}else plotBounds={0,0,float(width),float(height)};
  glBindVertexArray(vao);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_DITHER);glDisable(GL_SCISSOR_TEST);
  if(mode==1)prepareMeans(count,head);ensureRaster();ensureMap();float rasterRatio=float(rasterWidth)/width;std::array<float,4> rasterPlot=plotBounds;for(float& v:rasterPlot)v*=rasterRatio;uniforms(timeProgram,count,head);scalar(timeProgram,"horizon",seconds);scalar(timeProgram,"plotLeft",rasterPlot[0]);scalar(timeProgram,"plotRight",rasterPlot[2]);glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_2D,0);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[4],0);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_TIME_MAP_FRAMEBUFFER_FAILED");glViewport(0,0,rasterWidth,1);glDrawArrays(GL_TRIANGLES,0,3);glcheck();
  glBindFramebuffer(GL_FRAMEBUFFER,rasterFbo);glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_2D,textures[4]);uniforms(imageProgram,count,head);glUniform4fv(location(imageProgram,"plot"),1,rasterPlot.data());scalar(imageProgram,"horizon",seconds);scalar(imageProgram,"surfaceHeight",rasterHeight);scalar(imageProgram,"binTop",top);scalar(imageProgram,"binBottom",bottom);scalar(imageProgram,"axisMeters",meters);scalar(imageProgram,"scale",rasterScale*rasterRatio);integer(imageProgram,"smoothing",mode==1?1:0);integer(imageProgram,"scope",scope?1:0);integer(imageProgram,"bottomOverlay",bottomOverlay?1:0);integer(imageProgram,"trackCount",tracks.size());if(!tracks.empty())glUniform1fv(location(imageProgram,"tracks"),tracks.size(),tracks.data());
  auto c=static_cast<uint32_t>(background);glUniform4f(location(imageProgram,"background"),((c>>16)&255)/255.f,((c>>8)&255)/255.f,(c&255)/255.f,1);if(c==0xff030000u)glUniform4f(location(imageProgram,"accent"),0.65f,0.25f,0.15f,1);else glUniform4f(location(imageProgram,"accent"),0.24f,0.78f,0.91f,1);
  glViewport(0,0,rasterWidth,rasterHeight);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);glDrawArrays(GL_TRIANGLES,0,3);glcheck();
  glBindFramebuffer(GL_READ_FRAMEBUFFER,rasterFbo);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,0);
  glBlitFramebuffer(0,0,rasterWidth,rasterHeight,0,0,width,height,GL_COLOR_BUFFER_BIT,GL_LINEAR);glBindFramebuffer(GL_FRAMEBUFFER,0);glcheck();
  auto stamp=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();auto present=reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));check(present&&present(display,surface,stamp),"SONAR_FRAME_TIMESTAMP_FAILED");{TraceScope swap("sonar.gpu.swap");check(eglSwapBuffers(display,surface),"SONAR_EGL_SWAP_FAILED");}drawnMode=mode;frames++;return stamp;
 }
};
std::mutex registryMutex;std::unordered_map<int64_t,std::unique_ptr<Renderer>> registry;
Renderer& owned(int64_t id){auto i=registry.find(id);check(i!=registry.end(),"SONAR_GPU_OWNER_CLOSED");return *i->second;}
}

#define JNI(name) Java_com_no5_helm_gpu_SonarGpuNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv* e,jobject,jobject surface,jint cap){std::lock_guard lock(registryMutex);try{auto r=std::make_unique<Renderer>(e,surface,cap);auto id=nextId++;registry.emplace(id,std::move(r));ownerCount++;return id;}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*,jobject,jlong id){std::lock_guard lock(registryMutex);if(registry.erase(id))ownerCount--;}
extern "C" JNIEXPORT void JNICALL JNI(upload)(JNIEnv* e,jobject,jlong id,jint first,jint n,jshortArray values){std::lock_guard lock(registryMutex);try{owned(id).upload(e,first,n,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(metadataRows)(JNIEnv* e,jobject,jlong id,jint first,jint n,jfloatArray values){std::lock_guard lock(registryMutex);try{owned(id).metadata(e,first,n,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(toneRows)(JNIEnv* e,jobject,jlong id,jint first,jint n,jfloatArray values){std::lock_guard lock(registryMutex);try{owned(id).toneRows(e,first,n,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(toneControl)(JNIEnv* e,jobject,jlong id,jint mask,jfloat offset){std::lock_guard lock(registryMutex);try{check(mask>=0&&mask<=7&&std::isfinite(offset)&&offset>=-24&&offset<=24,"SONAR_TONE_CONTROL_INVALID");auto&r=owned(id);r.autoMask=mask;r.gainOffset=std::pow(10.f,offset/20.f);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(configure)(JNIEnv* e,jobject,jlong id,jfloat db,jfloat gamma,jfloat threshold,jintArray palette){std::lock_guard lock(registryMutex);try{owned(id).configure(e,db,gamma,threshold,palette);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(effects)(JNIEnv* e,jobject,jlong id,jboolean scope,jboolean bottom,jfloatArray tracks){std::lock_guard lock(registryMutex);try{auto&r=owned(id);int n=e->GetArrayLength(tracks);check(n<=64,"SONAR_TRACK_LIMIT");r.scope=scope;r.bottomOverlay=bottom;r.tracks.resize(n);if(n)e->GetFloatArrayRegion(tracks,0,n,r.tracks.data());}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(plot)(JNIEnv* e,jobject,jlong id,jfloat left,jfloat top,jfloat right,jfloat bottom,jfloat designWidth){std::lock_guard lock(registryMutex);try{auto&r=owned(id);check(std::isfinite(designWidth)&&designWidth>=0,"SONAR_DESIGN_VIEWPORT_INVALID");if(designWidth>0)check(left>=0&&top>=0&&right>left&&bottom>top&&right<=designWidth,"SONAR_LOGICAL_PLOT_INVALID");r.logicalPlot={left,top,right,bottom};r.designWidth=designWidth;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(rasterScale)(JNIEnv* e,jobject,jlong id,jfloat scale){std::lock_guard lock(registryMutex);try{check(scale>0&&scale<=16,"SONAR_RASTER_SCALE_INVALID");owned(id).rasterScale=scale;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jlong JNICALL JNI(draw)(JNIEnv* e,jobject,jlong id,jint count,jint head,jfloat newest,jfloat seconds,jfloat top,jfloat bottom,jfloat meters,jint mode,jint,jint background){std::lock_guard lock(registryMutex);try{return owned(id).draw(count,head,newest,seconds,top,bottom,meters,mode,background);}catch(const std::exception& x){fail(e,x);return -1;}}
extern "C" JNIEXPORT void JNICALL JNI(dimensions)(JNIEnv* e,jobject,jlong id,jintArray output){std::lock_guard lock(registryMutex);try{auto&r=owned(id);check(e->GetArrayLength(output)==2,"SONAR_DIMENSIONS_LENGTH");jint d[]={r.width,r.height};e->SetIntArrayRegion(output,0,2,d);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL JNI(viewportInfo)(JNIEnv* e,jobject,jlong id,jfloatArray output){std::lock_guard lock(registryMutex);try{auto&r=owned(id);int n=e->GetArrayLength(output);check(n==5||n==7,"SONAR_VIEWPORT_INFO_LENGTH");jfloat d[]={r.plotBounds[0],r.plotBounds[1],r.plotBounds[2]-r.plotBounds[0],r.plotBounds[3]-r.plotBounds[1],r.rasterScale,r.drawnMode==1?1.f:0.f,0};e->SetFloatArrayRegion(output,0,n,d);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jlongArray JNICALL JNI(resources)(JNIEnv* e,jobject){jlong v[]={ownerCount.load(),contexts.load(),surfaces.load(),textureCount.load(),allocatedPayloadBytes.load()};auto out=e->NewLongArray(5);if(out)e->SetLongArrayRegion(out,0,5,v);return out;}
extern "C" JNIEXPORT jlongArray JNICALL JNI(metrics)(JNIEnv* e,jobject,jlong id){std::lock_guard lock(registryMutex);try{auto&r=owned(id);jlong v[]={r.rawRows,r.metadataRows,r.meanRows,r.frames,r.payload};auto out=e->NewLongArray(5);if(out)e->SetLongArrayRegion(out,0,5,v);return out;}catch(const std::exception& x){fail(e,x);return nullptr;}}
#ifdef NO5_SONAR_FAULT_TEST
extern "C" JNIEXPORT jintArray JNICALL Java_com_no5_helm_gpu_SonarGpuStreamProof_row(JNIEnv* e,jobject,jlong id,jint slot,jboolean mean){std::lock_guard lock(registryMutex);try{
 auto&r=owned(id);check(slot>=0&&slot<r.capacity,"SONAR_PROOF_ROW_BOUNDS");r.current();glBindFramebuffer(GL_FRAMEBUFFER,r.fbo);
 glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,r.textures[mean?2:0],0,slot/256);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_PROOF_ROW_FBO");
 std::array<GLuint,4096> values{};glReadPixels(0,slot%256,1024,1,GL_RGBA_INTEGER,GL_UNSIGNED_INT,values.data());glBindFramebuffer(GL_FRAMEBUFFER,0);glcheck();
 std::array<jint,1024> result{};for(int i=0;i<1024;i++)result[i]=values[i*4];auto output=e->NewIntArray(1024);if(output)e->SetIntArrayRegion(output,0,1024,result.data());return output;
}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT jstring JNICALL JNI(hardwareDescription)(JNIEnv* e,jobject,jlong id){std::lock_guard lock(registryMutex);try{owned(id).current();std::string s=reinterpret_cast<const char*>(glGetString(GL_VENDOR));s+=" / ";s+=reinterpret_cast<const char*>(glGetString(GL_RENDERER));s+=" / ";s+=reinterpret_cast<const char*>(glGetString(GL_VERSION));return e->NewStringUTF(s.c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
#endif
