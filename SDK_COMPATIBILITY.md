# SDK 및 차량 정책 확인

확인일: 2026-11-02~03 (한국 시간). 공식 문서와 공식 Maven 바이너리·소스를 기준으로 작성했습니다. 컴파일 성공과 차량 런타임 검증은 구분합니다.

## 개발 환경

Pleos 빠른 시작의 SDK API 34 / minSdk 28 이상을 반영했습니다. Java 17, Gradle 8.9, AGP 8.7.3, Kotlin 1.9.25를 사용합니다. `.tooling/`에 개발 도구를 내려받아 두 변형을 실제 빌드했습니다. Windows 한글 경로 worker 문제는 임시 ASCII 드라이브 매핑으로 우회합니다.

공식 문서: [앱 개발 시작](https://document.pleos.ai/docs/pleos-only/getting-started), [프로젝트 및 SDK 상태](https://document.pleos.ai/docs/pleos-only/create-my-project).

후자의 문서는 NaviHelper/Vehicle State를 신청 없이 사용 가능한 상태로 안내하지만, Pleos 에뮬레이터 이미지는 계정·프로젝트에서 제공받는 배포 경로가 필요합니다. 이 환경에는 Pleos 이미지·실차가 제공되지 않아 공개 Android API 34 AOSP 이미지로 앱 공통 흐름을 검증했습니다.

## NaviHelper 실제 API 확인

저장소: `https://nexus-playground.pleos.ai/repository/maven-releases/`.

의존성 `ai.pleos.playground:NaviHelper:3.0.5`와 전이 의존성 `navi-data:3.0.4`, `navi-aidl:3.0.1`을 사용합니다. 공개 AAR의 `javap` 출력 및 navi-data 공식 sources JAR로 아래 항목을 확인했습니다. Pleos 변형이 이 API로 컴파일됩니다.

공식 AAR의 `@RequiresPermission`과 Pleos Lint 검사에서 `getDestinationInfo`, `getRecentDestinationInfo`, `getRouteStateInfo`에 `pleos.car.permission.NAVI_ROUTE`가 요구되는 것을 확인했습니다. Pleos 소스셋 manifest에만 이 권한을 선언하며, 호출 시 현재 허용 여부를 검사합니다. 실제 차량 모드 설정에서 권한을 요청할 수 있습니다. 기기에 권한이 정의되지 않았거나 허용되지 않으면 SDK 조회를 수행하지 않고 안내합니다. 배포 환경의 권한 보호 수준이나 자동 승인을 가정하지 않습니다.

| API / 콜백 | 확인한 시그니처·동작 | 앱 처리 |
|---|---|---|
| 생성 / 초기화 | `NaviHelper(Context)`, `initialize(onSuccess, onFailure)` | 성공 후 리스너 등록, 실패 안내 |
| 현재 목적지 | `getDestinationInfo(): Unit` → `onDestinationInfo(DestinationInfo)` | 방문으로 저장하지 않고 ETA 및 귀가 지정용 식별 정보에 사용 |
| 최근 목적지 | `getRecentDestinationInfo(): Unit` → `onRecentDestinationInfo(List<RecentDestinationInfo>)` | 최대 20개 미확인 보조 후보 |
| 경로 상태 | `getRouteStateInfo(): Unit` → `onRouteStateInfo(RouteStateInfo)` | 현재 목적지/ETA 다시 요청 |
| 목적지 도착 | `onDestinationArrived(DestinationArrivedInfo)` | 이름으로 방문 후보 추가; 도착 주소와 최근 경로를 지정한 귀가 목적지와 비교 |
| 경유지 도착 | `onWayPointArrived(WaypointArrivedInfo)` | `name`으로 방문 후보 추가 |
| 목적지 변경 | `onDestinationChanged()` | 이전 경로 식별 정보 비우기 및 목적지/ETA 다시 요청 |
| 경로 취소 | `onRouteCancelled()` | 이전 경로 식별 정보 비우기 |
| 오류 | `onError(NaviErrorCode)` | 민감한 payload 로깅 없이 오류 상태 표시 |
| 해제 | `removeListener(listener)`, `release()` | Flow 취소 시 해제 |

`DestinationInfo.remainTime`은 공식 소스 주석에서 **초 단위**임을 확인했습니다. `RouteStateInfo`에 존재하지 않는 잔여시간 필드를 가정하지 않았습니다. 도착 이벤트의 `duration`은 도착까지의 이동시간이며 관찰 시각과 다릅니다. 후보에는 앱의 이벤트 관찰 시각을 기록합니다. 최근 목적지는 도착 시각이 없으므로 UI에서 시각 미확인으로 구분합니다.

공식 API 문서: [개요](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/intro), [이벤트](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/event-listeners), [목적지](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations).

공식 navi-data 소스에서 DestinationInfo의 `poiId`, `poiSubId`, `poiProvider`, `address`를 확인했습니다. DestinationArrivedInfo에는 이름·주소·이동 시간·거리만 있고 POI ID가 없습니다. 존재하지 않는 도착 ID를 만들지 않고, 도착 주소와 별도로 수신한 최신 경로 식별 정보를 함께 검사합니다. SDK는 이 콜백에 조회 요청의 상관 ID도 제공하지 않으므로 경로 변경 주변의 실제 콜백 순서·주소 표현은 Pleos 환경에서 검증해야 합니다. 도로명/지번 등 서로 다른 주소 표현을 임의로 같은 장소로 추정하지 않습니다.

현재 방문 후보의 중복 방지 자체는 이름·출처·시간 또는 이벤트 키 기반입니다. 귀가 도착 판정은 이 규칙과 분리했으며 최신 후보를 조회해 판단하지 않습니다.

## 미디어 카테고리와 서비스

연결 중 `getDestinationInfo()`를 15초마다 재요청하고 수신 시점부터 경과 시간을 차감합니다. 조회 실패·같은 여정 재연결은 기존 잔여시간을 초기화하지 않습니다. 0.1.2에서 방문 기록 종료 후에도 사용자가 귀가 목적지를 연결한 경우는 도착/ETA 확인만 유지합니다. 연결 해제·여정 삭제·동의 철회·프로세스 종료 시 수신을 중단합니다. 실제 Pleos 응답 시점과 경로 변경 전달은 미검증입니다.

[Pleos Music/Audio 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide)는 에피소드형 음성을 Podcast로 분류하고 `MediaBrowserService`와 `MediaSession`을 요구합니다. 여운FM은 브랜드명과 별개로 Podcast 브라우징 트리를 제공합니다. `android:appCategory="audio"`, launchable 메타데이터 및 automotive media description을 선언했습니다.

Media3 자체의 MBS 대체 수용 여부를 추정하지 않고 API 28~34에 존재하는 플랫폼 MBS·MediaSession을 직접 사용했습니다. 시스템 play/pause/stop/seek/previous/next를 세션 callback에 연결합니다. previous는 처음부터 재생, next는 현재 에피소드 종료입니다. 저장 목록으로 들어가 직접 다음 에피소드를 선택할 수 있습니다.

표준 브라우저 트리에 Podcast 폴더를 제공하고 루트 extras에 `content_type=podcast`를 표시합니다. 이 extras 키가 Pleos 전용 등록 메타데이터라고 주장하지 않습니다. Pleos Console 하위 카테고리 설정과 미디어 센터 전용 연동은 별도 확인이 필요합니다.

[Android MediaBrowserService](https://developer.android.com/reference/android/service/media/MediaBrowserService), [Android MediaSession](https://developer.android.com/reference/android/media/session/MediaSession), [foreground 서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types).

## 주행 제한과 오디오 포커스

[Pleos 주행 규제](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide), [Pleos 오디오 포커스](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development).

공개 Android `CarUxRestrictionsManager`를 사용해 `isRequiresDistractionOptimization` 상태를 관찰합니다. 속도·기어로 UX 허용 상태를 추정하지 않습니다. 상태 조회 실패와 연결 해제는 입력 잠금입니다. 주행 중 동승자 입력 허용을 가정하지 않습니다. 일반 Android 기기에서만 명시적 시뮬레이션을 허용합니다.

미디어 용도와 speech content type으로 AudioFocusRequest를 생성합니다. transient/duck 시 일시정지, 영구 손실 시 정지 상태 유지, 안전한 focus gain 시 재개합니다. 실제 긴급·전화·내비 오디오 우선순위는 차량 시스템이 결정하므로 별도 실차/이미지 검증이 필요합니다.

[CarUxRestrictionsManager](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictionsManager), [CarUxRestrictions](https://developer.android.com/reference/android/car/drivingstate/CarUxRestrictions).

## 음성 및 미지원 범위

Android 표준 TTS의 오프라인 한국어 voice가 있으면 합성합니다. [Android TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)와 별도로 Pleos 클라우드 TTS를 호출하지 않습니다. 엔진 부재/초기화·합성 실패를 숨기지 않습니다.

한국어 고정 샘플은 프로젝트에서 직접 작성한 문장을 개발 PC의 Windows Microsoft Heami 음성으로 합성한 파일입니다. 음성 출처·고정 대본은 `docs/AUDIO_ASSETS.md`에 기록했습니다. 샘플은 앱 재생 경로 검증용이며 생성된 사용자 방송을 대신한 것으로 표시하지 않습니다.

미포함: 외부 LLM, 음성 인식, 자동 대화 수집, 동승자 전화 참여, 과거 전체 이동 복원, Vehicle Control SDK. 팀명 여운FM의 제출용 Release 서명은 완료했습니다. 실제 차량·Pleos 호스트 검증과 심사 승인은 별도입니다.
