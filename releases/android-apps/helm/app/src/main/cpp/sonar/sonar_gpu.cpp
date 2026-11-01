#include <jni.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstring>
#include <GLES3/gl3.h>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr int bins=1024, page=256, maximum=12000;
std::atomic<int> contexts{}, surfaces{}, textureCount{}, ownerCount{};
std::atomic<int64_t> allocatedPayloadBytes{};
int64_t payloadBytes(int cap){return 131072+static_cast<int64_t>((cap+page-1)/page)*page*(bins*4+32)+65536*16+256*64*16+static_cast<int64_t>((cap+2047)/2048)*2048*bins*4;}
std::atomic<int64_t> nextId{1};
void check(bool good,const char* detail) { if(!good) throw std::runtime_error(detail); }
void glcheck() { check(glGetError()==GL_NO_ERROR,"SONAR_GLES_ERROR"); }
const char* vertex=R"(#version 300 es
precision highp float;
out vec2 uv;
void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=vec2(p.x,1.0-p.y);gl_Position=vec4(p*2.0-1.0,0,1);}
)";
const char* fragment=R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2DArray;
precision highp sampler2D;
in vec2 uv;
out vec4 color;
uniform highp usampler2DArray raw,smoothed;
uniform highp sampler2D meta,rawColors,intensityColors,transfer,spans;
uniform highp sampler2DArray displayColors,pairColors;
uniform int capacity,head,count,mode,smoothing,transparentStage;
uniform float newest,horizon,binTop,binBottom,axisMeters,pixelWidth,pixelHeight,surfaceHeight,plotTop;
uniform vec4 background;
int slot(int age){return (head-1-age+capacity)%capacity;}
vec4 info(int age){int s=slot(age);return texelFetch(meta,ivec2(s%256,s/256),0);}
vec4 detail(int age){int s=slot(age);return texelFetch(meta,ivec2(s%256,s/256+(capacity+255)/256),0);}
uint sampleAt(int age,int bin){int s=slot(age);return texelFetch(raw,ivec3(clamp(bin,0,1023),s%256,s/256),0).r;}
float codeAt(int age,int bin){int s=slot(age);return float(texelFetch(smoothed,ivec3(clamp(bin,0,1023),s%256,s/256),0).r);}
vec4 palette(float code){int c=int(floor(clamp(code,0.0,65535.0)+0.5));return texelFetch(intensityColors,ivec2(c%256,c/256),0);}
vec4 rowColor(int age,int bin){if(mode==1)return palette(codeAt(age,bin));uint value=sampleAt(age,bin);return texelFetch(rawColors,ivec2(int(value)%256,int(value)/256),0);}
float alignedBin(float bin,float from,float to){if(from==to)return bin;if(from>0.0&&to>0.0)return bin*from/to;return -1.0;}
vec4 interpolatedRowColor(int age,float bin){int s=slot(age);return texture(displayColors,vec3((float(s%2048)+0.5)/2048.0,(bin+0.5)/1024.0,float(s/2048)));}
float interpolatedCode(int age,float bin){int low=int(floor(bin));return mix(codeAt(age,low),codeAt(age,min(low+1,1023)),bin-float(low));}
vec4 preparedPairRow(int age,int other,float row,float spacing,float oldBottom,float newBottom,bool anchored){
    float reference=(oldBottom+newBottom)*0.5;
    float a=spacing>0.0?row*spacing/info(age).y:row;
    float b=spacing>0.0?row*spacing/info(other).y:row;
    if(anchored){a=(row*spacing+oldBottom-reference)/info(age).y;b=(row*spacing+newBottom-reference)/info(other).y;}
    if(a<0.0||a>1023.0||b<0.0||b>1023.0)return vec4(0.0);
    return palette(interpolatedCode(age,a));
}
void main(){
    color=transparentStage!=0?vec4(0):background;
    if(count<=0||horizon<=0.0)return;
    int x=int(gl_FragCoord.x);vec4 span=texelFetch(spans,ivec2(x%256,x/256),0);
    if(span.x<0.0)return;
    int older=int(span.x),newerAge=int(span.y);
    float y=uv.y*surfaceHeight-plotTop;
    float worldBin=mix(binTop,binBottom,y/pixelHeight);
    vec4 m=info(older);
    if(span.w>0.5){
        float bin;
        if(m.y==axisMeters){
            float first=floor(binTop),last=min(1024.0,ceil(binBottom)+1.0);
            bin=first+y/pixelHeight*(last-first)-(mode==1?0.5:0.0);
        }else{
            bin=alignedBin(worldBin,axisMeters,m.y);
            if(bin<0.0||bin>1023.0)return;
            if(mode!=1)bin+=0.5;
        }
        color=mode==1?interpolatedRowColor(older,bin):rowColor(older,int(floor(bin)));
    }else{
        vec4 n=info(newerAge);
        float weight=span.z;
        bool anchored=detail(older).y>0.5&&detail(newerAge).y>0.5&&detail(older).x!=detail(newerAge).x;
        float oldBottom=detail(older).x,newBottom=detail(newerAge).x;
        int os=slot(older),ns=slot(newerAge);
        if(!anchored&&m.y==n.y&&ns==os+1&&os/2048==ns/2048){
            float bin=alignedBin(worldBin,axisMeters,m.y);
            if(bin<0.0||bin>1023.0)return;
            color=texture(displayColors,vec3((float(os%2048)+0.5+weight)/2048.0,(bin+0.5)/1024.0,float(os/2048)));
            return;
        }
        // Differently sampled/registered pairs are drawn below with their exact
        // prepared rectangle and shear, rather than another per-fragment remapping.
        return;
        if(color.a==0.0)color=background;
    }
    // Canvas' stored 8-bit display pixels are the comparison domain.
    color=floor(clamp(color,0.0,1.0)*255.0+0.5)/255.0;
}
)";
GLuint shader(GLenum type,const char* source) {
    GLuint id=glCreateShader(type); check(id!=0,"SONAR_SHADER_CREATE_FAILED");
    glShaderSource(id,1,&source,nullptr);glCompileShader(id);
    GLint good{};glGetShaderiv(id,GL_COMPILE_STATUS,&good);
    if(!good){std::array<char,2048> log{};GLsizei length{};glGetShaderInfoLog(id,log.size(),&length,log.data());glDeleteShader(id);throw std::runtime_error(std::string("SONAR_SHADER_COMPILE_FAILED: ")+std::string(log.data(),std::max(0,std::min(static_cast<int>(length),2047))));}
    return id;
}
struct Renderer {
    ANativeWindow* window{};
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    EGLConfig config{};
    GLuint program{},vao{};
    std::array<GLuint,9> textures{};
    struct Pair {double spacing{},oldBottom{},newBottom{};uint32_t oldColor{},newColor{};bool valid=false;};
    std::vector<Pair> pairs;
    bool pairStorage=false;
    bool rasterCullEnabled=true,singleSample=false,paletteOpaque=false;
    std::vector<uint8_t> columnOccluded,pixelCovered;
    // Conservative32-Float-ULP margin accounts for vertex/viewport rounding.
    double rasterMargin()const{return std::max(1.0/1024.0,static_cast<double>(std::max(width,height))*32*1.1920928955078125e-7);}
    bool hasPixelCenter(float left,float right)const{
        const double margin=rasterMargin();
        return std::max(0.0,std::ceil(static_cast<double>(left)-margin-.5))<
               std::min(static_cast<double>(width),std::ceil(static_cast<double>(right)+margin-.5));
    }

    int64_t pairBytes=0;
    // Sixteen recomputable pair rows; no retained Java array or raw history.
    std::array<uint8_t,131072> pairScratch{};
    int pairBatchFirst=-1,pairBatchCount=0;

    GLuint colorProgram{},colorFramebuffer{},columnProgram{},columnVao{},columnBuffer{};
    GLuint ridgeProgram{},ridgeVao{},ridgeBuffer{};
    GLuint pairProgram{},pairVao{},pairBuffer{};
    std::vector<float> pairVertices;
    std::vector<float> ridgeVertices;
    std::vector<float> columnVertices;
    std::vector<bool> colorDirty;
    std::vector<int> colorFirst,colorLast;
    bool fixtureFullColorRepaint=false;
    int lastColorColumns=0;
    void invalidateAllColors(){
        std::fill(colorDirty.begin(),colorDirty.end(),true);
        std::fill(colorFirst.begin(),colorFirst.end(),0);std::fill(colorLast.begin(),colorLast.end(),2048);
    }

    int colorMode=-1;
    std::vector<float> metaValues,spanValues;
    std::vector<double> exactGeometry;
    float rasterScale=1.f;
    std::array<float,4> logicalPlot{};
    float designWidth=0,targetLeft=0,targetTop=0,targetRight=0,targetBottom=0;
    int capacity{},width{},height{},bufferWidth{},bufferHeight{};
    int64_t frameId{};int drawnMode=-1;
    std::vector<uint8_t> colorScratch;
    Renderer(JNIEnv* env,jobject target,int cap):capacity(cap) {
        check(cap>0&&cap<=maximum,"SONAR_CAPACITY_OUTSIDE_BUDGET");
        try {
            window=ANativeWindow_fromSurface(env,target);check(window,"SONAR_WINDOW_UNAVAILABLE");
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            check(display!=EGL_NO_DISPLAY&&eglInitialize(display,nullptr,nullptr),"SONAR_EGL_INITIALIZE_FAILED");
            // Never eglTerminate the process-wide display: Map owns independent EGL resources.
            const EGLint attributes[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,
                EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
            EGLint found{};
            check(eglChooseConfig(display,attributes,&config,1,&found)&&found==1,"SONAR_EGL_CONFIG_FAILED");
            const EGLint contextAttributes[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
            context=eglCreateContext(display,config,EGL_NO_CONTEXT,contextAttributes);
            check(context!=EGL_NO_CONTEXT,"SONAR_EGL_CONTEXT_FAILED");contexts++;
            surface=eglCreateWindowSurface(display,config,window,nullptr);
            check(surface!=EGL_NO_SURFACE,"SONAR_EGL_SURFACE_FAILED");surfaces++;
            current();eglSwapInterval(display,1);
            EGLint sampleCount=0;check(eglGetConfigAttrib(display,config,EGL_SAMPLES,&sampleCount),"SONAR_EGL_SAMPLE_QUERY_FAILED");singleSample=sampleCount==0;
            // Own all raster state explicitly. RGBA8 palette/interpolation values
            // must not acquire the implementation-dependent default GL dither.
            glDisable(GL_DITHER);
            const auto* renderer=reinterpret_cast<const char*>(glGetString(GL_RENDERER));
            check(renderer&&std::strstr(renderer,"SwiftShader")==nullptr&&std::strstr(renderer,"llvmpipe")==nullptr&&std::strstr(renderer,"Software")==nullptr,"SONAR_SOFTWARE_GPU_REJECTED");
            check(eglGetProcAddress("eglPresentationTimeANDROID"),"SONAR_FRAME_TIMESTAMP_UNSUPPORTED");
            GLint size{},layers{};glGetIntegerv(GL_MAX_TEXTURE_SIZE,&size);glGetIntegerv(GL_MAX_ARRAY_TEXTURE_LAYERS,&layers);
            check(size>=2048&&layers>=(cap+page-1)/page,"SONAR_GPU_LIMIT_UNSUPPORTED");
            GLuint vs=shader(GL_VERTEX_SHADER,vertex),fs{};
            try{fs=shader(GL_FRAGMENT_SHADER,fragment);}catch(...){glDeleteShader(vs);throw;}
            program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
            glDeleteShader(vs);glDeleteShader(fs);GLint good{};glGetProgramiv(program,GL_LINK_STATUS,&good);
            check(good,"SONAR_SHADER_LINK_FAILED");
            glGenVertexArrays(1,&vao);glBindVertexArray(vao);
            glGenTextures(9,textures.data());check(std::all_of(textures.begin(),textures.end(),[](GLuint x){return x!=0;}),"SONAR_GPU_TEXTURE_ALLOCATION_FAILED");textureCount+=9;
            for(int i=0;i<9;i++){
                GLenum targetType=(i==0||i==5||i==7||i==8)?GL_TEXTURE_2D_ARRAY:GL_TEXTURE_2D;
                glActiveTexture(GL_TEXTURE0+i);glBindTexture(targetType,textures[i]);
                glTexParameteri(targetType,GL_TEXTURE_MIN_FILTER,(i==7||i==8)?GL_LINEAR:GL_NEAREST);glTexParameteri(targetType,GL_TEXTURE_MAG_FILTER,(i==7||i==8)?GL_LINEAR:GL_NEAREST);
                glTexParameteri(targetType,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(targetType,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
                if(i==0||i==5)glTexStorage3D(targetType,1,GL_R16UI,bins,page,(cap+page-1)/page);
                else if(i==1)glTexStorage2D(targetType,1,GL_RGBA32F,page,2*((cap+page-1)/page));
                else if(i==7)glTexStorage3D(targetType,1,GL_RGBA8,2048,bins,(cap+2047)/2048);
                else if(i==8)glTexStorage3D(targetType,1,GL_RGBA8,1,1,1);
                else if(i==6)glTexStorage2D(targetType,1,GL_RGBA32F,page,64);
                else glTexStorage2D(targetType,1,i==4?GL_R32F:GL_RGBA8,256,256);
            }
            glUseProgram(program);
            const char* names[]={"raw","meta","rawColors","intensityColors","transfer","smoothed","spans","displayColors","pairColors"};
            for(int i=0;i<9;i++)glUniform1i(glGetUniformLocation(program,names[i]),i);
            colorDirty.assign((cap+2047)/2048,true);colorFirst.assign(colorDirty.size(),0);colorLast.assign(colorDirty.size(),2048);pairs.resize(cap);
            colorPipeline();columnPipeline();ridgePipeline();pairPipeline();
            metaValues.resize(((capacity+page-1)/page)*page*8);colorScratch.resize(65536*4);glcheck();
        }catch(...){release();throw;}
    }
    void current(){
        check(context!=EGL_NO_CONTEXT&&surface!=EGL_NO_SURFACE,"SONAR_EGL_MAKE_CURRENT_FAILED");
        if(eglGetCurrentContext()!=context||eglGetCurrentSurface(EGL_DRAW)!=surface||eglGetCurrentSurface(EGL_READ)!=surface)
            check(eglMakeCurrent(display,surface,surface,context),"SONAR_EGL_MAKE_CURRENT_FAILED");
    }
    void release() noexcept {
        if(display!=EGL_NO_DISPLAY&&context!=EGL_NO_CONTEXT){
            if(surface!=EGL_NO_SURFACE&&eglMakeCurrent(display,surface,surface,context)){
                if(textures[0]){glDeleteTextures(9,textures.data());textureCount-=9;textures.fill(0);}
                if(pairProgram){glDeleteProgram(pairProgram);pairProgram=0;}if(pairVao){glDeleteVertexArrays(1,&pairVao);pairVao=0;}if(pairBuffer){glDeleteBuffers(1,&pairBuffer);pairBuffer=0;}
                if(ridgeProgram){glDeleteProgram(ridgeProgram);ridgeProgram=0;}if(ridgeVao){glDeleteVertexArrays(1,&ridgeVao);ridgeVao=0;}if(ridgeBuffer){glDeleteBuffers(1,&ridgeBuffer);ridgeBuffer=0;}
                if(columnProgram){glDeleteProgram(columnProgram);columnProgram=0;}if(columnVao){glDeleteVertexArrays(1,&columnVao);columnVao=0;}if(columnBuffer){glDeleteBuffers(1,&columnBuffer);columnBuffer=0;}
                if(colorProgram){glDeleteProgram(colorProgram);colorProgram=0;}if(colorFramebuffer){glDeleteFramebuffers(1,&colorFramebuffer);colorFramebuffer=0;}
                if(program){glDeleteProgram(program);program=0;}if(vao){glDeleteVertexArrays(1,&vao);vao=0;}
            } else if(textures[0]){textureCount-=9;textures.fill(0);} // Context destruction owns all its GL objects.
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE){eglDestroySurface(display,surface);surface=EGL_NO_SURFACE;surfaces--;}
        if(context!=EGL_NO_CONTEXT){eglDestroyContext(display,context);context=EGL_NO_CONTEXT;contexts--;}
        if(window){ANativeWindow_release(window);window=nullptr;}
        if(pairBytes){allocatedPayloadBytes-=pairBytes;pairBytes=0;}
        colorScratch.clear();
    }
    ~Renderer(){release();}
    void upload(JNIEnv* env,int first,int columns,jshortArray values,int texture=0){
        check(first>=0&&columns>0&&columns<=page&&first+columns<=capacity&&first%page+columns<=page,"SONAR_UPLOAD_BOUNDS");
        check(env->GetArrayLength(values)==columns*bins,"SONAR_UPLOAD_LENGTH");current();
        jshort* data=env->GetShortArrayElements(values,nullptr);check(data,"SONAR_UPLOAD_ALLOCATION_FAILED");
        glActiveTexture(GL_TEXTURE0+texture);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[texture]);
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,0,first%page,first/page,bins,columns,1,GL_RED_INTEGER,GL_UNSIGNED_SHORT,data);
        env->ReleaseShortArrayElements(values,data,JNI_ABORT);
        for(int slot=first;slot<first+columns;slot++){
            int layer=slot/2048,column=slot%2048;colorDirty[layer]=true;
            colorFirst[layer]=std::min(colorFirst[layer],column);colorLast[layer]=std::max(colorLast[layer],column+1);
        }glcheck();
    }
    void flushPairBatch(){
        if(pairBatchCount==0)return;
        glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[8]);
        // A live update often contains fewer than16 pairs. Pack those rows
        // tightly instead of passing a128-byte stride for a tiny GPU rectangle.
        // This reuses the same bounded buffer and changes no pixel value.
        if(pairBatchCount<16)for(int row=1;row<1024;row++)
            std::memmove(pairScratch.data()+row*pairBatchCount*8,pairScratch.data()+row*128,pairBatchCount*8);
        glTexSubImage3D(GL_TEXTURE_2D_ARRAY,0,(pairBatchFirst%1024)*2,0,pairBatchFirst/1024,pairBatchCount*2,1024,1,GL_RGBA,GL_UNSIGNED_BYTE,pairScratch.data());
        std::fill(pairScratch.begin(),pairScratch.end(),0);pairBatchFirst=-1;pairBatchCount=0;glcheck();
    }
    void temporalPair(JNIEnv* env,int slot,jintArray values,double spacing,double oldBottom,double newBottom,uint32_t oldColor,uint32_t newColor){
        check(slot>=0&&slot<capacity&&env->GetArrayLength(values)==2048,"SONAR_TEMPORAL_PAIR_BOUNDS");current();
        glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[8]);
        if(!pairStorage){
            glDeleteTextures(1,&textures[8]);glGenTextures(1,&textures[8]);check(textures[8],"SONAR_PAIR_TEXTURE_ALLOCATION_FAILED");glBindTexture(GL_TEXTURE_2D_ARRAY,textures[8]);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glTexStorage3D(GL_TEXTURE_2D_ARRAY,1,GL_RGBA8,2048,1024,(capacity+1023)/1024);glcheck();pairStorage=true;
            pairBytes=static_cast<int64_t>((capacity+1023)/1024)*2048*1024*4;allocatedPayloadBytes+=pairBytes;
        }
        if(pairBatchCount&&(slot!=pairBatchFirst+pairBatchCount||slot/1024!=pairBatchFirst/1024||pairBatchCount==16))flushPairBatch();
        if(pairBatchCount==0)pairBatchFirst=slot;
        std::array<jint,2048> argb{};env->GetIntArrayRegion(values,0,2048,argb.data());check(!env->ExceptionCheck(),"SONAR_PAIR_COPY_FAILED");
        for(int i=0;i<2048;i++){
            auto c=static_cast<uint32_t>(argb[i]);int at=((i/2)*32+pairBatchCount*2+i%2)*4;
            pairScratch[at]=c>>16;pairScratch[at+1]=c>>8;pairScratch[at+2]=c;pairScratch[at+3]=c>>24;
        }
        pairBatchCount++;pairs[slot]={spacing,oldBottom,newBottom,oldColor,newColor,true};
        if(pairBatchCount==16)flushPairBatch();
    }
    void metadata(JNIEnv* env,jfloatArray values){
        int length=env->GetArrayLength(values);check(length==((capacity+page-1)/page)*page*4||length==((capacity+page-1)/page)*page*8,"SONAR_METADATA_LENGTH");current();
        jfloat* data=env->GetFloatArrayElements(values,nullptr);check(data,"SONAR_METADATA_ALLOCATION_FAILED");
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);
        std::fill(metaValues.begin(),metaValues.end(),0.f);std::copy(data,data+length,metaValues.begin());
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,page,2*((capacity+page-1)/page),GL_RGBA,GL_FLOAT,metaValues.data());
        env->ReleaseFloatArrayElements(values,data,JNI_ABORT);glcheck();
    }
    void lut(JNIEnv* env,jintArray raw,jintArray colors,jfloatArray transfer){
        check(env->GetArrayLength(raw)==65536&&env->GetArrayLength(colors)==65536&&env->GetArrayLength(transfer)==65536,"SONAR_LUT_LENGTH");current();
        paletteOpaque=true;
        jintArray arrays[]={raw,colors};
        for(int a=0;a<2;a++){
            jint* data=env->GetIntArrayElements(arrays[a],nullptr);check(data,"SONAR_LUT_ALLOCATION_FAILED");
            for(int i=0;i<65536;i++){uint32_t v=static_cast<uint32_t>(data[i]);if((v>>24)!=255)paletteOpaque=false;colorScratch[i*4]=v>>16;colorScratch[i*4+1]=v>>8;colorScratch[i*4+2]=v;colorScratch[i*4+3]=v>>24;}
            env->ReleaseIntArrayElements(arrays[a],data,JNI_ABORT);
            glActiveTexture(GL_TEXTURE2+a);glBindTexture(GL_TEXTURE_2D,textures[2+a]);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,colorScratch.data());
        }
        jfloat* data=env->GetFloatArrayElements(transfer,nullptr);check(data,"SONAR_TRANSFER_ALLOCATION_FAILED");
        glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_2D,textures[4]);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,256,GL_RED,GL_FLOAT,data);
        env->ReleaseFloatArrayElements(transfer,data,JNI_ABORT);invalidateAllColors();glcheck();
    }
    void colorPipeline(){
        const char* frag=R"(#version 300 es
precision highp float;precision highp int;precision highp usampler2DArray;
uniform highp usampler2DArray raw,smoothed;uniform highp sampler2D rawColors,intensityColors;
uniform int pageOffset,capacity,mode;out vec4 color;
void main(){int s=pageOffset+int(gl_FragCoord.x),bin=int(gl_FragCoord.y);color=vec4(0.0);if(s>=capacity)return;
ivec3 p=ivec3(bin,s%256,s/256);uint value=mode==1?texelFetch(smoothed,p,0).r:texelFetch(raw,p,0).r;
ivec2 c=ivec2(int(value)%256,int(value)/256);color=mode==1?texelFetch(intensityColors,c,0):texelFetch(rawColors,c,0);}
)";
        GLuint vs=shader(GL_VERTEX_SHADER,vertex),fs{};try{fs=shader(GL_FRAGMENT_SHADER,frag);}catch(...){glDeleteShader(vs);throw;}
        colorProgram=glCreateProgram();glAttachShader(colorProgram,vs);glAttachShader(colorProgram,fs);glLinkProgram(colorProgram);glDeleteShader(vs);glDeleteShader(fs);
        GLint good{};glGetProgramiv(colorProgram,GL_LINK_STATUS,&good);check(good,"SONAR_COLOR_CACHE_LINK_FAILED");
        glGenFramebuffers(1,&colorFramebuffer);check(colorFramebuffer,"SONAR_COLOR_FRAMEBUFFER_FAILED");
        glUseProgram(colorProgram);glUniform1i(glGetUniformLocation(colorProgram,"raw"),0);glUniform1i(glGetUniformLocation(colorProgram,"smoothed"),5);
        glUniform1i(glGetUniformLocation(colorProgram,"rawColors"),2);glUniform1i(glGetUniformLocation(colorProgram,"intensityColors"),3);
    }
    void prepareColors(int mode){
        if(colorMode!=mode){invalidateAllColors();colorMode=mode;}
        if(fixtureFullColorRepaint)invalidateAllColors();
        lastColorColumns=0;
        glUseProgram(colorProgram);glBindVertexArray(vao);glBindFramebuffer(GL_FRAMEBUFFER,colorFramebuffer);
        glViewport(0,0,2048,bins);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);
        // The target is detached from samplers while it is an FBO destination.
        glActiveTexture(GL_TEXTURE7);glBindTexture(GL_TEXTURE_2D_ARRAY,0);
        glUniform1i(glGetUniformLocation(colorProgram,"capacity"),capacity);glUniform1i(glGetUniformLocation(colorProgram,"mode"),mode);
        for(int layer=0;layer<static_cast<int>(colorDirty.size());layer++)if(colorDirty[layer]){
            glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,textures[7],0,layer);
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_COLOR_FRAMEBUFFER_INCOMPLETE");
            check(colorFirst[layer]>=0&&colorFirst[layer]<colorLast[layer]&&colorLast[layer]<=2048,"SONAR_COLOR_DIRTY_RANGE_INVALID");
            glEnable(GL_SCISSOR_TEST);glScissor(colorFirst[layer],0,colorLast[layer]-colorFirst[layer],bins);
            glUniform1i(glGetUniformLocation(colorProgram,"pageOffset"),layer*2048);glDrawArrays(GL_TRIANGLES,0,3);
            lastColorColumns+=colorLast[layer]-colorFirst[layer];colorDirty[layer]=false;colorFirst[layer]=2048;colorLast[layer]=0;
        }
        glBindFramebuffer(GL_FRAMEBUFFER,0);glActiveTexture(GL_TEXTURE7);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[7]);glcheck();
    }
    void columnPipeline(){
        const char* vert=R"(#version 300 es
precision highp float;layout(location=0)in vec2 position;layout(location=1)in vec3 coordinate;
uniform vec2 viewport;out highp vec3 tc;
void main(){tc=coordinate;gl_Position=vec4(position*vec2(2.0/viewport.x,-2.0/viewport.y)+vec2(-1.0,1.0),0.0,1.0);}
)";
        const char* frag=R"(#version 300 es
precision mediump float;precision highp sampler2DArray;in highp vec3 tc;out vec4 color;uniform highp sampler2DArray colors;
void main(){color=texture(colors,tc);}
)";
        GLuint vs=shader(GL_VERTEX_SHADER,vert),fs{};try{fs=shader(GL_FRAGMENT_SHADER,frag);}catch(...){glDeleteShader(vs);throw;}
        columnProgram=glCreateProgram();glAttachShader(columnProgram,vs);glAttachShader(columnProgram,fs);glLinkProgram(columnProgram);glDeleteShader(vs);glDeleteShader(fs);
        GLint good{};glGetProgramiv(columnProgram,GL_LINK_STATUS,&good);check(good,"SONAR_COLUMN_LINK_FAILED");
        glGenVertexArrays(1,&columnVao);glGenBuffers(1,&columnBuffer);check(columnVao&&columnBuffer,"SONAR_COLUMN_BUFFER_FAILED");
        glBindVertexArray(columnVao);glBindBuffer(GL_ARRAY_BUFFER,columnBuffer);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,5*sizeof(float),nullptr);
        glEnableVertexAttribArray(1);glVertexAttribPointer(1,3,GL_FLOAT,GL_FALSE,5*sizeof(float),reinterpret_cast<void*>(2*sizeof(float)));
        glUseProgram(columnProgram);glUniform1i(glGetUniformLocation(columnProgram,"colors"),7);
    }
    std::pair<float,float> measuredRectangle(int age,int ringHead,float newTime,float seconds,int mode)const{
        int slot=(ringHead-1-age+capacity)%capacity;
        auto info=[&](int s,int field){return metaValues[s*4+field];};
        const float logicalWidth=targetRight-targetLeft;
        float w=(mode==0&&age>0)?std::max(1.f,static_cast<float>(std::clamp(static_cast<double>(info((slot+1)%capacity,0)-info(slot,0)),0.,150.)/(seconds*1000.)*logicalWidth)):1.f;
        float timePixels=static_cast<float>((static_cast<double>(newTime)-info(slot,0))/(static_cast<double>(seconds)*1000.)*logicalWidth);
        float center=mode==2?targetRight-2.5f-timePixels:mode==1?std::max(targetLeft+.5f,targetRight-2.5f-timePixels):
            std::clamp(targetRight-timePixels,targetLeft+w*.5f,targetRight-2.f-w*.5f);
        return {center-w*.5f,center+w*.5f};
    }
    void prepareOpaqueVisibility(int visible,int ringHead,float newTime,float seconds,double axis,int mode){
        columnOccluded.assign(visible,0);
        if(!rasterCullEnabled||!singleSample||!paletteOpaque||visible!=capacity)return;
        pixelCovered.assign(width,0);const double margin=rasterMargin();
        // Newer fully opaque columns prove older equal-coverage pixels invisible.
        // Keep original RAW, coordinates, colors and draw order; never aggregate.
        for(int age=0;age<visible;age++){
            int slot=(ringHead-1-age+capacity)%capacity;
            double spacing=exactGeometry.empty()?static_cast<double>(metaValues[slot*4+1]):exactGeometry[slot];
            if(!(spacing==axis||(std::isnan(spacing)&&std::isnan(axis))))continue;
            // A partially filled texture page can interpolate its padded alpha0.
            if(slot==capacity-1&&capacity%2048!=0)continue;
            auto rect=measuredRectangle(age,ringHead,newTime,seconds,mode);
            float left=rect.first*rasterScale,right=rect.second*rasterScale;
            int begin=std::clamp(static_cast<int>(std::ceil(static_cast<double>(left)-margin-.5)),0,width);
            int end=std::clamp(static_cast<int>(std::ceil(static_cast<double>(right)+margin-.5)),0,width);
            bool hidden=true;for(int x=begin;x<end;x++)if(!pixelCovered[x]){hidden=false;break;}
            if(hidden)columnOccluded[age]=1;
            int opaqueBegin=std::clamp(static_cast<int>(std::ceil(static_cast<double>(left)+margin-.5)),0,width);
            int opaqueEnd=std::clamp(static_cast<int>(std::ceil(static_cast<double>(right)-margin-.5)),0,width);
            for(int x=opaqueBegin;x<opaqueEnd;x++)pixelCovered[x]=1;
        }
    }
    void measuredColumns(int visible,int ringHead,float newTime,float seconds,double top,double bottom,double axis,int mode){
        columnVertices.clear();columnVertices.reserve(static_cast<size_t>(capacity)*30);
        prepareOpaqueVisibility(visible,ringHead,newTime,seconds,axis,mode);
        auto info=[&](int slot,int field){return metaValues[slot*4+field];};
        // The reference first rounds its logical Float rectangles, then Canvas applies
        // the Float device transform. Combining these operations changes half-pixel
        // interpolation at non-integral density/viewport scales.
        const float logicalHeight=targetBottom-targetTop;
        auto add=[&](float x,float y,float u,float v,float layer){columnVertices.insert(columnVertices.end(),{x*rasterScale,y*rasterScale,u,v,layer});};
        for(int age=visible-1;age>=0;age--){
            int slot=(ringHead-1-age+capacity)%capacity;
            if(columnOccluded[age])continue;
            auto rect=measuredRectangle(age,ringHead,newTime,seconds,mode);float left=rect.first,right=rect.second;
            if(right<=targetLeft||left>=targetRight)continue;
            float sourceTop=std::floor(top),sourceBottom=std::min(1024.,std::ceil(bottom)+1.),yTop=targetTop,yBottom=targetBottom;
            const double spacing=exactGeometry.empty()?static_cast<double>(info(slot,1)):exactGeometry[slot];
            if(!(spacing==axis||(std::isnan(spacing)&&std::isnan(axis)))){
                if(!std::isfinite(spacing)||!std::isfinite(axis)||spacing<=0||axis<=0)continue;
                // Preserve the reference's operation order before final Float device rectangles.
                const double axisEnd=1023.0*spacing/axis;
                double pixels=axisEnd/1023.0/(bottom-top)*logicalHeight;
                double zero=targetTop+(0.0-top)/(bottom-top)*logicalHeight;
                double last=targetTop+(axisEnd-top)/(bottom-top)*logicalHeight;
                // Explicit measured coverage clips geometry; sampling never supplies deeper water.
                yTop=static_cast<float>(zero-pixels*.5);yBottom=static_cast<float>(last+pixels*.5);
                sourceTop=0;sourceBottom=1024;
            }
            float u0=(slot%2048)/2048.f,u1=(slot%2048+1)/2048.f,layer=slot/2048;
            float v0=sourceTop/1024.f,v1=sourceBottom/1024.f;
            add(left,yTop,u0,v0,layer);add(left,yBottom,u0,v1,layer);add(right,yTop,u1,v0,layer);
            add(right,yTop,u1,v0,layer);add(left,yBottom,u0,v1,layer);add(right,yBottom,u1,v1,layer);
        }
        glActiveTexture(GL_TEXTURE7);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[7]);
        glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,mode==1?GL_LINEAR:GL_NEAREST);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,mode==1?GL_LINEAR:GL_NEAREST);
        glUseProgram(columnProgram);glUniform2f(glGetUniformLocation(columnProgram,"viewport"),width,height);glBindVertexArray(columnVao);glBindBuffer(GL_ARRAY_BUFFER,columnBuffer);
        glBufferData(GL_ARRAY_BUFFER,columnVertices.size()*sizeof(float),columnVertices.data(),GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES,0,static_cast<GLsizei>(columnVertices.size()/5));glcheck();
    }
    void pairPipeline(){
        const char* vert=R"(#version 300 es
precision highp float;layout(location=0)in vec2 position;layout(location=1)in vec3 coordinate;layout(location=2)in vec4 rectangle;
uniform vec2 viewport;out highp vec3 tc;flat out highp vec4 clipBounds;
void main(){tc=coordinate;clipBounds=rectangle;gl_Position=vec4(position*vec2(2.0/viewport.x,-2.0/viewport.y)+vec2(-1.0,1.0),0,1);}
)";
        const char* frag=R"(#version 300 es
precision mediump float;precision highp sampler2DArray;uniform highp sampler2DArray colors;uniform highp float viewportHeight;
in highp vec3 tc;flat in highp vec4 clipBounds;out vec4 color;
void main(){highp vec2 p=vec2(gl_FragCoord.x,viewportHeight-gl_FragCoord.y);if(p.x<clipBounds.x||p.y<clipBounds.y||p.x>=clipBounds.z||p.y>=clipBounds.w)discard;color=texture(colors,tc);}
)";
        GLuint vs=shader(GL_VERTEX_SHADER,vert),fs{};try{fs=shader(GL_FRAGMENT_SHADER,frag);}catch(...){glDeleteShader(vs);throw;}
        pairProgram=glCreateProgram();glAttachShader(pairProgram,vs);glAttachShader(pairProgram,fs);glLinkProgram(pairProgram);glDeleteShader(vs);glDeleteShader(fs);
        GLint good{};glGetProgramiv(pairProgram,GL_LINK_STATUS,&good);check(good,"SONAR_PAIR_QUAD_LINK_FAILED");
        glGenVertexArrays(1,&pairVao);glGenBuffers(1,&pairBuffer);check(pairVao&&pairBuffer,"SONAR_PAIR_QUAD_BUFFER_FAILED");
        glBindVertexArray(pairVao);glBindBuffer(GL_ARRAY_BUFFER,pairBuffer);
        const int sizes[]={2,3,4};int offset=0;
        for(int a=0;a<3;a++){glEnableVertexAttribArray(a);glVertexAttribPointer(a,sizes[a],GL_FLOAT,GL_FALSE,9*sizeof(float),reinterpret_cast<void*>(offset*sizeof(float)));offset+=sizes[a];}
        glUseProgram(pairProgram);glUniform1i(glGetUniformLocation(pairProgram,"colors"),8);
    }
    void temporalQuads(int visible,int ringHead,float newest,float seconds,double top,double bottom,double axis,int mode){
        if(mode!=1)return;
        pairVertices.clear();const float logicalWidth=targetRight-targetLeft,logicalHeight=targetBottom-targetTop;
        for(int age=visible-2;age>=0;age--){
            int newer=(ringHead-1-age+capacity)%capacity,older=(newer-1+capacity)%capacity;const auto& pair=pairs[newer];
            auto info=[&](int slot,int field){return metaValues[slot*4+field];};
            const int detail=((capacity+255)/256)*256*4;
            const bool anchored=metaValues[detail+newer*4+1]>.5f&&metaValues[detail+older*4+1]>.5f&&metaValues[detail+newer*4]!=metaValues[detail+older*4];
            if(!anchored&&info(older,1)==info(newer,1)&&newer==older+1&&older/2048==newer/2048)continue;
            if(!pair.valid||info(older,2)<=0||info(older,2)!=info(newer,2)||info(older,3)!=info(newer,3))continue;
            double gap=info(newer,0)-info(older,0);if(gap<=0||gap>500)continue;
            float oldX=targetRight-2.5f-static_cast<float>((static_cast<double>(newest)-info(older,0))/(static_cast<double>(seconds)*1000.)*logicalWidth);
            float newX=targetRight-2.5f-static_cast<float>((static_cast<double>(newest)-info(newer,0))/(static_cast<double>(seconds)*1000.)*logicalWidth);
            float w=newX-oldX;if(w<=0||(w<=1&&!anchored))continue;
            if(rasterCullEnabled&&singleSample&&!hasPixelCenter(oldX*rasterScale,newX*rasterScale))continue;
            double scale=((std::isnan(pair.spacing)&&std::isnan(axis))||pair.spacing==axis)?1.0:pair.spacing/axis;
            if(!std::isfinite(scale)||scale<=0)continue;
            double binPixels=scale/(bottom-top)*logicalHeight,zero=targetTop-top/(bottom-top)*logicalHeight,last=zero+1023*binPixels;
            float clipTop=static_cast<float>(std::max(static_cast<double>(targetTop),zero)),clipBottom=static_cast<float>(std::min(static_cast<double>(targetBottom),last));if(clipTop>=clipBottom)continue;
            float slope=0,offset=0;
            if(anchored){double metresToPixels=logicalHeight/(axis*(bottom-top));double shift=(pair.oldBottom-pair.newBottom)/2*metresToPixels;
                slope=static_cast<float>((pair.newBottom-pair.oldBottom)*metresToPixels/w);offset=static_cast<float>(shift-slope*oldX);}
            float left=oldX-w/2,right=newX+w/2,y0=static_cast<float>(zero-binPixels/2),y1=static_cast<float>(last+binPixels/2);
            const float u0=(newer%1024)*2/2048.f,u1=((newer%1024)*2+2)/2048.f,layer=newer/1024;
            auto add=[&](float x,float y,float u,float v){float transformedY=slope*x+y+offset;
                pairVertices.insert(pairVertices.end(),{x*rasterScale,transformedY*rasterScale,u,v,layer,oldX*rasterScale,clipTop*rasterScale,newX*rasterScale,clipBottom*rasterScale});};
            add(left,y0,u0,0);add(left,y1,u0,1);add(right,y0,u1,0);add(right,y0,u1,0);add(left,y1,u0,1);add(right,y1,u1,1);
        }
        if(pairVertices.empty())return;
        glUseProgram(pairProgram);glUniform2f(glGetUniformLocation(pairProgram,"viewport"),width,height);glUniform1f(glGetUniformLocation(pairProgram,"viewportHeight"),height);
        glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[8]);glBindVertexArray(pairVao);glBindBuffer(GL_ARRAY_BUFFER,pairBuffer);
        glBufferData(GL_ARRAY_BUFFER,pairVertices.size()*sizeof(float),pairVertices.data(),GL_STREAM_DRAW);glDrawArrays(GL_TRIANGLES,0,static_cast<GLsizei>(pairVertices.size()/9));glcheck();
    }
    void ridgePipeline(){
        const char* vert=R"(#version 300 es
precision highp float;layout(location=0)in vec2 position;layout(location=1)in vec4 ends;layout(location=2)in vec4 clipRectangle;layout(location=3)in vec4 firstColor;layout(location=4)in vec4 lastColor;
uniform vec2 viewport;flat out highp vec4 segment;flat out highp vec4 bounds;flat out highp vec4 a;flat out highp vec4 b;
void main(){segment=ends;bounds=clipRectangle;a=firstColor;b=lastColor;gl_Position=vec4(position*vec2(2.0/viewport.x,-2.0/viewport.y)+vec2(-1.0,1.0),0,1);}
)";
        const char* frag=R"(#version 300 es
precision highp float;uniform float viewportHeight,halfWidth;flat in highp vec4 segment;flat in highp vec4 bounds;flat in highp vec4 a;flat in highp vec4 b;out vec4 color;
void main(){vec2 p=vec2(gl_FragCoord.x,viewportHeight-gl_FragCoord.y);color=vec4(0);
if(p.x<bounds.x||p.x>=bounds.z||p.y<bounds.y||p.y>=bounds.w)return;
vec2 delta=segment.zw-segment.xy;float segmentLength=length(delta);if(segmentLength<=0.0)return;vec2 direction=delta/segmentLength;vec2 relative=p-segment.xy;
float along=dot(relative,direction);float perpendicular=abs(dot(relative,vec2(-direction.y,direction.x)));
float distance=max(perpendicular-halfWidth,max(-along,along-segmentLength));float coverage=clamp(0.5-distance,0.0,1.0);
float weight=clamp((p.x-segment.x)/(segment.z-segment.x),0.0,1.0);color=mix(a,b,weight)*coverage;}
)";
        GLuint vs=shader(GL_VERTEX_SHADER,vert),fs{};try{fs=shader(GL_FRAGMENT_SHADER,frag);}catch(...){glDeleteShader(vs);throw;}
        ridgeProgram=glCreateProgram();glAttachShader(ridgeProgram,vs);glAttachShader(ridgeProgram,fs);glLinkProgram(ridgeProgram);glDeleteShader(vs);glDeleteShader(fs);
        GLint good{};glGetProgramiv(ridgeProgram,GL_LINK_STATUS,&good);check(good,"SONAR_RIDGE_LINK_FAILED");
        glGenVertexArrays(1,&ridgeVao);glGenBuffers(1,&ridgeBuffer);check(ridgeVao&&ridgeBuffer,"SONAR_RIDGE_BUFFER_FAILED");
        glBindVertexArray(ridgeVao);glBindBuffer(GL_ARRAY_BUFFER,ridgeBuffer);
        const int sizes[]={2,4,4,4,4};int offset=0;
        for(int attribute=0;attribute<5;attribute++){glEnableVertexAttribArray(attribute);glVertexAttribPointer(attribute,sizes[attribute],GL_FLOAT,GL_FALSE,18*sizeof(float),reinterpret_cast<void*>(offset*sizeof(float)));offset+=sizes[attribute];}
    }
    void ridges(int visible,int ringHead,float newest,float seconds,double top,double bottom,double axis,int mode){
        ridgeVertices.clear();
        if(mode!=1||!std::isfinite(axis)||axis<=0)return;const float logicalWidth=targetRight-targetLeft,logicalHeight=targetBottom-targetTop;
        for(int age=visible-2;age>=0;age--){
            int newer=(ringHead-1-age+capacity)%capacity,older=(newer-1+capacity)%capacity;const auto& pair=pairs[newer];
            if(!pair.valid||!std::isfinite(pair.oldBottom)||!std::isfinite(pair.newBottom))continue;
            int detail=((capacity+255)/256)*256*4;
            if(metaValues[detail+newer*4+1]<.5f||metaValues[detail+older*4+1]<.5f||metaValues[older*4+2]!=metaValues[newer*4+2]||metaValues[older*4+3]!=metaValues[newer*4+3])continue;
            double gap=metaValues[newer*4]-metaValues[older*4];if(gap<=0||gap>500)continue;
            float oldX=targetRight-2.5f-static_cast<float>((static_cast<double>(newest)-metaValues[older*4])/(static_cast<double>(seconds)*1000.)*logicalWidth);
            float newX=targetRight-2.5f-static_cast<float>((static_cast<double>(newest)-metaValues[newer*4])/(static_cast<double>(seconds)*1000.)*logicalWidth);
            if(newX<=oldX||newX<targetLeft||oldX>targetRight)continue;
            float oldY=static_cast<float>(targetTop+(pair.oldBottom/axis-top)/(bottom-top)*logicalHeight),newY=static_cast<float>(targetTop+(pair.newBottom/axis-top)/(bottom-top)*logicalHeight);
            float clipTop=static_cast<float>(std::max(static_cast<double>(targetTop),targetTop-top/(bottom-top)*logicalHeight));
            float clipBottom=static_cast<float>(std::min(static_cast<double>(targetBottom),targetTop+(1023*pair.spacing/axis-top)/(bottom-top)*logicalHeight));
            if(clipTop>=clipBottom)continue;
            // Coordinates, physical coverage and endpoint colors use the established
            // confirmed-depth path. The stroke is confined to its own measured interval.
            const float padding=.75f*rasterScale+1;
            float left=oldX*rasterScale,right=newX*rasterScale,y0=oldY*rasterScale,y1=newY*rasterScale;
            const float first=std::min(y0,y1)-padding,last=std::max(y0,y1)+padding;
            auto add=[&](float x,float y){
                ridgeVertices.insert(ridgeVertices.end(),{x,y,left,y0,right,y1,std::floor(left+.5f),std::floor(clipTop*rasterScale+.5f),std::floor(right+.5f),std::floor(clipBottom*rasterScale+.5f)});
                for(auto c:{pair.oldColor,pair.newColor})ridgeVertices.insert(ridgeVertices.end(),{((c>>16)&255)/255.f,((c>>8)&255)/255.f,(c&255)/255.f,((c>>24)&255)/255.f});
            };
            const float drawLeft=std::floor(left+.5f),drawRight=std::floor(right+.5f);
            if(rasterCullEnabled&&singleSample&&!hasPixelCenter(drawLeft,drawRight))continue;
            add(drawLeft,first);add(drawLeft,last);add(drawRight,first);add(drawRight,first);add(drawLeft,last);add(drawRight,last);
        }
        if(ridgeVertices.empty())return;
        glUseProgram(ridgeProgram);glUniform2f(glGetUniformLocation(ridgeProgram,"viewport"),width,height);glUniform1f(glGetUniformLocation(ridgeProgram,"viewportHeight"),height);glUniform1f(glGetUniformLocation(ridgeProgram,"halfWidth"),.75f*rasterScale);
        glBindVertexArray(ridgeVao);glBindBuffer(GL_ARRAY_BUFFER,ridgeBuffer);glBufferData(GL_ARRAY_BUFFER,ridgeVertices.size()*sizeof(float),ridgeVertices.data(),GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES,0,static_cast<GLsizei>(ridgeVertices.size()/18));glcheck();
    }
    void spans(int visible,int ringHead,float newTime,float seconds,int mode){
        spanValues.assign(((width+255)/256)*256*4,0.f);
        for(int x=0;x<((width+255)/256)*256;x++)spanValues[x*4]=-1.f;
        auto info=[&](int age,int field){int slot=(ringHead-1-age+capacity)%capacity;return metaValues[slot*4+field];};
        auto compatible=[&](int newer,int older){float gap=info(newer,0)-info(older,0);return info(newer,2)>0&&info(newer,2)==info(older,2)&&info(newer,3)==info(older,3)&&gap>0&&gap<=500;};
        const float logicalWidth=targetRight-targetLeft;
        auto center=[&](int age){return (targetRight-2.5f-static_cast<float>((static_cast<double>(newTime)-info(age,0))/(static_cast<double>(seconds)*1000.)*logicalWidth))*rasterScale;};
        auto write=[&](float left,float right,int older,int newer,bool measured){
            int begin=std::max(0,static_cast<int>(std::ceil(left-.5f)));
            int end=std::min(width,static_cast<int>(std::ceil(right-.5f)));
            for(int x=begin;x<end;x++){int at=x*4;spanValues[at]=older;spanValues[at+1]=newer;
                spanValues[at+2]=measured?0.f:((x+.5f-left)/(right-left));spanValues[at+3]=measured?1.f:0.f;}
        };
        if(mode==1)for(int older=visible-1;older>0;older--){
            int newer=older-1;if(!compatible(newer,older))continue;
            float left=center(older),right=center(newer);
            int oldSlot=(ringHead-1-older+capacity)%capacity,newSlot=(ringHead-1-newer+capacity)%capacity;
            int extra=((capacity+255)/256)*256*4;
            bool anchored=metaValues[extra+oldSlot*4+1]>.5f&&metaValues[extra+newSlot*4+1]>.5f&&metaValues[extra+oldSlot*4]!=metaValues[extra+newSlot*4];
            if(right>left&&(right-left>rasterScale||anchored)){
                if(anchored||info(older,1)!=info(newer,1))check(pairStorage&&pairs[newSlot].valid,"SONAR_TEMPORAL_PAIR_NOT_PREPARED");
                write(left,right,older,newer,false);
            }
        }
        if(mode!=0&&mode!=1&&mode!=2)throw std::runtime_error("SONAR_MODE_INVALID");
        glActiveTexture(GL_TEXTURE6);glBindTexture(GL_TEXTURE_2D,textures[6]);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,page,(width+255)/256,GL_RGBA,GL_FLOAT,spanValues.data());
    }
    int64_t draw(int visible,int ringHead,float newTime,float seconds,float top,float bottom,float meters,int mode,int smoothing,int background){
        check(visible>=0&&visible<=capacity&&ringHead>=0&&ringHead<capacity&&seconds>0&&top>=0&&bottom<=1023&&top<bottom,"SONAR_DRAW_BOUNDS");current();flushPairBatch();
        width=ANativeWindow_getWidth(window);height=ANativeWindow_getHeight(window);
        check(width>0&&height>0&&width<=16384&&height<=16384,"SONAR_VIEWPORT_INVALID");
        if(designWidth>0){rasterScale=width/designWidth;targetLeft=logicalPlot[0];targetTop=logicalPlot[1];targetRight=logicalPlot[2];targetBottom=logicalPlot[3];}
        else{targetLeft=0;targetTop=0;targetRight=width/rasterScale;targetBottom=height/rasterScale;}

        check(eglQuerySurface(display,surface,EGL_WIDTH,&bufferWidth)&&eglQuerySurface(display,surface,EGL_HEIGHT,&bufferHeight),"SONAR_EGL_BUFFER_QUERY_FAILED");
        if(bufferWidth!=width||bufferHeight!=height){
            // API29 can keep the previous EGL backbuffer dimensions after the
            // TextureView consumer resized. Recreate ONLY our window surface,
            // keeping context/textures/data. Never draw into mismatched bounds.
            check(eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT),"SONAR_EGL_RESIZE_UNBIND_FAILED");
            check(eglDestroySurface(display,surface),"SONAR_EGL_RESIZE_DESTROY_FAILED");surface=EGL_NO_SURFACE;surfaces--;
            surface=eglCreateWindowSurface(display,config,window,nullptr);
            check(surface!=EGL_NO_SURFACE,"SONAR_EGL_RESIZE_CREATE_FAILED");surfaces++;current();
            check(eglQuerySurface(display,surface,EGL_WIDTH,&bufferWidth)&&eglQuerySurface(display,surface,EGL_HEIGHT,&bufferHeight),"SONAR_EGL_RESIZE_QUERY_FAILED");
            check(bufferWidth==width&&bufferHeight==height,"SONAR_EGL_BUFFER_VIEWPORT_MISMATCH");
        }
        prepareColors(mode);spans(visible,ringHead,newTime,seconds,mode);
        glActiveTexture(GL_TEXTURE7);glBindTexture(GL_TEXTURE_2D_ARRAY,textures[7]);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D_ARRAY,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glViewport(0,0,width,height);glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDisable(GL_SCISSOR_TEST);glUseProgram(program);glBindVertexArray(vao);
        auto integer=[&](const char* n,int v){glUniform1i(glGetUniformLocation(program,n),v);};
        auto scalar=[&](const char* n,float v){glUniform1f(glGetUniformLocation(program,n),v);};
        integer("capacity",capacity);integer("head",ringHead);integer("count",visible);integer("mode",mode);integer("smoothing",smoothing);integer("transparentStage",designWidth>0?1:0);
        scalar("newest",newTime);scalar("horizon",seconds);scalar("binTop",top);scalar("binBottom",bottom);scalar("axisMeters",meters);scalar("pixelWidth",(targetRight-targetLeft)*rasterScale);scalar("pixelHeight",(targetBottom-targetTop)*rasterScale);scalar("surfaceHeight",height);scalar("plotTop",targetTop*rasterScale);
        const auto c=static_cast<uint32_t>(background);
        glUniform4f(glGetUniformLocation(program,"background"),((c>>16)&255)/255.f,((c>>8)&255)/255.f,(c&255)/255.f,((c>>24)&255)/255.f);
        if(designWidth>0)glClearColor(0,0,0,0);else glClearColor(((c>>16)&255)/255.f,((c>>8)&255)/255.f,(c&255)/255.f,((c>>24)&255)/255.f);
        glClear(GL_COLOR_BUFFER_BIT);
        int l=std::clamp(static_cast<int>(std::floor(targetLeft*rasterScale+.5f)),0,width),r=std::clamp(static_cast<int>(std::floor(targetRight*rasterScale+.5f)),0,width);
        int topClip=std::clamp(static_cast<int>(std::floor(targetTop*rasterScale+.5f)),0,height),bottomClip=std::clamp(static_cast<int>(std::floor(targetBottom*rasterScale+.5f)),0,height);
        glEnable(GL_SCISSOR_TEST);glScissor(l,height-bottomClip,std::max(0,r-l),std::max(0,bottomClip-topClip));
        // A transparent full-stage buffer still owns an opaque plot background,
        // including time/physical coverage for which no measured profile exists.
        if(designWidth<=0){glClearColor(((c>>16)&255)/255.f,((c>>8)&255)/255.f,(c&255)/255.f,((c>>24)&255)/255.f);glClear(GL_COLOR_BUFFER_BIT);}
        glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES,0,3);glcheck();
        temporalQuads(visible,ringHead,newTime,seconds,exactGeometry.empty()?top:exactGeometry[capacity],exactGeometry.empty()?bottom:exactGeometry[capacity+1],exactGeometry.empty()?meters:exactGeometry[capacity+2],mode);
        measuredColumns(visible,ringHead,newTime,seconds,
            exactGeometry.empty()?top:exactGeometry[capacity],exactGeometry.empty()?bottom:exactGeometry[capacity+1],
            exactGeometry.empty()?meters:exactGeometry[capacity+2],mode);
        ridges(visible,ringHead,newTime,seconds,exactGeometry.empty()?top:exactGeometry[capacity],exactGeometry.empty()?bottom:exactGeometry[capacity+1],exactGeometry.empty()?meters:exactGeometry[capacity+2],mode);
        auto timestamp=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        auto present=reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(eglGetProcAddress("eglPresentationTimeANDROID"));
        check(present&&present(display,surface,timestamp),"SONAR_FRAME_TIMESTAMP_FAILED");
        check(eglSwapBuffers(display,surface),"SONAR_EGL_SWAP_FAILED");frameId=timestamp;drawnMode=mode;return frameId;
    }
};
std::mutex registryMutex;
std::unordered_map<int64_t,std::unique_ptr<Renderer>> registry;
Renderer& owned(int64_t id){auto item=registry.find(id);check(item!=registry.end(),"SONAR_GPU_OWNER_CLOSED");return *item->second;}
void fail(JNIEnv* env,const std::exception& error){jclass type=env->FindClass("java/lang/IllegalStateException");if(type)env->ThrowNew(type,error.what());}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_no5_helm_gpu_SonarGpuNative_create(JNIEnv* e,jobject,jobject surface,jint capacity){
    std::lock_guard lock(registryMutex);try{check(registry.size()<2,"SONAR_TOO_MANY_GPU_OWNERS");auto renderer=std::make_unique<Renderer>(e,surface,capacity);auto id=nextId++;registry.emplace(id,std::move(renderer));ownerCount++;allocatedPayloadBytes+=payloadBytes(capacity);return id;}catch(const std::exception& x){fail(e,x);return 0;}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_destroy(JNIEnv*,jobject,jlong id){std::lock_guard lock(registryMutex);auto item=registry.find(id);if(item!=registry.end()){allocatedPayloadBytes-=payloadBytes(item->second->capacity);ownerCount--;registry.erase(item);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_upload(JNIEnv* e,jobject,jlong id,jint first,jint count,jshortArray values){std::lock_guard lock(registryMutex);try{owned(id).upload(e,first,count,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_intensity(JNIEnv* e,jobject,jlong id,jint first,jint count,jshortArray values){std::lock_guard lock(registryMutex);try{owned(id).upload(e,first,count,values,5);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_temporalPair(JNIEnv* e,jobject,jlong id,jint slot,jintArray colors,jdouble spacing,jdouble oldBottom,jdouble newBottom,jint oldColor,jint newColor){std::lock_guard lock(registryMutex);try{owned(id).temporalPair(e,slot,colors,spacing,oldBottom,newBottom,static_cast<uint32_t>(oldColor),static_cast<uint32_t>(newColor));}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_plot(JNIEnv* e,jobject,jlong id,jfloat left,jfloat top,jfloat right,jfloat bottom,jfloat designWidth){std::lock_guard lock(registryMutex);try{auto& r=owned(id);check(std::isfinite(designWidth)&&designWidth>=0,"SONAR_DESIGN_VIEWPORT_INVALID");
    if(designWidth>0)check(std::isfinite(left)&&std::isfinite(top)&&std::isfinite(right)&&std::isfinite(bottom)&&left>=0&&top>=0&&right>left&&bottom>top&&right<=designWidth,"SONAR_LOGICAL_PLOT_INVALID");
    r.logicalPlot={left,top,right,bottom};r.designWidth=designWidth;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_viewportInfo(JNIEnv* e,jobject,jlong id,jfloatArray output){std::lock_guard lock(registryMutex);try{auto& r=owned(id);int length=e->GetArrayLength(output);check(length==5||length==7,"SONAR_VIEWPORT_INFO_LENGTH");jfloat values[]={r.targetLeft*r.rasterScale,r.targetTop*r.rasterScale,(r.targetRight-r.targetLeft)*r.rasterScale,(r.targetBottom-r.targetTop)*r.rasterScale,r.rasterScale,r.drawnMode==1?1.f:0.f,static_cast<float>(r.ridgeVertices.size()/108)};e->SetFloatArrayRegion(output,0,length,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_rasterScale(JNIEnv* e,jobject,jlong id,jfloat value){std::lock_guard lock(registryMutex);try{check(value>0&&value<=16,"SONAR_RASTER_SCALE_INVALID");owned(id).rasterScale=value;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_geometry(JNIEnv* e,jobject,jlong id,jdoubleArray values){
    std::lock_guard lock(registryMutex);try{auto& r=owned(id);check(e->GetArrayLength(values)==r.capacity+3,"SONAR_EXACT_GEOMETRY_LENGTH");
    r.exactGeometry.resize(r.capacity+3);e->GetDoubleArrayRegion(values,0,r.capacity+3,r.exactGeometry.data());
    check(!e->ExceptionCheck(),"SONAR_EXACT_GEOMETRY_COPY_FAILED");
    for(int i=0;i<r.capacity;i++)check(std::isnan(r.exactGeometry[i])||(std::isfinite(r.exactGeometry[i])&&r.exactGeometry[i]>0),"SONAR_EXACT_GEOMETRY_INVALID");
    check(std::isfinite(r.exactGeometry[r.capacity])&&std::isfinite(r.exactGeometry[r.capacity+1])&&r.exactGeometry[r.capacity]>=0&&r.exactGeometry[r.capacity]<r.exactGeometry[r.capacity+1]&&r.exactGeometry[r.capacity+1]<=1023,"SONAR_EXACT_GEOMETRY_BOUNDS");
    }catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_metadata(JNIEnv* e,jobject,jlong id,jfloatArray values){std::lock_guard lock(registryMutex);try{owned(id).metadata(e,values);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_lut(JNIEnv* e,jobject,jlong id,jintArray raw,jintArray colors,jfloatArray transfer){std::lock_guard lock(registryMutex);try{owned(id).lut(e,raw,colors,transfer);}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jlong JNICALL Java_com_no5_helm_gpu_SonarGpuNative_draw(JNIEnv* e,jobject,jlong id,jint count,jint head,jfloat newest,jfloat seconds,jfloat top,jfloat bottom,jfloat meters,jint mode,jint smooth,jint background){std::lock_guard lock(registryMutex);try{return owned(id).draw(count,head,newest,seconds,top,bottom,meters,mode,smooth,background);}catch(const std::exception& x){fail(e,x);return -1;}}
// Observer remains nonblocking even if a different GL thread is stuck inside a driver call.
// Exact Float->Double seven-term accumulation, same order as the established
// Kotlin display mean. No EGL, locks, retained input, JNI calls or allocation
// inside the critical arithmetic region; nested critical-array acquisition is
// the JNI-specified mechanism for multiple primitive arrays.
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_mean7Exact(JNIEnv* e,jobject,jshortArray input,jfloatArray table,jshortArray output,jint offset){
    if(!input||!table||!output||e->GetArrayLength(input)!=7*bins||e->GetArrayLength(table)!=65536||offset<0||offset>e->GetArrayLength(output)-bins){
        std::runtime_error error("SONAR_MEAN_ARRAY_BOUNDS");fail(e,error);return;
    }
    jboolean inputCopy=JNI_FALSE,tableCopy=JNI_FALSE,outputCopy=JNI_FALSE;
    auto* profiles=static_cast<jshort*>(e->GetPrimitiveArrayCritical(input,&inputCopy));
    if(!profiles)return;
    auto* lut=static_cast<jfloat*>(e->GetPrimitiveArrayCritical(table,&tableCopy));
    if(!lut){e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);return;}
    auto* result=static_cast<jshort*>(e->GetPrimitiveArrayCritical(output,&outputCopy));
    if(!result){e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);return;}
    const bool copied=inputCopy||tableCopy||outputCopy;
    bool valid=true;
    if(!copied)for(int row=0;row<bins;row++){
        double sum=static_cast<double>(lut[static_cast<uint16_t>(profiles[row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[2*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[3*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[4*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[5*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[6*bins+row])]);
        if(!std::isfinite(sum)||sum<0.0||sum>7.0){valid=false;break;}
        result[offset+row]=static_cast<jshort>(static_cast<uint16_t>(std::clamp(static_cast<int>(std::floor(sum/7.0*65535.0+0.5)),0,65535)));
    }
    e->ReleasePrimitiveArrayCritical(output,result,copied?JNI_ABORT:0);
    e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);
    e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);
    if(copied){std::runtime_error error("SONAR_MEAN_CRITICAL_ARRAY_COPY_NOT_SUPPORTED");fail(e,error);}
    else if(!valid){std::runtime_error error("SONAR_MEAN_INVALID_INTENSITY");fail(e,error);}
}
// Exact registered-pair display interpolation. Inputs remain caller-owned;
// no GL, allocation, locks or retained arrays in the bounded critical section.
extern "C" JNIEXPORT jlong JNICALL Java_com_no5_helm_gpu_SonarGpuNative_pairColorsExact(JNIEnv* e,jobject,jshortArray input,jintArray table,jintArray output,jdouble oldSpacing,jdouble newSpacing,jdouble spacing,jdouble oldBottom,jdouble newBottom){
    const bool anchored=std::isfinite(oldBottom)&&std::isfinite(newBottom);
    if(!input||!table||!output||e->GetArrayLength(input)!=2048||e->GetArrayLength(table)!=65536||e->GetArrayLength(output)!=2048||
       (!std::isnan(spacing)&&(!std::isfinite(spacing)||spacing<=0||!std::isfinite(oldSpacing)||oldSpacing<=0||!std::isfinite(newSpacing)||newSpacing<=0))){
        std::runtime_error error("SONAR_PAIR_ARRAY_OR_GEOMETRY_BOUNDS");fail(e,error);return 0;
    }
    jboolean a=JNI_FALSE,b=JNI_FALSE,c=JNI_FALSE;
    auto* codes=static_cast<jshort*>(e->GetPrimitiveArrayCritical(input,&a));if(!codes)return 0;
    auto* lut=static_cast<jint*>(e->GetPrimitiveArrayCritical(table,&b));if(!lut){e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);return 0;}
    auto* result=static_cast<jint*>(e->GetPrimitiveArrayCritical(output,&c));if(!result){e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);return 0;}
    const bool copied=a||b||c;uint32_t oldColor=0,newColor=0;
    if(!copied){
        const double reference=anchored?(oldBottom+newBottom)/2.0:0.0;
        auto color=[&](int offset,double bin)->jint{
            int low=std::clamp(static_cast<int>(std::floor(bin)),0,1023),high=std::min(low+1,1023);
            double weight=std::clamp(bin-low,0.0,1.0);
            int first=static_cast<uint16_t>(codes[offset+low]),second=static_cast<uint16_t>(codes[offset+high]);
            int index=std::clamp(static_cast<int>(std::floor(first*(1.0-weight)+second*weight+0.5)),0,65535);
            return lut[index];
        };
        for(int bin=0;bin<1024;bin++){
            double x=std::isnan(spacing)?static_cast<double>(bin):anchored?(bin*spacing+oldBottom-reference)/oldSpacing:bin*(spacing/oldSpacing);
            double y=std::isnan(spacing)?static_cast<double>(bin):anchored?(bin*spacing+newBottom-reference)/newSpacing:bin*(spacing/newSpacing);
            if(!(x>=0.0&&x<=1023.0&&y>=0.0&&y<=1023.0)){result[bin*2]=0;result[bin*2+1]=0;}
            else{result[bin*2]=color(0,x);result[bin*2+1]=color(1024,y);}
        }
        if(anchored){oldColor=static_cast<uint32_t>(color(0,oldBottom/oldSpacing));newColor=static_cast<uint32_t>(color(1024,newBottom/newSpacing));}
    }
    e->ReleasePrimitiveArrayCritical(output,result,copied?JNI_ABORT:0);
    e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);
    if(copied){std::runtime_error error("SONAR_PAIR_CRITICAL_ARRAY_COPY_NOT_SUPPORTED");fail(e,error);return 0;}
    return static_cast<jlong>((static_cast<uint64_t>(oldColor)<<32)|newColor);
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_no5_helm_gpu_SonarGpuNative_resources(JNIEnv* e,jobject){
    jlong counts[]={ownerCount.load(),contexts.load(),surfaces.load(),textureCount.load(),allocatedPayloadBytes.load()};
    jlongArray result=e->NewLongArray(5);if(result)e->SetLongArrayRegion(result,0,5,counts);return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_dimensions(JNIEnv* e,jobject,jlong id,jintArray out){std::lock_guard lock(registryMutex);try{check(e->GetArrayLength(out)==2||e->GetArrayLength(out)==4,"SONAR_DIMENSIONS_LENGTH");auto& r=owned(id);jint values[]={r.width,r.height,r.bufferWidth,r.bufferHeight};e->SetIntArrayRegion(out,0,e->GetArrayLength(out),values);}catch(const std::exception& x){fail(e,x);}}
#ifdef NO5_SONAR_FAULT_TEST
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuTestHooks_colorColumn(JNIEnv* e,jobject,jlong id,jint slot,jintArray output){
    std::lock_guard lock(registryMutex);try{auto& r=owned(id);check(slot>=0&&slot<r.capacity&&e->GetArrayLength(output)==1024,"SONAR_FIXTURE_COLUMN_BOUNDS");r.current();
    glBindFramebuffer(GL_FRAMEBUFFER,r.colorFramebuffer);glFramebufferTextureLayer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,r.textures[7],0,slot/2048);
    check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"SONAR_FIXTURE_READBACK_FRAMEBUFFER");
    std::array<uint8_t,4096> rgba{};glReadPixels(slot%2048,0,1,1024,GL_RGBA,GL_UNSIGNED_BYTE,rgba.data());glBindFramebuffer(GL_FRAMEBUFFER,0);glcheck();
    std::array<jint,1024> colors{};for(int row=0;row<1024;row++)colors[row]=static_cast<jint>((uint32_t(rgba[row*4+3])<<24)|(uint32_t(rgba[row*4])<<16)|(uint32_t(rgba[row*4+1])<<8)|rgba[row*4+2]);
    e->SetIntArrayRegion(output,0,1024,colors.data());
    }catch(const std::exception& x){fail(e,x);}}
// Compiled ONLY into the separate fixture .so, absent from the release library.
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_injectOwnContextLoss(JNIEnv* e,jobject,jlong id){std::lock_guard lock(registryMutex);try{
    auto& r=owned(id);r.current();eglMakeCurrent(r.display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    check(eglDestroyContext(r.display,r.context),"SONAR_FIXTURE_DESTROY_CONTEXT_FAILED");r.context=EGL_NO_CONTEXT;contexts--;
    if(r.textures[0]){textureCount-=static_cast<int>(r.textures.size());r.textures.fill(0);}r.program=0;r.vao=0;
}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNativeFaultProof_fullColorRepaint(JNIEnv* e,jobject,jlong id,jboolean enabled){std::lock_guard lock(registryMutex);try{owned(id).fixtureFullColorRepaint=enabled;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNativeFaultProof_rasterCull(JNIEnv* e,jobject,jlong id,jboolean enabled){std::lock_guard lock(registryMutex);try{owned(id).rasterCullEnabled=enabled;}catch(const std::exception& x){fail(e,x);}}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_no5_helm_gpu_SonarGpuNativeFaultProof_rasterPrimitiveCounts(JNIEnv* e,jobject,jlong id){std::lock_guard lock(registryMutex);try{auto&r=owned(id);jlong values[]={static_cast<jlong>(r.columnVertices.size()/30),static_cast<jlong>(r.pairVertices.size()/54),static_cast<jlong>(r.ridgeVertices.size()/108),r.singleSample?1L:0L,r.lastColorColumns};auto out=e->NewLongArray(5);if(out)e->SetLongArrayRegion(out,0,5,values);return out;}catch(const std::exception& x){fail(e,x);return nullptr;}}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNativeFaultProof_loseOwnContext(JNIEnv* e,jobject object,jlong id){
    Java_com_no5_helm_gpu_SonarGpuNative_injectOwnContextLoss(e,object,id);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_no5_helm_gpu_SonarGpuNative_hardwareDescription(JNIEnv* e,jobject,jlong id){std::lock_guard lock(registryMutex);try{owned(id).current();std::string text=reinterpret_cast<const char*>(glGetString(GL_VENDOR));text+=" / ";text+=reinterpret_cast<const char*>(glGetString(GL_RENDERER));text+=" / ";text+=reinterpret_cast<const char*>(glGetString(GL_VERSION));return e->NewStringUTF(text.c_str());}catch(const std::exception& x){fail(e,x);return nullptr;}}
#endif
