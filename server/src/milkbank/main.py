from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .api import auth, staff
from .clock import Clock, SystemClock
from .config import Config, load_config
from .db import init_db, make_engine, make_session_factory
from .errors import DomainError


def create_app(config: Config, clock: Clock | None = None) -> FastAPI:
    engine = make_engine(config.db_path)
    init_db(engine)
    app = FastAPI(title="milkbank")
    app.state.config = config
    app.state.clock = clock or SystemClock()
    app.state.engine = engine
    app.state.session_factory = make_session_factory(engine)

    @app.exception_handler(DomainError)
    async def _domain_error(_: Request, e: DomainError):
        return JSONResponse(status_code=e.status_code, content={"detail": e.message})

    app.include_router(auth.router)
    app.include_router(staff.router)
    return app


def app_factory() -> FastAPI:
    return create_app(load_config())
