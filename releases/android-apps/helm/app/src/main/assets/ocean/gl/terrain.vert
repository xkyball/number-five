#version 300 es
precision highp float;
// COMMON
layout(location=0) in vec3 aPosition;
layout(location=1) in vec3 aNormal;
layout(location=2) in float aShadow;
layout(location=3) in vec4 aRock;
out vec4 vRock;
out vec3 vWorld;
out vec3 vNormal;
out float vShadow;
void main() {
    vRock=aRock; vWorld=aPosition; vNormal=aNormal; vShadow=aShadow;
    gl_Position=projectWorld(vWorld);
}
