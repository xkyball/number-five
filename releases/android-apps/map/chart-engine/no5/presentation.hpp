#pragma once
#include "port/no5_platform.hpp"
#include "s52plib.h"
#include <future>
#include <atomic>
#include "land_telemetry.hpp"
#include "benchmark_run.hpp"

namespace no5 {
struct PresentationGlyph { std::string name; char definition='R'; int x=0,y=0,w=0,h=0,rw=0,rh=0,px=0,py=0,ox=0,oy=0; };
struct PresentationPacket {
 std::string style, symbols, mode, table, fingerprint, fontVersion, atlasVersion;
 int treatment=0, areaEffect=0, halo=0, haloRevision=7, coastWidth=1;
 wxBitmap atlas; std::vector<unsigned char> rgba, font;
 std::map<std::string,S52color> colors;
 std::vector<PresentationGlyph> glyphs;
 std::map<std::pair<int,int>,wxString> instructions;
 static std::shared_ptr<const PresentationPacket> load(const std::string& data,const std::string& style,const std::string& symbols);
};
class No5ChartRenderer;
struct LandTriangle { std::array<double,6> p; unsigned char kind; uint8_t sourceOwner=0; };
struct LandResources {
 int width=0,height=0; std::string key; std::vector<unsigned char> field,light;
 size_t landPixels=0,buildingPixels=0; double preparationMs=0;
};
// CPU inputs are copied from current SENC tessellation. No S52 pointer crosses threads.
std::shared_ptr<const LandResources> prepareLandResources(int,int,std::string,std::vector<LandTriangle>,const std::shared_ptr<std::atomic<bool>>&,const std::shared_ptr<LandWorkTelemetry>& = {},const std::shared_ptr<const std::vector<uint8_t>>& owners = {},const BenchmarkRunSnapshot& run = {});
class LandPresentation {
 GLuint program=0, texture[3]={}; int width=0,height=0;
 GLint uniforms[8]={}; std::string uploadedKey, uploadedLightKey;
public:
 unsigned links=0,uploads=0,allocations=0,copies=0,draws=0;
 void init(); void release(); void upload(const LandResources&,bool);
 void draw(const LandResources&,int,bool,S52color,S52color);
};
// Selected S01/R04 area-role pass. Reads the completed area attachment directly.
class AreaPresentation {
 GLuint program=0;
 bool programSimplified=false,programWorldAlpha=false;
 std::array<GLint,4> uniforms{};
public:
 unsigned links=0,allocations=0,copies=0,draws=0; // Legacy copy counters remain zero.
 void init(bool simplified=true,bool worldAlpha=false); void release();
 void draw(int,int,const std::array<S52color,7>&,GLuint,bool simplified=true,bool worldAlpha=false);
};
extern thread_local int presentationHalo, presentationHaloRevision, presentationCoastWidth;
void presentationTextHalo(unsigned char*,int,int,S52color*);
}
