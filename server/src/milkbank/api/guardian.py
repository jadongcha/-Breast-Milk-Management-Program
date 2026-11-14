from fastapi import APIRouter, Request, Response

from .. import guardians as guardian_service
from .. import submissions as submission_service
from ..errors import DomainError
from ..schemas import (
    BabyOut,
    GuardianLoginIn,
    GuardianLoginOut,
    SubmitIn,
    SubmitResult,
    SubmittedOut,
)
from .deps import DbDep, GuardianDep, OptionalStationDep

router = APIRouter(prefix="/guardian", tags=["guardian"])


@router.post("/login", response_model=GuardianLoginOut)
def login(body: GuardianLoginIn, request: Request, db: DbDep, station: OptionalStationDep):
    try:
        token, babies = guardian_service.login(
            db, request.app.state.clock, request.app.state.config,
            station=station,
            client_ip=request.client.host if request.client else None, phone=body.phone, baby_id=body.baby_id,
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


@router.post("/submissions", response_model=SubmitResult, status_code=201)
def submit(body: SubmitIn, request: Request, db: DbDep, session: GuardianDep):
    sub = submission_service.submit(
        db, request.app.state.clock, request.app.state.config,
        session=session, patient_id=body.patient_id, count=body.count, ml=body.ml,
    )
    baby = next(b for b in guardian_service.guardian_babies(db, session.phone)
                if b.patient_id == sub.patient_id)
    return SubmitResult(
        submission=SubmittedOut.model_validate(sub),
        balance=baby.balance,
        pending_count=baby.pending_count,
    )
