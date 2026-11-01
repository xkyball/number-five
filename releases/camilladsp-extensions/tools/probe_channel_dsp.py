#!/usr/bin/env python3
"""Targeted impulse/route proof against an existing DSP binary; no build/live IO."""
import argparse
import asyncio
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import struct
import time
import aiohttp

RATE, BLOCK = 48000, 1024


def configuration(output, front_only=False):
    mapping = [dict(dest=i, sources=[dict(channel=i % 2, gain=0.0)]) for i in range(6)]
    for i in (2, 3):
        mapping[i]['sources'] = [dict(channel=c, gain=-6.020599913279624) for c in (0, 1)]
    if front_only:
        for i in (2, 3, 4, 5):
            mapping[i]['mute'] = True
    routing = 'front_stereo_fullrange' if front_only else 'stereo_4_1'
    return dict(title='No5 channel audit', description='No5 sound v1 / '+routing,
        devices=dict(samplerate=RATE, chunksize=BLOCK, volume_ramp_time=0,
            capture=dict(type='Stdin', channels=2, format='F64_LE'),
            playback=dict(type='File', channels=6, format='F64_LE', filename=str(output))),
        mixers=dict(stereo=dict(channels={'in': 2, 'out': 6}, mapping=mapping)),
        pipeline=[dict(type='Mixer', name='stereo')])


def band_impulse(count):
    """Independent RBJ HP/LP evaluation for the declared 150..7000-Hz band."""
    result = [1.0]+[0.0]*(count-1)
    for kind, frequency in [('high',150.0), ('low',7000.0)]:
        omega=2*math.pi*frequency/RATE; cosine=math.cos(omega)
        alpha=math.sin(omega)/(2*math.sqrt(.5)); a0=1+alpha
        a1=-2*cosine/a0; a2=(1-alpha)/a0
        if kind=='high': b0=(1+cosine)/2/a0; b1=-(1+cosine)/a0
        else: b0=(1-cosine)/2/a0; b1=(1-cosine)/a0
        b2=b0; z1=z2=0.0
        for i,x in enumerate(result):
            y=b0*x+z1; z1=b1*x-a1*y+z2; z2=b2*x-a2*y; result[i]=y
    return result


async def run(binary, output_dir, name, mode, parameter=None):
    output=output_dir/(name+'.pcm'); cfg=output_dir/(name+'.json')
    cfg.write_text(json.dumps(configuration(output, mode=='front')))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0)); port=reservation.getsockname()[1]
    log=(output_dir/(name+'.log')).open('wb')
    process=await asyncio.create_subprocess_exec(str(binary),'-a','127.0.0.1','-p',str(port),'-l','warn',str(cfg),
        stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.DEVNULL,stderr=log)
    block=0; spectra=[]
    async def request(ws,key,value=None,error=False):
        await ws.send_json({key:value}); answer=(await ws.receive_json(timeout=3))[key]
        assert (answer['result']!='Ok') if error else (answer['result']=='Ok'),answer
        return answer.get('value')
    async def write(values=None):
        nonlocal block
        values=values if values is not None else [0.0]*(BLOCK*2)
        process.stdin.write(struct.pack('<'+'d'*len(values),*values)); await process.stdin.drain()
        block+=1; await asyncio.sleep(.01)
    try:
        async with aiohttp.ClientSession() as session:
            for _ in range(150):
                try: ws=await session.ws_connect(f'http://127.0.0.1:{port}'); break
                except aiohttp.ClientError: await asyncio.sleep(.02)
            else: raise AssertionError('isolated audit daemon not reachable')
            async with ws:
                state=await request(ws,'GetNo5Sound'); preset=state['preset']; calibration=state['calibration']
                if mode=='room': preset['room']={'amount':1.0,'size':parameter}
                elif mode=='alignment':
                    calibration={'delaysMs':[1.25,2.5,3.75,3.75,4.5,7.25], 'gainsDb':[-1.,-2.,-3.,-3.,-4.,-5.]}
                elif mode=='subeq':
                    preset['eqEnabled']=True
                    preset['eqPoints']=[dict(id='proof',frequencyHz=1000.,gainDb=12.,q=1.,widthLocked=False)]
                    preset['room']={'amount':1.0,'size':.5}
                elif mode=='front':
                    rejected=json.loads(json.dumps(preset)); rejected['room']['amount']=1.0
                    await request(ws,'SetNo5Sound',dict(expectedConfigGeneration=state['configGeneration'],
                        expectedSourceGeneration=state['sourceGeneration'], expectedAppliedRevision=0,
                        revision=1,preset=rejected,calibration=calibration,transitionMs=50,
                        validForMs=5000,previewLease=None), error=True)
                await request(ws,'SetNo5Sound',dict(expectedConfigGeneration=state['configGeneration'],
                    expectedSourceGeneration=state['sourceGeneration'],expectedAppliedRevision=0,revision=1,
                    preset=preset,calibration=calibration,transitionMs=50,validForMs=5000,previewLease=None))
                for _ in range(30):
                    await write(); state=await request(ws,'GetNo5Sound')
                    if state['appliedRevision']==1: break
                else: raise AssertionError('target did not process')
                for _ in range(8): await write()
                start=block*BLOCK+100
                if mode in ('room','alignment','front'):
                    values=[0.0]*(BLOCK*2); values[200]=.25; values[201]=-.25 if mode=='room' else .125
                    await write(values)
                    for _ in range(8): await write()
                else:
                    if mode=='clip': await request(ws,'SetNo5Spectrum',{'enabled':True})
                    for index in range(40):
                        values=[]
                        for i in range(BLOCK):
                            n=index*BLOCK+i
                            amplitude=(1.2 if mode=='clip' else .08)*math.sin(2*math.pi*1000*n/RATE)
                            values.extend([amplitude, 0.0])
                        await write(values)
                        if mode=='clip' and index%5==0:
                            await request(ws,'SetNo5Spectrum',{'enabled':True})
                            frame=await request(ws,'GetNo5Spectrum')
                            if frame:spectra.append(frame)
                process.stdin.close(); await process.stdin.wait_closed()
            await asyncio.wait_for(process.wait(),5)
        assert process.returncode==0,process.returncode
        raw=output.read_bytes(); values=struct.unpack('<'+'d'*(len(raw)//8),raw)
        assert len(values)==block*BLOCK*6
        channel=lambda c:[values[i*6+c] for i in range(block*BLOCK)]
        result=dict(pcmSha256=hashlib.sha256(raw).hexdigest(),samplesPerChannel=block*BLOCK,appliedRevision=1)
        if mode=='alignment':
            peaks=[]
            for c,(delay,gain,source) in enumerate(zip([60,120,180,180,216,348],[-1,-2,-3,-3,-4,-5],[.25,.125,.1875,.1875,.25,.125])):
                waveform=channel(c); peak=max(range(len(waveform)),key=lambda i:abs(waveform[i]))
                expected=source*10**(gain/20)
                assert peak==start+delay,(c,peak,start,delay)
                assert abs(waveform[peak]-expected)<1e-14,(c,waveform[peak],expected)
                assert sum(1 for v in waveform if abs(v)>1e-14)==1,c
                peaks.append(dict(channel=c,delaySamples=peak-start,amplitude=waveform[peak],gainDb=gain))
            assert channel(2)==channel(3)
            result.update(individualDelayAndGain=peaks,nonzeroSubPairIdentical=True)
        elif mode=='room':
            band=band_impulse(4096); weights=[.46,.27,.17,.10]
            factors=[[1.,1.47,1.91,2.31],[1.09,1.59,2.07,2.4]]
            max_error=0.0
            for side in range(2):
                waveform=channel(4+side)
                for n in range(4096):
                    reflection=0.0
                    for weight,factor in zip(weights,factors[side]):
                        delay=(5+20*parameter)*48*factor; integer=math.floor(delay); fraction=delay-integer
                        a=band[n-integer] if n>=integer else 0.0
                        b=band[n-integer-1] if n>integer else 0.0
                        reflection+=weight*(a*(1-fraction)+b*fraction)
                    expected=(.125 if n==0 else 0.0)+.125*reflection
                    if side: expected=-expected
                    max_error=max(max_error,abs(waveform[start+n]-expected))
            assert max_error<2e-13,max_error
            assert max(abs(v) for v in channel(2))==0.0 and channel(2)==channel(3)
            assert channel(0)[start]==.25 and channel(1)[start]==-.25
            result.update(roomSize=parameter,reflectionsMatchIndependentBandModel=True,
                maximumPcmError=max_error,firstReflectionMs=[5+20*parameter,(5+20*parameter)*1.09],
                lastReflectionMs=[(5+20*parameter)*2.31,(5+20*parameter)*2.4],subUnaffected=True,frontAnchorUnchanged=True)
        elif mode=='front':
            assert all(max(abs(v) for v in channel(c))==0.0 for c in (2,3,4,5))
            assert channel(0)[start]==.25 and channel(1)[start]==.125
            result.update(roomTargetRejected=True,rearAndSubRemainSilent=True)
        elif mode=='subeq':
            error=max(abs(values[i*6+2]-(values[i*6]+values[i*6+1])*.5) for i in range(block*BLOCK))
            assert error<1e-14,error
            assert channel(2)==channel(3)
            assert max(abs(values[i*6+4]-values[i*6]) for i in range(block*BLOCK))>.005
            result.update(equalizedMonoSub=True,roomChangesRear=True,maximumSubPcmError=error)
        elif mode=='clip':
            assert spectra and any(f['overRangeSamples']>0 for f in spectra)
            assert all(f['tap']=='POST_EQ_PRE_ROOM' for f in spectra)
            result.update(postEqOverRangeObserved=True,peakDbfs=max(max(f['peaksDbfs']) for f in spectra),
                maximumOverRangeSamples=max(f['overRangeSamples'] for f in spectra),dacClippingProven=False)
        return result
    finally:
        if process.returncode is None:
            process.terminate()
            try:await asyncio.wait_for(process.wait(),2)
            except asyncio.TimeoutError:process.kill();await process.wait()
        log.close()


async def main():
    parser=argparse.ArgumentParser();parser.add_argument('--binary',type=Path,required=True)
    parser.add_argument('--identity-binary',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();directory=args.output.resolve();directory.mkdir(parents=True,exist_ok=True)
    results={}
    for name,mode,parameter in [('room-small','room',0.0),('room-large','room',1.0),
            ('alignment','alignment',None),('front-only','front',None),('sub-eq','subeq',None),('spectrum-peak','clip',None)]:
        results[name]=await run(args.binary.resolve(),directory,name,mode,parameter)
    identity=(args.identity_binary or args.binary).resolve()
    result=dict(schema='no5.camilla-channel-audit/1',binarySha256=hashlib.sha256(identity.read_bytes()).hexdigest(),
        launcherSha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        probeSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),cases=results,
        scope='ISOLATED_FILE_PCM_NO_ALSA_NO_LIVE_UNITS_NO_ACOUSTIC_ACCEPTANCE')
    (directory/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':asyncio.run(main())
