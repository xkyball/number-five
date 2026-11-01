import asyncio
import importlib.util
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import numpy as np
import pytest
from no5_sonard.pico_protocol import MessageType, parse_config_set
import prototype as proto


class NativeScopeReplay(proto.ReplayDevice):
    def __init__(self):
        super().__init__([], [])
        self.sent_commands = []
        self.samples = np.arange(4096, dtype=np.uint16) % 600 + 50

    def _valid_config(self, payload):
        cfg = parse_config_set(payload)
        assert cfg.reserved == 1 and cfg.trigger_mode == 0 and cfg.sample_count == 4096
        return cfg

    async def _handle(self, packet):
        self.sent_commands.append(int(packet.type))
        return await super()._handle(packet)

    async def _data(self):
        if self._cursor:
            return
        payload = bytearray(proto.pack_stream(self.samples, 0, self._epoch, 2000))
        struct.pack_into('<I', payload, 32, 256 | (100 << 16))
        self._cursor += 1
        await self._send(MessageType.STREAM_FRAME, payload)


def load_tool():
    tools = Path(__file__).resolve().parents[1] / 'tools'
    sys.path.insert(0, str(tools))
    spec = importlib.util.spec_from_file_location('native_scope', tools / 'capture_native_scope.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize('matching_identity', [True, False])
def test_passive_scope_preserves_samples_and_never_sends_tx(tmp_path, matching_identity):
    async def run():
        peer = NativeScopeReplay()
        await peer.start()
        args = SimpleNamespace(output=tmp_path / 'capture', device=peer.slave_path,
            expected_serial='PTY-RECORDED-001' if matching_identity else 'WRONG-DEVICE',
            firmware_minor=5, source='C', venterior_state='normal', seconds=.1,
            sample_period_us=2, wheel_sha256='fixture')
        try:
            if not matching_identity:
                with pytest.raises(RuntimeError, match='identity/capabilities differ'):
                    await load_tool().capture(args)
                assert set(peer.sent_commands) == {0x02}
                return
            report = await load_tool().capture(args)
            assert report['stop_confirmed'] and report['frames'] == 1
            assert report['transmit_commands_sent'] == 0 and len(report['markers']) == 1
            assert set(peer.sent_commands) <= {0x02, 0x10, 0x12, 0x13, 0x30}
            raw = next(p.payload for p in proto.recorded_packets(args.output / 'capture.oescope') if int(p.type) == 0x22)
            packed = np.frombuffer(raw[40:], dtype=np.uint8).reshape(-1, 3).astype(np.uint16)
            values = np.empty(4096, dtype=np.uint16)
            values[::2] = packed[:, 0] | ((packed[:, 1] & 15) << 8)
            values[1::2] = (packed[:, 1] >> 4) | (packed[:, 2] << 4)
            np.testing.assert_array_equal(values, peer.samples)
        finally:
            await peer.close()
    asyncio.run(run())
