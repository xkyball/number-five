#version 300 es
precision highp float;
// COMMON
in vec2 vUv;
out vec4 outColor;
void main() { vec3 ray=cameraRay(vUv); ray.y*=uMirror; outColor=vec4(uMirror<0.0?skyBackground(ray):sky(ray),1); }
