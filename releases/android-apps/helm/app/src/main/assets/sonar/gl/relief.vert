#version 300 es
precision highp float;
precision highp int;
precision highp usampler2DArray;
layout(location=0) in vec2 aGrid;
uniform usampler2DArray uProfiles;
uniform sampler2D uTiming;
uniform int uHead,uCount,uAligned,uSlice,uCapacity,uAutoMask;
uniform float uNewest,uGainOffset,uSeconds,uStart,uEnd,uGain,uGamma,uThreshold;
uniform float uYaw,uPitch,uZoom,uAspect;
out vec3 vWorld;
out float vAmplitude;
flat out float vValid;
int ageAt(float column){return int(round(column/512.0*float(max(0,uCount-1))));}
int slotAt(int age){return (uHead-1-age+uCapacity)%uCapacity;}
vec4 meta(int s,int stripe){return texelFetch(uTiming,ivec2(s%256,s/256+stripe*((uCapacity+255)/256)),0);}
vec2 stamp(int age){vec4 t=meta(slotAt(age),0);float d=uNewest-t.x;if(d<0.0)d+=16777216.0;return vec2(-d/1000.0,t.y);}
float mapped(int age,float raw){int s=slotAt(age);vec4 t=meta(s,1);
 float g=(uAutoMask&1)!=0&&t.w>0.5?clamp(t.x*uGainOffset,0.063095734,63.095734):uGain;
 float ig=(uAutoMask&2)!=0&&t.w>0.5?t.y:1.0/uGamma;
 float cut=(uAutoMask&4)!=0&&t.w>0.5?t.z:uThreshold;
 return pow(clamp((raw/max(meta(s,0).z,1.0)*g-cut)/max(.000001,1.0-cut),0.0,1.0),ig);
}
float value(int age,float bin){
    // Peak-preserving bounded display pooling: up to 24 time columns × five range bins.
    // These are source amplitudes, never inferred terrain/material or new measurements.
    int span=max(1,int(ceil(float(uCount)/512.0)));
    int width=max(1,int(ceil((uEnd-uStart)/256.0)));
    float raw=0.0;
    for(int h=0;h<24;h++) for(int r=0;r<5;r++) {
        if(h<span && r<width && age+h<uCount) {
            int index=int(clamp(bin+float(r)-float(width-1)*.5,uStart,uEnd));
            // Never pool across a transport gap.
            if(meta(slotAt(age+h),0).w>0.5 && abs(stamp(age+h).y-stamp(age).y)<.5)
                raw=max(raw,mapped(age+h,float(texelFetch(uProfiles,ivec3(index,slotAt(age+h)%256,slotAt(age+h)/256),0).r)));
        }
    }
    return raw;
}
void main(){
    int age=ageAt(aGrid.x),newer=ageAt(max(0.0,aGrid.x-1.0));
    vec2 t=stamp(age),n=stamp(newer);
    vValid=(meta(slotAt(age),0).w>0.5 && meta(slotAt(newer),0).w>0.5 && uCount>1 && age>newer && abs(t.y-n.y)<.5 && t.x<n.x)?1.0:0.0;
    float bin=mix(uStart,uEnd,aGrid.y);
    float amplitude=value(age,bin);
    if(uSlice==1){
        // Exact view ping, without history pooling; same raw bins as aligned A-scope.
        float raw=float(texelFetch(uProfiles,ivec3(int(round(bin)),slotAt(0)%256,slotAt(0)/256),0).r);
        amplitude=mapped(0,raw);
        vValid=uCount>0&&meta(slotAt(0),0).w>0.5?1.0:0.0;
    }
    vec3 p=vec3(1.55+3.1*t.x/max(.1,uSeconds),amplitude*.75,(aGrid.y-.5)*2.0);
    vWorld=p;vAmplitude=amplitude;
    vec3 q=vec3(cos(uYaw)*p.x+sin(uYaw)*p.z,p.y-.25,-sin(uYaw)*p.x+cos(uYaw)*p.z);
    q=vec3(q.x,cos(uPitch)*q.y-sin(uPitch)*q.z,sin(uPitch)*q.y+cos(uPitch)*q.z);
    // Orthographic range comparison: time right, range down, amplitude into the screen.
    // Range endpoints fill exactly the same TOP..BOTTOM viewport as the A-scope.
    gl_Position=uAligned==1 ? vec4(p.x/1.55*.998,-p.z,-p.y*.7,1.0)
        : vec4(q.x*uZoom/uAspect,q.y*uZoom,-q.z*.2,1.0);
}
