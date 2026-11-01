#pragma once
// No5 value and in-memory drawing adapters for the original OpenCPN CPU
// presentation library. No windows, event loops, dialogs or wx runtime.
#include "no5_profile.hpp"
#include "no5_ablation.hpp"
#include "no5_line_batch.hpp"
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <type_traits>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <functional>
#include <list>
#include <iomanip>
#include <map>
#include <memory>
#include <sstream>
#ifdef NO5_LINE_BATCH_PROBE
namespace no5 {
class ProbeBBoxScope {
 bool reduced,measured,cpuMeasured;ProfileClock::time_point wall{};double cpu=-1;
public:
 explicit ProbeBBoxScope(bool r=false):reduced(r),measured(profileActive&&currentProfile.detailed&&currentProfile.detailCpuAvailable),cpuMeasured(measured&&currentProfile.detailCpuAvailable){
  if(reduced)probeSubmissionCounts.reducedBboxChecks++;else probeSubmissionCounts.bboxChecks++;
  if(measured)wall=ProfileClock::now();if(cpuMeasured)cpu=threadCpuMs();
 }
 void complete(bool retained){
  auto& p=probeSubmissionCounts;if(reduced){if(!retained)p.reducedBboxRejected++;}else if(retained)p.bboxRetained++;else p.bboxRejected++;
  if(measured){p.bboxTimingMeasured=true;p.bboxWallMs+=std::chrono::duration<double,std::milli>(ProfileClock::now()-wall).count();}
  if(cpuMeasured){const auto end=threadCpuMs();if(cpu>=0&&end>=cpu){p.bboxCpuMeasured=true;p.bboxCpuMs+=end-cpu;}}
 }
};
}
#endif
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>
#define _T(x) x
#define wxT(x) x
#define WX_PRECOMP 1
#define wxCHECK_VERSION(a,b,c) 1
#define wxUSE_GRAPHICS_CONTEXT 0
using wxChar=char; using wxCoord=int; using wxUint32=uint32_t; using wxDash=unsigned char;
#include <GLES2/gl2.h>
#ifdef NO5_LINE_BATCH_PROBE
namespace no5 {
inline void probeLineSubmission(GLenum mode,GLsizei count){if(count<=0)return;auto& p=probeSubmissionCounts;if(mode==GL_LINES){p.linePrimitiveSegments+=count/2;p.lineVerticesOrIndices+=count;}else if(mode==GL_LINE_STRIP){p.linePrimitiveSegments+=count>1?count-1:0;p.lineVerticesOrIndices+=count;}}
}
#endif
#include "no5_gl_locations.hpp"
namespace no5 {
// Owner-thread metric pass through the original S52 portrayal. It may prepare
// genuine glyph/symbol resources, but never submits framebuffer primitives.
inline thread_local bool measuringPortrayalBounds=false;
inline thread_local double measuredPortrayalStroke=0;
inline thread_local void* portrayalBoundsDc=nullptr;
struct PortrayalBoundsScope {
 bool previous=measuringPortrayalBounds;void* previousDc=portrayalBoundsDc;
 explicit PortrayalBoundsScope(void* dc){measuringPortrayalBounds=true;portrayalBoundsDc=dc;}
 ~PortrayalBoundsScope(){measuringPortrayalBounds=previous;portrayalBoundsDc=previousDc;}
};
}
inline void no5PortrayalLineWidth(GLfloat value){
 if(no5::measuringPortrayalBounds&&value>0&&std::isfinite(value))no5::measuredPortrayalStroke=std::max(no5::measuredPortrayalStroke,double(value));
 no5::lineWidthGl(value);
}
#include <iostream>
// Compatibility for the original OpenCPN GLES branch. Texture enable,
// stipple and fixed-function smoothing are not GLES2 capabilities.
constexpr GLenum GL_LINE_STIPPLE=0x0b24,GL_POLYGON_SMOOTH=0x0b41;
constexpr GLenum GL_SMOOTH_LINE_WIDTH_RANGE=0x0b22,GL_SMOOTH_LINE_WIDTH_GRANULARITY=0x0b23;
constexpr GLenum GL_TEXTURE_WIDTH=0x1000,GL_TEXTURE_HEIGHT=0x1001;
constexpr GLenum GL_ARRAY_BUFFER_ARB=GL_ARRAY_BUFFER;
inline void no5GlEnable(GLenum cap){if(cap!=GL_TEXTURE_2D&&cap!=GL_LINE_STIPPLE&&cap!=0x0b20&&cap!=GL_POLYGON_SMOOTH)no5::enableGlCapability(cap,true);}
inline void no5GlDisable(GLenum cap){if(cap!=GL_TEXTURE_2D&&cap!=GL_LINE_STIPPLE&&cap!=0x0b20&&cap!=GL_POLYGON_SMOOTH)no5::enableGlCapability(cap,false);}
inline void no5GlGetFloatv(GLenum name,GLfloat* data){if(name==GL_SMOOTH_LINE_WIDTH_GRANULARITY)*data=1;else no5::getGlFloats(name==GL_SMOOTH_LINE_WIDTH_RANGE?GL_ALIASED_LINE_WIDTH_RANGE:name,data);}
inline void no5GlGetIntegerv(GLenum name,GLint* data){if(name==GL_SMOOTH_LINE_WIDTH_GRANULARITY){data[0]=data[1]=1;}else no5::getGlIntegers(name==GL_SMOOTH_LINE_WIDTH_RANGE?GL_ALIASED_LINE_WIDTH_RANGE:name,data);}
inline void no5GlHint(GLenum target,GLenum value){if(target!=0x0c52)no5::glCommand(no5::RenderDetail::GlState,[&]{::glHint(target,value);},"glHint");}
inline thread_local GLenum no5FirstGlError=GL_NO_ERROR;
inline GLenum no5GlGetError(){if(auto* c=no5::currentGlLocationCache())++c->queries.errors;no5::DetailScope profile(no5::RenderDetail::GlErrors);auto error=::glGetError();if(error!=GL_NO_ERROR&&no5FirstGlError==GL_NO_ERROR)no5FirstGlError=error;return error;}
inline void no5GlTexImage2D(GLenum target,GLint level,GLint format,GLsizei w,GLsizei h,GLint border,GLenum type,GLenum scalar,const void* bytes){
 if(no5::profileActive){auto& p=no5::currentProfile;p.uploadCalls++;unsigned int channels=type==GL_RGBA?4:type==GL_RGB?3:type==GL_LUMINANCE_ALPHA?2:(type==GL_ALPHA||type==GL_LUMINANCE)?1:0;unsigned int unit=scalar==GL_UNSIGNED_BYTE?1:(scalar==GL_UNSIGNED_SHORT_5_6_5||scalar==GL_UNSIGNED_SHORT_4_4_4_4||scalar==GL_UNSIGNED_SHORT_5_5_5_1)?2:0;if(unit==2)channels=1;if(w>=0&&h>=0&&channels&&unit){auto count=uint64_t(w)*h*channels*unit;p.textureAllocationBytes+=count;if(bytes)p.textureUploadBytes+=count;}else p.unknownUploadSizes++;}
 no5::glCommand(no5::RenderDetail::GlUploads,[&]{::glTexImage2D(target,level,format,w,h,border,type,scalar,bytes);},"glTexImage2D");
 if(target==GL_TEXTURE_2D&&level==0&&w>=0&&h>=0&&border==0&&format==GLint(type))if(auto* c=no5::currentGlLocationCache())if(c->textureBindingKnown[c->activeTexture-GL_TEXTURE0]&&w<=c->maxTexture&&h<=c->maxTexture)c->textureSizes[no5::boundGlTexture(*c)]={w,h};
}
inline void glGetTexLevelParameteriv(GLenum target,GLint level,GLenum name,GLint* value){if(target!=GL_TEXTURE_2D||level!=0||(name!=GL_TEXTURE_WIDTH&&name!=GL_TEXTURE_HEIGHT))throw std::runtime_error("Unsupported GLES texture query");auto* c=no5::currentGlLocationCache();if(!c)throw std::runtime_error("Texture query outside owned GLES context");auto found=c->textureSizes.find(no5::boundGlTexture(*c));if(found==c->textureSizes.end())throw std::runtime_error("Missing GLES texture dimensions");*value=name==GL_TEXTURE_WIDTH?found->second.first:found->second.second;}
// Runtime evidence: submissions before the final framebuffer presentation quad.
inline thread_local unsigned long long no5DrawCalls=0;
inline void no5GlDrawArrays(GLenum mode,GLint first,GLsizei count){if(no5::measuringPortrayalBounds)return;++no5DrawCalls;
if(first>=0&&count>0)no5::guardGlClientArrays();
#ifdef NO5_LINE_BATCH_PROBE
no5::probeLineSubmission(mode,count);
#endif
if(no5::profileActive&&count>0)no5::currentProfile.submittedVertices+=count;no5::glCommand(no5::RenderDetail::GlDraw,[&]{::glDrawArrays(mode,first,count);},"glDrawArrays");}
inline void no5GlDrawElements(GLenum mode,GLsizei count,GLenum type,const void* indices){if(no5::measuringPortrayalBounds)return;++no5DrawCalls;
if(count>0&&(type==GL_UNSIGNED_BYTE||type==GL_UNSIGNED_SHORT))no5::guardGlClientArrays();
#ifdef NO5_LINE_BATCH_PROBE
no5::probeLineSubmission(mode,count);
#endif
if(no5::profileActive&&count>0)no5::currentProfile.submittedVertices+=count;no5::glCommand(no5::RenderDetail::GlDraw,[&]{::glDrawElements(mode,count,type,indices);},"glDrawElements");}
inline void no5GlBufferData(GLenum target,GLsizeiptr size,const void* data,GLenum usage){if(no5::profileActive){auto& p=no5::currentProfile;p.uploadCalls++;if(size>=0){p.bufferAllocationBytes+=size;if(data)p.bufferUploadBytes+=size;}else p.unknownUploadSizes++;}no5::glCommand(no5::RenderDetail::GlUploads,[&]{::glBufferData(target,size,data,usage);},"glBufferData");}
// Sample selected driver-facing APIs without forcing synchronization.
#define glGetUniformLocation(program,name) no5::glLocation(program,name,true)
#define glGetAttribLocation(program,name) no5::glLocation(program,name,false)
#define glLinkProgram no5::linkGlProgram
#define glDeleteProgram no5::deleteGlProgram
#define glUseProgram no5::useGlProgram
#define glUniform1i no5::uniformGl1i
#define glUniform1f no5::uniformGl1f
#define glUniform2f no5::uniformGl2f
#define glUniform2fv no5::uniformGl2fv
#define glUniform4f no5::uniformGl4f
#define glUniform4fv no5::uniformGl4fv
#define glUniformMatrix4fv no5::uniformGlMatrix4fv
#define glVertexAttribPointer(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glVertexAttribPointer(__VA_ARGS__);},"glVertexAttribPointer")
#define glEnableVertexAttribArray(index) no5::enableGlAttribute(index,true)
#define glDisableVertexAttribArray(index) no5::enableGlAttribute(index,false)
#define glBindBuffer no5::bindGlBuffer
#define glLineWidth no5PortrayalLineWidth
#define glBlendFunc no5::blendGlFunc
#define glActiveTexture no5::activeGlTexture
#define glDrawArrays no5GlDrawArrays
#define glDrawElements no5GlDrawElements
#define glEnable no5GlEnable
#define glDisable no5GlDisable
#define glGetFloatv no5GlGetFloatv
#define glGetIntegerv no5GlGetIntegerv
#define glHint no5GlHint
#define glBindTexture no5::bindGlTexture
#define glTexImage2D no5GlTexImage2D
#define glDeleteTextures no5::deleteGlTextures
#define glGetError no5GlGetError
#define glCreateProgram no5::createGlProgram
#define glGetProgramiv no5::getGlProgramiv
#define glIsEnabled no5::isGlEnabled
#define glScissor no5::scissorGl
#define glViewport no5::viewportGl
#define glGetBooleanv no5::getGlBooleans
#define glBindFramebuffer no5::bindGlFramebuffer
#define glDeleteFramebuffers no5::deleteGlFramebuffers
#define glDeleteBuffers no5::deleteGlBuffers
#define glColorMask no5::colorGlMask
#define glClearColor no5::clearGlColor
#define glCheckFramebufferStatus no5::checkGlFramebuffer
#define glAttachShader(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glAttachShader(__VA_ARGS__);},"glAttachShader")
#define glBindAttribLocation(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glBindAttribLocation(__VA_ARGS__);},"glBindAttribLocation")
#define glBufferData no5GlBufferData
#define glClear(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glClear(__VA_ARGS__);},"glClear")
#define glCompileShader(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glCompileShader(__VA_ARGS__);},"glCompileShader")
#define glCreateShader(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glCreateShader(__VA_ARGS__);},"glCreateShader")
#define glDeleteShader(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glDeleteShader(__VA_ARGS__);},"glDeleteShader")
#define glFramebufferTexture2D(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glFramebufferTexture2D(__VA_ARGS__);},"glFramebufferTexture2D")
#define glGenBuffers no5::genGlBuffers
#define glGenFramebuffers no5::genGlFramebuffers
#define glGenTextures no5::genGlTextures
#define glShaderSource(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glShaderSource(__VA_ARGS__);},"glShaderSource")
#define glTexParameteri(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glTexParameteri(__VA_ARGS__);},"glTexParameteri")
#define glGetShaderiv(...) no5::glValidation([&](){return ::glGetShaderiv(__VA_ARGS__);},"glGetShaderiv")
#define glGetShaderInfoLog(...) no5::glValidation([&](){return ::glGetShaderInfoLog(__VA_ARGS__);},"glGetShaderInfoLog")
#define glGetProgramInfoLog(...) no5::glValidation([&](){return ::glGetProgramInfoLog(__VA_ARGS__);},"glGetProgramInfoLog")
#define glGetString(...) no5::glValidation([&](){return ::glGetString(__VA_ARGS__);},"glGetString")
// Retain the existing profiling buckets for these land-pass APIs; only the
// bounded startup trace observes them. Warm calls add no error queries.
#define glUniform3f(...) no5::startupGlCall(no5::currentGlLocationCache(),"glUniform3f",[&](){return ::glUniform3f(__VA_ARGS__);})
#define glUniform3fv(...) no5::glCommand(no5::RenderDetail::GlState,[&](){return ::glUniform3fv(__VA_ARGS__);},"glUniform3fv")
#define glCopyTexImage2D(...) no5::startupGlCall(no5::currentGlLocationCache(),"glCopyTexImage2D",[&](){return ::glCopyTexImage2D(__VA_ARGS__);})
#define glCopyTexSubImage2D(...) no5::startupGlCall(no5::currentGlLocationCache(),"glCopyTexSubImage2D",[&](){return ::glCopyTexSubImage2D(__VA_ARGS__);})
class wxGLContext {};  // Opaque compatibility token: No5 EGL owns the context.
inline constexpr bool g_b_EnableVBO=false;  // No5 uses the original GLES client-array path.
constexpr int wxConvUTF8=0,wxNOT_FOUND=-1;
inline int wxRound(double x){return int(std::lround(x));}
inline bool wxFinite(double x){return std::isfinite(x);}
template<class A,class B> auto wxMin(A a,B b){return std::min<std::common_type_t<A,B>>(a,b);}
template<class A,class B> auto wxMax(A a,B b){return std::max<std::common_type_t<A,B>>(a,b);}
class wxCharBuffer {std::string s;public:wxCharBuffer()=default;wxCharBuffer(const char* p):s(p?p:""){}const char* data()const{return s.c_str();}operator const char*()const{return data();}};
struct GLUtesselator;
constexpr GLenum GL_LINE_SMOOTH=0x0b20,GL_LINE_SMOOTH_HINT=0x0c52;
struct wxMBConvUTF16 {};struct wxCSConv {std::string name;explicit wxCSConv(const char* n):name(n){}};
class wxString:public std::string {
 public:
  using std::string::string; using std::string::operator=;
  wxString()=default; wxString(const std::string& s):std::string(s){}
  static void utf8(std::string& out,uint32_t cp){if(cp<128)out+=char(cp);else if(cp<2048){out+=char(0xc0|(cp>>6));out+=char(0x80|(cp&63));}else if(cp<65536){out+=char(0xe0|(cp>>12));out+=char(0x80|((cp>>6)&63));out+=char(0x80|(cp&63));}else{out+=char(0xf0|(cp>>18));out+=char(0x80|((cp>>12)&63));out+=char(0x80|((cp>>6)&63));out+=char(0x80|(cp&63));}}
  wxString(const char* p,wxMBConvUTF16){auto s=reinterpret_cast<const unsigned char*>(p);bool be=s[0]==0xfe&&s[1]==0xff;if(be||(s[0]==0xff&&s[1]==0xfe))s+=2;auto word=[&](){uint32_t v=be?(s[0]<<8)|s[1]:(s[1]<<8)|s[0];s+=2;return v;};while(true){auto cp=word();if(!cp)break;if(cp>=0xd800&&cp<=0xdbff){auto low=word();if(low<0xdc00||low>0xdfff)throw std::runtime_error("invalid UTF-16 chart text");cp=0x10000+((cp-0xd800)<<10)+(low-0xdc00);}utf8(*this,cp);}}
  wxString(const char* s,const wxCSConv& conv){if(conv.name!="iso8859-1")throw std::runtime_error("unsupported text encoding");while(*s)utf8(*this,static_cast<unsigned char>(*s++));}
  wxString(const unsigned char* s,int conv):wxString(reinterpret_cast<const char*>(s),conv){}
  wxString(const char* s,int):std::string(s?s:""){}
  wxCharBuffer mb_str()const{return c_str();} wxCharBuffer char_str()const{return c_str();} const char* fn_str()const{return c_str();}
  wxCharBuffer ToUTF8()const{return c_str();} size_t Len()const{return size();}
  size_t Length()const{return size();} bool IsEmpty()const{return empty();}
  void Append(const wxString& s){append(s);}void Append(char c){push_back(c);}void Prepend(const wxString& s){insert(0,s);}
  bool Contains(const wxString& s)const{return find(s)!=npos;}wxString operator()(size_t p,size_t n)const{return Mid(p,n);}
  wxString& operator<<(const wxString& s){append(s);return *this;}
  char Last()const{return back();}void RemoveLast(){if(empty())return;do{auto c=static_cast<unsigned char>(back());pop_back();if((c&0xc0)!=0x80)break;}while(!empty());}std::string ToStdString()const{return *this;}
  void Clear(){clear();} char GetChar(size_t i)const{return at(i);}
  wxString Left(size_t n)const{return substr(0,n);} wxString Mid(size_t p,size_t n=npos)const{return substr(p,n);}
  int Find(char c)const{auto p=find(c);return p==npos?-1:int(p);}
  int Find(const wxString& s)const{auto p=find(s);return p==npos?-1:int(p);}
  int Index(char c)const{return Find(c);}bool StartsWith(const wxString& s,wxString* rest)const{bool ok=StartsWith(s);if(ok&&rest)*rest=Mid(s.size());return ok;}
  bool StartsWith(const wxString& s)const{return compare(0,s.size(),s)==0;}
  bool EndsWith(const wxString& s)const{return size()>=s.size()&&compare(size()-s.size(),s.size(),s)==0;}
  wxString Upper()const{wxString r=*this;for(auto& c:r)c=std::toupper((unsigned char)c);return r;}
  int CmpNoCase(const wxString& s)const{return Upper().compare(s.Upper());}
  bool IsSameAs(const wxString& s,bool cs=true)const{return cs?*this==s:CmpNoCase(s)==0;}
  wxString& Trim(bool right=true){if(right){while(!empty()&&std::isspace((unsigned char)back()))pop_back();}else{size_t n=0;while(n<size()&&std::isspace((unsigned char)(*this)[n]))n++;erase(0,n);}return *this;}
  size_t Replace(const wxString& from,const wxString& to,bool all=true){size_t p=0,n=0;while(!from.empty()&&(p=find(from,p))!=npos){replace(p,from.size(),to);p+=to.size();n++;if(!all)break;}return n;}
  // wxWidgets writes a parsed numeric prefix even when a suffix makes the
  // return value false. Retained S52 light rules pass radii such as "15.0"
  // through ToLong and use that output; leaving it untouched was undefined.
  bool ToLong(long* v,int base=10)const{const int saved=errno;errno=0;char* e;auto n=strtol(c_str(),&e,base);bool valid=e!=c_str()&&errno!=ERANGE;errno=saved;if(!valid)return false;*v=n;return !*e;}
  bool ToDouble(double* v)const{const int saved=errno;errno=0;char* e;auto n=strtod(c_str(),&e);bool valid=e!=c_str()&&errno!=ERANGE;errno=saved;if(!valid)return false;*v=n;return !*e;}
  template<class T> static T fmt(T v){return v;} static const char* fmt(const wxString& v){return v.c_str();}
  template<class... A> void Printf(const char* f,const A&... a){if constexpr(sizeof...(A)==0){assign(f);}else{int n=snprintf(nullptr,0,f,fmt(a)...);if(n<0)throw std::runtime_error("format");std::vector<char>b(n+1);snprintf(b.data(),b.size(),f,fmt(a)...);assign(b.data(),n);}}
  template<class... A> static wxString Format(const char* f,const A&... a){wxString s;s.Printf(f,a...);return s;}
};
inline const wxString wxEmptyString;
struct wxStringHash{size_t operator()(const wxString& s)const{return std::hash<std::string>{}(s);}};
struct wxStringEqual{bool operator()(const wxString& a,const wxString& b)const{return a==b;}};
class wxStringTokenizer {
 wxString s,delims;size_t pos=0;
 public: wxStringTokenizer(const wxString& str,const wxString& d):s(str),delims(d){}
 bool HasMoreTokens()const{return s.find_first_not_of(delims,pos)!=wxString::npos;}
 wxString GetNextToken(){auto a=s.find_first_not_of(delims,pos);if(a==wxString::npos){pos=s.size();return {};}auto b=s.find_first_of(delims,a);pos=b==wxString::npos?s.size():b+1;return s.substr(a,b==wxString::npos?b:b-a);}
};
template<class T> class No5Array:public std::vector<T>{
 using V=std::vector<T>; std::function<int(T,T)> compare;
 public:using V::V; No5Array(int(*c)(T,T)):compare(c){}
 size_t GetCount()const{return V::size();} T& Item(size_t i){return V::at(i);} const T& Item(size_t i)const{return V::at(i);}
 void Add(T v){if(compare){auto i=std::lower_bound(V::begin(),V::end(),v,[&](T a,T b){return compare(a,b)<0;});V::insert(i,v);}else V::push_back(v);}
 void Clear(){V::clear();} void RemoveAt(size_t i,size_t n=1){V::erase(V::begin()+i,V::begin()+i+n);}
 int Index(const T& x)const{auto i=std::find(V::begin(),V::end(),x);return i==V::end()?-1:int(i-V::begin());}
};
template<class T> class No5List {
 public:struct Node{T* data;Node* next=nullptr;T* GetData(){return data;} Node* GetNext(){return next;}};
 private:Node* head=nullptr;Node* tail=nullptr;
 public:~No5List(){Clear();}Node* GetFirst(){return head;}void Append(T* p){auto n=new Node{p};if(tail)tail->next=n;else head=n;tail=n;}
 void Clear(){while(head){auto n=head->next;delete head;head=n;}tail=nullptr;}
};
using wxArrayPtrVoid=No5Array<void*>;
#define WX_DECLARE_HASH_MAP(K,V,H,E,N) class N:public std::unordered_map<K,V,H,E>{}
#define WX_DECLARE_STRING_HASH_MAP(V,N) class N:public std::unordered_map<wxString,V,wxStringHash>{}
#define WX_DEFINE_SORTED_ARRAY(T,N) class N:public No5Array<T>{public:using No5Array<T>::No5Array;}
#define WX_DEFINE_ARRAY_PTR(T,N) class N:public No5Array<T>{public:using No5Array<T>::No5Array;}
#define WX_DECLARE_OBJARRAY(T,N) class N:public No5Array<T>{public:using No5Array<T>::No5Array;}
#define WX_DECLARE_LIST(T,N) class N:public No5List<T>{}
#define WX_DEFINE_LIST(N)
#define WX_DEFINE_OBJARRAY(N)
struct wxPoint {int x=0,y=0;wxPoint()=default;wxPoint(int a,int b):x(a),y(b){};bool operator!=(const wxPoint& p)const{return x!=p.x||y!=p.y;}};
struct wxPoint2DDouble {union{double x;double m_x;};union{double y;double m_y;};wxPoint2DDouble(double a=0,double b=0):x(a),y(b){};wxPoint2DDouble(wxPoint p):x(p.x),y(p.y){};wxPoint2DDouble& operator-=(wxPoint p){x-=p.x;y-=p.y;return *this;}wxPoint2DDouble& operator+=(wxPoint2DDouble p){x+=p.x;y+=p.y;return *this;}bool operator!=(const wxPoint2DDouble& p)const{return x!=p.x||y!=p.y;}};
struct wxSize {int x=0,y=0;wxSize()=default;wxSize(int a,int b):x(a),y(b){};int GetWidth()const{return x;}int GetHeight()const{return y;}};
struct wxRect {
 int x=0,y=0,width=0,height=0;
 wxRect()=default;wxRect(int a,int b,int w,int h):x(a),y(b),width(w),height(h){};wxRect(wxPoint p,wxSize s):wxRect(p.x,p.y,s.x,s.y){}
 wxRect(wxPoint a,wxPoint b):wxRect(a.x,a.y,b.x-a.x+1,b.y-a.y+1){}
 int GetX()const{return x;}int GetY()const{return y;}int GetWidth()const{return width;}int GetHeight()const{return height;}
 void SetX(int v){x=v;}void SetY(int v){y=v;}void SetWidth(int v){width=v;}void SetHeight(int v){height=v;}
 bool IsEmpty()const{return width<=0||height<=0;}bool Contains(int a,int b)const{return a>=x&&b>=y&&a<x+width&&b<y+height;}
 bool Contains(wxPoint p)const{return Contains(p.x,p.y);}bool Intersects(const wxRect& r)const{return !IsEmpty()&&!r.IsEmpty()&&x<r.x+r.width&&r.x<x+width&&y<r.y+r.height&&r.y<y+height;}
 wxRect& Inflate(int a,int b){x-=a;y-=b;width+=a*2;height+=b*2;return *this;}wxRect& Inflate(int a){return Inflate(a,a);}
 wxRect& Union(const wxRect& r){if(IsEmpty()){*this=r;return *this;}int a=std::min(x,r.x),b=std::min(y,r.y);width=std::max(x+width,r.x+r.width)-a;height=std::max(y+height,r.y+r.height)-b;x=a;y=b;return *this;}
};
class wxColour {
 uint8_t r=0,g=0,b=0,a=255;
 public:wxColour()=default;wxColour(int rr,int gg,int bb,int aa=255):r(rr),g(gg),b(bb),a(aa){}
 void Set(int rr,int gg,int bb,int aa=255){r=rr;g=gg;b=bb;a=aa;}void SetRGB(unsigned c){r=c&255;g=(c>>8)&255;b=(c>>16)&255;}bool IsOk()const{return true;}
 uint8_t Red()const{return r;}uint8_t Green()const{return g;}uint8_t Blue()const{return b;}uint8_t Alpha()const{return a;}
 unsigned GetRGB()const{return unsigned(r)|(unsigned(g)<<8)|(unsigned(b)<<16);}bool operator==(const wxColour& c)const{return GetRGB()==c.GetRGB()&&a==c.a;}
};
using wxColor=wxColour;
enum{wxPENSTYLE_SOLID,wxPENSTYLE_SHORT_DASH,wxPENSTYLE_USER_DASH,wxBRUSHSTYLE_SOLID,wxBRUSHSTYLE_TRANSPARENT,wxTRANSPARENT,wxCOPY,wxBITMAP_TYPE_PNG,wxIMAGE_QUALITY_HIGH,wxANTIALIAS_NONE};
class wxPen {
 wxColour color;int width=1,style=wxPENSTYLE_SOLID;std::vector<wxDash> dashes;
 public:wxPen()=default;wxPen(wxColour c,int w=1,int s=wxPENSTYLE_SOLID):color(c),width(w),style(s){}
 wxColour GetColour()const{return color;}int GetWidth()const{return width;}int GetStyle()const{return style;}
 void SetColour(wxColour c){color=c;}void SetWidth(int w){width=w;}void SetStyle(int s){style=s;}
 void SetDashes(int n,const wxDash* p){dashes.assign(p,p+n);}int GetDashes(wxDash** p){*p=dashes.data();return dashes.size();}
};
class wxBrush {
 wxColour color;int style=wxBRUSHSTYLE_SOLID;
 public:wxBrush()=default;wxBrush(wxColour c,int s=wxBRUSHSTYLE_SOLID):color(c),style(s){}
 wxColour GetColour()const{return color;}int GetStyle()const{return style;}void SetColour(wxColour c){color=c;}
};
inline wxColour No5Black,No5Green(0,255,0),No5Red(255,0,0);
inline wxColour *wxBLACK=&No5Black,*wxGREEN=&No5Green,*wxRED=&No5Red;
inline wxPen No5BlackPen(No5Black),No5TransparentPen(No5Black,0);
inline wxBrush No5TransparentBrush(No5Black,wxBRUSHSTYLE_TRANSPARENT);
inline wxPen *wxBLACK_PEN=&No5BlackPen,*wxTRANSPARENT_PEN=&No5TransparentPen;
inline wxBrush *wxTRANSPARENT_BRUSH=&No5TransparentBrush;
struct No5PenList{std::list<wxPen> cache;wxPen* FindOrCreatePen(wxColour c,int w,int s){for(auto& p:cache)if(p.GetColour()==c&&p.GetWidth()==w&&p.GetStyle()==s)return &p;cache.emplace_back(c,w,s);return &cache.back();}};
struct No5BrushList{std::list<wxBrush> cache;wxBrush* FindOrCreateBrush(wxColour c,int s){for(auto& b:cache)if(b.GetColour()==c&&b.GetStyle()==s)return &b;cache.emplace_back(c,s);return &cache.back();}};
inline thread_local No5PenList No5Pens;inline thread_local No5BrushList No5Brushes;
inline thread_local No5PenList* wxThePenList=&No5Pens;inline thread_local No5BrushList* wxTheBrushList=&No5Brushes;
enum wxFontFamily{wxFONTFAMILY_SWISS};enum wxFontStyle{wxFONTSTYLE_NORMAL};enum wxFontWeight{wxFONTWEIGHT_LIGHT,wxFONTWEIGHT_NORMAL,wxFONTWEIGHT_BOLD};enum wxFontEncoding{wxFONTENCODING_DEFAULT};
class wxFont {
 int points=10;wxFontFamily family=wxFONTFAMILY_SWISS;wxFontStyle style=wxFONTSTYLE_NORMAL;wxFontWeight weight=wxFONTWEIGHT_NORMAL;wxString face;
 public:wxFont()=default;wxFont(int p,wxFontFamily f,wxFontStyle s,wxFontWeight w,bool=false,wxString n={},wxFontEncoding=wxFONTENCODING_DEFAULT):points(p),family(f),style(s),weight(w),face(n){}
 int GetPointSize()const{return points;}wxFontFamily GetFamily()const{return family;}wxFontStyle GetStyle()const{return style;}wxFontWeight GetWeight()const{return weight;}wxString GetFaceName()const{return face;}
 bool IsOk()const{return points>0;}void SetPointSize(int p){points=p;}bool operator==(const wxFont& f)const{return points==f.points&&weight==f.weight&&face==f.face;}
};
class wxImage {
 public:struct RGBValue{double red,green,blue;RGBValue(double r,double g,double b):red(r),green(g),blue(b){}};struct HSVValue{double hue,saturation,value;};static HSVValue RGBtoHSV(RGBValue rgb){double r=rgb.red/255,g=rgb.green/255,b=rgb.blue/255,m=std::max({r,g,b}),lo=std::min({r,g,b}),d=m-lo,h=0;if(d){if(m==r)h=std::fmod((g-b)/d+6,6);else if(m==g)h=(b-r)/d+2;else h=(r-g)/d+4;h/=6;}return {h,m?d/m:0,m};}
 int w=0,h=0;std::vector<unsigned char> rgb,alpha;bool masked=false;wxColour mask;
 wxImage()=default;wxImage(int ww,int hh):w(ww),h(hh),rgb(size_t(w)*h*3){}
 int GetWidth()const{return w;}int GetHeight()const{return h;}bool IsOk()const{return w>0&&h>0&&rgb.size()==size_t(w)*h*3;}
 unsigned char* GetData(){return rgb.data();}unsigned char* GetAlpha(){return alpha.empty()?nullptr:alpha.data();}
 bool HasAlpha()const{return !alpha.empty();}void InitAlpha(){alpha.assign(size_t(w)*h,255);}
 void SetMaskColour(int r,int g,int b){mask=wxColour(r,g,b);masked=true;}
 bool GetOrFindMaskColour(unsigned char* r,unsigned char* g,unsigned char* b){*r=mask.Red();*g=mask.Green();*b=mask.Blue();return masked;}
 void SetRGB(const wxRect& r,int red,int green,int blue);void SetRGB(int x,int y,int red,int green,int blue){SetRGB(wxRect(x,y,1,1),red,green,blue);}
 wxImage Blur(int radius)const;
 wxImage& Rescale(int width,int height,int=wxIMAGE_QUALITY_HIGH);
 bool LoadFile(const wxString& path,int=wxBITMAP_TYPE_PNG);
};
class wxMask;
class wxBitmap {
 public:std::shared_ptr<wxImage> image;
 wxBitmap()=default;wxBitmap(int w,int h,int=-1):image(std::make_shared<wxImage>(w,h)){}
 wxBitmap(const wxImage& im,int=-1):image(std::make_shared<wxImage>(im)){}
 bool IsOk()const{return image&&image->IsOk();}int GetWidth()const{return image?image->w:0;}int GetHeight()const{return image?image->h:0;}
 wxImage ConvertToImage()const{return image?*image:wxImage();}wxBitmap GetSubBitmap(wxRect r)const;void SetMask(wxMask* m);
 void UseAlpha(){if(image&&!image->HasAlpha())image->InitAlpha();}
};
class wxMask{public:wxColour color;wxMask(const wxBitmap&,wxColour c):color(c){}};
inline wxBitmap wxNullBitmap;
class wxDC {
 protected:std::shared_ptr<wxImage> target;wxPen pen;wxBrush brush,background;wxFont font;wxColour textColor;int minx=0,miny=0,maxx=0,maxy=0;bool bboxValid=false;int bgmode=wxTRANSPARENT;
 const std::vector<uint8_t>* pixelOwners=nullptr;int pixelOwner=-1;
 public:virtual ~wxDC()=default;
 void SelectObject(wxBitmap& bitmap){target=bitmap.image;}void SelectObject(const wxBitmap& bitmap){target=bitmap.image;}
 void SetPen(wxPen p){pen=p;if(no5::measuringPortrayalBounds&&no5::portrayalBoundsDc==this)no5::measuredPortrayalStroke=std::max(no5::measuredPortrayalStroke,double(p.GetWidth()));}void SetBrush(wxBrush b){brush=b;}void SetBackground(wxBrush b){background=b;}
 void SetFont(wxFont f){font=f;}wxFont GetFont()const{return font;}void SetTextForeground(wxColour c){textColor=c;}
 void SetBackgroundMode(int s){bgmode=s;}void SetTextBackground(wxColour c){background=wxBrush(c);}
 void Clear();void DrawPoint(int x,int y);void DrawLine(int x0,int y0,int x1,int y1);void DrawLine(wxPoint a,wxPoint b){DrawLine(a.x,a.y,b.x,b.y);}
 void DrawRectangle(int x,int y,int w,int h);void DrawRectangle(wxRect r){DrawRectangle(r.x,r.y,r.width,r.height);}
 void DrawCircle(int x,int y,int r);void DrawCircle(wxPoint p,int r){DrawCircle(p.x,p.y,r);}
 void DrawEllipticArc(int x,int y,int w,int h,double start,double end);
 void DrawPolygon(int n,const wxPoint* p,int dx=0,int dy=0,int=0);
 void DrawBitmap(const wxBitmap& bitmap,int x,int y,bool mask=true);
 bool Blit(int x,int y,int w,int h,wxDC* src,int sx,int sy,int=wxCOPY,bool mask=false);
 void DrawText(const wxString& text,int x,int y);
 void GetTextExtent(const wxString& text,int* w,int* h,int* descent=nullptr,int* leading=nullptr,const wxFont* f=nullptr);
 bool IsOk()const{return target&&target->IsOk();}
 void ResetBoundingBox(){bboxValid=false;}int MinX()const{return minx;}int MinY()const{return miny;}int MaxX()const{return maxx;}int MaxY()const{return maxy;}
 void Bounds(int x,int y){if(!bboxValid){minx=maxx=x;miny=maxy=y;bboxValid=true;}else{minx=std::min(minx,x);miny=std::min(miny,y);maxx=std::max(maxx,x);maxy=std::max(maxy,y);}}
 std::shared_ptr<wxImage> Image()const{return target;}
 // No5 mixed-scale CPU portrayal clip. Raster areas use a separate scratch
 // canvas; all wx drawing (symbols, lines, labels, bitmap alpha) uses Pixel.
 void SetPixelOwners(const std::vector<uint8_t>* owners,int owner){pixelOwners=owners;pixelOwner=owner;}
 protected:void Pixel(int x,int y,wxColour c);void Span(int x0,int x1,int y,wxColour c);
};
class wxMemoryDC:public wxDC{public:wxMemoryDC()=default;explicit wxMemoryDC(wxBitmap& b){SelectObject(b);}};
class wxScreenDC:public wxDC{}; // Offscreen font metrics only; never a display.
class wxFileName {
 wxString filename;
 public:wxFileName(wxString f):filename(f){}static char GetPathSeparator(){return '/';}
 static bool FileExists(wxString f){return std::filesystem::is_regular_file(std::filesystem::path(std::string(f)));}
 static void SplitPath(wxString f,wxString* p,wxString* n,wxString* e){std::filesystem::path x{std::string(f)};if(p)*p=x.parent_path().string();if(n)*n=x.stem().string();if(e)*e=x.extension().string().substr(x.has_extension()?1:0);}
 wxString GetPath()const{return std::filesystem::path(std::string(filename)).parent_path().string();}
};
class wxTextFile {
 wxString path;std::vector<wxString> lines;size_t cursor=0;
 public:wxTextFile()=default;wxTextFile(wxString p):path(p){}bool Exists()const{return std::filesystem::is_regular_file(std::string(path));}bool Open(wxString p){path=p;return Open();}bool Open(){std::ifstream f(path);std::string l;while(std::getline(f,l))lines.emplace_back(l);return bool(f)||!lines.empty();}
 wxString GetFirstLine(){cursor=0;return lines.empty()?wxString{}:lines[0];}wxString GetNextLine(){return ++cursor<lines.size()?lines[cursor]:wxString{};}bool Eof()const{return cursor>=lines.size();}
};
// Configuration is supplied by the No5 facade. This empty value store preserves
// OpenCPN's documented defaults for its unused desktop config import entrypoints.
class wxFileConfig {
 public:void SetPath(wxString){}template<class T>bool Read(wxString,T* p){return false;}template<class T,class V>bool Read(wxString,T* p,V def){*p=static_cast<T>(def);return false;}
 int GetNumberOfEntries()const{return 0;}bool GetFirstEntry(wxString&,long&){return false;}bool GetNextEntry(wxString&,long&){return false;}
};
class wxTimeSpan{public:long seconds;explicit wxTimeSpan(int hours):seconds(hours*3600L){}};
#if defined(NO5_TEST_DATE_CLOCK) && !defined(__ANDROID__)
// Native regression only. Production Android always uses the real clock.
inline thread_local std::time_t no5DateClockForTests=0;
#endif
class wxDateTime {
 std::time_t value=0;bool valid=false;
 public:static wxDateTime Now(){wxDateTime d;d.value=std::time(nullptr);
#if defined(NO5_TEST_DATE_CLOCK) && !defined(__ANDROID__)
 if(no5DateClockForTests)d.value=no5DateClockForTests;
#endif
 d.valid=true;return d;}
 bool ParseFormat(const wxString& s,const char* fmt,wxString::const_iterator* end){std::tm tm{};std::istringstream in(s);in>>std::get_time(&tm,fmt);if(in.fail())return false;value=std::mktime(&tm);valid=value!=-1;*end=s.begin()+(in.eof()?s.size():size_t(in.tellg()));return valid;}
 bool IsValid()const{return valid;}void ResetTime(){auto tm=*std::localtime(&value);tm.tm_hour=tm.tm_min=tm.tm_sec=0;value=std::mktime(&tm);}
 bool IsEarlierThan(const wxDateTime& d)const{return value<d.value;}bool IsLaterThan(const wxDateTime& d)const{return value>d.value;}
 wxDateTime& operator+=(const wxTimeSpan& s){value+=s.seconds;return *this;}
};
inline long wxGetUTCTime(){return std::time(nullptr);}
class wxStopWatch{std::chrono::steady_clock::time_point start=std::chrono::steady_clock::now();public:long Time()const{return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();}};
inline void wxLogMessage(const wxString& s){std::fprintf(stderr,"OpenCPN: %s\n",s.c_str());}
template<class... A>void wxLogMessage(const char* f,const A&... a){wxLogMessage(wxString::Format(f,a...));}
template<class... A>void wxLogWarning(const char* f,const A&... a){wxLogMessage(f,a...);}

#define WX_DEFINE_ARRAY(T,N) WX_DEFINE_ARRAY_PTR(T,N)
#define WX_DEFINE_ARRAY_DOUBLE(T,N) WX_DEFINE_ARRAY_PTR(T,N)
#define TRUE true
#define FALSE false
#define _(x) wxString(x)

void no5PlatformLoadFont(const std::string& file);
bool no5PlatformValidateFont(const std::vector<unsigned char>&);
void no5PlatformInstallFont(std::vector<unsigned char>);
using wxIntegerHash=std::hash<int>;using wxIntegerEqual=std::equal_to<int>;
using wxArrayString=No5Array<wxString>;
class wxGenericProgressDialog;
class wxFFileInputStream {
 FILE* file=nullptr;bool ok=false;
public:
 explicit wxFFileInputStream(wxString path){file=fopen(path.c_str(),"rb");ok=file;}
 ~wxFFileInputStream(){if(file)fclose(file);}
 wxFFileInputStream& Read(void* p,size_t n){ok=file&&fread(p,1,n,file)==n;return *this;}
 bool IsOk()const{return ok;}
};
class wxFFileOutputStream {
 FILE* file=nullptr;bool ok=false;
public:
 explicit wxFFileOutputStream(wxString path){file=fopen(path.c_str(),"wb");ok=file;}
 ~wxFFileOutputStream(){if(file)fclose(file);}
 wxFFileOutputStream& Write(const void* p,size_t n){ok=file&&fwrite(p,1,n,file)==n;return *this;}
 bool Close(){if(!file)return false;bool result=fclose(file)==0;file=nullptr;ok=ok&&result;return ok;}
 bool IsOk()const{return ok;}
};

using GLdouble=double;

#define wxASSERT(x) assert(x)
