from __future__ import annotations

import asyncio


class LatestFrameHub:
    """Broadcast hub where slow display clients may lose old frames by design."""

    def __init__(self, queue_size: int = 4) -> None:
        self._queue_size = queue_size
        self._clients: set[asyncio.Queue[bytes]] = set()
        self._lock = asyncio.Lock()

    @property
    def client_count(self) -> int:
        return len(self._clients)

    async def subscribe(self) -> asyncio.Queue[bytes]:
        queue: asyncio.Queue[bytes] = asyncio.Queue(maxsize=self._queue_size)
        async with self._lock:
            self._clients.add(queue)
        return queue

    async def unsubscribe(self, queue: asyncio.Queue[bytes]) -> None:
        async with self._lock:
            self._clients.discard(queue)

    async def publish(self, frame: bytes) -> None:
        # Copy the set so display backpressure never holds the lock.
        clients = tuple(self._clients)
        for queue in clients:
            if queue.full():
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            try:
                queue.put_nowait(frame)
            except asyncio.QueueFull:
                # A raced/full display queue is allowed to miss this frame.
                pass
