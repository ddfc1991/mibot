#!/usr/bin/env bash
# Emulator smoke test: install, launch, capture key screens of the glass UI.
# Usage: smoke.sh <apk> <outdir>
set -uo pipefail
APK="$1"; OUT="$2"
mkdir -p "$OUT"
HERE="$(cd "$(dirname "$0")" && pwd)"
fail=0
link_count=0

# Preserve evidence even when install or ActivityManager fails before the UI walk.
finish() {
  local result=$?
  trap - EXIT
  adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
  grep -E "FATAL EXCEPTION" -A30 "$OUT/logcat.txt" | head -120 > "$OUT/crash.txt" || true
  if [ -s "$OUT/crash.txt" ]; then
    echo "::error::crash in logcat"; cat "$OUT/crash.txt"; result=1
  fi
  if [ "$result" -ne 0 ]; then
    adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
    adb shell dumpsys window > "$OUT/window.txt" 2>&1 || true
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/ui.xml > "$OUT/last-ui.xml" || true
    adb exec-out screencap -p > "$OUT/failure.png" 2>/dev/null || true
  fi
  exit "$result"
}
trap finish EXIT
abort() { echo "::error::$*"; exit 1; }

# applicationId comes from the built APK, not the Kotlin namespace or repo name.
AAPT2="$(command -v aapt2 || true)"
if [ -z "$AAPT2" ]; then
  for candidate in "${ANDROID_HOME:-}/build-tools/"*/aapt2 "${ANDROID_SDK_ROOT:-}/build-tools/"*/aapt2; do
    if [ -x "$candidate" ]; then AAPT2="$candidate"; fi
  done
fi
[ -n "$AAPT2" ] || abort "aapt2 not found; install Android SDK build-tools"
"$AAPT2" dump badging "$APK" > "$OUT/apk-badging.txt" 2> "$OUT/apk-badging-error.txt" || abort "cannot read APK metadata"
PKG="$(python3 -c 'import re, sys; text = open(sys.argv[1]).read(); match = re.search(r"^package: name=\x27([A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)+)\x27", text, re.M); sys.exit(1) if match is None else print(match.group(1))' "$OUT/apk-badging.txt")" || abort "APK metadata has no valid applicationId"
echo "$PKG" | tee "$OUT/package.txt"

alive() { adb shell pidof "$PKG" >/dev/null 2>&1; }
probe() { python3 "$HERE/ui_probe.py" "$@"; }
shot() {
  local file="$1" pause="$2"; shift 2
  sleep "$pause"; alive || abort "app died before screenshot $file"
  mkdir -p "$(dirname "$OUT/$file")"
  probe check --save "$OUT/$file.xml" "$@" || abort "UI assertion failed: $file"
  adb exec-out screencap -p > "$OUT/$file.png" || abort "screenshot failed: $file"
  echo "shot: $file ($(stat -c%s "$OUT/$file.png") bytes)"
}
tap() { probe tap "$@"; }
start() {
  local log="$1"; shift
  if ! adb shell am start -W "$@" > "$log" 2>&1; then
    cat "$log"; abort "ActivityManager failed ($log)"
  fi
  # am can return exit code 0 while reporting a missing activity on stderr.
  if grep -qE '^Error([ :]|$)|Exception|^Status: (timeout|error|failed)' "$log"; then
    cat "$log"; abort "ActivityManager rejected launch ($log)"
  fi
}
launch() { start "$OUT/start-$1.txt" -n "$PKG/com.openminis.app.MainActivity" --ez glass_demo true; }
link() {
  link_count=$((link_count + 1))
  # Keep the URI quoted for Android's remote shell as well (query strings).
  start "$OUT/link-$link_count.txt" -a android.intent.action.VIEW -d "'minis://settings/$1'" "$PKG"
}
# The original five-tab Mibot glass shell is the default home.
adb shell getprop ro.product.cpu.abilist | tee "$OUT/abilist.txt"
adb logcat -c || true
if ! adb install -r -g "$APK" 2>&1 | tee "$OUT/install.txt"; then abort "install failed"; fi
grep -q '^Success' "$OUT/install.txt" || abort "install did not report success"
adb shell pm path "$PKG" > "$OUT/package-path.txt" 2>&1 || abort "installed package query failed"
grep -q '^package:' "$OUT/package-path.txt" || abort "APK package $PKG is not installed"
# CI owns this emulator installation: never photograph an existing account/key.
adb shell pm clear "$PKG" > "$OUT/clean-install.txt" 2>&1 || abort 'cannot clear CI app data'

adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

# glass_demo (debug builds only) fills the sidebar's recent list with sample
# chats/groups so a fresh emulator has something to render.
launch en
sleep 25
alive || abort "app not running after launch"
shot 00-entry 1 --text 'Welcome to Mibot' --text 'Add a Provider' --desc 'Open sidebar'
shot 01-chats 1 --text 'Select Models' --desc Chats

tap desc "Open sidebar" && shot 02-sidebar 1 --desc 'New chat' || abort "sidebar button not found"
adb shell input keyevent KEYCODE_BACK; sleep 1

tap desc Feed && shot 03-feed 1 --text Feed --text 'What happened in the app, newest first' || abort 'Feed tab missing'
tap desc Ideas && shot 04-ideas 1 --text Ideas --text 'New idea' || abort 'Ideas tab missing'
tap desc 'Group chats' && shot 05-groups 1 --text 'Group chats' --text 'New group chat' || abort 'Groups tab missing'
tap desc Settings && shot 06-settings 1 --text Settings --text Model || abort 'Settings tab missing'

# Each entry starts at the top, even after returning to a restored list offset.
# One lookup per dump; both directions stop at a stable boundary or a hard cap.
settings_entry() {
  probe tap desc "$1" --top --scrolls 10 --save "$OUT/settings-entry.xml"
}
module_back() {
  tap desc Back || abort "module Back control missing"
  sleep 1
}
modules() {
  local prefix="$1" item desc file
  mkdir -p "$OUT/$prefix"
  for item in 'WeChat:wechat' 'Wallet:wallet' 'Self Host:selfhost' 'Virtual Display:virtual-display' 'Accounts:accounts' 'Task Queue:tasks' 'Voice:voice'; do
    desc="Mikova ${item%%:*} Entry"; file="${item##*:}"
    case "$file" in
      wechat) tap desc "$desc" || abort 'WeChat entry missing'; shot "$prefix/$file" 1 --text 'WeChat bridge|微信接入' --text 'WeChat channel|微信通道' --desc Back ;;
      wallet) tap desc "$desc" || abort 'Wallet entry missing'; shot "$prefix/$file" 1 --text Wallet --text 'Available balance|可用余额' --text 'No pay channel|未接支付通道' --desc Back ;;
      selfhost) tap desc "$desc" || abort 'Self-host entry missing'; shot "$prefix/$file" 1 --text 'Self-host connection|自托管连接' --desc 'Mikova SelfHost URL' --desc 'Mikova SelfHost Save' --desc 'Mikova SelfHost Test' --desc Back ;;
      virtual-display) tap desc "$desc" || abort 'Virtual display entry missing'; shot "$prefix/$file" 1 --text '虚拟屏多开' --text '新建虚拟屏' --desc Back ;;
      accounts) tap desc "$desc" || abort 'Accounts entry missing'; shot "$prefix/$file" 1 --text 'Account hub|账号连接中心' --text '+ Add account|+ 添加账号' --desc Back ;;
      tasks) tap desc "$desc" || abort 'Task queue entry missing'; shot "$prefix/$file" 1 --text 'Task queue|任务队列' --desc 'Mibot Task Queue' --desc 'Mibot New Task' --desc 'Mibot Scheduled Tasks' ;;
      voice) tap desc "$desc" || abort 'Voice entry missing'; shot "$prefix/$file" 1 --text '语音|Voice' --desc 'Mikova Voice Settings' --desc Back ;;
    esac
    module_back
    probe top || abort "Settings list did not return after $file"
    probe check --text Settings --desc 'Mikova Accounts Entry' || abort "Settings page unavailable after $file"
    alive || abort "app died during $file module screenshot"
  done
}
# Settings is the entry point into the added modules, without a legacy V7 detour.
modules modules
# Settings page scrolled to the Appearance / Feedback rows.
adb shell input swipe 540 1700 540 900 400; shot 07-settings-scrolled 2

link backdrop
shot 08-backdrop-settings 4

# Style B (warm white): switch, look at Chats + sidebar, then switch back to A.
if tap text "Warm white"; then
  shot 08b-backdrop-warm 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
  tap desc "Chats" && shot 12-chats-warm 3
  tap desc "Open sidebar" && shot 13-sidebar-warm 3
  adb shell input keyevent KEYCODE_BACK; sleep 2
  link backdrop; sleep 3
  tap text "Dusk glass" || true
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi
tap desc "Chats" >/dev/null 2>&1 || true

# Mibot v2 Chinese pass, light + dark. ci_lang/ci_theme/ci_vault_demo are
# debug-only launch extras (MainActivity); language applies from the next
# process start on API < 33, so seed once, then relaunch per theme.
ZH="$OUT/zh"; mkdir -p "$ZH"
zlaunch() { adb shell am force-stop "$PKG"; sleep 1
  start "$ZH/start-$2.txt" -n "$PKG/com.openminis.app.MainActivity" --ez glass_demo true \
    --es ci_lang zh-CN --ez ci_vault_demo true --ei ci_theme "$1"; sleep "${3:-12}"; }
zshot() { local file="$1"; shift; shot "zh/$file" "$@"; }
# Back to the chats tab; relaunch if Back left the app.
front() { adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp" | grep -Fq "$PKG"; }
home() { adb shell input keyevent KEYCODE_BACK; sleep 2
  front || { launch home; sleep 4; }
  if ! tap desc "对话" >/dev/null 2>&1; then
    tap desc "Chats" || abort "glass chats tab not found after returning home"
  fi
  sleep 1; }
adb shell am force-stop "$PKG"; adb shell pm clear "$PKG" >/dev/null 2>&1 || true
zlaunch 1 seed 15
for th in 1:light 2:dark; do
  m="${th%%:*}"; n="${th##*:}"
  zlaunch "$m" "$n" 14
  alive || abort "app not running (zh $n)"
  zshot "$n-00-entry" 1 --text '欢迎使用 Mibot' --desc 对话
  zshot "$n-01-onboarding" 1 --text 添加供应商 --text 选择模型
  tap desc 动态 && zshot "$n-feed" 1 --text 动态 --text '应用里发生的事，最新的在最上面' || abort "zh 动态 tab missing ($n)"
  tap desc 点子 && zshot "$n-ideas" 1 --text 点子 --text 新点子 || abort "zh 点子 tab missing ($n)"
  tap desc 群聊 && zshot "$n-04-groups" 1 --text 群聊 --text 新建群聊 || abort "zh 群聊 tab missing ($n)"
  tap desc 设置 && zshot "$n-05-settings" 1 --text 设置 --text 模型 || abort "zh 设置 tab missing ($n)"
  modules "zh/$n-modules" 设置
  tap desc 对话 || abort "zh 对话 tab missing ($n)"
  tap desc 打开侧边栏 && zshot "$n-03-sidebar" 1 --desc 新对话 || abort "zh sidebar missing ($n)"
  tap desc 新对话 && zshot "$n-02-chat" 1 --desc Back --desc Attach || abort "zh new chat missing ($n)"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  probe check --desc 对话 || abort "zh shell missing after new chat ($n)"
done
# All required modules in BOTH Chinese themes are captured before any network
# catalog or legacy capability attempts. These remain diagnostic, not proof of
# task execution, account authorization, TTS quality, or working hidden displays.
for th in 1:light 2:dark; do
  m="${th%%:*}"; n="${th##*:}"
  zlaunch "$m" "$n-legacy" 8
  link vault;        zshot "$n-06-vault" 4;    home
  link gateway;      zshot "$n-07-gateway" 4;  home
  link sandbox;      zshot "$n-08-sandbox" 4;  home
  link add-provider; zshot "$n-09-add-provider" 4; home
  link virtual-display; zshot "$n-10-vd" 4
  adb shell input swipe 540 1700 540 700 400; zshot "$n-11-vd-scrolled" 2; home
  link backdrop;     zshot "$n-12-backdrop" 4; home
  # [fork/mibot-v5] 插件市场: list (both sections), a category with desktop
  # plugins, and one plugin's detail page (needs network on the emulator).
  link plugins;      zshot "$n-13-plugins" 10
  adb shell input swipe 540 1700 540 500 500; zshot "$n-14-plugins-scrolled" 3
  home
  link "plugins?section=desktop"
  zshot "$n-15-plugins-desktop" 10; home
  link "plugins?tab=efficiency"
  zshot "$n-16-plugins-efficiency" 10
  adb shell input swipe 540 1700 540 500 500; zshot "$n-16b-plugins-efficiency-scrolled" 3; home
  link "plugin?name=dsh-market/dsh-market"
  zshot "$n-17-plugin-detail" 12
  adb shell input swipe 540 1700 540 600 500; zshot "$n-18-plugin-detail-scrolled" 3; home
  # [fork/mibot-v5b2] 邮箱台账 (demo loopback boxes), AI 邮箱 tab, 权限与自动化,
  # dsh-our-free-model detail (adapter audit + MIT attribution), 连接方式 sheets.
  link mail;         zshot "$n-19-mail-ledger" 8
  adb shell input swipe 540 1700 540 600 500; zshot "$n-19b-mail-ledger-scrolled" 3; home
  link ai-mail;      zshot "$n-20-ai-mail" 6; home
  link capabilities; zshot "$n-21-capabilities" 5
  adb shell input swipe 540 1700 540 600 500; zshot "$n-21b-capabilities-scrolled" 3; home
  link "plugin?name=ebony-vinyl/dsh-our-free-model"
  zshot "$n-22-plugin-ofm" 12
  adb shell input swipe 540 1700 540 600 500; zshot "$n-22b-plugin-ofm-audit" 3
  adb shell input swipe 540 1700 540 600 500; zshot "$n-22c-plugin-ofm-more" 3; home
  # [fork/mibot-v5b4] DSH host verdicts: skill-only (手机能用), blocked (不支持), missing services (待支持).
  link "plugin?name=alchaincyf/nuwa-skill"
  zshot "$n-25-plugin-nuwa" 12
  adb shell input swipe 540 1700 540 600 500; zshot "$n-25b-plugin-nuwa-host" 3; home
  link "plugin?name=yujunzhixue/dsh-purge"
  zshot "$n-26-plugin-purge" 12; home
  link "plugin?name=liustack/modlens"
  zshot "$n-27-plugin-modlens" 12; home
  link "vault-discover?connect=hunyuan"
  zshot "$n-23-connect-hunyuan" 6; home
  link "vault-discover?connect=github"
  zshot "$n-24-connect-github" 6; home
  alive || { echo "::error::app died during zh $n pass"; fail=1; }
done
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/ui.xml > "$ZH/last-ui.xml" || true

# [fork/mibot-v5b4] DSH host on the emulator (best effort, never fails the run):
# install nuwa-skill, press 启动 → Node 22 in the sandbox → skills imported.
# The x86_64 image runs the arm64 app through native-bridge translation, so the
# PRoot sandbox may not come up here; the plugin page then shows 失败 + its log.
DSH="$OUT/dsh"; mkdir -p "$DSH"
echo 'optional diagnostic; execution not verified' > "$DSH/verdict.txt"
link "plugin?name=alchaincyf/nuwa-skill"; sleep 12
if tap text "试装"; then sleep 40; fi
adb exec-out screencap -p > "$DSH/1-installed.png"
if tap text "启动" || { adb shell input swipe 540 1700 540 1100 500; sleep 2; tap text "启动"; }; then
  for i in $(seq 1 24); do sleep 10; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/ui.xml > "$DSH/ui.xml"; grep -qE 'text="(运行中|失败|待支持)"' "$DSH/ui.xml" && break; done
  adb exec-out screencap -p > "$DSH/2-started.png"
  grep -oE 'text="(运行中|失败|待支持|准备中|未启动)"' "$DSH/ui.xml" | head -3 | tee "$DSH/state.txt"
  tap text "查看日志（$(grep -oE '查看日志（[0-9]+）' "$DSH/ui.xml" | grep -oE '[0-9]+' | head -1)）" >/dev/null 2>&1 || true
  sleep 2; adb exec-out screencap -p > "$DSH/3-log.png"
  adb shell input swipe 540 1700 540 700 500; sleep 2; adb exec-out screencap -p > "$DSH/4-log-scrolled.png"
else echo "::warning::DSH 启动 button not found"; fi
adb logcat -d -v time | grep -E "DshHost|MibotPlugins" > "$DSH/log.txt" || true
alive || { echo "::error::app died during DSH host attempt"; fail=1; }
home

# [fork/mibot-v3] Hidden-display end-to-end (Android 12+): the app writes its
# "start from a computer" script, adb (shell uid) runs it -> helper hands its
# Binder to the app. Then the app is force-stopped and relaunched: the helper
# must re-attach on its own. The VD page (ci_vd_demo) creates a hidden display
# and starts Settings on it; the main screen must keep showing Mibot.
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "${SDK:-0}" -ge 31 ]; then
  VD="$OUT/vd"; mkdir -p "$VD"
  adb shell am force-stop $PKG; sleep 1
  start "$VD/start-usb.txt" -n "$PKG/com.openminis.app.MainActivity" --ez ci_vd_usb true; sleep 8
  SCRIPT=/sdcard/Android/data/$PKG/files/usb-start.sh
  adb shell ls -l "$SCRIPT" > "$VD/script-ls.txt" 2>&1 || true
  adb shell sh "$SCRIPT" > "$VD/usb-start-out.txt" 2>&1 || true
  cat "$VD/usb-start-out.txt"
  sleep 5
  adb shell "ps -A -o USER,PID,NAME | grep vdhelper" > "$VD/helper-ps.txt" 2>&1 || true
  cat "$VD/helper-ps.txt"
  adb shell am force-stop $PKG; sleep 2
  # Relaunch (the helper must re-attach by itself), then open the page via deep link.
  start "$VD/start-demo.txt" -n "$PKG/com.openminis.app.MainActivity" --ez ci_vd_demo true; sleep 12
  link virtual-display; sleep 30
  adb exec-out screencap -p > "$VD/vd-live.png"; echo "vd shot: $(stat -c%s "$VD/vd-live.png") bytes"
  adb shell dumpsys display > "$VD/dumpsys-display.txt" 2>&1 || true
  grep -n "mibot-agent" "$VD/dumpsys-display.txt" | head -5 || echo "no mibot-agent display"
  adb shell dumpsys activity activities > "$VD/activities.txt" 2>&1 || true
  grep -nE "Display #|com.android.settings" "$VD/activities.txt" | head -20 || true
  adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > "$VD/focus.txt" 2>&1 || true
  cat "$VD/focus.txt"
  adb shell input swipe 540 1600 540 600 400; sleep 2
  adb exec-out screencap -p > "$VD/vd-live-log.png"
  adb logcat -d -v time | grep -E "MibotHelper|MibotShell|MibotHelperHub|MibotPhone" > "$VD/helper-log.txt" || true
  tail -40 "$VD/helper-log.txt"
  alive || { echo "::error::app died during vd e2e"; fail=1; }
fi

alive || { echo "::error::app died during smoke test"; fail=1; }
exit $fail
