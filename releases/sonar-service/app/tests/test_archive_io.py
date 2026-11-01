import asyncio,time
from types import SimpleNamespace
import pytest
from no5_sonard_openecho.archive_io import append_raw

def test_original_append_keeps_bytes_and_durability_without_blocking_control_timers():
    async def run():
        ticks=[];written=[];raw=bytes(range(256))
        def append(record):time.sleep(.16);written.append(record)
        work=asyncio.create_task(append_raw(SimpleNamespace(append=append),raw))
        while not work.done():ticks.append(time.monotonic());await asyncio.sleep(.01)
        await work
        assert len(ticks)>8 and written==[raw]
    asyncio.run(run())

def test_cancel_waits_for_the_original_durable_append_before_retirement():
    async def run():
        written=[]
        def append(record):time.sleep(.1);written.append(record)
        task=asyncio.create_task(append_raw(SimpleNamespace(append=append),b'original'))
        await asyncio.sleep(.02);task.cancel()
        with pytest.raises(asyncio.CancelledError):await task
        assert written==[b'original']
    asyncio.run(run())

def test_disk_failure_is_not_changed_into_a_transport_timeout():
    async def run():
        def append(_):raise OSError('disk failure')
        with pytest.raises(OSError):await append_raw(SimpleNamespace(append=append),b'original')
    asyncio.run(run())
