#!/usr/bin/env bash
# 에뮬레이터에 APK를 설치하고 samples/ 의 파일을 하나씩 열어 화면을 캡처한다.
# 페이지 넘기기(볼륨 키)와 글자 크게(가+) 버튼도 눌러 본다.
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

tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  local bounds
  bounds=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
    | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
  if [ -z "$bounds" ]; then echo "button '$1' not found"; return 1; fi
  read -r x1 y1 x2 y2 < <(echo "$bounds" | tr -c '0-9' ' ')
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

for f in ebook-reader/samples/*; do
  name=$(basename "$f")
  base=${name%.*}
  adb push "$f" "/data/local/tmp/$name" > /dev/null
  adb shell run-as $PKG cp "/data/local/tmp/$name" "files/$name"

  adb shell am force-stop $PKG
  adb logcat -c
  adb shell am start -W -a android.intent.action.VIEW \
    -d "file:///data/user/0/$PKG/files/$name" -t text/plain -n $PKG/.MainActivity
  sleep 4
  shot "${base}_1_first-page"

  adb shell input keyevent KEYCODE_VOLUME_DOWN
  sleep 1
  shot "${base}_2_next-page"

  for _ in 1 2 3 4; do tap_text "가+"; sleep 1; done
  sleep 2
  shot "${base}_3_font-larger"

  for _ in 1 2 3 4 5 6 7 8; do tap_text "가−"; sleep 1; done
  sleep 2
  shot "${base}_4_font-smaller"

  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    adb logcat -d | grep -A 30 "FATAL EXCEPTION"
    echo "앱이 $name 에서 비정상 종료됨"
    exit 1
  fi
done

echo "스크린샷: $OUT"
