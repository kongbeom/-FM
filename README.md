# BACKFM

**같은 여행, 서로 다른 기억. 우리만의 귀갓길 라디오.**

현재 버전: **0.1.3**. 같은 단계의 선택·상태 갱신 후 스크롤 위치를 유지하고 방송 만들기 문구를 ‘저장된 기억’으로 통일했습니다. 이전 버전의 재생·잔여시간·귀가 목적지 식별 보완도 포함합니다.

확인한 방문 장소와 탑승자별 비공개 선택을 연결하는 Kotlin Android 앱입니다. 방송에서 같은 장소에 남은 서로 다른 마음을 공개합니다. UI 목업이 아니라 Room 저장소, 대본 생성, 미디어 서비스가 연결된 Android 프로젝트입니다.

## GitHub 공모전 제출

팀명은 **여운FM**, 앱 이름은 **BACKFM**입니다. 공개 제출 저장소는 [kongbeom/-FM](https://github.com/kongbeom/-FM)입니다.

제출 APK는 [apk/여운FM_BACKFM_release.apk](apk/여운FM_BACKFM_release.apk)입니다. `pleosRelease` 변형을 전용 Release 키로 서명한 버전 0.1.3(versionCode 4)입니다. 서명·설치·독립 실행을 확인했고, 이 APK를 대상으로 Android 계측 테스트 25개가 모두 통과했습니다.

심사 환경은 Android 9(API 28) 이상이며, 차량 기능은 Pleos SDK를 지원하는 환경에서 확인합니다. 최초 실행 시 Demo Mode의 춘천 여정으로 시연할 수 있습니다. 한국어 음성 생성에는 기기에 설치된 오프라인 한국어 TTS 음성이 필요하며, API 키는 필요하지 않습니다. 실제 내비 모드는 설정에서 내비 권한을 허용하고 연결합니다. 검증 환경은 공개 AOSP API 34 가상기기이며, 실제 Pleos 차량 검증은 별도입니다.

최종 APK 설치·실행 명령은 다음과 같습니다. 동일 앱 ID의 Debug 버전과 서명이 다르므로 별도 시험 기기에서 설치를 확인하세요.

```powershell
adb install -r "apk/여운FM_BACKFM_release.apk"
adb shell am start -n ai.backfm/ai.backfm.ui.MainActivity
```

서명·업로드·제출 페이지 입력 방법은 [GitHub 제출 안내](docs/GITHUB_SUBMISSION.md)를 따르세요. `.gitignore`는 최종 이름의 Release APK를 포함하고, 로컬 도구·서명 키·Debug/Unsigned APK는 제외하도록 설정했습니다.

## 시연 순서

위 Release APK는 차량 서비스가 없는 일반 Android 환경에서도 Demo Mode로 시연할 수 있습니다.

1. 설정에서 기록 활용 및 참여자·보호자 동의를 확인합니다.
2. 홈에서 **춘천 DEMO 여정 불러오기**를 누릅니다.
3. 여행 기록에서 실제 방문한 것으로 시연할 장소를 확인합니다.
4. 기억 선택에서 아빠·아이·엄마가 각자의 장소와 감정을 선택합니다.
5. 방송 준비에서 길이를 고르고 방송을 만듭니다.
6. 플레이어에서 **방송 재생**을 눌러 선택을 공개합니다. 보관은 별도 확인 후에만 수행합니다.

공개 AOSP 에뮬레이터에는 한국어 TTS 엔진이 없을 수 있습니다. 이 경우 생성된 대본과 명시적인 음성 오류가 표시됩니다. **고정 시연 샘플 듣기 · 현재 답변 미반영**으로 별도 제작된 한국어 샘플의 실제 재생을 검증할 수 있습니다. 이를 동적 생성 방송으로 표시하지 않습니다.

개발 PC의 **RUN_BACKFM.cmd**는 `.tooling/demo-emulator.json`과 로컬 SDK·가상기기·Demo Debug APK가 준비된 환경 전용입니다. 해당 파일은 Git에 포함하지 않습니다. 다른 PC에서는 제출 Release APK를 설치하거나 아래 Android Studio 빌드 방법을 사용하세요.

## 기술 스택

- Kotlin 1.9.25, Android SDK 34, minSdk 28, Java 17, Gradle 8.9 / AGP 8.7.3.
- 네이티브 적응형 UI, ViewModel / StateFlow / Coroutines, Room 2.6.1, DataStore 1.1.1.
- 플랫폼 MediaBrowserService, MediaSession, MediaPlayer, AudioFocusRequest, 오프라인 TextToSpeech.
- `core`: Android에 의존하지 않는 여정 규칙·대본 생성·근거 검증과 JUnit 테스트.
- `pleos` 변형: 공식 Maven의 NaviHelper 3.0.5 / navi-data 3.0.4.

## 빌드 환경

Android Studio에서 이 폴더를 열고 SDK 34와 Java 17을 지정합니다. SDK 위치는 로컬 `local.properties`에 설정하며 저장소에 올리지 않습니다.

```powershell
.\gradlew.bat :core:test :app:assembleDemoDebug :app:assemblePleosDebug
.\gradlew.bat :app:lintDemoDebug
.\gradlew.bat :app:connectedDemoDebugAndroidTest
```

이번 작업 환경에는 프로젝트 전용 Java / Gradle / SDK를 `.tooling/`에 설치했습니다. Windows 한글 경로의 Java worker 문제를 우회하려면 다음을 사용합니다. 이 스크립트는 사용하지 않는 `R:` 드라이브를 임시 매핑하고 종료 시 해제합니다. 이미 사용 중인 다른 드라이브는 건드리지 않습니다.

```powershell
.\scripts\build-local.ps1
.\scripts\build-local.ps1 -Tasks @(':core:test', ':app:connectedDemoDebugAndroidTest')
```

`.tooling/`은 개발 PC의 로컬 도구이며 저장소에 포함하지 않습니다. 다른 PC에서는 Android Studio/JDK를 설치한 후 표준 Gradle wrapper 명령을 사용합니다. 첫 빌드는 Google Maven, Maven Central, Gradle 배포 서버 접근이 필요합니다. Pleos 빌드는 공식 Pleos Maven 접근도 필요합니다.

## 실제 차량과 Demo Mode

| 구분 | Demo APK | Pleos APK |
|---|---|---|
| 앱 ID | `ai.backfm.demo` | `ai.backfm` |
| 기본 모드 | 명시된 가상 춘천 여정 | 명시된 가상 춘천 여정 |
| 실제 내비 모드 | 미지원 안내 + 수동 장소 추가 | NaviHelper 연결 및 콜백 수집 |
| 주행 제한 | 일반 기기에서 명시적 시뮬레이션 | AAOS 시스템 CarUxRestrictionsManager 조회 |
| 미디어 서비스 | Android 표준 MBS / MediaSession | 동일 서비스, Pleos 호스트 검증 필요 |

설정에서 모드를 전환하면 현재 여정과 선택을 초기화합니다. 보관 방송은 유지됩니다. 실제 내비의 최근 목적지는 미확인 후보이며 자동으로 실제 방문으로 인정하지 않습니다. 앱이 관찰하지 않은 과거 이동을 복원한다고 가정하지 않습니다. 프로세스 재실행 후 여정은 복원되며 **내비 연결 / 재연결**을 눌러 필요한 이벤트 수신을 재개합니다.

실제 차량에서는 여행 기록의 **현재 내비 목적지를 귀가 목적지로 지정**을 눌러 연결합니다. ‘서울 귀가’ 같은 표시 이름과 별개로 현재 목적지의 POI 식별 정보·주소를 기기에 저장하고, 도착 주소 및 최근 경로와 비교합니다. 도착 장소명이 ‘집’ 등으로 달라도 확인할 수 있습니다. 주소 누락·목적지 변경·오래된 정보는 자동 종료에 사용하지 않습니다. 방문 기록 종료 후에는 새 방문을 저장하지 않고, 연결한 귀가 목적지의 도착/ETA 확인만 계속합니다. 연결 해제·여정 삭제·동의 철회로 중단할 수 있습니다.

0.1.1 이전 여정의 표시 이름은 유지되지만 식별 정보로 자동 변환하지 않습니다. 기존 여정은 내비를 재연결한 뒤 귀가 목적지를 지정해 주세요. 새 Demo 여정은 명시된 가상 귀가 목적지를 기본 제공합니다.

Pleos 실제 차량 모드는 공식 SDK가 요구하는 `pleos.car.permission.NAVI_ROUTE` 권한이 필요합니다. 설정의 **내비 권한 요청**으로 허용 상태를 확인하고 기록을 연결하세요. 권한이 없거나 정의되지 않은 기기에서는 조회를 수행하지 않고 Demo Mode 또는 수동 추가를 안내합니다.

AAOS에서는 Demo Mode라도 시스템 주행 제한을 무시하지 않습니다. 상태 조회 실패·연결 끊김은 입력 잠금으로 처리합니다. 복잡한 Activity를 임의로 주행 허용 앱으로 선언하지 않으며, 차량 미디어 호스트를 통한 재생 제어를 제공합니다.

## 음성, AI와 API 키

기본 생성기는 네트워크가 필요 없는 사실 기반 규칙·템플릿 방식입니다. 외부 LLM API 연결은 이번 버전에 포함하지 않았으며 설정용 가짜 키 입력 UI도 제공하지 않습니다. API 키 설정은 필요하지 않습니다. 앱은 INTERNET·마이크·위치 권한을 요청하지 않습니다.

오프라인 한국어 TTS voice만 선택해 기기 내부 WAV로 합성합니다. 없으면 기기 설정에서 한국어 음성 데이터를 설치하거나 고정 시연 샘플을 사용합니다. Pleos 자체 클라우드 TTS API를 지원한다고 가정하지 않습니다.

향후 외부 LLM을 연결할 때에는 앱에 키를 넣지 않고 서버 측 비밀 관리, 별도 전송 동의, 구조화된 응답과 사실 검증을 추가해야 합니다. 현재 `ProvenanceValidator`는 내부 템플릿의 근거 참조를 검사하며, 임의의 LLM 문장을 의미적으로 검증하는 장치가 아닙니다.

## 보관과 삭제

- Room의 여정·선택은 기기 내부에만 저장하고 다음 여정 시작 또는 삭제까지 보관합니다.
- 생성 에피소드는 기본적으로 보관 목록에 저장하지 않습니다. 음성 합성 임시 파일은 내부 캐시에 있으며 프로세스 재실행 시 정리합니다.
- 명시적 **보관**은 대본과 음성을 앱 내부에 저장합니다. 음성이 아직 없으면 대본만 보관하고 다시 들을 때 재합성합니다.
- 방문 후보를 제거하면 해당 장소를 고른 참여 선택도 삭제합니다.
- 현재 여행 삭제와 보관 방송 삭제는 별도입니다. 동의 철회는 모두 삭제하고 재생과 수집을 중단합니다.
- Android 클라우드 백업·기기 간 데이터 전송을 제외합니다. 외부 공유, 상시 녹음, 공개 클라우드 업로드는 없습니다.

## 제출용 release 서명

제출 APK는 전용 Release 키로 서명했습니다. 서명 키와 암호는 개발 PC에만 보관하며 저장소에 포함하지 않습니다. 이후 업데이트도 같은 키를 사용합니다. 재빌드할 때에는 보관한 keystore와 아래 환경변수를 로컬에서 설정합니다.

```powershell
$env:BACKFM_KEYSTORE_PATH = 'C:\private\backfm-release.jks'
$env:BACKFM_STORE_PASSWORD = '<로컬에서만 입력>'
$env:BACKFM_KEY_ALIAS = '<키 별칭>'
$env:BACKFM_KEY_PASSWORD = '<로컬에서만 입력>'
.\scripts\export-release.ps1 -TeamName '여운FM' -Flavor pleos
```

성공 시 서명을 검증한 `apk/여운FM_BACKFM_release.apk`를 만듭니다. 서명 전 Release 빌드는 제출용 APK가 아닙니다. 이 스크립트는 이 PC의 `.tooling/` 도구를 이용하며, 다른 PC에서는 Android Studio의 **Generate Signed Bundle / APK** 또는 환경에 맞는 Gradle·apksigner를 사용하세요.

## 관련 문서

- [구조와 데이터 흐름](ARCHITECTURE.md)
- [SDK·차량 정책 확인](SDK_COMPATIBILITY.md)
- [실행한 검증과 미검증 범위](TEST_REPORT.md)
- [2~3분 시연](DEMO_SCRIPT.md)
- [단계별 구현 상태](IMPLEMENTATION_STATUS.md)
- [실제 플레이어 화면](docs/screens/player.png) / [서로 다른 기억 공개 대본](docs/screens/script.png)

검증 결과: 누적 자동 테스트 **70개 통과**, 두 변형 Lint 오류 0. 이번 UI 수정은 별도 가상기기에서 Android 전체 24개와 Pleos 미지원 1개를 재검증했으며, 변경 없는 단위 테스트 45개 결과를 재사용했습니다. 자세한 내용은 [검증 보고서](TEST_REPORT.md)에 기록합니다. 저장소의 `apk/`에는 서명한 최종 제출 APK와 SHA-256 체크섬을 제공합니다. 로컬 SDK·캐시·서명 키·Debug/Unsigned APK는 Git에서 제외합니다. `dist/BACKFM_source.zip`은 로컬에서 생성하는 소스 배포본이며 저장소에는 프로젝트 소스를 직접 포함합니다.

음성 준비 중에는 **준비 완료 후 재생 취소**로 자동 시작을 취소할 수 있습니다. 준비가 완료되어도 일시정지를 유지하며 직접 재생을 누른 경우에만 시작합니다. 차량 미디어 목록과 앱 플레이어는 같은 잔여시간 제한을 적용하고, 합성 중 경과한 시간도 차감합니다. 이어 듣기·처음부터 재생할 때에도 현재 잔여시간을 다시 확인합니다.

Pleos 에뮬레이터 이미지와 실제 차량에서의 NaviHelper, 미디어 호스트, 주행 규제 검증은 별도입니다. 현재 구현을 공모전 심사나 차량 배포 승인이 완료된 앱으로 표현하지 않습니다.
