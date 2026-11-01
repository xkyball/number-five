import asyncio
import threading
import numpy as np
from no5_sonard_openecho.archive_io import complete_in_worker
from no5_sonard_openecho.detection import DetectionPipeline


def test_slow_analysis_leaves_control_timers_live_and_retirement_waits():
    gate=threading.Event();entered=threading.Event();finished=threading.Event()
    def work():entered.set();gate.wait(3);finished.set();return 42
    async def run():
        task=asyncio.create_task(complete_in_worker(work))
        assert await asyncio.to_thread(entered.wait,1)
        ticks=0
        for _ in range(10):await asyncio.sleep(.005);ticks+=1
        assert ticks==10 and not finished.is_set()
        task.cancel();await asyncio.sleep(.01);assert not task.done()
        gate.set()
        try:await task
        except asyncio.CancelledError:pass
        assert finished.is_set()
    try:asyncio.run(run())
    finally:gate.set()


def test_worker_keeps_pipeline_order_and_raw_samples_byte_identical():
    raw=np.full(2048,30,dtype=np.uint16);raw[700:720]=900
    before=raw.tobytes();sync=DetectionPipeline();worker=DetectionPipeline()
    async def run():
        for i in range(8):
            args=dict(ping_id=i,samples=raw,tx_zero_sample_index=0,sample_period_ns=2000,
                      time_utc_ns=1000000000+i*100000000,valid_tx=True,acquisition_source='own')
            expected=sync.process(**args)
            actual=await complete_in_worker(worker.process,**args)
            assert actual.public()==expected.public()
    asyncio.run(run());assert raw.tobytes()==before
