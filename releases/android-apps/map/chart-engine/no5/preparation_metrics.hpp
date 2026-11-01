#pragma once
#include <chrono>
#include <cstdint>
#include <string>
#include <vector>

namespace no5 {
uint64_t preparationThreadId();
double preparationThreadCpuMs();
double preparationProcessCpuMs();
double preparationCpuDelta(double start,double end);
std::string preparationJsonString(const std::string&);
struct PreparationIoCounters {
 bool available=false;
 uint64_t logicalReadChars=0,logicalWriteChars=0,readCalls=0,writeCalls=0,storageReadBytes=0,storageWriteBytes=0;
};
struct PreparationResources {
 int64_t rssBytes=-1,processLifetimePeakRssBytes=-1;
 PreparationIoCounters processIo;
};
PreparationResources preparationResources();
struct PreparationHashIo {
 uint64_t logicalReadBytes=0,sourceReadBytes=0,registryReadBytes=0,fileOpenAttempts=0,readCalls=0;
 double fileOpenWallMs=0,readWallMs=0,readCpuMs=0;
 bool cpuAvailable=true,readingRegistry=false;
};
struct PreparationWorkerMetrics {
 uint64_t tid=0,claimedUnits=0,completedUnits=0,failedUnits=0,skippedUnits=0;
 double wallMs=0,cpuMs=-1;
 PreparationHashIo hashIo;
};
struct PreparationScopeMetrics {
 int64_t startElapsedRealtimeMs=0,endElapsedRealtimeMs=0;
 double wallMs=0,callerCpuMs=-1,processCpuMs=-1;
 PreparationResources before,after;
 std::string json()const;
};
class PreparationScope {
 PreparationScopeMetrics* value=nullptr;
 std::chrono::steady_clock::time_point wall;
 double caller=-1,process=-1;
public:
 explicit PreparationScope(PreparationScopeMetrics*);
 void finish();
 ~PreparationScope(){finish();}
};
struct PreparationIdentityMetrics {
 std::string phase,status="pending",error;
 unsigned requested=0,admittedCap=0,actualStartedThreads=0;
 std::string capReason;
 uint64_t callerTid=0,sourceCount=0,eligibleHashUnits=0,claimedUnits=0,activeJobs=0,observedConcurrentJobs=0,completedUnits=0,failedUnits=0,skippedUnits=0;
 double callerJoinWallMs=0,callerJoinCpuMs=0,workerThreadCpuSumMs=-1,ownedPhaseAllThreadCpuMs=-1;
 PreparationScopeMetrics scope;
 std::vector<PreparationWorkerMetrics> workers;
 std::string json()const;
};
struct PreparationCellMetrics {bool reused=false;uint64_t sourceBytes=0,cacheBytes=0;};
struct PreparationOpenMetrics {
 uint64_t openSequence=0,geometryGeneration=0,sourceCount=0;
 unsigned requestedWorkers=0;
 std::string sourceGeneration,converter,status="opening",error;
 bool requestedReuse=true;
 PreparationScopeMetrics nativeOpen;
 std::vector<PreparationIdentityMetrics> phases;
 std::vector<PreparationCellMetrics> cells;
 std::chrono::steady_clock::time_point started;
 bool firstNativeFrameComplete=false;
 int64_t firstFrameElapsedRealtimeMs=0;
 double openToFirstNativeFrameCompleteWallMs=0;
 int firstFrameWidth=0,firstFrameHeight=0;
 std::string firstFrameRenderer;
 std::string json()const;
};
}
