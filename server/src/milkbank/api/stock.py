from typing import Annotated

from fastapi import APIRouter, Depends, Request

from .. import stock as stock_service
from ..models import RecordKind, Staff
from ..schemas import RecordIn, RecordOut, RecordResult
from .deps import DbDep, current_staff

router = APIRouter(tags=["stock"])

StaffDep = Annotated[Staff, Depends(current_staff)]

_KINDS = {
    "intake": RecordKind.INTAKE,
    "use": RecordKind.USE,
    "discard": RecordKind.DISCARD,
}


def _result(db, record) -> RecordResult:
    return RecordResult(
        record=RecordOut.model_validate(stock_service.record_view(db, record)),
        balance=stock_service.balance(db, record.patient_id),
    )


@router.post("/patients/{patient_id}/records", response_model=RecordResult, status_code=201)
def add_record(
    patient_id: int, body: RecordIn, request: Request, db: DbDep, staff: StaffDep
):
    record = stock_service.add_record(
        db,
        request.app.state.clock,
        patient_id=patient_id,
        kind=_KINDS[body.kind],
        count=body.count,
        staff=staff,
        discard_reason=body.discard_reason,
        memo=body.memo,
    )
    return _result(db, record)


@router.post("/records/{record_id}/cancel", response_model=RecordResult, status_code=201)
def cancel_record(record_id: int, request: Request, db: DbDep, staff: StaffDep):
    record = stock_service.cancel_record(
        db,
        request.app.state.clock,
        record_id=record_id,
        staff=staff,
        cancel_window_minutes=request.app.state.config.cancel_window_minutes,
    )
    return _result(db, record)
