# 모유 관리 서버

병원 모유 재고 관리 서버 (FastAPI + SQLite). 1단계: 서버 기초.

## 설치

```
uv sync
```

## 첫 관리자 만들기

```
uv run milkbank-create-admin --username admin --name 관리자
```

임시 비밀번호가 출력된다. 처음 로그인한 뒤 비밀번호를 바꿔야 다른 기능을 쓸 수 있다.

## 서버 실행

```
uv run uvicorn milkbank.main:app_factory --factory --host 0.0.0.0 --port 8000
```

API 문서와 시험 화면은 `http://127.0.0.1:8000/docs`에서 볼 수 있다.

## DB 위치

환경 변수 `MILKBANK_DB`로 정한다. 없으면 `data/milkbank.db`를 쓴다(`server/data/`는 git에서 제외된다).

## 테스트

```
uv run pytest
```

## 참고

HTTPS는 2단계에서 추가한다(자체 서명 인증서). 그 전에는 병원 내부망에서만 쓴다.
