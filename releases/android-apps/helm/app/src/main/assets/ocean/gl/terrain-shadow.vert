#version 300 es
precision highp float;
// COMMON
// TERRAIN_LIGHT
layout(location=0) in vec3 aPosition;
void main(){gl_Position=rockLightPosition(aPosition);}
