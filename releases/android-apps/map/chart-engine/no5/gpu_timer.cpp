#include "gpu_timer.hpp"
#include <EGL/egl.h>
#include <chrono>
#include <limits>
#include <sstream>
#include <time.h>
namespace no5 {
namespace {
using Clock = std::chrono::steady_clock;
double cpuMs() { timespec t{}; return clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t) == 0 ? t.tv_sec * 1000.0 + t.tv_nsec / 1e6 : -1; }
struct Probe {
 Clock::time_point wall = Clock::now(); double cpu = cpuMs();
 double& wallTotal; double& cpuTotal; bool& available;
 ~Probe() { auto end=cpuMs(); wallTotal += std::chrono::duration<double,std::milli>(Clock::now()-wall).count();
  if(cpu>=0 && end>=cpu) cpuTotal+=end-cpu; else available=false; }
};
}
GpuTimerApi GpuTimerApi::load() {
 GpuTimerApi a;
 a.gen=reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));
 a.remove=reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(eglGetProcAddress("glDeleteQueriesEXT"));
 a.begin=reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));
 a.end=reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
 a.bits=reinterpret_cast<PFNGLGETQUERYIVEXTPROC>(eglGetProcAddress("glGetQueryivEXT"));
 a.available=reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(eglGetProcAddress("glGetQueryObjectuivEXT"));
 a.result=reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
 a.integer=glGetIntegerv; a.error=glGetError; return a;
}
void AsyncGpuTimer::configure(const std::string& extensions,GpuTimerApi functions) {
 release(); api=functions; std::istringstream text(extensions); std::string token;
 while(text>>token) if(token=="GL_EXT_disjoint_timer_query") advertised=true;
 reason=advertised ? "inactive; not checked" : "GL_EXT_disjoint_timer_query unavailable";
}
bool AsyncGpuTimer::boundary() {
 errorQueries++; auto error=api.error(); if(error==GL_NO_ERROR) return true;
 if(incomingError==GL_NO_ERROR) incomingError=error;
 supported=false; enabled=false; discard(); reason="pre-existing GL error; sampling stopped"; return false;
}
bool AsyncGpuTimer::callSucceeded() {
 errorQueries++; if(api.error()==GL_NO_ERROR) return true;
 driverFailures++; supported=false; enabled=false; discard(); reason="GPU timer API failure; sampling stopped"; return false;
}
bool AsyncGpuTimer::initialize() {
 if(initialized) return supported; initialized=true;
 if(!advertised) { reason="GL_EXT_disjoint_timer_query unavailable"; return false; }
 if(!api.complete()) { reason="GPU timer entry points unavailable"; return false; }
 if(!boundary()) return false;
 capabilityQueries++;api.bits(GL_TIME_ELAPSED_EXT,GL_QUERY_COUNTER_BITS_EXT,&counterBits);
 if(!callSucceeded()) return false;
 if(counterBits<30 || counterBits>64) { reason="GPU elapsed counter width unavailable"; return false; }
 GLuint ids[8]={}; allocationCalls++;api.gen(8,ids);
 for(std::size_t i=0;i<slots.size();i++) slots[i].id=ids[i];
 if(!callSucceeded()) return false;
 for(auto id:ids) if(!id) { reason="GPU query allocation unavailable"; return false; }
 supported=true; reason="asynchronous GL command interval; not physical display time"; return true;
}
void AsyncGpuTimer::discard() { for(auto& slot:slots) slot.pending=false; completedCount=0; }
void AsyncGpuTimer::sampling(bool value) {
 if(enabled==value) return;
 if(active>=0) end();
 enabled=value; discard();
}
std::size_t AsyncGpuTimer::pendingCount() const { std::size_t n=0; for(auto& s:slots) n+=s.pending; return n; }
void AsyncGpuTimer::begin(std::uint64_t frame,std::uint64_t nowNanos) {
 if(!enabled) return;
 Probe probe{Clock::now(),cpuMs(),issueWallMs,issueCpuMs,cpuAvailable};
 if(active>=0) { skipped++; return; }
 if(!initialize() || !boundary()) return;
 int free=-1; for(std::size_t i=0;i<slots.size();i++) if(!slots[i].pending) { free=int(i); break; }
 if(free<0) { skipped++; return; }
 auto& s=slots[free]; api.begin(GL_TIME_ELAPSED_EXT,s.id);
 if(callSucceeded()) { s.frame=frame; s.issuedNanos=nowNanos; active=free; }
}
void AsyncGpuTimer::end() {
 if(active<0) return;
 Probe probe{Clock::now(),cpuMs(),issueWallMs,issueCpuMs,cpuAvailable};
 int slot=active; active=-1;
 // Always close an active query, including after a pre-existing chart error.
 bool clean=boundary(); api.end(GL_TIME_ELAPSED_EXT); bool ended=callSucceeded();
 if(clean && ended) slots[slot].pending=true;
}
void AsyncGpuTimer::abandon() {
 const int slot=active;
 end();
 // Close the GL query but never attribute a partial render interval to the
 // next completed frame, which deliberately keeps the same frame number.
 if(slot>=0)slots[slot].pending=false;
}
void AsyncGpuTimer::poll(std::uint64_t nowNanos) {
 if(!enabled || !supported || !pendingCount()) return;
 Probe probe{Clock::now(),cpuMs(),pollWallMs,pollCpuMs,cpuAvailable};
 if(!boundary()) return;
 GLint disjoint=0; api.integer(GL_GPU_DISJOINT_EXT,&disjoint); disjointQueries++;
 if(!callSucceeded()) return;
 if(disjoint) { disjointDiscarded+=pendingCount()+completedCount; discard(); return; }
 std::array<GpuElapsedSample,8> candidates{}; std::size_t count=0;
 for(auto& s:slots) if(s.pending) {
  GLuint ready=0; api.available(s.id,GL_QUERY_RESULT_AVAILABLE_EXT,&ready); availabilityQueries++;
  if(!callSucceeded()) return;
  if(!ready) continue; // GL_QUERY_RESULT is never read before availability.
  GLuint64 value=0; api.result(s.id,GL_QUERY_RESULT_EXT,&value); resultQueries++;
  if(!callSucceeded()) return;
  s.pending=false;
  auto maximum=counterBits==64 ? std::numeric_limits<std::uint64_t>::max() : (std::uint64_t(1)<<counterBits)-1;
  if(value>=maximum || nowNanos<s.issuedNanos || nowNanos-s.issuedNanos>=maximum) overflowDiscarded++;
  else candidates[count++]={s.frame,std::uint64_t(value)};
 }
 api.integer(GL_GPU_DISJOINT_EXT,&disjoint); disjointQueries++;
 if(!callSucceeded()) return;
 if(disjoint) { disjointDiscarded+=pendingCount()+completedCount+count; discard(); return; }
 for(std::size_t i=0;i<count;i++) {
  if(completedCount<completed.size()) completed[completedCount++]=candidates[i]; else sampleCapacityDiscarded++;
 }
}
void AsyncGpuTimer::release() {
 if(active>=0 && api.end) api.end(GL_TIME_ELAPSED_EXT);
 if(api.remove) { GLuint ids[8]={}; GLsizei n=0; for(auto& s:slots) if(s.id) ids[n++]=s.id; if(n) api.remove(n,ids); }
 forget();
}
void AsyncGpuTimer::forget() {
 slots={}; completed={}; completedCount=0; advertised=enabled=initialized=supported=false; active=-1; counterBits=0;
 incomingError=GL_NO_ERROR; resetFrameStats();
 skipped=disjointDiscarded=overflowDiscarded=driverFailures=sampleCapacityDiscarded=0; reason="inactive; not checked";
}
}
