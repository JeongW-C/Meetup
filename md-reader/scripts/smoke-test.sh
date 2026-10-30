#!/usr/bin/env bash
# 에뮬레이터에 APK를 설치하고 samples/ 의 파일을 열어 읽기(재생)를 눌러 본다.
# 화면을 캡처하고, 재생 버튼이 일시정지로 바뀌는지와 앱이 죽지 않는지 확인한다.
# 사용법: scripts/smoke-test.sh <apk> <결과 폴더>
set -euo pipefail

APK=$1
OUT=$2
PKG=com.mdreader
mkdir -p "$OUT"

adb install -r "$APK"
echo "설치된 TTS 엔진:"
adb shell cmd package query-services -a android.intent.action.TTS_SERVICE | grep -E "packageName|name=" || true
echo "기본 엔진: $(adb shell settings get secure tts_default_synth)"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
}

ui() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null || true
}

# content-desc 가 $1 인 화면 요소의 가운데를 누른다
tap_desc() {
  local bounds
  bounds=$(ui | grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
    | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' || true)
  if [ -z "$bounds" ]; then
    echo "'$1' 버튼을 찾지 못함"
    return 1
  fi
  read -r x1 y1 x2 y2 < <(echo "$bounds" | sed 's/\]\[/,/; s/[][]//g; s/,/ /g')
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# text 가 $1 인 화면 요소의 가운데를 누른다
tap_text() {
  local bounds
  bounds=$(ui | grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" \
    | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' || true)
  if [ -z "$bounds" ]; then
    echo "'$1' 글자를 찾지 못함"
    return 1
  fi
  read -r x1 y1 x2 y2 < <(echo "$bounds" | sed 's/\]\[/,/; s/[][]//g; s/,/ /g')
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

fail=0

# "Google Drive에서 열기": 파일 선택기가 열리거나, (에뮬레이터처럼 Drive에 계정이 없어) Drive가 바로 닫히면
# 안내 문구가 나와야 한다
adb logcat -c
adb shell am start -W -n $PKG/.MainActivity
sleep 3
if tap_text "Google Drive에서 열기"; then
  sleep 2
  # 안내 문구(스낵바)는 몇 초 뒤 사라지므로 화면 내용을 먼저 받아 둔다
  screen=$(ui)
  shot "drive_1_picker"
  current=$(adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" || true)
  echo "Drive 버튼 후 화면: $current"
  if echo "$current" | grep -qE "documentsui|com.google.android.apps.docs"; then
    echo "Drive 버튼: 파일 선택기 열림"
  elif echo "$screen" | grep -q "Drive에서 고른 파일이 없습니다"; then
    echo "Drive 버튼: Drive가 바로 닫혀 안내 문구 표시"
  else
    echo "Drive 버튼: 파일 선택기가 열리지 않음"
    fail=1
  fi
  adb shell input keyevent KEYCODE_BACK
  sleep 2
else
  fail=1
fi
if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d | grep -A 30 "FATAL EXCEPTION"
  echo "앱이 Drive 버튼에서 비정상 종료됨"
  exit 1
fi

for f in md-reader/samples/*; do
  name=$(basename "$f")
  base=${name%.*}
  adb shell am force-stop $PKG
  adb shell run-as $PKG mkdir -p files
  adb push "$f" "/data/local/tmp/$name" > /dev/null
  adb shell run-as $PKG cp "/data/local/tmp/$name" "files/$name"
  adb logcat -c

  adb shell am start -W -a android.intent.action.VIEW \
    -d "file:///data/user/0/$PKG/files/$name" -t text/plain -n $PKG/.MainActivity
  sleep 5
  shot "${base}_1_opened"
  echo "[$name] 화면 글자:"
  screen=$(ui)
  echo "$screen" | grep -o 'text="[^"]\+"' | head -20
  # PDF(Drive가 넘겨주는 Google 문서 형식)는 글자를 뽑아 문단으로 보여 줘야 한다
  if [ "${name##*.}" = "pdf" ]; then
    if echo "$screen" | grep -q "오래된 문을 천천히"; then
      echo "[$name] PDF 글자 추출 확인"
    else
      echo "[$name] PDF에서 뽑은 글자가 화면에 없음"
      fail=1
    fi
  fi

  if tap_desc "재생"; then
    sleep 3
    shot "${base}_2_playing"
    if ui | grep -q 'content-desc="일시정지"'; then
      echo "[$name] 재생 중 표시 확인"
    else
      echo "[$name] 일시정지 버튼이 보이지 않음 (짧은 문서는 이미 다 읽었을 수 있음)"
    fi
    sleep 5
    # 서비스가 TTS 엔진에 문장을 넘겼는지(speak 결과 0 = 성공) 로그로 확인한다
    if adb logcat -d -s MdReader:I | grep -q "speak index=0 result=0"; then
      echo "[$name] 음성 합성 요청 성공"
    else
      echo "[$name] 음성 합성 요청 로그가 없음"
      fail=1
    fi
    echo "[$name] 앱 로그:"
    adb logcat -d -s MdReader:V TextToSpeech:V TtsEngines:V AndroidRuntime:E | tail -30 || true
    tap_desc "일시정지" || true
    sleep 2
    shot "${base}_3_paused"
  else
    fail=1
  fi

  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    adb logcat -d | grep -A 30 "FATAL EXCEPTION"
    echo "앱이 $name 에서 비정상 종료됨"
    exit 1
  fi
done

echo "스크린샷: $OUT"
exit $fail
