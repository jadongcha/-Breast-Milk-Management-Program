# 모유 관리 간호사 앱 (Android)

NICU 모유 재고 관리 앱. 간호사·관리자가 입고 확인, 사용, 폐기, 기록 취소를 한다. 서버(`server/`)와 HTTPS로만 통신한다.

## 요구 사항

- Android Studio(내장 JDK 사용), Android SDK 37
- minSdk 26 / targetSdk 37 (Galaxy 기기 Android 8 이상)

## 빌드

`android/` 폴더에서 실행한다. Gradle이 시스템 JDK 대신 Android Studio 내장 JDK(JBR)를 쓰도록 `JAVA_HOME`을 먼저 지정한다.

```
# Windows (Git Bash)
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug          # 앱 만들기 → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM 시험
./gradlew connectedDebugAndroidTest   # 기기/에뮬레이터 화면 시험 (기기 연결 필요)
```

PowerShell에서는 `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` 후 `.\gradlew.bat assembleDebug`.
처음 빌드할 때 라이브러리를 내려받는다.

## 설치

### 에뮬레이터

Android Studio의 Device Manager에서 가상 기기(예: Medium Phone, API 37)를 만들어 켠 뒤:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 실제 Galaxy 기기

1. 설정 → 휴대전화 정보 → 소프트웨어 정보에서 "빌드번호"를 7번 눌러 개발자 모드를 켠다.
2. 설정 → 개발자 옵션에서 "USB 디버깅"을 켠다.
3. USB로 PC에 연결하고 기기에서 "이 컴퓨터에서 USB 디버깅 허용"을 누른다.
4. `adb devices`에 기기가 보이면 위와 같이 `adb install -r ...app-debug.apk`.

정식 배포용 서명 키와 설치 파일(릴리스 빌드)은 7단계에서 만든다. 지금의 debug 빌드는 개발·시험용이다.

## 서버에 연결하기

앱은 처음에 "서버 등록 QR"을 찍어 병원 서버의 주소와 인증서 지문을 저장한다. QR은 서버에서 `milkbank-setup-tls`로 만든다(`server/README.md` 참고).

### 에뮬레이터에서 PC의 서버로

에뮬레이터 안에서 PC는 `10.0.2.2`로 보인다.

```
cd server
uv run milkbank-setup-tls --host 10.0.2.2 --port 8443
uv run milkbank-create-admin --username admin --name 관리자
uv run milkbank-serve
```

### 실제 기기에서

기기와 서버 PC가 같은 병원 와이파이에 있어야 한다. `--host`에 서버 PC의 내부 IP를 준다(고정 IP 권장).

```
uv run milkbank-setup-tls --host <서버 PC의 내부 IP>
```

서버 PC 방화벽에서 8443 포트 수신을 허용해야 한다. 생성된 `data/tls/server-qr.png`를 앱의 등록 화면에서 카메라로 찍는다.

### 개발용 등록 (QR 없이)

debug 빌드의 등록 화면에는 "테스트용 서버로 등록 (개발용)" 버튼이 있다(release 빌드에는 없다). 앱이 읽는 파일 `qr.txt`에는 QR에 들어가는 JSON을 한 줄로 넣는다.

```
{"type":"milkbank-server","v":1,"url":"https://10.0.2.2:8443","pin":"sha256/..."}
```

`url`과 `pin`은 서버의 `data/tls/server.json`(DB 위치를 바꿨다면 그 DB 폴더의 `tls/server.json`) 값이다. 이 파일을 기기의 앱 전용 폴더에 넣는다.

```
adb shell mkdir -p /sdcard/Android/data/app.milkbank/files
adb push qr.txt /sdcard/Android/data/app.milkbank/files/qr.txt
```

(Git Bash에서는 `/sdcard/...` 경로가 바뀌지 않도록 명령 앞에 `MSYS_NO_PATHCONV=1`을 붙인다.) 그다음 버튼을 누르면 등록된다.

## 권한

- **카메라**: QR을 찍을 때 필요하다.
- **근처 기기(로컬 네트워크)**: Android 17(API 37)부터 앱이 `10.0.2.2`나 `192.168.x.x` 같은 내부망 주소에 접속하려면 이 권한이 필요하다. 앱을 처음 열 때 나오는 안내에서 허용한다. 허용하지 않으면 와이파이가 연결돼 있어도 서버에 닿지 않는다. 에뮬레이터나 시험 기기에서는 다음으로 미리 허용할 수 있다.

  ```
  adb shell pm grant app.milkbank android.permission.ACCESS_LOCAL_NETWORK
  adb shell pm grant app.milkbank android.permission.CAMERA
  ```

  이미 거부했다면 설정 → 앱 → 모유 관리 → 권한에서 "근처 기기"를 허용한다.

## 동작 메모

- 10분 동안 아무 조작이 없으면 자동 로그아웃한다.
- 30초마다 서버 연결을 확인하며, 끊기면 "서버 연결 안 됨" 띠가 뜨고 입고·사용·폐기 버튼이 막힌다. 연결이 돌아오면 사라진다.
- 화면에는 아기 이름을 보이지 않고 아기 ID(등록번호)만 보인다.
