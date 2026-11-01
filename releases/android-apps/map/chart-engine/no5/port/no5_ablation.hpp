#pragma once
namespace no5 {
enum class PortrayalAblation { None, Text, Soundings, Symbols, Rules, Lines, Areas };
#if defined(NO5_FULLRENDER_DIAGNOSTICS) && !defined(__ANDROID__)
inline thread_local PortrayalAblation portrayalAblation=PortrayalAblation::None;
inline thread_local bool diagnosticSolidLineBatch=false;
inline bool suppressPortrayal(PortrayalAblation group){return portrayalAblation==group;}
inline bool keepDiagnosticRules(){return portrayalAblation==PortrayalAblation::Rules;}
inline bool batchSolidLines(){return diagnosticSolidLineBatch;}
#elif defined(NO5_LINE_BATCH_PROBE)
// Defined only by the independent probe's CMake root. No content ablations.
inline thread_local bool probeSolidLineBatch=false;
inline thread_local bool probeViewportQuery=false;
inline constexpr bool suppressPortrayal(PortrayalAblation){return false;}
inline constexpr bool keepDiagnosticRules(){return false;}
inline bool batchSolidLines(){return probeSolidLineBatch;}
#else
inline constexpr bool suppressPortrayal(PortrayalAblation){return false;}
inline constexpr bool keepDiagnosticRules(){return false;}
#ifdef NO5_BATCH_SOLID_LINES
inline constexpr bool batchSolidLines(){return true;}
#else
inline constexpr bool batchSolidLines(){return false;}
#endif
#endif
#ifdef NO5_LINE_BATCH_PROBE
struct ProbeSubmissionCounts {
 unsigned long long selectedObjects=0,areaObjectsVisited=0,areaObjectsWithDraws=0,objectsVisited=0,objectsWithDraws=0;
 unsigned long long areaQueryInput=0,areaQueryRejected=0,objectQueryInput=0,objectQueryRejected=0;
 double areaQueryWallMs=0,areaQueryCpuMs=0,objectQueryWallMs=0,objectQueryCpuMs=0;
 unsigned long long bboxChecks=0,bboxRejected=0,bboxRetained=0,reducedBboxChecks=0,reducedBboxRejected=0,linePrimitiveSegments=0,lineVerticesOrIndices=0;
 double bboxWallMs=0,bboxCpuMs=0;bool bboxTimingMeasured=false,bboxCpuMeasured=false;
};
inline thread_local ProbeSubmissionCounts probeSubmissionCounts;
#endif
}
