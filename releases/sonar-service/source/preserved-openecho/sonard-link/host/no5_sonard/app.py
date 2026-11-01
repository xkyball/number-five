from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from . import __version__
from .config import ServiceConfig
from .service import SonarService

STATIC_DIR = Path(__file__).resolve().parent / "static"


def create_app(service: SonarService) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        await service.start()
        try:
            yield
        finally:
            await service.stop()

    app = FastAPI(title="No 5 Sonar Daemon", version=__version__,
                  description="M1 Pico USB acquisition + raw Zarr archive", lifespan=lifespan)
    app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.get("/")
    async def index() -> FileResponse:
        return FileResponse(STATIC_DIR / "index.html")

    @app.get("/api/v1/status")
    async def status() -> dict:
        return service.snapshot()

    @app.get("/healthz")
    async def healthz() -> dict:
        return {"status": "ok", "version": __version__}

    @app.get("/readyz")
    async def readyz() -> dict:
        state = service.status.state
        return {"ready": state in {"RUNNING", "WAIT_PICO", "IDLE"}, "state": state}

    @app.websocket("/ws/v1/echogram")
    async def echogram(websocket: WebSocket) -> None:
        await websocket.accept()
        queue = await service.hub.subscribe()
        try:
            while True:
                frame = await queue.get()
                await websocket.send_bytes(frame)
        except WebSocketDisconnect:
            pass
        finally:
            await service.hub.unsubscribe(queue)

    return app


# Import-compatible default for development. Production CLI constructs its own app.
app = create_app(SonarService(ServiceConfig()))
