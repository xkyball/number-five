#version 300 es
precision highp float;
precision highp sampler2D;
// COMMON
// WATER_SURFACE
uniform sampler2D uDisplacement;
layout(location=0) in vec2 aPosition;
out vec3 vWorld;
out vec2 vSurface;
void main() {
    float a=uWindTo-.9272952;
    mat2 rotation=mat2(cos(a),sin(a),-sin(a),cos(a));
    vec2 q=transpose(rotation)*aPosition*WATER_FOOTPRINT;
    // Radial mesh spacing grows with distance; remove unrepresentable geometric frequencies.
    float radius=length(aPosition);
    float rows=uDetail<.6?48.0:80.0, columns=uDetail<.6?128.0:256.0;
    // Bound the actual logarithmic radial edge plus angular edge of OceanGlGeometry.water.
    float spacing=max(.25,((radius+.6)*(exp(log(10001.0)/rows)-1.0)+radius*6.2831853/columns)*WATER_FOOTPRINT);
    float lod=clamp(log2(spacing/.25),0.0,7.0);
    // Two spatial scales of the same deterministic spectrum: broad rolling swell and chop.
    vec3 d=textureLod(uDisplacement,q/32.0,lod).rgb;
    d+=textureLod(uDisplacement,(q*.37+vec2(11,19))/32.0,max(0.0,lod-1.43)).rgb*.55;
    d*=waveAmplitude();
    float shortCrest;
    d.y+=shortWater(q,vec2(spacing,0),vec2(0,spacing),shortCrest).x;
    d/=WATER_FOOTPRINT;
    d.xz*=waveChop();
    d.xz=rotation*d.xz;
    vec3 p=vec3(aPosition.x,0,aPosition.y)+d;
    vWorld=p; vSurface=aPosition;
    gl_Position=projectWorld(p);
}
