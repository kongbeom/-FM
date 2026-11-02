# BACKFM GitHub 제출 안내

팀명은 **여운FM**, 앱 이름은 **BACKFM**, 버전은 **0.1.3**입니다. 공개 저장소는 [kongbeom/-FM](https://github.com/kongbeom/-FM)이며, 제출 APK는 [apk/여운FM_BACKFM_release.apk](../apk/여운FM_BACKFM_release.apk)입니다. APK는 `pleosRelease` 변형을 전용 Release 키로 서명했습니다. 아래 절차는 재빌드·재업로드할 때 참고할 수 있습니다.

## 1. 제출용 Release APK 만들기

차량용 제출본은 `pleosRelease` 변형을 사용합니다. 기존 `BACKFM_pleos_debug.apk`나 `unsigned/` 파일의 이름만 바꾸면 제출 조건을 만족하지 않습니다.

Android Studio에서 다음과 같이 만듭니다.

1. 프로젝트 루트 폴더를 열고 JDK 17, Android SDK 34로 Gradle 동기화를 완료합니다.
2. **Build → Generate Signed Bundle / APK → APK**를 선택합니다.
3. 제출자 소유 keystore를 선택합니다. 처음이라면 **Create new**로 새 키를 만들고, 프로젝트 폴더 밖에 보관합니다. 비밀번호는 이 화면에서 직접 입력하고 키와 함께 별도로 백업합니다.
4. 모듈은 `app`, 변형은 `pleosRelease`를 선택해 빌드합니다. 빌드 결과의 **Locate**로 실제 서명된 APK를 확인합니다.
5. 서명된 파일을 프로젝트 `apk/` 폴더에 복사하고 `여운FM_BACKFM_release.apk`로 이름을 바꿉니다.
6. 새 시험 기기에 설치해 실행, 여정 불러오기, 기억 선택, 방송 생성과 재생을 확인합니다. 동일 앱 ID의 Debug 버전이 있는 기기는 서명이 달라 덮어쓰기가 실패할 수 있으므로 별도 시험 기기를 사용합니다.

이 PC의 로컬 도구를 이용할 때에는 루트 README의 `BACKFM_*` 환경변수를 설정한 뒤 다음 스크립트로 빌드·서명 검증·이름 지정을 할 수 있습니다. 비밀번호나 keystore를 채팅이나 GitHub에 올리지 마세요.

```powershell
.\scripts\export-release.ps1 -TeamName '여운FM' -Flavor pleos
```

이후 업데이트도 현재 보관한 서명 키를 사용합니다. 키와 암호는 저장소에 포함하지 않습니다.

## 2. GitHub 저장소에 업로드

### 웹사이트에서 업로드하는 방법

1. [GitHub 새 저장소](https://github.com/new)에서 저장소 이름을 `BACKFM` 등으로 정하고 공개 또는 비공개를 선택합니다.
2. 이 프로젝트의 `dist/BACKFM_source.zip`을 별도 폴더에 압축 해제합니다. 이 ZIP에는 로컬 SDK·캐시·서명 키·APK가 제외돼 있습니다.
3. 압축을 푼 폴더의 `apk/`에 1단계에서 만든 최종 APK를 추가합니다. README도 실제 제출 파일명과 상태로 갱신합니다.
4. GitHub 저장소의 **Add file → Upload files** 또는 초기 화면의 **uploading an existing file**을 선택합니다.
5. 압축 해제한 폴더 **안의 파일과 폴더**를 올립니다. 저장소 최상위에 `README.md`, `app/`, `core/`, `gradle/`, `apk/`가 보이도록 합니다. ZIP 파일 하나만 올리지 않습니다. 숨김 파일인 `.gitignore`도 포함합니다.
6. **Commit changes**를 누릅니다. 파일이 100개를 넘으면 나눠 올립니다.
7. 저장소에서 APK를 열어 실제 다운로드되는지, 소스 폴더와 README가 보이는지 확인합니다.

현재 Pleos Release APK는 서명 전 약 9.3MB이므로 GitHub 웹 업로드의 파일당 25MiB 한도 안에 들어갑니다. 최종 서명 후 크기도 확인하세요. 한도를 넘으면 Git으로 업로드하거나, 공지대로 GitHub Release에 최종 APK를 첨부하고 해당 Release 페이지 주소를 제출합니다. 웹사이트에서 작업 폴더 전체를 직접 끌어오면 `.gitignore`의 제외 규칙이 적용되지 않으므로 위의 소스 ZIP을 사용합니다.

### Git 명령으로 업로드하는 방법

새 **빈** 원격 저장소를 만든 경우, 프로젝트 루트의 PowerShell에서 실행합니다. GitHub에서 저장소를 만들 때 README, .gitignore, license 자동 생성은 선택하지 않습니다. 이미 코드가 있는 기존 저장소는 해당 저장소를 먼저 clone한 후 변경을 합칩니다.

```powershell
git init -b main
git add .
git status --short
git diff --cached --stat
git commit -m "Submit BACKFM 0.1.3 source and signed release APK"
git remote add origin https://github.com/<GitHub계정>/BACKFM.git
git push -u origin main
```

`git status`에서 `apk/여운FM_BACKFM_release.apk`가 포함되고 `.tooling/`, `local.properties`, 키 파일이 제외됐는지 확인합니다. 최초 commit에서 사용자 정보 설정을 요구하면 본인 GitHub 사용자명과 GitHub 계정 이메일 또는 GitHub의 비공개 noreply 이메일을 설정합니다. push 시 GitHub 인증을 완료합니다. 키 파일이나 비밀번호를 인증 수단으로 저장소에 커밋하지 않습니다.

## 3. 비공개 저장소의 심사 접근

비공개 저장소를 제출한다면 저장소 **Settings → Collaborators → Add people**에서 공지된 심사용 GitHub 계정을 초대합니다. 공지의 정확한 계정을 확인한 뒤 초대하고, 마감 전에 초대 수락 및 저장소 접근 가능 여부를 확인합니다. 개인 계정의 비공개 저장소는 공지대로 쓰기 권한도 부여된다는 점을 확인합니다.

## 4. 제출 페이지에 입력

| 항목 | 입력 예시 |
|---|---|
| 저장소 주소 | `https://github.com/kongbeom/-FM` |
| APK 파일 경로 | `apk/여운FM_BACKFM_release.apk` |
| APK URL을 요구하는 경우 | `https://github.com/kongbeom/-FM/blob/main/apk/여운FM_BACKFM_release.apk` |
| Release로 제출하는 경우 | 실제 생성한 GitHub Release 페이지 주소 |

비공개 저장소의 APK 링크도 심사 계정의 접근 권한이 있어야 열립니다. 제출 페이지의 입력 형식과 공지된 심사 계정은 주최 측 안내를 따릅니다.

## 공식 참고 문서

- [Android APK 서명과 Android Studio 서명 절차](https://developer.android.com/studio/publish/app-signing)
- [GitHub 파일 업로드와 용량 제한](https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository)
- [로컬 프로젝트를 GitHub에 올리기](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)
