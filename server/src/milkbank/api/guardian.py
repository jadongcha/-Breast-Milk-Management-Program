from fastapi import APIRouter, Request, Response

from .. import guardians as guardian_service
from ..errors import DomainError
from ..schemas import BabyOut, GuardianLoginIn, GuardianLoginOut
from .deps import DbDep, GuardianDep, StationDep

router = APIRouter(prefix="/guardian", tags=["guardian"])


@router.post("/login", response_model=GuardianLoginOut)
def login(body: GuardianLoginIn, request: Request, db: DbDep, station: StationDep):
    try:
        token, babies = guardian_service.login(
            db, request.app.state.clock, request.app.state.config,
            station=station, phone=body.phone, baby_id=body.baby_id,
        )
    except DomainError:
        # 실패 횟수 기록이 오류 응답에도 남도록 저장한 뒤 다시 던진다.
        db.commit()
        raise
    return GuardianLoginOut(token=token, babies=[BabyOut.model_validate(b) for b in babies])


@router.get("/babies", response_model=list[BabyOut])
def babies(db: DbDep, session: GuardianDep):
    return guardian_service.guardian_babies(db, session.phone)


@router.post("/logout", status_code=204)
def logout(db: DbDep, session: GuardianDep):
    guardian_service.logout(db, session)
    return Response(status_code=204)
