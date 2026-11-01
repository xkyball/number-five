"""A slow or failing evidence device must never occupy socket receivers' loop."""
import asyncio
import importlib.util
from pathlib import Path
import threading
import pytest

path=Path(__file__).parents[1]/'tools/verify_permanent_chain.py'
spec=importlib.util.spec_from_file_location('evidence_chain',path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def test_stalled_evidence_writer_keeps_receiver_loop_responsive(tmp_path):
    entered,release=threading.Event(),threading.Event();ticks=[]
    def write():
        entered.set();assert release.wait(2)
        (tmp_path/'original-evidence').write_bytes(b'unchanged-evidence')
    async def check():
        work=asyncio.create_task(m.evidence_io(write))
        try:
            assert await asyncio.to_thread(entered.wait,1)
            for _ in range(5):
                await asyncio.sleep(.01);ticks.append(True)
            assert not work.done() and len(ticks)==5
        finally:release.set();await work
    asyncio.run(check())
    assert (tmp_path/'original-evidence').read_bytes()==b'unchanged-evidence'


def test_cancel_waits_for_owned_evidence_completion(tmp_path):
    entered,release=threading.Event(),threading.Event()
    def write():
        entered.set();assert release.wait(2)
        (tmp_path/'result').write_text('complete')
    async def check():
        work=asyncio.create_task(m.evidence_io(write))
        try:
            assert await asyncio.to_thread(entered.wait,1)
            work.cancel();await asyncio.sleep(.02)
            assert not work.done(),'evidence writer outlived its cancelled owner'
        finally:release.set()
        with pytest.raises(asyncio.CancelledError):await work
    asyncio.run(check())
    assert (tmp_path/'result').read_text()=='complete'


def test_evidence_failures_are_reported_to_caller():
    def failed():raise OSError('evidence disk unavailable')
    async def check():
        with pytest.raises(OSError,match='evidence disk unavailable'):
            await m.evidence_io(failed)
    asyncio.run(check())
