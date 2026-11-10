# 모유 관리 서버

병원 모유 재고 관리 서버 (FastAPI + SQLite). 1단계: 서버 기초.

## 설치

아래 명령은 모두 `server/` 폴더에서 실행한다(DB 경로가 상대 경로라서, 다른 폴더에서 실행하면 다른 DB가 만들어진다).

```
uv sync
```

## 첫 관리자 만들기

```
uv run milkbank-create-admin --username admin --name 관리자
```

임시 비밀번호가 출력된다. 처음 로그인한 뒤 비밀번호를 바꿔야 다른 기능을 쓸 수 있다.
이미 있는 아이디면 오류 메시지를 출력하고 종료 코드 1로 끝난다. 아이디는 대소문자를 구분하지 않는다(소문자로 저장).

### 관리자가 비밀번호를 잊었을 때

관리자가 한 명뿐이고 비밀번호를 잊었다면, 위 명령으로 다른 아이디의 관리자를 하나 더 만든다.
그 관리자로 로그인해 `POST /staff/{id}/reset-password`로 기존 관리자의 비밀번호를 초기화한다.

## 서버 실행

```
uv run uvicorn milkbank.main:app_factory --factory --host 0.0.0.0 --port 8000
```

`GET /health`는 로그인 없이 `{"status": "ok"}`를 돌려준다(연결 확인용). `GET /auth/check`는 로그인 유지 여부만 확인하며 자동 로그아웃 시간(10분)을 늘리지 않는다.

API 문서와 시험 화면은 `http://127.0.0.1:8000/docs`에서 볼 수 있다.

## DB 위치

환경 변수 `MILKBANK_DB`로 정한다. 없으면 `data/milkbank.db`를 쓴다(`server/data/`는 git에서 제외된다).

## 테스트

```
uv run pytest
```

## 참고

HTTPS는 2단계에서 추가한다(자체 서명 인증서). 그 전에는 병원 내부망에서만 쓰고, **실제 환자 정보와 실제 비밀번호는 넣지 않는다.**
