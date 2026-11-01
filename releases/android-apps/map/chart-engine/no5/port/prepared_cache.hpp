#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include "../benchmark_run.hpp"
#include "../preparation_metrics.hpp"
namespace no5 {
class PreparedDirectoryLock {
 std::intptr_t descriptor=-1;
public:
 explicit PreparedDirectoryLock(const std::string& directory);
 ~PreparedDirectoryLock();
 PreparedDirectoryLock(const PreparedDirectoryLock&)=delete;
 PreparedDirectoryLock& operator=(const PreparedDirectoryLock&)=delete;
};
struct PreparedIdentity {
 std::string source,key;
 uint64_t sourceBytes=0;
 double keyMs=0;
};
struct PreparationStats {
 bool reused=false;
 std::string key,path;
 uint64_t sourceBytes=0,cacheBytes=0,geometryAdmissionBytes=0;
 double keyMs=0,validationMs=0,conversionMs=0,ingestMs=0,totalMs=0;
};
struct PreparedCache {
 static constexpr uint64_t BudgetBytes=256ULL*1024*1024;
 static uint64_t prune(const std::string& directory,const std::string& retained,uint64_t budget=BudgetBytes);
 static std::vector<PreparedIdentity> identities(const std::vector<std::string>& sources,const std::string& registry,unsigned workers,const BenchmarkRunSnapshot& run={},PreparationIdentityMetrics* metrics=nullptr);
 static std::string sourceKey(const std::string& source,const std::string& registry,PreparationStats* = nullptr,const BenchmarkRunSnapshot& run={},PreparationHashIo* io=nullptr);
 static std::string fileHash(const std::string& path,uint64_t* bytes=nullptr,const BenchmarkRunSnapshot& run={},PreparationHashIo* io=nullptr);
 static bool matches(const std::string& path,const std::string& key);
 static void publishIdentity(const std::string& path,const std::string& key);
 static std::string temporaryPath(const std::string& path);
};
}
