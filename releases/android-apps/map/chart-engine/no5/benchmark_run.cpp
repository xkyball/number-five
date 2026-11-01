#include "benchmark_run.hpp"
#include <chrono>
#include <time.h>

namespace no5 {
int64_t benchmarkElapsedRealtimeMs(){
#ifdef __ANDROID__
 timespec now{};
 if(clock_gettime(CLOCK_BOOTTIME,&now)!=0)throw std::runtime_error("Benchmark CLOCK_BOOTTIME unavailable");
 return int64_t(now.tv_sec)*1000+now.tv_nsec/1000000;
#else
 return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
#endif
}
struct BenchmarkRunState {
 const std::string runId;
 const int64_t deadline;
 const std::function<int64_t()> clock;
 std::atomic<bool> stopped{false};
 std::mutex reasonMutex;
 std::string reason;
 BenchmarkRunState(std::string id,int64_t until,std::function<int64_t()> now):runId(std::move(id)),deadline(until),clock(std::move(now)){}
 void stop(const std::string& why){
  std::lock_guard<std::mutex> lock(reasonMutex);
  if(stopped.load(std::memory_order_relaxed))return;
  reason=why;stopped.store(true,std::memory_order_release);
 }
};
void BenchmarkRunSnapshot::checkpoint()const{
 if(!state)return;
 if(!state->stopped.load(std::memory_order_acquire)&&state->clock()>=state->deadline)state->stop("deadline exceeded");
 if(state->stopped.load(std::memory_order_acquire)){
  // The release flag publishes the immutable, first-wins reason.
  throw BenchmarkRunCancelled("Benchmark run "+state->runId+" cancelled: "+state->reason);
 }
}
bool BenchmarkRunControl::begin(const std::string& id,int64_t deadline){
 std::lock_guard<std::mutex> lock(controlMutex);
 if(closed||id.empty()||deadline<=0)return false;
 if(active)return active->runId==id&&active->deadline==deadline;
 if(usedRunIds.count(id)||deadline<=clock())return false;
 auto next=std::make_shared<BenchmarkRunState>(id,deadline,clock);
 usedRunIds.insert(id);active=std::move(next);return true;
}
bool BenchmarkRunControl::cancel(const std::string& id,const std::string& reason){
 std::lock_guard<std::mutex> lock(controlMutex);
 if(closed||!active||active->runId!=id)return false;
 active->stop(reason.empty()?"cancel requested":reason);return true;
}
bool BenchmarkRunControl::end(const std::string& id){
 std::lock_guard<std::mutex> lock(controlMutex);
 if(closed||!active||active->runId!=id)return false;
 active->stop("run ended");active.reset();return true;
}
BenchmarkRunSnapshot BenchmarkRunControl::snapshot()const{
 std::lock_guard<std::mutex> lock(controlMutex);return BenchmarkRunSnapshot(active);
}
BenchmarkRunSnapshot BenchmarkRunControl::snapshotOwned(const std::string& id)const{
 std::lock_guard<std::mutex> lock(controlMutex);
 if(closed||!active||active->runId!=id)throw BenchmarkRunCancelled("Benchmark run ownership changed");
 return BenchmarkRunSnapshot(active);
}
bool BenchmarkRunControl::owns(const std::string& id)const{std::lock_guard<std::mutex> lock(controlMutex);return !closed&&active&&active->runId==id;}
bool BenchmarkRunControl::idle()const{std::lock_guard<std::mutex> lock(controlMutex);return !active;}
void BenchmarkRunControl::close(){
 std::lock_guard<std::mutex> lock(controlMutex);closed=true;
 if(active)active->stop("engine closed");active.reset();
}
void BenchmarkRunRegistry::add(int64_t id,const std::shared_ptr<BenchmarkRunControl>& control){
 if(id<=0||!control)throw std::invalid_argument("Invalid benchmark engine control");
 std::lock_guard<std::mutex> lock(registryMutex);
 if(!controls.emplace(id,control).second)throw std::invalid_argument("Benchmark engine control already registered");
}
std::shared_ptr<BenchmarkRunControl> BenchmarkRunRegistry::find(int64_t id){
 std::lock_guard<std::mutex> lock(registryMutex);const auto found=controls.find(id);
 return found==controls.end()?nullptr:found->second;
}
bool BenchmarkRunRegistry::remove(int64_t id){
 std::lock_guard<std::mutex> lock(registryMutex);const auto found=controls.find(id);
 if(found==controls.end())return false;
 found->second->close();controls.erase(found);return true;
}
}
