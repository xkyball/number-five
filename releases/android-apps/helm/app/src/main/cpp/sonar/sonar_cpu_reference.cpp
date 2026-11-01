#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
namespace { constexpr int bins=1024;
void fail(JNIEnv* e,const std::exception& x){auto c=e->FindClass("java/lang/IllegalStateException");if(c)e->ThrowNew(c,x.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_no5_helm_gpu_SonarGpuNative_mean7Exact(JNIEnv* e,jobject,jshortArray input,jfloatArray table,jshortArray output,jint offset){
    if(!input||!table||!output||e->GetArrayLength(input)!=7*bins||e->GetArrayLength(table)!=65536||offset<0||offset>e->GetArrayLength(output)-bins){
        std::runtime_error error("SONAR_MEAN_ARRAY_BOUNDS");fail(e,error);return;
    }
    jboolean inputCopy=JNI_FALSE,tableCopy=JNI_FALSE,outputCopy=JNI_FALSE;
    auto* profiles=static_cast<jshort*>(e->GetPrimitiveArrayCritical(input,&inputCopy));
    if(!profiles)return;
    auto* lut=static_cast<jfloat*>(e->GetPrimitiveArrayCritical(table,&tableCopy));
    if(!lut){e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);return;}
    auto* result=static_cast<jshort*>(e->GetPrimitiveArrayCritical(output,&outputCopy));
    if(!result){e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);return;}
    const bool copied=inputCopy||tableCopy||outputCopy;
    bool valid=true;
    if(!copied)for(int row=0;row<bins;row++){
        double sum=static_cast<double>(lut[static_cast<uint16_t>(profiles[row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[2*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[3*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[4*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[5*bins+row])]);
        sum+=static_cast<double>(lut[static_cast<uint16_t>(profiles[6*bins+row])]);
        if(!std::isfinite(sum)||sum<0.0||sum>7.0){valid=false;break;}
        result[offset+row]=static_cast<jshort>(static_cast<uint16_t>(std::clamp(static_cast<int>(std::floor(sum/7.0*65535.0+0.5)),0,65535)));
    }
    e->ReleasePrimitiveArrayCritical(output,result,copied?JNI_ABORT:0);
    e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);
    e->ReleasePrimitiveArrayCritical(input,profiles,JNI_ABORT);
    if(copied){std::runtime_error error("SONAR_MEAN_CRITICAL_ARRAY_COPY_NOT_SUPPORTED");fail(e,error);}
    else if(!valid){std::runtime_error error("SONAR_MEAN_INVALID_INTENSITY");fail(e,error);}
}
// Exact registered-pair display interpolation. Inputs remain caller-owned;
// no GL, allocation, locks or retained arrays in the bounded critical section.
extern "C" JNIEXPORT jlong JNICALL Java_com_no5_helm_gpu_SonarGpuNative_pairColorsExact(JNIEnv* e,jobject,jshortArray input,jintArray table,jintArray output,jdouble oldSpacing,jdouble newSpacing,jdouble spacing,jdouble oldBottom,jdouble newBottom){
    const bool anchored=std::isfinite(oldBottom)&&std::isfinite(newBottom);
    if(!input||!table||!output||e->GetArrayLength(input)!=2048||e->GetArrayLength(table)!=65536||e->GetArrayLength(output)!=2048||
       (!std::isnan(spacing)&&(!std::isfinite(spacing)||spacing<=0||!std::isfinite(oldSpacing)||oldSpacing<=0||!std::isfinite(newSpacing)||newSpacing<=0))){
        std::runtime_error error("SONAR_PAIR_ARRAY_OR_GEOMETRY_BOUNDS");fail(e,error);return 0;
    }
    jboolean a=JNI_FALSE,b=JNI_FALSE,c=JNI_FALSE;
    auto* codes=static_cast<jshort*>(e->GetPrimitiveArrayCritical(input,&a));if(!codes)return 0;
    auto* lut=static_cast<jint*>(e->GetPrimitiveArrayCritical(table,&b));if(!lut){e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);return 0;}
    auto* result=static_cast<jint*>(e->GetPrimitiveArrayCritical(output,&c));if(!result){e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);return 0;}
    const bool copied=a||b||c;uint32_t oldColor=0,newColor=0;
    if(!copied){
        const double reference=anchored?(oldBottom+newBottom)/2.0:0.0;
        auto color=[&](int offset,double bin)->jint{
            int low=std::clamp(static_cast<int>(std::floor(bin)),0,1023),high=std::min(low+1,1023);
            double weight=std::clamp(bin-low,0.0,1.0);
            int first=static_cast<uint16_t>(codes[offset+low]),second=static_cast<uint16_t>(codes[offset+high]);
            int index=std::clamp(static_cast<int>(std::floor(first*(1.0-weight)+second*weight+0.5)),0,65535);
            return lut[index];
        };
        for(int bin=0;bin<1024;bin++){
            double x=std::isnan(spacing)?static_cast<double>(bin):anchored?(bin*spacing+oldBottom-reference)/oldSpacing:bin*(spacing/oldSpacing);
            double y=std::isnan(spacing)?static_cast<double>(bin):anchored?(bin*spacing+newBottom-reference)/newSpacing:bin*(spacing/newSpacing);
            if(!(x>=0.0&&x<=1023.0&&y>=0.0&&y<=1023.0)){result[bin*2]=0;result[bin*2+1]=0;}
            else{result[bin*2]=color(0,x);result[bin*2+1]=color(1024,y);}
        }
        if(anchored){oldColor=static_cast<uint32_t>(color(0,oldBottom/oldSpacing));newColor=static_cast<uint32_t>(color(1024,newBottom/newSpacing));}
    }
    e->ReleasePrimitiveArrayCritical(output,result,copied?JNI_ABORT:0);
    e->ReleasePrimitiveArrayCritical(table,lut,JNI_ABORT);e->ReleasePrimitiveArrayCritical(input,codes,JNI_ABORT);
    if(copied){std::runtime_error error("SONAR_PAIR_CRITICAL_ARRAY_COPY_NOT_SUPPORTED");fail(e,error);return 0;}
    return static_cast<jlong>((static_cast<uint64_t>(oldColor)<<32)|newColor);
}
