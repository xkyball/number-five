#include "prepared_cache.hpp"
#include "sha256.hpp"
#include "prepared_converter.hpp"
#include <atomic>
#include <cstdio>
#include <chrono>
#include <exception>
#include <thread>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <cerrno>
#ifdef _WIN32
#include <process.h>
#include <windows.h>
#else
#include <unistd.h>
#include <fcntl.h>
#include <sys/file.h>
#endif
namespace no5 {
namespace {
void field(Sha256& key,const std::string& value){key.update(std::to_string(value.size())+":");key.update(value);}
bool ownedName(const std::string& name){return name.size()>=69&&name.substr(64,5)==".senc"&&std::all_of(name.begin(),name.begin()+64,[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});}
}
PreparedDirectoryLock::PreparedDirectoryLock(const std::string& directory){
 std::filesystem::create_directories(directory);const auto path=(std::filesystem::path(directory)/".lock").string();
#ifdef _WIN32
 const auto handle=CreateFileA(path.c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_ALWAYS,FILE_ATTRIBUTE_NORMAL,nullptr);
 if(handle==INVALID_HANDLE_VALUE)throw std::runtime_error("Prepared cache lock open failed");OVERLAPPED overlap{};
 if(!LockFileEx(handle,LOCKFILE_EXCLUSIVE_LOCK,0,MAXDWORD,MAXDWORD,&overlap)){CloseHandle(handle);throw std::runtime_error("Prepared cache lock failed");}
 descriptor=reinterpret_cast<std::intptr_t>(handle);
#else
 const auto fd=open(path.c_str(),O_CREAT|O_RDWR|O_CLOEXEC,0600);if(fd<0)throw std::runtime_error("Prepared cache lock open failed");
 int result;do{result=flock(fd,LOCK_EX);}while(result<0&&errno==EINTR);
 if(result<0){close(fd);throw std::runtime_error("Prepared cache lock failed");}descriptor=fd;
#endif
}
PreparedDirectoryLock::~PreparedDirectoryLock(){if(descriptor==-1)return;
#ifdef _WIN32
 const auto handle=reinterpret_cast<HANDLE>(descriptor);OVERLAPPED overlap{};UnlockFileEx(handle,0,MAXDWORD,MAXDWORD,&overlap);CloseHandle(handle);
#else
 flock(int(descriptor),LOCK_UN);close(int(descriptor));
#endif
}
uint64_t PreparedCache::prune(const std::string& directory,const std::string& retained,uint64_t budget){
 struct File{std::filesystem::path path;uint64_t bytes;std::filesystem::file_time_type lastUse;};std::vector<File> files;uint64_t total=0;
 for(const auto& entry:std::filesystem::directory_iterator(directory)){
  const auto name=entry.path().filename().string();if(!entry.is_regular_file()||!ownedName(name))continue;
  if(name.size()>69){
   if(name.compare(69,5,".tmp-")==0||name.compare(69,14,".identity.tmp-")==0)std::filesystem::remove(entry.path());
   else if(name.substr(69)==".identity"&&!std::filesystem::exists(entry.path().string().substr(0,entry.path().string().size()-9)))std::filesystem::remove(entry.path());
   continue;
  }
  const auto identity=entry.path().string()+".identity";const auto exists=std::filesystem::exists(identity);
  const auto bytes=entry.file_size()+(exists?std::filesystem::file_size(identity):0);total+=bytes;
  files.push_back({entry.path(),bytes,exists?std::filesystem::last_write_time(identity):entry.last_write_time()});
 }
 std::sort(files.begin(),files.end(),[](const auto& a,const auto& b){return a.lastUse<b.lastUse;});
 for(const auto& file:files){if(total<=budget)break;if(file.path==std::filesystem::path(retained))continue;
  std::filesystem::remove(file.path);std::filesystem::remove(file.path.string()+".identity");total-=file.bytes;
 }
 if(total>budget){
  // An oversized current generation must not leave the failed cache over quota.
  if(ownedName(std::filesystem::path(retained).filename().string())){
   std::filesystem::remove(retained);std::filesystem::remove(retained+".identity");
  }
  throw std::runtime_error("Prepared geometry exceeds cache budget");
 }
 return total;
}
std::vector<PreparedIdentity> PreparedCache::identities(const std::vector<std::string>& sources,const std::string& registry,unsigned workers,const BenchmarkRunSnapshot& run,PreparationIdentityMetrics* metrics){
 const auto count=std::min(size_t(workers),sources.size());
 if(metrics){auto name=std::move(metrics->phase);*metrics=PreparationIdentityMetrics{};metrics->phase=std::move(name);}
 PreparationScope scope(metrics?&metrics->scope:nullptr);
 std::atomic<uint64_t> active{0},maximum{0};
 if(metrics){metrics->requested=workers;metrics->admittedCap=unsigned(count);metrics->sourceCount=sources.size();metrics->callerTid=preparationThreadId();metrics->status="running";metrics->capReason=sources.empty()?"no_sources":workers>sources.size()?"source_count":"requested_cap";metrics->workers.resize(std::max(size_t(1),count));}
 auto finish=[&](const std::string& status,const std::string& error){if(!metrics)return;scope.finish();metrics->status=status;metrics->error=error;metrics->activeJobs=active.load();metrics->observedConcurrentJobs=maximum.load();
  double sum=0;bool cpu=true;for(const auto& worker:metrics->workers){metrics->claimedUnits+=worker.claimedUnits;metrics->completedUnits+=worker.completedUnits;metrics->failedUnits+=worker.failedUnits;metrics->skippedUnits+=worker.skippedUnits;if(worker.tid){if(worker.cpuMs<0)cpu=false;else sum+=worker.cpuMs;}}
  metrics->workerThreadCpuSumMs=cpu?sum:-1;
  metrics->ownedPhaseAllThreadCpuMs=count<=1?metrics->scope.callerCpuMs:metrics->scope.callerCpuMs>=0&&cpu?metrics->scope.callerCpuMs+sum:-1;
 };
 try{
  run.checkpoint();if(!workers||workers>8)throw std::invalid_argument("Prepared worker count outside 1..8");
  if(metrics)for(const auto& source:sources)metrics->eligibleHashUnits+=std::filesystem::path(source).extension()==".000";
  std::vector<PreparedIdentity> result(sources.size());std::vector<std::exception_ptr> failures(sources.size());std::atomic<size_t> next{0};
  auto work=[&](size_t slot){auto* worker=metrics?&metrics->workers[slot]:nullptr;std::chrono::steady_clock::time_point started;double cpu=-1;
   if(worker){worker->tid=preparationThreadId();started=std::chrono::steady_clock::now();cpu=preparationThreadCpuMs();}
   for(size_t i;(i=next++)<sources.size();){bool activeJob=false;if(worker)worker->claimedUnits++;
    try{run.checkpoint();auto& identity=result[i];identity.source=sources[i];if(std::filesystem::path(identity.source).extension()!=".000"){if(worker)worker->skippedUnits++;continue;}
     if(worker){activeJob=true;const auto value=active.fetch_add(1)+1;auto seen=maximum.load();while(value>seen&&!maximum.compare_exchange_weak(seen,value)){};}
     const auto start=std::chrono::steady_clock::now();PreparationStats stats;identity.key=sourceKey(identity.source,registry,&stats,run,worker?&worker->hashIo:nullptr);identity.sourceBytes=stats.sourceBytes;
     identity.keyMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();if(worker)worker->completedUnits++;
    }catch(...){failures[i]=std::current_exception();if(worker)worker->failedUnits++;}
    if(activeJob)active.fetch_sub(1);
   }
   if(worker){worker->cpuMs=preparationCpuDelta(cpu,preparationThreadCpuMs());worker->wallMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();}
  };
  auto join=[&](std::vector<std::thread>& threads){const auto started=std::chrono::steady_clock::now();const double cpu=metrics?preparationThreadCpuMs():-1;for(auto& t:threads)t.join();if(metrics){metrics->callerJoinWallMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();const auto delta=preparationCpuDelta(cpu,preparationThreadCpuMs());metrics->callerJoinCpuMs=delta>=0?metrics->callerJoinCpuMs+delta:-1;}};
  if(count<=1)work(0);else{std::vector<std::thread> threads;try{for(size_t i=0;i<count;i++){threads.emplace_back(work,i);if(metrics)metrics->actualStartedThreads++;}}catch(...){join(threads);throw;}join(threads);}
  for(const auto& error:failures)if(error)std::rethrow_exception(error);
  run.checkpoint();finish("complete","");return result;
 }catch(const BenchmarkRunCancelled& e){finish("cancelled",e.what());throw;}catch(const std::exception& e){finish("failed",e.what());throw;}catch(...){finish("failed","non-standard exception");throw;}
}
std::string PreparedCache::fileHash(const std::string& path,uint64_t* bytes,const BenchmarkRunSnapshot& run,PreparationHashIo* io){
 run.checkpoint();
 const auto opened=io?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};if(io)io->fileOpenAttempts++;
 std::ifstream input(path,std::ios::binary);if(io)io->fileOpenWallMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-opened).count();if(!input)throw std::runtime_error("Prepared source file missing");
 Sha256 digest;std::array<char,65536> buffer{};uint64_t total=0;
 while(input){run.checkpoint();const auto read=io?std::chrono::steady_clock::now():std::chrono::steady_clock::time_point{};const double cpu=io?preparationThreadCpuMs():-1;input.read(buffer.data(),buffer.size());auto n=input.gcount();
  if(io){io->readWallMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-read).count();const auto delta=preparationCpuDelta(cpu,preparationThreadCpuMs());if(delta>=0)io->readCpuMs+=delta;else io->cpuAvailable=false;io->readCalls++;io->logicalReadBytes+=n;if(io->readingRegistry)io->registryReadBytes+=n;else io->sourceReadBytes+=n;}
  digest.update(buffer.data(),size_t(n));total+=n;}
 run.checkpoint();
 if(!input.eof())throw std::runtime_error("Prepared source read failed");
 if(bytes)*bytes=total;return digest.hex();
}
std::string PreparedCache::sourceKey(const std::string& source,const std::string& registry,PreparationStats* stats,const BenchmarkRunSnapshot& run,PreparationHashIo* io){
 if(io)io->readingRegistry=false;
 run.checkpoint();
 Sha256 key;field(key,"no5-public-osenc201-prepared-v1");field(key,No5PreparedConverter);
 const uint16_t endian=1;field(key,std::to_string(*reinterpret_cast<const uint8_t*>(&endian)));field(key,std::to_string(sizeof(float)));field(key,std::to_string(sizeof(double)));field(key,std::to_string(sizeof(int)));
 const auto base=std::filesystem::path(source);field(key,base.filename().string());
 uint64_t total=0;bool missing=false;
 for(unsigned i=0;i<=999;i++){
  run.checkpoint();
  auto path=base;if(i){char ext[5];std::snprintf(ext,sizeof ext,".%03u",i);path.replace_extension(ext);}
  if(!std::filesystem::exists(path)){if(!i)throw std::runtime_error("S57 base missing");missing=true;continue;}
  if(missing)throw std::runtime_error("S57 update gap");
  uint64_t bytes=0;field(key,path.extension().string());field(key,fileHash(path.string(),&bytes,run,io));total+=bytes;
 }
 if(io)io->readingRegistry=true;
 for(const auto* name:{"s57attributes.csv","s57objectclasses.csv"}){field(key,name);field(key,fileHash((std::filesystem::path(registry)/name).string(),nullptr,run,io));}
 if(io)io->readingRegistry=false;
 if(stats)stats->sourceBytes=total;return key.hex();
}
std::string PreparedCache::temporaryPath(const std::string& path){
 static std::atomic<uint64_t> serial{0};
#ifdef _WIN32
 const auto process=_getpid();
#else
 const auto process=getpid();
#endif
 return path+".tmp-"+std::to_string(process)+"-"+std::to_string(serial++);
}
bool PreparedCache::matches(const std::string& path,const std::string& key){
 try{
  std::ifstream input(path+".identity");std::string version,storedKey,contentHash,extra;
  if(!std::getline(input,version)||!std::getline(input,storedKey)||!std::getline(input,contentHash)||std::getline(input,extra))return false;
  return version=="no5-public-prepared-v1"&&storedKey==key&&contentHash.size()==64&&fileHash(path)==contentHash;
 }catch(const std::exception&){return false;}
}
void PreparedCache::publishIdentity(const std::string& path,const std::string& key){
 const auto temp=temporaryPath(path+".identity");
 try{
  std::ofstream output(temp,std::ios::binary);output<<"no5-public-prepared-v1\n"<<key<<'\n'<<fileHash(path)<<'\n';output.close();
  if(!output)throw std::runtime_error("Prepared identity write failed");std::filesystem::rename(temp,path+".identity");
 }catch(...){std::error_code ignored;std::filesystem::remove(temp,ignored);throw;}
}
}
