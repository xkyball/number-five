// Same fixed-world projection for direct and mirrored views. Orthographic sun camera.
vec4 rockLightPosition(vec3 world) {
    vec3 s=normalize(uSun);
    vec3 right=normalize(cross(abs(s.y)>.98?vec3(0,0,1):vec3(0,1,0),s));
    vec3 up=cross(s,right);
    vec3 p=world-vec3(0,320,0);
    return vec4(dot(p,right)/9000.0,dot(p,up)/9000.0,-dot(p,s)/12000.0,1);
}
