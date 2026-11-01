"""Read-only display socket that can close even while its source is silent."""
import asyncio
from fastapi import WebSocketDisconnect


async def serve_display(websocket, hub):
    await websocket.accept()
    queue = await hub.subscribe()
    receive = asyncio.create_task(websocket.receive())
    frame = asyncio.create_task(queue.get())
    try:
        while True:
            done, _ = await asyncio.wait((receive, frame), return_when=asyncio.FIRST_COMPLETED)
            if receive in done:
                message = receive.result()
                if message['type'] != 'websocket.disconnect':
                    await websocket.close(code=1003)
                return
            await websocket.send_bytes(frame.result())
            frame = asyncio.create_task(queue.get())
    except WebSocketDisconnect:
        pass
    finally:
        receive.cancel(); frame.cancel()
        await asyncio.gather(receive, frame, return_exceptions=True)
        await hub.unsubscribe(queue)
