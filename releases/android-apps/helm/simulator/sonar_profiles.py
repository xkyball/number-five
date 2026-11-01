"""Deterministic illustrative envelope generator, not calibrated acoustic simulation.

Ground truth stays in the simulator. Profiles are relative linear counts; no
material classifier, RF/IQ channel or hardware ADC accuracy is implied.
"""
import math
import random
from .sonar_scenarios import SCENARIOS,scene

MODEL_VERSION = 3
PROFILE_BOUNDS = {'pulseUs': (50,600), 'beamDegrees': (5,40), 'receiverGainDb': (-12,18)}
PROFILE_CHOICES = {'scenario': SCENARIOS, 'bottomModel': ('mixed','sand','mud','rock','vegetation'),
                   'noiseModel': ('normal','bubbles','impulse','clipping')}
PROFILE_DEFAULTS = {'scenario':'navigation', 'bottomModel':'mixed','pulseUs':180.0,'beamDegrees':20.0,
                    'receiverGainDb':0.0,'noiseModel':'normal'}
MATERIALS = {'sand':(.68,.10,.45,0.0),'mud':(.45,.20,.78,0.0),
             'rock':(.84,.075,.25,0.0),'vegetation':(.57,.14,.58,1.4)}


def world_parameters(east,north,kind):
    # Overlapping continuous property ranges, never a unique material pixel pattern.
    variation=math.sin(east*.021+north*.017)*math.cos(north*.013)
    if kind=='mixed':
        mix=.5+.5*math.sin(east*.018+north*.012)
        a,b=MATERIALS['sand'],MATERIALS['rock']
        values=tuple(x*(1-mix)+y*mix for x,y in zip(a,b))
        truth={'model':'mixed','rockWeight':mix,'sandWeight':1-mix}
    else:values=MATERIALS[kind];truth={'model':kind}
    reflection,roughness,diffuse,vegetation=values
    return (reflection+.10*variation,roughness*(1+.35*variation),diffuse,vegetation),truth


def generate_profile(depth,east,north,elapsed,ping_id,settings,bins=1024,meters_per_bin=.03):
    if not all(math.isfinite(x) for x in (depth,east,north,elapsed)) or depth<=0:
        raise ValueError('finite positive depth and finite world/time inputs required')
    scenario=settings.get('scenario','navigation')
    scenario_targets=[];rock_returns=[];scenario_truth={}
    if scenario!='navigation':
        depth,scenario_targets,rock_returns,scenario_truth=scene(scenario,elapsed,settings['beamDegrees'],settings['targets'])
        east,north=scenario_truth['transectEastM'],0.0
    properties,truth=world_parameters(east,north,settings['bottomModel'])
    reflection,roughness,diffuse,vegetation=properties
    pulse_range=1500*settings['pulseUs']*1e-6/2
    half_beam=math.radians(settings['beamDegrees']*.5)
    footprint_radius=depth*math.tan(half_beam)
    beam_spread=depth*(1/math.cos(half_beam)-1)
    sigma=math.sqrt((pulse_range/2.355)**2+roughness**2+(.3*beam_spread)**2)
    amplitude=47000*reflection
    gain=10**(settings['receiverGainDb']/20)
    if settings['noiseModel']=='clipping':gain*=8
    random_source=random.Random((ping_id*0x9e3779b1+round(east*10)*31+round(north*10)*17)&0xffffffffffffffff)
    target_returns=list(scenario_targets)
    if scenario=='navigation' and settings['targets']:
        for tx,ty,tz in ((0.,0.,6.6),(35.,20.,8.0)):
            lateral=math.hypot(east-tx,north-ty)
            if lateral<footprint_radius*2 and tz<depth-.8:
                target_returns.append((math.hypot(tz,lateral),18000*math.exp(-2*(lateral/max(.1,footprint_radius))**2)))
    def gaussian(distance,center,width):return math.exp(-.5*((distance-center)/width)**2)
    second_range=2*depth  # Never clamp out-of-window energy to the last bin.
    max_range=(bins-1)*meters_per_bin
    impulse_bin=random_source.randrange(20,bins-20) if ping_id%53==0 else -10
    samples=[];clipped=0
    for i in range(bins):
        r=i*meters_per_bin
        noise=random_source.gauss(0,110)
        # Lower variation near a strong return avoids an arbitrary noise spike being its peak.
        noise*=1-.85*gaussian(r,depth,max(.1,sigma*2))
        value=650+noise+230*math.exp(-r/12)
        value+=9000*gaussian(r,.18,.10+pulse_range*.25)
        value+=amplitude*gaussian(r,depth,sigma)*(.45 if rock_returns else 1)
        for distance,weight in rock_returns:value+=amplitude*weight*gaussian(r,distance,sigma)
        if scenario=='rock_weed' and depth-1.7<r<depth-.15:
            value+=amplitude*.18*gaussian(r,depth-.85,.42)*(.4+random_source.random())
        if r>depth+.15:value+=8500*diffuse*math.exp(-(r-depth)/(.3+diffuse*1.3))*(.65+.35*random_source.random())
        if second_range+3*sigma<max_range:value+=amplitude*.16*gaussian(r,second_range,sigma*1.5)
        if vegetation and depth-vegetation<r<depth-.15:
            value+=amplitude*.22*gaussian(r,depth-vegetation*.55,vegetation*.35)*(0.4+random_source.random())
        for distance,strength in target_returns:value+=strength*gaussian(r,distance,max(.07,pulse_range/2.355))
        if settings['noiseModel']=='bubbles':value+=18000*gaussian(r,depth*.3+math.sin(elapsed)*.3,.45)*(0.3+random_source.random())
        if (settings['noiseModel']=='impulse' or scenario=='noise_second') and impulse_bin<=i<impulse_bin+2:value+=25000
        value*=gain
        if value>65535:clipped+=1
        samples.append(min(65535,max(0,round(value))))
    truth.update(scenario_truth)
    truth.update(bottomDepthM=depth,footprintRadiusM=footprint_radius,echoSigmaM=sigma,
                 secondEchoRangeM=second_range,secondEchoInWindow=second_range+3*sigma<max_range,
                 vegetationHeightM=vegetation,modelVersion=MODEL_VERSION)
    diagnostics={'clippedBins':clipped,'peakCount':max(samples),'classification':'UNKNOWN',
                 'signalKind':'SYNTHETIC_ENVELOPE','amplitudeEncoding':'LINEAR_RELATIVE_COUNTS',
                 'sampleStartDelayUs':0,'modelVersion':MODEL_VERSION}
    return samples,truth,diagnostics
