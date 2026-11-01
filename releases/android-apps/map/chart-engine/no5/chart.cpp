#include "chart.hpp"
#include "render_epoch.hpp"
#include "ogr_s57.h"
#include "s57class_registrar.h"
#include "s52utils.h"
#include "s52_plib_utils.h"
#include "port/senc_cache.hpp"
#include "port/sha256.hpp"
#include "prepared_converter.hpp"
#ifdef ocpnUSE_GL
#include <EGL/egl.h>
#include "port/no5_area_gpu.hpp"
#include "Cs52_shaders.h"
#include "s52shaders.h"
extern thread_local CGLShaderProgram *m_TexFontShader;
#endif

#include <fstream>
#include <sstream>
#include <iomanip>
#include <limits>
#include <filesystem>
#include <set>
#include <sys/stat.h>
#include <climits>
void no5InitializeS52Platform(const std::string&,const std::string&);
namespace no5 {
static std::atomic<uint64_t> nextRendererIdentity{0};
std::string jsonEscape(const std::string& s){std::string out;for(unsigned char c:s){if(c=='"'||c=='\\'){out+='\\';out+=c;}else if(c=='\n')out+="\\n";else if(c=='\r')out+="\\r";else if(c=='\t')out+="\\t";else if(c>=32)out+=c;}return out;}
void RgbaTarget::beginFrame(int w,int h,S52color c){width=w;height=h;rgba.resize(size_t(w)*h*4);for(size_t i=0;i<rgba.size();i+=4){rgba[i]=c.R;rgba[i+1]=c.G;rgba[i+2]=c.B;rgba[i+3]=255;}}
void RgbaTarget::pixel(int x,int y,S52color c){if(x<0||y<0||x>=width||y>=height)return;size_t i=(size_t(y)*width+x)*4;rgba[i]=c.R;rgba[i+1]=c.G;rgba[i+2]=c.B;rgba[i+3]=255;}
void RgbaTarget::drawLine(wxPoint a,wxPoint b,S52color c,int thick){
  // Clip before rasterization, keeping offscreen pans bounded.
  double x0=a.x,y0=a.y,x1=b.x,y1=b.y,dx=x1-x0,dy=y1-y0,t0=0,t1=1;
  double p[]={-dx,dx,-dy,dy},q[]={x0,double(width-1)-x0,y0,double(height-1)-y0};
  for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return;}else{double t=q[i]/p[i];if(p[i]<0)t0=std::max(t0,t);else t1=std::min(t1,t);if(t0>t1)return;}}
  a={int(std::lround(x0+t0*dx)),int(std::lround(y0+t0*dy))};b={int(std::lround(x0+t1*dx)),int(std::lround(y0+t1*dy))};
  int ix=abs(b.x-a.x),sx=a.x<b.x?1:-1,iy=-abs(b.y-a.y),sy=a.y<b.y?1:-1,e=ix+iy;
  for(;;){for(int y=-thick/2;y<=thick/2;y++)for(int x=-thick/2;x<=thick/2;x++)pixel(a.x+x,a.y+y,c);if(a.x==b.x&&a.y==b.y)break;int e2=2*e;if(e2>=iy){e+=iy;a.x+=sx;}if(e2<=ix){e+=ix;a.y+=sy;}}
}
void RgbaTarget::drawSymbol(wxPoint p,S52color c){if(p.x==INVALID_COORD||p.y==INVALID_COORD)return;drawLine({p.x-5,p.y},{p.x+5,p.y},c,1);drawLine({p.x,p.y-5},{p.x,p.y+5},c,1);}
void RgbaTarget::endFrame(){for(size_t i=3;i<rgba.size();i+=4)rgba[i]=255;}
void RgbaTarget::replaceChartRgb(const std::vector<uint8_t>& rgb){if(rgb.size()!=size_t(width)*height*3)throw std::runtime_error("chart framebuffer dimensions");for(size_t i=0,j=0;i<rgb.size();i+=3,j+=4){std::copy_n(&rgb[i],3,&rgba[j]);rgba[j+3]=255;}}
static wxString prepare(const std::string& data){no5InitializeS52Platform(data,data+"/DejaVuSans.ttf");return data+"/S52RAZDS.RLE";}
No5ChartRenderer::No5ChartRenderer(const std::string& data,const std::string& cache):portrayal(prepare(data),false),dataDirectory(data),cacheDirectory(cache.empty()?data+"/senc":cache){
 rendererIdentity=++nextRendererIdentity;
 ps52plib=&portrayal;
 if(!portrayal.m_bOK)throw std::runtime_error("OpenCPN presentation library failed");
 portrayal.m_bShowMeta=false;portrayal.m_bUseSCAMIN=true;portrayal.m_bUseSUPER_SCAMIN=false;
 portrayal.m_nSoundingFactor=0;portrayal.m_ChartScaleFactorExp=1;portrayal.m_SoundingsPointSize=10;
 portrayal.m_chart_zoom_modifier_vector=0;portrayal.ChartTextDefaultFont=nullptr;
 portrayal.SetScaleFactorExp(1);portrayal.SetShowS57Text(true);portrayal.SetDisplayCategory(OTHER);portrayal.SetTextOverlapAvoid(true);
 portrayal.SetDisplayWidth(2000);portrayal.SetPPMM(3);viewport(0,0,0.02,0);
}
No5ChartRenderer::~No5ChartRenderer(){if(landCancelled)landCancelled->store(true);if(landPreparation.valid())landPreparation.wait();for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)cell->pins=0;close();if(ps52plib==&portrayal)ps52plib=nullptr;}
void No5ChartRenderer::viewport(double lat,double lon,double ppm,double angle,int w,int h,bool cancelLandWork){
 if(!std::isfinite(lat)||!std::isfinite(lon)||!std::isfinite(ppm)||!std::isfinite(angle)||abs(lat)>85||abs(lon)>180||ppm<=0||w<1||w>4096||h<1||h>1499)throw std::invalid_argument("Invalid viewport");
 // Cancel only the old image-space land job, as soon as the native owner
 // applies a different camera. Immutable cell geometry remains reusable.
 if(cancelLandWork&&landCancelled&&(view.clat!=lat||view.clon!=lon||view.view_scale_ppm!=std::clamp(ppm,.00001,10.0)||
    view.rotation!=angle||view.pix_width!=w||view.pix_height!=h))landCancelled->store(true);
 view.pix_width=w;view.pix_height=h;view.view_scale_ppm=std::clamp(ppm,0.00001,10.0);view.rotation=angle;view.clat=lat;view.clon=lon;
  view.chart_scale=portrayal.GetPPMM()*1000/view.view_scale_ppm;view.ref_scale=view.chart_scale;view.display_scale=1;view.rv_rect=wxRect(0,0,w,h);
 ChartData=&catalogue;stack=ChartStack{};catalogue.BuildChartStack(&stack,lat,lon,0);
 if(stack.nEntry)view.ref_scale=catalogue.GetChartTableEntry(stack.GetDBIndex(0)).GetScale();
 double minlat=90,maxlat=-90,minlon=1e10,maxlon=-1e10;
 for(auto x:{0,w})for(auto y:{0,h}){double east=x-w/2.0,north=h/2.0-y;double c=cos(angle),s=sin(angle),la,lo;fromSM_plib((east*c-north*s)/view.view_scale_ppm,(north*c+east*s)/view.view_scale_ppm,lat,lon,&la,&lo);minlat=std::min(minlat,la);maxlat=std::max(maxlat,la);minlon=std::min(minlon,lo);maxlon=std::max(maxlon,lo);}
 LLBBox bbox; bbox.Set(minlat,minlon,maxlat,maxlon);
 portrayal.SetVPointCompat(w,h,view.view_scale_ppm,angle,lat,lon,view.chart_scale,view.rv_rect,bbox,view.ref_scale,1);
}
wxPoint2DDouble No5ChartRenderer::project(double lat,double lon){return portrayal.GetDoublePixFromLLROT(lat,lon,view.rotation);}
Projection No5ChartRenderer::unproject(double x,double y){
 if(!std::isfinite(x)||!std::isfinite(y))throw std::invalid_argument("Invalid screen coordinate");
 Projection p;portrayal.GetLLFromPix(wxPoint2DDouble(x,y),&p.latitude,&p.longitude);return p;
}
void No5ChartRenderer::transformCamera(double ax,double ay,double px,double py,double zoom,double rotation){
 if(!std::isfinite(ax)||!std::isfinite(ay)||!std::isfinite(px)||!std::isfinite(py)||!std::isfinite(zoom)||!std::isfinite(rotation)||zoom<=0)throw std::invalid_argument("Invalid camera transform");
 const auto before=view;double ppm=std::clamp(before.view_scale_ppm*zoom,.00001,10.0);
 const double angle=std::atan2(std::sin(before.rotation+rotation),std::cos(before.rotation+rotation));
 const double cx=before.pix_width*.5,cy=before.pix_height*.5;
 auto offset=[&](double x,double y,double scale,double r){return wxPoint2DDouble(((x-cx)*std::cos(r)+(y-cy)*std::sin(r))/scale,((x-cx)*std::sin(r)-(y-cy)*std::cos(r))/scale);};
 auto old=offset(ax,ay,before.view_scale_ppm,before.rotation),next=offset(ax+px,ay+py,ppm,angle);
 double lat,lon;fromSM_plib(old.m_x-next.m_x,old.m_y-next.m_y,before.clat,before.clon,&lat,&lon);
 lon=std::fmod(std::fmod(lon+180,360)+360,360)-180;
 viewport(std::clamp(lat,-85.0,85.0),lon,ppm,angle,before.pix_width,before.pix_height);
}
double No5ChartRenderer::distancePixels(double x0,double y0,double x1,double y1){
 auto p=unproject(x0,y0),q=unproject(x1,y1);const double rad=std::acos(-1.0)/180;
 double lat=(q.latitude-p.latitude)*rad,lon=(q.longitude-p.longitude)*rad;
 double h=std::pow(std::sin(lat*.5),2)+std::cos(p.latitude*rad)*std::cos(q.latitude*rad)*std::pow(std::sin(lon*.5),2);
 return 6378137.0*2*std::asin(std::sqrt(std::clamp(h,0.0,1.0)));
}
bool No5ChartRenderer::benchmarkPreparationConfigure(const std::string& id,unsigned workers){
 if(workers!=1&&workers!=2&&workers!=4&&workers!=6)return false;
 if(!benchmarkRunControl||!benchmarkRunControl->owns(id))return false;
 benchmarkRunControl->snapshot().checkpoint();
 if(preparationConfigured)return preparationRunId==id&&preparedWorkers==workers;
 savedPreparedWorkers=preparedWorkers;savedPreparationMeasurement=preparationMeasurementEnabled;
 preparationOpens.clear();preparationOpenSequence=droppedPreparationOpens=0;preparationRunId=id;
 preparationConfigured=true;preparationMeasurementEnabled=true;preparedWorkers=workers;return true;
}
bool No5ChartRenderer::benchmarkPreparationRestore(const std::string& id){
 if(id.empty()||preparationRunId!=id||(benchmarkRunControl&&!benchmarkRunControl->idle()))return false;
 if(!preparationConfigured)return true; // Same frozen token is an idempotent no-op.
 preparedWorkers=savedPreparedWorkers;preparationMeasurementEnabled=savedPreparationMeasurement;preparationConfigured=false;return true;
}
std::string No5ChartRenderer::benchmarkPreparationRead(const std::string& id)const{
 std::ostringstream out;out<<"{\"schema\":\"no5.preparation-metrics.v1\",\"runId\":"<<preparationJsonString(id);
 if(id.empty()||preparationRunId!=id){out<<",\"available\":false}";return out.str();}
 out<<",\"available\":true,\"enabled\":"<<(preparationMeasurementEnabled?"true":"false")<<",\"frozen\":"<<(preparationConfigured?"false":"true")<<",\"droppedOpenReports\":"<<droppedPreparationOpens<<",\"opens\":[";
 bool first=true;for(const auto& open:preparationOpens){if(!first)out<<',';first=false;out<<open.json();}out<<"]}";return out.str();
}
PreparationOpenMetrics* No5ChartRenderer::beginPreparationOpen(size_t sourceCount){
 if(!preparationMeasurementEnabled||!preparationConfigured||!benchmarkRunControl||!benchmarkRunControl->owns(preparationRunId))return nullptr;
 if(preparationOpens.size()==8){preparationOpens.erase(preparationOpens.begin());droppedPreparationOpens++;}
 preparationOpens.emplace_back();auto& report=preparationOpens.back();report.openSequence=++preparationOpenSequence;report.sourceCount=sourceCount;report.requestedWorkers=preparedWorkers;report.requestedReuse=preparedReuse;report.converter=No5PreparedConverter;report.started=std::chrono::steady_clock::now();return &report;
}
void No5ChartRenderer::markPreparationFrameComplete(const char* renderer){
 if(!preparationMeasurementEnabled||!preparationConfigured||preparationOpens.empty()||!benchmarkRunControl||!benchmarkRunControl->owns(preparationRunId))return;
 auto& report=preparationOpens.back();if(report.firstNativeFrameComplete||report.status!="complete"||!loaded||report.geometryGeneration!=geometryGeneration||presentationPending())return;
 report.firstFrameElapsedRealtimeMs=benchmarkElapsedRealtimeMs();report.openToFirstNativeFrameCompleteWallMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-report.started).count();report.firstFrameWidth=view.pix_width;report.firstFrameHeight=view.pix_height;report.firstFrameRenderer=renderer;report.firstNativeFrameComplete=true;
}
bool No5ChartRenderer::open(const std::vector<std::string>& paths,const std::vector<std::string>& backgroundPaths){
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};
 auto* measurement=beginPreparationOpen(paths.size());PreparationScope openScope(measurement?&measurement->nativeOpen:nullptr);
 close();ps52plib=&portrayal;
 try{
  run.checkpoint();
  if(paths.empty()||paths.size()>MAXSTACK)throw std::runtime_error("Chart set size outside foundation limit");
  const std::set<std::string> background(backgroundPaths.begin(),backgroundPaths.end());
  if(background.size()!=backgroundPaths.size()||std::any_of(background.begin(),background.end(),[&](const auto& p){return std::find(paths.begin(),paths.end(),p)==paths.end();}))throw std::runtime_error("Background cells outside explicit source set");
   double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
  PreparationIdentityMetrics* initial=nullptr;if(measurement){measurement->phases.emplace_back();initial=&measurement->phases.back();initial->phase="initial_generation";}
  auto identityStart=std::chrono::steady_clock::now();const auto identities=PreparedCache::identities(paths,dataDirectory,preparedWorkers,run,initial);
  if(measurement){Sha256 generation;for(const auto& identity:identities)generation.update(std::to_string(identity.key.size())+":"+identity.key);measurement->sourceGeneration=generation.hex();}
  preparedIdentityWallMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-identityStart).count();
  for(const auto& path:paths){
   run.checkpoint();
   auto cell=loadCell(path,identities.at(cells.size()),std::to_string(cells.size()),background.count(path));auto& table=cell->table;
   unsupported+=cell->unsupported;
   run.checkpoint(); // The existing serial parser itself is not preempted.
   minLat=std::min(minLat,table.extent.GetMinLat());maxLat=std::max(maxLat,table.extent.GetMaxLat());minLon=std::min(minLon,table.extent.GetMinLon());maxLon=std::max(maxLon,table.extent.GetMaxLon());
   catalogue.entries.push_back(std::move(table));cells.push_back(std::move(cell));
   if(measurement){const auto& p=catalogue.entries.back().preparation;measurement->cells.push_back({p.reused,p.sourceBytes,p.cacheBytes});}
   run.checkpoint();
  }
  // Recheck the entire public generation before exposing any loaded objects.
  PreparationIdentityMetrics* recheck=nullptr;if(measurement){measurement->phases.emplace_back();recheck=&measurement->phases.back();recheck->phase="generation_recheck";}
  identityStart=std::chrono::steady_clock::now();const auto checked=PreparedCache::identities(paths,dataDirectory,preparedWorkers,run,recheck);
  preparedIdentityWallMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-identityStart).count();
  for(size_t i=0;i<checked.size();++i){if(checked[i].key!=identities[i].key)throw std::runtime_error("S57 source changed during chart open");catalogue.entries[i].preparation.keyMs+=checked[i].keyMs;}
  for(auto& cell:cells)features.append(cell->arena);
  invalidateChartData();
  run.checkpoint();
  loaded=!features.empty();if(!loaded)throw std::runtime_error("S57 contains no objects with presentation rules");
  double span=std::max((maxLon-minLon)*111300.0,(maxLat-minLat)*111300.0);viewport((minLat+maxLat)/2,(minLon+maxLon)/2,900.0/std::max(1.0,span),0);error.clear();if(measurement){measurement->geometryGeneration=geometryGeneration;measurement->status="complete";}return true;
 }catch(const std::exception& e){error=e.what();if(measurement){measurement->status=dynamic_cast<const BenchmarkRunCancelled*>(&e)?"cancelled":"failed";measurement->error=e.what();}close();return false;}
}
static bool closedSoundingFamily(const Feature& feature){
 const auto& object=*feature.object;
 if(feature.name!="SOUNDG"||!object.geoPtz||!object.geoPtMulti||object.npt<=0||!feature.rules.LUP)return false;
 bool found=false;
 for(auto* rule=feature.rules.LUP->ruleList;rule;rule=rule->next)if(rule->ruleType==RUL_CND_SY){
  if(!rule->INSTstr||std::strncmp(rule->INSTstr,"SOUNDG02",8)!=0)return false;
  found=true;
 }
 return found;
}
std::unique_ptr<Cell> No5ChartRenderer::loadCell(const std::string& path,const PreparedIdentity& identity,const std::string& idPrefix,bool background,uint64_t geometryLimit){
 auto cell=std::make_unique<Cell>();cell->path=path;auto& table=cell->table;
   auto objects=SencCache::load(path,dataDirectory,cacheDirectory+"/"+idPrefix+"-"+std::filesystem::path(path).stem().string()+".senc",table,cell->model,preparedReuse,&identity,residentEnabled?std::min(residencyBudget.loadingBytes,geometryLimit):geometryLimit);
   table.background=background;

   cell->context.ref_lat=cell->model.ref_lat;cell->context.ref_lon=cell->model.ref_lon;cell->context.chart=&cell->model;cell->context.pt2GetAssociatedObjects=&s57chart::GetAssociatedObjects;
   cell->context.m_pve_hash=&cell->model.m_ve_hash;cell->context.m_pvc_hash=&cell->model.m_vc_hash;cell->context.pFloatingATONArray=&cell->floating;cell->context.pRigidATONArray=&cell->rigid;cell->context.chart_type=S52_CHART_TYPE_S57;cell->context.chart_scale=table.scale;
   for(auto& object:objects){
    Feature f;f.name=std::string(object->FeatureName,6);f.id=idPrefix+":"+std::to_string(object->Index);f.object=std::move(object);f.cell=&cell->model;auto& obj=*f.object;obj.m_chart_context=&cell->context;f.geometryBounds=obj.BBObj;
    for(int i=0;i<obj.n_attr;i++){std::string name(obj.att_array+i*6,6);auto v=obj.attVal->Item(i);std::ostringstream text;text<<std::setprecision(std::numeric_limits<double>::max_digits10);if(v->valType==OGR_INT)text<<*static_cast<int*>(v->value);else if(v->valType==OGR_REAL)text<<*static_cast<double*>(v->value);else if(v->valType==OGR_STR)text<<static_cast<char*>(v->value);f.attributes[name]=text.str();}
    auto primitive=obj.Primitive_type;auto lup=portrayal.S52_LUPLookup(primitive==GEO_AREA?PLAIN_BOUNDARIES:primitive==GEO_LINE?LINES:PAPER_CHART,f.name.c_str(),&obj);
    if(!lup){cell->unsupported++;continue;}
    if(background && f.name=="SEAARE" && primitive==GEO_AREA){
     // SEAARE has no measured depths. Paint neutral water only for the
     // explicitly public background provider, leaving original S52 lookups intact.
     if(!cell->backgroundWaterLookup){
      cell->backgroundWaterLookup.reset(new LUPrec(*lup));
      // The original instruction parser requires a semicolon/end-record delimiter.
      auto& own=*cell->backgroundWaterLookup;own.INST="AC(DEPDW);";own.ruleList=nullptr;own.DISC=DISPLAYBASE;own.DPRI=PRIO_GROUP1;
     }
     lup=cell->backgroundWaterLookup.get();
    }
    portrayal._LUP2rules(lup,&obj);obj.m_DisplayCat=lup->DISC;obj.m_DPRI=lup->DPRI-'0';obj.m_bcategory_mutable=f.name=="OBSTRN"||f.name=="WRECKS"||f.name=="DEPCNT"||f.name=="UWTROC";
    f.rules.obj=&obj;f.rules.LUP=lup;f.rules.sm_transform_parms=&cell->transform;
    // The original SOUNDG03/SNDFRM02 expansion reads the original samples,
    // quality attributes, safety depth and depth units. Camera/SCAMIN/layout
    // checks happen separately in RenderMPS on every frame. Do not extend
    // this to other conditional families with area-association dependencies.
    f.reusableSoundingRules=closedSoundingFamily(f);
    if(f.name.rfind("BOY",0)==0){obj.bIsAton=true;cell->floating.Add(&obj);}else if(f.name.rfind("BCN",0)==0||f.name=="LIGHTS"){obj.bIsAton=true;cell->rigid.Add(&obj);}
    cell->arena.push_back(std::move(f));
   }
 // No arena growth after these links are published.
 for(auto& f:cell->arena){int group=f.object->Primitive_type==GEO_AREA?3:f.object->Primitive_type==GEO_LINE?2:1;int priority=f.rules.LUP->DPRI-'0';f.rules.next=cell->model.razRules[priority][group];cell->model.razRules[priority][group]=&f.rules;}
 cell->model.AssembleLineGeometry();cell->context.vertex_buffer=cell->model.m_line_vertex_buffer;
 cell->preparedKey=identity.key;if(cell->preparedKey.empty()&&!residentEnabled)cell->preparedKey=PreparedCache::fileHash(path);return cell;
 }
void No5ChartRenderer::rebuildResidentViews(){
 if(residentEnabled)std::sort(cells.begin(),cells.end(),[&](const auto& a,const auto& b){
  auto rank=[&](const auto& key){auto i=std::find_if(residencySources.begin(),residencySources.end(),[&](const auto& source){return source.sourceKey==key;});return i==residencySources.end()?INT_MAX:i->order;};
  return rank(a->sourceKey)<rank(b->sourceKey);
 });
 features.clear();catalogue.entries.clear();unsupported=0;
 for(auto& cell:cells){features.append(cell->arena);catalogue.entries.push_back(cell->table);unsupported+=cell->unsupported;}
 loaded=!features.empty();invalidateChartData();
 // Re-evaluate the existing drawing policy without resetting the camera.
 viewport(view.clat,view.clon,view.view_scale_ppm,view.rotation,view.pix_width,view.pix_height);
}
static void releaseMultipoint(ObjRazRules& r,s52plib& lib){if(r.mps){if(r.mps->cs_rules){for(auto chain:*r.mps->cs_rules)s52plib::DestroyRulesChain(chain);delete r.mps->cs_rules;}free(r.mps);r.mps=nullptr;}while(r.child){auto child=r.child;r.child=child->next;delete child->obj;lib.DestroyLUP(child->LUP);delete child;}}
void No5ChartRenderer::close(){
 selectedObjectSource.clear();selectedObjectIndex=-1;selectedObjectVisible=false;selectedObjectSegments=0;
 if(residentLoading)throw std::logic_error("Close during owner resident load");
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(cell->pins)throw std::logic_error("Release resident pins before closing chart set");
#ifdef ocpnUSE_GL
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(eglGetCurrentContext()==EGL_NO_CONTEXT){
  if(cell->model.m_LineVBO_name>0)throw std::logic_error("Close GL geometry requires current owner context");
  for(const auto& f:cell->arena)if((f.object->pPolyTessGeo&&f.object->auxParm0>0)||hasAreaGpu(f.object.get()))throw std::logic_error("Close area GL geometry requires current owner context");
 }
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(cell->model.m_LineVBO_name>0){GLuint id=cell->model.m_LineVBO_name;glDeleteBuffers(1,&id);cell->model.m_LineVBO_name=-1;}
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)for(auto& f:cell->arena){auto& object=*f.object;
  releaseAreaGpu(f.object.get());
  if(object.pPolyTessGeo&&object.auxParm0>0&&g_b_EnableVBO&&!object.auxParm1){auto* group=object.pPolyTessGeo->Get_PolyTriGroup_head();if(group&&group->single_buffer){GLuint id=GLuint(object.auxParm0);glDeleteBuffers(1,&id);object.auxParm0=0;}}
 }
#endif
 featureTypes.clear();datedFeatures.clear();invalidatePreparation();coverageKey.clear();frameCoverage={};coverageCells.clear();captureWork(false);geometryGeneration++;if(landCancelled)landCancelled->store(true);land.reset();requestedLandKey.clear();gpuTextCacheValid=false;ps52plib=&portrayal;portrayal.ClearTextList();for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)for(auto& f:cell->arena)releaseMultipoint(f.rules,portrayal);features.clear();cells.clear();resetResidency();catalogue.entries.clear();stack=ChartStack{};loaded=false;filtered=0;unsupported=0;}
void No5ChartRenderer::invalidateRules(bool keepText,bool keepSoundings){
 DetailScope scope(RenderDetail::RuleReset);portrayal.ClearTextList();
 if(!keepSoundings)soundingPreparationKey.clear();
 // Bound additional retained instruction payload; a large container keeps
 // the original rebuild path. This is not a total process/RSS limit.
 constexpr size_t soundingBudget=16*1024*1024;
 soundingPreparationStats.retainedBytes=0;
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)for(auto& f:cell->arena){
  if(keepDiagnosticRules())continue;
  const auto* mps=f.rules.mps;
  bool retained=keepSoundings&&f.reusableSoundingRules&&f.object->bCS_Added&&
   f.object->CSrules&&mps&&mps->cs_rules&&mps->cs_rules->size()==size_t(f.object->npt);
  if(retained){
   if(!f.soundingRuleBytes){
    f.soundingRuleBytes=sizeof(mps_container)+sizeof(ArrayOfRules)+mps->cs_rules->capacity()*sizeof(Rules*);
    for(auto chain:*mps->cs_rules){
     if(chain&&chain->INST0)f.soundingRuleBytes+=strlen(chain->INST0)+1;
     for(auto* rule=chain;rule;rule=rule->next)f.soundingRuleBytes+=sizeof(Rules);
    }
   }
   if(f.soundingRuleBytes>soundingBudget-soundingPreparationStats.retainedBytes){retained=false;soundingPreparationStats.budgetFallbacks++;}
  }
  if(retained){soundingPreparationStats.containersReused++;soundingPreparationStats.pointRulesReused+=f.object->npt;soundingPreparationStats.retainedBytes+=f.soundingRuleBytes;}
  else{
   if(mps)soundingPreparationStats.containersReleased++;
   releaseMultipoint(f.rules,portrayal);f.object->bCS_Added=0;f.soundingRuleBytes=0;
  }
  // Category/priority, date, SCAMIN, position and current text placement are
  // still fresh. The retained sounding family does not mutate DISC/DPRI.
  f.object->m_DisplayCat=f.rules.LUP->DISC;f.object->m_DPRI=f.rules.LUP->DPRI-'0';
  if(!keepText&&f.object->FText){delete f.object->FText;f.object->FText=nullptr;f.object->bFText_Added=0;}
 }
}
S52color No5ChartRenderer::color(const std::string& name){return *portrayal.getColor(name.c_str());}
void No5ChartRenderer::invalidatePreparation(){safetyCacheValid=orderCacheValid=false;soundingPreparationKey.clear();selectionPackets.clear();areaWorkset.clear();areaWorksetOrdinals.clear();areaWorksetCandidates.clear();std::vector<Feature*>().swap(cachedOrdered);orderedCells.clear();cachedContours.clear();}
void No5ChartRenderer::abandonPreparedFrame()noexcept{
 eligibilityStaged=false;eligibilityPublished=false;
 // No loaded/tessellated ENC geometry is discarded. Mutable view preparation
 // is explicitly invalid until a fresh original PrepareForRender completes.
 gpuTextCacheValid=false;invalidatePreparation();coverageKey.clear();frameCoverage={};coverageCells.clear();
 retainedFullKey.clear();retainedStyleKey.clear();
 if(landCancelled)landCancelled->store(true);requestedLandKey.clear();
}
void No5ChartRenderer::invalidateChartData(){
 geometryGeneration++;invalidatePreparation();coverageKey.clear();frameCoverage={};coverageCells.clear();datedFeatures.clear();std::set<std::string> types;
 for(auto& f:features){
#ifdef ocpnUSE_GL
  // Data changes may update retained CPU vertices in place. Numeric pointer
  // identity alone cannot authorize a GPU buffer after this generation edge.
  // This only marks derived resources dirty; it needs no current GL context.
  invalidateAreaGpu(f.object.get());
#endif
  types.insert(f.name);if(!f.object->GetAttrValueAsString("DATSTA").empty()||!f.object->GetAttrValueAsString("DATEND").empty()||!f.object->GetAttrValueAsString("PEREND").empty())datedFeatures.push_back(&f);
 }
 featureTypes.assign(types.begin(),types.end());
}
void No5ChartRenderer::ensureViewportCoverage(bool captureCoveragePhases){
 StageScope keyScope(RenderStage::CoverageKey,captureCoveragePhases);
 std::ostringstream key;key<<std::hexfloat<<geometryGeneration<<'|'<<view.clat<<'|'<<view.clon<<'|'<<view.view_scale_ppm<<'|'<<view.rotation<<'|'<<view.chart_scale<<'|'<<view.pix_width<<'|'<<view.pix_height;
 const bool rebuild=coverageKey!=key.str();
 if(captureCoveragePhases&&profileActive){++currentProfile.coverageWork.keyLookups;if(!rebuild)++currentProfile.coverageWork.keyHits;}
 keyScope.finish();
 if(rebuild){
 StageScope buildScope(RenderStage::CoverageBuild,captureCoveragePhases);
 auto next=buildViewportCoverage(catalogue.entries,cells.size(),{view.clat,view.clon,view.view_scale_ppm,view.rotation,view.chart_scale,view.pix_width,view.pix_height});
 normalRenderCheckpoint(RenderAbortBoundary::CoverageCommit);
 if(captureCoveragePhases&&profileActive){auto& work=currentProfile.coverageWork;++work.builds;work.residentCells=cells.size();work.rasterPixels=next.owners.size();work.scanRows=next.scanRows;work.ownershipPixelChecks=next.ownershipPixelChecks;}
 coverageCells.clear();for(size_t i=0;i<cells.size();i++)coverageCells.emplace(&cells[i]->model,i);
 frameCoverage=std::move(next);coverageKey=key.str();
 }
 if(frameCoverage.masked&&frameCoverage.referenceScale){
  view.ref_scale=frameCoverage.referenceScale;
  portrayal.SetVPointCompat(view.pix_width,view.pix_height,view.view_scale_ppm,view.rotation,view.clat,view.clon,view.chart_scale,view.rv_rect,portrayal.GetBBox(),view.ref_scale,view.display_scale);
 }
}
size_t No5ChartRenderer::coverageCell(const Feature& feature)const{return coverageCells.at(feature.cell);}
bool No5ChartRenderer::coverageAnchorOwned(const Feature& feature){
 if(!frameCoverage.masked)return true;
 auto point=project(feature.object->m_lat,feature.object->m_lon);
 // Preserve decorations whose anchors lie just beyond the viewport: clip to
 // the ownership at that border. S52 retains its original padded BB tests.
 return frameCoverage.owns(coverageCell(feature),std::clamp(point.m_x,0.,double(view.pix_width)-.5),std::clamp(point.m_y,0.,double(view.pix_height)-.5));
}
namespace {
struct CoverageTextScope{
 s52plib& lib;bool previous;
 CoverageTextScope(s52plib& value,bool anchorOwned):lib(value),previous(value.m_bShowS57Text){if(!anchorOwned)lib.m_bShowS57Text=false;}
 ~CoverageTextScope(){lib.m_bShowS57Text=previous;}
};
#ifdef ocpnUSE_GL
struct CoveragePassScope{
 CoverageStencil* stencil;
 explicit CoveragePassScope(CoverageStencil* value):stencil(value){if(stencil)stencil->begin();}
 ~CoveragePassScope(){if(stencil)stencil->end();}
};
#endif
}
std::string No5ChartRenderer::chartFrameKey(bool includeCamera){
 // Compare complete inputs rather than trusting the legacy partial CRC alone.
 // ENC geometry/attributes are immutable between open/close reloads.
 std::ostringstream s;s<<std::hexfloat<<rendererIdentity<<'|'<<geometryGeneration<<'|'<<presentationGeneration<<'|';
 if(includeCamera)s<<view.clat<<'|'<<view.clon<<'|'<<view.view_scale_ppm<<'|'<<view.rotation<<'|'<<view.chart_scale<<'|'<<view.ref_scale<<'|'<<(land?land->key:"")<<'|';
 if(worldUnderlay)s<<"world-alpha-v1|";
 s<<view.pix_width<<'|'<<view.pix_height<<'|'<<view.display_scale<<'|'<<settings.palette<<'|'<<settings.safety<<'|'<<settings.shallow<<'|'<<settings.deep<<'|'<<renderOptimizations.s01Shader<<'|'<<presentationRelief<<'|';
 portrayal.GenerateStateHash();s<<portrayal.GetStateHash()<<'|';
 for(int i=0;i<S52_MAR_NUM;i++)s<<S52_getMarinerParam(S52_MAR_param_t(i))<<'|';
 s<<portrayal.m_nSymbolStyle<<'|'<<portrayal.m_nBoundaryStyle<<'|'<<portrayal.m_bShowSoundg<<'|'<<portrayal.m_bShowMeta<<'|'<<portrayal.m_bShowS57Text<<'|'<<portrayal.m_bUseSCAMIN<<'|'<<portrayal.m_bUseSUPER_SCAMIN<<'|'<<portrayal.m_bShowAtonText<<'|'<<portrayal.m_bShowLdisText<<'|'<<portrayal.m_bExtendLightSectors<<'|'<<portrayal.m_bShowS57ImportantTextOnly<<'|'<<portrayal.m_bDeClutterText<<'|'<<portrayal.m_bShowNationalTexts<<'|'<<portrayal.m_nSoundingFactor<<'|'<<portrayal.m_nTextFactor<<'|'<<portrayal.m_SoundingsPointSize<<'|'<<portrayal.m_ChartScaleFactorExp<<'|'<<portrayal.m_chart_zoom_modifier_vector<<'|'<<portrayal.m_dipfactor<<'|'<<portrayal.m_ContentScaleFactor<<'|'<<portrayal.m_nDepthUnitDisplay<<'|'<<portrayal.m_nHeightUnitDisplay<<'|'<<portrayal.m_GLMinCartographicLineWidth<<'|'<<portrayal.m_GLMinSymbolLineWidth<<'|'<<portrayal.GetDisplayCategory()<<'|'<<portrayal.GetPPMM()<<'|'<<portrayal.GetLightsOff()<<'|'<<portrayal.GetGLLineSmoothing()<<'|'<<portrayal.GetGLPolygonSmoothing()<<'|';
 const auto& font=*GetS52Utils()->GetDefaultFont("ChartTexts",0);s<<font.GetPointSize()<<'|'<<font.GetFamily()<<'|'<<font.GetStyle()<<'|'<<font.GetWeight()<<'|'<<font.GetFaceName()<<'|';
 for(const auto& type:featureTypes)s<<portrayal.IsObjNoshow(type.c_str());s<<'|';
 for(unsigned i=0;i<portrayal.pOBJLArray->GetCount();i++)s<<static_cast<OBJLElement*>(portrayal.pOBJLArray->Item(i))->nViz<<',';s<<'|';
 // Use the original eligibility evaluator every frame, including backward
 // clock changes and exact DATSTA/DATEND/PEREND transitions. Never freeze it.
 for(auto f:datedFeatures)s<<portrayal.ObjectRenderCheckDates(&f->rules);
 return s.str();
}
const std::vector<Feature*>& No5ChartRenderer::prepareFrame(bool gpu){
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};if(run.attached())run.checkpoint();
#ifdef ocpnUSE_GL
 portrayal.m_useGLSL=gpu;
 if(gpu){portrayal.SetGLOptions(false,false,false,true,false,GL_TEXTURE_2D,1,1);restorePresentationAtlas();}
#endif
 // The immutable original S57 seed includes its nominal point margin. Actual
 // current-camera portrayal extents are prepared below; none survive a pan.
 for(auto& f:features){if(run.attached())run.checkpoint();normalRenderCheckpoint(RenderAbortBoundary::Portrayal);f.object->BBObj=f.geometryBounds;}
 ps52plib=&portrayal;portrayal.SetPLIBColorScheme(settings.palette=="DAY"?GLOBAL_COLOR_SCHEME_DAY:settings.palette=="DUSK"?GLOBAL_COLOR_SCHEME_DUSK:GLOBAL_COLOR_SCHEME_NIGHT,ChartCtx(gpu));
 S52_setMarinerParam(S52_MAR_SAFETY_CONTOUR,settings.safety);S52_setMarinerParam(S52_MAR_SAFETY_DEPTH,settings.safety);S52_setMarinerParam(S52_MAR_SHALLOW_CONTOUR,settings.shallow);S52_setMarinerParam(S52_MAR_DEEP_CONTOUR,settings.deep);portrayal.UpdateMarinerParams();
 // Keep glyph-string textures across camera changes. Rule eligibility and
 // positions are still evaluated for every vector frame.
 const wxFont& font=*GetS52Utils()->GetDefaultFont("ChartTexts",0);
 bool keepText=gpu&&gpuTextCacheValid&&settings.palette==gpuTextSettings.palette&&
   settings.safety==gpuTextSettings.safety&&settings.shallow==gpuTextSettings.shallow&&settings.deep==gpuTextSettings.deep&&
   portrayal.m_bShowNationalTexts==gpuTextNational&&portrayal.m_nTextFactor==gpuTextFactor&&
   portrayal.m_nDepthUnitDisplay==gpuTextDepthUnit&&portrayal.m_nHeightUnitDisplay==gpuTextHeightUnit&&
   portrayal.m_dipfactor==gpuTextDip&&portrayal.m_ContentScaleFactor==gpuTextContentScale&&
   font.GetPointSize()==gpuTextDefaultFont.GetPointSize()&&font.GetFamily()==gpuTextDefaultFont.GetFamily()&&
   font.GetStyle()==gpuTextDefaultFont.GetStyle()&&font.GetWeight()==gpuTextDefaultFont.GetWeight()&&font.GetFaceName()==gpuTextDefaultFont.GetFaceName();
 // Reuse only the closed SOUNDG expansion. This complete, camera-independent
 // key conservatively includes source/presentation generations, all mariner
 // settings, units, fonts, palette, filters and current date eligibility.
 // RenderMPS still runs its per-view inclusion/SCAMIN/glyph placement checks.
 const auto soundingKey=renderOptimizations.soundingsPreparation?
  std::string(gpu?"gles|":"cpu|")+chartFrameKey(false):std::string{};
 const bool keepSoundings=renderOptimizations.soundingsPreparation&&!soundingPreparationKey.empty()&&soundingPreparationKey==soundingKey;
 soundingPreparationStats.frames++;
 if(keepSoundings)soundingPreparationStats.keyReuses++;else soundingPreparationStats.keyChanges++;
 invalidateRules(keepText,keepSoundings);
 soundingPreparationKey=renderOptimizations.soundingsPreparation?soundingKey:std::string{};
 gpuTextCacheValid=gpu;gpuTextSettings=settings;
 gpuTextDefaultFont=font;gpuTextNational=portrayal.m_bShowNationalTexts;gpuTextFactor=portrayal.m_nTextFactor;
 gpuTextDepthUnit=portrayal.m_nDepthUnitDisplay;gpuTextHeightUnit=portrayal.m_nHeightUnitDisplay;
 gpuTextDip=portrayal.m_dipfactor;gpuTextContentScale=portrayal.m_ContentScaleFactor;
 portrayal.m_isSoundingFontSet=false;portrayal.PrepareForRender();
 // Immutable loaded ENC contours depend on data generation and safety only.
 // Camera transforms and S52 rule invalidation still run for every vector frame.
 bool safetyHit=renderOptimizations.stablePreparation&&safetyCacheValid&&safetyGeometry==geometryGeneration&&cachedSafety==settings.safety&&cachedContours.size()==cells.size();
 if(!safetyHit){safetyCacheValid=false;cachedContours.assign(cells.size(),std::numeric_limits<double>::infinity());for(size_t i=0;i<cells.size();i++)for(auto& f:features){if(run.attached())run.checkpoint();if(f.cell==&cells[i]->model&&f.name=="DEPCNT"){double d;if(GetDoubleAttr(f.object.get(),"VALDCO",d)&&d>=settings.safety)cachedContours[i]=std::min(cachedContours[i],d);}}safetyGeometry=geometryGeneration;cachedSafety=settings.safety;safetyCacheValid=true;preparationReuseStats.safetyBuilds++;}else preparationReuseStats.safetyHits++;
 for(size_t i=0;i<cells.size();i++){if(run.attached())run.checkpoint();auto& cell=cells[i];cell->context.safety_contour=cachedContours[i];toSM_plib(view.clat,view.clon,cell->model.ref_lat,cell->model.ref_lon,&cell->transform.easting_vp_center,&cell->transform.northing_vp_center);}
 // Conditional rules can promote categories or set line priorities. Resolve
 // these before any dependent visibility decision, including offscreen queries.
 for(auto& f:features){if(run.attached())run.checkpoint();normalRenderCheckpoint(RenderAbortBoundary::Portrayal);portrayal.PrepareObjectRules(&f.rules);}
 DetailScope selectionScope(RenderDetail::Selection);
 ensureViewportCoverage();
 std::set<s57chart*> selected;
 for(auto index:frameCoverage.selected){if(run.attached())run.checkpoint();selected.insert(&cells[index]->model);}
 std::vector<s57chart*> selection(selected.begin(),selected.end());
 PreparedSelectionCache<Feature>::Key packetKey;packetKey.geometry=geometryGeneration;packetKey.presentation=presentationGeneration;
 for(auto* cell:selection)packetKey.cells.push_back(reinterpret_cast<std::uintptr_t>(cell));
 if(renderOptimizations.stablePreparation)if(const auto* packet=selectionPackets.find(packetKey)){preparationReuseStats.orderHits++;return *packet;}
 std::vector<Feature*> ordered;for(auto& f:features){if(run.attached())run.checkpoint();if(!selected.count(f.cell))continue;ordered.push_back(&f);}std::stable_sort(ordered.begin(),ordered.end(),[](Feature* a,Feature* b){if(a->rules.LUP->DPRI!=b->rules.LUP->DPRI)return a->rules.LUP->DPRI<b->rules.LUP->DPRI;return a->object->Primitive_type>b->object->Primitive_type;});
 orderedCells=std::move(selection);orderGeometry=geometryGeneration;orderPresentation=presentationGeneration;orderCacheValid=true;preparationReuseStats.orderBuilds++;
 if(renderOptimizations.stablePreparation)if(const auto* packet=selectionPackets.insert(std::move(packetKey),std::move(ordered))){
  // The packet owns only references; release the old oversize scratch so its
  // allocation does not silently double the bounded packet storage.
  std::vector<Feature*>().swap(cachedOrdered);return *packet;
 }
 cachedOrdered=std::move(ordered);return cachedOrdered;
}
void No5ChartRenderer::expandPortrayalBoundsPixels(Feature& f,double x,double y){
 const auto box=f.object->BBObj;if(!box.GetValid()||x<0||y<0)return;
 auto a=portrayal.GetDoublePixFromLLROT(box.GetMinLat(),box.GetMinLon(),0);
 auto b=portrayal.GetDoublePixFromLLROT(box.GetMaxLat(),box.GetMaxLon(),0);
 if(!std::isfinite(a.m_x)||!std::isfinite(a.m_y)||!std::isfinite(b.m_x)||!std::isfinite(b.m_y))return;
 double south,west,north,east;
 portrayal.GetPixPointSingleNoRotate(std::min(a.m_x,b.m_x)-x,std::max(a.m_y,b.m_y)+y,&south,&west);
 portrayal.GetPixPointSingleNoRotate(std::max(a.m_x,b.m_x)+x,std::min(a.m_y,b.m_y)-y,&north,&east);
 const double center=(box.GetMinLon()+box.GetMaxLon())*.5;
 while(west-center>180)west-=360;while(west-center< -180)west+=360;
 while(east-center>180)east-=360;while(east-center< -180)east+=360;
 if(east<west)east+=360;
 LLBBox extent;extent.Set(std::min(south,north),west,std::max(south,north),east);f.object->BBObj.Expand(extent);
}
void No5ChartRenderer::preparePortrayalBounds(const std::vector<Feature*>& ordered,bool gpu){
 StageScope phase(RenderStage::PortrayalBounds);
 const auto textHits=currentProfile.secondaryTextCacheHits,textMisses=currentProfile.secondaryTextCacheMisses,textures=currentProfile.textTexturesCreated;
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};
 // CPU portrayal gets a private bounded canvas. Genuine cached symbol bitmap
 // construction must still draw; only the metric destination is discarded.
 std::unique_ptr<wxBitmap> bitmap;std::unique_ptr<wxMemoryDC> dc;
 if(!gpu){bitmap=std::make_unique<wxBitmap>(view.pix_width,view.pix_height);dc=std::make_unique<wxMemoryDC>(*bitmap);}
 portrayal.ClearTextList();
 {
  PortrayalBoundsScope metrics(dc.get());wxGLContext token;
  for(auto* f:ordered){if(run.attached())run.checkpoint();normalRenderCheckpoint(RenderAbortBoundary::Portrayal);
   if(f->name=="SOUNDG"){
    if(portrayal.GetShowSoundings()){const auto box=portrayal.SoundingSymbolBox();expandPortrayalBoundsPixels(*f,box.width*.5,box.height*.5);}
    continue;
   }
   measuredPortrayalStroke=0;
   if(gpu)portrayal.RenderObjectToGL(token,&f->rules);else portrayal.RenderObjectToDC(dc.get(),&f->rules);
   // Keep the original S52 eligibility contract. Its text/symbol routines
   // expand BBObj themselves, including decorations attached to lines. A
   // blanket stroke-width expansion admits otherwise rejected plain lines
   // just outside the viewport and changes the original portrayal. Support
   // margins for a conservative workset must not redefine S52 visibility.
  }
 }
 // Real rendering gets the unchanged S52 declutter/priority order and fresh
 // LIGHTS declutter state; cached real glyph/symbol resources remain valid.
 portrayal.ClearTextList();portrayal.PrepareForRender();
 if(profileActive){currentProfile.portrayalSecondaryTextHits=currentProfile.secondaryTextCacheHits-textHits;currentProfile.portrayalSecondaryTextMisses=currentProfile.secondaryTextCacheMisses-textMisses;currentProfile.portrayalTextTexturesCreated=currentProfile.textTexturesCreated-textures;}
}
const std::vector<Feature*>& No5ChartRenderer::areaCandidates(const std::vector<Feature*>& ordered){
 if(!renderOptimizations.spatialAreas){areaWorksetLast={};return ordered;}
 SpatialWorkset::Key key;key.geometry=geometryGeneration;key.presentation=presentationGeneration;
 for(auto index:frameCoverage.selected)key.selection.push_back(reinterpret_cast<uintptr_t>(&cells.at(index)->model));
 if(!areaWorkset.matches(key)){
  std::vector<SpatialRecord> records;
  for(size_t i=0;i<ordered.size();++i)if(ordered[i]->object->Primitive_type==GEO_AREA)
   records.push_back({i,SpatialBounds::fromBox(ordered[i]->geometryBounds),true});
  areaWorkset.rebuild(key,std::move(records));areaWorksetBuilds++;
 }
 // Area BBObj was reset to the ENC extent in prepareFrame. The original
 // area entry point performs this same bbox rejection before conditional
 // rule side effects. Text, lights, semantic association and the full object
 // pass stay on the complete original packet.
 if(!areaWorkset.query(key,SpatialBounds::fromBox(portrayal.GetBBox()),areaWorksetOrdinals,areaWorksetLast))return ordered;
 areaWorksetCandidates.clear();areaWorksetCandidates.reserve(areaWorksetOrdinals.size());
 for(auto ordinal:areaWorksetOrdinals){
  if(ordinal>=ordered.size())return ordered; // Conservative stale-order guard.
  areaWorksetCandidates.push_back(ordered[ordinal]);
 }
 return areaWorksetCandidates;
}
void No5ChartRenderer::renderProjections(No5RenderTarget& target,bool prepared){
 renderObjectSelection(target,prepared);
 for(const auto& marker:diagnosticMarkers){auto p=project(marker.latitude,marker.longitude);
  if(std::isfinite(p.m_x)&&std::isfinite(p.m_y)&&abs(p.m_x)<30000&&abs(p.m_y)<30000){
   wxPoint c(wxRound(p.m_x),wxRound(p.m_y));S52color magenta{};magenta.R=255;magenta.G=0;magenta.B=255;
   target.drawLine({c.x-12,c.y},{c.x+12,c.y},magenta,5);
   target.drawLine({c.x,c.y-12},{c.x,c.y+12},magenta,5);
  }}
 auto clip=[&](wxPoint2DDouble& a,wxPoint2DDouble& b){
  if(!std::isfinite(a.m_x)||!std::isfinite(a.m_y)||!std::isfinite(b.m_x)||!std::isfinite(b.m_y))return false;
  const double dx=b.m_x-a.m_x,dy=b.m_y-a.m_y;double t0=0,t1=1;
  const double p[]={-dx,dx,-dy,dy},q[]={a.m_x,double(view.pix_width-1)-a.m_x,a.m_y,double(view.pix_height-1)-a.m_y};
  for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return false;}else{const double t=q[i]/p[i];if(p[i]<0)t0=std::max(t0,t);else t1=std::min(t1,t);if(t0>t1)return false;}}
  b={a.m_x+t1*dx,a.m_y+t1*dy};a={a.m_x+t0*dx,a.m_y+t0*dy};return true;
 };
 const bool red=settings.palette=="NIGHT"||(presentation&&presentation->style.rfind("red",0)==0);
 S52color ink{},halo{};ink.R=red?240:0;ink.G=red?65:112;ink.B=red?65:150;halo.R=red?35:245;halo.G=halo.B=red?0:245;
 auto line=[&](wxPoint2DDouble a,wxPoint2DDouble b,bool dashed){
  if(!clip(a,b))return;const double length=std::hypot(b.m_x-a.m_x,b.m_y-a.m_y);
  if(!dashed){const bool active=routeRole=="ACTIVE";target.drawOverlayLine({wxRound(a.m_x),wxRound(a.m_y)},{wxRound(b.m_x),wxRound(b.m_y)},halo,active?8:6);target.drawOverlayLine({wxRound(a.m_x),wxRound(a.m_y)},{wxRound(b.m_x),wxRound(b.m_y)},ink,active?5:3);return;}
  for(double offset=0;offset<length;offset+=18){const double t=offset/std::max(1.,length),u=std::min(offset+10,length)/std::max(1.,length);
   wxPoint p(wxRound(a.m_x+t*(b.m_x-a.m_x)),wxRound(a.m_y+t*(b.m_y-a.m_y))),q(wxRound(a.m_x+u*(b.m_x-a.m_x)),wxRound(a.m_y+u*(b.m_y-a.m_y)));
   target.drawOverlayLine(p,q,halo,6);target.drawOverlayLine(p,q,ink,3);}
 };
 auto leg=[&](const Projection& a,const Projection& b){
  const double difference=b.longitude-a.longitude;
  if(std::abs(difference)<=180){line(project(a.latitude,a.longitude),project(b.latitude,b.longitude),routeRole=="DRAFT");return;}
  const double longitude=b.longitude+(difference>0?-360:360),seam=difference>0?-180:180;
  if(longitude==a.longitude){line(project(a.latitude,a.longitude),project(b.latitude,a.longitude),routeRole=="DRAFT");return;}
  const double t=(seam-a.longitude)/(longitude-a.longitude);
  const auto merc=[](double latitude){return std::log(std::tan(M_PI/4+latitude*M_PI/360));};
  const double latitude=(2*std::atan(std::exp(merc(a.latitude)+t*(merc(b.latitude)-merc(a.latitude))))-M_PI/2)*180/M_PI;
  line(project(a.latitude,a.longitude),project(latitude,seam),routeRole=="DRAFT");line(project(latitude,-seam),project(b.latitude,b.longitude),routeRole=="DRAFT");
 };
 auto number=[&](wxPoint c,const std::string& value){
  static const unsigned glyph[]={0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
  const int from[][2]={{0,0},{6,0},{6,5},{0,10},{0,5},{0,0},{0,5}},to[][2]={{6,0},{6,5},{6,10},{6,10},{0,10},{0,5},{6,5}};
  int x=c.x+11;for(char digit:value){if(digit<'0'||digit>'9')continue;for(int i=0;i<7;i++)if(glyph[digit-'0']&(1u<<i)){
   wxPoint a(x+from[i][0],c.y-5+from[i][1]),b(x+to[i][0],c.y-5+to[i][1]);target.drawOverlayLine(a,b,halo,4);target.drawOverlayLine(a,b,ink,2);}x+=9;}
 };
 auto markers=[&](const std::vector<Projection>& values,int kind){for(size_t index=0;index<values.size();index++){
  const auto& value=values[index];auto p=project(value.latitude,value.longitude);
  if(!std::isfinite(p.m_x)||!std::isfinite(p.m_y)||p.m_x<-30||p.m_y<-30||p.m_x>view.pix_width+30||p.m_y>view.pix_height+30)continue;
  const int sides=kind==1?(value.direction?3:4):kind==0?(value.direction?5:16):4;const double rotation=value.direction?value.heading-view.rotation:0;
  auto vertex=[&](int i){const double angle=rotation+i*2*M_PI/sides-M_PI/2;return wxPoint(wxRound(p.m_x+10*std::cos(angle)),wxRound(p.m_y+10*std::sin(angle)));};
  for(int i=0;i<sides;i++){target.drawOverlayLine(vertex(i),vertex(i+1),halo,6);target.drawOverlayLine(vertex(i),vertex(i+1),ink,3);}
  if(kind==3)number({wxRound(p.m_x),wxRound(p.m_y)},value.label.empty()?std::to_string(index+1):value.label);
 }};
 {StageScope scope(RenderStage::Overlays);
  for(size_t i=1;i<route.size();i++)leg(route[i-1],route[i]);markers(route,3);markers(waypoints,2);markers(ais,1);markers(ownship,0);
 }

 target.endFrame();
}
void No5ChartRenderer::render(No5RenderTarget& target){
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};run.checkpoint();
 const auto& ordered=prepareFrame(false);
 target.beginFrame(view.pix_width,view.pix_height,color("NODTA"));
 wxBitmap bitmap(view.pix_width,view.pix_height);wxMemoryDC dc(bitmap);auto bg=color("NODTA");dc.SetBackground(wxBrush(wxColour(bg.R,bg.G,bg.B)));dc.Clear();
 preparePortrayalBounds(ordered,false);
 render_canvas_parms canvas;canvas.pix_buff=bitmap.image->rgb.data();canvas.depth=24;canvas.pb_pitch=view.pix_width*3;canvas.x=canvas.y=canvas.lclip=0;canvas.rclip=view.pix_width-1;canvas.width=view.pix_width;canvas.height=view.pix_height;canvas.b_revrgb=false;
 portrayal.m_pdc=&dc;
 const auto& areaObjects=areaCandidates(ordered);
 if(!frameCoverage.masked){
  for(auto f:areaObjects)if(f->object->Primitive_type==GEO_AREA){NO5_DETAIL_CHART_FEATURE(f,Areas);portrayal.RenderAreaToDC(&dc,&f->rules,&canvas);}
 }else{
  // Disjoint patches commute across cells. Within each patch retain the full
  // original S52 area priority order; only one scratch RGB canvas is allocated.
  wxBitmap scratch(view.pix_width,view.pix_height);wxMemoryDC patch(scratch);patch.SetBackground(wxBrush(wxColour(bg.R,bg.G,bg.B)));
  auto patchCanvas=canvas;patchCanvas.pix_buff=scratch.image->rgb.data();
  for(auto cell:frameCoverage.selected){const auto base=catalogue.entries[cell].background?color("DEPDW"):bg;patch.SetBackground(wxBrush(wxColour(base.R,base.G,base.B)));patch.Clear();for(auto f:areaObjects)if(f->object->Primitive_type==GEO_AREA&&coverageCell(*f)==cell){NO5_DETAIL_CHART_FEATURE(f,Areas);portrayal.RenderAreaToDC(&patch,&f->rules,&patchCanvas);}
   for(size_t pixel=0;pixel<frameCoverage.owners.size();pixel++)if(frameCoverage.owners[pixel]==cell+1)std::copy_n(scratch.image->rgb.data()+pixel*3,3,bitmap.image->rgb.data()+pixel*3);
  }
 }
 portrayal.m_pdc=&dc;
 {NO5_DETAIL_PHASE(LinePriority);for(auto& cell:cells){cell->model.m_bLinePrioritySet=false;for(auto p:cell->model.m_pve_vector)p->max_priority=0;for(auto p:cell->model.m_pcs_vector)p->max_priority_cs=0;cell->model.SetLinePriorities();}}
 filtered=0;for(auto f:ordered){
  NO5_DETAIL_CHART_FEATURE(f,Objects);
  const bool anchor=coverageAnchorOwned(*f);
  if(frameCoverage.masked&&f->object->Primitive_type==GEO_POINT&&f->name!="SOUNDG"&&!anchor){NO5_DETAIL_CHART_EVENT(CoverageRejected,f);filtered++;continue;}
  if(frameCoverage.masked)dc.SetPixelOwners(&frameCoverage.owners,int(coverageCell(*f)));
  CoverageTextScope text(portrayal,anchor);portrayal.RenderObjectToDC(&dc,&f->rules);{NO5_DETAIL_PHASE(FilterCount);if(!portrayal.ObjectRenderCheck(&f->rules))filtered++;}
 }
 dc.SetPixelOwners(nullptr,-1);
 stageObjectEligibility();
 target.replaceChartRgb(bitmap.image->rgb);renderProjections(target,true);publishObjectEligibility();
 run.checkpoint();
 markPreparationFrameComplete("cpu_rgba");
}
void No5ChartRenderer::renderGl(No5RenderTarget& target){
#ifdef ocpnUSE_GL
 bool publicationComplete=false;
 try{
 normalRenderCheckpoint(RenderAbortBoundary::Begin);
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};run.checkpoint();
 no5FirstGlError=GL_NO_ERROR;
 target.startRender();
#ifdef NO5_LINE_BATCH_PROBE
 probeSubmissionCounts={};
#endif
 portrayal.m_pdc=nullptr;
 currentProfile.width=view.pix_width;currentProfile.height=view.pix_height;currentProfile.latitude=view.clat;currentProfile.longitude=view.clon;currentProfile.ppm=view.view_scale_ppm;currentProfile.rotation=view.rotation;
 ps52plib=&portrayal;
 ensureViewportCoverage(true);
 if(renderOptimizations.staticChart&&!presentationPending()&&target.restoreChartFrame(chartFrameKey(),view.pix_width,view.pix_height,color("NODTA"))){staticChartReuseStats.hits++;filtered=cachedFiltered;renderProjections(target);publicationComplete=true;run.checkpoint();markPreparationFrameComplete("gles");return;}
 if(renderOptimizations.staticChart)staticChartReuseStats.misses++;
 const auto& ordered=[&]()->const std::vector<Feature*>&{StageScope scope(RenderStage::Prepare);return prepareFrame(true);}();
 normalRenderCheckpoint(RenderAbortBoundary::Prepared);
#ifdef NO5_LINE_BATCH_PROBE
 probeSubmissionCounts.selectedObjects=ordered.size();
#endif
 {StageScope scope(RenderStage::FrameSetup);target.beginFrame(view.pix_width,view.pix_height,color("NODTA"));}
 preparePortrayalBounds(ordered,true);
 wxGLContext token;
 if(frameCoverage.masked){StageScope scope(RenderStage::CoverageUpload);if(profileActive){++currentProfile.coverageWork.stencilUploads;/* Ownership rectangles need no mask texture upload. */}coverageStencil.upload(frameCoverage);}
#ifdef NO5_LINE_BATCH_PROBE
 // The existing S52 BBox is queried immediately before each pass. Global
 // preparation/CS/line-priority work and the original stable order remain.
 // Point padding is unchanged; this is not a narrower symbol/anchor policy.
 auto candidates=[&](bool areas)->const std::vector<Feature*>&{
  auto& result=areas?probeAreaCandidates:probeObjectCandidates;result.clear();
  if(result.capacity()<ordered.size())result.reserve(ordered.size());
  const auto start=ProfileClock::now();const auto cpu=threadCpuMs();unsigned long long input=0,rejected=0;
  for(auto f:ordered){if(areas&&f->object->Primitive_type!=GEO_AREA)continue;input++;if(portrayal.ObjectRenderCheckPos(&f->rules))result.push_back(f);else rejected++;}
  const auto endCpu=threadCpuMs();double wall=std::chrono::duration<double,std::milli>(ProfileClock::now()-start).count();
  auto& p=probeSubmissionCounts;if(areas){p.areaQueryInput=input;p.areaQueryRejected=rejected;p.areaQueryWallMs=wall;p.areaQueryCpuMs=cpu>=0&&endCpu>=cpu?endCpu-cpu:-1;}else{p.objectQueryInput=input;p.objectQueryRejected=rejected;p.objectQueryWallMs=wall;p.objectQueryCpuMs=cpu>=0&&endCpu>=cpu?endCpu-cpu:-1;}
  return result;
 };
#endif
 {StageScope scope(RenderStage::Areas);
 CoveragePassScope clip(frameCoverage.masked?&coverageStencil:nullptr);
 // Seed public ownership with neutral water before S52 geometry. This covers
 // subpixel float tessellation seams without painting any nautical pixel or
 // implying surveyed depths. Land/coast vectors still supply actual geography.
 if(frameCoverage.masked){const auto water=color("DEPDW");for(auto cell:frameCoverage.selected)
  if(catalogue.entries[cell].background)coverageStencil.fill(cell,water.R/255.f,water.G/255.f,water.B/255.f);}
#ifdef NO5_LINE_BATCH_PROBE
 const auto& areaObjects=probeViewportQuery?candidates(true):areaCandidates(ordered);
#else
 const auto& areaObjects=areaCandidates(ordered);
#endif
 for(auto f:areaObjects)if(f->object->Primitive_type==GEO_AREA){
  normalRenderCheckpoint(RenderAbortBoundary::AreaObject);
  NO5_DETAIL_CHART_FEATURE(f,Areas);NO5_DETAIL_GL_SCOPE(f);
  if(frameCoverage.masked)coverageStencil.cell(coverageCell(*f));
#ifdef NO5_LINE_BATCH_PROBE
  probeSubmissionCounts.areaObjectsVisited++;const auto before=no5DrawCalls;
#endif
  portrayal.RenderAreaToGL(token,&f->rules);
#ifdef NO5_LINE_BATCH_PROBE
  if(no5DrawCalls>before)probeSubmissionCounts.areaObjectsWithDraws++;
#endif
 }}
 {StageScope scope(RenderStage::AreaEffect);if(presentation&&presentation->areaEffect){
  std::array<S52color,7> roles{color("DEPDW"),color("DEPMD"),color("DEPMS"),color("DEPVS"),color("LANDA"),color("CHBRN"),color("DEPIT")};
  const auto input=target.beginAreaEffect();
  areaPass.draw(view.pix_width,view.pix_height,roles,input,renderOptimizations.s01Shader,worldUnderlay);
 }}
 {StageScope scope(RenderStage::Land);if(presentation&&(presentation->treatment||presentationRelief)){prepareLand(ordered);if(land&&land->key==requestedLandKey)landPass.draw(*land,presentation->treatment,presentationRelief,color("LANDA"),color("CHBRN"));}}
 // S52 land/depth fills and the area effect are complete. Asynchronous decorative
 // land treatment may still be pending, as in the existing full-image path.
 // Record that explicitly; this preview is never a fully portrayed chart.
 if(target.wantsBaseAreas())target.presentBaseAreas(presentationPending());
 if(target.wantsAreaReuse()&&!retainedFullKey.empty()&&retainedStyleKey==chartFrameKey(false)){
  std::array<wxPoint2DDouble,4> quad;
  for(size_t i=0;i<quad.size();i++)quad[i]=project(retainedCorners[i].latitude,retainedCorners[i].longitude);
  if(target.reuseChartFrame(retainedFullKey,quad)){
   // This composite is deliberately never stored as the next full basis.
   // The owner schedules bounded full checkpoints during motion and a final
   // full path at rest. These partial composites never become a full basis.
   renderProjections(target);publicationComplete=true;run.checkpoint();return;
  }
 }
 {StageScope scope(RenderStage::LinePriority);NO5_DETAIL_PHASE(LinePriority);for(auto& cell:cells){normalRenderCheckpoint(RenderAbortBoundary::LinePriorityCell);cell->model.m_bLinePrioritySet=false;for(auto p:cell->model.m_pve_vector)p->max_priority=0;for(auto p:cell->model.m_pcs_vector)p->max_priority_cs=0;cell->model.SetLinePriorities();}}
 {StageScope scope(RenderStage::Objects);filtered=0;
 CoveragePassScope clip(frameCoverage.masked?&coverageStencil:nullptr);
#ifdef NO5_LINE_BATCH_PROBE
 // Query after the global line-priority/CS pass, which is never culled.
 const auto& objectObjects=probeViewportQuery?candidates(false):ordered;
 if(probeViewportQuery)filtered=probeSubmissionCounts.objectQueryRejected;
#else
 const auto& objectObjects=ordered;
#endif
 for(auto f:objectObjects){
  normalRenderCheckpoint(RenderAbortBoundary::Object);
  NO5_DETAIL_CHART_FEATURE(f,Objects);NO5_DETAIL_GL_SCOPE(f);
  const bool anchor=coverageAnchorOwned(*f);
  if(frameCoverage.masked&&f->object->Primitive_type==GEO_POINT&&f->name!="SOUNDG"&&!anchor){NO5_DETAIL_CHART_EVENT(CoverageRejected,f);filtered++;continue;}
  if(frameCoverage.masked)coverageStencil.cell(coverageCell(*f));
#ifdef NO5_LINE_BATCH_PROBE
  probeSubmissionCounts.objectsVisited++;const auto before=no5DrawCalls;
#endif
  CoverageTextScope text(portrayal,anchor);portrayal.RenderObjectToGL(token,&f->rules);
#ifdef NO5_LINE_BATCH_PROBE
  if(no5DrawCalls>before)probeSubmissionCounts.objectsWithDraws++;
#endif
  DetailScope filter(RenderDetail::FilterCount);{NO5_DETAIL_PHASE(FilterCount);if(!portrayal.ObjectRenderCheck(&f->rules))filtered++;}
 }}
 stageObjectEligibility();
 if(renderOptimizations.staticChart&&!presentationPending()){
  normalRenderCheckpoint(RenderAbortBoundary::FullCache);
  const auto key=chartFrameKey();
  if(target.rememberChartFrame(key)){
   cachedFiltered=filtered;staticChartReuseStats.stores++;
   retainedFullKey=key;retainedStyleKey=chartFrameKey(false);
   // Pixel edges in texture order, using the actual S52 inverse projection.
   retainedCorners={unproject(0,view.pix_height),unproject(view.pix_width,view.pix_height),unproject(0,0),unproject(view.pix_width,0)};
  }
 }
 renderProjections(target,true);publishObjectEligibility();
 publicationComplete=true;
 run.checkpoint(); // After endFrame/capture-End, with GL still on its owner.
 markPreparationFrameComplete("gles");
 }catch(const RenderSuperseded&){target.abandonFrame();abandonPreparedFrame();throw;}
 catch(const BenchmarkRunCancelled&){
  // A run stop before publication rolls back exactly like supersession. The
  // existing post-endFrame check may instead observe cancellation only AFTER
  // a real FULL; that completed publication must not be erased or relabelled.
  if(!publicationComplete&&(!activeRenderEpoch||!activeRenderEpoch->committed())){target.abandonFrame();abandonPreparedFrame();}
  else if(activeRenderEpoch&&activeRenderEpoch->committed())publishObjectEligibility();
  throw;
 }
#else
 throw std::runtime_error("Direct GLES is not built");
#endif
}
void No5ChartRenderer::releaseGlResources(){
 retainedFullKey.clear();retainedStyleKey.clear();
 invalidatePreparation();
#ifdef ocpnUSE_GL
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(cell->model.m_LineVBO_name>0){GLuint id=cell->model.m_LineVBO_name;glDeleteBuffers(1,&id);cell->model.m_LineVBO_name=-1;}
 for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)for(auto& f:cell->arena){auto& object=*f.object;
  releaseAreaGpu(f.object.get());
  if(object.pPolyTessGeo&&object.auxParm0>0&&g_b_EnableVBO&&!object.auxParm1){auto* group=object.pPolyTessGeo->Get_PolyTriGroup_head();if(group&&group->single_buffer){GLuint id=GLuint(object.auxParm0);glDeleteBuffers(1,&id);object.auxParm0=0;}}
 }
 coverageStencil.release();
 areaPass.release();landPass.release();
 gpuTextCacheValid=false;invalidateRules();portrayal.FlushSymbolCaches(ChartCtx(false));
 for(auto* cache:{&portrayal.vector_symbol_cache,&portrayal.lc_vector_symbol_cache}){
  for(auto& pair:*cache){GLuint id=pair.second;glDeleteTextures(1,&id);}cache->clear();
 }
 portrayal.m_texSoundings.Delete();portrayal.m_isSoundingFontSet=false;
 auto& symbols=portrayal.m_chartSymbols;
 if(symbols.rasterSymbolsTexture)glDeleteTextures(1,&symbols.rasterSymbolsTexture);
 symbols.rasterSymbolsTexture=0;symbols.rasterSymbolsTextureSize=wxSize(0,0);
 delete m_TexFontShader;m_TexFontShader=nullptr;
 unloadCShaders();unloadS52Shaders();
#endif
}

void No5ChartRenderer::renderGlRaster(No5RenderTarget& target,int width,int height){
 const auto canonical=view;
 // Reserve drawing temporarily changes only the raster geometry. Cancelling
 // on either leg would kill every asynchronous land job before its next frame.
 // Real camera changes still use viewport's default cancellation above.
 viewport(canonical.clat,canonical.clon,canonical.view_scale_ppm,canonical.rotation,width,height,false);
 try{renderGl(target);}catch(...){
  viewport(canonical.clat,canonical.clon,canonical.view_scale_ppm,canonical.rotation,canonical.pix_width,canonical.pix_height,false);throw;
 }
 viewport(canonical.clat,canonical.clon,canonical.view_scale_ppm,canonical.rotation,canonical.pix_width,canonical.pix_height,false);
}

std::string No5ChartRenderer::navigationBoundsJson()const{
 std::ostringstream out;out<<std::setprecision(std::numeric_limits<double>::max_digits10)<<"{\"generation\":"<<geometryGeneration<<",\"cells\":[";bool first=true;
 if(loaded)for(const auto& cell:catalogue.entries){
  const auto south=cell.extent.GetMinLat(),north=cell.extent.GetMaxLat(),rawWest=cell.extent.GetMinLon(),rawEast=cell.extent.GetMaxLon();
  if(!std::isfinite(south)||!std::isfinite(north)||!std::isfinite(rawWest)||!std::isfinite(rawEast)||south>north||south < -90||north > 90)continue;
  auto normalize=[](double longitude){return std::fmod(std::fmod(longitude+180,360)+360,360)-180;};
  const auto span=rawEast-rawWest;
  const double west=span>=360?-180:normalize(rawWest),east=span>=360?180:normalize(rawEast);
  if(!first)out<<',';first=false;
  out<<"{\"cellId\":\""<<jsonEscape(std::filesystem::path(std::string(cell.path)).stem().string())<<"\",\"south\":"<<south<<",\"north\":"<<north<<",\"west\":"<<west<<",\"east\":"<<east<<'}';
 }
 out<<"]}";return out.str();
}

std::string No5ChartRenderer::query(double x,double y){
 if(!std::isfinite(x)||!std::isfinite(y))return "[]";ps52plib=&portrayal;double lat,lon;portrayal.GetLLFromPix(wxPoint2DDouble(x,y),&lat,&lon);double radius=12/view.view_scale_ppm/111120.0;
 ensureViewportCoverage();
 std::ostringstream out;out<<"[";bool first=true;for(auto& f:features){const auto cell=coverageCell(f);if(frameCoverage.masked&&!frameCoverage.owns(cell,x,y))continue;if(!frameCoverage.masked&&std::find(frameCoverage.selected.begin(),frameCoverage.selected.end(),cell)==frameCoverage.selected.end())continue;if(!f.cell->DoesLatLonSelectObject(lat,lon,radius,f.object.get()))continue;if(!first)out<<",";first=false;out<<"{\"id\":\""<<jsonEscape(f.id)<<"\",\"name\":\""<<jsonEscape(f.name)<<"\",\"attributes\":{";bool field=true;for(auto& [k,v]:f.attributes){if(!field)out<<",";field=false;out<<"\""<<jsonEscape(k)<<"\":\""<<jsonEscape(v)<<"\"";}out<<"}}";}out<<"]";return out.str();
}
std::string No5ChartRenderer::soundingPreparationJson()const{
 const auto& c=soundingPreparationStats;std::ostringstream s;
 s<<"{\"enabled\":"<<(renderOptimizations.soundingsPreparation?"true":"false")
  <<",\"frames\":"<<c.frames<<",\"keyReuses\":"<<c.keyReuses<<",\"keyChanges\":"<<c.keyChanges
  <<",\"containersReused\":"<<c.containersReused<<",\"pointRulesReused\":"<<c.pointRulesReused
  <<",\"containersReleased\":"<<c.containersReleased<<",\"retainedBytes\":"<<c.retainedBytes
  <<",\"retentionBudgetBytes\":16777216,\"budgetFallbacks\":"<<c.budgetFallbacks<<"}";return s.str();
}
std::string No5ChartRenderer::areaWorksetJson()const{
 const auto& c=areaWorksetLast;std::ostringstream s;
 s<<"{\"enabled\":"<<(renderOptimizations.spatialAreas?"true":"false")<<",\"builds\":"<<areaWorksetBuilds
  <<",\"input\":"<<c.inputRecords<<",\"indexed\":"<<c.indexedRecords<<",\"fallback\":"<<c.fallbackRecords
  <<",\"returned\":"<<c.returnedRecords<<",\"rejected\":"<<c.rejectedRecords
  <<",\"nodesVisited\":"<<c.nodesVisited<<",\"boundsTested\":"<<c.entryBoundsTested
  <<",\"storageBytes\":"<<areaWorkset.storageBytes()<<",\"queryFallback\":"<<(c.queryFallback?"true":"false")
  <<",\"revisionMismatch\":"<<(c.revisionMismatch?"true":"false")<<"}";return s.str();
}
std::string No5ChartRenderer::status()const{std::ostringstream s;s<<std::setprecision(std::numeric_limits<double>::max_digits10)<<"{\"objectSelection\":"<<objectSelectionJson()<<",\"presentation\":"<<presentationStatus()<<",\"width\":"<<view.pix_width<<",\"height\":"<<view.pix_height<<",\"dataGeneration\":"<<geometryGeneration<<",\"loaded\":"<<(loaded?"true":"false")<<",\"featureCount\":"<<features.size()<<",\"unsupportedFeatures\":"<<unsupported<<",\"filteredFeatures\":"<<filtered<<",\"latitude\":"<<view.clat<<",\"longitude\":"<<view.clon<<",\"ppm\":"<<view.view_scale_ppm<<",\"rotation\":"<<view.rotation<<",\"chartScaleDenominator\":"<<view.chart_scale<<",\"viewportSelectedCharts\":"<<frameCoverage.selected.size()<<",\"coverageMasked\":"<<(frameCoverage.masked?"true":"false")<<",\"coverageUnknownCells\":"<<frameCoverage.unknownCells<<",\"coverageUncoveredPixels\":"<<frameCoverage.uncoveredPixels<<",\"coverageWidth\":"<<frameCoverage.width<<",\"coverageHeight\":"<<frameCoverage.height<<",\"selectedCharts\":"<<stack.nEntry<<",\"areaWorkset\":"<<areaWorksetJson()<<",\"soundingPreparation\":"<<soundingPreparationJson()<<",\"sencVersion\":201,\"completePortrayal\":false,\"portrayalCore\":\"OpenCPN original S52 CPU/GLES source\",\"message\":\""<<jsonEscape(error)<<"\"}";return s.str();}

void No5ChartRenderer::clearPresentationCaches(){
 gpuTextCacheValid=false;invalidateRules();portrayal.ClearCNSYLUPArray();
 for(auto* rh:{portrayal._symb_sym,portrayal._patt_sym,portrayal._line_sym})for(auto& pair:*rh)if(pair.second)s52plib::ClearRulesCache(pair.second);
 for(auto* cache:{&portrayal.vector_symbol_cache,&portrayal.lc_vector_symbol_cache}){for(auto& pair:*cache){GLuint id=pair.second;glDeleteTextures(1,&id);}cache->clear();}
 portrayal.m_texSoundings.Delete();portrayal.m_isSoundingFontSet=false;
}
void No5ChartRenderer::restorePresentationAtlas(){
 if(!presentation)return;auto& symbols=portrayal.m_chartSymbols;if(symbols.rasterSymbolsTexture)return;
 GLuint texture=0;glGenTextures(1,&texture);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture);
 glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,presentation->atlas.GetWidth(),presentation->atlas.GetHeight(),0,GL_RGBA,GL_UNSIGNED_BYTE,presentation->rgba.data());
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
 if(glGetError()!=GL_NO_ERROR){glDeleteTextures(1,&texture);throw std::runtime_error("Presentation atlas restore failed");}
 symbols.rasterSymbolsTexture=texture;symbols.rasterSymbolsTextureSize=wxSize(presentation->atlas.GetWidth(),presentation->atlas.GetHeight());symbols.rasterSymbols=presentation->atlas;symbols.rasterSymbolsLoadedColorMapNumber=symbols.FindColorTable(presentation->table);
}
bool No5ChartRenderer::applyPresentationProfile(std::shared_ptr<const PresentationPacket> candidate,bool relief,uint64_t generation){
 if(!candidate||generation<=presentationGeneration)return false;
 if(relief&&candidate->mode!="DAY")throw std::invalid_argument("Land relief is available in day profiles");
 if(relief&&candidate->areaEffect)throw std::invalid_argument("S01 preserves R04 without additional land relief");
 if(presentation&&presentation->fingerprint==candidate->fingerprint){
  if(candidate->areaEffect)areaPass.init(renderOptimizations.s01Shader);
  if(candidate->treatment||relief){landPass.init();auto ordered=prepareFrame(true);prepareLand(ordered,true);if(!land)throw std::runtime_error("Land preparation cancelled");landPass.upload(*land,relief);}
  presentation=std::move(candidate);presentationRelief=relief;presentationGeneration=generation;return true;
 }
 auto& symbols=portrayal.m_chartSymbols;
 // Register each added glyph once. Original LUP/Rule objects remain stable.
 for(auto& g:candidate->glyphs)if(!portrayal._symb_sym->count(g.name)){
  if(g.name.rfind("N7",0)!=0&&!(candidate->areaEffect&&g.name.rfind("N5",0)==0))throw std::runtime_error("Unknown presentation glyph");
  auto* rule=static_cast<Rule*>(calloc(1,sizeof(Rule)));if(!rule)throw std::bad_alloc();std::copy_n(g.name.data(),8,rule->name.SYNM);rule->definition.SYDF='R';rule->exposition.SXPO=new wxString("No5 faceted buoy body");rule->vector.SVCT=strdup("");rule->colRef.SCRF=strdup("");portrayal.pAlloc->Add(rule);(*portrayal._symb_sym)[g.name]=rule;
 }
 struct Update {LUPrec* lup;wxString instruction;Rules* rules;};std::vector<Update> updates;GLuint atlas=0;
 try{
  for(auto* container:{portrayal.line_LAC,portrayal.areaPlain_LAC,portrayal.areaSymbol_LAC,portrayal.pointSimple_LAC,portrayal.pointPaper_LAC}){
   auto* array=container->GetLUPArray();for(unsigned i=0;i<array->GetCount();i++){auto* lup=array->Item(i);originalInstructions.emplace(lup,lup->INST);auto found=candidate->instructions.find({int(lup->TNAM),lup->RCID});wxString instruction=found==candidate->instructions.end()?originalInstructions.at(lup):found->second;
    if(instruction==lup->INST)continue;LUPrec temporary=*lup;temporary.INST=instruction;temporary.ruleList=nullptr;portrayal._LUP2rules(&temporary,nullptr);updates.push_back({lup,instruction,temporary.ruleList});
   }
  }
  glActiveTexture(GL_TEXTURE0);glGenTextures(1,&atlas);glBindTexture(GL_TEXTURE_2D,atlas);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,candidate->atlas.GetWidth(),candidate->atlas.GetHeight(),0,GL_RGBA,GL_UNSIGNED_BYTE,candidate->rgba.data());
  glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
  if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Presentation atlas upload failed");
  if(candidate->areaEffect)areaPass.init(renderOptimizations.s01Shader);
  if(candidate->treatment||relief){landPass.init();auto ordered=prepareFrame(true);prepareLand(ordered,true);if(!land)throw std::runtime_error("Land preparation cancelled");landPass.upload(*land,relief);}
  // All fallible resource uploads and parsing precede the frame-boundary commit.
  auto font=candidate->font;
  clearPresentationCaches();
  int table=symbols.FindColorTable(candidate->table);auto* colors=static_cast<colTable*>(symbols.m_colorTables.Item(table));
  for(auto& pair:candidate->colors){colors->colors[pair.first]=pair.second;auto c=pair.second;colors->wxColors[pair.first]=wxColour(c.R,c.G,c.B);}
  for(auto& update:updates){s52plib::DestroyRulesChain(update.lup->ruleList);update.lup->INST=std::move(update.instruction);update.lup->ruleList=update.rules;update.rules=nullptr;}
  // A presentation packet may replace LUP instructions, including the CS
  // family. Reclassify rather than treating the load-time flag as authority.
  for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)for(auto& f:cell->arena)f.reusableSoundingRules=closedSoundingFamily(f);
  for(auto& g:candidate->glyphs){symbols.m_symbolGraphicLocations[g.name]=wxRect(g.x,g.y,g.w,g.h);auto* rule=portrayal._symb_sym->at(g.name);rule->definition.SYDF=g.definition;{rule->pos.symb.pivot_x.SYCL=g.px;rule->pos.symb.pivot_y.SYRW=g.py;rule->pos.symb.bnbox_w.SYHL=g.rw;rule->pos.symb.bnbox_h.SYVL=g.rh;rule->pos.symb.bnbox_x.SBXC=g.ox;rule->pos.symb.bnbox_y.SBXR=g.oy;}}
  no5PlatformInstallFont(std::move(font));presentationHalo=candidate->halo;presentationHaloRevision=candidate->haloRevision;presentationCoastWidth=candidate->coastWidth;
  GLuint old=symbols.rasterSymbolsTexture;symbols.rasterSymbolsTexture=atlas;atlas=0;if(old)glDeleteTextures(1,&old);symbols.rasterSymbols=candidate->atlas;symbols.rasterSymbolsTextureSize=wxSize(candidate->atlas.GetWidth(),candidate->atlas.GetHeight());symbols.rasterSymbolsLoadedColorMapNumber=table;symbols.ColorTableIndex=table;colors->rasterFileName="presentation/"+candidate->style+"-"+candidate->symbols+"/atlas.png";
  presentation=std::move(candidate);presentationRelief=relief;settings.palette=presentation->mode;presentationGeneration=generation;return true;
 }catch(...){if(atlas)glDeleteTextures(1,&atlas);for(auto& update:updates)if(update.rules)s52plib::DestroyRulesChain(update.rules);throw;}
}
std::string No5ChartRenderer::presentationStatus()const{
 std::ostringstream s;s<<"{\"style\":\""<<(presentation?presentation->style:"default")<<"\",\"symbols\":\""<<(presentation?presentation->symbols:"default")<<"\",\"relief\":"<<(presentationRelief?"true":"false")<<",\"generation\":"<<presentationGeneration<<",\"mode\":\""<<settings.palette<<"\",\"fingerprint\":\""<<(presentation?presentation->fingerprint:"")<<"\",\"atlasVersion\":\""<<(presentation?presentation->atlasVersion:"")<<"\",\"fontVersion\":\""<<(presentation?presentation->fontVersion:"")<<"\",\"landPending\":"<<(presentationPending()?"true":"false")<<",\"landProgramLinks\":"<<landPass.links<<",\"landUploads\":"<<landPass.uploads<<",\"landDraws\":"<<landPass.draws<<",\"landGeometryVersion\":"<<geometryGeneration<<",\"landPixels\":"<<(land?land->landPixels:0)<<",\"buildingPixels\":"<<(land?land->buildingPixels:0)<<",\"landPreparationMs\":"<<(land?land->preparationMs:0)<<",\"areaEffect\":"<<(presentation?presentation->areaEffect:0)<<",\"haloRevision\":"<<(presentation?presentation->haloRevision:7)<<",\"areaProgramLinks\":"<<areaPass.links<<",\"areaAllocations\":"<<areaPass.allocations<<",\"areaCopies\":"<<areaPass.copies<<",\"areaDraws\":"<<areaPass.draws<<"}";return s.str();
}
bool No5ChartRenderer::presentationPending()const{return presentation&&(presentation->treatment||presentationRelief)&&(!land||land->key!=requestedLandKey);}
void No5ChartRenderer::waitForPresentationLand(){if(presentation&&(presentation->treatment||presentationRelief)){auto ordered=prepareFrame(true);prepareLand(ordered,true);}}
void No5ChartRenderer::captureWork(bool enabled){
 if(enabled==captureLandWork)return;captureLandWork=enabled;landCaptureSession++;completedLandWork.clear();droppedLandWork=0;
}
void No5ChartRenderer::completeLandTelemetry(bool accepted){
 if(preparingLandTelemetry&&captureLandWork&&preparingLandTelemetry->captureSession==landCaptureSession){
  auto value=*preparingLandTelemetry;value.accepted=accepted;
  if(completedLandWork.size()<64)completedLandWork.push_back(value);else droppedLandWork++;
 }
 preparingLandTelemetry.reset();
}
std::string No5ChartRenderer::landWorkJson(){
 std::ostringstream out;out<<std::fixed<<std::setprecision(6)<<"{\"dropped\":"<<droppedLandWork<<",\"jobs\":[";bool first=true;
 auto metric=[&](const LandWorkTiming& t){out<<"{\"wallMs\":"<<t.wallMs<<",\"cpuMs\":";if(t.cpuMs>=0)out<<t.cpuMs;else out<<"null";out<<",\"calls\":"<<t.calls<<"}";};
 for(auto& t:completedLandWork){if(!first)out<<",";first=false;out<<"{\"jobId\":"<<t.jobId<<",\"width\":"<<t.width<<",\"height\":"<<t.height<<",\"triangles\":"<<t.triangles<<",\"outcome\":\""<<t.outcome<<"\",\"accepted\":"<<(t.accepted?"true":"false")<<",\"workerPhasesNonAdditive\":true,\"geometryHarvest\":";metric(t.geometryHarvest);out<<",\"futureWait\":";metric(t.futureWait);out<<",\"phases\":{";
  for(std::size_t i=0;i<t.phases.size();i++){if(i)out<<",";out<<"\""<<landWorkNames[i]<<"\":";metric(t.phases[i]);}out<<"}}";
 }out<<"]}";completedLandWork.clear();return out.str();
}
void No5ChartRenderer::prepareLand(const std::vector<Feature*>& ordered,bool wait){
 const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};if(run.attached())run.checkpoint();
 std::ostringstream key;key<<std::setprecision(17)<<geometryGeneration<<":"<<view.pix_width<<":"<<view.pix_height<<":"<<view.clat<<":"<<view.clon<<":"<<view.view_scale_ppm<<":"<<view.rotation<<":"<<view.chart_scale<<":"<<portrayal.GetDisplayCategory()<<":"<<portrayal.m_bUseSCAMIN<<":"<<settings.safety<<":"<<settings.shallow<<":"<<settings.deep;
 for(auto* f:ordered){if(run.attached())run.checkpoint();if(f->name=="LNDARE"||f->name=="LAKARE"||f->name=="RIVERS"||f->name=="BUISGL"||f->name=="RUNWAY")if(portrayal.ObjectRenderCheck(&f->rules))key<<":"<<f->id;}
 auto next=key.str();requestedLandKey=next;if(land&&land->key==next)return;
 if(landPreparation.valid()){
  if(landCancelled&&preparingLandKey!=next)landCancelled->store(true);
  if(wait){LandWorkScope scope(preparingLandTelemetry?&preparingLandTelemetry->futureWait:nullptr);landPreparation.wait();}
  if(landPreparation.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
  auto result=landPreparation.get();completeLandTelemetry(result&&result->key==next);if(result&&result->key==next){land=std::move(result);return;}
 }
 auto telemetry=captureLandWork?std::make_shared<LandWorkTelemetry>():nullptr;
 if(telemetry){telemetry->jobId=++landJobId;telemetry->captureSession=landCaptureSession;telemetry->width=view.pix_width;telemetry->height=view.pix_height;}
 std::vector<LandTriangle> triangles;
 {LandWorkScope harvest(telemetry?&telemetry->geometryHarvest:nullptr);
 for(auto* f:ordered){if(run.attached())run.checkpoint();normalRenderCheckpoint(RenderAbortBoundary::LandObject);int kind=f->name=="LNDARE"?0:f->name=="LAKARE"||f->name=="RIVERS"?1:f->name=="BUISGL"?2:f->name=="RUNWAY"?3:-1;if(kind<0||f->object->Primitive_type!=GEO_AREA||!portrayal.ObjectRenderCheck(&f->rules))continue;auto& obj=*f->object;if(!obj.pPolyTessGeo)continue;if(!obj.pPolyTessGeo->IsOk())obj.pPolyTessGeo->BuildDeferredTess();auto* group=obj.pPolyTessGeo->Get_PolyTriGroup_head();if(!group)continue;
  for(auto* t=group->tri_prim_head;t;t=t->p_next){if(run.attached())run.checkpoint();auto point=[&](int i){double e=group->data_type==DATA_TYPE_DOUBLE?t->p_vertex[2*i]:group->data_type==DATA_TYPE_SHORT?reinterpret_cast<short*>(t->p_vertex)[2*i]:reinterpret_cast<float*>(t->p_vertex)[2*i];double n=group->data_type==DATA_TYPE_DOUBLE?t->p_vertex[2*i+1]:group->data_type==DATA_TYPE_SHORT?reinterpret_cast<short*>(t->p_vertex)[2*i+1]:reinterpret_cast<float*>(t->p_vertex)[2*i+1];double x=(e*obj.x_rate*group->sfactor+obj.x_origin-f->rules.sm_transform_parms->easting_vp_center)*view.view_scale_ppm,y=-(n*obj.y_rate*group->sfactor+obj.y_origin-f->rules.sm_transform_parms->northing_vp_center)*view.view_scale_ppm;double c=cos(view.rotation),s=sin(view.rotation);return std::array<double,2>{std::clamp(x*c-y*s+view.pix_width/2.0,-30000.,30000.),std::clamp(x*s+y*c+view.pix_height/2.0,-30000.,30000.)};};
   auto add=[&](int a,int b,int c){if(run.attached())run.checkpoint();auto p=point(a),q=point(b),r=point(c);triangles.push_back({{p[0],p[1],q[0],q[1],r[0],r[1]},static_cast<unsigned char>(kind),frameCoverage.masked?uint8_t(coverageCell(*f)+1):uint8_t(0)});};
   if(t->type==PTG_TRIANGLES){for(int i=0;i+2<t->nVert;i+=3)add(i,i+1,i+2);}else if(t->type==PTG_TRIANGLE_FAN){for(int i=1;i+1<t->nVert;i++)add(0,i,i+1);}else if(t->type==PTG_TRIANGLE_STRIP){for(int i=0;i+2<t->nVert;i++)add(i,i+1,i+2);}
  }
 }
 }
 if(telemetry)telemetry->triangles=triangles.size();preparingLandTelemetry=telemetry;
 preparingLandKey=next;landCancelled=std::make_shared<std::atomic<bool>>(false);auto cancellation=landCancelled;int w=view.pix_width,h=view.pix_height;
 const auto owners=frameCoverage.masked?std::make_shared<const std::vector<uint8_t>>(frameCoverage.owners):std::shared_ptr<const std::vector<uint8_t>>{};
 landPreparation=std::async(std::launch::async,[w,h,next,triangles=std::move(triangles),cancellation,telemetry,owners,run]()mutable{return prepareLandResources(w,h,next,std::move(triangles),cancellation,telemetry,owners,run);});
 if(wait){std::shared_ptr<const LandResources> result;{LandWorkScope scope(telemetry?&telemetry->futureWait:nullptr);result=landPreparation.get();}completeLandTelemetry(bool(result));land=std::move(result);}
}
void No5ChartRenderer::applyObjectFilters(const ObjectFilters& value){
 if(value.category!="BASE"&&value.category!="STANDARD"&&value.category!="ALL"&&value.category!="CUSTOM")throw std::invalid_argument("Unknown S52 category");
 std::set<std::string> known;
 for(unsigned i=0;i<portrayal.pOBJLArray->GetCount();i++){
  auto* item=static_cast<OBJLElement*>(portrayal.pOBJLArray->Item(i));known.insert(std::string(item->OBJLName,6));
 }
 for(const auto& name:value.hidden)if(!known.count(name))throw std::invalid_argument("Unknown S52 object class: "+name);
 portrayal.SetDisplayCategory(value.category=="BASE"?DISPLAYBASE:value.category=="STANDARD"?STANDARD:value.category=="CUSTOM"?MARINERS_STANDARD:OTHER);
 portrayal.ClearNoshow();
 if(value.category=="CUSTOM")for(unsigned i=0;i<portrayal.pOBJLArray->GetCount();i++){
  auto* item=static_cast<OBJLElement*>(portrayal.pOBJLArray->Item(i));item->nViz=value.hidden.count(std::string(item->OBJLName,6))?0:1;
 }
 portrayal.SetShowSoundings(value.soundings);portrayal.SetShowS57Text(value.text);portrayal.SetShowS57ImportantTextOnly(value.important);
 portrayal.SetShowAtonText(value.aton);portrayal.SetShowLdisText(value.light);portrayal.m_bShowMeta=value.meta;
 objectFilters=value;objectFilterRevision++;gpuTextCacheValid=false;invalidateRules();invalidatePreparation();
}
std::string No5ChartRenderer::readObjectFilters()const{
 const auto& f=objectFilters;std::ostringstream out;out<<"{\"category\":\""<<f.category<<"\",\"revision\":"<<objectFilterRevision<<",\"hiddenClasses\":[";
 bool first=true;for(const auto& name:f.hidden){if(!first)out<<',';first=false;out<<'"'<<jsonEscape(name)<<'"';}
 out<<"],\"soundings\":"<<(f.soundings?"true":"false")<<",\"text\":"<<(f.text?"true":"false")<<",\"importantTextOnly\":"<<(f.important?"true":"false")
  <<",\"atonText\":"<<(f.aton?"true":"false")<<",\"lightText\":"<<(f.light?"true":"false")<<",\"metaObjects\":"<<(f.meta?"true":"false")<<'}';return out.str();
}

}
