# 모유 관리 서버

병원 모유 재고 관리 서버 (FastAPI + SQLite). 1단계: 서버 기초, 2-1단계: 서버 보강(HTTPS, DB 버전 관리, 로그인 잠금).

## 설치 순서

아래 명령은 모두 `server/` 폴더에서 실행한다(DB 경로가 상대 경로라서, 다른 폴더에서 실행하면 다른 DB가 만들어진다).
1단계 때 만든 테스트용 DB가 있으면 **지우고 시작한다**(버전 기록이 없는 DB는 열지 않는다). `data/milkbank.db`, `data/milkbank.db-wal`, `data/milkbank.db-shm` 세 파일을 모두 지운다.

1. 설치

   ```
   uv sync
   ```

2. 인증서와 서버 등록 QR 만들기

   ```
   uv run milkbank-setup-tls --host <서버 PC의 내부 IP>
   ```

   `--host`에는 기기가 서버에 접속할 주소(서버 PC의 내부 IP)를 준다. 여러 개를 줄 수 있고, QR에는 첫 번째 주소를 쓴다. 첫 번째 주소는 IPv4 주소나 이름이어야 한다(IPv6는 두 번째부터 가능). 전산팀에 서버 PC의 IP를 **고정**(공유기/DHCP 예약)해 달라고 요청한다. IP가 바뀌면 기기가 서버를 찾지 못한다. 포트는 기본 8443이며 `--port`로 바꿀 수 있다.
   `data/tls/server-qr.png`를 출력해 신생아실에 보관한다. 기기 앱이 이 QR을 찍어 진짜 병원 서버를 알아본다.

3. 첫 관리자 만들기

   ```
   uv run milkbank-create-admin --username admin --name 관리자
   ```

   임시 비밀번호가 출력된다. 처음 로그인한 뒤 비밀번호를 바꿔야 다른 기능을 쓸 수 있다.
   이미 있는 아이디면 오류 메시지를 출력하고 종료 코드 1로 끝난다. 아이디는 대소문자를 구분하지 않는다(소문자로 저장).

4. 서버 실행 (HTTPS 전용)

   ```
   uv run milkbank-serve
   ```

   `data/tls/`의 인증서로 HTTPS로만 실행한다(암호화하지 않은 HTTP 포트는 열지 않는다). 인증서가 없으면 "먼저 milkbank-setup-tls를 실행하세요"를 출력하고 종료 코드 1로 끝난다.

### 서버 PC 설정과 주의

- 서버 PC의 Windows 방화벽에서 8443 포트로 들어오는 연결을 허용한다(전산팀). `--port`를 바꿨다면 그 포트를 허용한다.
- `data/` 폴더(DB, 백업, 개인키)는 서버 PC 밖으로 복사하지 않는다.
- 개인키가 새면 `uv run milkbank-setup-tls --host <IP> --new-key`로 다시 만들고, 모든 기기에서 서버 등록 QR을 다시 찍어 등록한다.
- 서버 등록 QR에는 **첫 번째 `--host` 주소와 포트**가 들어 있다. 첫 번째 주소나 포트를 바꾸거나 `--new-key`를 쓰면 모든 기기에서 QR을 다시 찍어야 한다. 이름이나 주소를 **추가**만 하면(첫 번째 주소는 그대로) 기기는 그대로 쓸 수 있다.
- `milkbank-setup-tls`를 다시 실행했다면 `milkbank-serve`를 **껐다가 다시 켠다**. 인증서는 서버가 시작할 때만 읽는다.

### 백업과 복원

- DB 버전을 올릴 때마다 서버가 시작 전에 `data/backups/`에 백업을 만든다(`milkbank-<시각>-before-<버전>.db`). 이 폴더는 계속 커지므로 오래된 백업은 직접 지운다.
- 백업으로 되돌리려면: 서버를 끄고 → `data/milkbank.db`를 백업 파일로 바꾸고 → `data/milkbank.db-wal`, `data/milkbank.db-shm`을 지우고 → 서버를 켠다.

### 관리자가 비밀번호를 잊었을 때

관리자가 한 명뿐이고 비밀번호를 잊었다면, 위 명령으로 다른 아이디의 관리자를 하나 더 만든다.
그 관리자로 로그인해 `POST /staff/{id}/reset-password`로 기존 관리자의 비밀번호를 초기화한다. 로그인 잠금도 함께 풀린다.

## 제출처와 보호자 제출 (2-3a단계)

보호자가 제출처 기기(태블릿)에서 가져온 팩 수를 제출하면, 간호사가 실제 수를 확인한 뒤에야 재고가 늘어난다(확인하면 일반 입고 기록이 생긴다).

- 제출처 등록·사용 중지: `POST /stations`, `GET /stations`, `PATCH /stations/{id}`(관리자). 등록할 때 제출처 토큰이 한 번만 보인다.
- 제출처 기기: `GET /station/self`, `DELETE /station/self`(관리자 토큰 + 제출처 토큰. 앱에서 제출처 모드를 해제할 때 부른다)
- 보호자: `POST /guardian/login`, `GET /guardian/babies`, `POST /guardian/submissions`, `POST /guardian/logout`
- 간호사: `GET /submissions?status=pending`(확인 대기 목록), `POST /submissions/{id}/confirm`(실제 팩 수로 확인), `POST /submissions/{id}/reject`(반려: `not_received` 또는 `other`+메모)
- 제출처 기기는 모든 `/guardian/*` 요청에 헤더 `X-Station-Token`을 보내고, 보호자 로그인 뒤에는 `Authorization: Bearer <보호자 토큰>`도 보낸다. 제출처 태블릿은 제출처 토큰을 쓰고, 보호자는 서버에 등록된 병원 기기라면 어디서든 로그인할 수 있다(2-4단계).
- 보호자 세션은 마지막 요청 뒤 1분이 지나면 끝난다.
- 제출처 토큰은 선택이다(2-4단계). 헤더 없이 로그인하면 일반 병원 기기 세션이 되고(`station_id`가 비어 있다), 그 세션은 제출처 토큰 없이만 쓸 수 있다. 제출처 세션은 같은 제출처 토큰으로만 쓸 수 있고, 어긋나면 401 "로그인이 필요합니다"이다. 헤더가 있는데 무효이면 401 `station_invalid`이다.
- 일반 기기 잠금: 같은 접속 IP에서 10분 안에 20번 실패하면 그 IP의 토큰 없는 로그인이 10분 동안 잠긴다(429). 제출처 토큰으로 하는 로그인에는 영향이 없다. 전화번호 잠금은 그대로다.
- 일반 기기로 낸 제출은 간호사 확인 대기 목록에 제출처 이름 대신 "병원 기기"로 보이고, 확인하면 기록 메모가 "병원 기기 제출분(제출 N팩)"이 된다.
- 잠금: 같은 전화번호로 5번 로그인에 실패하면 10분, 한 제출처에서 10분 안에 20번 실패하면 그 제출처 로그인이 10분 동안 잠긴다. 아기 한 명당 확인 대기 제출은 5건까지다.
- 사용 중지한 제출처는 다시 켤 수 없다(필요하면 새로 등록한다). 사용 중지하면 그 제출처의 보호자 세션도 끝난다.

## 개발용 실행 (HTTP)

개발과 시험용이다. 암호화되지 않으므로 병원 내부망의 실제 운영에는 쓰지 않는다.

```
uv run uvicorn milkbank.main:app_factory --factory --host 127.0.0.1 --port 8000
```

`GET /health`는 로그인 없이 `{"status": "ok"}`를 돌려준다(연결 확인용). `GET /auth/check`는 로그인 유지 여부만 확인하며 자동 로그아웃 시간(10분)을 늘리지 않는다.

API 문서와 시험 화면은 `http://127.0.0.1:8000/docs`에서 볼 수 있다.

## 마이그레이션 추가하는 법 (개발자용)

1. `models.py`를 고친 뒤 `uv run python scripts/new_migration.py "설명"`을 실행한다(`alembic.ini` 없이 임시 DB로 초안을 `src/milkbank/migrations/versions/`에 만든다). 초안은 사람이 검토하고 `down_revision`과 번호를 확인한다.
2. SQLite에서 열 변경 등은 표 재생성(batch)으로 처리된다. 외래 키가 걸린 표를 재생성해도 실패하지 않도록 `env.py`가 `milkbank.migrate.foreign_keys_off`로 감싸고, 마이그레이션마다 한 트랜잭션(DDL+버전 기록)으로 실행하며 버전 기록 직후 `foreign_key_check`를 한다. 깨지면 그 마이그레이션이 통째로 되돌려진다. 테스트에서 직접 쓸 때는 `with foreign_keys_off(conn): ...` 안에서 마이그레이션 트랜잭션을 연다.
3. enum 열의 CHECK 제약은 이름 없이 만들어지므로 재생성할 때 enum 이름(`name='recordkind'` 등)으로 이름을 붙여 준다.
4. 시험은 `seeded_engine` 픽스처(직원·환자·재고 기록이 들어 있는 최신 DB)를 이전 버전으로 내린 뒤 올려서, 데이터가 있는 DB에서 새 마이그레이션이 되는지 확인한다. 모델과 마이그레이션이 같은지는 `tests/test_migrations.py`의 스키마 비교 테스트가 잡는다.
5. 이미 배포된 마이그레이션 파일은 고치지 않고 새 파일을 추가한다.

## DB 위치

환경 변수 `MILKBANK_DB`로 정한다. 없으면 `data/milkbank.db`를 쓴다(`server/data/`는 git에서 제외된다).

## 테스트

```
uv run pytest
```

## 참고

HTTPS 적용됨(자체 서명 인증서). 같은 아이디로 5번 로그인에 실패하면 10분 동안 잠긴다. 아직 개발 중이므로 **실제 환자 정보와 실제 비밀번호는 넣지 않는다.**
