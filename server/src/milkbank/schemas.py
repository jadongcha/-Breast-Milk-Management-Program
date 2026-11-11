"""pydantic 입력/출력 모델."""
from datetime import date, datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from .models import (
    DiscardReason,
    PatientStatus,
    RecordKind,
    RejectReason,
    Role,
    SubmissionStatus,
)


class StaffOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    username: str
    name: str
    role: Role
    active: bool
    must_change_password: bool


class LoginIn(BaseModel):
    username: str
    password: str


class LoginOut(BaseModel):
    token: str
    must_change_password: bool
    staff: StaffOut


class PasswordChangeIn(BaseModel):
    current_password: str
    new_password: str


class StaffCreateIn(BaseModel):
    username: str
    name: str
    role: Role


class StaffCreateOut(BaseModel):
    staff: StaffOut
    temp_password: str


class StaffPatchIn(BaseModel):
    active: bool


class TempPasswordOut(BaseModel):
    temp_password: str


class PatientCreate(BaseModel):
    reg_no: str
    baby_name: str
    bed: str
    guardian_name: str
    guardian_phone: str
    admitted_on: date | None = None
    alert_enabled: bool = True
    days_threshold: int | None = Field(default=None, ge=1, le=30)
    packs_threshold: int | None = Field(default=None, ge=1, le=50)


class PatientUpdate(BaseModel):
    reg_no: str | None = None
    baby_name: str | None = None
    bed: str | None = None
    guardian_name: str | None = None
    guardian_phone: str | None = None
    admitted_on: date | None = None
    alert_enabled: bool | None = None
    days_threshold: int | None = Field(default=None, ge=1, le=30)
    packs_threshold: int | None = Field(default=None, ge=1, le=50)


class PatientOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    reg_no: str
    baby_name: str
    bed: str
    guardian_name: str
    guardian_phone: str
    status: PatientStatus
    admitted_on: date
    discharged_on: date | None
    alert_enabled: bool
    days_threshold: int | None
    packs_threshold: int | None
    balance: int


class RecordIn(BaseModel):
    kind: Literal["intake", "use", "discard"]
    count: int
    discard_reason: DiscardReason | None = None
    memo: str | None = None


class RecordOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    kind: RecordKind
    count: int
    discard_reason: DiscardReason | None
    target_id: int | None
    memo: str | None
    staff_id: int
    staff_name: str
    created_at: datetime
    canceled: bool


class RecordResult(BaseModel):
    record: RecordOut
    balance: int


class PatientDetail(PatientOut):
    records: list[RecordOut]


class StationCreateIn(BaseModel):
    name: str


class StationPatchIn(BaseModel):
    active: bool


class StationOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    active: bool
    created_at: datetime


class StationCreateOut(BaseModel):
    station: StationOut
    station_token: str


class StationSelfOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str


class GuardianLoginIn(BaseModel):
    phone: str
    baby_id: str


class BabyOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    patient_id: int
    baby_id: str
    balance: int
    pending_count: int


class GuardianLoginOut(BaseModel):
    token: str
    babies: list[BabyOut]


class SubmitIn(BaseModel):
    patient_id: int
    count: int


class SubmittedOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    count: int
    submitted_at: datetime


class SubmitResult(BaseModel):
    submission: SubmittedOut
    balance: int
    pending_count: int


class SubmissionOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    patient_id: int
    count: int
    submitted_at: datetime
    status: SubmissionStatus
    confirmed_count: int | None
    handled_by: int | None
    handled_at: datetime | None
    reject_reason: RejectReason | None
    memo: str | None


class PendingOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    patient_id: int
    baby_id: str
    balance: int
    station_name: str
    submitted_at: datetime
    count: int
    discharged: bool


class ConfirmIn(BaseModel):
    count: int


class ConfirmResult(BaseModel):
    record: RecordOut
    balance: int
    submission: SubmissionOut


class RejectIn(BaseModel):
    reason: RejectReason
    memo: str | None = None


class RejectResult(BaseModel):
    submission: SubmissionOut
