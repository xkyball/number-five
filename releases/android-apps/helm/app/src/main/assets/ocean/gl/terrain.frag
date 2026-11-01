#version 300 es
precision highp float;
// COMMON
// TERRAIN_LIGHT
in vec3 vWorld;
in vec3 vNormal;
in float vShadow;
in vec4 vRock;
uniform sampler2D uTerrainShadow;
uniform float uShadowTexel;
out vec4 outColor;
float sunlightVisibility(vec3 n) {
    vec3 q=rockLightPosition(vWorld).xyz*.5+.5;
    if(any(lessThan(q,vec3(0)))||any(greaterThan(q,vec3(1)))) return 1.0;
    float bias=.00035+.0009*(1.0-max(dot(n,uSun),0.0));
    float visible=0.0;
    for(int y=-1;y<=1;y++) for(int x=-1;x<=1;x++) {
        vec3 encodedDepth=texture(uTerrainShadow,q.xy+vec2(x,y)*uShadowTexel).rgb;
        float depth=dot(encodedDepth,vec3(1,1.0/255.0,1.0/65025.0));
        visible+=step(q.z-bias,depth);
    }
    return visible/9.0;
}
void main() {
    if(vWorld.y<0.0) discard;
    vec3 n=normalize(vNormal);
    float folded=step(.5,vRock.z)*(1.0-step(1.5,vRock.z));
    // Bedding follows each massif's own strike/dip. All coordinates remain world anchored.
    float bedding=vWorld.y+mix(.025,.22,folded)*vRock.x+mix(.035,.34,folded)*vRock.y;
    bedding+=folded*18.0*sin(vRock.x*.005+vRock.w);
    float layer=bedding/32.0;
    float width=max(fwidth(layer),.025);
    float seam=1.0-smoothstep(.02,.055+width,abs(fract(layer)-.5));
    float grain=fbm(vec2(vRock.x,bedding)*vec2(.027,.075)+vRock.w*13.0);
    vec3 albedo=mix(vec3(.40,.39,.35),vec3(.70,.67,.59),smoothstep(.20,.80,grain));
    albedo=mix(albedo,vec3(.27,.24,.20),seam*.35);
    float joints=abs(fract(vRock.x/63.0+bedding*.002+fbm(vRock.xy*.004)*.8)-.5);
    float jointWidth=max(fwidth(vRock.x/63.0+bedding*.002),.012);
    albedo*=1.0-(1.0-smoothstep(.01,.025+jointWidth,joints))*.22;
    // Sparse plants on upward-facing ledges; cliffs and undercuts remain bare stone.
    float plants=smoothstep(.65,.88,n.y)*smoothstep(.56,.76,fbm(vRock.xy*.012+19.0));
    plants*=1.0-smoothstep(200.0,500.0,vWorld.y);
    albedo=mix(albedo,vec3(.18,.23,.14),plants*.8);
    albedo*=1.0-(1.0-smoothstep(0.0,9.0,vWorld.y))*.28;
    vec3 ambient=vec3(.28,.33,.41)*mix(.08,1.0,daylight())*vShadow;
    vec3 direct=mix(vec3(1,.52,.28),vec3(1.16,1.04,.86),smoothstep(0.0,.4,uSun.y));
    vec3 color=albedo*(ambient+direct*max(dot(n,uSun),0.0)*directLight()*sunlightVisibility(n));
    color=mix(color,vec3(.24,.40,.59)*mix(.08,1.0,daylight()),1.0-exp(-length(vWorld.xz)*.000015));
    outColor=vec4(atmosphere(color,vWorld),1);
}
