#include "no5_platform.hpp"
#include "s52plib.h"
#include "s52_plib_utils.h"
thread_local wxString no5SharedData;
extern "C" wxString* GetpSharedDataLocation(){return &no5SharedData;}
wxColour GetFontColour_PlugIn(wxString){return ps52plib?ps52plib->getwxColour("CHBLK"):wxColour(0,0,0);}
extern "C" bool GetGlobalColor(wxString name,wxColour* c){if(!ps52plib)return false;*c=ps52plib->getwxColour(name.c_str());return true;}
wxFont* FindOrCreateFont_PlugIn(int p,wxFontFamily f,wxFontStyle s,wxFontWeight w,bool underline,const wxString& face,wxFontEncoding e){
 static thread_local std::map<std::tuple<int,int,int,int,bool,std::string>,wxFont> fonts;
 auto key=std::make_tuple(p,int(f),int(s),int(w),underline,std::string(face));
 auto [it,added]=fonts.try_emplace(key,p,f,s,w,underline,face,e);return &it->second;
}
wxFont* GetOCPNScaledFont_PlugIn(wxString,int p){return FindOrCreateFont_PlugIn(p?p:10,wxFONTFAMILY_SWISS,wxFONTSTYLE_NORMAL,wxFONTWEIGHT_NORMAL,false,{},wxFONTENCODING_DEFAULT);}
float GetOCPNChartScaleFactor_Plugin(){return 1.0f;}
void LoadS57Config(){}
class No5S52Fonts:public S52PlibUtils {
 wxFont* GetFont(wxFont* f,double scale)override{return FindOrCreateFont_PlugIn(std::max(1,wxRound(f->GetPointSize()*scale)),f->GetFamily(),f->GetStyle(),f->GetWeight(),false,f->GetFaceName(),wxFONTENCODING_DEFAULT);}
 wxFont* GetDefaultFont(wxString,int p)override{return GetOCPNScaledFont_PlugIn({},p);}
 wxFont* GetScaledFont(int p,wxFontFamily f,wxFontStyle s,wxFontWeight w,const wxString face,double scale)override{return FindOrCreateFont_PlugIn(std::max(1,wxRound(p*scale)),f,s,w,false,face,wxFONTENCODING_DEFAULT);}
};
void no5InitializeS52Platform(const std::string& data,const std::string& font){no5SharedData=data;no5PlatformLoadFont(font);static No5S52Fonts fonts;SetUtils(&fonts);}
