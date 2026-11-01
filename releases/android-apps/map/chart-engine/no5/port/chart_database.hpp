#pragma once
#include "no5_platform.hpp"
#include "bbox.h"
#include "prepared_cache.hpp"
constexpr int MAXSTACK=100;
constexpr int CHART_TYPE_S57=3,CHART_TYPE_PLUGIN=7;
struct MyFlPoint {float y,x;};
class ChartStack {
public:
 ChartStack():b_valid(false),nEntry(0),CurrentStackEntry(0){}
 bool b_valid;int nEntry,CurrentStackEntry;
 int GetCurrentEntrydbIndex();void SetCurrentEntryFromdbIndex(int);
 int GetDBIndex(int);void SetDBIndex(int,int);bool DoesStackContaindbIndex(int);void AddChart(int);
private:int DBIndex[MAXSTACK]{};
};
struct ChartTableEntry {
 bool background=false; // Explicit public coarse cell; never outranks an ENC.
 wxString path,filename;int scale=0;long fileTime=0;wxString editionDate;
 LLBBox extent;std::vector<float> coverage;std::vector<std::vector<float>> auxiliary;
 std::vector<std::vector<float>> exclusions;
 no5::PreparationStats preparation;
 std::vector<int> groups;
 wxString GetFullPath()const{return path;}wxString GetFullSystemPath()const{return path;}
 const wxString* GetpFileName()const{return &filename;}
 long GetFileTime()const{return fileTime;}wxString GetChartEditionDate()const{return editionDate;}
 int GetScale()const{return scale;}int GetChartType()const{return CHART_TYPE_S57;}
 const std::vector<int>& GetGroupArray()const{return groups;}
 float GetLatMax()const{return extent.GetMaxLat();}float GetLatMin()const{return extent.GetMinLat();}
 float GetLonMax()const{return extent.GetMaxLon();}float GetLonMin()const{return extent.GetMinLon();}
 int GetnPlyEntries()const{return coverage.size()/2;}float* GetpPlyTable()const{return const_cast<float*>(coverage.data());}
 int GetnAuxPlyEntries()const{return auxiliary.size();}
 float* GetpAuxPlyTableEntry(int i)const{return const_cast<float*>(auxiliary.at(i).data());}
 int GetAuxCntTableEntry(int i)const{return auxiliary.at(i).size()/2;}
 void ReEnable(){}void SetAvailable(bool){} // Unreachable plugin branch; only S57 entries exist.
};
class ChartDB {
public:
 std::vector<ChartTableEntry> entries;
 bool IsValid()const{return !entries.empty();}
 int GetChartTableEntries()const{return entries.size();}
 const ChartTableEntry& GetChartTableEntry(int i)const{return entries.at(i);}
 bool IsChartDirectoryExcluded(const wxString&)const{return false;} // No5 supplies an explicit chart set.
 bool IsChartAvailable(int i)const{return std::filesystem::is_regular_file(std::string(entries.at(i).path));}
 int BuildChartStack(ChartStack*,float,float,int);
 bool CheckPositionWithinChart(int,float,float);
 bool EqualStacks(ChartStack*,ChartStack*);
};
extern thread_local ChartDB* ChartData;
