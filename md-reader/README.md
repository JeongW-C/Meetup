# MD 리더

.md / .txt 파일을 한국어 음성(휴대폰 내장 TTS)으로 읽어 주는 안드로이드 앱입니다.

## 기능

- 파일 선택기로 .md/.txt 열기, 다른 앱에서 "열기"·"공유"로 받기
- Google Drive의 파일 열기(아래 참고), Google 문서(Docs)는 텍스트로 변환해 읽기
- 마크다운 기호 제거(제목, 강조, 링크, 표, 인용, HTML), 코드블록·이미지·URL은 건너뜀
- UTF-8, UTF-16, CP949/EUC-KR 한글 텍스트 자동 인식
- 재생/일시정지/정지, 이전·다음 문단, 문단을 눌러 그 위치부터 읽기
- 읽는 문단 강조와 자동 스크롤
- 속도·음 높이·글자 크기·목소리 선택
- 화면을 꺼도 계속 읽기(알림창·잠금화면·이어폰 버튼 컨트롤)
- 파일별 마지막 위치 기억, 최근 파일 목록, 수면 타이머(15/30/60분)
- 다크 모드, Android 12+ 동적 색상

## 구조

| 모듈 | 내용 |
| --- | --- |
| `core` | 순수 Kotlin. 마크다운 → 읽기용 문단 변환, 문장 단위 분할, 인코딩 감지 (단위 테스트 포함) |
| `app` | Jetpack Compose UI, TTS 재생 포그라운드 서비스, DataStore 설정 |

최소 Android 8.0 (API 26), 대상 API 35.

## 빌드

```bash
./gradlew :core:test            # 단위 테스트 (Android SDK 불필요: ./gradlew -PcoreOnly :core:test)
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease       # 서명된 릴리스 APK
./gradlew bundleRelease         # Play 스토어용 AAB
```

Android SDK(ANDROID_HOME 또는 local.properties의 sdk.dir)와 JDK 17 이상이 필요합니다.

### 릴리스 서명

- 로컬: 저장소 루트에 `keystore.properties`와 키스토어 파일을 두면 됩니다(둘 다 .gitignore 처리됨).
- GitHub Actions: 저장소 Settings > Secrets and variables > Actions에 다음을 등록합니다.
  - `MDREADER_KEYSTORE_BASE64`: 키스토어 파일의 base64 내용
  - `MDREADER_KEYSTORE_PASSWORD`, `MDREADER_KEY_PASSWORD`: 비밀번호
  - `MDREADER_KEY_ALIAS`: `mdreader`

서명 정보가 없으면 릴리스 APK는 디버그 키로 서명됩니다. 키스토어를 잃어버리면 같은 앱으로 업데이트할 수 없으니 꼭 백업하세요.

### GitHub Actions

저장소 루트의 `.github/workflows/md-reader.yml`이 `md-reader/`가 바뀔 때마다 단위 테스트, 린트, APK·AAB 빌드, 에뮬레이터 스모크 테스트(`scripts/smoke-test.sh`)를 실행합니다. `main`에 반영되면 GitHub Releases에 `md-reader-v<버전>`으로 APK와 AAB를 올립니다.

## Google Drive 파일 읽기

휴대폰에 Google Drive 앱이 설치되어 로그인되어 있어야 합니다. 별도 로그인이나 권한 요청은 없습니다.

- **파일 열기**: 시스템 파일 선택기 왼쪽 메뉴에서 Drive를 고르면 됩니다. 이렇게 연 파일은 최근 파일에 남습니다.
- **Google Drive에서 열기**: Drive 앱의 선택기가 바로 열립니다. Drive가 이 방식의 권한을 계속 유지해 주지 않아서, 여기서 연 파일은 최근 파일에 남지 않습니다(읽던 위치는 기억합니다). Drive 앱이 없거나 Google 계정에 로그인되어 있지 않으면 시스템 파일 선택기를 엽니다.
- 휴대폰에 내려받지 않은 파일은 여는 순간 내려받으므로 인터넷이 필요합니다. 오프라인에서 읽으려면 Drive 앱에서 파일의 "오프라인으로 사용"을 켜 두세요.
- Google 문서는 Drive가 일반 텍스트로 변환해 준 내용을 읽습니다. 스프레드시트·프레젠테이션·PDF는 지원하지 않습니다.

## 설치

APK를 휴대폰으로 받아 열고, "출처를 알 수 없는 앱 설치"를 허용하면 됩니다. 한국어 음성이 없다고 나오면 설정 > 텍스트 음성 변환(TTS)에서 Google 음성 엔진의 한국어 데이터를 설치하세요.
