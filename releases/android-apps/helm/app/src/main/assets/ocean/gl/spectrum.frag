#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
// Hermitian travelling spectrum; packed displacement follows David Li/waves (MIT).
// No accumulated simulation clock: every frequency is periodic at the existing 8*pi rebase.
uniform sampler2D uInput;
uniform float uTime;
out vec4 outColor;
vec2 mul(vec2 a,vec2 b) { return vec2(a.x*b.x-a.y*b.y,a.x*b.y+a.y*b.x); }
vec2 timesI(vec2 a) { return vec2(-a.y,a.x); }
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy); int n=textureSize(uInput,0).x;
    ivec2 signedP=ivec2(p.x<n/2?p.x:p.x-n,p.y<n/2?p.y:p.y-n);
    vec2 k=6.28318530718*vec2(signedP)/32.0;
    float magnitude=length(k);
    if(magnitude==0.0 || p.x==n/2 || p.y==n/2) { outColor=vec4(0); return; }
    float omega=floor(sqrt(9.81*magnitude)*4.0+.5)*.25;
    float phase=omega*mod(uTime,25.132741228718345);
    vec2 w=vec2(cos(phase),sin(phase));
    vec2 a=texelFetch(uInput,p,0).xy;
    vec2 b=texelFetch(uInput,(ivec2(n)-p)%n,0).xy*vec2(1,-1);
    vec2 h=mul(a,w)+mul(b,w*vec2(1,-1));
    vec2 dx=-timesI(h)*(k.x/magnitude)*.70;
    vec2 dz=-timesI(h)*(k.y/magnitude)*.70;
    outColor=vec4(dx+timesI(h),dz);
}
