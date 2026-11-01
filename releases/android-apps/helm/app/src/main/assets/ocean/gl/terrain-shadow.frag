#version 300 es
precision highp float;
out vec4 outColor;
void main(){
    vec3 encoded=fract(gl_FragCoord.z*vec3(1,255,65025));
    encoded-=encoded.yzz*vec3(1.0/255.0,1.0/255.0,0);
    outColor=vec4(encoded,1);
}
