#pragma once
// B0 is a host-only observation seam. No policy, evaluator calls, Android
// allocation, frame key, or portrayal setting is changed by these hooks.
#ifndef __ANDROID__
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <map>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace no5::detail {
enum class Phase { Unattributed, Areas, LinePriority, Objects, FilterCount, DirectTest, Count };
enum class Event {
 PassVisit, CoverageRejected, PositionAccepted, PositionRejected,
 CategoryEntered, CategoryAccepted, CategoryRejected, MetaRejected,
 ScaminAllowed, ScaminRejected, ScaminExempt, ScaminDisabled,
 RulesAccepted, RulesRejected, DatesAccepted, DatesRejected, ConditionalBefore, ConditionalAfter,
 TextRequested, TextCheckRejected, ImportantTextRejected, TextDrawn, TextNotDrawn,
 SoundingsEntered, SoundingsHidden, SoundingsScaminAllowed, SoundingsScaminRejected,
 SoundingRulesBuilt, SoundingPointVisited, SoundingPointAccepted, SoundingGlyphQueued,
 ObjectWithGlCalls, GlDrawCalls, Count
};
inline constexpr const char* phaseNames[]={"unattributed","areas","line_priority","objects","filter_count","direct_test"};
inline constexpr const char* eventNames[]={
 "pass_visit","coverage_rejected","position_accepted","position_rejected",
 "category_entered","category_accepted","category_rejected","meta_rejected",
 "scamin_allowed","scamin_rejected","scamin_exempt","scamin_disabled",
 "rules_accepted","rules_rejected","dates_accepted","dates_rejected","conditional_before","conditional_after",
 "text_requested","text_check_rejected","important_text_rejected","text_drawn","text_not_drawn",
 "soundings_entered","soundings_hidden","soundings_scamin_allowed","soundings_scamin_rejected",
 "sounding_rules_built","sounding_point_visited","sounding_point_accepted","sounding_glyph_queued",
 "object_with_gl_calls","gl_draw_calls"};
static_assert(sizeof(phaseNames)/sizeof(*phaseNames)==static_cast<std::size_t>(Phase::Count));
static_assert(sizeof(eventNames)/sizeof(*eventNames)==static_cast<std::size_t>(Event::Count));
struct GeoWindow {
 double south=0,west=0,north=0,east=0;
 bool valid()const{return std::isfinite(south)&&std::isfinite(west)&&std::isfinite(north)&&std::isfinite(east)&&south<=north&&west<=east&&east-west<180;}
 bool contains(double lat,double lon)const{return valid()&&lat>=south&&lat<=north&&lon>=west&&lon<=east;}
 template<class B> bool intersects(const B& b)const{
  return valid()&&b.GetMaxLat()>=south&&b.GetMinLat()<=north&&
   ((b.GetMaxLon()>=west&&b.GetMinLon()<=east)||(b.GetMaxLon()+360>=west&&b.GetMinLon()+360<=east)||(b.GetMaxLon()-360>=west&&b.GetMinLon()-360<=east));
 }
};
struct Counts {std::uint64_t all=0,common=0,unattributed=0;};
struct Record {
 Phase phase;Event event;std::string id,objectClass,encodedScamin;
 double scale=0,scamin=0;int primitive=0,lupCategory=0,category=0,lupPriority=0,priority=0,displayCategory=0,zoomModifier=0;
 bool common=false,point=false,useScamin=false,useSuperScamin=false,mutableCategory=false,csAdded=false;
 double latitude=0,longitude=0;std::uint64_t amount=1;
};
struct FeatureContext {const std::string* id=nullptr;const std::string* name=nullptr;const std::string* encoded=nullptr;bool common=false;};
inline std::string quote(const std::string& value){std::string out="\"";for(unsigned char c:value){if(c=='"'||c=='\\'){out+='\\';out+=c;}else if(c=='\n')out+="\\n";else if(c=='\r')out+="\\r";else if(c=='\t')out+="\\t";else if(c>=32)out+=c;}return out+'"';}
inline const char* boolean(bool b){return b?"true":"false";}
struct Capture {
 static constexpr std::size_t MaxRecords=4096,MaxFocus=256;
 GeoWindow commonWindow;std::set<std::string> focus;
 // Only the bounded focus is indexed. Global line-priority decisions outside
 // it remain explicitly unattributed, rather than inventing common-ROI totals.
 std::map<const void*,FeatureContext> focusObjects;
 std::array<std::array<Counts,static_cast<std::size_t>(Event::Count)>,static_cast<std::size_t>(Phase::Count)> counts{};
 std::vector<Record> records;std::size_t limit=MaxRecords;std::uint64_t droppedRecords=0;
 explicit Capture(GeoWindow window={},std::set<std::string> ids={},std::size_t recordLimit=MaxRecords):commonWindow(window),focus(std::move(ids)),limit(std::min(recordLimit,MaxRecords)){
  if(focus.size()>MaxFocus)throw std::invalid_argument("Detail capture focus is not bounded");records.reserve(limit);
 }
 template<class Features> void bindFocus(const Features& features){focusObjects.clear();for(const auto& f:features)if(focus.count(f.id)){const auto it=f.attributes.find("SCAMIN");focusObjects.emplace(f.object.get(),FeatureContext{&f.id,&f.name,it==f.attributes.end()?nullptr:&it->second,commonWindow.intersects(f.geometryBounds)});}}
 std::uint64_t count(Event event,Phase phase)const{return counts[static_cast<std::size_t>(phase)][static_cast<std::size_t>(event)].all;}
 std::uint64_t count(Event event)const{std::uint64_t n=0;for(const auto& p:counts)n+=p[static_cast<std::size_t>(event)].all;return n;}
 std::string json()const{
  std::ostringstream o;o<<std::setprecision(17)<<"{\"schema\":\"no5-detail-observation/1\",\"recordLimit\":"<<limit<<",\"droppedRecords\":"<<droppedRecords;
  o<<",\"countSemantics\":\"calls at existing decisions; non-unique, non-additive; common is original feature bbox intersection except explicit sounding point events; line-priority common attribution covers bounded focus only, other global decisions unattributed; GL submission is not pixel visibility\"";
  o<<",\"commonWindow\":{\"south\":"<<commonWindow.south<<",\"west\":"<<commonWindow.west<<",\"north\":"<<commonWindow.north<<",\"east\":"<<commonWindow.east<<"},\"focusIds\":[";
  bool first=true;for(const auto& id:focus){if(!first)o<<',';first=false;o<<quote(id);}o<<"],\"counts\":[";first=true;
  for(std::size_t p=0;p<counts.size();p++)for(std::size_t e=0;e<counts[p].size();e++){const auto& c=counts[p][e];if(!c.all)continue;if(!first)o<<',';first=false;o<<"{\"phase\":"<<quote(phaseNames[p])<<",\"event\":"<<quote(eventNames[e])<<",\"all\":"<<c.all<<",\"common\":"<<c.common<<",\"unattributed\":"<<c.unattributed<<'}';}
  o<<"],\"records\":[";first=true;for(const auto& r:records){if(!first)o<<',';first=false;
   o<<"{\"phase\":"<<quote(phaseNames[static_cast<std::size_t>(r.phase)])<<",\"event\":"<<quote(eventNames[static_cast<std::size_t>(r.event)])<<",\"id\":"<<quote(r.id)<<",\"class\":"<<quote(r.objectClass)<<",\"encodedScamin\":"<<quote(r.encodedScamin)<<",\"effectiveScamin\":"<<r.scamin<<",\"nativeScale\":"<<r.scale<<",\"primitive\":"<<r.primitive<<",\"lupCategory\":"<<r.lupCategory<<",\"effectiveCategory\":"<<r.category<<",\"lupPriority\":"<<r.lupPriority<<",\"effectivePriority\":"<<r.priority<<",\"displayCategory\":"<<r.displayCategory<<",\"zoomModifier\":"<<r.zoomModifier<<",\"useScamin\":"<<boolean(r.useScamin)<<",\"useSuperScamin\":"<<boolean(r.useSuperScamin)<<",\"categoryMutable\":"<<boolean(r.mutableCategory)<<",\"csAdded\":"<<boolean(r.csAdded)<<",\"common\":"<<boolean(r.common)<<",\"point\":"<<boolean(r.point)<<",\"amount\":"<<r.amount;if(r.point)o<<",\"latitude\":"<<r.latitude<<",\"longitude\":"<<r.longitude;o<<'}';
  }o<<"]}";return o.str();
 }
};
inline thread_local Capture* active=nullptr;
inline thread_local Phase phase=Phase::Unattributed;
inline thread_local FeatureContext feature;
class CaptureScope {
 Capture* previous;Phase oldPhase;FeatureContext oldFeature;
public:
 explicit CaptureScope(Capture& c):previous(active),oldPhase(phase),oldFeature(feature){active=&c;phase=Phase::Unattributed;feature={};}
 ~CaptureScope(){active=previous;phase=oldPhase;feature=oldFeature;}
};
class PhaseScope {Phase previous;public:explicit PhaseScope(Phase p):previous(phase){if(active)phase=p;}~PhaseScope(){phase=previous;}};
class FeatureScope {
 FeatureContext previous;Phase previousPhase;
public:
 template<class F> FeatureScope(const F& f,Phase p):previous(feature),previousPhase(phase){if(active){const auto it=f.attributes.find("SCAMIN");feature={&f.id,&f.name,it==f.attributes.end()?nullptr:&it->second,active->commonWindow.intersects(f.geometryBounds)};phase=p;}}
 ~FeatureScope(){feature=previous;phase=previousPhase;}
};
template<class R> inline void observe(Event event,const R* rules,double scale,bool useScamin,bool useSuperScamin,int zoomModifier,int displayCategory,std::uint64_t amount=1,bool point=false,double lat=0,double lon=0){
 auto* c=active;if(!c||!amount)return;
 auto context=feature;if(!context.id&&rules&&rules->obj){auto found=c->focusObjects.find(rules->obj);if(found!=c->focusObjects.end())context=found->second;}
 bool attributed=context.id!=nullptr,common=attributed&&(point?c->commonWindow.contains(lat,lon):context.common);
 auto& n=c->counts[static_cast<std::size_t>(phase)][static_cast<std::size_t>(event)];n.all+=amount;if(common)n.common+=amount;if(!attributed)n.unattributed+=amount;
 if(!attributed||!c->focus.count(*context.id))return;
 if(c->records.size()>=c->limit){++c->droppedRecords;return;}
 Record r{};r.phase=phase;r.event=event;r.id=*context.id;r.objectClass=*context.name;r.encodedScamin=context.encoded?*context.encoded:"";r.common=common;r.point=point;r.latitude=lat;r.longitude=lon;r.amount=amount;r.scale=scale;r.useScamin=useScamin;r.useSuperScamin=useSuperScamin;r.zoomModifier=zoomModifier;r.displayCategory=displayCategory;
 if(rules&&rules->obj){r.scamin=rules->obj->Scamin;r.primitive=rules->obj->Primitive_type;r.category=rules->obj->m_DisplayCat;r.priority=rules->obj->m_DPRI;r.mutableCategory=rules->obj->m_bcategory_mutable;r.csAdded=rules->obj->bCS_Added;}
 if(rules&&rules->LUP){r.lupCategory=rules->LUP->DISC;r.lupPriority=int(rules->LUP->DPRI)-'0';}
 c->records.push_back(std::move(r));
}
// Observe category result on every existing return; no evaluator is called.
template<class R> class CategoryResultScope {
 const R* rules;const bool& result;double scale;bool use,super;int zoom,category;
public:
 CategoryResultScope(const R* r,const bool& b,double s,bool u,bool su,int z,int c):rules(r),result(b),scale(s),use(u),super(su),zoom(z),category(c){}
 ~CategoryResultScope(){observe(result?Event::CategoryAccepted:Event::CategoryRejected,rules,scale,use,super,zoom,category);}
};
template<class F,class P> inline void chartEvent(Event e,const F& f,P& p,double scale,std::uint64_t amount=1){observe(e,&f.rules,scale,p.m_bUseSCAMIN,p.m_bUseSUPER_SCAMIN,p.m_chart_zoom_modifier_vector,p.GetDisplayCategory(),amount);}
template<class F,class P> class GlSubmissionScope {
 const F& f;P& p;double scale;const unsigned long long& counter;unsigned long long before;
public:
 GlSubmissionScope(const F& feature,P& portrayal,double s,const unsigned long long& calls):f(feature),p(portrayal),scale(s),counter(calls),before(active?calls:0){}
 ~GlSubmissionScope(){if(active&&counter>before){chartEvent(Event::ObjectWithGlCalls,f,p,scale);chartEvent(Event::GlDrawCalls,f,p,scale,counter-before);}}
};
}
#define NO5_DETAIL_EVENT(event, rules) ::no5::detail::observe(::no5::detail::Event::event,rules,vp_plib.chart_scale,m_bUseSCAMIN,m_bUseSUPER_SCAMIN,m_chart_zoom_modifier_vector,m_nDisplayCategory)
#define NO5_DETAIL_POINT(event, rules, lat, lon) ::no5::detail::observe(::no5::detail::Event::event,rules,vp_plib.chart_scale,m_bUseSCAMIN,m_bUseSUPER_SCAMIN,m_chart_zoom_modifier_vector,m_nDisplayCategory,1,true,lat,lon)
#define NO5_DETAIL_CATEGORY_RESULT(rules, result) ::no5::detail::CategoryResultScope detailCategoryResult(rules,result,vp_plib.chart_scale,m_bUseSCAMIN,m_bUseSUPER_SCAMIN,m_chart_zoom_modifier_vector,m_nDisplayCategory)
#define NO5_DETAIL_CHART_FEATURE(f, p) ::no5::detail::FeatureScope detailFeature(*(f),::no5::detail::Phase::p); ::no5::detail::chartEvent(::no5::detail::Event::PassVisit,*(f),portrayal,view.chart_scale)
#define NO5_DETAIL_CHART_EVENT(event, f) ::no5::detail::chartEvent(::no5::detail::Event::event,*(f),portrayal,view.chart_scale)
#define NO5_DETAIL_PHASE(p) ::no5::detail::PhaseScope detailPhase(::no5::detail::Phase::p)
#define NO5_DETAIL_GL_SCOPE(f) ::no5::detail::GlSubmissionScope detailGl(*(f),portrayal,view.chart_scale,no5DrawCalls)
#else
#define NO5_DETAIL_EVENT(event, rules) ((void)0)
#define NO5_DETAIL_POINT(event, rules, lat, lon) ((void)0)
#define NO5_DETAIL_CATEGORY_RESULT(rules, result) ((void)0)
#define NO5_DETAIL_CHART_FEATURE(f, p) ((void)0)
#define NO5_DETAIL_CHART_EVENT(event, f) ((void)0)
#define NO5_DETAIL_PHASE(p) ((void)0)
#define NO5_DETAIL_GL_SCOPE(f) ((void)0)
#endif
