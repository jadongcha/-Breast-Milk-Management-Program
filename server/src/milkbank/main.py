from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .api import admin, auth, guardian, patients, staff, stations, stock, submissions
from .clock import Clock, SystemClock
from .config import Config, load_config
from .db import make_engine, make_session_factory
from .errors import DomainError
from .migrate import migrate


def create_app(config: Config, clock: Clock | None = None) -> FastAPI:
    clock = clock or SystemClock()
    engine = make_engine(config.db_path)
    migrate(engine, config.db_path, clock.now())
    app = FastAPI(title="milkbank")
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
