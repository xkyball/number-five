#include "chart.hpp"
#include "render_epoch.hpp"
#include <filesystem>
#include <iomanip>
#include <limits>
#include <sstream>
#include <tuple>
namespace no5 {
std::string jsonEscape(const std::string&);
namespace {
std::string sourceOf(const Cell& cell){return cell.sourceKey.empty()?cell.preparedKey:cell.sourceKey;}
bool supported(const std::string& name){
 return name=="BOYCAR"||name=="BOYINB"||name=="BOYISD"||name=="BOYLAT"||name=="BOYSAW"||name=="BOYSPP"||name=="WRECKS"||name=="RESARE"||name=="MIPARE"||name=="CTNARE"||name=="LIGHTS"||name=="TOPMAR"||name=="FOGSIG"||name=="RTPBCN";
}
double segmentDistance(wxPoint2DDouble p,wxPoint2DDouble a,wxPoint2DDouble b){
 const double dx=b.m_x-a.m_x,dy=b.m_y-a.m_y,length=dx*dx+dy*dy;
 const double t=length?std::clamp(((p.m_x-a.m_x)*dx+(p.m_y-a.m_y)*dy)/length,0.,1.):0;
 return std::hypot(p.m_x-a.m_x-t*dx,p.m_y-a.m_y-t*dy);
}
bool clip(wxPoint2DDouble& a,wxPoint2DDouble& b,int w,int h){
 if(!std::isfinite(a.m_x)||!std::isfinite(a.m_y)||!std::isfinite(b.m_x)||!std::isfinite(b.m_y))return false;
 double dx=b.m_x-a.m_x,dy=b.m_y-a.m_y,t0=0,t1=1;
 double p[]={-dx,dx,-dy,dy},q[]={a.m_x,double(w-1)-a.m_x,a.m_y,double(h-1)-a.m_y};
 for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return false;}else {double t=q[i]/p[i];if(p[i]<0)t0=std::max(t0,t);else t1=std::min(t1,t);if(t0>t1)return false;}}
 b={a.m_x+t1*dx,a.m_y+t1*dy};a={a.m_x+t0*dx,a.m_y+t0*dy};return true;
}
}
bool No5ChartRenderer::selectableObject(Feature& f){
 return publishedEligibilityGeometry==geometryGeneration&&f.publishedSelectable;
}
void No5ChartRenderer::stageObjectEligibility(){
 StageScope phase(RenderStage::QueryEligibility);
 eligibilityStaged=false;
 for(auto& f:features){normalRenderCheckpoint(RenderAbortBoundary::Portrayal);f.preparedSelectable=supported(f.name)&&f.rules.LUP&&portrayal.ObjectRenderCheckRules(&f.rules,true);}
 eligibilityStaged=true;
}
void No5ChartRenderer::publishObjectEligibility()noexcept{
 if(!eligibilityStaged)return;
 for(auto& f:features)f.publishedSelectable=f.preparedSelectable;
 publishedEligibilityGeometry=geometryGeneration;publishedEligibilityPresentation=presentationGeneration;
 publishedEligibilityFilters=objectFilterRevision;publishedEligibilityView=view;
 eligibilityPublished=true;eligibilityStaged=false;
}
bool No5ChartRenderer::hasCompletedQueryView()const{
 const auto& p=publishedEligibilityView;
 return eligibilityPublished&&publishedEligibilityGeometry==geometryGeneration&&publishedEligibilityPresentation==presentationGeneration&&publishedEligibilityFilters==objectFilterRevision&&
  p.clat==view.clat&&p.clon==view.clon&&p.view_scale_ppm==view.view_scale_ppm&&p.rotation==view.rotation&&p.pix_width==view.pix_width&&p.pix_height==view.pix_height;
}
bool No5ChartRenderer::objectOwnedAt(const Feature& f,double x,double y)const{
 const auto index=coverageCell(f);
 return x>=0&&y>=0&&x<view.pix_width&&y<view.pix_height&&
  (frameCoverage.masked?frameCoverage.owns(index,x,y):std::find(frameCoverage.selected.begin(),frameCoverage.selected.end(),index)!=frameCoverage.selected.end());
}
bool No5ChartRenderer::walkObjectBoundary(const Feature& f,const std::function<void(wxPoint2DDouble,wxPoint2DDouble)>& visit){
 // Keep disjoint rings/edges separate. These are the original assembled ENC
 // segments, including inner rings, rather than the label/symbol bounding box.
 auto& model=*f.cell;size_t visited=0;bool any=false;
 for(auto* segment=f.object->m_ls_list;segment;segment=segment->next){
  const bool edge=segment->ls_type==TYPE_EE||segment->ls_type==TYPE_EE_REV;
  const size_t count=edge?segment->pedge->nCount:2;
  const size_t offset=edge?segment->pedge->vbo_offset:segment->pcs->vbo_offset;
  if(!model.m_line_vertex_buffer||count<2||offset>model.m_vbo_byte_length||count>(model.m_vbo_byte_length-offset)/(2*sizeof(float)))continue;
  if(visited+count>100000)throw std::runtime_error("Object boundary exceeds selection budget");visited+=count;
  auto* points=reinterpret_cast<const float*>(reinterpret_cast<const char*>(model.m_line_vertex_buffer)+offset);
  auto projected=[&](size_t i){double lat,lon;fromSM_plib(points[i*2],points[i*2+1],model.ref_lat,model.ref_lon,&lat,&lon);return project(lat,lon);};
  auto a=projected(0);for(size_t i=1;i<count;i++){auto b=projected(i);visit(a,b);a=b;any=true;}
 }
 return any;
}
std::string No5ChartRenderer::queryObjects(double x,double y,double radius,int offset){
 if(!std::isfinite(x)||!std::isfinite(y)||!std::isfinite(radius)||radius<=0||radius>512||offset<0)throw std::invalid_argument("Invalid object query");
 if(!hasCompletedQueryView())throw std::runtime_error("NO_CONFIRMED_PORTRAYAL");
 ps52plib=&portrayal;ensureViewportCoverage();auto geo=unproject(x,y);
 struct Hit{Feature* f;Cell* cell;double distance;int rank;};std::vector<Hit> hits;
 for(auto& owner:cells)for(auto& f:owner->arena){
  if(!selectableObject(f))continue;
  const auto primitive=f.object->Primitive_type;double distance=std::numeric_limits<double>::infinity();
  if(primitive==GEO_POINT){auto p=project(f.object->m_lat,f.object->m_lon);if(objectOwnedAt(f,p.m_x,p.m_y))distance=std::hypot(x-p.m_x,y-p.m_y);}
  else {
   if(!objectOwnedAt(f,x,y))continue;
   if(primitive==GEO_AREA&&f.cell->IsPointInObjArea(geo.latitude,geo.longitude,0,f.object.get()))distance=0;
   else walkObjectBoundary(f,[&](auto a,auto b){distance=std::min(distance,segmentDistance({x,y},a,b));});
  }
  if(distance<=radius)hits.push_back({&f,owner.get(),distance,primitive==GEO_POINT?0:primitive==GEO_LINE?1:2});
 }
 std::sort(hits.begin(),hits.end(),[](const auto& a,const auto& b){return std::make_tuple(a.rank,a.distance,sourceOf(*a.cell),a.f->object->Index)<std::make_tuple(b.rank,b.distance,sourceOf(*b.cell),b.f->object->Index);});
 const size_t start=std::min(size_t(offset),hits.size()),end=std::min(start+32,hits.size());
 std::ostringstream out;out<<std::setprecision(std::numeric_limits<double>::max_digits10)<<"{\"total\":"<<hits.size()<<",\"nextOffset\":"<<(end<hits.size()?std::to_string(end):"null")<<",\"objects\":[";
 for(size_t i=start;i<end;i++){auto& hit=hits[i];auto& f=*hit.f;if(i!=start)out<<',';
  out<<"{\"id\":\""<<jsonEscape(f.id)<<"\",\"sourceKey\":\""<<jsonEscape(sourceOf(*hit.cell))<<"\",\"featureIndex\":"<<f.object->Index<<",\"cellName\":\""<<jsonEscape(std::filesystem::path(hit.cell->path).stem().string())<<"\",\"objectClass\":\""<<f.name<<"\",\"primitive\":\""<<(f.object->Primitive_type==GEO_POINT?"POINT":f.object->Primitive_type==GEO_AREA?"AREA":"LINE")<<"\",\"latitude\":"<<f.object->m_lat<<",\"longitude\":"<<f.object->m_lon<<",\"distancePixels\":"<<hit.distance<<",\"attributes\":{";
  bool first=true;for(auto& pair:f.attributes){if(!first)out<<',';first=false;out<<'"'<<jsonEscape(pair.first)<<"\":\""<<jsonEscape(pair.second)<<'"';}out<<"}}";
 }
 out<<"]}";return out.str();
}
bool No5ChartRenderer::selectObject(const std::string& source,int index,double radius){
 if(source.empty()){if(index>=0)return false;selectedObjectSource.clear();selectedObjectIndex=-1;selectedObjectVisible=false;selectedObjectSegments=0;return true;}
 if(!std::isfinite(radius)||radius<=0||radius>512)return false;
 if(!hasCompletedQueryView())return false;
 for(auto& cell:cells)if(sourceOf(*cell)==source)for(auto& f:cell->arena)if(f.object->Index==index&&selectableObject(f)){
  selectedObjectSource=source;selectedObjectIndex=index;selectedObjectRadius=radius;return true;
 }
 return false;
}
void No5ChartRenderer::renderObjectSelection(No5RenderTarget& target,bool prepared){
 selectedObjectVisible=false;selectedObjectSegments=0;
 if(selectedObjectSource.empty()||(benchmarkRunControl&&!benchmarkRunControl->idle()))return;
 Feature* selected=nullptr;
 for(auto& cell:cells)if(sourceOf(*cell)==selectedObjectSource)for(auto& f:cell->arena)if(f.object->Index==selectedObjectIndex)selected=&f;
 if(!selected||!(prepared?eligibilityStaged&&selected->preparedSelectable:selectableObject(*selected)))return;
 S52color ink{},edge{};ink.R=ink.G=ink.B=245;
 if(settings.palette=="NIGHT"||(presentation&&presentation->style.rfind("red",0)==0)){edge.R=255;edge.G=80;edge.B=80;ink.R=45;ink.G=ink.B=0;}else{edge.R=0;edge.G=112;edge.B=150;}
 auto draw=[&](wxPoint2DDouble a,wxPoint2DDouble b){
  if(!clip(a,b,view.pix_width,view.pix_height))return;
  // Respect quilt ownership even for an area whose boundary crosses cells.
  const int steps=std::max(1,int(std::ceil(std::hypot(b.m_x-a.m_x,b.m_y-a.m_y)/4)));
  auto point=[&](double t){return wxPoint2DDouble(a.m_x+(b.m_x-a.m_x)*t,a.m_y+(b.m_y-a.m_y)*t);};
  int run=-1;for(int i=0;i<=steps;i++){
   const auto mid=point((i+.5)/steps);const bool owned=i<steps&&objectOwnedAt(*selected,mid.m_x,mid.m_y);
   if(owned&&run<0)run=i;
   if(!owned&&run>=0){auto p=point(double(run)/steps),q=point(double(i)/steps);wxPoint from(wxRound(p.m_x),wxRound(p.m_y)),to(wxRound(q.m_x),wxRound(q.m_y));target.drawLine(from,to,ink,5);target.drawLine(from,to,edge,2);selectedObjectSegments++;run=-1;}
  }
 };
 if(selected->object->Primitive_type==GEO_POINT){auto p=project(selected->object->m_lat,selected->object->m_lon);
  if(objectOwnedAt(*selected,p.m_x,p.m_y))for(int i=0;i<40;i++){double a=i*2*M_PI/40,b=(i+1)*2*M_PI/40;draw({p.m_x+selectedObjectRadius*cos(a),p.m_y+selectedObjectRadius*sin(a)},{p.m_x+selectedObjectRadius*cos(b),p.m_y+selectedObjectRadius*sin(b)});}
 }else walkObjectBoundary(*selected,draw);
 selectedObjectVisible=selectedObjectSegments>0;
}
std::string No5ChartRenderer::objectSelectionJson()const{
 std::ostringstream out;out<<"{\"sourceKey\":\""<<jsonEscape(selectedObjectSource)<<"\",\"featureIndex\":"<<selectedObjectIndex<<",\"visible\":"<<(selectedObjectVisible?"true":"false")<<",\"segments\":"<<selectedObjectSegments<<'}';return out.str();
}
}
