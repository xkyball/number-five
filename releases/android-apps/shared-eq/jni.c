#include <jni.h>
#include <stdint.h>
#include <stddef.h>
extern uint8_t* no5_eq_process(const uint8_t*,size_t,size_t*);
extern void no5_eq_free(uint8_t*,size_t);
JNIEXPORT jbyteArray JNICALL Java_com_no5_helm_SharedEqNative_process(JNIEnv* env,jclass c,jbyteArray input){
 (void)c;if(!input)return NULL;jsize n=(*env)->GetArrayLength(env,input);if(n<0||n>65536)return NULL;
 jbyte* bytes=(*env)->GetByteArrayElements(env,input,NULL);if(!bytes)return NULL;size_t len=0;
 uint8_t* out=no5_eq_process((const uint8_t*)bytes,(size_t)n,&len);(*env)->ReleaseByteArrayElements(env,input,bytes,JNI_ABORT);
 if(!out)return NULL;if(len>131072){no5_eq_free(out,len);return NULL;}
 jbyteArray result=(*env)->NewByteArray(env,(jsize)len);if(result)(*env)->SetByteArrayRegion(env,result,0,(jsize)len,(const jbyte*)out);no5_eq_free(out,len);return result;
}
