"""Bounded admission must not couple raw durability to another filesystem scan."""
import threading
import time
import pytest
from no5_sonard_openecho.archive_fault import ArchiveFault, JournalBudget


def _run(target,name,errors):
    def guarded():
        try:target()
        except BaseException as exc:errors.append(exc)
    worker=threading.Thread(target=guarded,name=name);worker.start();return worker


def test_slow_wire_scan_does_not_delay_raw_admission(tmp_path,monkeypatch):
    scan,gate,done=threading.Event(),threading.Event(),threading.Event();errors=[]
    original=JournalBudget.used
    def used(self):
        if threading.current_thread().name=='blocked-wire-scan':
            scan.set();assert gate.wait(3)
        return original(self)
    monkeypatch.setattr(JournalBudget,'used',used)
    def wire():
        with JournalBudget(tmp_path).reserve(1024, refresh=True):
            (tmp_path/'wire').write_bytes(b'w'*1024)
    def raw():
        with JournalBudget(tmp_path).reserve(1024, refresh=True):
            (tmp_path/'raw').write_bytes(b'r'*1024)
        done.set()
    a=_run(wire,'blocked-wire-scan',errors);b=None
    try:
        assert scan.wait(1);b=_run(raw,'raw-admission',errors)
        assert done.wait(.3),'RAW admission waits on another writer filesystem scan'
    finally:
        gate.set();a.join(3)
        if b:b.join(3)
    assert not errors
    assert (tmp_path/'wire').read_bytes()==b'w'*1024
    assert (tmp_path/'raw').read_bytes()==b'r'*1024


def test_completed_raw_is_not_held_by_wire_scan(tmp_path,monkeypatch):
    admitted,finish,scan,gate,done=(threading.Event()for _ in range(5));errors=[]
    original=JournalBudget.used
    def used(self):
        if threading.current_thread().name=='blocked-wire-scan':
            scan.set();assert gate.wait(3)
        return original(self)
    monkeypatch.setattr(JournalBudget,'used',used)
    def raw():
        with JournalBudget(tmp_path).reserve(1024, refresh=True):
            (tmp_path/'raw').write_bytes(b'r'*1024)
            admitted.set();assert finish.wait(3)
        done.set()
    def wire():
        with JournalBudget(tmp_path).reserve(1024, refresh=True):pass
    a=_run(raw,'raw-completion',errors);b=None
    try:
        assert admitted.wait(1);b=_run(wire,'blocked-wire-scan',errors)
        assert scan.wait(1);finish.set()
        assert done.wait(.3),'durable RAW completion waits on unrelated filesystem scan'
    finally:
        gate.set();finish.set();a.join(3)
        if b:b.join(3)
    assert not errors


def test_completed_new_admission_is_counted_during_stale_scan(tmp_path,monkeypatch):
    monkeypatch.setattr(JournalBudget,'LIMIT',100);monkeypatch.setattr(JournalBudget,'RESERVE',20)
    scan,gate=threading.Event(),threading.Event();errors=[];rejected=[]
    def used(self):
        if threading.current_thread().name=='stale-scan':
            scan.set();assert gate.wait(3)
        return 0 # bytes were read before the concurrent writer became visible
    monkeypatch.setattr(JournalBudget,'used',used)
    def stale():
        try:
            with JournalBudget(tmp_path).reserve(21, refresh=True):pass
        except ArchiveFault:rejected.append(True)
    a=_run(stale,'stale-scan',errors)
    try:
        assert scan.wait(1)
        with JournalBudget(tmp_path).reserve(60, refresh=True):
            (tmp_path/'wire').write_bytes(b'w'*60)
        gate.set();a.join(3)
    finally:gate.set();a.join(3)
    assert not errors and rejected==[True]


def test_inflight_at_scan_start_remains_counted_after_completion(tmp_path,monkeypatch):
    monkeypatch.setattr(JournalBudget,'LIMIT',100);monkeypatch.setattr(JournalBudget,'RESERVE',20)
    scan,gate=threading.Event(),threading.Event();errors=[];rejected=[]
    def used(self):
        if threading.current_thread().name=='stale-scan':
            scan.set();assert gate.wait(3)
        return 0
    monkeypatch.setattr(JournalBudget,'used',used)
    first=JournalBudget(tmp_path).reserve(60, refresh=True);first.__enter__()
    def stale():
        try:
            with JournalBudget(tmp_path).reserve(41,emergency=True, refresh=True):pass
        except ArchiveFault:rejected.append(True)
    a=_run(stale,'stale-scan',errors)
    try:
        assert scan.wait(1);(tmp_path/'raw').write_bytes(b'r'*60)
        first.__exit__(None,None,None);gate.set();a.join(3)
    finally:gate.set();a.join(3)
    assert not errors and rejected==[True]


def test_concurrent_stale_scans_cannot_exceed_hard_budget(tmp_path,monkeypatch):
    monkeypatch.setattr(JournalBudget,'LIMIT',100);monkeypatch.setattr(JournalBudget,'RESERVE',20)
    barrier=threading.Barrier(20);finish=threading.Event();errors=[];accepted=[];rejected=[]
    def used(self):barrier.wait(timeout=5);return 0
    monkeypatch.setattr(JournalBudget,'used',used)
    def write():
        try:
            with JournalBudget(tmp_path).reserve(6,emergency=True, refresh=True):
                accepted.append(6);assert finish.wait(5)
        except ArchiveFault:rejected.append(True)
    workers=[_run(write,'concurrent-scan',errors)for _ in range(20)]
    try:
        deadline=time.monotonic()+3
        while len(accepted)+len(rejected)<20 and time.monotonic()<deadline:time.sleep(.01)
        assert len(accepted)==16 and len(rejected)==4
    finally:
        finish.set()
        for worker in workers:worker.join(3)
    assert not errors


def test_scan_error_propagates_without_admission(tmp_path,monkeypatch):
    def failed(self):raise OSError('SSD scan failed')
    monkeypatch.setattr(JournalBudget,'used',failed)
    with pytest.raises(OSError,match='SSD scan failed'):
        with JournalBudget(tmp_path).reserve(1, refresh=True):pytest.fail('admitted unknown storage')
