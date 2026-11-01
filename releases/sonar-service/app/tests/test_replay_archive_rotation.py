"""Deletion authority, corruption refusal and bounded closed-archive retention."""
import asyncio
import json
import os
import signal
from pathlib import Path
import sys
import struct
import subprocess
import time
import urllib.error
import urllib.request
import zlib

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from replay_archive_rotation import ArchiveRing, allocated_bytes, verify_part
from no5_sonard import pico_protocol as c
from no5_sonard.storage.raw_zarr import RawPingRecord, RawZarrV2Writer


def populate(part, pid=1, n=2048):
    values = (np.arange(n, dtype=np.uint16) % 4096)
    ping = c.PingFrame(pid, 1000000 + pid * 100000, 2000, 0, 0, 4095, 1, values)
    wire = part / 'wire/owned'; wire.mkdir(parents=True)
    (wire / 'rx.cobs').write_bytes(c.encode_packet(c.Packet(c.MessageType(0x20), pid, 0, c.pack_ping_frame(ping))))
    (wire / 'tx.cobs').write_bytes(b'')
    (wire / 'control.jsonl').write_text('')
    raw = RawZarrV2Writer(part / 'raw/test.zarr', sample_count=n, sample_period_ns=2000,
        chunk_pings=1, attrs={'sample_period_ns': 2000})
    raw.append(RawPingRecord(pid, 0, ping.trigger_device_time_us, 2000, 0, 1, 0, 4095, values))
    raw.close()
    derived = part / 'derived/test'; derived.mkdir(parents=True)
    (derived / 'detections.jsonl').write_text(json.dumps({'ping_id': pid}) + '\n')
    return raw, ping


def test_rotates_sixty_logical_minutes_with_bounded_storage_and_receipts(tmp_path):
    ring = ArchiveRing(tmp_path / 'parts')
    maximum = 0
    for minute in range(60):
        part = ring.begin()
        writer, ping = populate(part, minute + 1, [2048, 8192, 16384][minute % 3])
        maximum = max(maximum, allocated_bytes(ring.base))
        ring.close_verified(part, [writer], lambda p: verify_part(p, {ping.ping_id},
            lambda pid: (ping, ping.trigger_device_time_us)))
        assert len(ring.parts) == 1 and len(ring.history) <= 4
    assert ring.verified_parts == ring.verified_pings == 60 and ring.deleted_parts == 59
    assert maximum < 2 * 1024 * 1024
    assert len(json.loads(ring.summary_path.read_text())['last_parts']) == 4
    ring.cleanup()
    assert not list(ring.base.iterdir())


def test_never_deletes_open_unverified_or_foreign_parts(tmp_path):
    ring = ArchiveRing(tmp_path / 'parts')
    part = ring.begin(); foreign = tmp_path / 'foreign'; foreign.mkdir()
    secret = foreign / 'keep'; secret.write_text('retain')
    handle = (part / 'open').open('w')
    with pytest.raises(ValueError, match='closed'):
        ring.close_verified(part, [handle], lambda p: {'passed': True, 'pings': 0})
    with pytest.raises(ValueError, match='open/unverified'): ring.cleanup()
    with pytest.raises(ValueError): ring._remove(part)
    with pytest.raises(ValueError): ring._remove(foreign)
    handle.close()
    assert secret.read_text() == 'retain' and part.exists()


@pytest.mark.parametrize('failure', ['sample', 'wire', 'time', 'derived', 'partial_tx', 'pending'])
def test_corruption_stops_retention_and_preserves_failed_part(tmp_path, failure):
    ring = ArchiveRing(tmp_path / 'parts')
    part = ring.begin(); writer, ping = populate(part)
    if failure == 'sample':
        p = part / 'raw/test.zarr/samples/0.0'
        data = np.frombuffer(zlib.decompress(p.read_bytes()), dtype='<u2').copy()
        data[100] += 1; p.write_bytes(zlib.compress(data.tobytes()))
    elif failure == 'wire':
        p = part / 'wire/owned/rx.cobs'; p.write_bytes(p.read_bytes()[:-1])
    elif failure == 'time':
        p = part / 'raw/test.zarr/trigger_device_time_us/0'
        data = np.frombuffer(zlib.decompress(p.read_bytes()), dtype='<u8').copy()
        data[0] += 1; p.write_bytes(zlib.compress(data.tobytes()))
    elif failure == 'derived':
        (part / 'derived/test/detections.jsonl').write_text('')
    elif failure == 'partial_tx':
        (part / 'wire/owned/control.jsonl').write_text('{"event":"send_result","complete":false}\n')
    else:
        (part / 'raw/test.zarr/_pending.bin').write_bytes(b'pending')
    with pytest.raises(ValueError):
        ring.close_verified(part, [writer], lambda p: verify_part(p, {1},
            lambda pid: (ping, ping.trigger_device_time_us)))
    ring.cleanup()
    assert part.exists() and ring.failed and ring.deleted_parts == 0
    with pytest.raises(RuntimeError): ring.begin()


@pytest.mark.parametrize('attack', ['owner', 'symlink'])
def test_foreign_owner_and_nested_symlink_are_preserved(tmp_path, attack):
    ring = ArchiveRing(tmp_path / 'parts')
    part = ring.begin(); writer, ping = populate(part)
    foreign = tmp_path / 'foreign'; foreign.write_text('keep')
    if attack == 'owner': (part / 'owner.json').write_text('{"token":"foreign"}')
    else: (part / 'link').symlink_to(foreign)
    with pytest.raises(ValueError):
        ring.close_verified(part, [writer], lambda p: verify_part(p, {1},
            lambda pid: (ping, ping.trigger_device_time_us)))
    assert part.exists() and foreign.read_text() == 'keep'


def test_existing_archive_root_is_never_adopted(tmp_path):
    root = tmp_path / 'already'; root.mkdir()
    with pytest.raises(ValueError, match='new'): ArchiveRing(root)


@pytest.mark.skipif(os.environ.get('SONARD_REPLAY_INTEGRATION') != '1',
    reason='Opt-in: requires the two retained private offline fixtures and pinned .16 wheel')
def test_real_recorded_pty_rotations_preserve_socket_and_stop_cleanup(tmp_path):
    """Separate PTY, OS-selected port; never accesses live8787 or real USB."""
    import websockets
    project = Path(__file__).resolve().parents[1]
    out = tmp_path / 'owned-replay'
    log = (tmp_path / 'worker.log').open('w')
    process = subprocess.Popen([sys.executable, str(project / 'tools/replay_view.py'),
        '--output', str(out), '--duration-s', '0', '--rotation-pings', '16',
        '--max-storage-mib', '32'], stdout=log, stderr=subprocess.STDOUT)
    ready = None

    async def check():
        nonlocal ready
        until = time.monotonic() + 45
        while not (out / 'current.json').exists():
            assert process.poll() is None
            assert time.monotonic() < until
            await asyncio.sleep(.05)
        ready = json.loads((out / 'current.json').read_text())
        def get(path):
            with urllib.request.urlopen(ready['url'] + path, timeout=3) as response:
                return json.load(response)
        first = get('/api/v1/status')
        while first['state'] != 'RUNNING' or not first['session']['segment_id'] or not first['ping_id']:
            assert time.monotonic() < until
            await asyncio.sleep(.05)
            first = get('/api/v1/status')
        frames = []
        paused = False
        until = time.monotonic() + 30
        async with websockets.connect(ready['url'].replace('http:', 'ws:') + '/ws/v1/echogram') as ws:
            while time.monotonic() < until:
                message = await asyncio.wait_for(ws.recv(), 3)
                frames.append(struct.unpack_from('<QQ', message, 4))
                status = get('/api/v1/status')
                if not paused and len(frames)>3:
                    # Producer and consumer share this process. Suspend BOTH
                    # longer than physical-USB silence and resume the owned PTY.
                    os.kill(process.pid,signal.SIGSTOP)
                    try:await asyncio.sleep(3.8)
                    finally:os.kill(process.pid,signal.SIGCONT)
                    paused=True
                if status['replay']['rotation']['deleted_parts'] >= 4: break
        assert len(frames) > 10 and all(a[0] < b[0] and a[1] < b[1] for a, b in zip(frames, frames[1:]))
        assert status['replay']['rotation']['verified_parts'] >= 5
        assert status['session']['id'] == first['session']['id']
        assert status['session']['segment_id'] == first['session']['segment_id']
        assert status['display_selection']['generation'] == first['display_selection']['generation']
        assert paused and status['pico']['transport_liveness']['owned_replay_scheduler_pauses']>=1
        assert all(status['pico'][key] == 0 for key in
            ('packet_seq_gaps', 'ping_id_gaps', 'queue_drops', 'frame_errors', 'crc_errors'))
        assert ready['expires_utc_ns'] is None and ready['until_explicit_STOP']
        try:
            urllib.request.urlopen(urllib.request.Request(ready['url'] + '/api/v1/transmit/fire',
                data=b'{}', headers={'Content-Type': 'application/json'}, method='POST'), timeout=3)
        except urllib.error.HTTPError as exc:
            assert exc.code == 403
        else: raise AssertionError('Replay exposed writable TX controls')
        Path(ready['stop_file']).touch()
        until = time.monotonic() + 15
        while process.poll() is None:
            assert time.monotonic() < until
            await asyncio.sleep(.05)
        assert process.returncode == 0
        final = json.loads((out / 'stopped.json').read_text())
        assert final['passed'] and final['raw_fixture_unchanged']
        assert final['rotation']['retained_parts'] == 0
        assert final['rotation']['verified_pings'] == final['processed_pings'] == final['delivered_pings']

    try:
        asyncio.run(check())
    finally:
        if process.poll() is None:
            # Also clean up a slow-starting child before current.json appears.
            if out.is_dir():(out/'STOP').touch()
            else:process.terminate()
            process.wait(timeout=45)
        log.close()
