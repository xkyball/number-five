#!/usr/bin/env python3
"""Real Camilla process, PCM file output and loopback control/spectrum proof.

No ALSA, playback, production daemon, private state or external network used.
The same executable is exercised with the measurement subscriber off/on.
"""
import argparse
import asyncio
import cmath
import hashlib
import json
import math
from pathlib import Path
import socket
import struct
import time
import aiohttp

RATE, BLOCK = 48000, 1024


def configuration(path):
    mapping = [dict(dest=i, sources=[dict(channel=i % 2, gain=0.0)]) for i in range(6)]
    # Mono pair must remain identical even with opposite-phase source channels.
    for i in (2, 3):
        mapping[i]['sources'] = [dict(channel=c, gain=-6.020599913279624) for c in (0, 1)]
    return dict(title='No5 deterministic DSP proof', description='No5 sound v1 / stereo_4_1',
        devices=dict(samplerate=RATE, chunksize=BLOCK, volume_ramp_time=0,
            capture=dict(type='Stdin',channels=2,format='F64_LE'),
            playback=dict(type='File',channels=6,format='F64_LE',filename=str(path))),
        mixers=dict(stereo=dict(channels={'in':2,'out':6},mapping=mapping)),
        pipeline=[dict(type='Mixer',name='stereo')])


def signal(block):
    values=[]
    for i in range(BLOCK):
        n=block*BLOCK+i
        v=.08*math.sin(2*math.pi*1000*n/RATE)+.08*math.sin(2*math.pi*4000*n/RATE)
        values.extend((v,-v))
    return struct.pack('<'+'d'*len(values),*values)


async def run(binary,directory,name,measure,edit=None):
    directory.mkdir(parents=True,exist_ok=True)
    output=directory/(name+'.pcm')
    cfg=directory/(name+'.json');cfg.write_text(json.dumps(configuration(output)))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    log=(directory/(name+'.log')).open('wb')
    process=await asyncio.create_subprocess_exec(str(binary),'-a','127.0.0.1','-p',str(port),'-l','warn',str(cfg),
        stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.DEVNULL,stderr=log)
    frames=[];applied=None;prepare_ms=None;readback_ms=None
    async def request(ws,key,value=None):
        await ws.send_json({key:value})
        answer=await ws.receive_json(timeout=3)
        reply=answer[key]
        if reply['result']!='Ok':raise AssertionError(answer)
        return reply.get('value')
    try:
        async with aiohttp.ClientSession() as session:
            for attempt in range(100):
                try:ws=await session.ws_connect(f'http://127.0.0.1:{port}');break
                except aiohttp.ClientError:await asyncio.sleep(.02)
            else:raise AssertionError('proof daemon did not open its loopback WS')
            async with ws:
                state=await request(ws,'GetNo5Sound')
                assert state['schema']=='no5.camilla-sound/2',state
                assert state['appliedRevision']==0,state
                if measure:await request(ws,'SetNo5Spectrum',{'enabled':True})
                for block in range(108):
                    process.stdin.write(signal(block));await process.stdin.drain()
                    # This producer rate is a bounded test fixture, not a live
                    # latency benchmark. Output samples remain at 48 kHz.
                    await asyncio.sleep(.009)
                    if edit and block==24:
                        preset=state['preset']
                        if edit in {'eq','eq-cut'}:
                            preset['eqEnabled']=True
                            preset['eqPoints']=[dict(id='one',frequencyHz=1000.0,gainDb=-6.0 if edit=='eq-cut' else 12.0,q=1.0,widthLocked=False)]
                        elif edit=='room':preset['room']={'amount':1.0,'size':1.0}
                        elif edit=='fader':await request(ws,'SetVolume',-6.0)
                        start=time.monotonic()
                        receipt=await request(ws,'SetNo5Sound',dict(expectedConfigGeneration=state['configGeneration'],
                            expectedAppliedRevision=0,expectedSourceGeneration=state['sourceGeneration'],validForMs=5000,previewLease=None,revision=1,preset=preset,calibration=state['calibration'],transitionMs=50))
                        prepare_ms=(time.monotonic()-start)*1000
                        assert receipt['status']=='PREPARED' and receipt['revision']==1,receipt
                        started=start
                    if edit and block>24 and applied is None:
                        observed=await request(ws,'GetNo5Sound')
                        if observed['appliedRevision']==1:
                            applied=observed;readback_ms=(time.monotonic()-started)*1000
                    if measure and block%6==0:
                        await request(ws,'SetNo5Spectrum',{'enabled':True})
                        frame=await request(ws,'GetNo5Spectrum')
                        if frame:frames.append(frame)
                process.stdin.close()
                await process.stdin.wait_closed()
            await asyncio.wait_for(process.wait(),5)
        assert process.returncode==0,process.returncode
        raw=output.read_bytes()
        expected_bytes=108*BLOCK*6*8
        assert len(raw)==expected_bytes,(len(raw),expected_bytes)
        values=struct.unpack('<'+'d'*(len(raw)//8),raw)
        assert all(values[i+2]==values[i+3] for i in range(0,len(values),6)),'sub outputs differ'
        if edit is None:
            for block in range(108):
                original=struct.unpack('<'+'d'*(2*BLOCK),signal(block))
                for i in range(BLOCK):
                    base=(block*BLOCK+i)*6
                    assert values[base]==original[i*2] and values[base+1]==original[i*2+1],'neutral stage altered source PCM'
                    assert values[base+4]==original[i*2] and values[base+5]==original[i*2+1],'neutral room altered direct rear'
        if measure:
            assert frames,'no measured spectra'
            for frame in frames:
                assert frame['tap']=='POST_EQ_PRE_ROOM' and frame['timebase']=='CAPTURE_MONOTONIC_NS'
                assert frame['fftSize']==8192 and frame['window']=='HANN'
                assert len(frame['binsDbfs'])==256 and frame['overRangeSamples']==0
            assert all(a['sequence']<=b['sequence'] for a,b in zip(frames,frames[1:]))
        result={'pcmSha256':hashlib.sha256(raw).hexdigest(),'samplesPerChannel':108*BLOCK,
            'measuredFrames':len(frames),'prepareMs':prepare_ms,'processedReadbackMs':readback_ms}
        if edit:
            assert applied, 'prepared target never confirmed by processing thread'
            # Read actual output PCM: far outside the EQ center, headroom must
            # audibly attenuate; a center sine stays near its prior amplitude.
            start=72*BLOCK;length=12000
            def amplitude(freq):
                return abs(sum(values[(start+i)*6]*cmath.exp(-2j*math.pi*freq*(start+i)/RATE) for i in range(length)))*2/length
            center=amplitude(1000);outside=amplitude(4000)
            last=frames[-1]
            near=lambda freq:min(range(256),key=lambda i:abs(last['frequenciesHz'][i]-freq))
            if edit!='eq-cut':assert last['binsDbfs'][near(1000)]>-28,'antiphase stereo disappeared'
            if edit=='eq':
                assert applied['headroomDb']==12.0,applied
                assert .075<center<.085 and .018<outside<.030,(center,outside)
                assert last['binsDbfs'][near(4000)]<-29,'spectrum missed applied headroom/EQ'
            elif edit=='eq-cut':
                assert applied['headroomDb']==12.0,applied
                assert .009<center<.012 and .017<outside<.022,(center,outside)
                assert last['binsDbfs'][near(1000)]<-37,'spectrum missed EQ cut'
                assert last['binsDbfs'][near(4000)]<-32,'spectrum missed reserve'
            elif edit=='room':
                assert .075<center<.085 and .075<outside<.085,(center,outside)
                difference=max(abs(values[(start+i)*6+4]-values[(start+i)*6]) for i in range(length))
                assert difference>.02,'room did not reach rear'
                assert all(values[(start+i)*6+2]==0.0 for i in range(length)),'side/reflection leaked into sub'
                assert last['binsDbfs'][near(4000)]>-28,'room affected pre-room tap'
                result['rearChangedOnly']=True
            elif edit=='fader':
                assert .038<center<.042 and .038<outside<.042,(center,outside)
                assert last['binsDbfs'][near(4000)]>-28,'spectrum tap followed Main fader'
                result['tapBeforeMainFader']=True
            result.update(headroomDb=applied['headroomDb'],centerAmplitude=center,outsideAmplitude=outside,
                spectrum1000Dbfs=last['binsDbfs'][near(1000)],spectrum4000Dbfs=last['binsDbfs'][near(4000)])
        return result
    finally:
        if process.returncode is None:
            process.terminate()
            try:await asyncio.wait_for(process.wait(),2)
            except asyncio.TimeoutError:process.kill();await process.wait()
        log.close()


async def run_leases(binary, directory):
    """Exercise autonomous expiry in the real process, with producer/WS gaps."""
    output = directory/'leases.pcm'
    cfg = directory/'leases.json'; cfg.write_text(json.dumps(configuration(output)))
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1', 0)); port = reservation.getsockname()[1]
    log = (directory/'leases.log').open('wb')
    process = await asyncio.create_subprocess_exec(str(binary), '-a', '127.0.0.1', '-p', str(port),
        '-l', 'warn', str(cfg), stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.DEVNULL, stderr=log)
    blocks = 0
    async def request(ws, key, value=None, expected_error=False):
        await ws.send_json({key: value})
        reply = (await ws.receive_json(timeout=3))[key]
        if expected_error:
            assert reply['result'] != 'Ok', reply
            return reply
        assert reply['result'] == 'Ok', reply
        return reply.get('value')
    async def produce(count):
        nonlocal blocks
        for _ in range(count):
            process.stdin.write(signal(blocks)); await process.stdin.drain()
            blocks += 1
            await asyncio.sleep(BLOCK/RATE)
    async def applied(ws, revision):
        for _ in range(50):
            await produce(1)
            state = await request(ws, 'GetNo5Sound')
            if state['appliedRevision'] == revision:
                return state
        raise AssertionError(('revision did not process', revision, state))
    def target(state, revision, lease_ms=5000, validity=5000, attenuation=-6.0):
        calibration = json.loads(json.dumps(state['calibration']))
        calibration['gainsDb'][0] = attenuation
        return dict(expectedConfigGeneration=state['configGeneration'],
            expectedSourceGeneration=state['sourceGeneration'], expectedAppliedRevision=state['appliedRevision'],
            revision=revision, preset=state['preset'], calibration=calibration,
            transitionMs=50, validForMs=validity,
            previewLease=dict(id='file-preview', validForMs=lease_ms,
                fallbackPreset=state['preset'], fallbackCalibration=state['calibration']) if lease_ms else None)
    def update(state, validity):
        return dict(id='file-preview', expectedConfigGeneration=state['configGeneration'],
            expectedSourceGeneration=state['sourceGeneration'], expectedAppliedRevision=state['appliedRevision'],
            validForMs=validity, fallbackPreset=state['previewLease']['fallbackPreset'],
            fallbackCalibration=state['previewLease']['fallbackCalibration'])
    try:
        async with aiohttp.ClientSession() as session:
            for _ in range(100):
                try:
                    ws = await session.ws_connect(f'http://127.0.0.1:{port}')
                    break
                except aiohttp.ClientError:
                    await asyncio.sleep(.02)
            else:
                raise AssertionError('lease proof daemon did not open loopback WS')
            async with ws:
                baseline = await request(ws, 'GetNo5Sound')
                assert baseline['previewLease'] is None and baseline['lastRollback'] is None, baseline
                # No PCM arrives for longer than the real five-second lease.
                # Native expiry must queue a valid baseline independently of us.
                await request(ws, 'SetNo5Sound', target(baseline, 1, validity=200))
                await asyncio.sleep(5.15)
                pending = await request(ws, 'GetNo5Sound')
                assert pending['preparedRevision'] == 2 and pending['appliedRevision'] == 0, pending
                assert pending['previewLease'] is None and pending['lastRollback'] is None, pending
                rolled = await applied(ws, 2)
                assert rolled['lastRollback'] == dict(leaseId='file-preview', fromRevision=1, toRevision=2), rolled
                neutral_blocks = blocks
                await request(ws, 'SetNo5Sound', target(rolled, 3))
                current = await applied(ws, 3)
                renewal = update(current, 5000)
                foreign = dict(renewal, expectedSourceGeneration='foreign')
                await request(ws, 'SetNo5SoundLease', foreign, expected_error=True)
                assert await request(ws, 'SetNo5SoundLease', renewal) == dict(
                    status='RENEWED', id='file-preview', appliedRevision=3)
                assert await request(ws, 'SetNo5SoundLease', update(current, 0)) == dict(
                    status='RELEASED', id='file-preview', appliedRevision=3)
                released = await request(ws, 'GetNo5Sound')
                assert released['previewLease'] is None and released['preparedRevision'] == 3, released
                # A second preview uses a short deterministic lease. Close the
                # control connection entirely while the producer keeps running.
                second = target(released, 4, lease_ms=350, attenuation=-9.0)
                second['previewLease']['fallbackCalibration'] = baseline['calibration']
                await request(ws, 'SetNo5Sound', second)
                await applied(ws, 4)
            await produce(24)
            async with session.ws_connect(f'http://127.0.0.1:{port}') as ws:
                autonomous = await request(ws, 'GetNo5Sound')
                assert autonomous['appliedRevision'] == 5 and autonomous['preparedRevision'] == 5, autonomous
                assert autonomous['calibration'] == baseline['calibration'], autonomous
                assert autonomous['lastRollback'] == dict(leaseId='file-preview', fromRevision=4, toRevision=5), autonomous
                await request(ws, 'SetNo5Sound', target(autonomous, 6, lease_ms=0, validity=200))
                await asyncio.sleep(.3)
                expired = await request(ws, 'GetNo5Sound')
                assert expired['preparedRevision'] == 7 and expired['appliedRevision'] == 5, expired
                resolved = await applied(ws, 7)
                assert resolved['preparedRevision'] == resolved['appliedRevision'] == 7, resolved
                assert resolved['calibration'] == baseline['calibration'], resolved
                process.stdin.close(); await process.stdin.wait_closed()
            await asyncio.wait_for(process.wait(), 5)
        assert process.returncode == 0, process.returncode
        raw = output.read_bytes()
        assert len(raw) == blocks*BLOCK*6*8, (len(raw), blocks)
        values = struct.unpack('<'+'d'*(len(raw)//8), raw)
        expired_preview_max_error = 0.0
        for block in range(neutral_blocks):
            original = struct.unpack('<'+'d'*(2*BLOCK), signal(block))
            for i in range(BLOCK):
                expired_preview_max_error = max(expired_preview_max_error,
                    abs(values[(block*BLOCK+i)*6]-original[i*2]))
        # The established two-bank crossfade can round identical operands by a
        # few floating-point ulps; this is distinct from any preview attenuation.
        assert expired_preview_max_error < 1e-15, ('expired preview reached PCM', expired_preview_max_error)
        return dict(pcmSha256=hashlib.sha256(raw).hexdigest(), fiveSecondProducerGap=True,
            expiredPreviewNeverApplied=True, expiredPreviewMaxPcmError=expired_preview_max_error,
            rollbackVisibleOnlyAfterProcessed=True,
            independentOfControlConnection=True, renewReleaseAndSourceCas=True,
            expiredNonLeaseTargetResolved=True, queuedRollbackPerExpiry=1,
            finalAppliedRevision=7, samplesPerChannel=blocks*BLOCK)
    finally:
        if process.returncode is None:
            process.terminate()
            try: await asyncio.wait_for(process.wait(), 2)
            except asyncio.TimeoutError: process.kill(); await process.wait()
        log.close()


async def main():
    p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();binary=args.binary.resolve();directory=args.output.resolve()
    off=await run(binary,directory,'neutral-off',False)
    on=await run(binary,directory,'neutral-on',True)
    assert off['pcmSha256']==on['pcmSha256'],'measurement changed output PCM'
    edited=await run(binary,directory,'eq-on',True,'eq')
    room=await run(binary,directory,'room-on',True,'room')
    fader=await run(binary,directory,'fader-on',True,'fader')
    leases=await run_leases(binary,directory)
    result=dict(schema='no5.camilla-file-proof/1',binarySha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
        neutralMeasurementBitIdentical=True,neutralOff=off,neutralOn=on,edited=edited,room=room,fader=fader,leases=leases,
        scope='FILE_PCM_AND_LOOPBACK_WS; not ALSA, live audio latency or acoustic acceptance')
    (directory/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))


if __name__=='__main__':asyncio.run(main())
