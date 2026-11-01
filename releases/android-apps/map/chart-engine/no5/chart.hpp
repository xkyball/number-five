#pragma once
#include <set>
#include "port/no5_platform.hpp"
#include "s52plib.h"
#include "port/chart_model.hpp"
#include "port/chart_database.hpp"
#include "presentation.hpp"
#include "benchmark_run.hpp"
#include "preparation_metrics.hpp"
#include "prepared_selection_cache.hpp"
#include "spatial_workset.hpp"
#include "resident_cells.hpp"
#include "viewport_coverage.hpp"
#include <deque>
namespace no5 {
struct Feature {
 std::string id,name;
 std::unique_ptr<S57Obj> object;
 LLBBox geometryBounds; // Original ENC extent, independent of drawn text/symbol bounds.
 std::map<std::string,std::string> attributes;
 ObjRazRules rules{};
 s57chart* cell=nullptr;
 // Only the original SOUNDG02 -> MP -> SOUNDG03 family is closed over
 // immutable sounding samples/attributes and the retained S52 settings key.
 bool reusableSoundingRules=false;
 size_t soundingRuleBytes=0;
 // Owner-thread staging is invisible to queries until a FULL is published.
 bool preparedSelectable=false,publishedSelectable=false;
};
// Non-owning aggregate. Cell arenas are finalized before publication; relocating
// this pointer index never relocates Feature or ObjRazRules addresses.
class FeatureView {
 std::vector<Feature*> refs;
public:
 template<bool Const> class Iterator {
  using Base=typename std::conditional<Const,std::vector<Feature*>::const_iterator,std::vector<Feature*>::iterator>::type;
  Base at;
  using Reference=typename std::conditional<Const,const Feature&,Feature&>::type;
 public:
  explicit Iterator(Base value):at(value){}
  Reference operator*()const{return **at;}
  Iterator& operator++(){++at;return *this;}
  bool operator!=(const Iterator& other)const{return at!=other.at;}
 };
 FeatureView()=default;
 FeatureView(const FeatureView&)=delete;
 FeatureView& operator=(const FeatureView&)=delete;
 auto begin(){return Iterator<false>(refs.begin());} auto end(){return Iterator<false>(refs.end());}
 auto begin()const{return Iterator<true>(refs.begin());} auto end()const{return Iterator<true>(refs.end());}
 size_t size()const{return refs.size();} bool empty()const{return refs.empty();}
 Feature& front(){return *refs.front();} const Feature& front()const{return *refs.front();}
 Feature& operator[](size_t n){return *refs.at(n);} const Feature& operator[](size_t n)const{return *refs.at(n);}
 void clear(){refs.clear();} void append(std::vector<Feature>& arena){for(auto& f:arena)refs.push_back(&f);}
};
struct Settings {std::string palette="DAY";double safety=3,shallow=2,deep=6;};
struct Projection {double latitude=0,longitude=0,heading=0;std::string id,label;bool direction=false;};
struct ObjectFilters {std::string category="ALL";std::set<std::string> hidden;bool soundings=true,text=true,important=false,aton=true,light=true,meta=false;};
#if defined(NO5_VIEWPORT_REUSE) && NO5_VIEWPORT_REUSE
inline constexpr bool viewportReuseDefault=true;
#else
inline constexpr bool viewportReuseDefault=false;
#endif
struct RenderOptimizations {
 bool stablePreparation=true,s01Shader=true,staticChart=true;
 bool soundingsPreparation=viewportReuseDefault,spatialAreas=viewportReuseDefault;
};
struct PreparationReuseStats {uint64_t safetyBuilds=0,safetyHits=0,orderBuilds=0,orderHits=0;};
struct SoundingPreparationStats {
 uint64_t frames=0,keyReuses=0,keyChanges=0,containersReused=0,pointRulesReused=0,containersReleased=0;
 uint64_t retainedBytes=0,budgetFallbacks=0;
};
struct StaticChartReuseStats {uint64_t hits=0,misses=0,stores=0;};
class No5RenderTarget {
public:
 virtual ~No5RenderTarget()=default;
 virtual void startRender(){} // Before rule preparation; optional timing hook.
 // Optional persistent chart attachment. The key belongs to the renderer;
 // the target also rejects reuse after context loss or storage replacement.
 virtual bool restoreChartFrame(const std::string&,int,int,S52color){return false;}
 virtual bool rememberChartFrame(const std::string&){return false;}
 virtual void beginFrame(int,int,S52color)=0;
 // Bind a distinct color target and return the completed area attachment.
 // Sampling an attachment of the active framebuffer would be a feedback loop.
 virtual GLuint beginAreaEffect(){throw std::logic_error("GPU area target unavailable");}
 virtual void drawLine(wxPoint,wxPoint,S52color,int)=0;
 // Overlay strokes need a stable pixel width even on GLES implementations
 // whose GL_LINES width range is restricted to one pixel.
 virtual void drawOverlayLine(wxPoint a,wxPoint b,S52color color,int width){drawLine(a,b,color,width);}
 virtual void drawSymbol(wxPoint,S52color)=0;
 virtual void replaceChartRgb(const std::vector<uint8_t>&)=0;
 virtual void endFrame()=0;
 // A diagnostic supersession never publishes a private partial frame.
 virtual void abandonFrame()noexcept{}
 // Optional single early publication after all base-area dependencies. Continue
 // the same offscreen frame afterwards; default targets retain full-image behavior.
 virtual bool wantsBaseAreas()const{return false;}
 virtual void presentBaseAreas(bool /*decorativeLandPending*/=false){}
 // One composite publication: current area fills beneath the immutable last
 // full chart. The key refers to that exact retained attachment, not its camera.
 virtual bool wantsAreaReuse()const{return false;}
 virtual bool reuseChartFrame(const std::string&,const std::array<wxPoint2DDouble,4>&){return false;}
};
class RgbaTarget final:public No5RenderTarget {
public:
 int width=0,height=0;std::vector<uint8_t> rgba;
 void beginFrame(int,int,S52color)override;
 void drawLine(wxPoint,wxPoint,S52color,int)override;
 void drawSymbol(wxPoint,S52color)override;
 void replaceChartRgb(const std::vector<uint8_t>&)override;
 void endFrame()override;
 void pixel(int,int,S52color);
};
struct BackgroundLookupDeleter {
 void operator()(LUPrec* value)const {if(value){s52plib::DestroyRulesChain(value->ruleList);delete value;}}
};
struct Cell {
 std::string path;
 s57chart model;
 chart_context context{};
 wxArrayPtrVoid floating,rigid;
 sm_parms transform{};
 std::unique_ptr<LUPrec,BackgroundLookupDeleter> backgroundWaterLookup;
 std::vector<Feature> arena; // Destroy objects before their lookups/model/context.
 ChartTableEntry table;
 std::string sourceKey,preparedKey;
 uint64_t chargedBytes=0,lastUse=0;
 size_t pins=0;
 int unsupported=0;
};
class No5ChartRenderer {
public:
 s52plib portrayal;
 VPointCompat view{};
 Settings settings;
 RenderOptimizations renderOptimizations;
 bool worldUnderlay=false; // Qualification candidate only; never chart coverage.
 PreparationReuseStats preparationReuseStats;
 SoundingPreparationStats soundingPreparationStats;
 std::string soundingPreparationJson()const;
 SpatialQueryStats areaWorksetLast;
 uint64_t areaWorksetBuilds=0;
 std::string areaWorksetJson()const;
 StaticChartReuseStats staticChartReuseStats;
 ChartDB catalogue;ChartStack stack;
 FeatureView features;
 std::vector<std::unique_ptr<Cell>> cells;
 std::vector<Projection> ownship,ais,route,waypoints;
 std::string routeRole="SAVED";
 ObjectFilters objectFilters;
 uint64_t objectFilterRevision=0;
 void applyObjectFilters(const ObjectFilters&);
 std::string readObjectFilters()const;
 int unsupported=0,filtered=0;
 bool loaded=false;
 bool preparedReuse=true;
 unsigned preparedWorkers=1;
 std::shared_ptr<BenchmarkRunControl> benchmarkRunControl;
 double preparedIdentityWallMs=0;
 std::string error,dataDirectory,cacheDirectory;
 explicit No5ChartRenderer(const std::string&,const std::string& cache="");
 ~No5ChartRenderer();
 bool open(const std::vector<std::string>&,const std::vector<std::string>& backgroundPaths={});
 void close();
 // Explicit, opt-in owner-thread lifecycle; no app activation or camera reset.
 // install validates metadata only. step performs at most one serial cell job.
 void residentCatalog(const std::string&,std::vector<ResidentSource>,ResidentBudget);
 void residentActivate(const std::string&,std::vector<ResidentSource>,ResidentBudget);
 void residentPolicy(const std::string&);
 void residentPolicyRestore(const std::string&);
 bool residentDemand(ResidentDemand);
 ResidentStep residentStep();
 ResidentStatus residentStatus()const;
 // Serial owner checkpoints; pinned arenas are never forcibly discarded.
 bool releaseRetiredResident(const std::string& expectedCatalog);
 bool resetResidentGeometry(const std::string& expectedCatalog,ResidentBudget,bool emptyGeometry);
 bool pinResident(const std::string&);
 void unpinResident(const std::string&);
 // Call after an intentional in-place ENC data edit. Normal chart updates
 // use open(), which rebuilds this dependency set and advances the generation.
 void invalidateChartData();
 void viewport(double,double,double,double,int=2000,int=1200,bool cancelLandWork=true);
 void render(No5RenderTarget&);
 void renderGl(No5RenderTarget&);
 // Temporary producer extent: canonical view and tap/projection coordinates
 // are restored, while cache keys/retained corners belong to the actual raster.
 void renderGlRaster(No5RenderTarget&,int,int);
 std::string navigationBoundsJson()const;
 // Public P0 measurement marker only; empty in normal rendering and after recovery.
 std::vector<Projection> diagnosticMarkers;
 void releaseGlResources();
 std::string query(double,double);
 std::string queryObjects(double x,double y,double radius,int offset=0);
 bool selectObject(const std::string& sourceKey,int index,double radius);
 std::string objectSelectionJson()const;
 S52color color(const std::string&);
 std::string status()const;
 wxPoint2DDouble project(double,double);
 Projection unproject(double,double);
 void transformCamera(double,double,double,double,double,double);
 double distancePixels(double,double,double,double);
 bool applyPresentationProfile(std::shared_ptr<const PresentationPacket>,bool,uint64_t);
 std::string presentationStatus()const;
 bool presentationPending()const;
 // Read-only diagnostic identity. A parity capture binds these exact revisions
 // to a completed owner frame; it never changes renderer policy or keys.
 std::array<uint64_t,3> diagnosticRevisions()const{return {geometryGeneration,presentationGeneration,objectFilterRevision};}
#ifndef __ANDROID__
 std::function<void()> residentBeforeCommitForTests;
 std::shared_ptr<const LandResources> landResourcesForTests()const{return land;}
#endif
 void waitForPresentationLand();
 void captureWork(bool enabled);
 std::string landWorkJson();
 // Debug measurement configuration; all methods are renderer-owner-only.
 bool benchmarkPreparationConfigure(const std::string& runId,unsigned requestedWorkers);
 std::string benchmarkPreparationRead(const std::string& expectedRunId)const;
 bool benchmarkPreparationRestore(const std::string& expectedRunId);
private:
 std::string selectedObjectSource;
 int selectedObjectIndex=-1,selectedObjectSegments=0;
 double selectedObjectRadius=24;
 bool selectedObjectVisible=false;
 void renderObjectSelection(No5RenderTarget&,bool prepared=false);
 bool selectableObject(Feature&);
 bool eligibilityStaged=false;
 uint64_t publishedEligibilityGeometry=0;
 uint64_t publishedEligibilityPresentation=0,publishedEligibilityFilters=0;
 VPointCompat publishedEligibilityView{};
 bool eligibilityPublished=false;
 bool hasCompletedQueryView()const;
 void stageObjectEligibility();
 void publishObjectEligibility()noexcept;
 bool objectOwnedAt(const Feature&,double,double)const;
 bool walkObjectBoundary(const Feature&,const std::function<void(wxPoint2DDouble,wxPoint2DDouble)>&);
 bool preparationMeasurementEnabled=false,preparationConfigured=false,savedPreparationMeasurement=false;
 unsigned savedPreparedWorkers=1;
 std::string preparationRunId;
 uint64_t preparationOpenSequence=0,droppedPreparationOpens=0;
 std::vector<PreparationOpenMetrics> preparationOpens;
 PreparationOpenMetrics* beginPreparationOpen(size_t sourceCount);
 void markPreparationFrameComplete(const char* renderer);
#ifdef NO5_LINE_BATCH_PROBE
 std::vector<Feature*> probeAreaCandidates,probeObjectCandidates;
#endif
 std::shared_ptr<const PresentationPacket> presentation;
 uint64_t presentationGeneration=0, geometryGeneration=0;
 uint64_t rendererIdentity=0;
 std::vector<std::string> featureTypes;
 std::vector<Feature*> datedFeatures;
 int cachedFiltered=0;
 std::string chartFrameKey(bool includeCamera=true);
 std::string retainedStyleKey,retainedFullKey;
 std::array<Projection,4> retainedCorners;
 bool presentationRelief=false;
 LandPresentation landPass;
 AreaPresentation areaPass;
 std::shared_ptr<const LandResources> land;
 std::future<std::shared_ptr<const LandResources>> landPreparation;
 std::shared_ptr<std::atomic<bool>> landCancelled;
 std::string requestedLandKey, preparingLandKey;
 bool captureLandWork=false;std::uint64_t landJobId=0,landCaptureSession=0,droppedLandWork=0;
 std::shared_ptr<LandWorkTelemetry> preparingLandTelemetry;
 std::deque<LandWorkTelemetry> completedLandWork;
 void completeLandTelemetry(bool accepted);
 std::map<LUPrec*,wxString> originalInstructions;
 void prepareLand(const std::vector<Feature*>&,bool wait=false);
 void restorePresentationAtlas();
 void clearPresentationCaches();
 bool gpuTextCacheValid=false;
 Settings gpuTextSettings;
 wxFont gpuTextDefaultFont;
 bool gpuTextNational=false;
 int gpuTextFactor=0,gpuTextDepthUnit=0,gpuTextHeightUnit=0;
 double gpuTextDip=0,gpuTextContentScale=0;
 void invalidateRules(bool keepText=false,bool keepSoundings=false);
 std::string soundingPreparationKey;
 bool safetyCacheValid=false,orderCacheValid=false;
 uint64_t safetyGeometry=0,orderGeometry=0,orderPresentation=0;
 double cachedSafety=0;
 std::vector<double> cachedContours;
 std::vector<s57chart*> orderedCells;
 std::vector<Feature*> cachedOrdered;
 PreparedSelectionCache<Feature> selectionPackets;
 SpatialWorkset areaWorkset;
 std::vector<size_t> areaWorksetOrdinals;
 std::vector<Feature*> areaWorksetCandidates;
 const std::vector<Feature*>& areaCandidates(const std::vector<Feature*>&);
 void invalidatePreparation();
 void abandonPreparedFrame()noexcept;
 std::unique_ptr<Cell> loadCell(const std::string&,const PreparedIdentity&,const std::string&,bool background=false,uint64_t geometryLimit=UINT64_MAX);
 void rebuildResidentViews();
 void evictResident(size_t,bool retired=false);
 std::vector<std::unique_ptr<Cell>> retiredCells;
 bool residentEnabled=false,residentLoading=false;
 uint64_t residentLoadingReservation=0;
 std::map<std::string,uint64_t> residentReservationBounds;
 ResidentBudget residencyBudget;
 ResidentDemand residencyDemand;
 std::string residencyCatalog,residencyMessage,residencyPolicy;
 std::vector<ResidentSource> residencySources;
 uint64_t residencyClock=0,residencyRevision=0,residencyLoads=0,residencyRetains=0,residencyEvictions=0,residencyFailures=0;
 size_t residencyPeakCells=0;
 uint64_t residencyPeakBytes=0;
 void resetResidency();
 ViewportCoverage frameCoverage;
 std::string coverageKey;
 std::map<s57chart*,size_t> coverageCells;
#ifdef ocpnUSE_GL
 CoverageStencil coverageStencil;
#endif
 void ensureViewportCoverage(bool captureCoveragePhases=false);
 size_t coverageCell(const Feature&)const;
 bool coverageAnchorOwned(const Feature&);
 const std::vector<Feature*>& prepareFrame(bool);
 void preparePortrayalBounds(const std::vector<Feature*>&,bool gpu);
 void expandPortrayalBoundsPixels(Feature&,double x,double y);
 void renderProjections(No5RenderTarget&,bool prepared=false);
};
std::string jsonEscape(const std::string&);
}
