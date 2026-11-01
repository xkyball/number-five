#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <unordered_set>

namespace no5 {
// Android CLOCK_BOOTTIME shares SystemClock.elapsedRealtime()'s suspend-aware
// epoch. Hosts use their monotonic clock; host callers must use that same epoch.
int64_t benchmarkElapsedRealtimeMs();

class BenchmarkRunCancelled final : public std::runtime_error {
public:
 explicit BenchmarkRunCancelled(const std::string& message):std::runtime_error(message){}
};

struct BenchmarkRunState;
class BenchmarkRunSnapshot {
 std::shared_ptr<BenchmarkRunState> state;
 explicit BenchmarkRunSnapshot(std::shared_ptr<BenchmarkRunState> value):state(std::move(value)){}
 friend class BenchmarkRunControl;
public:
 BenchmarkRunSnapshot()=default;
 bool attached()const noexcept{return bool(state);}
 // Empty snapshots leave ordinary rendering/recovery unchanged. A snapshot
 // retains its own generation even when End permits a subsequent Restore/run.
 void checkpoint()const;
};

class BenchmarkRunControl {
 mutable std::mutex controlMutex;
 std::shared_ptr<BenchmarkRunState> active;
 std::unordered_set<std::string> usedRunIds;
 std::function<int64_t()> clock;
 bool closed=false;
public:
 explicit BenchmarkRunControl(std::function<int64_t()> now=benchmarkElapsedRealtimeMs):clock(std::move(now)){}
 bool begin(const std::string& runId,int64_t deadlineElapsedRealtimeMs);
 bool cancel(const std::string& expectedRunId,const std::string& reason);
 bool end(const std::string& expectedRunId);
 bool owns(const std::string& expectedRunId)const;
 bool idle()const;
 BenchmarkRunSnapshot snapshot()const;
 // Atomic identity-bound acquisition for separately signalled render authority.
 // Unlike the ordinary empty snapshot, missing/stale ownership is an error.
 BenchmarkRunSnapshot snapshotOwned(const std::string& expectedRunId)const;
 void close();
};

// Contains control objects only, never Engine/GL pointers. Its short lookup
// lock and each run's control lock are independent of the renderer/JNI lock.
// Removal revokes extracted shared references before renderer teardown waits.
class BenchmarkRunRegistry {
 std::mutex registryMutex;
 std::unordered_map<int64_t,std::shared_ptr<BenchmarkRunControl>> controls;
public:
 void add(int64_t engineId,const std::shared_ptr<BenchmarkRunControl>& control);
 std::shared_ptr<BenchmarkRunControl> find(int64_t engineId);
 bool remove(int64_t engineId);
};
}
