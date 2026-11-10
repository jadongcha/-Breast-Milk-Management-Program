from datetime import UTC, date, datetime
from enum import StrEnum

from sqlalchemy import DateTime, Enum, ForeignKey
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator


class UTCDateTime(TypeDecorator):
    """UTC naive로 저장하고, 읽을 때 tz-aware UTC로 돌려준다."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("tz-aware datetime이 필요합니다")
        return value.astimezone(UTC).replace(tzinfo=None)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        return value.replace(tzinfo=UTC)


class Base(DeclarativeBase):
    pass


class Role(StrEnum):
    ADMIN = "admin"
    NURSE = "nurse"


class PatientStatus(StrEnum):
    ADMITTED = "admitted"
    DISCHARGED = "discharged"


class RecordKind(StrEnum):
    INTAKE = "in"
    USE = "use"
    DISCARD = "discard"
    RETURN = "return"
    CANCEL = "cancel"


class DiscardReason(StrEnum):
    THAWED_LEFTOVER = "thawed_leftover"
    CONTAMINATED = "contaminated"
    OTHER = "other"


class AlertState(StrEnum):
    NORMAL = "normal"
    ALERTED = "alerted"


def _enum_type(enum_cls: type[StrEnum]) -> Enum:
    # 이름이 아니라 값 문자열("in", "admitted" ...)을 저장한다.
    return Enum(
        enum_cls,
        values_callable=lambda e: [m.value for m in e],
        native_enum=False,
        create_constraint=True,
        length=20,
    )


class Staff(Base):
    __tablename__ = "staff"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(unique=True)
    name: Mapped[str]
    role: Mapped[Role] = mapped_column(_enum_type(Role))
    password_hash: Mapped[str]
    must_change_password: Mapped[bool]
    active: Mapped[bool] = mapped_column(default=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)


class AuthSession(Base):
    __tablename__ = "auth_session"

    id: Mapped[int] = mapped_column(primary_key=True)
    token_hash: Mapped[str] = mapped_column(unique=True)
    staff_id: Mapped[int] = mapped_column(ForeignKey("staff.id"))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    last_seen_at: Mapped[datetime] = mapped_column(UTCDateTime)


class Patient(Base):
    __tablename__ = "patient"

    id: Mapped[int] = mapped_column(primary_key=True)
    reg_no: Mapped[str] = mapped_column(unique=True)
    baby_name: Mapped[str]
    bed: Mapped[str]
    guardian_name: Mapped[str]
    guardian_phone: Mapped[str]
    status: Mapped[PatientStatus] = mapped_column(
        _enum_type(PatientStatus), default=PatientStatus.ADMITTED
    )
    admitted_on: Mapped[date]
    discharged_on: Mapped[date | None]
    alert_enabled: Mapped[bool] = mapped_column(default=True)
    days_threshold: Mapped[int | None]
    packs_threshold: Mapped[int | None]
    alert_state: Mapped[AlertState] = mapped_column(
        _enum_type(AlertState), default=AlertState.NORMAL
    )
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)


class StockRecord(Base):
    __tablename__ = "stock_record"

    id: Mapped[int] = mapped_column(primary_key=True)
    patient_id: Mapped[int] = mapped_column(ForeignKey("patient.id"))
    kind: Mapped[RecordKind] = mapped_column(_enum_type(RecordKind))
    count: Mapped[int]
    discard_reason: Mapped[DiscardReason | None] = mapped_column(
        _enum_type(DiscardReason)
    )
    target_id: Mapped[int | None] = mapped_column(ForeignKey("stock_record.id"))
    memo: Mapped[str | None]
    staff_id: Mapped[int] = mapped_column(ForeignKey("staff.id"))
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
