#pragma once
#include "senc_cache.hpp"
namespace no5 {
// Export only at import/backfill. Ordinary chart opening and frame work are unchanged.
struct ValidatedCellMetadata {
 ChartTableEntry table;
 int usage=0; // 0 means unavailable, never infer INTU from the filename.
 uint64_t objectCount=0; // Actual parsed count for import validation; not visible/drawn objects.
 std::string json() const;
};
ValidatedCellMetadata validatePublicCellMetadata(const std::string& source,
 const std::string& data,const std::string& destination);
}
