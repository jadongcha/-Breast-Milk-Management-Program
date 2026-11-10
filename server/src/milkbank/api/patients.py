from typing import Annotated

from fastapi import APIRouter, Depends, Request

from .. import patients as patient_service
from ..models import Staff
from ..schemas import PatientCreate, PatientOut, PatientUpdate
from .deps import DbDep, current_staff, require_admin

router = APIRouter(prefix="/patients", tags=["patients"])

StaffDep = Annotated[Staff, Depends(current_staff)]
AdminDep = Annotated[Staff, Depends(require_admin)]


@router.get("", response_model=list[PatientOut])
def list_patients(
    db: DbDep, _: StaffDep, q: str | None = None, include_discharged: bool = False
):
    return patient_service.list_patients(db, q, include_discharged)


@router.get("/{patient_id}", response_model=PatientOut)
def get_patient(patient_id: int, db: DbDep, _: StaffDep):
    return patient_service.get_patient(db, patient_id)


@router.post("", response_model=PatientOut, status_code=201)
def create_patient(body: PatientCreate, request: Request, db: DbDep, _: AdminDep):
    return patient_service.create_patient(db, request.app.state.clock, body)


@router.patch("/{patient_id}", response_model=PatientOut)
def update_patient(patient_id: int, body: PatientUpdate, db: DbDep, _: AdminDep):
    return patient_service.update_patient(db, patient_id, body)
