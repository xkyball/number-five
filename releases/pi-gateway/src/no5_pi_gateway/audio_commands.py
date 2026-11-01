"""Bounded asynchronous AUDIO admission; only the existing adapter proves writes.

Latest absolute faders/mutes/seek replace unstarted goals from the same producer.
Discrete transport/preset actions are FIFO barriers. Accepted outcomes are retained
for polling/stream publication; unknown writes are never replayed.
"""
from __future__ import annotations
import asyncio
import copy
import hashlib
import json
import secrets
import time
from collections import OrderedDict, deque
from dataclasses import dataclass, field
from typing import Callable
from .audio import AudioError, AudioOutcomeUnknown, validate_audio_command
from .sound import SOUND_TYPES, SOUND_REJECTION_CODES

TERMINAL={"APPLIED","SUPERSEDED","CANCELLED","REJECTED","UNKNOWN"}

@dataclass(slots=True)
class _Job:
    identifier: str
    owner: str
    request: dict
    fingerprint: str
    valid: Callable[[], bool] | None
    status: str="ACCEPTED"
    reason: str="NONE"
    replacement: str | None=None
    state: dict | None=None
    admitted_ms: int=0
    verification: str | None=None
    admitted_monotonic: float=0.0
    sound: dict | None=None
    sound_captured_monotonic: float=0.0
    sound_captured_epoch_ms: int=0
    completed: asyncio.Event=field(default_factory=asyncio.Event)

    def receipt(self,include_sound=True):
        value={"version":1,"kind":"audio-command-result","commandId":self.identifier,
               "sessionId":self.request["sessionId"],"serverSessionId":self.request["serverSessionId"],
               "requestId":self.request["requestId"],"status":self.status,"reason":self.reason,
               "acceptedAtEpochMs":self.admitted_ms,"replacementId":self.replacement}
        if self.state is not None: value["state"]=self.state
        if self.verification is not None: value["verification"]=self.verification
        if self.request["command"]["type"]=="equalizer" and self.status=="APPLIED":value["verification"]="ACTIVE_CONFIG_READBACK"
        if include_sound and self.sound is not None and self.status=="APPLIED":
            age=max(0,int((time.monotonic()-self.sound_captured_monotonic)*1000))
            preview=self.sound.get("preview")
            remaining=None if preview is None else max(0,min(5000,preview["expiresAtEpochMs"]-self.sound_captured_epoch_ms-age))
            value["soundReadback"]={"schema":"no5.sound-command-readback/1","state":copy.deepcopy(self.sound),
                "ageMs":age,"remainingLeaseMs":remaining,"workMs":max(0,int((self.sound_captured_monotonic-self.admitted_monotonic)*1000))}
            value["soundReadback"]["state"].setdefault("capabilities", {})["commandStream"] = True
        return value

class AudioCommands:
    CAPACITY=64
    HISTORY=256
    def __init__(self, adapter):
        self.adapter=adapter
        self._pending=[None]*self.CAPACITY
        self._head=self._size=0
        self._results: OrderedDict[str,_Job]=OrderedDict()
        self._requests: dict[tuple[str,str],_Job]={}
        self._highwater: dict[tuple[str,str],int]={}
        self._chain_owner=None
        self._chain: set[int]=set()
        self._last_revision=None
        self._closed=False
        self._wake=asyncio.Event()
        self._task=None
        self.diagnostics=deque(maxlen=64)

    def start(self):
        if self._task is None:self._task=asyncio.create_task(self._run(),name="no5-audio-command-actor")

    async def stop(self):
        self._closed=True;self._wake.set()
        while self._size:
            job=self._take();self._finish(job,"CANCELLED","CLOSED")
        if self._task:
            self._task.cancel()
            try:await self._task
            except asyncio.CancelledError:pass
            self._task=None

    @staticmethod
    def _key(job):
        command=job.request["command"]
        if command["type"] in {"fader","mute"}:return job.owner,job.request["sessionId"],command["type"],command["channel"]
        if command["type"]=="soundPreview":return job.owner,job.request["sessionId"],"soundPreview",command["previewId"],command["scope"]
        if command["type"]=="soundKeepalive":return job.owner,job.request["sessionId"],"soundKeepalive",command["previewId"]
        if command["type"]=="equalizer":return job.owner,job.request["sessionId"],"equalizer",command["bankId"]
        if command["type"]=="transport" and command["action"]=="SEEK":return job.owner,job.request["sessionId"],"seek"
        return None

    def submit(self,owner,request,valid):
        validate_audio_command(request)
        fingerprint=hashlib.sha256(json.dumps(request,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()
        request_key=owner,request["requestId"]
        previous=self._requests.get(request_key)
        if previous is not None:
            if previous.fingerprint!=fingerprint:raise AudioError("request ID was used for a different command")
            return previous.receipt()
        producer=owner,request["sessionId"]
        counter=int(request["requestId"].rsplit(":",1)[1])
        if counter<=self._highwater.get(producer,0):raise AudioError("request outcome evicted; reconcile without replay")
        # Never evict a producer high-water mark and thereby permit an old action again.
        if producer not in self._highwater and len(self._highwater)>=256:raise AudioError("producer capacity reached")
        self._highwater[producer]=counter
        job=_Job(secrets.token_urlsafe(18),owner,dict(request),fingerprint,valid,admitted_ms=time.time_ns()//1_000_000,admitted_monotonic=time.monotonic())
        self._results[job.identifier]=job;self._requests[request_key]=job
        current=self.adapter.payload
        if self._closed:self._finish(job,"REJECTED","CLOSED")
        elif current is None:self._finish(job,"REJECTED","UNAVAILABLE")
        elif request["serverSessionId"]!=current["serverSessionId"]:self._finish(job,"REJECTED","SOURCE_CHANGED")
        elif request["command"]["type"] not in SOUND_TYPES and request["expectedRevision"]!=current["revision"]:self._finish(job,"REJECTED","REVISION_CHANGED")
        else:
            key=self._key(job);replaced=False
            if key is not None:
                for age in range(self._size-1,-1,-1):
                    position=(self._head+age)%self.CAPACITY;old=self._pending[position]
                    oldkey=self._key(old)
                    if oldkey is None:break
                    if oldkey==key:
                        old.replacement=job.identifier;self._finish(old,"SUPERSEDED","NEWER_TARGET")
                        self._pending[position]=job;replaced=True;break
            if not replaced:
                if self._size==self.CAPACITY:self._finish(job,"REJECTED","BUSY")
                else:self._pending[(self._head+self._size)%self.CAPACITY]=job;self._size+=1
            self._wake.set()
        self._trim()
        return job.receipt()

    def _trim(self):
        # Active entries remain protected; at most 64 queued + 1 running + 256 terminal.
        terminals=sum(job.status in TERMINAL for job in self._results.values())
        for identifier,job in tuple(self._results.items()):
            if terminals<=self.HISTORY:break
            if job.status in TERMINAL:
                self._results.pop(identifier);self._requests.pop((job.owner,job.request["requestId"]),None);terminals-=1

    def read(self,owner,identifier):
        job=self._results.get(identifier)
        return job.receipt() if job is not None and job.owner==owner else None

    def results(self,owner):
        # The bounded status stream never republishes up to 256 full sound models.
        return [job.receipt(include_sound=False) for job in self._results.values() if job.owner==owner]

    async def wait_result(self,owner,identifier,timeout=.15):
        job=self._results.get(identifier)
        if job is None or job.owner!=owner:return None
        try:await asyncio.wait_for(job.completed.wait(),min(.15,max(0,timeout)))
        except asyncio.TimeoutError:pass
        return job.receipt()
    def _take(self):
        job=self._pending[self._head];self._pending[self._head]=None
        self._head=(self._head+1)%self.CAPACITY;self._size-=1;return job
    def _finish(self,job,status,reason,state=None):
        job.status=status;job.reason=reason;job.state=state;job.valid=None
        self.diagnostics.append({"commandId":job.identifier,"type":job.request["command"]["type"],"status":status,"reason":reason,
                                 "elapsedMs":max(0,time.time_ns()//1_000_000-job.admitted_ms)})
        job.completed.set()
        self._trim()

    async def _run(self):
        while not self._closed:
            if not self._size:
                self._wake.clear();await self._wake.wait();continue
            job=self._take()
            if job.valid is None or not job.valid():self._finish(job,"CANCELLED","AUTHORIZATION_EXPIRED");continue
            if job.request["command"]["type"] in SOUND_TYPES and time.monotonic()-job.admitted_monotonic>=5:
                self._finish(job,"CANCELLED","SOUND_ACTIVITY_EXPIRED");continue
            current=self.adapter.payload
            if current is None:self._finish(job,"REJECTED","UNAVAILABLE");continue
            if current["serverSessionId"]!=job.request["serverSessionId"]:self._finish(job,"CANCELLED","SOURCE_CHANGED");continue
            request=dict(job.request)
            producer=job.owner,request["sessionId"]
            if request["command"]["type"] not in SOUND_TYPES and request["expectedRevision"]!=current["revision"]:
                # Only this producer's own verified chain can advance its queued target.
                if producer==self._chain_owner and current["revision"]==self._last_revision and request["expectedRevision"] in self._chain:
                    request["expectedRevision"]=current["revision"]
                else:self._finish(job,"REJECTED","REVISION_CHANGED");continue
            job.status="RUNNING"
            try:
                authorized_command=getattr(self.adapter,"command_authorized",None)
                if request["command"]["type"] in SOUND_TYPES:
                    ack=await self.adapter.sound_command_authorized(job.owner,request,job.valid,admitted_monotonic=job.admitted_monotonic)
                else:
                    ack=await authorized_command(request,job.valid) if authorized_command is not None else await self.adapter.command(request)
                if ack["status"]=="VERIFIED":
                    if request["command"]["type"] not in SOUND_TYPES:
                        if producer!=self._chain_owner:self._chain=set()
                        self._chain_owner=producer;self._chain.add(job.request["expectedRevision"])
                        self._last_revision=ack["state"]["revision"]
                        if len(self._chain)>self.HISTORY:self._chain={job.request["expectedRevision"]}
                    job.verification=ack.get("verification")
                    if request["command"]["type"] in SOUND_TYPES and isinstance(ack.get("sound"),dict):
                        # Copy only the adapter's verified readback, never the requested model.
                        sound=copy.deepcopy(ack["sound"])
                        if len(json.dumps(sound,separators=(",",":"),allow_nan=False).encode())<=65536:
                            job.sound=sound;job.sound_captured_monotonic=time.monotonic()
                            job.sound_captured_epoch_ms=time.time_ns()//1_000_000
                    self._finish(job,"APPLIED","NONE",ack["state"])
                else:
                    message = ack.get("message")
                    reason = message if request["command"]["type"] in SOUND_TYPES and message in SOUND_REJECTION_CODES else "BACKEND_REJECTED"
                    self._finish(job,"REJECTED",reason,ack.get("state"))
            except asyncio.CancelledError:
                self._finish(job,"UNKNOWN","COMPLETION_UNKNOWN");raise
            except (AudioOutcomeUnknown,AudioError,ConnectionError,TimeoutError):
                self._finish(job,"UNKNOWN","COMPLETION_UNKNOWN")
            except Exception:
                self._finish(job,"UNKNOWN","COMPLETION_UNKNOWN")
