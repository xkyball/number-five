import asyncio
import os
import threading
import pytest
from no5_sonard_openecho.wire_journal import WireJournal


def test_blocked_wire_disk_does_not_block_control_and_keeps_exact_three_streams(tmp_path):
    gate=threading.Event(); entered=threading.Event()
    handles={k:(tmp_path/k).open('xb',buffering=0) for k in ('rx','tx','control')}
    original=handles['rx']
    class Slow:
        def write(self,data):
            entered.set(); gate.wait(3)
            return original.write(data[:3])  # also exercise partial disk writes
        def __getattr__(self,key):return getattr(original,key)
    handles['rx']=Slow()
    journal=WireJournal(handles)
    expected={key:bytearray() for key in handles}
    async def run():
        for i in range(100):
            for key in handles:
                data=bytes([i,0,255,42]);expected[key].extend(data);journal.append(key,data)
        assert await asyncio.to_thread(entered.wait,1)
        for _ in range(4):await asyncio.sleep(.005)
        assert journal.pending>0
        gate.set();await asyncio.to_thread(journal.close)
    try:asyncio.run(run())
    finally:gate.set()
    for key,data in expected.items():assert (tmp_path/key).read_bytes()==bytes(data)
    assert journal.pending==0


def test_overflow_latches_storage_failure_and_all_accepted_bytes_are_drained(tmp_path):
    handles={'rx':(tmp_path/'rx').open('xb',buffering=0)}
    journal=WireJournal(handles,limit_bytes=4)
    with pytest.raises(OSError,match='capacity'):journal.append('rx',b'over limit')
    with pytest.raises(OSError,match='wire archive failed'):journal.check()
    with pytest.raises(OSError):journal.close()
    assert handles['rx'].closed


def test_actual_disk_failure_is_visible_before_another_lease(tmp_path):
    handle=(tmp_path/'rx').open('xb',buffering=0)
    class Failed:
        def write(self,data):raise OSError('disk unavailable')
        def __getattr__(self,key):return getattr(handle,key)
    journal=WireJournal({'rx':Failed()});journal.append('rx',b'original')
    journal.thread.join(timeout=1)
    with pytest.raises(OSError,match='wire archive failed'):journal.check()
    with pytest.raises(OSError):journal.close()


def test_storage_fault_stays_latched_after_usb_owner_is_replaced(tmp_path):
    from no5_sonard.config import ServiceConfig
    from no5_sonard_openecho.service import HostService
    service=HostService(ServiceConfig(device='/not-open',storage_root=tmp_path), archive_enabled=True)
    service._record_storage_fault(OSError('archive disk unavailable'))
    service._client=None
    with pytest.raises(OSError,match='archive disk unavailable'):service.check_archive()
