from typing import Annotated

from fastapi import APIRouter, Depends, Request

from .. import discharge as discharge_service
from .. import patients as patient_service
from .. import stock as stock_service
from ..models import Staff
from ..schemas import (
    DischargeIn,
    PatientCreate,
    PatientDetail,
    PatientOut,
    PatientUpdate,
    RecordOut,
)
from .deps import DbDep, current_staff, require_admin

router = APIRouter(prefix="/patients", tags=["patients"])

StaffDep = Annotated[Staff, Depends(current_staff)]
AdminDep = Annotated[Staff, Depends(require_admin)]


def _out(db, patient) -> PatientOut:
    base = {k: getattr(patient, k) for k in PatientOut.model_fields if k != "balance"}
    return PatientOut(**base, balance=stock_service.balance(db, patient.id))


@router.get("", response_model=list[PatientOut])
def list_patients(
    db: DbDep, _: StaffDep, q: str | None = None, include_discharged: bool = False
):
    return [_out(db, p) for p in patient_service.list_patients(db, q, include_discharged)]


@router.get("/{patient_id}", response_model=PatientDetail)
def get_patient(patient_id: int, db: DbDep, _: StaffDep):
    patient = patient_service.get_patient(db, patient_id)
    records = [
        RecordOut.model_validate(v) for v in stock_service.recent_records(db, patient.id, 50)
    ]
    return PatientDetail(**_out(db, patient).model_dump(), records=records)


@router.post("", response_model=PatientOut, status_code=201)
def create_patient(body: PatientCreate, request: Request, db: DbDep, _: AdminDep):
    return _out(db, patient_service.create_patient(db, request.app.state.clock, body))


@router.patch("/{patient_id}", response_model=PatientOut)
def update_patient(patient_id: int, body: PatientUpdate, db: DbDep, _: AdminDep):
    return _out(db, patient_service.update_patient(db, patient_id, body))


@router.post("/{patient_id}/discharge", response_model=PatientOut)
def discharge_patient(
    patient_id: int, body: DischargeIn, request: Request, db: DbDep, staff: AdminDep
):
    patient = discharge_service.discharge(
        db, request.app.state.clock, patient_id=patient_id, remaining=body.remaining,
        discard_reason=body.discard_reason, staff=staff,
    )
    return _out(db, patient)


@router.post("/{patient_id}/erase", response_model=PatientOut)
def erase_patient(patient_id: int, request: Request, db: DbDep, _: AdminDep):
    patient = discharge_service.erase(db, request.app.state.clock, patient_id=patient_id)
    return _out(db, patient)
