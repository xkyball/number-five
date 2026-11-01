import asyncio,hashlib,os,threading,time
from pathlib import Path
import pytest
from no5_sonard_openecho.evidence_journal import EvidenceJournal

def test_blocked_disk_does_not_block_control_and_close_preserves_every_original_line(tmp_path,monkeypatch):
    original=os.fsync;gate=threading.Event();entered=threading.Event()
    def slow(fd):entered.set();gate.wait(2);original(fd)
    monkeypatch.setattr(os,'fsync',slow)
    async def run():
        journal=EvidenceJournal();path=tmp_path/'transmit.jsonl'
        rows=[('{"row":%d}\n'%n).encode() for n in range(1000)]
        journal.append(path,rows[0]);assert await asyncio.to_thread(entered.wait,1)
        for row in rows[1:]:journal.append(path,row)
        ticks=0
        for _ in range(10):ticks+=1;await asyncio.sleep(.005)
        assert ticks==10 and journal.snapshot()['pending_bytes']>0
        gate.set();await asyncio.to_thread(journal.close)
        assert path.read_bytes()==b''.join(rows)
        assert journal.snapshot()['pending_bytes']==0
    try:asyncio.run(run())
    finally:gate.set()

def test_queue_overflow_and_disk_failure_are_explicit_storage_errors(tmp_path,monkeypatch):
    journal=EvidenceJournal(limit_bytes=8)
    with pytest.raises(OSError,match='capacity'):journal.append(tmp_path/'a',b'123456789')
    journal.close()
    def fail(_):raise OSError('disk full')
    monkeypatch.setattr(os,'fsync',fail);journal=EvidenceJournal();journal.append(tmp_path/'b',b'original\n')
    with pytest.raises(OSError,match='journal failed'):journal.close()
    with pytest.raises(OSError):journal.append(tmp_path/'b',b'no retry')

def test_durable_evidence_survives_wd_stall_beyond_old_ram_limit(tmp_path,monkeypatch):
    import no5_sonard_openecho.wire_spool as module
    from no5_sonard_openecho.archive_fault import JournalBudget
    monkeypatch.setattr(module.SpoolingWireJournal,'_check_volume',lambda self:None)
    gate=threading.Event();entered=threading.Event();original=module.publish_wire_part
    def blocked(*args,**kwargs):
        entered.set();assert gate.wait(10)
        return original(*args,**kwargs)
    monkeypatch.setattr(module,'publish_wire_part',blocked)
    wd=tmp_path/'wd';wd.mkdir();ssd=tmp_path/'ssd'
    journal=EvidenceJournal(root=ssd,volume_guard={'mount':str(wd),'device':-1})
    path=wd/'derived/detections.jsonl';row=b'{"original":"'+b'x'*16000+b'"}\n'
    count=700
    try:
        for i in range(count):
            journal.append(path,row)
            if i%50==0:
                deadline=time.monotonic()+2
                while journal.snapshot()['pending_bytes'] and time.monotonic()<deadline:time.sleep(.005)
        assert entered.wait(2)
        assert not path.exists()  # No WD work in the acquisition callback.
        snap=journal.snapshot()
        assert snap['kind']=='durable_ssd_evidence' and not snap['failed']
        assert 8*1024*1024 < JournalBudget(ssd).used() < JournalBudget.LIMIT
        gate.set();journal.close()
        assert hashlib.sha256(path.read_bytes()).digest()==hashlib.sha256(row*count).digest()
        assert not list(ssd.iterdir())
    finally:gate.set()

def test_evidence_close_is_bounded_and_originals_are_recoverable(tmp_path,monkeypatch):
    import no5_sonard_openecho.wire_spool as module
    monkeypatch.setattr(module.SpoolingWireJournal,'_check_volume',lambda self:None)
    gate=threading.Event();entered=threading.Event();original=module.publish_wire_part
    def blocked(*args,**kwargs):
        entered.set();gate.wait(5)
        return original(*args,**kwargs)
    monkeypatch.setattr(module,'publish_wire_part',blocked)
    wd=tmp_path/'wd';wd.mkdir();ssd=tmp_path/'ssd'
    journal=EvidenceJournal(root=ssd,volume_guard={'mount':str(wd),'device':-1},close_timeout_s=.05)
    data=b'{"unaltered":1}\n';journal.append(wd/'derived/transmit.jsonl',data)
    try:
        assert entered.wait(2)
        started=time.monotonic()
        with pytest.raises(OSError,match='original SSD journals retained'):journal.close()
        assert time.monotonic()-started<.5
        spool=next(iter(journal.spools.values()))
        import json
        owner=json.loads((spool.path/'OWNER.json').read_text())
        assert owner['schema']=='no5.evidence-spool-owner/1'
        assert owner['stream_names']=={'3':'transmit.jsonl'}
        assert list(spool.path.glob('*.sealed'))
    finally:
        gate.set();spool.transfer_thread.join(3)
    assert (wd/'derived/transmit.jsonl').read_bytes()==data

def test_evidence_replay_compares_original_bytes_and_rejects_traversal(tmp_path):
    import zlib
    from no5_sonard_openecho.wire_spool import HEADER,publish_wire_part
    from no5_sonard_openecho.archive_fault import ArchiveFault
    data=b'first\nsecond\n';part=tmp_path/'part.sealed'
    raw=HEADER.pack(3,0,len(data),zlib.crc32(data)&0xffffffff)+data
    part.write_bytes(raw)
    publish_wire_part(part,tmp_path/'wd',names={3:'transmit.jsonl'},create_destination=True)
    part.write_bytes(raw)
    publish_wire_part(part,tmp_path/'wd',names={3:'transmit.jsonl'},create_destination=True)
    assert (tmp_path/'wd/transmit.jsonl').read_bytes()==data
    part.write_bytes(raw)
    with pytest.raises(ArchiveFault,match='invalid journal destination'):
        publish_wire_part(part,tmp_path/'wd',names={3:'../other'},create_destination=True)
    assert part.read_bytes()==raw and not (tmp_path/'other').exists()

def test_evidence_uses_shared_raw_wire_ssd_budget(tmp_path,monkeypatch):
    import no5_sonard_openecho.wire_spool as module
    from no5_sonard_openecho.archive_fault import JournalBudget
    monkeypatch.setattr(module.SpoolingWireJournal,'_check_volume',lambda self:None)
    monkeypatch.setattr(JournalBudget,'LIMIT',65536)
    monkeypatch.setattr(JournalBudget,'RESERVE',16384)
    wd=tmp_path/'wd';wd.mkdir();ssd=tmp_path/'ssd';ssd.mkdir()
    (ssd/'existing-raw-wire').write_bytes(b'x'*40000)
    journal=EvidenceJournal(root=ssd,volume_guard={'mount':str(wd),'device':-1})
    journal.append(wd/'derived/transmit.jsonl',b'x'*16000)
    spool=next(iter(journal.spools.values()));spool.thread.join(2)
    assert spool.error is not None and JournalBudget(ssd).used()<JournalBudget.LIMIT
    with pytest.raises(OSError):journal.check()
    with pytest.raises(OSError):journal.close()
    assert (wd/'derived/transmit.jsonl').read_bytes()==b'x'*16000


def test_blocked_ssd_fsync_cannot_block_enqueue_or_status(tmp_path,monkeypatch):
    import no5_sonard_openecho.wire_spool as module
    from no5_sonard_openecho.archive_fault import JournalBudget
    monkeypatch.setattr(module.SpoolingWireJournal,'_check_volume',lambda self:None)
    wd=tmp_path/'wd';wd.mkdir();gate=threading.Event();entered=threading.Event()
    original=os.fsync;original_used=JournalBudget.used
    def slow(fd):
        if threading.current_thread().name=='sonard-wire-spool':
            entered.set();assert gate.wait(3)
        original(fd)
    monkeypatch.setattr(os,'fsync',slow)
    journal=EvidenceJournal(root=tmp_path/'ssd',volume_guard={'mount':str(wd),'device':-1})
    path=wd/'derived/transmit.jsonl'
    try:
        journal.append(path,b'first\n');assert entered.wait(2)
        def forbidden(_):raise AssertionError('status inspected filesystem')
        monkeypatch.setattr(JournalBudget,'used',forbidden)
        started=time.monotonic();journal.append(path,b'second\n');snapshot=journal.snapshot()
        assert time.monotonic()-started<.1 and snapshot['pending_bytes']>0
    finally:
        monkeypatch.setattr(JournalBudget,'used',original_used);gate.set();journal.close()
    assert path.read_bytes()==b'first\nsecond\n'


def test_reopened_evidence_stream_continues_existing_byte_offset(tmp_path,monkeypatch):
    from no5_sonard_openecho.wire_spool import SpoolingWireJournal
    monkeypatch.setattr(SpoolingWireJournal,'_check_volume',lambda self:None)
    wd=tmp_path/'wd';wd.mkdir();path=wd/'transmit.jsonl';path.write_bytes(b'original-existing\n')
    for row in (b'next-original\n',b'last-original\n'):
        journal=EvidenceJournal(root=tmp_path/'ssd',volume_guard={'mount':str(wd),'device':-1})
        journal.append(path,row);journal.close()
    assert path.read_bytes()==b'original-existing\nnext-original\nlast-original\n'
    assert not list((tmp_path/'ssd').iterdir())
