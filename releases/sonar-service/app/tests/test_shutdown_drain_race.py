"""A PING get may complete while the STOP branch awaits freeze_receive."""
import asyncio
import json
from test_host import (StopBoundaryReplayDevice, fixture_inputs, config, wait_for,
    assert_closed_archive_matches_wire)
from no5_sonard_openecho.service import HostService


def test_ping_claimed_during_receive_freeze_is_not_lost(tmp_path):
    async def run():
        freeze_entered=asyncio.Event();sent=asyncio.Event()
        class DelayedStopPeer(StopBoundaryReplayDevice):
            async def _handle(self,packet):
                if int(packet.type)==0x13 and self.config is not None:
                    self.running=False
                    await freeze_entered.wait()
                    await self._data();sent.set()
                    return
                await super()._handle(packet)
        device=DelayedStopPeer(*fixture_inputs(),late_pings=1,send_stopped_status=False)
        await device.start()
        service=HostService(config(device.slave_path,tmp_path), archive_enabled=True)
        service.stop_confirmation_timeout_s=.01
        service.shutdown_settle_timeout_s=2
        try:
            await service.start()
            await wait_for(lambda:service.status.archived_pings==1)
            transport=service._client.transport;original=transport.freeze_receive
            async def freeze():
                pending=next(t for t in asyncio.all_tasks() if t.get_name()=='pico-ping')
                assert not pending.done()
                freeze_entered.set()
                await asyncio.wait_for(sent.wait(),1)
                await wait_for(pending.done,timeout=1)
                await original()
                assert pending.done() and not pending.cancelled()
            transport.freeze_receive=freeze
            await service.stop()
        finally:
            freeze_entered.set();await device.close()
        final=json.loads(service.final_status_path.read_text())
        wire,raw=assert_closed_archive_matches_wire(final)
        assert len(wire)==len(raw)==2
        assert final['shutdown']['drained_received_pings']==1
        assert final['shutdown']['remaining_received_pings']==0
        assert not final['shutdown']['stop_status_confirmed']
    asyncio.run(run())
