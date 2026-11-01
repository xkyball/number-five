#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uScene;
uniform sampler2D uMask;
out vec4 outColor;
void main() {
    // Android Canvas mask uses top-left origin; GL framebuffer uses bottom-left.
    float a=texture(uMask,vec2(vUv.x,1.0-vUv.y)).a;
    vec3 c=texture(uScene,vUv).rgb;
    outColor=vec4(c*a,a); // TextureView consumes premultiplied alpha.
}
