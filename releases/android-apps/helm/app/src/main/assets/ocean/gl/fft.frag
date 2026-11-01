#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
// Stockham butterfly adapted from David Li, waves (MIT, 2014).
// See THIRD_PARTY_NOTICES.md. ES3 texelFetch, positive inverse transform, packed pairs.
uniform sampler2D uInput;
uniform int uStage;
uniform int uHorizontal;
out vec4 outColor;
vec2 mul(vec2 a,vec2 b) { return vec2(a.x*b.x-a.y*b.y,a.x*b.y+a.y*b.x); }
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
    int n=textureSize(uInput,0).x;
    int index=uHorizontal==1?p.x:p.y;
    int evenIndex=(index/uStage)*(uStage/2)+index%(uStage/2);
    ivec2 e=uHorizontal==1?ivec2(evenIndex,p.y):ivec2(p.x,evenIndex);
    ivec2 o=e+(uHorizontal==1?ivec2(n/2,0):ivec2(0,n/2));
    vec4 a=texelFetch(uInput,e,0), b=texelFetch(uInput,o,0);
    float angle=6.28318530718*float(index)/float(uStage);
    vec2 w=vec2(cos(angle),sin(angle));
    outColor=vec4(a.xy+mul(w,b.xy),a.zw+mul(w,b.zw));
}
