"""Ordered completion outside USB/control; bounded stage latency observations."""
import asyncio
from collections import defaultdict, deque
import time

_TIMES=defaultdict(lambda:deque(maxlen=256))


def processing_timings():
    result={}
    for name,rows in list(_TIMES.items()):
        values=list(rows)
        if not values:continue
        result[name]={'samples':len(values),'mean_wall_ms':round(sum(v[0] for v in values)*1000/len(values),3),
            'maximum_wall_ms':round(max(v[0] for v in values)*1000,3),
            'mean_worker_ms':round(sum(v[1] for v in values)*1000/len(values),3),
            'mean_cpu_ms':round(sum(v[2] for v in values)*1000/len(values),3)}
    return result


async def complete_in_worker(function, *args, **kwargs):
    started=time.perf_counter()
    def measured():
        wall=time.perf_counter();cpu=time.thread_time()
        result=function(*args,**kwargs)
        return result,time.perf_counter()-wall,time.thread_time()-cpu
    work=asyncio.create_task(asyncio.to_thread(measured))
    try:
        result,wall,cpu=await asyncio.shield(work)
        _TIMES[function.__qualname__].append((time.perf_counter()-started,wall,cpu))
        return result
    except asyncio.CancelledError:
        await work
        raise


async def append_raw(writer, record):
    return await complete_in_worker(writer.append, record)
