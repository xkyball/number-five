#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace no5 {
struct ResidentSourceFile {std::string path,sha256;};
struct ResidentSource {
 std::string provider="noaa-s57",name,sourceKey;
 int edition=0,update=0,order=0;
 std::vector<ResidentSourceFile> files;
 std::string product,licenseHost; // Present only for verified private Pi exports.
};
// Catalog size is independent of this budget. The existing native draw stack
// still limits this first lifecycle implementation to 100 resident cells.
struct ResidentBudget {size_t cells=100;uint64_t bytes=512ULL*1024*1024,loadingBytes=128ULL*1024*1024;};
struct ResidentDemand {
 std::string catalogGeneration,cameraKey,policyKey;
 uint64_t cameraRevision=0;
 std::vector<std::string> required,prefetch;
};
enum class ResidentStep {Idle,Loaded,Blocked,Failed,Cancelled};
struct ResidentGeometry {
 std::string geometryId,sourceKey;
 uint64_t chargedBytes=0,reservationBoundBytes=0;
 bool pinned=false,reusableForCatalog=false;
};
struct ResidentStatus {
 bool enabled=false,complete=false;
 std::string catalogGeneration,cameraKey,policyKey,message;
 uint64_t cameraRevision=0,contentRevision=0,chargedBytes=0,geometryBytes=0,loadingBytes=0;
 size_t resident=0,loading=0,pinned=0,required=0,missing=0,peakCells=0;
 uint64_t peakChargedBytes=0,loads=0,retains=0,evictions=0,failures=0;
 std::vector<std::string> sources;
 std::vector<ResidentGeometry> geometries;
 ResidentBudget budget;
 std::string json()const;
};
// Same canonical JSON/hash as ChartMetadataContract.sourceKey. File paths are
// transport only; names plus full ordered base/update hashes define identity.
std::string residentSourceKey(const ResidentSource&);
}
