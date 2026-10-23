# 이북리더 (Android)

`.txt` / `.md` 파일을 이북처럼 **페이지 단위**로 넘겨 보고, **글자 크기**를 바꿀 수 있는 간단한 안드로이드 앱입니다.

## 기능

- `.txt`, `.md` 파일 열기 (앱의 열기 버튼, 또는 파일 관리자에서 "이북리더로 열기")
- 마크다운은 제목·굵게·목록·인용 등 서식을 적용해서 표시 ([Markwon](https://github.com/noties/Markwon))
- 화면 크기에 맞춰 자동으로 페이지 나누기
- `가−` / `가+` 버튼으로 글자 크기 변경 (12~40sp). 바꿔도 보던 위치 유지
- 페이지 넘기기: 화면 왼쪽/오른쪽 탭, 좌우 스와이프, 볼륨 키, 하단 슬라이더
- 가운데 탭으로 상단/하단 메뉴 숨기기
- 마지막으로 읽던 파일·위치·글자 크기 기억
- 한글 txt 인코딩 자동 처리 (UTF-8 → 안 되면 CP949/EUC-KR)

## 구조

| 파일 | 역할 |
| --- | --- |
| `DocumentLoader.kt` | 파일 읽기, 인코딩 판별, 마크다운 → 서식 텍스트 변환 |
| `Paginator.kt` | `StaticLayout`으로 전체 텍스트를 줄바꿈한 뒤, 화면 높이만큼씩 줄을 묶어 페이지로 나눔 |
| `PageView.kt` | 한 페이지에 해당하는 줄만 잘라서 그리는 커스텀 뷰 |
| `MainActivity.kt` | 파일 열기, 글자 크기, 터치/키 입력, 상태 저장 |

페이지 나누기의 핵심 아이디어: 글자 크기가 바뀌면 현재 페이지 **첫 글자의 위치(문자 인덱스)**를 기억해 두고,
새 글자 크기로 다시 나눈 뒤 그 글자가 들어 있는 페이지를 찾아 보여줍니다.

## 빌드 / 설치

### APK 바로 받기
GitHub Actions(`ebook-reader APK` 워크플로)가 빌드한 `ebook-reader-apk` 아티팩트를 내려받아 폰에 설치하면 됩니다.
(설치 시 "출처를 알 수 없는 앱" 허용 필요)

## 지인에게 공유하기

1. **GitHub Release 링크 (추천)**: 앱 코드가 바뀌어 올라가면 `ebook-reader release` 워크플로가 Release 페이지에 APK를 자동으로 올립니다
   (버전 이름은 `app/build.gradle.kts`의 `versionName`).
   지인에게 `https://github.com/JeongW-C/Meetup/releases/latest` 링크를 보내면, 폰 브라우저에서 `ebook-reader.apk`를 눌러 받고 설치할 수 있습니다.
   (Actions 아티팩트는 GitHub 로그인이 필요하고 zip으로 받아져서 지인 공유에는 불편합니다.)
2. **APK 파일 직접 전달**: `ebook-reader.apk` 파일을 카카오톡·구글 드라이브·이메일로 보내도 됩니다.
3. **설치하는 쪽에서 할 일**: 받은 APK를 열면 "출처를 알 수 없는 앱 설치" 허용을 묻습니다. 카카오톡/크롬/파일 앱 등 APK를 연 앱에 허용을 켜고 설치하면 됩니다.
   Play 프로텍트가 "확인되지 않은 앱" 경고를 띄우면 "무시하고 설치"를 누르세요.
4. **업데이트**: 모든 빌드가 저장소의 `demo.keystore`로 같은 서명을 하므로, 새 APK를 받아 설치하면 기존 앱 위에 그대로 업데이트됩니다.
5. 더 많은 사람에게 배포하려면 Google Play 내부 테스트(개발자 계정 25달러)나 Firebase App Distribution을 쓰면 됩니다. 이때는 데모 키 대신 비공개 서명 키를 쓰세요.

## 에뮬레이터 자동 테스트
`ebook-reader APK` 워크플로의 `smoke-test` 작업이 에뮬레이터에서 `samples/` 의 파일을 하나씩 열고,
페이지 넘기기·글자 크기 변경 후 화면을 캡처해 `ebook-reader-screenshots` 아티팩트로 올립니다.

### 직접 빌드
Android Studio에서 `ebook-reader/` 폴더를 열고 실행하거나, 터미널에서:

```bash
cd ebook-reader
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```

요구 사항: JDK 17 이상, Android SDK (compileSdk 35). 최소 지원 버전은 Android 7.0 (API 24).

## 시험용 파일
`samples/sample.md` 를 폰에 복사해서 열어보세요.
