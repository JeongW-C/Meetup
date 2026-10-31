#!/usr/bin/env bash
# 에뮬레이터에 APK를 설치하고 samples/ 의 파일을 하나씩 열어 화면을 캡처한다.
# 페이지 넘기기(볼륨 키)와 글자 크기 변경(키보드 +/-)도 해 본다.
# 사용법: scripts/smoke-test.sh <apk> <결과 폴더>
set -euo pipefail

APK=$1
OUT=$2
PKG=com.example.ebookreader
mkdir -p "$OUT"

adb install -r "$APK"
adb shell run-as $PKG mkdir -p files

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
}

# 화면 하단 페이지 표시("3 / 12")를 읽어 로그에 남긴다
page_info() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | grep -o 'text="[0-9]* / [0-9]*"' | head -1 || true
}

for f in ebook-reader/samples/*; do
  name=$(basename "$f")
  base=${name%.*}
  adb push "$f" "/data/local/tmp/$name" > /dev/null
  adb shell run-as $PKG cp "/data/local/tmp/$name" "files/$name"

  adb shell am force-stop $PKG
  # 앞 파일에서 바꾼 글자 크기가 남지 않도록 설정 초기화
  adb shell run-as $PKG rm -f shared_prefs/reader.xml
  adb logcat -c
  adb shell am start -W -a android.intent.action.VIEW \
    -d "file:///data/user/0/$PKG/files/$name" -t text/plain -n $PKG/.MainActivity
  sleep 4
  shot "${base}_1_first-page"
  info=$(page_info)
  echo "[$name] 첫 화면: $info"
  if [ -z "$info" ]; then
    echo "$name 파일이 열리지 않음"
    exit 1
  fi

  adb shell input keyevent KEYCODE_VOLUME_DOWN
  sleep 1
  shot "${base}_2_next-page"
  echo "[$name] 다음 페이지: $(page_info)"

  for _ in 1 2 3 4; do adb shell input keyevent KEYCODE_PLUS; sleep 1; done
  sleep 2
  shot "${base}_3_font-larger"
  echo "[$name] 글자 크게: $(page_info)"

  for _ in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_MINUS; sleep 1; done
  sleep 2
  shot "${base}_4_font-smaller"
  echo "[$name] 글자 작게: $(page_info)"

  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    adb logcat -d | grep -A 30 "FATAL EXCEPTION"
    echo "앱이 $name 에서 비정상 종료됨"
    exit 1
  fi
done

# 구글 드라이브 등에서 "보내기/공유"로 넘겨받는 경우(SEND)도 열리는지 확인
name=$(basename "$(ls ebook-reader/samples/* | head -1)")
adb shell am force-stop $PKG
adb shell run-as $PKG rm -f shared_prefs/reader.xml
adb logcat -c
adb shell am start -W -a android.intent.action.SEND -t text/plain \
  --eu android.intent.extra.STREAM "file:///data/user/0/$PKG/files/$name" -n $PKG/.MainActivity
sleep 4
shot "share_${name%.*}"
info=$(page_info)
echo "[공유로 받은 $name] 첫 화면: $info"
if [ -z "$info" ]; then
  echo "공유로 받은 파일이 열리지 않음"
  exit 1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d | grep -A 30 "FATAL EXCEPTION"
  echo "앱이 공유 받기에서 비정상 종료됨"
  exit 1
fi

echo "스크린샷: $OUT"
