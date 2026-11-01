"""A process pause is not a virtual disconnect; a stalled source still is."""
import asyncio
from pathlib import Path
import sys

import pytest
from no5_sonard_openecho.wire import ObservedTransport
import no5_sonard_openecho.wire as wire_module
from no5_sonard.transport.serial_posix import SerialDisconnected

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from replay_lifecycle import owned_replay_transport
from recorded_pico_replay import RecordedPico


@pytest.mark.parametrize('producer_resumes',[True,False])
def test_late_poll_excludes_only_process_pause_without_fabricating_receive_time(producer_resumes,tmp_path,monkeypatch):
    now=[0.]
    monkeypatch.setattr(wire_module.time,'monotonic',lambda:now[0])
    class Loop:
        def call_later(self,*args):return args
    T=owned_replay_transport(ObservedTransport,'/owned-pty',clock=lambda:now[0])
    t=T('/owned-pty',audit_root=tmp_path/'audit')
    t.fd=9;t._closed=False;t._loop=Loop();t._last_packet_at=0.;t._last_byte_at=0.
    t._replay_poll_due=.01
    monkeypatch.setattr(t,'_on_readable',lambda:None)
    now[0]=3.81;t._poll_receive()
    assert t._packets.empty() and t._last_packet_at==0.
    assert t.replay_scheduler_pauses==1
    # The unmodified, pinned transport reproduces the reported silence fault.
    control=ObservedTransport('/owned-pty',audit_root=tmp_path/'control')
    control.fd=9;control._closed=False;control._loop=Loop()
    control._last_packet_at=control._last_byte_at=0.
    monkeypatch.setattr(control,'_on_readable',lambda:None)
    control._poll_receive()
    assert isinstance(control._packets.get_nowait(),SerialDisconnected)
    if producer_resumes:t._last_packet_at=now[0] # An actual receive, outside the guard.
    now[0]+=.01;t._poll_receive()
    assert t._packets.empty()
    if not producer_resumes:
        # Keep polling normally: no additional blocked-process allowance. A
        # genuinely stalled source must still fail after its 3-second budget.
        for _ in range(300):now[0]+=.01;t._poll_receive()
        assert isinstance(t._packets.get_nowait(),SerialDisconnected)
        assert t._last_packet_at==0.
    assert t.replay_scheduler_pauses==1
    with pytest.raises(ValueError,match='exact owned PTY'):T('/dev/cu.physical',audit_root=tmp_path/'refused')


def test_quiesce_waits_for_the_partial_packet_before_cancelling_feed():
    async def run():
        # No serial port or fixture needed: deliberately interrupt a packet at
        # the same shared lock used by RecordedPico.send and its STOP command.
        source=RecordedPico.__new__(RecordedPico)
        source.lock=asyncio.Lock();source.running=True
        entered=asyncio.Event();finish=asyncio.Event();wire=bytearray()
        async def producer():
            async with source.lock:
                wire.extend(b'partial');entered.set()
                await finish.wait()
                wire.extend(b'complete\0')
            await asyncio.sleep(60)
        source.stream=asyncio.create_task(producer());await entered.wait()
        stop=asyncio.create_task(source.quiesce());await asyncio.sleep(.01)
        assert not stop.done() and wire==b'partial' and not source.running
        finish.set();await asyncio.wait_for(stop,1)
        assert wire==b'partialcomplete\0' and source.stream is None and not source.lock.locked()
    asyncio.run(run())


def test_packet_received_before_late_timer_gets_no_extra_silence_budget(tmp_path,monkeypatch):
    now=[3.81];monkeypatch.setattr(wire_module.time,'monotonic',lambda:now[0])
    class Loop:
        def call_later(self,*args):return args
    T=owned_replay_transport(ObservedTransport,'/owned-pty',clock=lambda:now[0])
    t=T('/owned-pty',audit_root=tmp_path/'audit')
    t.fd=9;t._closed=False;t._loop=Loop();t._last_packet_at=t._last_byte_at=3.8
    t._replay_poll_due=.01;monkeypatch.setattr(t,'_on_readable',lambda:None)
    t._poll_receive()
    assert t._replay_blocked_s==0. and t._packets.empty()
    for _ in range(301):now[0]+=.01;t._poll_receive()
    assert isinstance(t._packets.get_nowait(),SerialDisconnected)
