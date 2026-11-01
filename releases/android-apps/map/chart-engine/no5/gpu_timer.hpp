#pragma once
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <array>
#include <cstdint>
#include <string>

namespace no5 {
struct GpuTimerApi {
 PFNGLGENQUERIESEXTPROC gen=nullptr;PFNGLDELETEQUERIESEXTPROC remove=nullptr;
 PFNGLBEGINQUERYEXTPROC begin=nullptr;PFNGLENDQUERYEXTPROC end=nullptr;
 PFNGLGETQUERYIVEXTPROC bits=nullptr;PFNGLGETQUERYOBJECTUIVEXTPROC available=nullptr;
 PFNGLGETQUERYOBJECTUI64VEXTPROC result=nullptr;
 void (*integer)(GLenum,GLint*)=nullptr;GLenum (*error)()=nullptr;
 bool complete()const{return gen&&remove&&begin&&end&&bits&&available&&result&&integer&&error;}
 static GpuTimerApi load();
};
struct GpuElapsedSample {std::uint64_t frame=0,nanoseconds=0;};
class AsyncGpuTimer {
 struct Slot {GLuint id=0;std::uint64_t frame=0,issuedNanos=0;bool pending=false;};
 GpuTimerApi api{};std::array<Slot,8> slots{};
 std::array<GpuElapsedSample,16> completed{};std::size_t completedCount=0;
 bool advertised=false,enabled=false,initialized=false,supported=false;int active=-1,counterBits=0;
 std::string reason="inactive; not checked";
 GLenum incomingError=GL_NO_ERROR;
 bool initialize();bool boundary();bool callSucceeded();
 void discard();
public:
 std::uint64_t availabilityQueries=0,resultQueries=0,disjointQueries=0,errorQueries=0,capabilityQueries=0,allocationCalls=0;
 std::uint64_t skipped=0,disjointDiscarded=0,overflowDiscarded=0,driverFailures=0,sampleCapacityDiscarded=0;
 double pollWallMs=0,issueWallMs=0,pollCpuMs=0,issueCpuMs=0;bool cpuAvailable=true;
 void configure(const std::string& extensions,GpuTimerApi functions=GpuTimerApi::load());
 void sampling(bool value);
 void resetFrameStats(){completedCount=0;availabilityQueries=resultQueries=disjointQueries=errorQueries=capabilityQueries=allocationCalls=0;pollWallMs=issueWallMs=pollCpuMs=issueCpuMs=0;cpuAvailable=true;}
 void begin(std::uint64_t frame,std::uint64_t nowNanos);
 void end();void abandon();void poll(std::uint64_t nowNanos);void release();void forget();
 GLenum takeIncomingError(){auto value=incomingError;incomingError=GL_NO_ERROR;return value;}
 const auto& samples()const{return completed;}
 std::size_t sampleCount()const{return completedCount;}
 std::size_t pendingCount()const;
 bool samplingEnabled()const{return enabled;}
 bool isSupported()const{return supported;}bool isAdvertised()const{return advertised;}
 int bits()const{return counterBits;}const std::string& status()const{return reason;}
};
}
