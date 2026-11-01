#include "preparation_metrics.hpp"
#include "benchmark_run.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <time.h>
#include <sys/resource.h>
#ifdef __APPLE__
#include <mach/mach.h>
#include <pthread.h>
#elif defined(__linux__)
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace no5 {
namespace {
void number(std::ostream& out,double value){if(value>=0&&std::isfinite(value))out<<std::setprecision(17)<<value;else out<<"null";}
void memory(std::ostream& out,int64_t value){if(value>=0)out<<value;else out<<"null";}
bool ioDelta(uint64_t before,uint64_t after,uint64_t& value){if(after<before)return false;value=after-before;return true;}
std::string resourcesJson(const PreparationResources& before,const PreparationResources& after){
 std::ostringstream out;out<<"{\"ram\":{\"scope\":\"whole_process_boundary_and_lifetime_highwater\",\"rssStartBytes\":";memory(out,before.rssBytes);
 out<<",\"rssEndBytes\":";memory(out,after.rssBytes);out<<",\"observedBoundaryMaxRssBytes\":";memory(out,std::max(before.rssBytes,after.rssBytes));
 out<<",\"processLifetimePeakRssBytesStart\":";memory(out,before.processLifetimePeakRssBytes);out<<",\"processLifetimePeakRssBytesEnd\":";memory(out,after.processLifetimePeakRssBytes);
 out<<",\"phasePeakRssBytes\":null},\"processIo\":{\"scope\":\"whole_process_including_measurement_and_other_threads\",\"physicalDiskTimeMs\":null,\"ioWaitMs\":null,\"delta\":";
 const auto& a=before.processIo;const auto& b=after.processIo;uint64_t r=0,w=0,rc=0,wc=0,rb=0,wb=0;
 if(a.available&&b.available&&ioDelta(a.logicalReadChars,b.logicalReadChars,r)&&ioDelta(a.logicalWriteChars,b.logicalWriteChars,w)&&ioDelta(a.readCalls,b.readCalls,rc)&&ioDelta(a.writeCalls,b.writeCalls,wc)&&ioDelta(a.storageReadBytes,b.storageReadBytes,rb)&&ioDelta(a.storageWriteBytes,b.storageWriteBytes,wb))
  out<<"{\"logicalReadChars\":"<<r<<",\"logicalWriteChars\":"<<w<<",\"readCalls\":"<<rc<<",\"writeCalls\":"<<wc<<",\"storageReadBytes\":"<<rb<<",\"storageWriteBytes\":"<<wb<<'}';
 else out<<"null";
 out<<"}}";return out.str();
}
}
uint64_t preparationThreadId(){
#ifdef __APPLE__
 uint64_t tid=0;return pthread_threadid_np(nullptr,&tid)==0?tid:0;
#elif defined(__linux__)
 return uint64_t(syscall(SYS_gettid));
#else
 return 0;
#endif
}
double preparationThreadCpuMs(){timespec t{};return clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t)==0?t.tv_sec*1000.+t.tv_nsec/1e6:-1;}
double preparationProcessCpuMs(){timespec t{};return clock_gettime(CLOCK_PROCESS_CPUTIME_ID,&t)==0?t.tv_sec*1000.+t.tv_nsec/1e6:-1;}
double preparationCpuDelta(double start,double end){return start>=0&&end>=start?end-start:-1;}
std::string preparationJsonString(const std::string& value){
 std::ostringstream out;out<<'"';for(unsigned char c:value){switch(c){case '"':out<<"\\\"";break;case '\\':out<<"\\\\";break;case '\n':out<<"\\n";break;case '\r':out<<"\\r";break;case '\t':out<<"\\t";break;default:if(c<32)out<<"\\u00"<<std::hex<<std::setw(2)<<std::setfill('0')<<unsigned(c)<<std::dec;else out<<c;}}out<<'"';return out.str();
}
PreparationResources preparationResources(){
 PreparationResources result;rusage usage{};if(getrusage(RUSAGE_SELF,&usage)==0)result.processLifetimePeakRssBytes=int64_t(usage.ru_maxrss)
#ifndef __APPLE__
 *1024
#endif
 ;
#ifdef __APPLE__
 mach_task_basic_info_data_t info{};mach_msg_type_number_t count=MACH_TASK_BASIC_INFO_COUNT;
 if(task_info(mach_task_self(),MACH_TASK_BASIC_INFO,reinterpret_cast<task_info_t>(&info),&count)==KERN_SUCCESS)result.rssBytes=int64_t(info.resident_size);
#elif defined(__linux__)
 std::ifstream memoryInput("/proc/self/statm");uint64_t virtualPages=0,residentPages=0;const long page=sysconf(_SC_PAGESIZE);
 if(page>0&&memoryInput>>virtualPages>>residentPages)result.rssBytes=int64_t(residentPages)*page;
 std::ifstream ioInput("/proc/self/io");std::string key;uint64_t value=0;unsigned fields=0;
 while(ioInput>>key>>value){auto& io=result.processIo;
  if(key=="rchar:"){io.logicalReadChars=value;fields|=1;}else if(key=="wchar:"){io.logicalWriteChars=value;fields|=2;}
  else if(key=="syscr:"){io.readCalls=value;fields|=4;}else if(key=="syscw:"){io.writeCalls=value;fields|=8;}
  else if(key=="read_bytes:"){io.storageReadBytes=value;fields|=16;}else if(key=="write_bytes:"){io.storageWriteBytes=value;fields|=32;}
 }result.processIo.available=fields==63;
#endif
 return result;
}
PreparationScope::PreparationScope(PreparationScopeMetrics* target):value(target){if(!value)return;value->before=preparationResources();value->startElapsedRealtimeMs=benchmarkElapsedRealtimeMs();wall=std::chrono::steady_clock::now();caller=preparationThreadCpuMs();process=preparationProcessCpuMs();}
void PreparationScope::finish(){if(!value)return;value->processCpuMs=preparationCpuDelta(process,preparationProcessCpuMs());value->callerCpuMs=preparationCpuDelta(caller,preparationThreadCpuMs());value->wallMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-wall).count();value->endElapsedRealtimeMs=benchmarkElapsedRealtimeMs();value->after=preparationResources();value=nullptr;}
std::string PreparationScopeMetrics::json()const{
 std::ostringstream out;out<<"{\"wallMs\":";number(out,wallMs);out<<",\"callerCpuMs\":";number(out,callerCpuMs);out<<",\"processCpuMs\":";number(out,processCpuMs);
 out<<",\"cpuScope\":\"caller_thread_and_all_process_threads_separate\",\"startElapsedRealtimeMs\":"<<startElapsedRealtimeMs<<",\"endElapsedRealtimeMs\":"<<endElapsedRealtimeMs;
 out<<",\"timestampClock\":\"";
#ifdef __ANDROID__
 out<<"CLOCK_BOOTTIME_SystemClock.elapsedRealtime";
#else
 out<<"steady_clock_host_epoch";
#endif
 out<<"\",\"wallClock\":\"steady_clock\",\"includesMeasurementOverhead\":true,\"schedulerWaitMs\":null,\"resources\":"<<resourcesJson(before,after)<<'}';return out.str();
}
std::string PreparationIdentityMetrics::json()const{
 std::ostringstream out;out<<"{\"phase\":"<<preparationJsonString(phase)<<",\"status\":"<<preparationJsonString(status)<<",\"error\":"<<preparationJsonString(error);
 out<<",\"requested\":"<<requested<<",\"admittedCap\":"<<admittedCap<<",\"capReason\":"<<preparationJsonString(capReason)<<",\"actualStartedThreads\":"<<actualStartedThreads<<",\"callerTid\":"<<callerTid;
 out<<",\"actualCallerTIDs\":[";if(callerTid)out<<callerTid;out<<"],\"actualWorkerTIDs\":[";bool first=true;for(const auto& worker:workers){if(!worker.tid||admittedCap<=1)continue;if(!first)out<<',';first=false;out<<worker.tid;}
 out<<"],\"actualExecutingTIDs\":[";first=true;for(const auto& worker:workers){if(!worker.tid)continue;if(!first)out<<',';first=false;out<<worker.tid;}
 out<<"],\"workerExecution\":\""<<(admittedCap<=1?"caller_inline":"new_threads")<<"\",\"sourceCount\":"<<sourceCount<<",\"eligibleHashUnits\":"<<eligibleHashUnits<<",\"claimedUnits\":"<<claimedUnits<<",\"activeJobs\":"<<activeJobs<<",\"observedConcurrentJobs\":"<<observedConcurrentJobs;
 out<<",\"observedConcurrentBusyThreads\":null,\"effectiveWorkUnits\":"<<completedUnits<<",\"completedUnits\":"<<completedUnits<<",\"failedUnits\":"<<failedUnits<<",\"skippedUnits\":"<<skippedUnits;
 out<<",\"workerThreadCpuSumMs\":";number(out,workerThreadCpuSumMs);out<<",\"ownedPhaseAllThreadCpuMs\":";number(out,ownedPhaseAllThreadCpuMs);
 out<<",\"ownedCpuScope\":\"worker_function_scopes_plus_caller_phase_deduplicated_inline\",\"callerJoinWallMs\":";number(out,callerJoinWallMs);out<<",\"callerJoinCpuMs\":";number(out,callerJoinCpuMs);
 out<<",\"joinScope\":\"sequential_caller_join_scope_not_pure_wait\",\"scope\":"<<scope.json()<<",\"workers\":[";first=true;
 for(const auto& worker:workers){if(!worker.tid)continue;if(!first)out<<',';first=false;const auto& io=worker.hashIo;
  out<<"{\"tid\":"<<worker.tid<<",\"wallMs\":";number(out,worker.wallMs);out<<",\"cpuMs\":";number(out,worker.cpuMs);
  out<<",\"claimedUnits\":"<<worker.claimedUnits<<",\"completedUnits\":"<<worker.completedUnits<<",\"failedUnits\":"<<worker.failedUnits<<",\"skippedUnits\":"<<worker.skippedUnits;
  out<<",\"hashIo\":{\"scope\":\"this_worker_logical_ifstream_reads_only\",\"logicalReadBytes\":"<<io.logicalReadBytes<<",\"sourceReadBytes\":"<<io.sourceReadBytes<<",\"registryReadBytes\":"<<io.registryReadBytes<<",\"fileOpenAttempts\":"<<io.fileOpenAttempts<<",\"readCalls\":"<<io.readCalls;
  out<<",\"fileOpenWallMs\":";number(out,io.fileOpenWallMs);out<<",\"readWallMs\":";number(out,io.readWallMs);out<<",\"readCpuMs\":";number(out,io.cpuAvailable?io.readCpuMs:-1);out<<",\"ioWaitMs\":null}}";
 }out<<"]}";return out.str();
}
std::string PreparationOpenMetrics::json()const{
 std::ostringstream out;out<<"{\"openSequence\":"<<openSequence<<",\"sourceCount\":"<<sourceCount<<",\"requestedWorkers\":"<<requestedWorkers<<",\"geometryGeneration\":"<<geometryGeneration<<",\"sourceGeneration\":"<<preparationJsonString(sourceGeneration)<<",\"converter\":"<<preparationJsonString(converter)<<",\"status\":"<<preparationJsonString(status)<<",\"error\":"<<preparationJsonString(error)<<",\"nativeOpen\":"<<nativeOpen.json();
 uint64_t hits=0,sourceBytes=0,cacheBytes=0;for(const auto& cell:cells){hits+=cell.reused;sourceBytes+=cell.sourceBytes;cacheBytes+=cell.cacheBytes;}
 out<<",\"cache\":{\"requestedReuse\":"<<(requestedReuse?"true":"false")<<",\"observedHitCells\":"<<hits<<",\"observedRebuiltCells\":"<<cells.size()-hits<<",\"observedCells\":"<<cells.size()<<",\"sourceBytes\":"<<sourceBytes<<",\"cacheBytes\":"<<cacheBytes<<",\"osPageCacheState\":\"unknown\",\"recipe\":\"existing_cache_no_namespace_or_eviction_change\"},\"phases\":[";
 bool first=true;for(const auto& phase:phases){if(!first)out<<',';first=false;out<<phase.json();}out<<"],\"firstNativeFrameComplete\":";
 if(!firstNativeFrameComplete)out<<"null";else{out<<"{\"endpoint\":\"native_endFrame_return\",\"renderer\":"<<preparationJsonString(firstFrameRenderer)<<",\"geometryGeneration\":"<<geometryGeneration<<",\"elapsedRealtimeMs\":"<<firstFrameElapsedRealtimeMs<<",\"openToFirstNativeFrameCompleteWallMs\":";number(out,openToFirstNativeFrameCompleteWallMs);out<<",\"width\":"<<firstFrameWidth<<",\"height\":"<<firstFrameHeight<<",\"presentationPending\":false,\"visibleDisplayProven\":false,\"gpuExecutionMs\":null}";}
 out<<'}';return out.str();
}
}
