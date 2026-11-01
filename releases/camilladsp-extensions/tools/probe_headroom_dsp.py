#!/usr/bin/env python3
"""First-EQ-edit gain audit using only an isolated Stdin/File Camilla instance."""
import argparse
import asyncio
import cmath
import hashlib
import json
import math
from pathlib import Path
import socket
import struct
import aiohttp
from probe_channel_dsp import configuration

RATE,BLOCK=48000,1024


def response(gain,frequency):
    a=10**(gain/40); omega=2*math.pi*1000/RATE; alpha=math.sin(omega)/2
    denominator=1+alpha/a
    b0=(1+alpha*a)/denominator;b1=-2*math.cos(omega)/denominator;b2=(1-alpha*a)/denominator
    a1=b1;a2=(1-alpha/a)/denominator
    z=cmath.exp(-2j*math.pi*frequency/RATE)
    return 20*math.log10(abs((b0+b1*z+b2*z*z)/(1+a1*z+a2*z*z)))


async def run(binary,directory,name,gain,calibration_db,before_enabled,after_enabled,reserve_db=12.0,before_gain=0.0,after_reserve_db=None):
    if after_reserve_db is None:after_reserve_db=reserve_db
    output=directory/(name+'.pcm');cfg=directory/(name+'.json');cfg.write_text(json.dumps(configuration(output)))
    with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
    log=(directory/(name+'.log')).open('wb')
    process=await asyncio.create_subprocess_exec(str(binary),'-a','127.0.0.1','-p',str(port),'-l','warn',str(cfg),
        stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.DEVNULL,stderr=log)
    blocks=0
    async def request(ws,key,value=None):
        await ws.send_json({key:value});reply=(await ws.receive_json(timeout=3))[key]
        assert reply['result']=='Ok',reply
        return reply.get('value')
    async def produce(count):
        nonlocal blocks
        for _ in range(count):
            values=[]
            for i in range(BLOCK):
                n=blocks*BLOCK+i
                v=.08*math.sin(2*math.pi*1000*n/RATE)+.08*math.sin(2*math.pi*4000*n/RATE)
                values.extend((v,v))
            process.stdin.write(struct.pack('<'+'d'*len(values),*values));await process.stdin.drain()
            blocks+=1;await asyncio.sleep(.008)
    async def apply(ws,state,preset,calibration,revision):
        await request(ws,'SetNo5Sound',dict(expectedConfigGeneration=state['configGeneration'],
            expectedSourceGeneration=state['sourceGeneration'],expectedAppliedRevision=state['appliedRevision'],
            revision=revision,preset=preset,calibration=calibration,transitionMs=50,validForMs=5000,previewLease=None))
        for _ in range(20):
            await produce(1); result=await request(ws,'GetNo5Sound')
            if result['appliedRevision']==revision:return result
        raise AssertionError('target did not complete processing')
    try:
        async with aiohttp.ClientSession() as session:
            for _ in range(100):
                try:ws=await session.ws_connect(f'http://127.0.0.1:{port}');break
                except aiohttp.ClientError:await asyncio.sleep(.02)
            else:raise AssertionError('isolated daemon unavailable')
            async with ws:
                initial=await request(ws,'GetNo5Sound');master_before=await request(ws,'GetVolume')
                assert initial['schema']=='no5.camilla-sound/2' and initial['headroomDb']==0 and initial['preset']['eqPoints']==[],initial
                assert initial['preset']['eqEnabled'] is False and initial['preset']['reserveDb']==12.0,initial
                assert initial['eqPolicy']==dict(minReserveDb=6.,maxReserveDb=18.,defaultReserveDb=12.,positiveBandsMustNotOverlap=True),initial
                state=initial
                if calibration_db or before_enabled or before_gain or reserve_db!=12.0:
                    calibration=json.loads(json.dumps(state['calibration']));calibration['gainsDb'][0]=calibration_db
                    before_preset=json.loads(json.dumps(state['preset']));before_preset['eqEnabled']=before_enabled;before_preset['reserveDb']=reserve_db
                    if before_gain:before_preset['eqPoints']=[dict(id='first',frequencyHz=1000.,gainDb=before_gain,q=1.,widthLocked=False)]
                    state=await apply(ws,state,before_preset,calibration,1)
                await produce(24); before_end=blocks*BLOCK
                before=await request(ws,'GetNo5Sound')
                preset=json.loads(json.dumps(before['preset']))
                preset['eqEnabled']=after_enabled;preset['reserveDb']=after_reserve_db
                preset['eqPoints']=[dict(id='first',frequencyHz=1000.,gainDb=gain,q=1.,widthLocked=False)]
                after=await apply(ws,before,preset,before['calibration'],before['appliedRevision']+1)
                await produce(24);after_end=blocks*BLOCK
                master_after=await request(ws,'GetVolume')
                assert master_before==master_after,(master_before,master_after)
                assert before['calibration']==after['calibration']
                process.stdin.close();await process.stdin.wait_closed()
            await asyncio.wait_for(process.wait(),5)
        assert process.returncode==0,process.returncode
        raw=output.read_bytes();samples=struct.unpack('<'+'d'*(len(raw)//8),raw)
        assert len(samples)==blocks*BLOCK*6
        def amplitude(end,frequency):
            start=end-12000
            return abs(sum(samples[(start+i)*6]*cmath.exp(-2j*math.pi*frequency*(start+i)/RATE)
                for i in range(12000)))*2/12000
        centers=[amplitude(end,1000) for end in (before_end,after_end)]
        outside=[amplitude(end,4000) for end in (before_end,after_end)]
        expected_before_reserve=reserve_db if before_enabled else 0.0
        expected_reserve=after_reserve_db if after_enabled else 0.0
        assert after['headroomDb']==expected_reserve,(after,expected_reserve)
        assert before['headroomDb']==expected_before_reserve,before
        expected_before=.08*10**((calibration_db+(response(before_gain,4000) if before_enabled else 0.0)-expected_before_reserve)/20)
        assert abs(outside[0]-expected_before)<1e-9,(outside,expected_before)
        ratios={str(f):20*math.log10(values[1]/values[0]) for f,values in [(1000,centers),(4000,outside)]}
        expected={str(f):(response(gain,f) if after_enabled else 0.0)-expected_reserve-
            ((response(before_gain,f) if before_enabled else 0.0)-expected_before_reserve) for f in (1000,4000)}
        for f in expected:assert abs(ratios[f]-expected[f])<1e-6,(f,ratios[f],expected[f])
        return dict(firstPointGainDb=gain,calibrationDb=calibration_db,
            eqEnabledBefore=before_enabled,eqEnabledAfter=after_enabled,reserveDbBefore=reserve_db,reserveDbAfter=after_reserve_db,
            headroomBeforeDb=before['headroomDb'],headroomAfterDb=after['headroomDb'],
            masterUnchanged=True,calibrationUnchanged=True,
            centerAmplitudeBeforeAfter=centers,outsideRmsBeforeAfter=[v/math.sqrt(2) for v in outside],
            measuredChangeDb=ratios,expectedChangeDb=expected,
            pcmSha256=hashlib.sha256(raw).hexdigest(),samplesPerChannel=blocks*BLOCK)
    finally:
        if process.returncode is None:
            process.terminate()
            try:await asyncio.wait_for(process.wait(),2)
            except asyncio.TimeoutError:process.kill();await process.wait()
        log.close()


async def validation(binary,directory):
    output=directory/'validation.pcm';cfg=directory/'validation.json';cfg.write_text(json.dumps(configuration(output)))
    with socket.socket() as reserve:reserve.bind(('127.0.0.1',0));port=reserve.getsockname()[1]
    log=(directory/'validation.log').open('wb')
    process=await asyncio.create_subprocess_exec(str(binary),'-a','127.0.0.1','-p',str(port),'-l','warn',str(cfg),
        stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.DEVNULL,stderr=log)
    rejected={};accepted=[];state=None
    async def request(ws,key,value=None,error=None):
        await ws.send_json({key:value});reply=(await ws.receive_json(timeout=3))[key]
        if error:
            assert reply['result']=={'InvalidValueError':error},reply
        else:assert reply['result']=='Ok',reply
        return reply.get('value')
    async def target(ws,preset,error=None,label=None):
        nonlocal state
        argument=dict(expectedConfigGeneration=state['configGeneration'],expectedSourceGeneration=state['sourceGeneration'],
            expectedAppliedRevision=state['appliedRevision'],revision=state['appliedRevision']+1,preset=preset,
            calibration=state['calibration'],transitionMs=50,validForMs=5000,previewLease=None)
        await request(ws,'SetNo5Sound',argument,error)
        if error:
            observed=await request(ws,'GetNo5Sound')
            assert observed==state,'rejected target altered state'
            rejected[label]=error
        else:
            for _ in range(20):
                process.stdin.write(bytes(BLOCK*2*8));await process.stdin.drain();await asyncio.sleep(.01)
                observed=await request(ws,'GetNo5Sound')
                if observed['appliedRevision']==argument['revision']:
                    state=observed;break
            else:raise AssertionError('validation target did not complete')
            assert state['preset']==preset
            assert state['headroomDb']==(preset['reserveDb'] if preset['eqEnabled'] else 0.)
            accepted.append(label)
    try:
        async with aiohttp.ClientSession() as session:
            for _ in range(100):
                try:ws=await session.ws_connect(f'http://127.0.0.1:{port}');break
                except aiohttp.ClientError:await asyncio.sleep(.02)
            else:raise AssertionError('isolated validator unavailable')
            async with ws:
                state=await request(ws,'GetNo5Sound')
                initial=json.loads(json.dumps(state['preset']))
                for field in ('eqEnabled','reserveDb'):
                    preset=json.loads(json.dumps(initial));del preset[field]
                    await target(ws,preset,'INVALID_SOUND_REQUEST','missing-'+field)
                for reserve in (5.999,18.001):
                    preset=dict(initial,reserveDb=reserve)
                    await target(ws,preset,'UNSUPPORTED_OR_INVALID_SOUND_TARGET','reserve-'+str(reserve))
                point=dict(id='a',frequencyHz=1000.,gainDb=12.,q=1.,widthLocked=False)
                touching=dict(point,id='b',frequencyHz=1000.*(math.sqrt(5)+1)/(math.sqrt(5)-1))
                overlap=dict(point,id='b',frequencyHz=1100.)
                preset=dict(initial,eqEnabled=True,reserveDb=18.,eqPoints=[point,overlap])
                await target(ws,preset,'EQ_POSITIVE_BANDS_OVERLAP','overlap-on')
                preset=dict(preset,eqEnabled=False)
                await target(ws,preset,label='overlap-off-preserved')
                await target(ws,dict(preset,eqEnabled=True),'EQ_POSITIVE_BANDS_OVERLAP','enable-overlap')
                preset=dict(preset,reserveDb=12.,eqPoints=[point,touching])
                await target(ws,preset,label='over-budget-off-preserved')
                await target(ws,dict(preset,eqEnabled=True),'EQ_COMBINED_GAIN_LIMIT','touching-skirts-over-budget')
                await target(ws,dict(preset,eqEnabled=True,reserveDb=18.),label='touching-at-18-accepted')
                await target(ws,dict(preset,eqEnabled=True,eqPoints=[point]),label='single-plus-12-at-12-accepted')
                process.stdin.close();await process.stdin.wait_closed()
            await asyncio.wait_for(process.wait(),5)
        assert process.returncode==0,process.returncode
        return dict(rejectedWithoutEffect=rejected,accepted=accepted,finalAppliedRevision=state['appliedRevision'],
            pcmSha256=hashlib.sha256(output.read_bytes()).hexdigest())
    finally:
        if process.returncode is None:
            process.terminate()
            try:await asyncio.wait_for(process.wait(),2)
            except asyncio.TimeoutError:process.kill();await process.wait()
        log.close()


async def main():
    p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();directory=args.output.resolve();directory.mkdir(parents=True,exist_ok=True)
    cases={}
    cases_spec=[('off-neutral',0.,0.,False,False,12.,0.,None),
        ('enable-neutral',0.,0.,False,True,12.,0.,None),
        ('on-cut',-6.,0.,True,True,12.,0.,None),
        ('on-boost',6.,0.,True,True,12.,0.,None),
        ('reserve-six-boost',6.,0.,True,True,6.,0.,None),
        ('explicit-reserve-change',6.,0.,True,True,6.,6.,18.),
        ('disable-preserves-curve',6.,0.,True,False,12.,6.,None),
        ('off-curve-edit',-6.,0.,False,False,12.,6.,None),
        ('calibrated-on-boost',6.,-3.,True,True,12.,0.,None)]
    for name,gain,calibration,before_enabled,after_enabled,reserve,before_gain,after_reserve in cases_spec:
        cases[name]=await run(args.binary.resolve(),directory,name,gain,calibration,before_enabled,after_enabled,reserve,before_gain,after_reserve)
    validation_result=await validation(args.binary.resolve(),directory)
    result=dict(schema='no5.camilla-fixed-reserve-audit/1',binarySha256=hashlib.sha256(args.binary.read_bytes()).hexdigest(),
        probeSourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        fixtureSourceSha256=hashlib.sha256((Path(__file__).parent/'probe_channel_dsp.py').read_bytes()).hexdigest(),
        cases=cases,validation=validation_result,scope='ISOLATED_FILE_PCM; DOES_NOT_VALIDATE_LIVE_GATEWAY_PRESET_OR_CALIBRATION_INITIALIZATION')
    (directory/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':asyncio.run(main())
