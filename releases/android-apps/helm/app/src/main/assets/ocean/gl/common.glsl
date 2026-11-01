// Original No5 procedural scenery. World x=east, y=up, z=north; never geographic terrain.
uniform float uHeading;
uniform float uTime;
uniform float uRoughness;
uniform float uAspect;
uniform float uMirror;
uniform float uDetail;
uniform vec3 uSun;
uniform float uCloudCover;
uniform float uFogDensity;
uniform float uWindTo;
// Decorative artistic gain, not a significant-wave-height measurement.
float waveAmplitude() { return 3.0+uRoughness*4.5; }
float waveChop() { return 1.30; }
float daylight() { return smoothstep(-.14,.16,uSun.y); }
float directLight() { return smoothstep(-.02,.10,uSun.y)*(1.0-.90*uCloudCover)*exp(-uFogDensity*120.0); }
vec2 windRotate(vec2 d) {
    float a=uWindTo-.9272952;
    return vec2(d.x*cos(a)+d.y*sin(a),-d.x*sin(a)+d.y*cos(a));
}
const float TAN_HALF_FOV = 0.8004249;
const float PITCH = 0.23;
float hash21(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
    return mix(mix(hash21(i),hash21(i+vec2(1,0)),f.x),mix(hash21(i+vec2(0,1)),hash21(i+vec2(1)),f.x),f.y);
}
float fbm(vec2 p) { return .55*noise(p)+.27*noise(p*2.03+17.7)+.13*noise(p*4.11+31.2)+.05*noise(p*8.3); }
vec3 cameraRay(vec2 uv) {
    vec2 q=(uv*2.0-1.0)*vec2(TAN_HALF_FOV,TAN_HALF_FOV/uAspect);
    vec3 v=normalize(vec3(q.x,q.y*cos(PITCH)-sin(PITCH),q.y*sin(PITCH)+cos(PITCH)));
    return vec3(v.x*cos(uHeading)+v.z*sin(uHeading),v.y,-v.x*sin(uHeading)+v.z*cos(uHeading));
}
vec4 projectWorld(vec3 p) {
    p.y*=uMirror;
    p.y-=4.2;
    float z=p.x*sin(uHeading)+p.z*cos(uHeading);
    float x=p.x*cos(uHeading)-p.z*sin(uHeading);
    float y=p.y*cos(PITCH)+z*sin(PITCH);
    z=z*cos(PITCH)-p.y*sin(PITCH);
    return vec4(x/TAN_HALF_FOV,y*uAspect/TAN_HALF_FOV,1.0000556*z-.500014,z);
}
vec3 skyRadiance(vec3 ray,bool includeSun) {
    float up=clamp(ray.y,0.0,1.0), day=daylight();
    vec3 horizon=mix(vec3(.035,.05,.09),vec3(.70,.86,.94),day);
    vec3 zenith=mix(vec3(.012,.025,.06),vec3(.22,.52,.80),day);
    vec3 c=mix(horizon,zenith,pow(up,.45));
    float dusk=exp(-abs(uSun.y+.02)*12.0)*(1.0-uCloudCover*.65);
    float facing=pow(max(dot(normalize(vec3(ray.x,0,ray.z)),normalize(vec3(uSun.x,.001,uSun.z))),0.0),3.0);
    c=mix(c,vec3(.72,.32,.18),dusk*facing*exp(-up*8.0)*.65);
    float sun=max(dot(ray,uSun),0.0);
    if(includeSun) c+=vec3(1.0,.84,.57)*(pow(sun,600.0)*1.8+pow(sun,12.0)*.12)*directLight();
    // Fixed world cloud field: changing weather changes coverage, never heading-relative billboards.
    vec2 p=ray.xz/max(.12,ray.y)*1.8;
    float cloud=smoothstep(mix(.72,.22,uCloudCover),mix(.90,.62,uCloudCover),fbm(p));
    vec3 cloudColor=mix(vec3(.045,.06,.09),vec3(.79,.83,.86),day);
    c=mix(c,cloudColor,clamp(uCloudCover*.65+cloud*uCloudCover*.5,0.0,.96));
    float veil=clamp(uFogDensity*180.0,0.0,.92)*(1.0-up);
    return mix(c,mix(vec3(.025,.04,.06),vec3(.73,.79,.81),day),veil);
}
vec3 sky(vec3 ray) { return skyRadiance(ray,true); }
vec3 skyBackground(vec3 ray) { return skyRadiance(ray,false); }
vec3 atmosphere(vec3 c,vec3 world) {
    float fog=1.0-exp(-length(world.xz)*uFogDensity);
    return mix(c,sky(normalize(vec3(world.x,.06*length(world.xz),world.z))),fog);
}
