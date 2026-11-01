#include "chart.hpp"
#include "port/senc_cache.hpp"
#include "port/sha256.hpp"
#include "mygeom.h"
#include <algorithm>
#include <filesystem>
#include <set>
#include <sstream>
#ifdef ocpnUSE_GL
#include <EGL/egl.h>
#include "port/no5_area_gpu.hpp"
#endif

namespace no5 {
namespace {
bool digest(const std::string& s){return s.size()==64&&s.find_first_not_of("0123456789abcdef")==std::string::npos;}
bool contains(const std::vector<std::string>& values,const std::string& key){return std::find(values.begin(),values.end(),key)!=values.end();}
bool piSource(const ResidentSource& source){return source.provider=="no5-pi-map-cache-v1";}
bool backgroundSource(const ResidentSource& source){return source.provider=="natural-earth-background-v1";}
void validateSource(const ResidentSource& source){
 if(piSource(source)||backgroundSource(source)){
  if((piSource(source)&&(source.product.empty()||source.product.size()>800||source.product.find('\0')!=std::string::npos||
     source.licenseHost.empty()||source.licenseHost.size()>64||source.licenseHost.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-")!=std::string::npos||
     source.licenseHost.find_first_of("_.-")==0))||
     (backgroundSource(source)&&(!source.product.empty()||!source.licenseHost.empty()||source.name.rfind("NO5BG_",0)!=0||source.edition!=1||source.update!=0))||
     source.name.empty()||source.name.size()>80||source.name.find_first_of("_.-")==0||
     source.name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-")!=std::string::npos||
     source.edition<1||source.edition>65535||source.update<0||source.update>65535||source.order<0||source.files.size()!=2||!digest(source.sourceKey))throw std::invalid_argument("Invalid private resident source descriptor");
  const auto senc=std::filesystem::path(source.files[0].path),provenance=std::filesystem::path(source.files[1].path);
  if(!senc.is_absolute()||!provenance.is_absolute()||senc.filename()!=source.name+".senc"||provenance.filename()!=source.name+".json"||
     senc.parent_path().filename()!="cells"||provenance.parent_path().filename()!="provenance"||
     senc.parent_path().parent_path()!=provenance.parent_path().parent_path())throw std::invalid_argument("Private resident source path mismatch");
  for(const auto& file:source.files)if(file.path.find('\0')!=std::string::npos||!digest(file.sha256))throw std::invalid_argument("Invalid private resident source pin");
  if(residentSourceKey(source)!=source.sourceKey)throw std::invalid_argument("Private resident source identity mismatch");
  return;
 }
 const bool supportedProvider=source.provider=="noaa-s57"||(source.provider=="wsv-inland-s57"&&source.name.size()==8&&source.name.rfind("1W",0)==0&&source.name[2]>='7'&&source.name[2]<='9');
 if(!supportedProvider||source.name.empty()||source.name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")!=std::string::npos||source.edition<0||source.update<0||source.update>999||source.order<0||source.files.size()!=size_t(source.update+1)||!digest(source.sourceKey))throw std::invalid_argument("Invalid resident source descriptor");
 const auto parent=std::filesystem::path(source.files.front().path).parent_path();
 for(size_t i=0;i<source.files.size();++i){char extension[5];snprintf(extension,sizeof(extension),".%03u",unsigned(i));const auto path=std::filesystem::path(source.files[i].path);
  if(path.parent_path()!=parent||path.filename()!=source.name+extension||!digest(source.files[i].sha256))throw std::invalid_argument("Resident source chain mismatch");
 }
 if(residentSourceKey(source)!=source.sourceKey)throw std::invalid_argument("Resident source identity mismatch");
}
std::set<std::string> validateCatalog(const std::string& generation,std::vector<ResidentSource>& sources,ResidentBudget budget){
 if(!digest(generation)||budget.cells==0||budget.cells>MAXSTACK||budget.loadingBytes==0||budget.loadingBytes>budget.bytes)throw std::invalid_argument("Invalid resident catalog/budget");
 std::set<std::string> keys,names;std::set<int> order;
 for(const auto& source:sources){validateSource(source);if(!keys.insert(source.sourceKey).second||!names.insert(source.name).second||!order.insert(source.order).second)throw std::invalid_argument("Duplicate resident source/order");}
 std::sort(sources.begin(),sources.end(),[](const auto& a,const auto& b){return a.order<b.order;});
 return keys;
}
void verifySource(const ResidentSource& source,const BenchmarkRunSnapshot& run){
 // Check this one base/update chain, never rehash the full catalog per camera.
 for(const auto& file:source.files)if(PreparedCache::fileHash(file.path,nullptr,run)!=file.sha256)throw std::runtime_error("Resident source bytes changed");
 if(piSource(source)||backgroundSource(source))return;
 auto path=std::filesystem::path(source.files.front().path);
 for(int i=source.update+1;i<=999;++i){char extension[5];snprintf(extension,sizeof(extension),".%03d",i);path.replace_extension(extension);if(std::filesystem::exists(path))throw std::runtime_error("Resident source has unpinned update");}
}
// Measured immutable geometry payloads/capacities, with conservative container
// node overhead. Frame buffers, shared S52 library, allocator overhead and
// conversion scratch are separate from this geometry cache quota.
uint64_t geometryBytes(const Cell& cell){
 uint64_t n=sizeof(Cell)+cell.path.capacity()+cell.sourceKey.capacity()+cell.preparedKey.capacity()+cell.arena.capacity()*sizeof(Feature);
 n+=cell.model.m_vbo_byte_length+cell.model.m_pcs_vector.capacity()*sizeof(connector_segment*)+cell.model.m_pve_vector.capacity()*sizeof(VE_Element*);
 n+=cell.model.m_pcs_vector.size()*sizeof(connector_segment);
 for(const auto& pair:cell.model.m_ve_hash)if(pair.second)n+=sizeof(VE_Element)+64+uint64_t(pair.second->nCount)*2*sizeof(float);
 for(const auto& pair:cell.model.m_vc_hash)if(pair.second)n+=sizeof(VC_Element)+64+2*sizeof(float);
 n+=(cell.floating.capacity()+cell.rigid.capacity())*sizeof(void*);
 for(const auto& f:cell.arena){const auto& obj=*f.object;n+=sizeof(S57Obj)+f.id.capacity()+f.name.capacity()+obj.n_attr*6;
  for(const auto& a:f.attributes)n+=96+a.first.capacity()+a.second.capacity();
  if(obj.attVal){n+=sizeof(*obj.attVal)+obj.attVal->capacity()*sizeof(S57attVal*);for(auto* value:*obj.attVal)n+=sizeof(S57attVal)+(value->valType==OGR_STR?strlen(static_cast<const char*>(value->value))+1:value->valType==OGR_REAL?sizeof(double):sizeof(int));}
  if(obj.geoPt)n+=uint64_t(obj.npt)*sizeof(pt);
  if(obj.geoPtz)n+=uint64_t(obj.npt)*3*sizeof(double);
  if(obj.geoPtMulti)n+=uint64_t(obj.npt)*2*sizeof(double);
  if(obj.m_lsindex_array)n+=uint64_t(obj.m_n_lsindex)*3*sizeof(int);
  for(auto* element=obj.m_ls_list;element;element=element->next)n+=sizeof(line_segment_element);
  if(obj.pPolyTessGeo){n+=sizeof(PolyTessGeo);auto* group=obj.pPolyTessGeo->Get_PolyTriGroup_head();if(group){n+=sizeof(PolyTriGroup)+uint64_t(group->nContours)*sizeof(int)+std::max(0,group->single_buffer_size);for(auto* triangle=group->tri_prim_head;triangle;triangle=triangle->p_next){n+=sizeof(TriPrim);if(!group->bsingle_alloc)n+=uint64_t(triangle->nVert)*2*(group->data_type==DATA_TYPE_DOUBLE?sizeof(double):group->data_type==DATA_TYPE_SHORT?sizeof(short):sizeof(float));}}}
 }
 n+=cell.table.coverage.capacity()*sizeof(float);
 for(const auto* rings:{&cell.table.auxiliary,&cell.table.exclusions}){n+=rings->capacity()*sizeof(std::vector<float>);for(const auto& ring:*rings)n+=ring.capacity()*sizeof(float);}
 return n;
}
bool needsContext(const Cell& cell){
 if(cell.model.m_LineVBO_name>0)return true;
 for(const auto& f:cell.arena)if(f.object->pPolyTessGeo&&f.object->auxParm0>0)return true;
#ifdef ocpnUSE_GL
 for(const auto& f:cell.arena)if(hasAreaGpu(f.object.get()))return true;
#endif
 return false;
}
}
std::string residentSourceKey(const ResidentSource& source){
 std::ostringstream json;json<<"{\"provider\":\""<<jsonEscape(source.provider)<<"\"";
 if(piSource(source))json<<",\"product\":\""<<jsonEscape(source.product)<<"\",\"licenseHost\":\""<<jsonEscape(source.licenseHost)<<"\"";
 json<<",\"cell\":\""<<jsonEscape(source.name)<<"\",\"edition\":"<<source.edition<<",\"update\":"<<source.update<<",\"files\":[";
 for(size_t i=0;i<source.files.size();++i){if(i)json<<',';json<<"{\"name\":\""<<jsonEscape(std::filesystem::path(source.files[i].path).filename().string())<<"\",\"sha256\":\""<<source.files[i].sha256<<"\"}";}json<<"]}";
 Sha256 h;h.update(json.str());return h.hex();
}
void No5ChartRenderer::resetResidency(){residentEnabled=false;retiredCells.clear();residencySources.clear();residencyCatalog.clear();residencyPolicy.clear();residencyDemand={};residencyMessage.clear();residentLoading=false;residentLoadingReservation=0;residentReservationBounds.clear();residencyClock=residencyRevision=residencyLoads=residencyRetains=residencyEvictions=residencyFailures=0;residencyPeakCells=0;residencyPeakBytes=0;}
void No5ChartRenderer::residentCatalog(const std::string& generation,std::vector<ResidentSource> sources,ResidentBudget budget){
 if(residentLoading)throw std::logic_error("Catalog replacement during owner load");
 const auto keys=validateCatalog(generation,sources,budget);
 // Legacy eager data cannot be silently promoted without verified source keys.
 if(!residentEnabled&&!cells.empty())throw std::logic_error("Close eager set before entering resident mode");
 const auto current=residentStatus();if(current.resident>budget.cells||current.chargedBytes>budget.bytes)throw std::invalid_argument("Budget below current pinned/resident set");
 if(residentEnabled&&generation==residencyCatalog){
  if(sources.size()!=residencySources.size())throw std::invalid_argument("Changed catalog reuses generation");
  for(size_t i=0;i<sources.size();++i)if(sources[i].sourceKey!=residencySources[i].sourceKey||sources[i].order!=residencySources[i].order)throw std::invalid_argument("Changed catalog reuses generation");
  residencyBudget=budget;return;
 }
 invalidatePreparation();datedFeatures.clear();portrayal.ClearTextList();features.clear();
 for(size_t i=0;i<retiredCells.size();)if(keys.count(retiredCells[i]->sourceKey)){cells.push_back(std::move(retiredCells[i]));retiredCells.erase(retiredCells.begin()+i);}else ++i;
 for(size_t i=0;i<cells.size();)if(!keys.count(cells[i]->sourceKey)){retiredCells.push_back(std::move(cells[i]));cells.erase(cells.begin()+i);}else ++i;
 residentReservationBounds.clear();
 residentEnabled=true;residencyCatalog=generation;residencySources=std::move(sources);residencyBudget=budget;residencyPolicy.clear();residencyDemand={};residencyMessage.clear();
 rebuildResidentViews();
 // Changed keys are no longer required. Pinned old generations remain charged
 // until released; none may be drawn as complete under the new catalog.
}
void No5ChartRenderer::residentActivate(const std::string& generation,std::vector<ResidentSource> sources,ResidentBudget budget){
 if(residentLoading)throw std::logic_error("Catalog activation during owner load");
 // Malformed metadata must leave the previous eager chart and camera intact.
 // No file IO occurs here: source bytes are admitted by the later one-cell job.
 validateCatalog(generation,sources,budget);
 if(!residentEnabled)close();
 residentCatalog(generation,std::move(sources),budget);
}
void No5ChartRenderer::residentPolicy(const std::string& policy){
 if(!residentEnabled||policy.empty())throw std::invalid_argument("Invalid resident policy authority");
 if(policy==residencyPolicy)return;
 residencyPolicy=policy;residencyDemand={};residencyMessage.clear();
}
void No5ChartRenderer::residentPolicyRestore(const std::string& policy){
 if(!residentEnabled||residentLoading)throw std::logic_error("Policy restore requires idle resident owner");
 residencyPolicy=policy;residencyDemand={};residencyMessage.clear();
}
bool No5ChartRenderer::releaseRetiredResident(const std::string& expectedCatalog){
 if(!residentEnabled||expectedCatalog!=residencyCatalog)throw std::invalid_argument("Stale resident release checkpoint");
 if(residentLoading)throw std::logic_error("Release during owner resident load");
 // Refuse before mutating any arena. A deferred checkpoint cannot authorize IO deletion.
 for(const auto& cell:retiredCells)if(cell->pins)return false;
 while(!retiredCells.empty())evictResident(0,true);
 return true;
}
bool No5ChartRenderer::resetResidentGeometry(const std::string& expectedCatalog,ResidentBudget budget,bool emptyGeometry){
 if(!residentEnabled||expectedCatalog!=residencyCatalog)throw std::invalid_argument("Stale resident budget checkpoint");
 if(residentLoading)throw std::logic_error("Budget reset during owner resident load");
 auto sources=residencySources;validateCatalog(expectedCatalog,sources,budget);
 const auto held=residentStatus();
 if(emptyGeometry){for(const auto* set:{&cells,&retiredCells})for(const auto& cell:*set)if(cell->pins)return false;}
 else if(held.resident>budget.cells||held.chargedBytes>budget.bytes)return false;
 if(emptyGeometry){
  while(!retiredCells.empty())evictResident(0,true);
  while(!cells.empty())evictResident(0);
  if(landCancelled)landCancelled->store(true);land.reset();requestedLandKey.clear();
 }
 residencyBudget=budget;
 // Keep demand/policy authority: the existing single loader repopulates the same target.
 residencyPeakCells=cells.size()+retiredCells.size();residencyPeakBytes=residentStatus().chargedBytes;
 residencyLoads=residencyRetains=residencyEvictions=residencyFailures=0;residencyMessage.clear();
 return true;
}
bool No5ChartRenderer::residentDemand(ResidentDemand demand){
 if(!residentEnabled||demand.catalogGeneration!=residencyCatalog||demand.cameraKey.empty()||demand.policyKey.empty()||demand.cameraRevision==0)return false;
 if(!residencyPolicy.empty()&&demand.policyKey!=residencyPolicy)return false;
 if(residencyDemand.cameraRevision&&demand.cameraRevision<residencyDemand.cameraRevision)return false;
 if(demand.cameraRevision==residencyDemand.cameraRevision&&demand.cameraKey!=residencyDemand.cameraKey)return false;
 std::set<std::string> unique;for(const auto* list:{&demand.required,&demand.prefetch})for(const auto& key:*list){if(!unique.insert(key).second||std::none_of(residencySources.begin(),residencySources.end(),[&](const auto& s){return s.sourceKey==key;}))return false;}
 for(auto& cell:cells)if(contains(demand.required,cell->sourceKey)||contains(demand.prefetch,cell->sourceKey)){cell->lastUse=++residencyClock;residencyRetains++;}
 residencyPolicy=demand.policyKey;residencyDemand=std::move(demand);residencyMessage.clear();return true;
}
bool No5ChartRenderer::pinResident(const std::string& key){for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(cell->sourceKey==key){cell->pins++;return true;}return false;}
void No5ChartRenderer::unpinResident(const std::string& key){for(auto* set:{&cells,&retiredCells})for(auto& cell:*set)if(cell->sourceKey==key){if(!cell->pins)throw std::logic_error("Resident pin underflow");--cell->pins;return;}throw std::invalid_argument("Unknown resident pin");}
void No5ChartRenderer::evictResident(size_t index,bool retired){
 auto& set=retired?retiredCells:cells;auto& cell=*set.at(index);if(cell.pins)throw std::logic_error("Eviction of pinned cell");
#ifdef ocpnUSE_GL
 if(needsContext(cell)&&eglGetCurrentContext()==EGL_NO_CONTEXT)throw std::logic_error("Cell GL resources require current owner context");
 for(const auto& f:cell.arena)releaseAreaGpu(f.object.get());
#endif
 invalidatePreparation();datedFeatures.clear();portrayal.ClearTextList();gpuTextCacheValid=false;
 // Clear ephemeral conditional children before unlinking the arena. The
 // shared immutable LUPs remain owned by portrayal.
 for(auto& f:cell.arena){auto& r=f.rules;if(r.mps){if(r.mps->cs_rules){for(auto chain:*r.mps->cs_rules)s52plib::DestroyRulesChain(chain);delete r.mps->cs_rules;}free(r.mps);r.mps=nullptr;}while(r.child){auto child=r.child;r.child=child->next;delete child->obj;portrayal.DestroyLUP(child->LUP);delete child;}r.next=nullptr;}
 for(auto& priority:cell.model.razRules)for(auto& head:priority)head=nullptr;
#ifdef ocpnUSE_GL
 if(cell.model.m_LineVBO_name>0){GLuint id=cell.model.m_LineVBO_name;glDeleteBuffers(1,&id);cell.model.m_LineVBO_name=-1;}
#endif
 set.erase(set.begin()+index);residencyEvictions++;residencyRevision++;rebuildResidentViews();
}
ResidentStep No5ChartRenderer::residentStep(){
 if(!residentEnabled||residencyDemand.catalogGeneration!=residencyCatalog)return ResidentStep::Idle;
 if(residentLoading)throw std::logic_error("Reentrant resident load");
 auto findCell=[&](const std::string& key){return std::find_if(cells.begin(),cells.end(),[&](const auto& cell){return cell->sourceKey==key;});};
 std::string next;for(const auto* list:{&residencyDemand.required,&residencyDemand.prefetch}){for(const auto& key:*list)if(findCell(key)==cells.end()){next=key;break;}if(!next.empty())break;}
 if(next.empty()){residencyMessage.clear();return ResidentStep::Idle;}
 // Required cells are never evicted to cycle through an oversized demand.
 // Remove unused cells first, then prefetch, ordered by last owner use.
 auto candidate=[&](){size_t best=cells.size();for(size_t i=0;i<cells.size();++i){const auto& c=*cells[i];if(c.pins||contains(residencyDemand.required,c.sourceKey))continue;if(best==cells.size()||std::make_pair(contains(residencyDemand.prefetch,c.sourceKey),c.lastUse)<std::make_pair(contains(residencyDemand.prefetch,cells[best]->sourceKey),cells[best]->lastUse))best=i;}return best;};
 auto source=*std::find_if(residencySources.begin(),residencySources.end(),[&](const auto& s){return s.sourceKey==next;});
 const auto generation=residencyCatalog;const auto run=benchmarkRunControl?benchmarkRunControl->snapshot():BenchmarkRunSnapshot{};
 uint64_t reservation=residencyBudget.loadingBytes;
 // Plain SENC already has a conservative, record-validated expansion bound.
 // Reuse that exact bound, tied to the complete verified source chain, instead
 // of reserving the maximum 128 MiB for every small cell. Validation scratch is
 // not resident geometry; no S57 arena is built before the unchanged quota gate.
 try{
  run.checkpoint();verifySource(source,run);
  if(piSource(source)||backgroundSource(source)){
   auto found=residentReservationBounds.find(source.sourceKey);
   if(found==residentReservationBounds.end()){
    const auto bound=SencCache::validate(source.files.front().path,nullptr,residencyBudget.loadingBytes);
    verifySource(source,run);found=residentReservationBounds.emplace(source.sourceKey,bound).first;
   }
   reservation=found->second;
   if(!reservation||reservation>residencyBudget.loadingBytes)throw GeometryBudgetExceeded("OSENC geometry exceeds loading reservation");
  }
 }catch(const GeometryBudgetExceeded& e){residencyMessage=e.what();return ResidentStep::Blocked;}
 catch(const std::exception& e){residencyFailures++;residencyMessage=e.what();return dynamic_cast<const BenchmarkRunCancelled*>(&e)?ResidentStep::Cancelled:ResidentStep::Failed;}
 while(cells.size()+retiredCells.size()+1>residencyBudget.cells||residentStatus().chargedBytes>residencyBudget.bytes-reservation){
  const auto old=std::find_if(retiredCells.begin(),retiredCells.end(),[](const auto& c){return !c->pins;});
  if(old!=retiredCells.end()){evictResident(size_t(old-retiredCells.begin()),true);continue;}
  auto victim=candidate();if(victim==cells.size()){residencyMessage="Resident budget or pins prevent loading; required coverage incomplete";return ResidentStep::Blocked;}evictResident(victim);}
 residentLoading=true;residentLoadingReservation=reservation;residencyPeakCells=std::max(residencyPeakCells,cells.size()+retiredCells.size()+1);residencyPeakBytes=std::max(residencyPeakBytes,residentStatus().chargedBytes);
 struct Loading {bool& value;uint64_t& bytes;~Loading(){value=false;bytes=0;}} loading{residentLoading,residentLoadingReservation};
 try{
  run.checkpoint();verifySource(source,run);auto identity=PreparedCache::identities({source.files.front().path},dataDirectory,1,run).front();run.checkpoint();
  ps52plib=&portrayal;auto cell=loadCell(source.files.front().path,identity,source.sourceKey,backgroundSource(source),reservation);run.checkpoint();
  if(cell->arena.empty())throw std::runtime_error("S57 contains no objects with presentation rules");
  cell->sourceKey=source.sourceKey;const auto measured=geometryBytes(*cell);cell->chargedBytes=measured*2+65536;
  if(cell->chargedBytes>reservation||cell->chargedBytes>cell->table.preparation.geometryAdmissionBytes)throw std::runtime_error("Geometry measurement exceeded verified reservation");
#ifndef __ANDROID__
  if(residentBeforeCommitForTests)residentBeforeCommitForTests();
#endif
  verifySource(source,run);auto checked=PreparedCache::identities({source.files.front().path},dataDirectory,1,run).front();run.checkpoint();
  if(generation!=residencyCatalog||checked.key!=identity.key)throw std::runtime_error("Resident source/catalog changed before commit");
  // Camera revision is intentionally absent from acceptance: useful immutable
  // cell preparation survives continuous interaction.
  if(!contains(residencyDemand.required,next)&&!contains(residencyDemand.prefetch,next)){residencyMessage="Prepared source no longer needed";return ResidentStep::Cancelled;}
  cell->lastUse=++residencyClock;cells.push_back(std::move(cell));
  residencyLoads++;residencyRevision++;rebuildResidentViews();residencyMessage.clear();return ResidentStep::Loaded;
 }catch(const GeometryBudgetExceeded& e){residencyMessage=e.what();return ResidentStep::Blocked;}catch(const std::exception& e){residencyFailures++;residencyMessage=e.what();return dynamic_cast<const BenchmarkRunCancelled*>(&e)?ResidentStep::Cancelled:ResidentStep::Failed;}
}
ResidentStatus No5ChartRenderer::residentStatus()const{
 ResidentStatus status;status.enabled=residentEnabled;if(!residentEnabled)return status;status.catalogGeneration=residencyCatalog;status.cameraKey=residencyDemand.cameraKey;status.policyKey=residencyPolicy;status.cameraRevision=residencyDemand.cameraRevision;status.contentRevision=residencyRevision;status.message=residencyMessage;
 status.budget=residencyBudget;status.resident=cells.size()+retiredCells.size();status.loading=residentLoading?1:0;status.loadingBytes=residentLoading?residentLoadingReservation:0;status.chargedBytes=status.loadingBytes;
 for(const auto* set:{&cells,&retiredCells})for(const auto& cell:*set){if(set==&cells)status.sources.push_back(cell->sourceKey);status.chargedBytes+=cell->chargedBytes;status.geometryBytes+=cell->chargedBytes? (cell->chargedBytes-65536)/2:0;status.pinned+=cell->pins?1:0;
  status.geometries.push_back({cell->sourceKey,cell->sourceKey,cell->chargedBytes,cell->table.preparation.geometryAdmissionBytes,cell->pins>0,set==&cells});}
 status.required=residencyDemand.required.size();for(const auto& key:residencyDemand.required)if(!contains(status.sources,key))status.missing++;
 status.complete=residentEnabled&&residencyDemand.catalogGeneration==residencyCatalog&&status.missing==0&&!residentLoading;
 status.peakCells=residencyPeakCells;status.peakChargedBytes=residencyPeakBytes;status.loads=residencyLoads;status.retains=residencyRetains;status.evictions=residencyEvictions;status.failures=residencyFailures;return status;
}
std::string ResidentStatus::json()const{
 std::ostringstream out;out<<"{\"enabled\":"<<(enabled?"true":"false")<<",\"complete\":"<<(complete?"true":"false")<<",\"catalogGeneration\":\""<<jsonEscape(catalogGeneration)<<"\",\"cameraKey\":\""<<jsonEscape(cameraKey)<<"\",\"policyKey\":\""<<jsonEscape(policyKey)<<"\",\"cameraRevision\":"<<cameraRevision<<",\"contentRevision\":"<<contentRevision<<",\"resident\":"<<resident<<",\"loading\":"<<loading<<",\"pinned\":"<<pinned<<",\"required\":"<<required<<",\"missing\":"<<missing<<",\"chargedBytes\":"<<chargedBytes<<",\"geometryBytes\":"<<geometryBytes<<",\"loadingBytes\":"<<loadingBytes<<",\"peakCells\":"<<peakCells<<",\"peakChargedBytes\":"<<peakChargedBytes<<",\"loads\":"<<loads<<",\"retains\":"<<retains<<",\"evictions\":"<<evictions<<",\"failures\":"<<failures<<",\"message\":\""<<jsonEscape(message)<<"\",\"sources\":[";for(size_t i=0;i<sources.size();++i){if(i)out<<',';out<<'"'<<sources[i]<<'"';}out<<"],\"geometries\":[";
 for(size_t i=0;i<geometries.size();++i){if(i)out<<',';const auto& g=geometries[i];out<<"{\"geometryId\":\""<<g.geometryId<<"\",\"sourceKey\":\""<<g.sourceKey<<"\",\"chargedBytes\":"<<g.chargedBytes<<",\"pinned\":"<<(g.pinned?"true":"false")<<",\"reusableForCatalog\":"<<(g.reusableForCatalog?"true":"false")<<",\"reservationBoundBytes\":";if(g.reservationBoundBytes)out<<g.reservationBoundBytes;else out<<"null";out<<'}';}out<<"],\"budget\":";
 if(enabled)out<<"{\"cells\":"<<budget.cells<<",\"bytes\":"<<budget.bytes<<",\"loadingBytes\":"<<budget.loadingBytes<<'}';else out<<"null";out<<'}';return out.str();
}
}
