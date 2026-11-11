from typing import Annotated

from fastapi import APIRouter, Depends, Request, Response

from .. import stations as station_service
from ..errors import BadRequest
from ..models import Staff
from ..schemas import (
    StationCreateIn,
    StationCreateOut,
    StationOut,
    StationPatchIn,
    StationSelfOut,
)
from .deps import DbDep, StationDep, require_admin

router = APIRouter(tags=["stations"])

AdminDep = Annotated[Staff, Depends(require_admin)]


@router.post("/stations", response_model=StationCreateOut, status_code=201)
def create_station(body: StationCreateIn, request: Request, db: DbDep, actor: AdminDep):
    station, token = station_service.create_station(
        db, request.app.state.clock, name=body.name, actor=actor
    )
    return StationCreateOut(station=StationOut.model_validate(station), station_token=token)


@router.get("/stations", response_model=list[StationOut])
def list_stations(db: DbDep, _: AdminDep):
    return station_service.list_stations(db)


@router.patch("/stations/{station_id}", response_model=StationOut)
def patch_station(station_id: int, body: StationPatchIn, db: DbDep, _: AdminDep):
    if body.active:
        raise BadRequest("사용 중지한 제출처는 다시 켤 수 없습니다. 새로 등록해 주세요")
    return station_service.deactivate_station(db, station_id)


@router.get("/station/self", response_model=StationSelfOut)
def station_self(station: StationDep):
    return station


@router.delete("/station/self", status_code=204)
def delete_station_self(db: DbDep, _: AdminDep, station: StationDep):
    station_service.deactivate_station(db, station.id)
    return Response(status_code=204)
