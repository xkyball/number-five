#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 vUv;
uniform samplerExternalOES uVideo;
uniform sampler2D uMask;
uniform mat4 uTextureMatrix;
uniform float uHeading;
uniform float uAspect;
uniform float uFog;
out vec4 outColor;
void main() {
    vec2 screen=vec2(vUv.x,1.0-vUv.y);
    float t=tan(0.675);
    vec3 ray=normalize(vec3((screen.x-.5)*2.0*t,(.28-screen.y)*2.0*t/uAspect,1.0));
    float c=cos(uHeading),s=sin(uHeading);
    ray=vec3(c*ray.x+s*ray.z,ray.y,c*ray.z-s*ray.x);
    vec2 panorama=vec2(fract(atan(ray.x,ray.z)/6.28318530718+.5),.5-asin(clamp(ray.y,-1.0,1.0))/3.14159265359);
    vec2 textureUv=(uTextureMatrix*vec4(panorama.x,1.0-panorama.y,0,1)).xy;
    vec3 color=texture(uVideo,textureUv).rgb;
    // Deliberate screen-space demonstration, not a physical change to recorded weather.
    float fog=uFog*(.35+.65*exp(-abs(screen.y-.28)*4.0));
    color=mix(color,vec3(.69,.76,.79),fog);
    float alpha=texture(uMask,screen).a;
    outColor=vec4(color*alpha,alpha);
}
