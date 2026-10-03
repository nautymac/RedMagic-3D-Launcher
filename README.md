# 3D 런처 — Red Magic 3D 태블릿용

![3D Launcher](docs/banner.jpg)

**설치된 아무 앱이나 안경 없는 3D 로 실행합니다.** 게임, 유튜브 등 일반 2D 앱을 기기의 Leia 2D→3D 엔진으로 변환해
3D 화면에 띄웁니다. 터치와 뒤로 가기도 그대로 됩니다.

- 대상 기기: **Red Magic 3D Explorer Edition 태블릿 (NP02J)** 전용. 다른 기기에서는 동작하지 않습니다.
- 루팅 필요 없음. 대신 **Shizuku** 앱이 필요합니다 (아래 설명).
- 기기 내장 "3D 모드" 보다 3D 품질이 좋고 끊김이 적습니다 (실기 비교).

> English summary: Runs any Android app in glasses-free 3D on the Red Magic 3D Explorer tablet (NP02J).
> Install Shizuku, start it via wireless debugging, allow 3D Launcher in Shizuku, then tap an app.

---

## 설치

### 1. 앱 두 개 받기
- **3D 런처**: [Releases](https://github.com/nautymac/RedMagic-3D-Launcher/releases/latest) 에서 `RedMagic-3D-Launcher-*.apk`
- **Shizuku**: [Shizuku Releases](https://github.com/RikkaApps/Shizuku/releases/latest) 의 apk, 또는 Google Play 에서 "Shizuku"

태블릿에서 둘 다 설치합니다 (파일 관리자에서 apk 를 누르고, "알 수 없는 앱 설치" 를 허용).

### 2. 개발자 옵션 켜기 (처음 한 번)
설정 → 휴대전화 정보(태블릿 정보) → **빌드 번호를 7번 누릅니다.** "개발자가 되었습니다" 가 나오면 됩니다.

### 3. Shizuku 페어링 (처음 한 번)
1. Wi-Fi 에 연결합니다.
2. **Shizuku** 앱 → "무선 디버깅으로 시작" → **페어링** (알림 권한을 묻으면 허용).
3. 설정 → 개발자 옵션 → **무선 디버깅** 을 켜고 들어가서 → **"페어링 코드로 기기 페어링"** 을 누릅니다. 6자리 코드가 나옵니다.
4. 화면 위에서 알림창을 내려 Shizuku 알림의 입력칸에 그 6자리 코드를 넣습니다.
5. "페어링 성공" 알림이 나오면 끝입니다.

### 4. Shizuku 시작
Shizuku 앱 → **"시작"**. 위쪽에 "Shizuku 가 실행 중입니다" 가 나오면 됩니다.

> **"디버깅 서비스 찾는 중" 에서 멈출 때** — 일부 Wi-Fi 환경에서는 Shizuku 가 무선 디버깅 포트를 찾지 못합니다.
> 다른 Wi-Fi(예: 휴대폰 핫스팟)에서 다시 시도하거나, PC 가 있으면 Shizuku 앱의 **컴퓨터(adb)로 시작하는 방법** 안내에
> 나오는 `adb` 명령을 한 번 실행하세요. Shizuku 는 재부팅 전까지 계속 켜져 있습니다.

### 5. 3D 런처 실행
3D 런처를 열고 **"권한 요청" → 허용** 합니다. 위쪽에 **"준비됨"** 이 나오면 끝입니다.

---

## 사용법

| 동작 | 방법 |
|---|---|
| 앱을 3D 로 실행 | 목록에서 앱을 누릅니다 |
| 앱별 설정 | 앱을 **길게** 누릅니다 — 즐겨찾기, 2D→3D 변환 / SBS, 깊이 |
| 즐겨찾기만 보기 | 위쪽 **"즐겨찾기만" / "전체 보기"** 버튼 |
| 게임 안에서 뒤로 가기 | 평소처럼 뒤로 제스처 |
| 깊이 조절 · 나가기 | 화면 가장자리를 쓸어 시스템 바를 꺼내면 위쪽에 **나가기 / 깊이 − +** 막대가 나옵니다 |

- 3D 화면을 나가면 그 앱도 함께 닫힙니다. 앱이 스스로 끝나면 3D 화면도 닫힙니다.
- 앱을 실행할 때 그 앱이 이미 켜져 있으면 껐다가 새로 켭니다.
- **SBS 모드**: 화면을 좌우 두 눈으로 그리는 앱(에뮬레이터의 SBS 출력 등)은 앱 설정에서 "SBS" 를 고르세요.

## 재부팅한 뒤에는
페어링·권한은 다시 할 필요가 없습니다. **무선 디버깅을 켜고 → Shizuku 앱에서 "시작"** 만 누르면 됩니다.
(재부팅하면 Android 가 무선 디버깅을 끕니다. 루팅하지 않은 기기의 제약입니다.)

## 문제 해결
- **"도우미 연결이 늦어지고 있습니다"** — 최근 앱에서 "모두 지우기" 를 하면 Shizuku 앱이 꺼져서 생깁니다.
  "Shizuku 열기" 를 눌러 Shizuku 를 한 번 열었다가 돌아오면 다시 연결됩니다.
  **최근 앱 화면에서 3D 런처와 Shizuku 카드를 잠가 두면** 이 일이 생기지 않습니다.
- **"Shizuku 가 꺼져 있습니다"** — 재부팅했거나 Shizuku 가 멈춘 것입니다. 위 "Shizuku 시작" 을 다시 하세요.
- **화면이 검게 나옴** — 넷플릭스 같은 복제 방지 앱은 3D 로 볼 수 없습니다.
- **"이 앱은 3D 화면에서 실행되지 않았습니다"** — 일부 앱·게임은 보조 화면에서 실행되기를 거부합니다.

---

## 개발자용

### 구조
- `HelperService` — shell 권한 도우미. Shizuku UserService 로 뜬다 (daemon 끔: 앱이 죽으면 같이 죽는다).
  신뢰된 가상 디스플레이 생성/해제, `cmd activity start-activity --display N`, 터치·키 주입, 디스플레이의 태스크 수 조회.
  일반 앱이 만든 가상 디스플레이에는 다른 앱을 올릴 수 없어서(`Not allow to launch ... on display N`) shell 권한이 필요하다.
- `Helper` — 앱 쪽 Shizuku 연결·권한·바인딩. 막힌 바인딩은 8초 뒤 다시 띄운다.
- `MainActivity` — 앱 선택 화면, 앱별 설정, 즐겨찾기.
- `ViewActivity` — 3D 화면.
  ```
  [2D] 앱 ─▶ 가상 디스플레이 1920x1200 = 엔진 입력면 ─▶ Leia 2D→3D 엔진 ─▶ CNSDK 입력면 ─▶ 위빙
  [SBS] 앱 ─▶ 가상 디스플레이 3840x1200 = CNSDK 입력면 ──────────────────────▶ 위빙
  터치 ─▶ 디스플레이 좌표로 변환 ─▶ 도우미 ─▶ injectInputEvent(displayId)
  ```
  2D→3D 엔진은 기기의 `com.leiainc.media.service` 를 DexClassLoader 로 불러 쓴다 (`LeiaMediaSDK.java`).

### 빌드
`gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

Leia CNSDK 커넥터는 기기의 CalibrationK68 시스템 앱에서 꺼낸 것이라 저장소에 넣지 않았다. 빌드하려면 직접 넣어야 한다:
- `app/libs/leia-cnsdk.jar`
- `app/src/main/jniLibs/arm64-v8a/libleiaCore-loader.so`, `libleiaSDK-jni.so`
- `app/src/main/assets/shaders/`, `app/src/main/assets/cnsdk.version`

꺼내는 방법과 파일 묶음은 [DepthFlix](https://github.com/nautymac/DepthFlix) 의 redmagic 플레이버와 같다.

로그: `adb logcat -s L3D L3DHelper P3DLeiaMl`
