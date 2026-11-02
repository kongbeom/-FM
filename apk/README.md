# APK 산출물

- [여운FM_BACKFM_release.apk](여운FM_BACKFM_release.apk): 여운FM 팀의 최종 제출 APK. Pleos SDK를 포함하는 `pleosRelease`, 버전 0.1.3(versionCode 4), 전용 Release 키로 서명했습니다.
- [SHA256SUMS.txt](SHA256SUMS.txt): 최종 제출 APK의 SHA-256 체크섬.

Android 9(API 28) 이상에서 설치할 수 있습니다. 일반 Android에서는 Demo Mode로 시연하고, 실제 차량 내비 기능은 Pleos 지원 환경에서 권한을 허용한 뒤 연결합니다. 실행 방법과 필요한 설정은 [루트 README](../README.md)를 참고하세요.

2026-11-03에 서명 검증, AOSP API 34 별도 가상기기 설치·독립 실행, 최종 Release 대상 계측 테스트 25개 통과를 확인했습니다. 실제 Pleos 차량 검증은 별도입니다.

Debug 및 서명 전 Release 파일은 개발 PC에서만 보관하고 이 저장소에는 올리지 않습니다. 재빌드 시에는 README의 환경변수를 설정한 뒤 `scripts/export-release.ps1 -TeamName '여운FM' -Flavor pleos`를 실행합니다.

제출 절차는 [GitHub 제출 안내](../docs/GITHUB_SUBMISSION.md)를 참고하세요.
