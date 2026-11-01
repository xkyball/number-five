#pragma once
#include "chart_model.hpp"
#include "chart_database.hpp"
namespace no5 {
// Public, unencrypted OpenCPN OSENC 201 only. Cached geometry, never pixels.
class GeometryBudgetExceeded final:public std::runtime_error {public:using std::runtime_error::runtime_error;};
class SencCache {
public:
 static std::vector<std::unique_ptr<S57Obj>> load(const std::string& source,
   const std::string& data,const std::string& destination,ChartTableEntry&,s57chart&,bool reuse=true,
   const PreparedIdentity* identity=nullptr,uint64_t geometryLimit=UINT64_MAX);
 // Conservative reservation for encoded reader buffers, object/attribute
 // containers and line/tessellation expansion; not a process RSS limit.
 static uint64_t validate(const std::string& path,ChartTableEntry* metadata=nullptr,uint64_t geometryLimit=UINT64_MAX);
};
}
