import asyncio
from no5_sonard_openecho.wire import ObservedTransport
from no5_sonard.transport.serial_posix import PosixSerialTransport
from no5_sonard.pico_protocol import Packet,MessageType,encode_packet

def test_overlapping_partial_plan_and_lease_writes_keep_cobs_packets_whole(tmp_path,monkeypatch):
    async def run():
        wire=bytearray();transport=ObservedTransport('/not-opened',audit_root=tmp_path)
        packets=[Packet(MessageType(0x47),1,0,b'plan'),Packet(MessageType(0x41),2,0,b'lease')]
        async def partial(self,p):
            b=encode_packet(p);wire.extend(b[:3]);self.bytes_tx+=3
            await asyncio.sleep(.01)
            wire.extend(b[3:]);self.bytes_tx+=len(b)-3
        monkeypatch.setattr(PosixSerialTransport,'send_packet',partial)
        await asyncio.gather(*(transport.send_packet(p) for p in packets))
        assert bytes(wire)==b''.join(encode_packet(p) for p in packets)
    asyncio.run(run())
