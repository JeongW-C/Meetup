#!/usr/bin/env bash
# 에뮬레이터에 APK를 설치하고 samples/ 의 이미지를 열어 스크롤해 보며 화면을 캡처한다.
# - 여러 장(ep_10, ep_1, ep_2 순서로 넘김)을 이름 순서(1 → 2 → 10)로 이어 붙이는지
# - 손가락 스크롤·볼륨 키 스크롤, 탭으로 메뉴 숨기기/보이기
# - 앱을 껐다 켜도 보던 위치가 이어지는지, 가로 화면으로 돌려도 죽지 않는지
# - 세로 20000px 짜리 이미지 한 장을 열 수 있는지
# 사용법: scripts/smoke-test.sh <apk> <결과 폴더>
set -euo pipefail

APK=$1
OUT=$2
PKG=com.example.webtoonviewer
FILES=/data/user/0/$PKG/files
mkdir -p "$OUT"

adb install -r "$APK"
adb shell run-as $PKG mkdir -p files
for f in webtoon-viewer/samples/*; do
  name=$(basename "$f")
  adb push "$f" "/data/local/tmp/$name" > /dev/null
  adb shell run-as $PKG cp "/data/local/tmp/$name" "files/$name"
done

shot() {
  adb exec-out screencap -p > "$OUT/$1.png"
}

# 화면 아래 위치 표시("37%  ·  2 / 3")를 읽는다
position() {
  adb exec-out uiautomator dump /dev/tty 2>/dev/null \
    | grep -o 'text="[0-9]*%[^"]*/ [0-9]*"' | head -1 | sed 's/text="//; s/"$//' || true
}

percent() { echo "$1" | grep -o '^[0-9]*' || echo 0; }

check_crash() {
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then
    adb logcat -d | grep -A 30 "FATAL EXCEPTION"
    echo "앱이 비정상 종료됨: $1"
    exit 1
  fi
}

fail() { echo "실패: $*"; exit 1; }

size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${size%x*}
H=${size#*x}
echo "화면 크기: ${W}x${H}"

# 부팅 직후 화면 설정이 바뀌며 앱이 다시 만들어지는 일이 없도록 잠시 기다린다
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
sleep 10

# 1) 여러 장을 일부러 뒤섞인 순서로 넘긴다
adb logcat -c
adb shell am start -W -a android.intent.action.SEND_MULTIPLE -t "image/*" \
  --esal android.intent.extra.STREAM "file://$FILES/ep_10.png,file://$FILES/ep_1.png,file://$FILES/ep_2.png" \
  -n $PKG/.MainActivity
sleep 5
shot 1_opened
pos=$(position)
echo "[여러 장 열기] $pos"
[ -n "$pos" ] || fail "이미지가 열리지 않음"
echo "$pos" | grep -q '/ 3$' || fail "3장이 아님: $pos"
echo "$pos" | grep -q ' 1 / 3$' || fail "첫 장부터 시작하지 않음: $pos"
adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q 'text="이미지 3장"' \
  || fail "제목이 '이미지 3장' 이 아님"
adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -q 'text="ep_1.png"' \
  || fail "이름 순서로 정렬되지 않음 (첫 장이 ep_1.png 여야 함)"
check_crash "여러 장 열기"

# 2) 손가락으로 위로 밀어 스크롤 → 메뉴가 숨겨진다
for _ in 1 2 3 4 5 6; do
  adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 300
  sleep 1
done
sleep 2
shot 2_scrolled
[ -z "$(position)" ] || fail "스크롤해도 메뉴가 숨겨지지 않음"

# 3) 볼륨 키로 한 화면씩 내려가기
for _ in 1 2 3 4 5 6 7 8 9 10; do
  adb shell input keyevent KEYCODE_VOLUME_DOWN
  sleep 1
done
sleep 2
shot 3_volume-keys

# 4) 가운데 탭 → 메뉴가 다시 보이고 위치가 0% 보다 크다
adb shell input tap $((W / 2)) $((H / 2))
sleep 2
shot 4_menu
pos=$(position)
echo "[스크롤 후] $pos"
[ -n "$pos" ] || fail "탭해도 메뉴가 나타나지 않음"
p1=$(percent "$pos")
p1pos=$pos
[ "$p1" -gt 0 ] || fail "스크롤이 되지 않음: $pos"
check_crash "스크롤"

# 5) 앱을 껐다 켜면 같은 위치에서 이어 본다
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.MainActivity
sleep 5
shot 5_resumed
pos=$(position)
echo "[다시 켜기] $pos"
p2=$(percent "$pos")
[ $((p2 - p1)) -le 2 ] && [ $((p1 - p2)) -le 2 ] || fail "위치가 이어지지 않음: $p1% → $p2%"
check_crash "다시 켜기"

# 6) 가로 화면으로 돌려도 폭에 맞춰 다시 그리고 위치를 유지한다
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 5
shot 6_landscape
pos=$(position)
echo "[가로 화면] $pos"
[ -n "$pos" ] || fail "가로 화면에서 다시 열리지 않음"
p3=$(percent "$pos")
# 화면 높이가 달라지므로 %는 조금 달라질 수 있다. 보던 장이 같고 처음으로 돌아가지 않았는지만 본다
echo "$pos" | grep -q "$(echo "$p1pos" | grep -o '[0-9]* / [0-9]*$')$" || fail "가로 화면에서 보던 장이 바뀜: $p1pos → $pos"
[ $((p1 - p3)) -le 15 ] || fail "가로 화면에서 위치가 바뀜: $p1% → $p3%"
adb shell input keyevent KEYCODE_VOLUME_DOWN
sleep 2
shot 6_landscape-scrolled
adb shell settings put system user_rotation 0
sleep 3
check_crash "가로 화면"

# 7) 세로 20000px 이미지 한 장 (갤러리의 "다음으로 열기")
adb logcat -c
adb shell am start -W -a android.intent.action.VIEW -d "file://$FILES/long-20000px.jpg" -t image/jpeg \
  -n $PKG/.MainActivity
sleep 5
shot 7_long-top
pos=$(position)
echo "[긴 이미지] $pos"
echo "$pos" | grep -q ' 1 / 1$' || fail "긴 이미지가 열리지 않음: $pos"
# 아래 슬라이더의 오른쪽 끝을 눌러 맨 끝으로 이동
bounds=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null \
  | grep -o 'resource-id="com.example.webtoonviewer:id/seekBar"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"')
echo "슬라이더 위치: $bounds"
read -r sx1 sy1 sx2 sy2 <<< "$(echo "$bounds" | grep -o '[0-9]*' | tr '\n' ' ')"
adb shell input tap $((sx2 - 2)) $(((sy1 + sy2) / 2))
sleep 3
shot 8_long-end
pos=$(position)
echo "[긴 이미지 끝] $pos"
[ "$(percent "$pos")" -ge 98 ] || fail "슬라이더로 끝까지 가지 않음: $pos"
check_crash "긴 이미지"

echo "스크린샷: $OUT"
