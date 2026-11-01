import threading

import pytest

from no5_sonard_openecho.archive_fault import ArchiveFault, JournalBudget


def test_stalled_wire_write_cannot_hold_raw_reservation(tmp_path):
    wire, raw = JournalBudget(tmp_path), JournalBudget(tmp_path)
    entered, release, completed = threading.Event(), threading.Event(), threading.Event()
    failures = []

    def spool():
        try:
            with wire.reserve(1024):
                (tmp_path/'wire').write_bytes(b'w'*1024)
                entered.set()
                assert release.wait(3)
        except BaseException as error:
            failures.append(error)

    def capture():
        try:
            with raw.reserve(1024):
                (tmp_path/'raw').write_bytes(b'r'*1024)
            completed.set()
        except BaseException as error:
            failures.append(error)

    a = threading.Thread(target=spool)
    b = threading.Thread(target=capture)
    a.start()
    try:
        assert entered.wait(1)
        b.start()
        assert completed.wait(1), 'RAW was serialized behind unrelated Wire I/O'
    finally:
        release.set()
        a.join(2)
        if b.ident is not None:
            b.join(2)
    assert not failures
    assert (tmp_path/'wire').read_bytes() == b'w'*1024
    assert (tmp_path/'raw').read_bytes() == b'r'*1024


def test_inflight_unwritten_bytes_still_consume_shared_capacity(tmp_path, monkeypatch):
    monkeypatch.setattr(JournalBudget, 'LIMIT', 100)
    monkeypatch.setattr(JournalBudget, 'RESERVE', 20)
    a, b = JournalBudget(tmp_path), JournalBudget(tmp_path)
    with a.reserve(60):
        # Nothing has been written yet: the second writer must still account
        # for the first writer, including against the emergency hard bound.
        with pytest.raises(ArchiveFault):
            with b.reserve(21):
                pytest.fail('normal capacity exceeded')
        with pytest.raises(ArchiveFault):
            with b.reserve(41, emergency=True):
                pytest.fail('hard shared capacity exceeded')
        with b.reserve(40, emergency=True):
            pass
    with b.reserve(80):
        pass


def test_failed_writer_releases_reservation_without_deleting_originals(tmp_path, monkeypatch):
    monkeypatch.setattr(JournalBudget, 'LIMIT', 100)
    monkeypatch.setattr(JournalBudget, 'RESERVE', 20)
    budget = JournalBudget(tmp_path)
    with pytest.raises(OSError, match='fsync failed'):
        with budget.reserve(60):
            (tmp_path/'original').write_bytes(b'x'*20)
            raise OSError('fsync failed')
    with budget.reserve(60):
        pass
    with pytest.raises(ArchiveFault):
        with budget.reserve(61):
            pass
    assert (tmp_path/'original').read_bytes() == b'x'*20


def test_negative_reservation_cannot_expand_capacity(tmp_path):
    with pytest.raises(ValueError):
        with JournalBudget(tmp_path).reserve(-1):
            pass
