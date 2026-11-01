import asyncio
import json
import os
import time

import pytest
from no5_sonard.pico_protocol import encode_packet
from no5_sonard.transport.serial_posix import SerialDisconnected
from no5_sonard_openecho.wire import ObservedTransport
from test_host import status_packet
from test_display_selection import make_service


def test_short_usb_reads_are_drained_before_packet_silence_check(tmp_path, monkeypatch):
    async def run():
        master, slave = os.openpty(); path = os.ttyname(slave); os.close(slave)
        transport = ObservedTransport(path, audit_root=tmp_path/'wire')
        try:
            await transport.open()
            asyncio.get_running_loop().remove_reader(transport.fd)
            transport._read_poll_handle.cancel()
            encoded = encode_packet(status_packet(1))
            fragments = [encoded[i:i+7] for i in range(0, len(encoded), 7)]
            read = os.read
            def fragment_read(fd, count):
                if fd != transport.fd:
                    return read(fd, count)
                if fragments:
                    return fragments.pop(0)
                raise BlockingIOError()
            monkeypatch.setattr(os, 'read', fragment_read)
            transport._last_packet_at = time.monotonic()-51.55
            transport._poll_receive()
            assert not transport._silence_faulted
            packet = await transport.recv_packet(timeout=.1)
            assert packet.seq == 1 and not fragments
            assert not transport.frame_errors and not transport.crc_errors
        finally:
            await transport.close(); os.close(master)
        assert (tmp_path/'wire/rx.cobs').read_bytes() == encoded
        assert not (tmp_path/'wire/tx.cobs').read_bytes()
    asyncio.run(run())


def test_continuous_garbage_bytes_do_not_extend_packet_silence(tmp_path, monkeypatch):
    async def run():
        master, slave = os.openpty(); path = os.ttyname(slave); os.close(slave)
        transport = ObservedTransport(path, audit_root=tmp_path/'wire')
        try:
            await transport.open()
            transport._read_poll_handle.cancel()
            transport._last_packet_at = time.monotonic()-4
            # Real byte arrival is insufficient: a complete valid packet is
            # still required inside the unchanged three-second limit.
            os.write(master, b'fragment-without-delimiter')
            transport._poll_receive()
            with pytest.raises(SerialDisconnected, match='packet silence'):
                await transport.recv_packet(timeout=.1)
        finally:
            await transport.close(); os.close(master)
    asyncio.run(run())


def test_same_fd_poll_recovers_partial_packet_when_readiness_notification_is_missing(tmp_path):
    async def run():
        master,slave=os.openpty();path=os.ttyname(slave);os.close(slave)
        transport=ObservedTransport(path,audit_root=tmp_path/'wire')
        transport._read_poll_interval_s=.01
        try:
            await transport.open()
            asyncio.get_running_loop().remove_reader(transport.fd)
            encoded=encode_packet(status_packet(1));half=len(encoded)//2
            os.write(master,encoded[:half]);await asyncio.sleep(.03)
            assert transport.bytes_rx==half and transport._packets.empty()
            os.write(master,encoded[half:])
            packet=await transport.recv_packet(timeout=.3)
            assert packet.seq==1 and transport.status_generation==1
            assert transport._poll_read_bytes==len(encoded)
            assert not transport.frame_errors and not transport.crc_errors
        finally:
            await transport.close();os.close(master)
        assert transport._read_poll_handle is None
        assert (tmp_path/'wire/rx.cobs').read_bytes()==encoded
        assert not (tmp_path/'wire/tx.cobs').read_bytes()
    asyncio.run(run())


def test_packet_silence_becomes_disconnect_without_sending_or_rearming(tmp_path):
    async def run():
        master,slave=os.openpty();path=os.ttyname(slave);os.close(slave)
        transport=ObservedTransport(path,audit_root=tmp_path/'wire')
        transport._read_poll_interval_s=.01;transport._packet_silence_limit_s=.04
        try:
            await transport.open()
            with pytest.raises(SerialDisconnected,match='packet silence'):
                await transport.recv_packet(timeout=.3)
            assert transport.liveness_snapshot()['state']=='silent'
            await asyncio.sleep(.03)
            assert transport._packets.empty()  # One fault, no repeated errors.
        finally:
            await transport.close();os.close(master)
        assert not (tmp_path/'wire/tx.cobs').read_bytes()
        rows=[json.loads(l) for l in (tmp_path/'wire/control.jsonl').read_text().splitlines()]
        assert sum(r['event']=='serial_packet_silence' for r in rows)==1
    asyncio.run(run())


def test_stale_last_ping_never_keeps_a_positive_current_rate(tmp_path):
    service=make_service(tmp_path)
    service._last_raw_host_at=time.monotonic()-4
    snapshot=service.snapshot()
    assert snapshot['rates']['ping_hz']==0
    assert snapshot['rates']['last_raw_arrival_age_s']>=4
