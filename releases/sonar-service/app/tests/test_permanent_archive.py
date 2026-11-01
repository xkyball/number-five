import asyncio
import json
import os
import threading
import time
from pathlib import Path

import pytest

from no5_sonard_openecho.archive_fault import ArchiveFault, JournalBudget
from no5_sonard_openecho.wire_spool import SpoolingWireJournal, publish_wire_part
from test_recovery import setup, authorize, healthy


def test_slow_wd_keeps_wire_durable_on_ssd_and_transfers_exactly(tmp_path, monkeypatch):
    import no5_sonard_openecho.wire_spool as module
    monkeypatch.setattr(SpoolingWireJournal,'_check_volume',lambda self:None)
    destination=tmp_path/'wd';destination.mkdir()
    handles={key:(destination/name).open('xb',buffering=0)
             for key,name in [('rx','rx.cobs'),('tx','tx.cobs'),('control','control.jsonl')]}
    gate=threading.Event();entered=threading.Event();original=module.publish_wire_part
    def blocked(*args):
        entered.set();assert gate.wait(5)
        return original(*args)
    monkeypatch.setattr(module,'publish_wire_part',blocked)
    journal=SpoolingWireJournal(handles,root=tmp_path/'ssd',destination=destination,
        volume_guard={'mount':'unused','device':-1},part_bytes=2048)
    expected={key:bytearray()for key in handles}
    try:
        for i in range(500):
            for key in handles:
                data=bytes([i%256,0,255])*40
                journal.append(key,data);expected[key].extend(data)
        assert entered.wait(2)
        deadline=time.monotonic()+2
        while journal.pending and time.monotonic()<deadline:time.sleep(.01)
        assert journal.pending==0
        assert list(journal.path.glob('*.sealed'))
        assert JournalBudget(tmp_path/'ssd').used()<JournalBudget.LIMIT
        gate.set();journal.close()
        for key,name in [('rx','rx.cobs'),('tx','tx.cobs'),('control','control.jsonl')]:
            assert (destination/name).read_bytes()==expected[key]
        assert not journal.path.exists()
    finally:gate.set()


def test_wire_recovery_is_idempotent_and_rejects_conflicting_existing_bytes(tmp_path):
    import struct,zlib
    from no5_sonard_openecho.wire_spool import HEADER
    target=tmp_path/'rx.cobs';target.write_bytes(b'first')
    data=b'first-second';part=tmp_path/'part.sealed'
    raw=HEADER.pack(1,0,len(data),zlib.crc32(data)&0xffffffff)+data
    part.write_bytes(raw);publish_wire_part(part,tmp_path)
    assert target.read_bytes()==data
    part.write_bytes(raw);publish_wire_part(part,tmp_path)
    assert target.read_bytes()==data
    target.write_bytes(b'wrong');part.write_bytes(raw)
    with pytest.raises(ArchiveFault,match='differs'):publish_wire_part(part,tmp_path)
    assert part.read_bytes()==raw and target.read_bytes()==b'wrong'


def test_technical_shutdown_preserves_intent_but_explicit_stop_revokes_it(tmp_path):
    s,tx,r,now,calls,_=setup(tmp_path)
    async def run():
        await authorize(r,tx)
        desired=dict(r.desired)
        await r.close(operator_stop=False)
        assert json.loads(r.path.read_text())['desired']==desired
        assert r.desired==desired and tx.mode=='off'
        from no5_sonard_openecho.recovery import RecoveryController
        restored=RecoveryController(s,clock=lambda:now[0])
        assert restored.desired==desired and restored.state=='WAIT_HEALTH'
        await restored.operator_abort()
        assert json.loads(r.path.read_text())['desired'] is None
    asyncio.run(run())


def test_explicit_start_qualifies_before_first_continuous_fire(tmp_path):
    s,tx,r,now,calls,_=setup(tmp_path)
    async def run():
        await r.start_continuous()
        assert not any(c[0]=='fire'for c in calls)
        await r.tick()
        healthy(s,r,now,start=10)
        await r.tick();await r._attempt_task
        assert [c[2]for c in calls if c[0]=='fire']==[2,0]
        await r.close(operator_stop=False)
    asyncio.run(run())


def test_repeated_start_request_cannot_rearm_after_explicit_stop(tmp_path):
    import uuid
    s,tx,r,now,calls,_=setup(tmp_path)
    request={'request_id':str(uuid.uuid4())}
    async def run():
        await r.start_continuous(request)
        await r.operator_abort();calls.clear()
        await r.start_continuous(request)
        assert r.desired is None and not calls
        await r.close()
    asyncio.run(run())


def test_wire_storage_failure_reaches_hold_without_becoming_a_serial_error(tmp_path):
    from no5_sonard.config import ServiceConfig
    from no5_sonard_openecho.service import HostService
    from no5_sonard.transport.serial_posix import SerialDisconnected
    service=HostService(ServiceConfig(device='/not-open',storage_root=tmp_path/'wd'),control_root=tmp_path/'control', archive_enabled=True)
    async def fail():
        service._record_storage_fault(ArchiveFault('wire archive capacity exceeded'))
        raise SerialDisconnected('Pico disconnected')
    service._run_pico_connection=fail
    async def run():
        service._running=True
        await service._supervisor()
        await service._hold_task
        assert service.status.state=='HOLD'
        assert 'capacity exceeded'in service._storage_fault
        restored=HostService(service.config,control_root=tmp_path/'control', archive_enabled=True)
        await restored.start()
        assert restored.status.state=='HOLD'and restored._task is None
    asyncio.run(run())


def test_archive_fault_stops_and_freezes_usb_before_draining_original_raw(tmp_path,monkeypatch):
    from types import SimpleNamespace
    from no5_sonard.config import ServiceConfig
    from no5_sonard.service import SonarService
    from no5_sonard_openecho.service import HostService
    service=HostService(ServiceConfig(device='/not-open',storage_root=tmp_path/'wd'),control_root=tmp_path/'control', archive_enabled=True)
    calls=[]
    async def cancel():calls.append('cancel_lease')
    async def tx_stop(kind):assert kind==0x43;calls.append('TX_STOP')
    async def acquisition_stop():calls.append('acquisition_STOP');client.last_stop_confirmed=True
    async def freeze():calls.append('freeze')
    async def raw_only(self,ping,peer):
        assert self._storage_fault and peer.last_stop_confirmed
        calls.append(('original_raw',ping))
    monkeypatch.setattr(SonarService,'_handle_ping',raw_only)
    service.transmit._cancel_renewal=cancel;service.transmit._command=tx_stop
    client=SimpleNamespace(pings=asyncio.Queue(),last_stop_confirmed=False,
        stop_acquisition=acquisition_stop,transport=SimpleNamespace(freeze_receive=freeze))
    client.pings.put_nowait(b'original-a');client.pings.put_nowait(b'original-b')
    async def run():
        await service._settle_archive_fault(client,ArchiveFault('derived queue full'))
        await service._hold_task
    asyncio.run(run())
    assert calls==['cancel_lease','TX_STOP','acquisition_STOP','freeze',
                   ('original_raw',b'original-a'),('original_raw',b'original-b')]
    assert service._archive_fault_settle=={'stopped_status_confirmed':True,
        'raw_drained_pings':2,'remaining_received_pings':0}
    assert json.loads((tmp_path/'control/archive-hold.json').read_text())['reason'].endswith('derived queue full')


@pytest.mark.parametrize('state',['RUNNING','FINISHING','ABORTED'])
def test_archive_hold_is_not_relabelled_usb_disconnect_by_transmit_unbind(tmp_path,state):
    from test_clip_endurance import setup as clip_setup
    service,tx,_,_=clip_setup(tmp_path)
    service._storage_fault='OSError: evidence queue full'
    tx.run={'state':state,'elapsed_s':0,'duration_s':0,'counters_final':True,
            'own_starts':0,'physical_markers':0,'raw_ping_links':0,'usable_pings':0}
    asyncio.run(tx.unbind())
    assert tx.run['state']=='FAILED'
    assert tx.run['reason']=='archive HOLD: OSError: evidence queue full'
    assert tx.snapshot()['reason']=='archive HOLD: OSError: evidence queue full'


def test_failed_raw_original_is_rescued_only_after_stop_and_receive_freeze(tmp_path,monkeypatch):
    from types import SimpleNamespace
    from no5_sonard.config import ServiceConfig
    from no5_sonard.service import SonarService
    from no5_sonard_openecho.service import HostService
    service=HostService(ServiceConfig(device='/not-open',storage_root=tmp_path/'wd'),control_root=tmp_path/'control', archive_enabled=True)
    calls=[]
    async def cancel():calls.append('cancel');raise OSError('renewal evidence unavailable')
    async def tx_stop(kind):assert kind==0x43;calls.append('TX_STOP')
    async def acquisition_stop():calls.append('STOP');client.last_stop_confirmed=True
    async def freeze():calls.append('freeze')
    def rescue():assert calls==['cancel','TX_STOP','STOP','freeze'];calls.append('rescue_exact_original');return 1
    async def raw_only(self,ping,peer):calls.append('queued_RAW')
    service._writer=SimpleNamespace(begin_fault_drain=rescue)
    monkeypatch.setattr(SonarService,'_handle_ping',raw_only)
    service.transmit._cancel_renewal=cancel;service.transmit._command=tx_stop
    client=SimpleNamespace(pings=asyncio.Queue(),last_stop_confirmed=False,
        stop_acquisition=acquisition_stop,transport=SimpleNamespace(freeze_receive=freeze))
    client.pings.put_nowait('queued original')
    async def run():
        await service._settle_archive_fault(client,ArchiveFault('RAW backlog'))
        await service._hold_task
    asyncio.run(run())
    assert calls==['cancel','TX_STOP','STOP','freeze','rescue_exact_original','queued_RAW']
    assert service._archive_fault_settle['failed_original_raw_drained']==1
    assert service._storage_fault.endswith('RAW backlog')


def test_transmit_evidence_survives_unbind_until_final_recovery_event_and_close(tmp_path):
    from no5_sonard.config import ServiceConfig
    from no5_sonard_openecho.service import HostService
    service=HostService(ServiceConfig(device='/not-open',storage_root=tmp_path/'wd'),control_root=tmp_path/'control', archive_enabled=True)
    service._ensure_session();service._async_evidence=True
    async def run():
        service.transmit._archive('before_unbind')
        journal=service.transmit._evidence_journal
        await service.transmit.unbind()
        assert service.transmit._evidence_journal is journal and not journal.closed
        service.transmit._archive('after_unbind')
        await service.transmit.close_archive()
        assert journal.closed
    asyncio.run(run())
    rows=[json.loads(line)['event'] for line in (service._session_path/'derived/transmit.jsonl').read_text().splitlines()]
    assert rows==['before_unbind','after_unbind']
