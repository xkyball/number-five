// Water-only physical footprint. Scene camera/terrain remain fixed. Scaling both wavelength
// and height together preserves slopes. Build28 restores the original 1x footprint.
const float WATER_FOOTPRINT = 1.0;
// Independent finite short-wave band; x=height, yz=slopes, w=unresolved slope variance.
// Wavelength, direction, steepness and phase are authored decoratively, not measured sea state.
vec4 shortWater(vec2 q,vec2 pixelX,vec2 pixelY,out float crestCompression) {
    vec4 result=vec4(0);
    crestCompression=0.0;
    for(int i=0;i<24;i++) {
        float f=float(i);
        float wavelength=i<20 ? 2.6*pow(.87,f) : .15*pow(.64,f-20.0);
        float angle=.6435+sin(f*2.399+1.3)*(i<20?1.12:2.1);
        vec2 direction=vec2(cos(angle),sin(angle));
        float k=6.28318530718/wavelength;
        // Gravity dispersion, capillary contribution for the shortest waves. Quantized only
        // to retain the existing exact 8*pi monotonic-clock rebase, not simulator time.
        float omega=floor(sqrt(9.81*k*(1.0+(k/370.0)*(k/370.0)))*4.0+.5)*.25;
        float phase=dot(q,direction)*k-omega*mod(uTime,25.132741228718345)+f*2.71828;
        float steepness=(i<20?.16:.035)*(.7+.3*sin(f*1.73+1.0))*(.7+uRoughness);
        float frequency=max(abs(dot(pixelX,direction)),abs(dot(pixelY,direction)))*k;
        float bandWeight=1.0-smoothstep(1.2,3.14159265,frequency);
        bandWeight*=i<12?1.0:uDetail;
        float harmonicWeight=(1.0-smoothstep(1.2,3.14159265,frequency*2.0))*(i<12?1.0:uDetail);
        float crestShape=i<20?.22:0.0;
        // Sharper crests and broader troughs, with analytically matching derivatives.
        result.x+=steepness/k*(sin(phase)*bandWeight-crestShape*cos(phase*2.0)*harmonicWeight);
        result.yz+=direction*steepness*(cos(phase)*bandWeight+2.0*crestShape*sin(phase*2.0)*harmonicWeight);
        crestCompression+=steepness*(sin(phase)*bandWeight-4.0*crestShape*cos(phase*2.0)*harmonicWeight);
        result.w+=.5*steepness*steepness*((1.0-bandWeight*bandWeight)+4.0*crestShape*crestShape*(1.0-harmonicWeight*harmonicWeight));
    }
    return result;
}
