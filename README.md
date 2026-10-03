# 3D 런처 (RedMagic 태블릿 NP02J)

![3D Launcher](docs/banner.jpg)

다른 앱을 신뢰된 가상 디스플레이에 띄우고 Leia 2D→3D 엔진 + CNSDK 위빙으로 3D 로 보여 주는 런처.
배경과 실기 데모 결과: `D:\ClaudeCode\projects\RedMagic-Tablet\SBS·게임 3D 변환 가능성 검토.md`

## 구조
- `HelperService` — shell 권한 도우미. Shizuku UserService 로 뜬다 (daemon 끔: 앱이 죽으면 같이 죽는다).
  신뢰된 가상 디스플레이 생성/해제, `cmd activity start-activity --display N`, 터치·키 주입, 디스플레이의 태스크 수 조회.
- `Helper` — 앱 쪽 Shizuku 연결·권한·바인딩.
- `MainActivity` — 앱 선택 화면. 누르면 3D 실행, 길게 누르면 앱별 설정(2D 변환/SBS, 깊이 gain).
- `ViewActivity` — 3D 화면. DepthFlix 데모(VdSpikeActivity)의 위빙·엔진 연결을 그대로 옮김.
  - 뒤로 제스처 → 앱에 KEYCODE_BACK 주입
  - 가장자리를 쓸어 시스템 바를 꺼내면 위쪽에 도구 막대(나가기, 깊이 −/+)
  - 나가면 디스플레이 해제(DESTROY_CONTENT_ON_REMOVAL 로 앱 화면도 닫힘)
  - 앱이 스스로 끝나 디스플레이가 비면 자동으로 닫힘
- 실행 전에 대상 앱을 force-stop 한다 (이미 떠 있는 태스크가 이 디스플레이로 옮겨지지 않을 수 있어서).

## 빌드
`gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (DepthFlix 와 같은 키로 서명)

로컬에만 있는 파일(Leia 것이라 저장소에 넣지 않는다 — 기기의 CalibrationK68 에서 꺼낸다):
- `app/libs/leia-cnsdk.jar`
- `app/src/main/jniLibs/arm64-v8a/libleiaCore-loader.so`, `libleiaSDK-jni.so`
- `app/src/main/assets/shaders/`, `app/src/main/assets/cnsdk.version`

꺼내는 방법과 같은 파일 묶음은 [DepthFlix](https://github.com/nautymac/DepthFlix) 의 redmagic 플레이버와 같다.

## 설치·사용
- 태블릿은 adb 설치가 막혀 있다. APK 를 Download 에 넣고 직접 설치한다.
- Shizuku: 처음 한 번 무선 디버깅 페어링, 재부팅 뒤에는 Shizuku 앱에서 "시작".
- 로그: `adb logcat -s L3D L3DHelper P3DLeiaMl`
