#include "cell_metadata.hpp"
#include "prepared_converter.hpp"
#include "iso8211.h"
#include <iomanip>
#include <locale>
#include <sstream>
namespace no5 {
ValidatedCellMetadata validatePublicCellMetadata(const std::string& source,const std::string& data,const std::string& destination){
 const auto extension=std::filesystem::path(source).extension();
 const bool plaintext=extension==".senc"||extension==".S57";
 // A finished SENC is read directly and never enters prepared-public-v1. Its
 // metadata still needs a content identity bound to the bytes actually parsed.
 const auto contentKey=plaintext?PreparedCache::fileHash(source):std::string{};
 ValidatedCellMetadata value;s57chart model;
 auto objects=SencCache::load(source,data,destination,value.table,model);
 if(objects.empty())throw std::runtime_error("S57 cell has no geometry");
 value.objectCount=objects.size();
 if(plaintext){
  if(PreparedCache::fileHash(source)!=contentKey)throw std::runtime_error("SENC source changed during metadata validation");
  value.table.preparation.key=contentKey;
 }
 // Prepared OSENC 201 does not retain INTU. Read only the native DSID header,
 // also on a prepared-cache hit; do not ingest or interpret geometry twice.
 if(extension==".000"){
  DDFModule header;if(!header.Open(source.c_str(),TRUE))throw std::runtime_error("S57 metadata open failed");
  while(auto record=header.ReadRecord())if(record->FindField("DSID")){
   const int usage=record->GetIntSubfield("DSID",0,"INTU",0);
   if(usage>=1&&usage<=6)value.usage=usage;
   else if(usage>=7&&usage<=9){
    // Separate WSV Inland profile; INTU still comes from the real DSID,
    // never from a renamed NOAA cell or an inferred filename usage.
    const auto name=std::filesystem::path(source).stem().string();
    const bool wsvName=name.size()==8&&name.rfind("1W",0)==0&&name[2]==char('0'+usage)&&
      name.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")==std::string::npos;
    const char* edition=record->GetStringSubfield("DSID",0,"PRED",0);
    const std::string productEdition=edition?edition:"";
    if(wsvName&&productEdition=="2.4"&&record->GetIntSubfield("DSID",0,"PRSP",0)==10&&
      record->GetIntSubfield("DSID",0,"PROF",0)==1&&record->GetIntSubfield("DSID",0,"AGEN",0)==7984)
      value.usage=usage;
   }
   break;
  }
 }
 return value;
}
std::string ValidatedCellMetadata::json() const {
 std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(17);
 out<<"{\"metadataVersion\":1,\"parserVersion\":\"no5-s57-osenc201-metadata-v1\",\"parserIdentity\":\""<<No5PreparedConverter
    <<"\",\"preparedSourceKey\":\""<<table.preparation.key<<"\",\"coverageVersion\":1,\"nativeScale\":"<<table.scale<<",\"usage\":";
 if(usage)out<<usage;else out<<"null";
 out<<",\"bounds\":{\"south\":"<<table.extent.GetMinLat()<<",\"west\":"<<table.extent.GetMinLon()<<",\"north\":"<<table.extent.GetMaxLat()<<",\"east\":"<<table.extent.GetMaxLon()<<"}";
 auto rings=[&](const char* name,const std::vector<std::vector<float>>& values){
  out<<",\""<<name<<"\":[";bool first=true;
  for(const auto& ring:values){if(!first)out<<',';first=false;out<<'[';
   for(size_t i=0;i<ring.size();i+=2){if(i)out<<',';out<<'['<<double(ring.at(i))<<','<<double(ring.at(i+1))<<']';}out<<']';}out<<']';
 };
 rings("coverage",table.auxiliary);rings("exclusions",table.exclusions);
 out<<",\"capabilities\":{\"exteriorRingsOnly\":true,\"polygonHoles\":false,\"multiPolygons\":false,\"wrappedDateline\":false,\"latitudeLimit\":85,\"coordinatePrecision\":\"float32\"}}";
 return out.str();
}
}
