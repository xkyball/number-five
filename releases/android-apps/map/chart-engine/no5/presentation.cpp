// GPL-2.0-or-later. Runtime adapter for the supplied R07 resources and selected S01/R04.
#include "presentation.hpp"
#include <filesystem>
namespace no5 {
thread_local int presentationHalo=0, presentationHaloRevision=7, presentationCoastWidth=1;
static bool validId(const std::string& value){return !value.empty()&&value.size()<48&&std::all_of(value.begin(),value.end(),[](char c){return (c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-';});}
std::shared_ptr<const PresentationPacket> PresentationPacket::load(const std::string& data,const std::string& style,const std::string& symbols){
 const bool s01=style=="lagoon-sculpt"&&symbols=="r04";
 if(!validId(style)||((symbols!="default"&&symbols!="big"&&symbols!="no5")&&!s01)||(style=="lagoon-sculpt"&&!s01))throw std::invalid_argument("Invalid presentation selection");
 auto p=std::make_shared<PresentationPacket>();auto dir=std::filesystem::path(data)/"presentation"/(style+"-"+symbols);
 pugi::xml_document xml;if(!xml.load_file((dir/"presentation.xml").c_str()))throw std::runtime_error("Presentation resources unavailable");
 auto root=xml.child("presentation");p->style=root.attribute("style").value();p->symbols=root.attribute("symbols").value();p->mode=root.attribute("mode").value();p->table=root.attribute("table").value();
 p->fingerprint=root.attribute("fingerprint").value();p->fontVersion=root.attribute("fontHash").value();p->atlasVersion=root.attribute("atlasHash").value();
 p->treatment=root.attribute("treatment").as_int();p->halo=root.attribute("halo").as_int();p->coastWidth=root.attribute("coastWidth").as_int();
 p->areaEffect=root.attribute("areaEffect").as_int(0);p->haloRevision=root.attribute("haloRevision").as_int(7);
 if(s01?(p->areaEffect!=1||p->treatment!=0||p->halo!=1||p->haloRevision!=4||p->mode!="DAY"||p->coastWidth!=3):(p->areaEffect!=0||p->haloRevision!=7))throw std::runtime_error("Invalid S01/R04 effect contract");
 if(p->style!=style||p->symbols!=symbols||(p->mode!="DAY"&&p->mode!="DUSK")||!((p->mode=="DAY"&&p->table=="DAY_BRIGHT")||(p->mode=="DUSK"&&p->table=="DUSK"))||p->fingerprint.size()!=64||p->fontVersion.size()!=64||p->atlasVersion.size()!=64||p->treatment<0||p->treatment>2||p->halo<0||p->halo>2||(p->coastWidth!=1&&p->coastWidth!=3)||(p->mode!="DAY"&&p->treatment))throw std::runtime_error("Invalid presentation contract");
 for(auto c:root.child("colors").children("color")){std::string key=c.attribute("name").value();if(key.size()!=5)throw std::runtime_error("Invalid S52 color role");S52color value{};std::copy_n(key.c_str(),5,value.colName);for(auto role:{"r","g","b"})if(c.attribute(role).as_int(-1)<0||c.attribute(role).as_int()>255)throw std::runtime_error("Invalid role RGB");value.R=c.attribute("r").as_int();value.G=c.attribute("g").as_int();value.B=c.attribute("b").as_int();p->colors.emplace(key,value);}
 for(const char* required:{"LANDA","CHBRN","NODTA","CHBLK","CHRED","CHGRN","CHYLW","DEPSC","SNDG1","SNDG2"})if(!p->colors.count(required))throw std::runtime_error("Incomplete S52 role palette");
 wxImage image;if(!image.LoadFile((dir/"atlas.png").string())||!image.HasAlpha()||image.w>4096||image.h>4096||size_t(image.w)*image.h>4*1024*1024)throw std::runtime_error("Invalid or oversized symbol atlas");
 p->rgba.resize(size_t(image.w)*image.h*4);for(size_t i=0;i<image.alpha.size();i++){std::copy_n(&image.rgb[i*3],3,&p->rgba[i*4]);p->rgba[i*4+3]=image.alpha[i];}p->atlas=wxBitmap(image);
 std::string font=root.attribute("font").value();if(font!="DejaVuSans.ttf"&&font!="LiberationSans-Regular.ttf")throw std::runtime_error("Invalid presentation font");
 std::ifstream in(std::filesystem::path(data)/"presentation"/font,std::ios::binary);p->font.assign(std::istreambuf_iterator<char>(in),{});if(p->font.size()>2*1024*1024||!no5PlatformValidateFont(p->font))throw std::runtime_error("Invalid bundled presentation font");
 for(auto node:root.child("glyphs").children("glyph")){PresentationGlyph g;g.name=node.attribute("name").value();g.definition=node.attribute("definition").value()[0];g.x=node.attribute("x").as_int();g.y=node.attribute("y").as_int();g.w=node.attribute("w").as_int();g.h=node.attribute("h").as_int();g.rw=node.attribute("rw").as_int();g.rh=node.attribute("rh").as_int();g.px=node.attribute("px").as_int();g.py=node.attribute("py").as_int();g.ox=node.attribute("ox").as_int();g.oy=node.attribute("oy").as_int();
  if(g.name.size()!=8||(g.definition!='R'&&g.definition!='V')||g.x<0||g.y<0||g.w<=0||g.h<=0||g.x+g.w>image.w||g.y+g.h>image.h)throw std::runtime_error("Invalid symbol metadata");p->glyphs.push_back(g);}
 if(p->glyphs.empty())throw std::runtime_error("Empty symbol metadata");
 for(auto node:root.child("instructions").children("lookup")){int table=node.attribute("table").as_int(-1),id=node.attribute("rcid").as_int(-1);std::string text=node.text().get();if(table<0||id<0||text.size()>4096)throw std::runtime_error("Invalid lookup instruction");
  // Match ChartSymbols::ProcessLookups: the original S52 parser scans to ';'
  // or this record terminator, not to the C string's terminating NUL.
  text.push_back('\037');p->instructions.emplace(std::make_pair(table,id),text);}
 return p;
}
// R06/R07 readability treatment on the existing whole-label texture path.
void presentationTextHalo(unsigned char* rgba,int w,int h,S52color* color){
 if(!presentationHalo)return;
 if(presentationHaloRevision==4){
  // Exact selected R04 day halo: 5/238 grayscale tone and .78 spread.
  std::vector<unsigned char> source(rgba,rgba+size_t(w)*h*4);
  bool light=(color->R*.2126+color->G*.7152+color->B*.0722)>125;
  const unsigned char tone=light?5:238;
  for(int y=0;y<h;++y)for(int x=0;x<w;++x){
   size_t i=(size_t(y)*w+x)*4;unsigned char spread=0;
   for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){if(x+dx<0||x+dx>=w||y+dy<0||y+dy>=h)continue;spread=std::max(spread,source[(size_t(y+dy)*w+x+dx)*4+3]);}
   float a=source[i+3]/255.f,b=spread/255.f*.78f*(1-a),total=a+b;
   if(total>0){for(int k=0;k<3;++k)rgba[i+k]=(source[i+k]*a+tone*b)/total;rgba[i+3]=std::lround(total*255);}
  }return;
 }
 bool night=presentationHalo==2;float brightness=color->R*.2126f+color->G*.7152f+color->B*.0722f;
 unsigned char tone[3];if(night){bool dark=brightness<=48;tone[0]=dark?64:0;tone[1]=dark?12:0;tone[2]=dark?16:0;}else{unsigned char c=brightness>125?8:246;tone[0]=tone[1]=tone[2]=c;}
 std::vector<unsigned char> source(rgba,rgba+size_t(w)*h*4);for(int y=0;y<h;y++)for(int x=0;x<w;x++){size_t at=(size_t(y)*w+x)*4;unsigned char spread=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){if(x+dx<0||x+dx>=w||y+dy<0||y+dy>=h)continue;spread=std::max(spread,source[(size_t(y+dy)*w+x+dx)*4+3]);}float a=source[at+3]/255.f,b=spread/255.f*.88f*(1-a),total=a+b;if(total){for(int k=0;k<3;k++)rgba[at+k]=(source[at+k]*a+tone[k]*b)/total;rgba[at+3]=std::lround(total*255);}}
}
}
