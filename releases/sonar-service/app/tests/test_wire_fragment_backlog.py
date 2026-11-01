import os
import threading

import pytest

from no5_sonard_openecho.archive_fault import ArchiveFault, JournalBudget
from no5_sonard_openecho.wire_spool import SpoolingWireJournal


def test_short_usb_fragments_do_not_exhaust_rows_during_ssd_sync(tmp_path, monkeypatch):
    monkeypatch.setattr(SpoolingWireJournal, '_check_volume', lambda self: None)
    destination = tmp_path/'wd'
    destination.mkdir()
    target = destination/'rx.cobs'
    handles = {'rx': target.open('xb', buffering=0)}
    entered, release = threading.Event(), threading.Event()
    original = os.fsync
    blocked = False

    def fsync(fd):
        nonlocal blocked
        if threading.current_thread().name == 'sonard-wire-spool' and not blocked:
            blocked = True
            entered.set()
            assert release.wait(5)
        return original(fd)

    monkeypatch.setattr(os, 'fsync', fsync)
    journal = SpoolingWireJournal(handles, root=tmp_path/'ssd', destination=destination,
        volume_guard={'mount': 'unused', 'device': -1})
    expected = b'first' + bytes(i % 251 for i in range(20000))
    try:
        journal.append('rx', b'first')
        assert entered.wait(2)
        for value in expected[5:]:
            journal.append('rx', bytes([value]))
        assert journal.pending < 32*1024
        assert journal.rows.qsize() <= 2
    finally:
        release.set()
        try:
            journal.close()
        except ArchiveFault:
            if journal.error is None:
                raise
    assert target.read_bytes() == expected


def test_fragment_coalescing_keeps_stream_order_and_byte_bound(tmp_path, monkeypatch):
    monkeypatch.setattr(SpoolingWireJournal, '_check_volume', lambda self: None)
    destination = tmp_path/'wd'
    destination.mkdir()
    names = {'rx': 'rx.cobs', 'tx': 'tx.cobs', 'control': 'control.jsonl'}
    handles = {key: (destination/name).open('xb', buffering=0) for key, name in names.items()}
    journal = SpoolingWireJournal(handles, root=tmp_path/'ssd', destination=destination,
        volume_guard={'mount': 'unused', 'device': -1})
    expected = {key: bytearray() for key in names}
    for i in range(4000):
        for key in names:
            data = bytes([i % 251, 0, 255])
            journal.append(key, data)
            expected[key].extend(data)
    journal.close()
    for key, name in names.items():
        assert (destination/name).read_bytes() == expected[key]
    assert JournalBudget(tmp_path/'ssd').used() == 0


def test_full_normal_queue_retains_rejected_fragment_and_stop_tail(tmp_path, monkeypatch):
    monkeypatch.setattr(SpoolingWireJournal, '_check_volume', lambda self: None)
    monkeypatch.setattr(JournalBudget, 'QUEUE_LIMIT', 128)
    monkeypatch.setattr(JournalBudget, 'QUEUE_TAIL', 64)
    destination = tmp_path/'wd'
    destination.mkdir()
    names = {'rx': 'rx.cobs', 'tx': 'tx.cobs', 'control': 'control.jsonl'}
    handles = {key: (destination/name).open('xb', buffering=0) for key, name in names.items()}
    entered, release = threading.Event(), threading.Event()
    original = os.fsync

    def fsync(fd):
        if threading.current_thread().name == 'sonard-wire-spool' and not entered.is_set():
            entered.set()
            assert release.wait(5)
        return original(fd)

    monkeypatch.setattr(os, 'fsync', fsync)
    journal = SpoolingWireJournal(handles, root=tmp_path/'ssd', destination=destination,
        volume_guard={'mount': 'unused', 'device': -1})
    try:
        journal.append('rx', b'first')
        assert entered.wait(2)
        journal.append('rx', b'a'*50)
        with pytest.raises(ArchiveFault, match='enqueue capacity') as error:
            journal.append('rx', b'b'*20)
        assert error.value.wire_original_enqueued
        assert journal.budget.queued == 75
        journal.begin_fault_drain()
        journal.append('control', b'STOP requested\n')
        journal.append('tx', b'actual STOP bytes')
        assert journal.budget.queued <= 128
        with pytest.raises(ArchiveFault):journal.check()
    finally:
        release.set()
        with pytest.raises(ArchiveFault):journal.close()
    assert (destination/'rx.cobs').read_bytes() == b'first'+b'a'*50+b'b'*20
    assert (destination/'tx.cobs').read_bytes() == b'actual STOP bytes'
    assert (destination/'control.jsonl').read_bytes() == b'STOP requested\n'
    assert journal.pending == journal.budget.queued == 0
    assert journal.snapshot()['failed']


def test_stop_still_reaches_serial_when_even_fault_tail_is_unavailable(tmp_path, monkeypatch):
    import asyncio
    from no5_sonard.pico_protocol import Packet, MessageType, encode_packet
    from no5_sonard.transport.serial_posix import PosixSerialTransport
    from no5_sonard_openecho.wire import ObservedTransport
    transport = ObservedTransport('/unused', audit_root=tmp_path/'wire')
    transport._archive_fault_settling = True
    transport._audit = {'tx': object()}
    sent = []

    def failed(*args, **kwargs):
        raise ArchiveFault('SSD unavailable')

    async def send(self, packet):
        data = encode_packet(packet)
        sent.append(data)
        self.bytes_tx += len(data)

    transport.trace = transport._write_audit = failed
    monkeypatch.setattr(PosixSerialTransport, 'send_packet', send)

    async def run():
        for kind in (MessageType.TX_ABORT, MessageType.STOP):
            await transport.send_packet(Packet(kind, 123, 0, b''))
        for kind in (MessageType.START, MessageType.TX_ARM, MessageType.TX_FIRE, MessageType.TX_CONFIG):
            with pytest.raises(ArchiveFault, match='only STOP'):
                await transport.send_packet(Packet(kind, 124, 0, b''))

    asyncio.run(run())
    assert len(sent) == 2
    assert len(transport._settlement_audit_errors) == 6


def test_real_pty_archive_fault_stops_and_reconciles_late_originals(tmp_path, monkeypatch):
    import asyncio
    import json
    from test_host import (StopBoundaryReplayDevice, fixture_inputs, config,
        wait_for, assert_closed_archive_matches_wire)
    from no5_sonard_openecho.service import HostService
    monkeypatch.setattr(SpoolingWireJournal, '_check_volume', lambda self: None)

    async def run():
        device = StopBoundaryReplayDevice(*fixture_inputs(), late_pings=2)
        await device.start()
        volume = tmp_path/'volume'
        volume.mkdir()
        service = HostService(config(device.slave_path, volume),
            capture_journal_root=tmp_path/'ssd', control_root=tmp_path/'control',
            data_volume_guard={'mount': str(volume), 'device': volume.stat().st_dev, 'uuid': 'fixture'}, archive_enabled=True)
        try:
            await service.start()
            await wait_for(lambda: service.status.archived_pings == 1)
            spool = service._client.transport._wire_journal
            spool.error = ArchiveFault('injected archive failure')
            # The next real RX callback encounters the archive failure. No
            # test replacement for the transport, STOP, reader or raw writer.
            await device._status()
            await wait_for(lambda: service.status.state == 'HOLD' and service._client is None, timeout=12)
            assert service._archive_fault_settle['stopped_status_confirmed']
            assert 'STOP' in device.trace
            assert (tmp_path/'control/archive-hold.json').exists()
            await service.stop()
            final = json.loads(service.final_status_path.read_text())
            wire, raw = assert_closed_archive_matches_wire(final)
            assert len(wire) == len(raw) == 3
            assert final['archive_health']['wire']['failed']
            assert final['archive_health']['wire']['pending_bytes'] == 0
        finally:
            await service.stop()
            await device.close()

    asyncio.run(run())
