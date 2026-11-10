"""pydantic 입력/출력 모델."""
from datetime import date

from pydantic import BaseModel, ConfigDict, Field

from .models import PatientStatus, Role


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
