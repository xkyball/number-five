#pragma once
#include "benchmark_run.hpp"
#include <string>
#include <stdexcept>

namespace no5 {
struct ViewportReuseFlags {
 bool soundings=false,spatial=false,gpu=false;
 bool operator==(const ViewportReuseFlags& value)const{return soundings==value.soundings&&spatial==value.spatial&&gpu==value.gpu;}
};
inline ViewportReuseFlags viewportReuseFlags(const std::string& variant){
 if(variant=="OFF")return {};
 if(variant=="SOUNDINGS")return {true,false,false};
 if(variant=="SPATIAL")return {false,true,false};
 if(variant=="GPU")return {false,false,true};
 if(variant=="ALL")return {true,true,true};
 throw std::invalid_argument("Unknown viewport reuse diagnostic variant");
}
/** Owner-thread state only. Never a persistent feature setting or an acceptance gate.
 * A stopped run is restored at the next owner checkpoint, even if its old JNI
 * caller disappeared. Restore never applies an old baseline over a newer run.
 */
class ViewportReuseRun {
 std::string id_,variant_,reason_;
 ViewportReuseFlags original_{};
 bool captured_=false,active_=false;
public:
 bool configure(const BenchmarkRunControl& control,const std::string& id,const std::string& variant,
                ViewportReuseFlags current,ViewportReuseFlags& next){
  const auto requested=viewportReuseFlags(variant); // Validate before any mutation.
  if(id.empty()||id.size()>128||!control.owns(id))return false;
  control.snapshot().checkpoint();
  if(active_&&id_!=id)return false;
  if(!active_){id_=id;original_=current;captured_=true;active_=true;reason_.clear();}
  variant_=variant;next=requested;return true;
 }
 bool restore(const std::string& id,ViewportReuseFlags& next,const std::string& reason){
  if(!captured_||id_!=id)return false;
  if(!active_)return true;
  next=original_;active_=false;reason_=reason;return true;
 }
 bool reconcile(const BenchmarkRunControl& control,ViewportReuseFlags& next){
  if(!active_)return false;
  if(!control.owns(id_)){restore(id_,next,"RUN_ENDED");return true;}
  try{control.snapshot().checkpoint();}catch(const BenchmarkRunCancelled&){restore(id_,next,"RUN_CANCELLED_OR_EXPIRED");return true;}
  return false;
 }
 bool active()const{return active_;}
 bool captured()const{return captured_;}
 const std::string& id()const{return id_;}
 const std::string& variant()const{return variant_;}
 const std::string& reason()const{return reason_;}
 ViewportReuseFlags original()const{return original_;}
};
}
