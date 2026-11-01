#version 300 es
precision highp float;
precision highp sampler2D;
// COMMON
// WATER_SURFACE
uniform sampler2D uReflection;
uniform sampler2D uDisplacement;
uniform vec2 uResolution;
in vec3 vWorld;
in vec2 vSurface;
out vec4 outColor;
vec3 displacement(vec2 q,float lod) {
    return textureLod(uDisplacement,q/32.0,lod).rgb+
        textureLod(uDisplacement,(q*.37+vec2(11,19))/32.0,max(0.0,lod-1.43)).rgb*.55;
}
void main() {
    vec3 eye=vec3(0,4.2,0), v=normalize(eye-vWorld);
    float a=uWindTo-.9272952;
    mat2 rotation=mat2(cos(a),sin(a),-sin(a),cos(a));
    vec2 q=transpose(rotation)*vSurface*WATER_FOOTPRINT;
    float footprint=max(length(dFdx(q)),length(dFdy(q)));
    float lod=clamp(log2(max(footprint,.25)/.25),0.0,7.0);
    float e=.25*exp2(lod);
    vec3 dx=(displacement(q+vec2(e,0),lod)-displacement(q-vec2(e,0),lod))/(2.0*e)*waveAmplitude();
    vec3 dz=(displacement(q+vec2(0,e),lod)-displacement(q-vec2(0,e),lod))/(2.0*e)*waveAmplitude();
    // Derivatives of the actual displaced mesh: compressed crests also steepen normals.
    vec3 tx=vec3(1.0+dx.x*waveChop(),dx.y,dx.z*waveChop());
    vec3 tz=vec3(dz.x*waveChop(),dz.y,1.0+dz.z*waveChop());
    vec3 coarseNormal=normalize(cross(tz,tx));
    vec2 slope=-coarseNormal.xz/max(.25,coarseNormal.y);
    float shortCrest;
    vec4 shortBand=shortWater(q,dFdx(q),dFdy(q),shortCrest);
    slope+=shortBand.yz;
    slope=rotation*slope;
    vec3 n=normalize(vec3(-slope.x,1.0,-slope.y));
    vec3 reflected=reflect(-v,n);
    float fresnel=.0204+.9796*pow(1.0-max(dot(n,v),0.0),5.0);
    vec2 uv=gl_FragCoord.xy/uResolution;
    vec2 screenSlope=vec2(n.x*cos(uHeading)-n.z*sin(uHeading),n.x*sin(uHeading)+n.z*cos(uHeading));
    vec2 reflectUv=clamp(uv+screenSlope*.060,vec2(.002),vec2(.998));
    vec3 reflection=texture(uReflection,reflectUv).rgb;
    // Unresolved rough waves still scatter reflection; mip-filtered slopes must not
    // turn distant rough water into a perfect mirror of the entire coast.
    float unresolved=.85*(1.0-exp(-footprint*.8));
    reflection=mix(reflection,skyBackground(reflected),max(unresolved,smoothstep(.08,.65,reflected.y)));
    // Marine-blue troughs with bright blue/turquoise sunlit shoulders.
    vec3 body=mix(vec3(.008,.15,.40),vec3(.025,.55,.92),clamp(.44+n.x*.65+n.z*.45,0.0,1.0));
    body*=mix(.09,1.12,daylight())*(1.0-.48*uCloudCover);
    // Sunlit forward scattering reveals translucent blue wave shoulders.
    float shoulder=smoothstep(-.05,.48,vWorld.y*WATER_FOOTPRINT)*pow(1.0-max(dot(n,v),0.0),2.0);
    body+=vec3(.015,.24,.32)*shoulder*directLight();
    vec3 color=mix(body,reflection,clamp(fresnel,.0204,.86));
    vec3 halfVector=normalize(v+uSun);
    // Filtered unresolved slope energy broadens the sun lobe instead of shimmering or
    // disappearing into a flat mirror. The sky reflection excludes the explicit sun disc.
    float normalVariance=min(.035,.25*(dot(dFdx(slope),dFdx(slope))+dot(dFdy(slope),dFdy(slope))));
    float alpha2=.045*.045+shortBand.w+normalVariance;
    float nh=max(dot(n,halfVector),0.0), nv=max(dot(n,v),.02), nl=max(dot(n,uSun),0.0);
    float denominator=nh*nh*(alpha2-1.0)+1.0;
    float distribution=alpha2/(3.14159265*denominator*denominator);
    float gv=2.0*nv/(nv+sqrt(alpha2+(1.0-alpha2)*nv*nv));
    float gl=2.0*nl/max(.0001,nl+sqrt(alpha2+(1.0-alpha2)*nl*nl));
    float sunFresnel=.0204+.9796*pow(1.0-max(dot(v,halfVector),0.0),5.0);
    float sunRadiance=distribution*gv*gl*sunFresnel/max(.08,4.0*nv);
    float glint=1.0-exp(-sunRadiance*.8*directLight());
    color=mix(color,vec3(.99,.97,.91),clamp(glint,0.0,.94));
    // Whitecaps occur where horizontal displacement compresses a crest. No full-screen noise.
    float jacobian=tx.x*tz.z-tx.z*tz.x;
    float crest=smoothstep(.03,.24,displacement(q,lod).y*waveAmplitude());
    float breaking=(1.0-smoothstep(.55,.91,jacobian))*crest;
    float lace=mix(.43,fbm(q*2.7+vec2(displacement(q,lod).x)),1.0-smoothstep(.08,.38,footprint));
    float largeFoam=breaking*smoothstep(.12,.66,lace)*1.25;
    // Signed crest curvature plus positive short-wave height prevents a white blanket.
    float shortFoam=smoothstep(.28,.56,shortCrest)*smoothstep(.015,.07,shortBand.x);
    shortFoam*=smoothstep(.20,.62,lace)*.92;
    float foam=max(largeFoam,shortFoam);
    foam*=1.0-smoothstep(90.0,320.0,length(vWorld.xz)*WATER_FOOTPRINT);
    vec3 foamColor=vec3(.80,.90,.96)*mix(.06,1.0,daylight())*(.55+.45*directLight());
    color=mix(color,foamColor,clamp(foam,0.0,.9));
    outColor=vec4(atmosphere(color,vWorld),1);
}
