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
  grep -E "FATAL EXCEPTION|ACRA.*ACRA caught.*for ${PKG:-com.mikova.app}" -A65 "$OUT/logcat.txt" | head -200 > "$OUT/crash.txt" || true
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

# All modules must be opened through the existing Settings UI and return by Back.
# A selected Tab can be non-clickable; selecting it is an assertion, not a tap.
settings_entry() {
  probe tap desc "$1" --top --scrolls 10 --save "$OUT/settings-entry.xml"
}
module_back() {
  tap desc Back || abort "module Back control missing"
  sleep 1
}
modules() {
  local prefix="$1" tab="${2:-Settings}" item desc file
  local wallet_title='Wallet' accounts_title='Account hub' add_account='+ Add account'
  local collapse='Collapse' tasks_title='Task queue' wechat_title='WeChat bridge' wechat_control='WeChat channel'
  if [ "$tab" = 设置 ]; then
    wallet_title=钱包; accounts_title=账号连接中心; add_account='+ 添加账号'; collapse=收起目录
    tasks_title=任务队列; wechat_title=微信接入; wechat_control=微信通道
  fi
  mkdir -p "$OUT/$prefix"
  for item in 'WeChat:wechat' 'Wallet:wallet' 'Self Host:selfhost' 'Virtual Display:virtual-display' 'Accounts:accounts' 'Task Queue:tasks' 'Voice:voice'; do
    desc="Mikova ${item%%:*} Entry"; file="${item##*:}"
    probe select desc "$tab" || abort "Settings tab unavailable before $file"
    settings_entry "$desc" || abort "settings entry $desc not found"
    case "$file" in
      wechat) shot "$prefix/$file" 1 --text "$wechat_title" --text "$wechat_control" --desc Back ;;
      wallet) shot "$prefix/$file" 1 --text "$wallet_title" --text 'Available balance|可用余额' --text 'No pay channel|未接支付通道' --desc Back ;;
      selfhost)
        shot "$prefix/$file" 1 --text 'Self-host connection|自托管连接' --desc 'Mikova SelfHost URL' --desc 'Mikova SelfHost Save' --desc 'Mikova SelfHost Test' --desc Back
        probe tap desc 'Mikova SelfHost Guide' --scrolls 5 --save "$OUT/$prefix/selfhost-guide-before.xml" || abort 'selfhost guide missing'
        shot "$prefix/selfhost-guide" 1 --desc 'Mikova SelfHost Guide' --expanded-from "$OUT/$prefix/selfhost-guide-before.xml" --desc Back ;;
      virtual-display) shot "$prefix/$file" 1 --text '虚拟屏多开' --text '新建虚拟屏' --desc Back ;;
      accounts)
        shot "$prefix/$file" 1 --text "$accounts_title" --text "$add_account" --desc Back
        tap text "$add_account" || abort 'account catalog control missing'
        shot "$prefix/accounts-catalog" 1 --text "$accounts_title" --text "$collapse" --desc Back ;;
      tasks) shot "$prefix/$file" 1 --text "$tasks_title" --desc 'Mibot Task Queue' --desc 'Mibot New Task' --desc 'Mibot Scheduled Tasks' --desc Back ;;
      voice)
        # -g does not pre-grant RECORD_AUDIO on every API level: grant it
        # explicitly so the listen interaction really starts.
        adb shell pm grant "$PKG" android.permission.RECORD_AUDIO > "$OUT/grant-voice.txt" 2>&1 && echo "granted RECORD_AUDIO" || { echo "::warning::cannot grant record audio"; cat "$OUT/grant-voice.txt"; }
        sleep 1
        shot "$prefix/$file" 1 --text 语音 --desc 'Mikova Voice Settings' --desc Back
        # Real listen interaction: permission is pre-granted by -g; the emulator may
        # or may not have a default recognition service. Tap the field, wait, screenshot
        # whichever state (listening / diagnosis / permission) without failing the run.
        probe tap desc 'Mikova Voice Mic' --scrolls 2 || echo "::warning::voice mic not found"
        sleep 3
        shot "$prefix/voice-listen" 1 || true
        probe tap desc 'Mikova Voice Mic' --scrolls 2 || true
        sleep 2
        shot "$prefix/voice-listen-end" 1 || true
        # Real read-aloud interaction over the TTS field.
        probe tap desc 'Mikova Voice TTS Input' --scrolls 2 || echo "::warning::voice tts input not found"
        adb shell input keyevent KEYCODE_MOVE_END
        adb shell input text MikovaTTS01 || echo "::warning::cannot enter tts text"
        adb shell input keyevent KEYCODE_BACK; sleep 1
        probe tap desc 'Mikova Voice Speak' --scrolls 2 || echo "::warning::voice speak not found"
        sleep 5
        shot "$prefix/voice-speak" 1 || true
        probe tap desc 'Mikova Voice Transcript' --scrolls 6 || abort 'voice transcript missing'
        adb shell input keyevent KEYCODE_MOVE_END
        adb shell input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
        adb shell input text MikovaDraft42 || abort 'cannot enter voice draft'
        adb shell input keyevent KEYCODE_BACK; sleep 1
        shot "$prefix/voice-draft" 1 --desc 'Mikova Voice Transcript' --draft 'Mikova Voice Transcript' MikovaDraft42
        probe tap desc 'Mikova Voice Settings' --top --scrolls 2 || abort 'voice settings missing'
        shot "$prefix/voice-settings" 1 --text 语音设置 --text '系统识别(兜底)' --desc 'Mikova System Voice Settings' --desc 'Mikova System TTS Settings' --desc Back
        module_back
        probe seek desc 'Mikova Voice Transcript' --scrolls 6 || abort 'voice transcript missing after Back'
        shot "$prefix/voice-return" 1 --desc 'Mikova Voice Transcript' --draft 'Mikova Voice Transcript' MikovaDraft42
        # Floating voice overlay entry exists (open/close validated in the
        # dedicated pass after the module loop). Only check presence here —
        # do NOT tap it, which would open the overlay and pollute the
        # Settings assertion that follows.
        probe check --desc 'Mikova Floating Voice' 2>/dev/null || echo "::warning::floating voice entry not found"
        ;;
    esac
    module_back
    alive || abort "app died returning from $file"
    probe check --desc "$tab" --save "$OUT/$prefix/$file-return.xml" || abort "glass shell missing after $file"
  done
}
modules modules Settings
# Settings page scrolled to the Appearance / Feedback rows.
adb shell input swipe 540 1700 540 900 400; shot 07-settings-scrolled 2

# --- Floating voice overlay (dedicated pass) ------------------------------
# Open from the Voice page, assert the overlay window via dumpsys, then close
# it (close button, else BACK) and confirm the shell is intact on Settings.
# The module loop ends on the Settings page scrolled down to Appearance, so
# scroll back to the top before re-selecting the Settings tab.
probe select desc 设置 --top || probe select desc Settings --top || abort "Settings tab unavailable for floating voice"
probe tap desc 'Mikova Voice Entry' --scrolls 8 --top || echo "::warning::voice entry not found for float"
sleep 1
probe tap desc 'Mikova Floating Voice' --scrolls 6 --top || echo "::warning::floating voice entry not found (dedicated)"
sleep 2
shot 07b-voice-float 1 || true
adb shell dumpsys window > "$OUT/float-windows.txt" 2>&1
if grep -q 'FloatingVoice' "$OUT/float-windows.txt"; then
  echo "floating voice window present (dedicated)"
else
  echo "::warning::floating voice window not in dumpsys (dedicated)"
fi
# Close via the overlay's own close button (the overlay now takes focus, so
# uiautomator can see it). Fall back to BACK if the button is not found.
probe tap desc '关闭' --top --scrolls 0 || probe tap desc 'Close' --top --scrolls 0 || { echo "::warning::floating voice close button not found (dedicated)"; adb shell input keyevent KEYCODE_BACK; }
sleep 1
adb shell dumpsys window > "$OUT/float-windows-after.txt" 2>&1
if grep -q 'FloatingVoice' "$OUT/float-windows-after.txt"; then
  echo "::warning::floating voice still present after close (dedicated)"
  adb shell input keyevent KEYCODE_BACK; sleep 1
fi
# Now back on the Voice page; BACK to the Settings shell.
adb shell input keyevent KEYCODE_BACK; sleep 1
probe select desc 设置 || probe select desc Settings || abort "Settings shell missing after floating voice (dedicated)"
shot 07c-settings-after-float 2


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
home() {
  link home; sleep 2
  probe check --desc "对话|Chats" || abort "original chat shell missing after home link"
}
adb shell am force-stop "$PKG"; adb shell pm clear "$PKG" >/dev/null 2>&1 || true
zlaunch 1 seed 15
for th in 1:light 2:dark; do
  m="${th%%:*}"; n="${th##*:}"
  zlaunch "$m" "$n" 14
  alive || abort "app not running (zh $n)"
  # Dark first render is slower on API 31; give the entry a longer settle.
  if [ "$m" = "2" ]; then
    for _ in 1 2 3; do
      probe check --text Mibot --desc 对话 --desc Conversations && break
      sleep 4
    done
  fi
  # ci_vault_demo configures a demo provider: the original shell opens Chat,
  # while the initial English clean-install pass already verified onboarding.
  zshot "$n-00-entry" 1 --text Mibot --desc 对话 --desc Conversations
  zshot "$n-01-chat" 1 --desc Attach --desc 'Voice input' --desc Send
  tap desc 动态 && zshot "$n-feed" 1 --text 动态 --text '应用里发生的事，最新的在最上面' || abort "zh 动态 tab missing ($n)"
  tap desc 点子 && zshot "$n-ideas" 1 --text 点子 --text 新点子 || abort "zh 点子 tab missing ($n)"
  tap desc 群聊 && zshot "$n-04-groups" 1 --text 群聊 --text 新建群聊 || abort "zh 群聊 tab missing ($n)"
  tap desc 设置 && zshot "$n-05-settings" 1 --text 设置 --text 模型 || abort "zh 设置 tab missing ($n)"
  modules "zh/$n-modules" 设置
  tap desc 对话 || abort "zh 对话 tab missing ($n)"
  tap desc Conversations && zshot "$n-03-sidebar" 1 --desc 新对话 || abort "zh sidebar missing ($n)"
  tap desc 新对话 && zshot "$n-02-chat" 1 --desc Conversations --desc Attach || abort "zh new chat missing ($n)"
  # The preserved glass chat has a conversation drawer, not a top-bar Back.
  # Select Settings through its real tab rather than exiting the task with system Back.
  probe select desc 设置 || abort "zh settings tab missing after new chat ($n)"
  probe check --desc 设置 || abort "zh shell missing after new chat ($n)"
done
# [fork/mikova-frost-island] Real-content verification for the two new
# behaviors: (1) frosted feed cards over seeded entries (no background
# bleed-through), (2) the per-display floating island (流云体) as a real
# overlay window once a virtual screen exists. Overlay permission is granted
# via appops (CI owns this emulator installation).
NEW="$OUT/new"; mkdir -p "$NEW"
adb shell am force-stop "$PKG"; sleep 1
python3 - "$NEW/feed.json" <<'PY' 
import json, sys, time
now = int(time.time() * 1000)
entries = [
    {"id": "seed-1", "kind": "task_result", "title": "定时任务完成：每日备份", "body": "备份了 42 个文件，占用 1.2 MB，用时 3 秒。全部写入保险库。", "ts": now - 60_000, "liked": False, "sourceRef": "task:seed-1"},
    {"id": "seed-2", "kind": "key_acquired", "title": "新密钥已保存", "body": "服务：example.com（仅显示服务名，密钥内容不落盘）", "ts": now - 3_600_000, "liked": False, "sourceRef": "vault:example.com"},
    {"id": "seed-3", "kind": "plugin_event", "title": "插件已启动：dsh-market", "body": "Node 22 就绪，导入 3 个技能。", "ts": now - 7_200_000, "liked": True, "sourceRef": "plugin:dsh-market"},
]
json.dump({"version": 1, "entries": entries}, ensure_ascii=False, fp=open(sys.argv[1], "w"))
PY
adb push "$NEW/feed.json" /data/local/tmp/feed.json >/dev/null 2>&1 || abort "cannot push feed seed"
adb shell run-as "$PKG" mkdir -p files/feed || abort "cannot mkdir feed dir"
adb shell run-as "$PKG" cp /data/local/tmp/feed.json files/feed/feed.json || abort "cannot seed feed"
adb shell rm -f /data/local/tmp/feed.json
start "$NEW/start-feed.txt" -n "$PKG/com.openminis.app.MainActivity" --ez glass_demo true --ez ci_vault_demo true --ei ci_theme 1; sleep 14
alive || abort "app not running (frost feed pass)"
probe check --desc 对话 --save "$NEW/entry.xml" || probe check --desc Chats --save "$NEW/entry.xml" || abort "shell missing (frost feed pass)"
tap desc 动态 || tap desc Feed || abort "feed tab missing (frost feed pass)"
shot "$NEW/feed-seeded" 2 --text '定时任务完成：每日备份' --text '新密钥已保存' || abort "seeded feed entries missing"
# Pixel check (best effort): first-card area must be near-opaque frosted white
# in light theme; ghost text would show as dark pixels. Warn, never fail.
if python3 -c 'import PIL' >/dev/null 2>&1; then
  python3 - "$NEW/feed-seeded.png" <<'PY' || true
import sys
from PIL import Image
img = Image.open(sys.argv[1]).convert("RGB")
w, h = img.size
samples = []
for y in range(int(h * 0.28), int(h * 0.55), 12):
    for x in range(int(w * 0.08), int(w * 0.92), 12):
        r, g, b = img.getpixel((x, y))
        if r > 180 and g > 180 and b > 180:
            samples.append((r, g, b))
if samples:
    n = len(samples)
    avg = tuple(sum(c[i] for c in samples) // n for i in range(3))
    dark = sum(1 for c in samples if sum(c) < 640) / n
    open(sys.argv[1] + ".op.txt", "w").write(f"avg={avg} dark_ratio={dark:.3f} samples={n}\n")
    if avg[0] < 232 or avg[1] < 232 or avg[2] < 232 or dark > 0.05:
        print("::warning::feed card area not near-opaque; possible bleed-through")
PY
fi
# Floating island over a real virtual screen.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || abort "cannot grant overlay appop"
probe select desc 设置 --scrolls 4 || probe select desc Settings --scrolls 4 || abort "settings tab missing (island pass)"
settings_entry "Mikova Virtual Display Entry" || abort "vd entry missing (island pass)"
shot "$NEW/vd-pool" 1 --text '虚拟屏多开' --text '新建虚拟屏' --desc Back || abort "vd pool missing"
tap text '新建虚拟屏' || abort "create screen button missing"
sleep 12
shot "$NEW/vd-screen-created" 1 --text '屏幕池(1)' --text '流云体' --desc Back || abort "screen did not appear"
tap text '流云体' || abort "island toggle missing"
sleep 4
shot "$NEW/vd-island" 1 --desc 'display 预览' --text '收起浮窗' --desc Back || abort "island did not open"
adb shell dumpsys window windows > "$NEW/windows.txt" 2>&1 || true
if grep -q 'MikovaIsland' "$NEW/windows.txt"; then
  grep 'MikovaIsland' "$NEW/windows.txt" | head -3 | tee "$NEW/island-window.txt"
else
  echo "NO island overlay window" | tee "$NEW/island-window.txt"; abort "island overlay window absent in dumpsys"
fi
tap text '收起浮窗' || true; sleep 2
tap text '全部关闭' || true; sleep 3
module_back
probe check --desc 设置 --save "$NEW/after-island.xml" || probe check --desc Settings --save "$NEW/after-island.xml" || abort "shell missing after island pass"
# Core UI verification retains all tabs, modules, Back navigation and draft checks.
# Network catalogs and obsolete helper diagnostics remain in the full workflow.
if [ "${MIKOVA_CORE_UI_ONLY:-0}" = 1 ]; then
  printf '%s\n' 'Native UI checks passed. Cloud execution, payment, live WeChat access and hidden-display execution require separate device/service validation.' > "$OUT/ui-verdict.txt"
  exit "$fail"
fi
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
