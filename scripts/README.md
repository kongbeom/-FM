# 개발 스크립트

`build-local.ps1`은 이번 작업에서 프로젝트의 `.tooling/`에 설치한 도구를 사용하는 Windows 전용 보조 스크립트입니다. 일반 Android Studio 설치 환경에서는 루트의 `gradlew.bat`를 사용하세요. `R:`이 다른 용도로 쓰이고 있으면 매핑하지 않고 오류로 종료합니다.

`export-release.ps1`은 제출자 keystore 환경변수를 확인하고 테스트·release 빌드·서명 검증 후 최종 이름으로 복사합니다. 비밀값을 출력하거나 파일에 기록하지 않습니다. 기본 도구 경로는 `build-local.ps1`과 같으므로 다른 개발 환경에서는 표준 Gradle release task와 Android SDK의 apksigner를 직접 사용해도 됩니다.

테스트용 에뮬레이터는 `.tooling/android-sdk`의 공개 API 34 이미지를 사용했습니다. Pleos 이미지는 사용자 프로젝트에서 별도로 설치해야 합니다. 에뮬레이터 자체를 앱 또는 소스 배포물에 포함하지 않습니다.
