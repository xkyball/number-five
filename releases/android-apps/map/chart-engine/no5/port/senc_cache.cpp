#include "senc_cache.hpp"
#include "o_senc.h"
#include "s57registrar_mgr.h"
#include "gdal/cpl_string.h"
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <chrono>
extern S57ClassRegistrar* g_poRegistrar;
namespace no5 {
namespace {
std::mutex registryMutex;
struct RegistrarScope {
 s57RegistrarMgr manager;
 explicit RegistrarScope(const std::string& data):manager(data,nullptr){
  if(!g_poRegistrar)throw std::runtime_error("S57 registry missing");m_pRegistrarMan=&manager;
 }
 ~RegistrarScope(){m_pRegistrarMan=nullptr;}
};
struct Cursor {
 const uint8_t* p;size_t left;
 void take(size_t n){if(n>left)throw std::runtime_error("Truncated OSENC payload");p+=n;left-=n;}
 template<class T>T read(){T v;const auto* q=p;take(sizeof(T));memcpy(&v,q,sizeof(T));return v;}
 void floats(size_t n){for(size_t i=0;i<n;i++)if(!std::isfinite(read<float>()))throw std::runtime_error("Invalid OSENC coordinate");}
 void doubles(size_t n){for(size_t i=0;i<n;i++)if(!std::isfinite(read<double>()))throw std::runtime_error("Invalid OSENC coordinate");}
 void string(){if(!left||p[left-1]!=0||memchr(p,0,left-1))throw std::runtime_error("Invalid OSENC string");take(left);}
};
void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
uint32_t count(Cursor& c){auto n=c.read<uint32_t>();require(n<=10000000,"OSENC count exceeds allocation limit");return n;}
void extent(Cursor& c){c.doubles(4);}
}
uint64_t SencCache::validate(const std::string& path,ChartTableEntry* table,uint64_t geometryLimit){
 if(table){table->auxiliary.clear();table->exclusions.clear();}
 std::ifstream in(path,std::ios::binary|std::ios::ate);require(bool(in),"OSENC file missing");auto total=in.tellg();require(total>=8&&total<=268435456,"OSENC size outside supported limit");
 // Reader buffers plus record-specific expansion. Edge tuples can create
 // three line elements/connectors, map nodes and assembled vertices; reserve
 // these by tuple count instead of multiplying unrelated raster-free payload.
 uint64_t admission=uint64_t(total)*2+65536;
 if(admission>geometryLimit)throw GeometryBudgetExceeded("OSENC geometry exceeds loading reservation");
 std::vector<uint8_t> bytes(static_cast<size_t>(total));in.seekg(0);in.read(reinterpret_cast<char*>(bytes.data()),bytes.size());require(bool(in),"OSENC read failed");Cursor file{bytes.data(),bytes.size()};
 bool version=false,haveExtent=false,haveScale=false,feature=false,geometry=false;size_t objects=0;std::set<uint16_t> headers,featureIds;bool edgeTable=false,nodeTable=false;
 while(file.left){auto type=file.read<uint16_t>();auto length=file.read<uint32_t>();require(length>=6&&length-6<=file.left,"Invalid OSENC record length");Cursor c{file.p,length-6};file.take(length-6);
  if(!version)require(type==HEADER_SENC_VERSION,"Missing OSENC version header");
  if(type<=8)require(headers.insert(type).second,"Duplicate OSENC header");
  switch(type){
   case HEADER_SENC_VERSION:require(c.read<uint16_t>()==201,"Only plaintext OSENC 201 is supported");version=true;break;
   case HEADER_CELL_NAME:case HEADER_CELL_PUBLISHDATE:case HEADER_CELL_UPDATEDATE:case HEADER_CELL_SENCCREATEDATE:{if(table&&type==HEADER_CELL_PUBLISHDATE)table->editionDate=std::string(reinterpret_cast<const char*>(c.p),c.left?c.left-1:0);c.string();break;}
   case HEADER_CELL_EDITION:case HEADER_CELL_UPDATE:c.read<uint16_t>();break;
   case HEADER_CELL_NATIVESCALE:{auto scale=c.read<uint32_t>();require(scale>0&&scale<=100000000,"Invalid OSENC native scale");if(table)table->scale=scale;haveScale=true;break;}
   case CELL_EXTENT_RECORD:{double v[8];for(auto& x:v)x=c.read<double>();require(std::all_of(v,v+8,[](double x){return std::isfinite(x);}),"Invalid OSENC extent");require(v[2]>=v[6]&&v[3]<=v[7]&&v[2]<=85&&v[6]>=-85&&v[3]>=-180&&v[7]<=180,"Unsupported OSENC extent");if(table)table->extent.Set(v[6],v[3],v[2],v[7]);haveExtent=true;break;}
   case CELL_COVR_RECORD:case CELL_NOCOVR_RECORD:{auto n=count(c);require(n>=3,"Invalid OSENC coverage");std::vector<float> poly;poly.reserve(size_t(n)*2);for(size_t i=0;i<size_t(n)*2;i++){auto x=c.read<float>();require(std::isfinite(x),"Invalid OSENC coverage coordinate");poly.push_back(x);}if(table)(type==CELL_COVR_RECORD?table->auxiliary:table->exclusions).push_back(std::move(poly));break;}
   case FEATURE_ID_RECORD:{require(haveExtent&&haveScale,"OSENC geometry precedes metadata");require(!feature||geometry,"OSENC object missing geometry");auto code=c.read<uint16_t>();if(m_pRegistrarMan)require(!m_pRegistrarMan->getFeatureAcronym(code).empty(),"Unknown OSENC object class");require(featureIds.insert(c.read<uint16_t>()).second,"Duplicate OSENC feature ID");require(c.read<uint8_t>()<=GEO_AREA,"Invalid OSENC primitive");feature=true;geometry=false;objects++;break;}
   case FEATURE_ATTRIBUTE_RECORD:{require(feature&&!geometry,"OSENC attribute outside feature");c.read<uint16_t>();auto t=c.read<uint8_t>();if(t==0)c.read<int32_t>();else if(t==1){require(c.left&&c.left%4==0&&c.left/4<=65536,"Invalid OSENC integer list");while(c.left)c.read<int32_t>();}else if(t==2)c.doubles(1);else if(t==3){require(c.left&&c.left%8==0&&c.left/8<=65536,"Invalid OSENC real list");c.doubles(c.left/8);}else if(t==4)c.string();else throw std::runtime_error("OSENC attribute type outside retained reader");break;}
   case FEATURE_GEOMETRY_RECORD_POINT:require(feature&&!geometry,"Duplicate OSENC geometry");c.doubles(2);geometry=true;break;
   case FEATURE_GEOMETRY_RECORD_MULTIPOINT:require(feature&&!geometry,"Duplicate OSENC geometry");extent(c);c.floats(size_t(count(c))*3);geometry=true;break;
   case FEATURE_GEOMETRY_RECORD_LINE:{require(feature&&!geometry,"Duplicate OSENC geometry");extent(c);auto edges=count(c);admission+=uint64_t(edges)*1024;c.take(size_t(edges)*12);geometry=true;break;}
   case FEATURE_GEOMETRY_RECORD_AREA:{require(feature&&!geometry,"Duplicate OSENC geometry");extent(c);auto nc=count(c),nt=count(c),ne=count(c);require(nc&&nt,"Empty OSENC polygon");for(unsigned i=0;i<nc;i++)count(c);for(unsigned i=0;i<nt;i++){auto t=c.read<uint8_t>();auto n=count(c);require(t>=4&&t<=6&&n>=3&&(t!=4||n%3==0),"Invalid OSENC triangles");c.doubles(4);c.floats(size_t(n)*2);}admission+=uint64_t(ne)*1024;c.take(size_t(ne)*12);geometry=true;break;}
   case VECTOR_EDGE_NODE_TABLE_RECORD:{require(!edgeTable,"Duplicate OSENC edge table");edgeTable=true;auto n=count(c);admission+=uint64_t(n)*256;std::set<int32_t> ids;for(unsigned i=0;i<n;i++){auto id=c.read<int32_t>();require(id>0&&ids.insert(id).second,"Duplicate OSENC edge");c.floats(size_t(count(c))*2);}break;}
   case VECTOR_CONNECTED_NODE_TABLE_RECORD:{require(!nodeTable,"Duplicate OSENC node table");nodeTable=true;auto n=count(c);admission+=uint64_t(n)*256;std::set<int32_t> ids;for(unsigned i=0;i<n;i++){auto id=c.read<int32_t>();require(id>0&&ids.insert(id).second,"Duplicate OSENC node");c.floats(2);}break;}
   default:throw std::runtime_error("Unknown OSENC record");
  }
  admission+=uint64_t(length)*4+512+(type==FEATURE_ID_RECORD?4096:0);
  if(admission>geometryLimit)throw GeometryBudgetExceeded("OSENC geometry exceeds loading reservation");
  require(c.left==0,"Unexpected OSENC payload length");
 }
 require(version&&haveExtent&&haveScale&&objects&&geometry,"Incomplete OSENC cell");
 if(table){auto& b=table->extent;table->preparation.geometryAdmissionBytes=admission;table->coverage={float(b.GetMinLat()),float(b.GetMinLon()),float(b.GetMinLat()),float(b.GetMaxLon()),float(b.GetMaxLat()),float(b.GetMaxLon()),float(b.GetMaxLat()),float(b.GetMinLon())};}
 return admission;
}
std::vector<std::unique_ptr<S57Obj>> SencCache::load(const std::string& source,const std::string& data,const std::string& destination,ChartTableEntry& table,s57chart& model,bool reuse,const PreparedIdentity* identity,uint64_t geometryLimit){
 using Clock=std::chrono::steady_clock;const auto totalStarted=Clock::now();table=ChartTableEntry{};auto& report=table.preparation;
 auto elapsed=[](Clock::time_point start){return std::chrono::duration<double,std::milli>(Clock::now()-start).count();};
 std::lock_guard<std::mutex> lock(registryMutex);RegistrarScope registrar(data);Osenc senc;senc.lockCR.lock();
 table.path=source;table.filename=std::filesystem::path(source).filename().string();struct stat st{};if(stat(source.c_str(),&st)==0)table.fileTime=st.st_mtime;
 std::string input=source;auto extension=std::filesystem::path(source).extension();
 std::unique_ptr<PreparedDirectoryLock> directoryLock;
 if(extension==".000"){
  if(identity){require(identity->source==source&&!identity->key.empty(),"Prepared source identity mismatch");report.key=identity->key;report.sourceBytes=identity->sourceBytes;report.keyMs=identity->keyMs;}
  else{const auto keyStarted=Clock::now();report.key=PreparedCache::sourceKey(source,data,&report);report.keyMs=elapsed(keyStarted);}
  const auto directory=std::filesystem::path(destination).parent_path()/"prepared-public-v1";
  directoryLock=std::make_unique<PreparedDirectoryLock>(directory.string());
  const auto prepared=(directory/(report.key+".senc")).string();
  report.path=prepared;const auto validationStarted=Clock::now();bool valid=false;
  if(reuse&&PreparedCache::matches(prepared,report.key))try{validate(prepared,nullptr,geometryLimit);valid=true;}catch(const GeometryBudgetExceeded&){throw;}catch(const std::exception&){}
  report.validationMs=elapsed(validationStarted);
  if(valid){input=prepared;report.reused=true;}else{
  const auto conversionStarted=Clock::now();
  std::string date,edition="0";uint16_t updates=0;DDFModule metadata;require(metadata.Open(source.c_str(),TRUE),"S57 metadata open failed");while(auto record=metadata.ReadRecord()){if(record->FindField("DSPM"))table.scale=record->GetIntSubfield("DSPM",0,"CSCL",0);if(record->FindField("DSID")){date=record->GetStringSubfield("DSID",0,"ISDT",0);edition=record->GetStringSubfield("DSID",0,"EDTN",0);}}
  require(table.scale>0,"S57 native scale missing");
  std::string updatedDate=date;
  // Fail on update gaps. S57Reader applies the same colocated sequential updates.
  bool missing=false;for(int i=1;i<=999;i++){char ext[5];snprintf(ext,sizeof(ext),".%03d",i);auto update=std::filesystem::path(source);update.replace_extension(ext);if(!std::filesystem::exists(update)){missing=true;continue;}require(!missing,"S57 update gap");updates=i;DDFModule metadata;require(metadata.Open(update.c_str(),TRUE),"S57 update metadata open failed");bool found=false;while(auto record=metadata.ReadRecord())if(record->FindField("DSID")){updatedDate=record->GetStringSubfield("DSID",0,"ISDT",0);found=true;break;}require(found&&!updatedDate.empty(),"S57 update issue date missing");}
  S57Reader reader(source.c_str());reader.SetClassBased(g_poRegistrar);char** options=nullptr;options=CSLSetNameValue(options,S57O_UPDATES,"ON");options=CSLSetNameValue(options,S57O_RETURN_LINKAGES,"ON");reader.SetOptions(options);CSLDestroy(options);require(reader.Open(FALSE),"S57 open failed");require(reader.Ingest()==0,"S57 updates failed");
  OGREnvelope bounds;require(reader.GetExtent(&bounds,TRUE)==OGRERR_NONE,"S57 extent missing");table.extent.Set(bounds.MinY,bounds.MinX,bounds.MaxY,bounds.MaxX);model.ref_lat=(bounds.MinY+bounds.MaxY)/2;model.ref_lon=(bounds.MinX+bounds.MaxX)/2;senc.setRefLocn(model.ref_lat,model.ref_lon);
  senc.m_extent={float(bounds.MinY),float(bounds.MinX),float(bounds.MaxY),float(bounds.MaxX)};
  // Match the float Extent centroid used by upstream ingest200.
  senc.setRefLocn((double(senc.m_extent.NLAT)+senc.m_extent.SLAT)/2,(double(senc.m_extent.ELON)+senc.m_extent.WLON)/2);
  for(auto primitive:{RCNM_VI,RCNM_VC,RCNM_VE,RCNM_VF})reader.AddFeatureDefn(S57GenerateVectorPrimitiveFeatureDefn(primitive,reader.GetOptionFlags()));
  int classes[MAX_CLASSES]{};reader.CollectClassList(classes,MAX_CLASSES);for(int i=0;i<MAX_CLASSES;i++)if(classes[i]){auto def=S57GenerateObjectClassDefn(g_poRegistrar,i,reader.GetOptionFlags());if(def)reader.AddFeatureDefn(def);}
  // Preserve native coverage polygons in the original OSENC coverage records.
  while(auto inputFeature=reader.ReadNextFeature()){
   std::unique_ptr<OGRFeature> feature(inputFeature);auto g=feature->GetGeometryRef();
   if(!g||std::string(feature->GetDefnRef()->GetName())!="M_COVR"||wkbFlatten(g->getGeometryType())!=wkbPolygon)continue;
   const auto category=feature->GetFieldAsInteger("CATCOV");if(category!=1&&category!=2)continue;
   auto ring=static_cast<OGRPolygon*>(g)->getExteriorRing();require(ring&&ring->getNumPoints()>=3,"S57 coverage ring missing");std::vector<float> p;
   for(int i=0;i<ring->getNumPoints();i++){p.push_back(ring->getY(i));p.push_back(ring->getX(i));}
   (category==1?table.auxiliary:table.exclusions).push_back(std::move(p));
  }
  senc.m_nCOVREntries=table.auxiliary.size();senc.m_pCOVRTablePoints=static_cast<int*>(calloc(senc.m_nCOVREntries,sizeof(int)));senc.m_pCOVRTable=static_cast<float**>(calloc(senc.m_nCOVREntries,sizeof(float*)));for(size_t i=0;i<table.auxiliary.size();i++){auto& poly=table.auxiliary[i];senc.m_pCOVRTablePoints[i]=poly.size()/2;senc.m_pCOVRTable[i]=static_cast<float*>(malloc(poly.size()*sizeof(float)));memcpy(senc.m_pCOVRTable[i],poly.data(),poly.size()*sizeof(float));}
  senc.m_nNoCOVREntries=table.exclusions.size();senc.m_pNoCOVRTablePoints=static_cast<int*>(calloc(senc.m_nNoCOVREntries,sizeof(int)));senc.m_pNoCOVRTable=static_cast<float**>(calloc(senc.m_nNoCOVREntries,sizeof(float*)));for(size_t i=0;i<table.exclusions.size();i++){auto& poly=table.exclusions[i];senc.m_pNoCOVRTablePoints[i]=poly.size()/2;senc.m_pNoCOVRTable[i]=static_cast<float*>(malloc(poly.size()*sizeof(float)));memcpy(senc.m_pNoCOVRTable[i],poly.data(),poly.size()*sizeof(float));}
  int edgeId=0;while(auto edge=reader.ReadVector(edgeId,RCNM_VE)){senc.m_vector_helper_hash[edge->GetFieldAsInteger("RCID")]=edgeId;delete edge;edgeId++;}
  std::filesystem::create_directories(std::filesystem::path(prepared).parent_path());std::string temp=PreparedCache::temporaryPath(prepared);
  struct RemoveTemporary {std::string path;~RemoveTemporary(){std::error_code ignored;std::filesystem::remove(path,ignored);}} removeTemporary{temp};Osenc_outstreamFile output;require(output.Open(temp),"Cannot create OSENC geometry cache");
  require(senc.WriteHeaderRecord200(&output,HEADER_SENC_VERSION,uint16_t(201))&&senc.WriteHeaderRecord200(&output,HEADER_CELL_NAME,std::filesystem::path(source).stem().string())&&senc.WriteHeaderRecord200(&output,HEADER_CELL_PUBLISHDATE,date)&&senc.WriteHeaderRecord200(&output,HEADER_CELL_EDITION,uint16_t(std::stoi(edition)))&&senc.WriteHeaderRecord200(&output,HEADER_CELL_UPDATEDATE,updatedDate)&&senc.WriteHeaderRecord200(&output,HEADER_CELL_UPDATE,updates)&&senc.WriteHeaderRecord200(&output,HEADER_CELL_NATIVESCALE,uint32_t(table.scale))&&senc.CreateCovrRecords(&output),"OSENC header write failed");
  reader.Rewind();while(auto inputFeature=reader.ReadNextFeature()){std::unique_ptr<OGRFeature> feature(inputFeature);if(feature->GetGeometryRef()){require(feature->GetFID()>=0&&feature->GetFID()<=65535,"S57 feature ID exceeds OSENC 201 range");require(senc.CreateSENCRecord200(feature.get(),&output,1,&reader),"OSENC feature write failed");}}
  senc.CreateSENCVectorEdgeTableRecord200(&output,&reader);senc.CreateSENCVectorConnectedTableRecord200(&output,&reader);output.Close();require(output.IsOk(),"OSENC cache close failed");validate(temp,nullptr,geometryLimit);
  const auto recheckStarted=Clock::now();require(PreparedCache::sourceKey(source,data)==report.key,"S57 source changed during preparation");const auto recheckMs=elapsed(recheckStarted);report.keyMs+=recheckMs;
  std::filesystem::rename(temp,prepared);PreparedCache::publishIdentity(prepared,report.key);input=prepared;
  report.conversionMs=elapsed(conversionStarted)-recheckMs;
  }
 }else{
  require(extension==".senc"||extension==".S57","Input must be public S57 or plaintext OSENC 201");
  if(std::filesystem::path(source).parent_path().filename()=="prepared-public-v1"&&std::filesystem::is_regular_file(std::filesystem::path(source).parent_path()/".lock"))directoryLock=std::make_unique<PreparedDirectoryLock>(std::filesystem::path(source).parent_path().string());
 }
 const auto ingestStarted=Clock::now();validate(input,&table,geometryLimit);

 S57ObjVector objects;VE_ElementVector edges;VC_ElementVector nodes;
 auto result=senc.ingest200(input,&objects,&edges,&nodes);
 std::vector<std::unique_ptr<S57Obj>> owned;for(auto object:objects)owned.emplace_back(object);
 for(auto edge:edges){if(edge->nCount){double emin=1e20,emax=-1e20,nmin=1e20,nmax=-1e20;for(unsigned i=0;i<edge->nCount;i++){emin=std::min(emin,double(edge->pPoints[2*i]));emax=std::max(emax,double(edge->pPoints[2*i]));nmin=std::min(nmin,double(edge->pPoints[2*i+1]));nmax=std::max(nmax,double(edge->pPoints[2*i+1]));}double a,b,c,d;auto& e=senc.getReadExtent();double lat=(double(e.NLAT)+e.SLAT)/2,lon=(double(e.ELON)+e.WLON)/2;fromSM_plib(emin,nmin,lat,lon,&a,&b);fromSM_plib(emax,nmax,lat,lon,&c,&d);edge->edgeBBox.Set(a,b,c,d);}model.m_ve_hash[edge->index]=edge;}
 for(auto node:nodes)model.m_vc_hash[node->index]=node;
 require(result==SENC_NO_ERROR,"OSENC geometry read failed");auto& e=senc.getReadExtent();model.ref_lat=(double(e.NLAT)+e.SLAT)/2;model.ref_lon=(double(e.ELON)+e.WLON)/2;
 report.ingestMs=elapsed(ingestStarted);report.path=input;report.cacheBytes=std::filesystem::file_size(input);
 if(extension==".000"&&report.reused&&!identity){const auto recheckStarted=Clock::now();require(PreparedCache::sourceKey(source,data)==report.key,"S57 source changed during prepared reuse");report.keyMs+=elapsed(recheckStarted);}
 if(directoryLock&&extension==".000"){
  const auto identity=input+".identity";if(std::filesystem::exists(identity))std::filesystem::last_write_time(identity,std::filesystem::file_time_type::clock::now());
  PreparedCache::prune(std::filesystem::path(input).parent_path().string(),input);
 }
 report.totalMs=elapsed(totalStarted);return owned;
}
}
