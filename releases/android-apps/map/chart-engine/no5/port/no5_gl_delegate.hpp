#pragma once
#include "no5_profile.hpp"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <stdexcept>
#include <sstream>
#include <type_traits>

namespace no5 {
// One delegate for the retained GLES2 paths. No5 owns fresh, unshared contexts;
// reads of managed state never synchronize with the driver during rendering.
struct GlUniformValue {
  GLenum type=0;
  bool known=false;
  std::array<unsigned char,16*sizeof(GLfloat)> bytes{};
};
struct GlProgramLocations {
  bool linked=false;
  bool uniformTypesLoaded=false;
  std::unordered_map<std::string,GLint> uniforms,attributes;
  std::unordered_map<GLint,GlUniformValue> values;
  bool activeAttributesLoaded=false;
  std::vector<bool> activeAttributes;
  uint64_t activeAttributeMask=0;
};
enum class GlSetter { Capability,Scissor,Viewport,LineWidth,Program,ActiveTexture,Texture,Buffer,Framebuffer,Blend,ClearColor,ColorMask,AttributeEnable,Uniform1i,Uniform1f,Uniform2f,Uniform2fv,Uniform4f,Uniform4fv,UniformMatrix4fv,Count };
inline constexpr const char* glSetterNames[]={"enable/disable","scissor","viewport","line width","program","active texture","texture bind","buffer bind","framebuffer bind","blend function","clear color","color mask","attribute enable/disable","uniform1i","uniform1f","uniform2f","uniform2fv","uniform4f","uniform4fv","uniform matrix4fv"};
struct GlSetterCounts {unsigned long long requested=0,forwarded=0,skipped=0;};
using GlSetterProfile=std::array<GlSetterCounts,static_cast<std::size_t>(GlSetter::Count)>;
struct GlQueryCounts {
  unsigned long long locations=0,state=0,capabilityInit=0,validation=0,errors=0,debug=0;
};
struct GlLocationCache {
  bool enabled=true,stateEnabled=true,setterDedup=true,verifyAfterFrame=false,unclampedClearColor=false;
  bool startupTrace=false;
  unsigned long long startupErrorChecks=0;
  std::string startupFailure;
  unsigned long long hits=0,driverQueries=0,stateHits=0,commands=0;
  GlQueryCounts queries;
  GlSetterProfile setters{};
  std::unordered_map<GLenum,unsigned long long> specialStateQueries;
  std::unordered_map<GLuint,GlProgramLocations> programs;
  std::unordered_map<GLenum,bool> enabledCaps;
  std::array<GLint,4> viewport{},scissor{};
  std::array<GLfloat,2> lineRange{};
  std::array<GLint,2> lineRangeInt{},maxViewport{};
  GLint maxTexture=0,maxTextureUnits=0,maxAttributes=0;
  GLuint currentProgram=0,arrayBuffer=0,elementBuffer=0,framebuffer=0;
  bool programKnown=true,arrayBufferKnown=true,elementBufferKnown=true,framebufferKnown=true;
  GLenum activeTexture=GL_TEXTURE0,blendSource=GL_ONE,blendDestination=GL_ZERO,blendAlphaSource=GL_ONE,blendAlphaDestination=GL_ZERO;
  GLfloat lineWidth=1;
  bool lineWidthKnown=true;
  std::array<GLfloat,4> clearColor{};
  std::array<GLboolean,4> colorMask{GL_TRUE,GL_TRUE,GL_TRUE,GL_TRUE};
  std::vector<GLuint> textures2D;
  std::vector<bool> textureBindingKnown,attributeEnabled;
  uint64_t enabledAttributeMask=0;
  std::unordered_set<GLuint> textureNames,bufferNames,framebufferNames;
  std::unordered_map<GLuint,std::pair<int,int>> textureSizes;
  std::unordered_map<GLuint,GLenum> textureTargets;
};
inline thread_local std::unordered_map<EGLContext,GlLocationCache> glLocationCaches;
inline thread_local EGLContext delegateCurrentContext=EGL_NO_CONTEXT;
inline thread_local GlLocationCache* delegateCurrentCache=nullptr;
inline GlLocationCache* currentGlLocationCache() {
  EGLContext context=eglGetCurrentContext();
  if(context!=delegateCurrentContext){delegateCurrentContext=context;auto found=glLocationCaches.find(context);delegateCurrentCache=found==glLocationCaches.end()?nullptr:&found->second;}
  return delegateCurrentCache;
}
// Probe only initialization/first presentation of an owned Android context.
// Check the incoming error separately: a pre-existing flag is not evidence
// against the named call. Abort at the first failing forwarded API, before a
// delegate can cache a write that the driver rejected. No probes on warm frames.
inline void startupGlError(GlLocationCache& c,const char* api,GLenum error,bool before) {
  std::ostringstream out;
  out<<"GLES startup failed: "<<error<<" (0x"<<std::hex<<error<<std::dec<<") "
     <<(before?"pre-existing before ":"at ")<<api<<"; stage="<<activeRenderStage;
  if(before)out<<"; originating call unknown";
  // Failure-only raw snapshots. These never modify or repair managed state.
  for(auto entry:{std::pair<GLenum,const char*>{GL_CURRENT_PROGRAM,"program"},
      {GL_ARRAY_BUFFER_BINDING,"arrayBuffer"},{GL_ELEMENT_ARRAY_BUFFER_BINDING,"elementBuffer"},
      {GL_FRAMEBUFFER_BINDING,"FBO"},{GL_ACTIVE_TEXTURE,"activeTexture"},
      {GL_MAX_VERTEX_ATTRIBS,"maxAttributes"}}){GLint value=0;::glGetIntegerv(entry.first,&value);out<<"; "<<entry.second<<"="<<value;}
  for(GLuint i=0;i<GLuint(std::min(c.maxAttributes,16));++i){
    GLint enabled=0;::glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&enabled);
    if(enabled){GLint buffer=0,size=0,type=0;::glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,&buffer);
      ::glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_SIZE,&size);::glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_TYPE,&type);
      out<<"; attribute["<<i<<"] buffer="<<buffer<<" size="<<size<<" type="<<type;}
  }
  c.startupFailure=out.str();throw std::runtime_error(c.startupFailure);
}
template<class Function> inline auto startupGlCall(GlLocationCache* c,const char* api,Function&& call)->decltype(call()) {
  if(!c||(!c->startupTrace&&c->startupFailure.empty()))return call();
  if(!c->startupFailure.empty())throw std::runtime_error(c->startupFailure);
  ++c->startupErrorChecks;auto error=::glGetError();if(error!=GL_NO_ERROR)startupGlError(*c,api,error,true);
  if constexpr(std::is_void_v<decltype(call())>){
    call();++c->startupErrorChecks;error=::glGetError();if(error!=GL_NO_ERROR)startupGlError(*c,api,error,false);
  }else{
    auto value=call();++c->startupErrorChecks;error=::glGetError();if(error!=GL_NO_ERROR)startupGlError(*c,api,error,false);return value;
  }
}
inline void finishStartupGlTrace(){if(auto* c=currentGlLocationCache())c->startupTrace=false;}
inline void stopStartupGlTrace(){if(auto* c=currentGlLocationCache()){c->startupTrace=false;c->startupFailure.clear();}}
inline void unregisterGlLocationContext(EGLContext context) {
  if(delegateCurrentContext==context){delegateCurrentContext=EGL_NO_CONTEXT;delegateCurrentCache=nullptr;}
  glLocationCaches.erase(context);
}
inline void registerGlLocationContext(EGLContext context,int width=0,int height=0) {
  if(context==EGL_NO_CONTEXT||context!=eglGetCurrentContext())return;
  auto& c=glLocationCaches[context];c=GlLocationCache{};delegateCurrentContext=context;delegateCurrentCache=&c;
#ifdef __ANDROID__
  c.startupTrace=true;
#endif
#ifndef __ANDROID__
  const char* locations=std::getenv("NO5_GL_LOCATION_CACHE");c.enabled=!locations||std::string(locations)!="0";
  const char* state=std::getenv("NO5_GL_STATE_CACHE");c.stateEnabled=!state||std::string(state)!="0";
  const char* setters=std::getenv("NO5_GL_SETTER_DEDUP");c.setterDedup=!setters||std::string(setters)!="0";
  const char* verify=std::getenv("NO5_GL_VERIFY");c.verifyAfterFrame=verify&&std::string(verify)=="1";
  const char* startup=std::getenv("NO5_GL_STARTUP_TRACE");c.startupTrace=startup&&std::string(startup)=="1";
#endif
  // Hardware limits are immutable for this context. Preserve the driver's own
  // integer conversion rather than guessing it from a float value.
  startupGlCall(&c,"glGetFloatv",[&]{::glGetFloatv(GL_ALIASED_LINE_WIDTH_RANGE,c.lineRange.data());});
  startupGlCall(&c,"glGetIntegerv",[&]{::glGetIntegerv(GL_ALIASED_LINE_WIDTH_RANGE,c.lineRangeInt.data());});
  startupGlCall(&c,"glGetIntegerv",[&]{::glGetIntegerv(GL_MAX_TEXTURE_SIZE,&c.maxTexture);});
  startupGlCall(&c,"glGetIntegerv",[&]{::glGetIntegerv(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS,&c.maxTextureUnits);});
  startupGlCall(&c,"glGetIntegerv",[&]{::glGetIntegerv(GL_MAX_VERTEX_ATTRIBS,&c.maxAttributes);});
  startupGlCall(&c,"glGetIntegerv",[&]{::glGetIntegerv(GL_MAX_VIEWPORT_DIMS,c.maxViewport.data());});
  const auto* version=startupGlCall(&c,"glGetString",[]{return ::glGetString(GL_VERSION);});
  c.unclampedClearColor=version&&std::string(reinterpret_cast<const char*>(version)).find("OpenGL ES 3")!=std::string::npos;
  c.queries.capabilityInit=7;
  c.textures2D.assign(std::max(1,c.maxTextureUnits),0);
  c.textureBindingKnown.assign(c.textures2D.size(),true);
  c.attributeEnabled.assign(c.maxAttributes,false);
  for(GLenum cap:{GL_BLEND,GL_CULL_FACE,GL_DEPTH_TEST,GL_DITHER,GL_POLYGON_OFFSET_FILL,GL_SAMPLE_ALPHA_TO_COVERAGE,GL_SAMPLE_COVERAGE,GL_SCISSOR_TEST,GL_STENCIL_TEST})c.enabledCaps[cap]=cap==GL_DITHER;
  // Fresh-context defaults are fixed by GLES2. Set the surface-dependent values
  // explicitly, so no startup state query or inference from a prior context is needed.
  startupGlCall(&c,"glViewport",[&]{::glViewport(0,0,width,height);});
  startupGlCall(&c,"glScissor",[&]{::glScissor(0,0,width,height);});
  c.viewport={0,0,width,height};c.scissor=c.viewport;
}
template<class Function> inline auto glCommand(RenderDetail bucket,Function&& call,const char* api)->decltype(call()) {
  auto* c=currentGlLocationCache();if(c)++c->commands;
  DetailScope profile(bucket);return startupGlCall(c,api,std::forward<Function>(call));
}
// Only suppress a command whose validity and current state are both known.
// Unknown names, invalid parameters and foreign contexts still reach GLES.
inline bool skipGlSetter(GlLocationCache* c,GlSetter setter,bool same) {
  if(!c)return false;
  auto& counts=c->setters[static_cast<std::size_t>(setter)];++counts.requested;
  if(c->setterDedup&&same){++counts.skipped;return true;}
  ++counts.forwarded;return false;
}
inline void loadGlUniformTypes(GlLocationCache& c,GLuint program,GlProgramLocations& p) {
  if(p.uniformTypesLoaded)return;
  GLint count=0,length=0;c.queries.validation+=2;
  startupGlCall(&c,"glGetProgramiv",[&]{::glGetProgramiv(program,GL_ACTIVE_UNIFORMS,&count);});
  startupGlCall(&c,"glGetProgramiv",[&]{::glGetProgramiv(program,GL_ACTIVE_UNIFORM_MAX_LENGTH,&length);});
  std::vector<GLchar> name(std::max(1,length));
  for(GLint i=0;i<count;++i){
    GLsizei used=0;GLint size=0;GLenum type=0;++c.queries.validation;
    startupGlCall(&c,"glGetActiveUniform",[&]{::glGetActiveUniform(program,i,name.size(),&used,&size,&type,name.data());});
    std::string base(name.data(),used);auto array=base.find("[0]");
    for(GLint n=0;n<size;++n){
      std::string element=base;
      if(n){if(array==std::string::npos)break;element.replace(array,3,"["+std::to_string(n)+"]");}
      ++c.queries.validation;GLint location=startupGlCall(&c,"glGetUniformLocation",[&]{return ::glGetUniformLocation(program,element.c_str());});
      if(location>=0)p.values.emplace(location,GlUniformValue{type});
    }
  }
  p.uniformTypesLoaded=true;
}
// Linker-assigned locations are context/program data, cached once per link.
inline void loadGlActiveAttributes(GlLocationCache& c,GLuint program,GlProgramLocations& p) {
  if(p.activeAttributesLoaded)return;
  p.activeAttributes.assign(c.maxAttributes,false);p.activeAttributeMask=0;
  GLint count=0,length=0;c.queries.validation+=2;
  startupGlCall(&c,"glGetProgramiv",[&]{::glGetProgramiv(program,GL_ACTIVE_ATTRIBUTES,&count);});
  startupGlCall(&c,"glGetProgramiv",[&]{::glGetProgramiv(program,GL_ACTIVE_ATTRIBUTE_MAX_LENGTH,&length);});
  std::vector<GLchar> name(std::max(1,length));
  for(GLint i=0;i<count;++i){
    GLsizei used=0;GLint size=0;GLenum type=0;c.queries.validation+=2;
    startupGlCall(&c,"glGetActiveAttrib",[&]{::glGetActiveAttrib(program,i,name.size(),&used,&size,&type,name.data());});
    GLint location=startupGlCall(&c,"glGetAttribLocation",[&]{return ::glGetAttribLocation(program,name.data());});
    const int columns=type==GL_FLOAT_MAT2?2:type==GL_FLOAT_MAT3?3:type==GL_FLOAT_MAT4?4:1;
    for(GLint n=0;n<size*columns;++n)if(location+n>=0&&location+n<c.maxAttributes){
      p.activeAttributes[location+n]=true;
      if(location+n<64)p.activeAttributeMask|=uint64_t(1)<<(location+n);
    }
  }
  p.activeAttributesLoaded=true;
}
inline GLuint createGlProgram() {
  GLuint id=glCommand(RenderDetail::GlState,[]{return ::glCreateProgram();},"glCreateProgram");
  if(id)if(auto* c=currentGlLocationCache())c->programs.emplace(id,GlProgramLocations{});
  return id;
}
inline void invalidateGlProgramLocations(GLuint program) {
  if(auto* c=currentGlLocationCache()) {
    auto p=c->programs.find(program);if(p!=c->programs.end())p->second=GlProgramLocations{};
  }
}
inline void linkGlProgram(GLuint program) {
  invalidateGlProgramLocations(program);glCommand(RenderDetail::GlState,[&]{::glLinkProgram(program);},"glLinkProgram");
}
inline void getGlProgramiv(GLuint program,GLenum name,GLint* value) {
  if(auto* c=currentGlLocationCache())++c->queries.validation;
  glCommand(RenderDetail::GlState,[&]{::glGetProgramiv(program,name,value);},"glGetProgramiv");
  if(name==GL_LINK_STATUS&&value)if(auto* c=currentGlLocationCache()) {
    auto p=c->programs.find(program);if(p!=c->programs.end()){
      p->second.linked=*value==GL_TRUE;
      if(p->second.linked){loadGlUniformTypes(*c,program,p->second);loadGlActiveAttributes(*c,program,p->second);}
    }
  }
}
inline void deleteGlProgram(GLuint program) {
  glCommand(RenderDetail::GlState,[&]{::glDeleteProgram(program);},"glDeleteProgram");
  if(auto* c=currentGlLocationCache())c->programs.erase(program);
}
inline GLint glLocation(GLuint program,const GLchar* name,bool uniform) {
  auto* c=currentGlLocationCache();
  GlProgramLocations* p=nullptr;
  if(c) {auto found=c->programs.find(program);if(found!=c->programs.end())p=&found->second;}
  if(c&&c->enabled&&p&&p->linked&&name) {
    auto& locations=uniform?p->uniforms:p->attributes;
    auto found=locations.find(name);if(found!=locations.end()){++c->hits;return found->second;}
  }
  if(c){++c->driverQueries;++c->queries.locations;}
  DetailScope profile(RenderDetail::GlQueries);
  GLint result=startupGlCall(c,uniform?"glGetUniformLocation":"glGetAttribLocation",[&]{return uniform?::glGetUniformLocation(program,name) : ::glGetAttribLocation(program,name);});
  // A -1 on a driver-confirmed linked program means an absent/inactive name,
  // not an invalid-program error. Invalid/deleted/unlinked programs never cache it.
  if(c&&c->enabled&&p&&p->linked&&name)(uniform?p->uniforms:p->attributes).emplace(name,result);
  return result;
}
template<class Function> inline void uniformGl(GlSetter setter,GLint location,GLsizei count,GLboolean transpose,GLenum type,const void* bytes,std::size_t size,Function&& call,const char* api) {
  auto* c=currentGlLocationCache();GlUniformValue* value=nullptr;GlProgramLocations* program=nullptr;
  if(c&&c->programKnown){auto p=c->programs.find(c->currentProgram);if(p!=c->programs.end()&&p->second.linked)program=&p->second;}
  if(program&&count==1&&transpose==GL_FALSE&&bytes){
    auto found=program->values.find(location);
    if(found!=program->values.end()){
      bool valid=found->second.type==type;
      if(type==GL_INT){
        auto actual=found->second.type;valid=actual==GL_INT||actual==GL_BOOL;
        if(actual==GL_SAMPLER_2D||actual==GL_SAMPLER_CUBE){GLint unit;std::memcpy(&unit,bytes,sizeof unit);valid=unit>=0&&unit<c->maxTextureUnits;}
      }
      if(valid)value=&found->second;
    }
  }
  if(skipGlSetter(c,setter,value&&value->known&&std::memcmp(value->bytes.data(),bytes,size)==0))return;
  bool newStartupCall=c&&c->startupTrace&&c->startupFailure.empty();
  try{glCommand(RenderDetail::GlState,std::forward<Function>(call),api);}
  catch(const std::runtime_error&){
    if(newStartupCall&&!c->startupFailure.empty()){
      std::ostringstream details;details<<"; uniformLocation="<<location<<"; count="<<count<<"; transpose="<<int(transpose);
      if(program){
        auto declared=program->values.find(location);
        if(declared!=program->values.end())details<<"; activeUniformType="<<declared->second.type;
        else details<<"; activeUniformType=absent";
        for(const auto& uniform:program->uniforms)if(uniform.second==location)details<<"; activeUniformName="<<uniform.first;
      }
      c->startupFailure+=details.str();throw std::runtime_error(c->startupFailure);
    }
    throw;
  }
  if(value){std::memcpy(value->bytes.data(),bytes,size);value->known=true;}
  else if(program){
    // Array/count forms and unknown/invalid calls are deliberately forwarded.
    // Forget values so a subsequent scalar call cannot reuse stale array data.
    for(auto& entry:program->values)entry.second.known=false;
  }
}
inline void uniformGl1i(GLint l,GLint v){uniformGl(GlSetter::Uniform1i,l,1,GL_FALSE,GL_INT,&v,sizeof v,[&]{::glUniform1i(l,v);},"glUniform1i");}
inline void uniformGl1f(GLint l,GLfloat v){uniformGl(GlSetter::Uniform1f,l,1,GL_FALSE,GL_FLOAT,&v,sizeof v,[&]{::glUniform1f(l,v);},"glUniform1f");}
inline void uniformGl2f(GLint l,GLfloat a,GLfloat b){GLfloat v[]={a,b};uniformGl(GlSetter::Uniform2f,l,1,GL_FALSE,GL_FLOAT_VEC2,v,sizeof v,[&]{::glUniform2f(l,a,b);},"glUniform2f");}
inline void uniformGl2fv(GLint l,GLsizei n,const GLfloat* v){uniformGl(GlSetter::Uniform2fv,l,n,GL_FALSE,GL_FLOAT_VEC2,v,2*sizeof(GLfloat),[&]{::glUniform2fv(l,n,v);},"glUniform2fv");}
inline void uniformGl4f(GLint l,GLfloat a,GLfloat b,GLfloat d,GLfloat e){GLfloat v[]={a,b,d,e};uniformGl(GlSetter::Uniform4f,l,1,GL_FALSE,GL_FLOAT_VEC4,v,sizeof v,[&]{::glUniform4f(l,a,b,d,e);},"glUniform4f");}
inline void uniformGl4fv(GLint l,GLsizei n,const GLfloat* v){uniformGl(GlSetter::Uniform4fv,l,n,GL_FALSE,GL_FLOAT_VEC4,v,4*sizeof(GLfloat),[&]{::glUniform4fv(l,n,v);},"glUniform4fv");}
inline void uniformGlMatrix4fv(GLint l,GLsizei n,GLboolean t,const GLfloat* v){uniformGl(GlSetter::UniformMatrix4fv,l,n,t,GL_FLOAT_MAT4,v,16*sizeof(GLfloat),[&]{::glUniformMatrix4fv(l,n,t,v);},"glUniformMatrix4fv");}
inline void enableGlCapability(GLenum cap,bool enable) {
  auto* c=currentGlLocationCache();auto known=c?c->enabledCaps.find(cap):std::unordered_map<GLenum,bool>::iterator{};
  if(skipGlSetter(c,GlSetter::Capability,c&&known!=c->enabledCaps.end()&&known->second==enable))return;
  glCommand(RenderDetail::GlState,[&]{if(enable)::glEnable(cap);else ::glDisable(cap);},enable?"glEnable":"glDisable");
  if(c&&known!=c->enabledCaps.end())known->second=enable;
}
inline GLboolean isGlEnabled(GLenum cap) {
  auto* c=currentGlLocationCache();
  if(c&&c->stateEnabled){auto p=c->enabledCaps.find(cap);if(p!=c->enabledCaps.end()){++c->stateHits;return p->second?GL_TRUE:GL_FALSE;}}
  if(c){++c->queries.state;++c->specialStateQueries[cap];}
  DetailScope profile(RenderDetail::GlQueries);return startupGlCall(c,"glIsEnabled",[&]{return ::glIsEnabled(cap);});
}
inline void scissorGl(GLint x,GLint y,GLsizei width,GLsizei height) {
  auto* c=currentGlLocationCache();std::array<GLint,4> v{x,y,width,height};
  if(skipGlSetter(c,GlSetter::Scissor,c&&width>=0&&height>=0&&c->scissor==v))return;
  glCommand(RenderDetail::GlState,[&]{::glScissor(x,y,width,height);},"glScissor");
  if(c&&width>=0&&height>=0)c->scissor=v;
}
inline void viewportGl(GLint x,GLint y,GLsizei width,GLsizei height) {
  auto* c=currentGlLocationCache();std::array<GLint,4> v{x,y,width,height};
  if(c){v[2]=std::min(width,c->maxViewport[0]);v[3]=std::min(height,c->maxViewport[1]);}
  if(skipGlSetter(c,GlSetter::Viewport,c&&width>=0&&height>=0&&c->viewport==v))return;
  glCommand(RenderDetail::GlState,[&]{::glViewport(x,y,width,height);},"glViewport");
  if(c&&width>=0&&height>=0)c->viewport=v;
}
inline void lineWidthGl(GLfloat value) {
  auto* c=currentGlLocationCache();bool valid=value>0&&std::isfinite(value);
  if(skipGlSetter(c,GlSetter::LineWidth,c&&valid&&c->lineWidthKnown&&c->lineWidth==value))return;
  glCommand(RenderDetail::GlState,[&]{::glLineWidth(value);},"glLineWidth");
  if(c){if(valid){c->lineWidth=value;c->lineWidthKnown=true;}else if(!std::isfinite(value))c->lineWidthKnown=false;}
}
inline void useGlProgram(GLuint program) {
  auto* c=currentGlLocationCache();bool valid=program==0;
  if(c){auto p=c->programs.find(program);valid|=p!=c->programs.end()&&p->second.linked;}
  if(skipGlSetter(c,GlSetter::Program,c&&valid&&c->programKnown&&c->currentProgram==program))return;
  glCommand(RenderDetail::GlState,[&]{::glUseProgram(program);},"glUseProgram");
  if(c){c->programKnown=valid;if(valid)c->currentProgram=program;}
}
inline void activeGlTexture(GLenum unit) {
  auto* c=currentGlLocationCache();bool valid=c&&unit>=GL_TEXTURE0&&unit<GL_TEXTURE0+c->textures2D.size();
  if(skipGlSetter(c,GlSetter::ActiveTexture,valid&&c->activeTexture==unit))return;
  glCommand(RenderDetail::GlState,[&]{::glActiveTexture(unit);},"glActiveTexture");
  if(valid)c->activeTexture=unit;
}
inline GLuint boundGlTexture(GlLocationCache& c){return c.textures2D[c.activeTexture-GL_TEXTURE0];}
inline void genGlTextures(GLsizei count,GLuint* ids){glCommand(RenderDetail::GlState,[&]{::glGenTextures(count,ids);},"glGenTextures");if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i])c->textureNames.insert(ids[i]);}
inline void bindGlTexture(GLenum target,GLuint id) {
  auto* c=currentGlLocationCache();bool valid=c&&(id==0||c->textureNames.count(id));
  if(valid){auto old=c->textureTargets.find(id);if(id&&old!=c->textureTargets.end()&&old->second!=target)valid=false;}
  if(skipGlSetter(c,GlSetter::Texture,valid&&target==GL_TEXTURE_2D&&c->textureBindingKnown[c->activeTexture-GL_TEXTURE0]&&boundGlTexture(*c)==id))return;
  glCommand(RenderDetail::GlState,[&]{::glBindTexture(target,id);},"glBindTexture");
  if(c&&(target==GL_TEXTURE_2D||target==GL_TEXTURE_CUBE_MAP)) {
    auto old=c->textureTargets.find(id);
    if(id&&old!=c->textureTargets.end()&&old->second!=target)return; // driver rejects target reassignment
    if(valid&&id)c->textureTargets[id]=target;
    if(target==GL_TEXTURE_2D){c->textureBindingKnown[c->activeTexture-GL_TEXTURE0]=valid;if(valid)c->textures2D[c->activeTexture-GL_TEXTURE0]=id;}
  }
}
inline void deleteGlTextures(GLsizei count,const GLuint* ids) {
  glCommand(RenderDetail::GlState,[&]{::glDeleteTextures(count,ids);},"glDeleteTextures");
  if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i]) {
    c->textureSizes.erase(ids[i]);c->textureTargets.erase(ids[i]);c->textureNames.erase(ids[i]);for(auto& binding:c->textures2D)if(binding==ids[i])binding=0;
  }
}
inline void genGlBuffers(GLsizei count,GLuint* ids){glCommand(RenderDetail::GlState,[&]{::glGenBuffers(count,ids);},"glGenBuffers");if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i])c->bufferNames.insert(ids[i]);}
inline void bindGlBuffer(GLenum target,GLuint id) {
  auto* c=currentGlLocationCache();bool valid=c&&(id==0||c->bufferNames.count(id));
  bool same=valid&&((target==GL_ARRAY_BUFFER&&c->arrayBufferKnown&&c->arrayBuffer==id)||(target==GL_ELEMENT_ARRAY_BUFFER&&c->elementBufferKnown&&c->elementBuffer==id));
  if(skipGlSetter(c,GlSetter::Buffer,same))return;
  glCommand(RenderDetail::GlState,[&]{::glBindBuffer(target,id);},"glBindBuffer");
  if(c){if(target==GL_ARRAY_BUFFER){c->arrayBufferKnown=valid;if(valid)c->arrayBuffer=id;}else if(target==GL_ELEMENT_ARRAY_BUFFER){c->elementBufferKnown=valid;if(valid)c->elementBuffer=id;}}
}
inline void deleteGlBuffers(GLsizei count,const GLuint* ids) {
  glCommand(RenderDetail::GlState,[&]{::glDeleteBuffers(count,ids);},"glDeleteBuffers");
  if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i]) {
    c->bufferNames.erase(ids[i]);
    if(c->arrayBuffer==ids[i])c->arrayBuffer=0;if(c->elementBuffer==ids[i])c->elementBuffer=0;
  }
}
inline void genGlFramebuffers(GLsizei count,GLuint* ids){glCommand(RenderDetail::GlState,[&]{::glGenFramebuffers(count,ids);},"glGenFramebuffers");if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i])c->framebufferNames.insert(ids[i]);}
inline void bindGlFramebuffer(GLenum target,GLuint id) {
  auto* c=currentGlLocationCache();bool valid=c&&(id==0||c->framebufferNames.count(id));
  if(skipGlSetter(c,GlSetter::Framebuffer,valid&&target==GL_FRAMEBUFFER&&c->framebufferKnown&&c->framebuffer==id))return;
  glCommand(RenderDetail::GlState,[&]{::glBindFramebuffer(target,id);},"glBindFramebuffer");
  if(c&&target==GL_FRAMEBUFFER){c->framebufferKnown=valid;if(valid)c->framebuffer=id;}
}
inline void deleteGlFramebuffers(GLsizei count,const GLuint* ids) {
  glCommand(RenderDetail::GlState,[&]{::glDeleteFramebuffers(count,ids);},"glDeleteFramebuffers");
  if(count>=0&&ids)if(auto* c=currentGlLocationCache())for(GLsizei i=0;i<count;++i)if(ids[i]){c->framebufferNames.erase(ids[i]);if(c->framebuffer==ids[i])c->framebuffer=0;}
}
inline bool blendGlFactor(GLenum f){return f==GL_ZERO||f==GL_ONE||f==GL_SRC_COLOR||f==GL_ONE_MINUS_SRC_COLOR||f==GL_DST_COLOR||f==GL_ONE_MINUS_DST_COLOR||f==GL_SRC_ALPHA||f==GL_ONE_MINUS_SRC_ALPHA||f==GL_DST_ALPHA||f==GL_ONE_MINUS_DST_ALPHA||f==GL_CONSTANT_COLOR||f==GL_ONE_MINUS_CONSTANT_COLOR||f==GL_CONSTANT_ALPHA||f==GL_ONE_MINUS_CONSTANT_ALPHA;}
inline bool compatibleGlBlendFactors(GLenum s,GLenum d) {
  auto color=[](GLenum f){return f==GL_CONSTANT_COLOR||f==GL_ONE_MINUS_CONSTANT_COLOR;};
  auto alpha=[](GLenum f){return f==GL_CONSTANT_ALPHA||f==GL_ONE_MINUS_CONSTANT_ALPHA;};
  return !(color(s)&&alpha(d))&&!(alpha(s)&&color(d));
}
inline thread_local bool worldUnderlayAlpha=false;
inline void blendGlFunc(GLenum source,GLenum destination) {
  const GLenum alphaSource=worldUnderlayAlpha&&source==GL_SRC_ALPHA?GL_ONE:source;
  auto* c=currentGlLocationCache();bool valid=(blendGlFactor(source)||source==GL_SRC_ALPHA_SATURATE)&&blendGlFactor(destination)&&compatibleGlBlendFactors(source,destination);
  if(skipGlSetter(c,GlSetter::Blend,c&&valid&&c->blendSource==source&&c->blendDestination==destination&&c->blendAlphaSource==alphaSource&&c->blendAlphaDestination==destination))return;
  glCommand(RenderDetail::GlState,[&]{if(worldUnderlayAlpha)::glBlendFuncSeparate(source,destination,alphaSource,destination);else ::glBlendFunc(source,destination);},"glBlendFunc");
  if(c&&valid){c->blendSource=source;c->blendDestination=destination;c->blendAlphaSource=alphaSource;c->blendAlphaDestination=destination;}
}
inline void clearGlColor(GLfloat r,GLfloat g,GLfloat b,GLfloat a) {
  auto* c=currentGlLocationCache();std::array<GLfloat,4> v{r,g,b,a};
  if(c&&!c->unclampedClearColor)for(auto& f:v)f=std::clamp(f,0.f,1.f);
  if(skipGlSetter(c,GlSetter::ClearColor,c&&c->clearColor==v))return;
  glCommand(RenderDetail::GlState,[&]{::glClearColor(r,g,b,a);},"glClearColor");
  if(c)c->clearColor=v;
}
inline void colorGlMask(GLboolean r,GLboolean g,GLboolean b,GLboolean a) {
  auto* c=currentGlLocationCache();std::array<GLboolean,4> v{GLboolean(r!=0),GLboolean(g!=0),GLboolean(b!=0),GLboolean(a!=0)};
  if(skipGlSetter(c,GlSetter::ColorMask,c&&c->colorMask==v))return;
  glCommand(RenderDetail::GlState,[&]{::glColorMask(r,g,b,a);},"glColorMask");
  if(c)c->colorMask=v;
}
inline void enableGlAttribute(GLuint index,bool enable) {
  auto* c=currentGlLocationCache();bool valid=c&&index<c->attributeEnabled.size();
  if(skipGlSetter(c,GlSetter::AttributeEnable,valid&&c->attributeEnabled[index]==enable))return;
  glCommand(RenderDetail::GlState,[&]{if(enable)::glEnableVertexAttribArray(index);else ::glDisableVertexAttribArray(index);},enable?"glEnableVertexAttribArray":"glDisableVertexAttribArray");
  if(valid){c->attributeEnabled[index]=enable;if(index<64){if(enable)c->enabledAttributeMask|=uint64_t(1)<<index;else c->enabledAttributeMask&=~(uint64_t(1)<<index);}}
}
// API29's GLES encoder packs all enabled client arrays, even shader-inactive
// locations. A prior short array can otherwise be read with the next draw's
// large first/index value. Never touch active locations or inspect client memory.
inline void guardGlClientArrays() {
  auto* c=currentGlLocationCache();if(!c||!c->programKnown)return;
  auto found=c->programs.find(c->currentProgram);
  if(found==c->programs.end()||!found->second.linked||!found->second.activeAttributesLoaded)return;
  const auto& program=found->second;
  if(c->maxAttributes<=64&&(c->enabledAttributeMask&~program.activeAttributeMask)==0)return;
  for(GLuint i=0;i<program.activeAttributes.size();++i)
    if(c->attributeEnabled[i]&&!program.activeAttributes[i])enableGlAttribute(i,false);
}
inline bool managedGlIntegers(GlLocationCache& c,GLenum name,GLint* data) {
  switch(name) {
    case GL_ALIASED_LINE_WIDTH_RANGE:std::copy(c.lineRangeInt.begin(),c.lineRangeInt.end(),data);break;
    case GL_MAX_TEXTURE_SIZE:*data=c.maxTexture;break;
    case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:*data=c.maxTextureUnits;break;
    case GL_MAX_VERTEX_ATTRIBS:*data=c.maxAttributes;break;
    case GL_MAX_VIEWPORT_DIMS:std::copy(c.maxViewport.begin(),c.maxViewport.end(),data);break;
    case GL_SCISSOR_BOX:std::copy(c.scissor.begin(),c.scissor.end(),data);break;
    case GL_VIEWPORT:std::copy(c.viewport.begin(),c.viewport.end(),data);break;
    case GL_CURRENT_PROGRAM:if(!c.programKnown)return false;*data=c.currentProgram;break;
    case GL_ACTIVE_TEXTURE:*data=c.activeTexture;break;
    case GL_TEXTURE_BINDING_2D:if(!c.textureBindingKnown[c.activeTexture-GL_TEXTURE0])return false;*data=boundGlTexture(c);break;
    case GL_ARRAY_BUFFER_BINDING:if(!c.arrayBufferKnown)return false;*data=c.arrayBuffer;break;
    case GL_ELEMENT_ARRAY_BUFFER_BINDING:if(!c.elementBufferKnown)return false;*data=c.elementBuffer;break;
    case GL_FRAMEBUFFER_BINDING:if(!c.framebufferKnown)return false;*data=c.framebuffer;break;
    case GL_BLEND_SRC_RGB:*data=c.blendSource;break;
    case GL_BLEND_SRC_ALPHA:*data=c.blendAlphaSource;break;
    case GL_BLEND_DST_RGB:*data=c.blendDestination;break;
    case GL_BLEND_DST_ALPHA:*data=c.blendAlphaDestination;break;
    case GL_LINE_WIDTH:if(!c.lineWidthKnown)return false;*data=GLint(std::round(c.lineWidth));break;
    default:return false;
  }
  return true;
}
inline void getGlIntegers(GLenum name,GLint* data) {
  auto* c=currentGlLocationCache();
  if(c&&c->stateEnabled&&data&&managedGlIntegers(*c,name,data)){++c->stateHits;return;}
  if(c){++c->queries.state;++c->specialStateQueries[name];}
  DetailScope profile(RenderDetail::GlQueries);startupGlCall(c,"glGetIntegerv",[&]{::glGetIntegerv(name,data);});
}
inline void getGlFloats(GLenum name,GLfloat* data) {
  auto* c=currentGlLocationCache();
  if(c&&c->stateEnabled&&data) {
    if(name==GL_ALIASED_LINE_WIDTH_RANGE){std::copy(c->lineRange.begin(),c->lineRange.end(),data);++c->stateHits;return;}
    if(name==GL_LINE_WIDTH&&c->lineWidthKnown){*data=c->lineWidth;++c->stateHits;return;}
    if(name==GL_COLOR_CLEAR_VALUE){std::copy(c->clearColor.begin(),c->clearColor.end(),data);++c->stateHits;return;}
    GLint integers[4];if(managedGlIntegers(*c,name,integers)) {
      int n=(name==GL_SCISSOR_BOX||name==GL_VIEWPORT)?4:name==GL_MAX_VIEWPORT_DIMS?2:1;
      for(int i=0;i<n;++i)data[i]=integers[i];++c->stateHits;return;
    }
  }
  if(c){++c->queries.state;++c->specialStateQueries[name];}
  DetailScope profile(RenderDetail::GlQueries);startupGlCall(c,"glGetFloatv",[&]{::glGetFloatv(name,data);});
}
inline void getGlBooleans(GLenum name,GLboolean* data) {
  auto* c=currentGlLocationCache();
  if(c&&c->stateEnabled&&data&&name==GL_COLOR_WRITEMASK){std::copy(c->colorMask.begin(),c->colorMask.end(),data);++c->stateHits;return;}
  if(c){++c->queries.state;++c->specialStateQueries[name];}
  DetailScope profile(RenderDetail::GlQueries);startupGlCall(c,"glGetBooleanv",[&]{::glGetBooleanv(name,data);});
}
inline GLenum checkGlFramebuffer(GLenum target) {
  auto* c=currentGlLocationCache();if(c)++c->queries.validation;
  return startupGlCall(c,"glCheckFramebufferStatus",[&]{return ::glCheckFramebufferStatus(target);});
}
template<class Function> inline auto glValidation(Function&& call,const char* api)->decltype(call()) {
  auto* c=currentGlLocationCache();if(c)++c->queries.validation;return startupGlCall(c,api,std::forward<Function>(call));
}
// Explicit opt-in verification: separate from the production rendering path.
// Raw driver reads cannot silently "repair" the cache and conceal an omission.
inline void verifyGlDelegateState() {
  auto* c=currentGlLocationCache();if(!c)throw std::runtime_error("No owned GLES context to verify");
  auto ints=[&](GLenum name,const GLint* expected,int count){GLint actual[4]{};++c->queries.debug;::glGetIntegerv(name,actual);if(!std::equal(actual,actual+count,expected))throw std::runtime_error("GLES delegate mismatch: "+std::to_string(name));};
  ints(GL_VIEWPORT,c->viewport.data(),4);ints(GL_SCISSOR_BOX,c->scissor.data(),4);
  for(GLenum name:{GL_CURRENT_PROGRAM,GL_ACTIVE_TEXTURE,GL_TEXTURE_BINDING_2D,GL_ARRAY_BUFFER_BINDING,GL_ELEMENT_ARRAY_BUFFER_BINDING,GL_FRAMEBUFFER_BINDING,GL_BLEND_SRC_RGB,GL_BLEND_DST_RGB}){GLint expected[4]{};if(managedGlIntegers(*c,name,expected))ints(name,expected,1);}
  for(auto [cap,enabled]:c->enabledCaps){++c->queries.debug;if((::glIsEnabled(cap)==GL_TRUE)!=enabled)throw std::runtime_error("GLES delegate enable mismatch");}
  GLfloat width=0;++c->queries.debug;::glGetFloatv(GL_LINE_WIDTH,&width);if(c->lineWidthKnown&&width!=c->lineWidth)throw std::runtime_error("GLES delegate line-width mismatch");
  GLfloat clear[4];++c->queries.debug;::glGetFloatv(GL_COLOR_CLEAR_VALUE,clear);if(!std::equal(clear,clear+4,c->clearColor.begin()))throw std::runtime_error("GLES delegate clear-color mismatch actual="+std::to_string(clear[0])+","+std::to_string(clear[1])+","+std::to_string(clear[2])+","+std::to_string(clear[3])+" expected="+std::to_string(c->clearColor[0])+","+std::to_string(c->clearColor[1])+","+std::to_string(c->clearColor[2])+","+std::to_string(c->clearColor[3]));
  GLboolean mask[4];++c->queries.debug;::glGetBooleanv(GL_COLOR_WRITEMASK,mask);if(!std::equal(mask,mask+4,c->colorMask.begin()))throw std::runtime_error("GLES delegate color-mask mismatch");
  for(GLuint i=0;i<c->attributeEnabled.size();++i){GLint actual=0;++c->queries.debug;::glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&actual);if((actual!=0)!=c->attributeEnabled[i])throw std::runtime_error("GLES delegate attribute-enable mismatch");}
  for(const auto& [id,program]:c->programs)if(program.linked)for(const auto& [location,value]:program.values)if(value.known){
    ++c->queries.debug;
    if(value.type==GL_INT||value.type==GL_BOOL||value.type==GL_SAMPLER_2D||value.type==GL_SAMPLER_CUBE){
      GLint expected=0,actual=0;std::memcpy(&expected,value.bytes.data(),sizeof expected);if(value.type==GL_BOOL)expected=expected!=0;
      ::glGetUniformiv(id,location,&actual);if(expected!=actual)throw std::runtime_error("GLES delegate integer-uniform mismatch");
    }else{
      std::array<GLfloat,16> expected{},actual{};std::memcpy(expected.data(),value.bytes.data(),value.bytes.size());::glGetUniformfv(id,location,actual.data());
      int count=value.type==GL_FLOAT?1:value.type==GL_FLOAT_VEC2?2:value.type==GL_FLOAT_VEC4?4:16;
      for(int i=0;i<count;++i)if(expected[i]!=actual[i]&&!(std::isnan(expected[i])&&std::isnan(actual[i])))throw std::runtime_error("GLES delegate float-uniform mismatch");
    }
  }
}
}
