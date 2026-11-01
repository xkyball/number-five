#version 300 es
precision highp float;
precision highp int;
in vec3 vWorld;
in float vAmplitude;
flat in float vValid;
uniform int uPalette,uNight,uSlice;
out vec4 outColor;
vec3 color(float a){
    if(uNight==1||uPalette==3)return vec3(5.0+a*135.0,2.0+a*a*46.0,2.0+a*a*10.0)/255.0;
    if(uPalette==2)return vec3(8.0+a*247.0,16.0+a*159.0,22.0+a*13.0)/255.0;
    if(uPalette==1)return vec3(246.0-a*175.0,249.0-a*211.0,250.0-a*240.0)/255.0;
    vec3 stops[6]=vec3[6](vec3(6,19,35),vec3(0,62,133),vec3(0,197,223),vec3(242,222,32),vec3(255,111,0),vec3(235,28,26));
    float t=clamp(a,0.0,1.0)*5.0;int i=min(4,int(t));return mix(stops[i],stops[i+1],t-float(i))/255.0;
}
void main(){
    if(vValid<.5)discard;
    if(uSlice==1){outColor=vec4(uNight==1?vec3(.88,.10,.025):vec3(.90,.98,1.0),1);return;}
    vec3 normal=normalize(cross(dFdx(vWorld),dFdy(vWorld)));
    if(normal.y<0.0)normal=-normal;
    float light=.48+.52*max(0.0,dot(normal,normalize(vec3(-.4,1.0,.5))));
    outColor=vec4(color(vAmplitude)*light,1.0);
}
