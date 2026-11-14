from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Request

from .. import stock as stock_service
from .. import submissions as submission_service
from ..models import Staff
from ..schemas import (
    ConfirmIn,
    ConfirmResult,
    PendingOut,
    RecordOut,
    RejectIn,
    RejectResult,
    SubmissionOut,
)
from .deps import DbDep, current_staff

router = APIRouter(tags=["submissions"])

StaffDep = Annotated[Staff, Depends(current_staff)]


@router.get("/submissions", response_model=list[PendingOut])
def list_submissions(db: DbDep, staff: StaffDep, status: Literal["pending"] = "pending"):
    return submission_service.pending(db)


@router.post("/submissions/{submission_id}/confirm", response_model=ConfirmResult)
def confirm(submission_id: int, body: ConfirmIn, request: Request, db: DbDep, staff: StaffDep):
    sub, record = submission_service.confirm(
        db, request.app.state.clock, submission_id=submission_id, count=body.count,
        ml=body.ml, ml_given="ml" in body.model_fields_set, staff=staff,
    )
    return ConfirmResult(
        record=RecordOut.model_validate(stock_service.record_view(db, record)),
        balance=stock_service.balance(db, sub.patient_id),
        submission=SubmissionOut.model_validate(sub),
    )


@router.post("/submissions/{submission_id}/reject", response_model=RejectResult)
def reject(submission_id: int, body: RejectIn, request: Request, db: DbDep, staff: StaffDep):
    sub = submission_service.reject(
        db, request.app.state.clock, submission_id=submission_id,
        reason=body.reason, memo=body.memo, staff=staff,
    )
    return RejectResult(submission=SubmissionOut.model_validate(sub))
