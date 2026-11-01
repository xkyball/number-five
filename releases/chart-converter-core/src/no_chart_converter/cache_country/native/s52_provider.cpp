// Public OpenCPN S52 compiler bridge. No pixels, OEM code or licence operations.
// Link against an explicitly selected no5_chart_core build from no-apps/map.
#include "port/no5_platform.hpp"
#include "port/senc_cache.hpp"
#include "s52utils.h"
#include <fstream>
#include <iomanip>
#include <limits>
#include <memory>
#include <set>

extern Cond condTable[];
void no5InitializeS52Platform(const std::string&, const std::string&);

namespace {
std::string quoted(const std::string& value) {
  std::ostringstream out; out << '"';
  for (unsigned char c : value) {
    if (c == '"' || c == '\\') out << '\\' << c;
    else if (c < 32) out << "\\u" << std::hex << std::setw(4)
                            << std::setfill('0') << int(c) << std::dec;
    else out << c;
  }
  out << '"'; return out.str();
}
const char* opcode(Rules_t type) {
  switch(type) {
    case RUL_TXT_TX:return "TX";case RUL_TXT_TE:return "TE";
    case RUL_SYM_PT:return "SY";case RUL_SIM_LN:return "LS";
    case RUL_COM_LN:return "LC";case RUL_ARE_CO:return "AC";
    case RUL_ARE_PA:return "AP";case RUL_CND_SY:return "CS";
    case RUL_MUL_SG:return "MP";case RUL_ARC_2C:return "CA";
    default:throw std::runtime_error("S52_UNSUPPORTED_PARSED_RULE");
  }
}
std::string parameters(const Rules& rule) {
  if(!rule.INSTstr)throw std::runtime_error("S52_MISSING_RULE_PARAMETERS");
  const char* end=rule.INSTstr;
  // Exact SCANFWRD delimiters from the public StringToRules implementation.
  while(*end && *end!=';' && *end!=31)++end;
  return std::string(rule.INSTstr,static_cast<size_t>(end-rule.INSTstr));
}
struct SymbolResolution {std::string requested,actual;int rcid;};
std::string canonical(const Rules& rule,std::vector<SymbolResolution>* resolutions=nullptr) {
  const auto body=parameters(rule);int depth=0;char quote=0;
  auto serialise=[&](const std::string& parameters){
    std::string target=parameters;
    if(rule.ruleType==RUL_SYM_PT){
      if(!rule.razRule||parameters.size()<8)throw std::runtime_error("S52_MISSING_RESOLVED_SYMBOL");
      const std::string requested=parameters.substr(0,8);
      const std::string actual(rule.razRule->name.SYNM,8);
      if(actual.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")!=std::string::npos)
        throw std::runtime_error("S52_UNSERIALISABLE_RESOLVED_SYMBOL_NAME");
      // StringToRules itself binds missing public symbols to QUESMRK1.
      // Preserve that actual resolved Rule, rather than exporting a requested
      // name which the original renderer did not use. Keep explicit provenance.
      if(requested!=actual){
        target.replace(0,8,actual);
        if(resolutions)resolutions->push_back({requested,actual,rule.razRule->RCID});
      }
    }
    return std::string(opcode(rule.ruleType))+"("+target+")";
  };
  for(size_t i=0;i<body.size();++i){
    const char c=body[i];
    if(quote){if(c==quote)quote=0;continue;}
    if(c=='\''||c=='"'){quote=c;continue;}
    if(c=='('){++depth;continue;}
    if(c==')'){
      if(depth){--depth;continue;}
      // Public LNDARE point LUPs contain CS(QUAPOS01;TX(...)).
      // StringToRules correctly yields separate CS and TX nodes; the extra
      // closing parenthesis is outside TX's consumed argument list. Serialize
      // that actual TX rule as one well-formed target command, retaining its
      // complete text parameters rather than swallowing it as part of CS.
      for(size_t j=i+1;j<body.size();++j)
        if(body[j]!=')'&&body[j]!=' '&&body[j]!='\t')
          throw std::runtime_error("S52_UNCONSUMED_PARSED_RULE_SUFFIX");
      return serialise(body.substr(0,i));
    }
  }
  if(quote||depth)throw std::runtime_error("S52_UNBALANCED_PARSED_PARAMETERS");
  // Some original CSP CA returns omit the optional closing parenthesis.
  // The target representation adds syntax only; argument bytes are retained.
  return serialise(body);
}
std::unique_ptr<Rules,void(*)(Rules*)> parsed(s52plib& lib,const std::string& value) {
  LUPrec temporary{};std::string framed=value;
  if(!framed.empty()&&framed.back()!=31&&framed.back()!=';')framed+=char(31);
  temporary.INST=wxString(framed);
  lib._LUP2rules(&temporary,nullptr);
  return {temporary.ruleList,&s52plib::DestroyRulesChain};
}
std::string expand(s52plib& lib,const std::string& value, ObjRazRules& owner,
                   std::vector<std::string>& invoked,
                   std::vector<SymbolResolution>& resolutions,unsigned depth = 0) {
  if (depth > 8) throw std::runtime_error("S52_CONDITIONAL_RECURSION");
  std::string output;
  auto chain=parsed(lib,value);
  for(auto* rule=chain.get();rule;rule=rule->next){
    if(rule->ruleType!=RUL_CND_SY){output+=canonical(*rule,&resolutions)+';';continue;}
    const auto body=parameters(*rule);
    if(body.size()<8)throw std::runtime_error("S52_BAD_CONDITIONAL_NAME");
    // Source S52 uses exactly eight bytes, independent of trailing legacy
    // syntax; do not reinterpret a following TX node as part of this name.
    const auto name=body.substr(0,8);
    Cond* found = nullptr;
    for (auto* row = condTable; row->condInst; ++row)
      if (name == row->name) { found = row; break; }
    if (!found) throw std::runtime_error("S52_UNSUPPORTED_CONDITIONAL:" + name);
    invoked.push_back(name);
    // This is the exact public condTable call used by s52plib::RenderCS.
    std::unique_ptr<char, decltype(&std::free)> text(
        static_cast<char*>(found->condInst(&owner)), &std::free);
    if (text) output += expand(lib,text.get(), owner, invoked,resolutions,depth + 1);
  }
  return output;
}
double number(const char* value) {
  size_t used = 0; const std::string text(value);
  double result = std::stod(text, &used);
  if (used != text.size() || !std::isfinite(result))
    throw std::runtime_error("S52_BAD_SETTING");
  return result;
}
struct Owner { S57Obj* object; ObjRazRules rules{}; };

void compile(const std::string& source, const std::string& assets,
             const std::string& cache, const std::string& contextName,
             double safety, double shallow, double deep, int depthUnit, bool twoShades,
             std::ostream& output) {
  no5InitializeS52Platform(assets, assets + "/DejaVuSans.ttf");
  s52plib lib(assets + "/S52RAZDS.RLE", false);
  if (!lib.m_bOK) throw std::runtime_error("S52_RESOURCE_LOAD_FAILED");
  ps52plib = &lib;
  lib.m_nSymbolStyle = contextName == "paper" ? PAPER_CHART : SIMPLIFIED;
  lib.m_nBoundaryStyle = PLAIN_BOUNDARIES;
  lib.m_nDepthUnitDisplay = depthUnit;
  lib.m_bShowMeta = true; lib.m_bShowSoundg = true;
  lib.SetDisplayCategory(MARINERS_STANDARD);
  S52_setMarinerParam(S52_MAR_SAFETY_CONTOUR, safety);
  S52_setMarinerParam(S52_MAR_SAFETY_DEPTH, safety);
  S52_setMarinerParam(S52_MAR_SHALLOW_CONTOUR, shallow);
  S52_setMarinerParam(S52_MAR_DEEP_CONTOUR, deep);
  S52_setMarinerParam(S52_MAR_TWO_SHADES, twoShades ? 1 : 0);
  lib.UpdateMarinerParams();
  s57chart model; ChartTableEntry table;
  auto objects = no5::SencCache::load(source, assets, cache + "/unused.senc",
                                    table, model, false);
  chart_context context{}; context.ref_lat=model.ref_lat;context.ref_lon=model.ref_lon;
  context.chart=&model;context.chart_scale=table.scale;
  context.chart_type=S52_CHART_TYPE_S57;
  context.m_pve_hash=&model.m_ve_hash;context.m_pvc_hash=&model.m_vc_hash;
  context.pt2GetAssociatedObjects=&s57chart::GetAssociatedObjects;
  wxArrayPtrVoid floating, rigid;
  context.pFloatingATONArray=&floating;context.pRigidATONArray=&rigid;
  context.safety_contour=std::numeric_limits<double>::infinity();
  std::vector<Owner> owners; owners.reserve(objects.size());
  std::set<int> ids;
  for (auto& object : objects) {
    if (!ids.insert(object->Index).second) throw std::runtime_error("S52_DUPLICATE_SOURCE_FEATURE");
    object->m_chart_context=&context;
    const std::string name(object->FeatureName,6);
    if (name.rfind("BOY",0)==0) {object->bIsAton=true;floating.Add(object.get());}
    if (name.rfind("BCN",0)==0||name=="LIGHTS") {object->bIsAton=true;rigid.Add(object.get());}
    if (name=="DEPCNT") {double value;if(GetDoubleAttr(object.get(),"VALDCO",value)&&value>=safety)context.safety_contour=std::min(context.safety_contour,value);}
    auto kind=object->Primitive_type;
    auto lookup=kind==GEO_AREA?PLAIN_BOUNDARIES:kind==GEO_LINE?LINES:lib.m_nSymbolStyle;
    auto* lup=lib.S52_LUPLookup(lookup,name.c_str(),object.get());
    owners.push_back({object.get(),{}});auto& owner=owners.back();
    owner.rules.obj=object.get();owner.rules.LUP=lup;
    if(lup){lib._LUP2rules(lup,object.get());object->m_DisplayCat=lup->DISC;object->m_DPRI=lup->DPRI-'0';
      int group=kind==GEO_AREA?3:kind==GEO_LINE?2:1;
      const int priority=lup->DPRI-'0';
      if(priority<0||priority>=PRIO_NUM)throw std::runtime_error("S52_BAD_PRIORITY");
      owner.rules.next=model.razRules[priority][group];model.razRules[priority][group]=&owner.rules;
    }
  }
  model.AssembleLineGeometry();context.vertex_buffer=model.m_line_vertex_buffer;
  bool first=true;
  for(auto& owner:owners){
    if(!first)output<<',';first=false;auto& object=*owner.object;
    output<<"{\"sourceFeatureId\":"<<object.Index<<",\"classAcronym\":"<<quoted(std::string(object.FeatureName,6));
    try{
      auto* lup=owner.rules.LUP;if(!lup)throw std::runtime_error("S52_NO_LUP");
      const std::string original(lup->INST);std::vector<std::string> invoked;
      std::vector<SymbolResolution> resolutions;
      // Native RX5 keeps complete sounding samples and executes SOUNDG itself.
      const bool sounding=std::string(object.FeatureName,6)=="SOUNDG";
      std::string instructions=sounding?"CS(SOUNDG01);":expand(lib,original,owner.rules,invoked,resolutions);
      output<<",\"status\":\"resolved\",\"type\":"<<quoted(sounding?"sounding":"programme")
            <<",\"table\":"<<int(lup->TNAM)<<",\"rcid\":"<<lup->RCID
            <<",\"priority\":"<<object.m_DPRI<<",\"category\":"<<int(object.m_DisplayCat)
            <<",\"originalInstruction\":"<<quoted(original)<<",\"instruction\":"<<quoted(instructions)
            <<",\"conditionals\":[";
      for(size_t i=0;i<invoked.size();++i){if(i)output<<',';output<<quoted(invoked[i]);}output<<']';
      output<<",\"symbolResolutions\":[";
      for(size_t i=0;i<resolutions.size();++i){if(i)output<<',';const auto& r=resolutions[i];
        output<<"{\"requestedName\":"<<quoted(r.requested)<<",\"actualName\":"<<quoted(r.actual)
              <<",\"actualRuleRCID\":"<<r.rcid<<",\"source\":\"public_StringToRules_actual_Rule_binding\"}";}
      output<<']';
    }catch(const std::exception& e){output<<",\"status\":\"unresolved\",\"reason\":"<<quoted(e.what());}
    output<<'}';
  }
  ps52plib=nullptr;
}
void selfTest(const std::string& assets) {
  no5InitializeS52Platform(assets,assets+"/DejaVuSans.ttf");
  s52plib lib(assets+"/S52RAZDS.RLE",false);
  if(!lib.m_bOK)throw std::runtime_error("S52_RESOURCE_LOAD_FAILED");
  const std::string text="TX(OBJNAM,1,2,3,'15118',-1,-1,CHBLK,26)";
  auto legacy=parsed(lib,"SY(LNDARE01);CS(QUAPOS01;"+text+"))");
  auto* first=legacy.get();auto* cs=first?first->next:nullptr;
  auto* tx=cs?cs->next:nullptr;
  if(!first||first->ruleType!=RUL_SYM_PT||canonical(*first)!="SY(LNDARE01)"||
     !cs||cs->ruleType!=RUL_CND_SY||parameters(*cs).substr(0,8)!="QUAPOS01"||
     !tx||tx->ruleType!=RUL_TXT_TX||canonical(*tx)!=text||tx->next)
    throw std::runtime_error("S52_LEGACY_CS_TX_SELF_TEST_FAILED");
  auto ordinary=parsed(lib,"AC(DEPMS);LS(SOLD,1,CSTLN);TE('%s','OBJNAM',3,1,2,'15110',1,0,CHBLK,29)");
  size_t count=0;for(auto* r=ordinary.get();r;r=r->next){canonical(*r);++count;}
  if(count!=3)throw std::runtime_error("S52_ORDINARY_RULE_SELF_TEST_FAILED");
  auto ignored=parsed(lib,"OP(6---);LS(DASH,2,CHMGD);SY(ENTRES61)");
  if(!ignored||ignored->ruleType!=RUL_SIM_LN||!ignored->next||
     ignored->next->ruleType!=RUL_SYM_PT||ignored->next->next)
    throw std::runtime_error("S52_NATIVE_NON_DRAW_RULE_SELF_TEST_FAILED");
  auto arc=parsed(lib,"CA(CHBLK,1,LITRD,2,0,90,10,20\037");
  if(!arc||canonical(*arc)!="CA(CHBLK,1,LITRD,2,0,90,10,20)")
    throw std::runtime_error("S52_OPTIONAL_CA_CLOSING_SELF_TEST_FAILED");
  auto absent=parsed(lib,"SY(ZZZZZZZ1,ORIENT)");std::vector<SymbolResolution> resolutions;
  if(!absent||canonical(*absent,&resolutions)!="SY(QUESMRK1,ORIENT)"||resolutions.size()!=1||
     resolutions[0].requested!="ZZZZZZZ1"||resolutions[0].actual!="QUESMRK1")
    throw std::runtime_error("S52_ACTUAL_SYMBOL_FALLBACK_SELF_TEST_FAILED");
  std::cout<<"{\"format\":\"no5-s52-native-rule-self-test/1\",\"cases\":5,\"passed\":true}\n";
}
}
int main(int argc,char** argv){
  try{
    if(argc==3&&std::string(argv[1])=="--self-test-programmes"){selfTest(argv[2]);return 0;}
    if(argc!=10)throw std::runtime_error("usage: engine SOURCE_SENC ASSETS CACHE paper|simple SAFETY SHALLOW DEEP DEPTH_UNIT TWO_SHADES");
    const std::string context(argv[4]);if(context!="paper"&&context!="simple")throw std::runtime_error("S52_BAD_CONTEXT");
    const auto safety=number(argv[5]),shallow=number(argv[6]),deep=number(argv[7]);const auto units=number(argv[8]);
    if(shallow<0||safety<shallow||deep<safety||units<0||units>2||units!=int(units))throw std::runtime_error("S52_BAD_SETTINGS");
    const auto shades=number(argv[9]);if(shades!=0&&shades!=1)throw std::runtime_error("S52_BAD_TWO_SHADES");
    std::ostringstream rows;compile(argv[1],argv[2],argv[3],context,safety,shallow,deep,int(units),shades!=0,rows);
    std::cout<<"{\"format\":\"no5-opencpn-s52-engine/1\",\"context\":"<<quoted(context)<<",\"owners\":["<<rows.str()<<"]}\n";
    return 0;
  }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
