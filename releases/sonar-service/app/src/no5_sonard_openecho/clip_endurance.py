"""Experimental dual-GPIO clip TX, bounded by Pico time and renewable lease.

Native and owned emissions remain separate. An ACK is never a physical ping.
The short acceptance is mandatory in each USB session before a 600 s run.
"""
from __future__ import annotations
import asyncio
from collections import deque
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import struct
import time
import uuid
import numpy as np
from scipy.ndimage import gaussian_filter1d
from scipy.signal import find_peaks
from .transmit import TransmitController, EVENT, EVENTS, REASONS


@dataclass(frozen=True)
class ClipProfile:
    serial: str
    sha256: str
    evidence: tuple
    kind: str = "venterior_clip_experimental"
    depth_reference_verified: bool = True
    firmware: tuple = (0, 7, 6)
    quiet_window: bool = False

    @classmethod
    def load(cls, path):
        path=Path(path).resolve(); data=path.read_bytes(); row=json.loads(data)
        if row.get('schema')=='no5.clip-depth-rate-profile/1':
            from .depth_rate import DepthRateProfile
            return DepthRateProfile.load(path)
        if (row.get('schema')!='no5.clip-endurance-profile/1' or
            row.get('firmware') not in ([0,7,6],[0,7,7],[0,7,8],[0,7,9],[0,7,10]) or row.get('gpio')!=[18,22] or
            row.get('cycles')!=4 or row.get('series_ohms_each')!=390 or
            row.get('source')!='ECHO_AD' or not isinstance(row.get('serial'),str) or
            len(row['serial'])!=16):
            raise ValueError('incompatible clip endurance profile')
        evidence=row.get('evidence',[])
        if len(evidence)!=2: raise ValueError('require echo A/B/A and R17 recovery receipts')
        receipts=[]
        for item in evidence:
            raw=(path.parent/item['path']).resolve().read_bytes()
            if hashlib.sha256(raw).hexdigest()!=item['sha256']: raise ValueError('clip evidence hash mismatch')
            receipts.append(json.loads(raw))
        if receipts[0].get('schema')!='no5.clip-echo-aba-exploration/1' or receipts[0].get('raw_samples_modified') is not False:
            raise ValueError('missing unchanged physical A/B/A evidence')
        if not any(r.get('name')=='ten-4cycle-ECHO_AD-071-A2' and r.get('stop_confirmed') for r in receipts[0].get('records',[])):
            raise ValueError('missing short four-cycle echo trial')
        if receipts[1].get('schema')!='no5.clip-R17-own-recovery/1':
            raise ValueError('missing physical recovery observation')
        quiet=row.get('firmware') in ([0,7,7],[0,7,8],[0,7,9],[0,7,10])
        if row.get('firmware') in ([0,7,8],[0,7,9],[0,7,10]) and row.get('marker_policy')!='phase_qualified_quarantine_v1':
            raise ValueError('qualified marker profile requires explicit quarantine policy')
        if quiet:
            if row.get('own_slots_us')!=([174000,201000,228000,255000,282000,309000,336000] if row['firmware'] in ([0,7,9],[0,7,10]) else [174000,207000,240000,273000,306000,339000] if row['firmware']==[0,7,8] else [180000,220000,260000,300000,340000]):
                raise ValueError('quiet profile requires measured late slots')
            proof=row['quiet_window_evidence'];raw=(path.parent/proof['path']).resolve().read_bytes()
            if hashlib.sha256(raw).hexdigest()!=proof['sha256'] or json.loads(raw).get('schema')!='no5.owned-echo-phase-diagnosis/1':
                raise ValueError('quiet-window evidence mismatch')
            model=row['quiet_window_model'];raw=(path.parent/model['path']).resolve().read_bytes();state=json.loads(raw)
            if (hashlib.sha256(raw).hexdigest()!=model['sha256'] or
                state.get('schema')!='no5.quiet-window-timing-model/1' or
                state.get('adc_windows_clear_of_native_bursts') is not True or
                state.get('all_gpio_burst_geometry_unchanged') is not True):
                raise ValueError('quiet-window model mismatch')
            if row['firmware']==[0,7,10] and (state.get('firmware')!=[0,7,10] or
                    state.get('dense_late_slot_omitted_above_us')!=4000 or
                    row.get('maximum_slot_lateness_us')!=4000 or
                    state.get('minimum_spacing_us')!=27000):
                raise ValueError('4ms slot profile requires its exact timing model')
        return cls(row['serial'],hashlib.sha256(data).hexdigest(),tuple(evidence),
                   firmware=tuple(row['firmware']),quiet_window=quiet)


class ClipEnduranceController(TransmitController):
    LEASE_RENEW_INTERVAL_S = .5
    MIN_RENEW_GAP_S = .05
    _lease_clock = staticmethod(time.monotonic)
    def __init__(self, service, profile):
        super().__init__(service,profile)
        self.policy={'mode':'off','duration_s':2}
        self.run=None; self.short_verified=False
        self.profiles=deque(maxlen=128)
        self._starts=deque(maxlen=256)
        self._native=0; self._echoes=0; self._total=0
        self._first_start=None; self._last_start=None
        self._done_task=None
        self._usable=0
        self._usable_baseline=0
        self._reference_diagnostics={'quarantines':0,'recoveries':0,'skipped_slots':0,'quarantined':False,
                                     'last_observed_device_us':None}

    def bind(self, client):
        super().bind(client)
        self.short_verified=False
        self._reference_diagnostics.update(quarantines=0,recoveries=0,skipped_slots=0,quarantined=False,
                                           last_observed_device_us=None)

    def snapshot(self):
        result=super().snapshot()
        run=None if self.run is None else dict(self.run)
        if run is not None:
            if not run.get('counters_final'):
                run.update(own_starts=self.output_starts-self._baseline[0],
                physical_markers=self.physical_markers-self._baseline[1],
                raw_ping_links=self.linked_pings-self._baseline[2],
                native_pings=self._native-self._baseline[3],
                own_echo_candidates=self._echoes-self._baseline[4],
                usable_pings=self._usable-self._usable_baseline)
            if self._first_start is not None:
                if run['state'] in {'ARMED','RUNNING','FINISHING'}:
                    elapsed=max(0,time.monotonic()-self._first_host)
                    run['elapsed_s']=min(run['duration_s'],elapsed) if run['duration_s'] else elapsed
                span=(self._last_start-self._first_start)/1e6
                run['own_mean_hz']=(run['own_starts']-1)/span if span else None
            run['all_associated']=run['own_starts']==run['physical_markers']==run['raw_ping_links']
            run['usable_hz']=run['usable_pings']/run['elapsed_s'] if run['elapsed_s'] else 0.0
        result.update(schema='no5.clip-endurance-status/1',run=run,
            short_verified=self.short_verified,
            marker_reference=dict(self._reference_diagnostics),
            schedule={'kind':'native_quiet_window' if self.profile.quiet_window else 'native_interleaved',
                'own_slots_us':([174000,201000,228000,255000,282000,309000,336000] if self.profile.firmware in {(0,7,9),(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else [174000,207000,240000,273000,306000,339000] if self.profile.firmware==(0,7,8) else [180000,220000,260000,300000,340000] if self.profile.quiet_window else [40000,140000,240000,340000]),
                'native_period_bounds_us':[380000,395000],'minimum_own_spacing_us':27000 if self.profile.firmware in {(0,7,9),(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else 33000 if self.profile.firmware in {(0,7,8),(0,7,9),(0,7,10)} else 40000 if self.profile.quiet_window else 80000,
                'nominal_own_mean_hz':18.12 if self.profile.firmware in {(0,7,9),(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else 15.53 if self.profile.firmware==(0,7,8) else 12.95 if self.profile.quiet_window else 10.35,'uniform_100ms':False,
                'frequency_hz':197000,'high_ns':1000,'cycles':4,'hard_max_duration_s':600,
                'late_slot_action':'omit' if self.profile.firmware in {(0,7,8),(0,7,9),(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else 'abort',
                'maximum_slot_lateness_us':4000 if self.profile.firmware in {(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else 3000 if self.profile.firmware in {(0,7,8),(0,7,9),(0,7,10)} else 1000,
                'lease_s':2,'marker_packet_holdoff_us':5000,'experimental':True})
        continuous=getattr(self.profile,'continuous_run_supported',False)
        result['continuous_supported']=continuous
        result['continuous']=continuous and self.policy['duration_s']==0
        if result['continuous']:result['schedule']['hard_max_duration_s']=None
        return result

    def live(self):
        return {'schema':'no5.clip-endurance-live/1','transmit':self.snapshot(),
            'profiles':list(self.profiles),'presentation':self.service.presentation(),
            'raw_samples_modified':False,'display_only_smoothing':{'library':'scipy','sigma_samples':4},
            'echo_candidate_window_us':[800,1900],
            'candidate_is_calibrated_confidence':False}

    def is_own_ping(self, ping):
        return any(v.get('physical_tx_confirmed') and
                   abs(v.get('marker_device_us', 0)-ping.trigger_device_time_us) <= 3
                   for v in self._ledger.values())

    async def update(self,payload):
        if not isinstance(payload,dict) or set(payload)-{'mode','duration_s'}:
            raise ValueError('clip policy accepts only mode and duration_s')
        policy={**self.policy,**payload}
        durations={0,2,30,600} if getattr(self.profile,'continuous_run_supported',False) else {2,30,600} if self.profile.firmware==(0,7,11) else {2,600}
        if policy['mode'] not in {'off','periodic'} or type(policy['duration_s']) is not int or policy['duration_s'] not in durations:
            raise ValueError('mode off/periodic; duration_s 2 (qualification) or 600')
        if policy['mode']=='off': return await self.abort()
        async with self._lock:
            self._ready()
            hello=self.client.hello
            caps=(0xC0003FEE if self.profile.firmware in {(0,7,12),(0,7,13)} else 0xC0001FEE if self.profile.firmware==(0,7,11) else 0xC0000FEE if self.profile.firmware==(0,7,10) else 0xC00007EE if self.profile.firmware==(0,7,9) else 0xC00003EE if self.profile.firmware==(0,7,8) else
                  0xC00001EE if self.profile.quiet_window else 0xC00000EE)
            if (hello.fw_major,hello.fw_minor,hello.fw_patch)!=self.profile.firmware or hello.capabilities!=caps:
                raise RuntimeError('clip endurance requires the exact profile firmware/capabilities')
            if policy['duration_s'] in {0,600} and not self.short_verified:
                raise RuntimeError('short interleaved physical/PING qualification required in this USB session')
            if self.run and self.run['state'] in {'ARMED','RUNNING','FINISHING'}:
                raise RuntimeError('a run is already active')
            await self._cancel_renewal()
            await self._command(0x43)
            self._baseline=(self.output_starts,self.physical_markers,self.linked_pings,self._native,self._echoes)
            self._usable_baseline=self._usable
            self._first_start=self._last_start=None
            self._starts.clear()
            self.run={'state':'ARMED','duration_s':policy['duration_s'],'elapsed_s':0,
                'reason':'waiting for explicit FIRE and next native marker','verified':False,
                'started_utc_ns':None,'errors_at_start':self._errors()}
            try:
                await self._command(0x46,struct.pack('<IIIIII',197000,1000,4,
                    27000 if self.profile.firmware in {(0,7,9),(0,7,10),(0,7,11),(0,7,12),(0,7,13)} else 33000 if self.profile.firmware in {(0,7,8),(0,7,9),(0,7,10)} else 40000 if self.profile.quiet_window else 80000,1000,policy['duration_s']))
                await self._command(0x41,struct.pack('<II',4,2000000))
            except BaseException:
                self.run['state']='FAILED'; self.mode='off'
                try: await self._command(0x43)
                except Exception: pass
                raise
            self.mode='periodic'; self.policy=policy
            self.reason='armed; no burst before explicit FIRE'
            self._renewal=asyncio.create_task(self._renew(),name='clip-endurance-lease')
            self._archive('clip_policy',policy=policy,schedule=self.snapshot()['schedule'])
            return self.snapshot()

    def _errors(self):
        c=self.client
        if not c: return None
        return {'crc':c.transport.crc_errors,'frame':c.transport.frame_errors,
            'packet_gaps':c.packet_seq_gaps,'ping_gaps':c.ping_id_gaps,
            'queue_drops':c.missed_ping_queue}

    async def _renew(self):
        due = self._lease_clock() + self.LEASE_RENEW_INTERVAL_S
        try:
            while self.mode!='off':
                await asyncio.sleep(max(0., due-self._lease_clock()))
                if self.mode=='off': break
                if self._errors()!=self.run['errors_at_start']:
                    raise RuntimeError('acquisition counters changed')
                sent = self._lease_clock()
                await self._command(0x41,struct.pack('<II',4,2000000))
                # ACK latency must not add another 500ms to every lease gap.
                # There is no catch-up burst of renewals after a delayed reply.
                due = max(sent+self.LEASE_RENEW_INTERVAL_S,
                          self._lease_clock()+self.MIN_RENEW_GAP_S)
        except asyncio.CancelledError: raise
        except Exception as exc:
            self.mode=self.policy['mode']='off'; self.reason=f'lease/stream failed: {type(exc).__name__}: {exc}'
            self.run.update(elapsed_s=self.snapshot()['run']['elapsed_s'],state='FAILED',reason=self.reason)
            self._archive('endurance_failure',run=self.snapshot()['run'])
            try: await self._command(0x43)
            except Exception: pass

    async def fire(self,payload):
        # Reuse the durable UUID/no-replay ledger, but mode 4 is device-manual.
        if not isinstance(payload,dict) or set(payload)!={'request_id'}:
            raise ValueError('body must contain only request_id (UUID)')
        try: key=str(uuid.UUID(payload['request_id']))
        except (ValueError,TypeError,AttributeError) as exc:
            raise ValueError('request_id must be a UUID') from exc
        if key in self._requests: return dict(self._requests[key])
        if not self.run or self.run['state']!='ARMED':
            # Existing request receipts are still idempotent after completion.
            if isinstance(payload,dict) and payload.get('request_id') in self._requests:
                return dict(self._requests[payload['request_id']])
            raise RuntimeError('no armed clip run')
        # FIRE queues the NEXT native frame, so submit it after the current
        # passive PING has cleared capture.ready. No rejected FIRE is retried.
        before=self._total; deadline=time.monotonic()+1
        while self._total==before:
            if self.mode=='off' or self.run['state']!='ARMED':
                raise RuntimeError('run was aborted while waiting for native PING')
            if time.monotonic()>=deadline: raise TimeoutError('no passive PING before FIRE')
            await asyncio.sleep(.005)
        # With the low-latency durable journal the host can finish the PING
        # before the Pico retires capture.ready after its USB send. Leave a
        # bounded drain margin; still exactly one FIRE, never a busy retry.
        await asyncio.sleep(.01)
        if self.mode=='off' or self.run['state']!='ARMED':
            raise RuntimeError('run was aborted during passive drain guard')
        self.mode='manual'
        try: return await super().fire(payload)
        finally:
            if self.mode=='manual': self.mode='periodic'

    async def abort(self):
        if self.run and self.run['state'] in {'ARMED','RUNNING','FINISHING'}:
            fault=getattr(self.service,'_storage_fault',None)
            self.run.update(elapsed_s=self.snapshot()['run']['elapsed_s'],
                state='FAILED' if fault else 'ABORTED',
                reason='archive HOLD: '+fault if fault else 'operator/shutdown abort')
        return await super().abort()

    def on_packet(self,packet):
        if int(packet.type)!=0x45:
            return super().on_packet(packet)
        event,reason,tx_id,start,marker,ping=EVENT.unpack(packet.payload)
        if event==8:
            self._archive('clip_reference_diagnostic',detail=reason,observed_us=tx_id,
                native_last_us=start,native_period_us=marker,
                native_stable=ping>>32,slot=ping&0xffffffff)
            if reason in {5,6}:
                self._reference_diagnostics['quarantines' if reason==5 else 'recoveries']+=1
                self._reference_diagnostics.update(quarantined=reason==5,
                                                   last_observed_device_us=tx_id)
            elif reason==10:
                self._reference_diagnostics['skipped_slots']+=1
            return
        super().on_packet(packet)
        if not self.run: return
        if event==1:
            if self._first_start is None:
                self._first_start=start; self._first_host=time.monotonic()
                self.run.update(state='RUNNING',reason='physical marker/PING association monitored',started_utc_ns=time.time_ns())
            self._last_start=start; self._starts.append(start)
        elif event in {4,5} and self.run['state'] in {'ARMED','RUNNING','FINISHING'}:
            self.run.update(elapsed_s=self.snapshot()['run']['elapsed_s'],state='FAILED',reason=REASONS.get(reason,str(reason)))
            self._archive('endurance_failure',run=self.snapshot()['run'])
        elif event==7:
            self.mode=self.policy['mode']='off'
            self.run['state']='FINISHING'
            self._done_task=asyncio.create_task(self._finish(),name='clip-endurance-finish')

    async def _finish(self):
        await self._cancel_renewal()
        await asyncio.sleep(.15)
        snap=self.snapshot()['run']
        valid=(snap['duration_s']>0 and snap['all_associated'] and snap['own_starts']>=snap['duration_s']*10 and
            self._errors()==snap['errors_at_start'] and snap['elapsed_s']>=snap['duration_s']-.25)
        if self.profile.quiet_window:
            valid=valid and snap['usable_hz']>=10
        self.run.update(snap,counters_final=True)
        self.run.update(state='COMPLETE' if valid else 'FAILED',verified=valid,
            reason='device hard duration completed; markers and unchanged raw PINGs reconciled' if valid else 'end-of-run checks failed',
            elapsed_s=snap['elapsed_s'],finished_utc_ns=time.time_ns(),errors_at_end=self._errors())
        if valid and self.run['duration_s']==2: self.short_verified=True
        self.reason=self.run['reason']
        self._archive('endurance_result',run=self.snapshot()['run'])

    def on_detection(self,result,presentation):
        if (self.run and self.run['state'] in {'RUNNING','FINISHING'} and
            result.acquisition_source=='own' and result.valid_tx and
            presentation['quality']=='LOCKED' and presentation['bottom'] is not None):
            self._usable+=1

    def on_ping(self,ping):
        super().on_ping(ping)
        self._total+=1
        item=next((v for v in reversed(self._ledger.values()) if v.get('physical_tx_confirmed') and
            abs(v.get('marker_device_us',0)-ping.trigger_device_time_us)<=3),None)
        own=item is not None
        if not own: self._native+=1
        values=gaussian_filter1d(ping.samples.astype(float),4)
        # Exploratory bath reference diagnostic, separate from CFAR confidence.
        lo=int(800000/ping.sample_period_ns); hi=int(1900000/ping.sample_period_ns)
        peaks,props=find_peaks(values[lo:hi],prominence=.008*4095/3.3)
        peak=None
        if peaks.size:
            k=int(peaks[np.argmax(props['prominences'])])+lo
            peak={'delay_us':k*ping.sample_period_ns/1000,'depth_m_estimate':k*ping.sample_period_ns*1e-9*750}
            if own: self._echoes+=1
        # Display derivative only; raw samples were already committed by HostService.
        bins=512; stop=min(len(values),int(4000000/ping.sample_period_ns))
        edges=np.linspace(0,stop,bins+1,dtype=int)
        profile=[round(float(values[a:max(a+1,b)].max()),2) for a,b in zip(edges[:-1],edges[1:])]
        row={'ping_id':ping.ping_id,'device_us':ping.trigger_device_time_us,'own':own,
            'tx_id':None if item is None else item['tx_id'],'sample_period_ns':ping.sample_period_ns,
            'span_us':stop*ping.sample_period_ns/1000,'amplitudes_adc':profile,'reference_candidate':peak}
        self.profiles.append(row)
        self._archive('echo_reference_hypothesis',ping_id=ping.ping_id,own=own,candidate=peak)

    async def unbind(self):
        if self._done_task:
            await self._done_task
        fault=getattr(self.service,'_storage_fault',None)
        if self.run and (self.run['state'] in {'ARMED','RUNNING'} or
                         fault and self.run['state'] in {'FINISHING','ABORTED'}):
            self.run.update(elapsed_s=self.snapshot()['run']['elapsed_s'],state='FAILED',
                            reason='archive HOLD: '+fault if fault else 'USB disconnected')
        await super().unbind()
