# 테스트 보고서

검증일: 2026-11-02~03, 한국 시간. 실제 실행 결과와 구현만 확인한 범위를 구분합니다.

## 환경

- Windows 11 x64, Java 17.0.17, Gradle 8.9, AGP 8.7.3, Kotlin 1.9.25.
- Android SDK 34 / build-tools 34.0.0.
- 공개 AOSP Android 14(API 34), x86_64 에뮬레이터, 1280×800, density 160, WHPX.
- Pleos SDK: NaviHelper 3.0.5, navi-data 3.0.4. 제공받은 Pleos 이미지나 실차는 없음.
- 공개 AOSP 이미지에는 한국어 TTS 엔진이 없음. 동적 합성 실패 표시 및 별도 한국어 고정 샘플의 실제 MediaPlayer 재생을 검증.

## 검증 결과

0.1.3의 누적 검증은 **70개 통과**입니다. 이번 수정에서는 별도 가상기기에 직접 설치해 스크롤 회귀 3개를 포함한 Demo Android 전체 24개와 Pleos 미지원 1개를 재실행했습니다. 변경되지 않은 core 42개 및 WAV 3개의 기존 통과 결과를 재사용했습니다. 두 변형 Lint는 오류 0개입니다. 최초 버전의 1280×800/1280×480 렌더링·프로세스 재시작 복원 검증 이력도 유지합니다.

| 항목 | 결과 |
|---|---|
| core JUnit | 42개 통과, 실패 0(방송/여정 18 + 재생 정책 11 + 귀가 식별 13) |
| WAV 결합 JVM 테스트 | 3개 통과, 실패 0 |
| Room codec / 최근 목적지 미확인 유지 | Android 계측 통과 |
| 주행 입력 잠금 / 비공개 핸드오프 | Android 계측 통과 |
| 실제 버튼 → 저장 입력 → 방송 생성 | Espresso 계측 통과 |
| MBS Podcast 폴더 탐색 / MediaSession pause·play | 실제 Android 미디어 서비스 테스트 통과 |
| 실제 AudioManager transient/duck → pause → gain → resume | 계측 통과 |
| 실제 영구 포커스 손실 → 자동 재개 없음 | 계측 통과 |
| 가로 UI 실측 렌더링 | 합성 DEMO fixture로 1280×800 및 1280×480 실제 View PNG 렌더링과 보관함·플레이어 이동 통과 |
| 가족 3인 전체 흐름 | Espresso에서 참여·생성·공개·TTS 오류·고정 샘플·백그라운드·일시정지/재개·내비 우선·도착 정지·보관 재청취·삭제·동의 철회 통과 |
| 실제 Pleos 서비스 부재 시 초기화 실패 | Pleos 변형에서 실제 SDK 연결이 성립하지 않아 10초 watchdog이 연결 시간 초과를 안내, 계측 1개 통과 |
| Demo/Pleos Debug 빌드 | 빌드 성공 |
| Release 빌드 | 여운FM 전용 키로 서명한 Pleos Release 0.1.3 생성, apksigner 검증 및 설치·독립 실행 성공 |
| 최종 제출 APK 계측 | Release 변형과 같은 키로 서명한 Release 테스트 APK로 25개 통과, 실패 0 |
| Lint | Demo/Pleos 각각 오류 0, 경고 13(기존 라이브러리 버전 알림 및 의도된 exported MBS) |

## 최종 제출 APK 검증

2026-11-03에 `apk/여운FM_BACKFM_release.apk`를 최종 빌드했습니다. 앱 ID는 `ai.backfm`, 버전은 0.1.3(versionCode 4), minSdk 28, targetSdk 34이며 debuggable 플래그가 없습니다. APK Signature Scheme v2와 RSA 4096 전용 서명을 확인했습니다. 서명 키와 암호는 저장소에서 제외합니다.

사용자 가상기기와 분리된 공개 AOSP API 34 시험 기기에 이 파일을 설치하고, 같은 Release 변형으로 컴파일·서명한 계측 APK로 전체 **25개 통과**를 확인했습니다(92.039초). 여정·귀가 식별·가족 입력·방송 생성·재생 상태·미디어 제어·스크롤·Pleos 서비스 부재를 포함합니다. 테스트 종료 후 독립 실행도 `Status: ok`로 확인했습니다. 이 25개는 기존 케이스의 Release 재검증이므로 누적 고유 테스트 70개에 중복 가산하지 않습니다. 실제 Pleos 차량 검증을 뜻하지 않습니다.

## 요구 시나리오 추적

| 번호 | 시나리오 | 검증 방법 / 한계 |
|---|---|---|
| 1 | 3명 각기 다른 장소·감정 | threePeopleChooseThreeDifferentPlacesAndEmotions + Android 3인 가족 입력(2곳 사용) |
| 2 | 같은 장소·다른 감정 | familyRevealsDifferentMemories + 실제 가족 UI |
| 3 | 다른 장소·같은 감정 | differentPlacesSameEmotion |
| 4 | 단독 참여 | soloWorks + 생성 버튼 단독 fixture |
| 5 | 한 장소만 기록 | 단독 fixture / 같은 장소 비교 |
| 6 | 짧은 귀가 | durationFitsRide / tooShortRideDeferred / highlightIsShorterAndRetainsEveryParticipant |
| 7 | 생성·재생·저장·재청취 | 가족 E2E; TTS 없는 이미지에서는 고정 샘플을 명시적으로 별도 재생 |
| 8 | 내비 데이터 없음 | 수동 장소 추가 및 Demo adapter 구현; 실제 Navi 서비스 부재 검증 분리 |
| 9 | 방문 안 한 최근 목적지 | Android codec 미확인 유지 + unverifiedDestinationNeverNarrated |
| 10 | 중복 도착 | duplicateArrivalIgnored / timeWindowDeduplicatesButAllowsRevisit |
| 11 | SDK 초기화 실패 | Pleos APK를 공개 Android 이미지에서 검증; 실제 차량의 초기화 실패와 구분 |
| 12 | AI 서버 실패 | 외부 LLM 미구현으로 해당 없음. fallback은 독립 로컬 생성기 |
| 13 | 인터넷 없음 | 앱에 INTERNET 권한·네트워크 호출 없음. 외부 서버 없는 구조로 생성 |
| 14 | 음성 합성 실패 | 실제 AOSP의 한국어 TTS 부재 시 오류, 생성 대본 유지 |
| 15 | 무응답 생성 | noPicksRejected + UI 생성 버튼 비활성 |
| 16 | 내비 안내 우선 | 실제 AudioManager 경쟁 포커스 테스트 + 명시적 DEMO 안내 시뮬레이션; 실차 내비 조합은 미검증 |
| 17 | 주행 중 입력 | 공개 Android에서 시뮬레이션 계측. 실제 AAOS 시스템 상태 전환은 미검증 |
| 18 | 재실행 복원 | Room round trip / Activity 재생성 계측, ADB force-stop 후 신규 프로세스에서 2곳·3명 및 보관 에피소드가 남는 것까지 수동 확인 |
| 19 | 여행·방송 삭제 | 가족 E2E와 데이터 관리 구현. 포렌식 보안 삭제를 뜻하지 않음 |
| 20 | 동의 철회 | 가족 E2E의 전체 records 삭제 및 서비스 중단 |

## 0.1.1 재생 회귀 검사

추가한 Android 계측 8개는 실제 MediaPlayer와 MediaSession을 사용합니다. 준비 단계는 주입한 테스트 전용 AudioRenderer로 지연시키고 미리 제작한 고정 오디오 파일을 반환하도록 제어했습니다. 이 방법은 제어·타이밍 검증이며 실제 한국어 TTS 엔진의 합성 성공 검증이 아닙니다.

- 합성 중 일시정지 → 준비 완료 후에도 일시정지 → 명시적 재생.
- 화면의 준비 완료 후 재생 취소 버튼과 MediaSession의 일시정지 상태 반영.
- 준비 중 일시정지 후 다시 재생 → 마지막 의도로 시작.
- 차량 보관함에서 준비 중 종료 → 늦은 완료가 재생을 되살리지 않음.
- 이전 준비가 늦게 완료되어도 새 에피소드를 교체하지 않음.
- 준비 중 경과 시간이 빠져 실제 오디오가 더 길어지면 재생 차단.
- 차량 보관함 진입이 기존 잔여시간 제한을 지우지 않음.
- 최신 ETA가 짧아지면 재개 차단; 남은 음성은 허용하되 전체 다시 듣기는 별도 재검사.

추가한 core 11개는 요청 무효화, 일시정지 의도, 단조 시계에 따른 경과 시간, 최신 ETA 교체, 만료된 시간의 0 유지, 다른 여정의 지연 이벤트, 미확인 ETA, 갱신 실패·재연결 시 기존 카운트다운 보존을 검사합니다.

## 0.1.2 귀가 도착 회귀 검사

Core 13개는 이름이 달라도 같은 목적지인 경우, 같은 이름의 다른 주소, 같은 주소의 다른 POI, provider/subId 충돌, 귀가 미연결, 도착 주소 누락, 오래된 경로, Unicode/공백 정규화와 번지 구분, 주소 기반 지정, 경로 변경 시 캐시 비우기, 도착 증거의 불변 스냅샷을 검증했습니다.

Android 6개는 테스트 전용 JourneyProvider로 이벤트를 주입하고 실제 ViewModel·Room·MediaPlayer를 사용했습니다.

- ‘서울 귀가’라는 별칭과 다른 도착 이름이어도 식별 정보·주소가 맞으면 실제 재생 종료.
- 가장 최근 방문 후보가 관련 없는 장소여도 도착 이벤트 자체의 근거로 판정.
- 같은 이름의 다른 목적지는 재생 유지.
- 경로가 없거나 오래되었으면 캐시 ID를 빌려 자동 종료하지 않음.
- 방문 기록 종료 후에도 연결한 귀가 목적지의 도착 확인은 유지하며 새 방문은 저장하지 않음.
- 기록 종료 후 귀가 목적지를 연결해도 확인 전용 수신을 재개.
- 화면에서 목적지 지정, Room 식별 정보 복원, 이전 JSON 필드 누락 호환성, 별칭 수정 및 연결 해제.

이는 실제 SDK 콜백 전달/순서 검증이 아닙니다. 도착 콜백에 POI ID가 없다는 공식 SDK 제약을 반영해, 테스트에서 도착 주소와 최근 경로를 별도로 전달했습니다. 실차의 주소 별칭·도로명/지번 차이·경로 변경 중 콜백 순서는 여전히 미검증입니다.

## 0.1.3 스크롤 회귀 검사

별도 데이터 디스크의 BACKFM_SCROLL_QA 가상기기에서 검증했습니다. 사용자가 실행한 가상기기의 데이터에는 테스트를 수행하지 않았습니다.

- 화면을 아래로 스크롤한 뒤 감정을 선택해도 본문 위치가 유지됨.
- 비동기 메시지 처리로 화면이 연속 갱신돼도 위치가 유지됨.
- 방송 길이 선택과 방송 생성의 busy/완료 갱신 후 위치가 유지됨.
- ‘저장된 기억으로 방송 만들기’ 문구가 적용되고 기존 문구가 사라짐.
- 참여 완료·다음 참여자 전환 시 본문을 위에서 시작하며 이전 답변을 표시하지 않음.

## 사실성 검사

확인되지 않은 호텔 후보가 대본에 포함되지 않는지, 다른 여정의 선택을 거부하는지, 근거 없는 ID를 거부하는지, 제공하지 않은 사진 촬영 사건을 만들지 않는지, 직접 작성한 메모를 그대로 인용하는지 검증했습니다. 같은 장소·같은 감정에 억지 차이를 만들지 않습니다.

ProvenanceValidator는 내부 생성 템플릿의 참조를 검사합니다. 임의의 외부 AI 문장에 대한 의미 기반 진실 판별을 검증한 것은 아닙니다.

## 미검증

- 제공받은 Pleos 이미지·실차의 실제 도착/경유지/목적지 변경 이벤트와 ETA 갱신 빈도.
- Pleos 미디어 호스트 권한 및 Console Podcast 카테고리 등록, 물리 스티어링 휠·Tune Knob.
- 실제 AAOS 시스템 주행 제한 변경 및 전화·긴급·내비 앱 조합의 포커스 정책.
- 한국어 오프라인 TTS가 설치된 Android 기기의 동적 합성 성공·voice별 길이·자연스러움.
- 인간 참여자의 30초 이내 선택 비율·공모전 사용자 평가·차량 배포 심사.

## 재현

```powershell
.\scripts\build-local.ps1 -Tasks @(':core:test', ':app:testDemoDebugUnitTest', ':app:connectedDemoDebugAndroidTest')
.\scripts\build-local.ps1 -Tasks @(':app:connectedPleosDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=ai.backfm.NaviAvailabilityTest')
```

NaviAvailabilityTest는 Pleos 내비 서비스가 **없는** 공개 Android 이미지 전용입니다. 실제 Pleos 이미지에서 실행할 때에는 내비 연결 성공을 기대하는 별도 테스트를 사용해야 합니다.

서명 환경변수를 설정한 뒤 Release 계측 APK를 빌드하려면 다음을 사용합니다. `backfmTestBuildType`의 기본값은 `debug`이며, Release 검증에는 `release`로 지정합니다. 앱과 계측 APK의 서명 키가 같아야 합니다. Debug용 계측 APK를 Release 앱에 재사용하면 Kotlin 내부 함수 이름과 리소스가 달라 검증할 수 없습니다.

```powershell
.\gradlew.bat -PbackfmTestBuildType=release :app:assemblePleosRelease :app:assemblePleosReleaseAndroidTest
```

중간 테스트에서 스크롤 후 UiAutomator 터치 좌표, 홈으로 보낸 Activity의 복귀 방식, 재생 상태에 따른 버튼 갱신 문제가 드러났습니다. View에 기반한 Espresso 입력과 실제 Activity 시작을 사용하고, 플레이어 버튼 갱신과 단일 Activity 복귀를 보완했습니다. 최종 수치에는 수정 후 실행만 반영합니다.
