import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .api import admin, auth, guardian, patients, staff, stations, stock, submissions
from .clock import Clock, SystemClock
from .config import Config, load_config
from .db import make_engine, make_session_factory
from . import discharge
from .errors import DomainError
from .migrate import migrate


log = logging.getLogger(__name__)


def _erase_due_once(app: FastAPI) -> None:
    with app.state.session_factory() as db:
        try:
            ids = discharge.erase_due(db, app.state.clock)
            db.commit()
        except BaseException:
            db.rollback()
            raise
    if ids:
        log.info("보관 기간이 지난 환자 %d명의 개인정보를 말소했습니다", len(ids))


async def _auto_erase_loop(app: FastAPI) -> None:
    interval = app.state.config.auto_erase_interval_seconds
    while True:
        try:
            await asyncio.to_thread(_erase_due_once, app)
        except Exception:
            log.exception("자동 말소에 실패했습니다")
        await asyncio.sleep(interval)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    task = asyncio.create_task(_auto_erase_loop(app))
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


def create_app(config: Config, clock: Clock | None = None) -> FastAPI:
    clock = clock or SystemClock()
    engine = make_engine(config.db_path)
    migrate(engine, config.db_path, clock.now())
    app = FastAPI(title="milkbank", lifespan=_lifespan)
    app.state.config = config
    app.state.clock = clock
    app.state.engine = engine
    app.state.session_factory = make_session_factory(engine)

    @app.exception_handler(DomainError)
    async def _domain_error(_: Request, e: DomainError):
        content = {"detail": e.message}
        if e.code:
            content["code"] = e.code
        return JSONResponse(status_code=e.status_code, content=content)

    @app.get("/health")
    def health():
        return {"status": "ok"}

    app.include_router(auth.router)
    app.include_router(admin.router)
    app.include_router(staff.router)
    app.include_router(patients.router)
    app.include_router(stock.router)
    app.include_router(stations.router)
    app.include_router(guardian.router)
    app.include_router(submissions.router)
    return app


def app_factory() -> FastAPI:
    return create_app(load_config())
