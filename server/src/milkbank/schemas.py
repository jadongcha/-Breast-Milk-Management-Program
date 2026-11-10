"""pydantic 입력/출력 모델."""
from pydantic import BaseModel, ConfigDict

from .models import Role


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
