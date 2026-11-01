import asyncio
from types import SimpleNamespace
from no5_sonard.hub import LatestFrameHub
from no5_sonard_openecho.display_socket import serve_display


def test_idle_own_only_socket_disconnect_releases_subscription_without_a_ping():
    async def run():
        hub=LatestFrameHub()
        messages=asyncio.Queue();accepted=asyncio.Event();sent=[]
        async def accept():accepted.set()
        async def send(value):sent.append(value)
        socket=SimpleNamespace(accept=accept,receive=messages.get,send_bytes=send)
        task=asyncio.create_task(serve_display(socket,hub));await accepted.wait()
        await messages.put({'type':'websocket.disconnect'})
        await asyncio.wait_for(task,.5)
        assert hub.client_count==0 and not sent
    asyncio.run(run())
