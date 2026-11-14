#!/usr/bin/env python3
"""Run the real smoke script against deterministic SDK/adb fixtures."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent

# Only external commands are faked: smoke.sh and tap.py run unchanged.
FAKE_TOOL = r'''#!/usr/bin/env bash
name="${0##*/}"
{ printf '%s\0' "$name" "$@"; printf '\n'; } >> "$SMOKE_CALLS"
pkg="$FIXTURE_PACKAGE"
fixture="$FIXTURE_CASE"
if [[ "$name" == sleep ]]; then exit 0; fi
if [[ "$name" == aapt2 ]]; then
  if [[ "$fixture" == metadata_error ]]; then echo 'invalid APK' >&2; exit 1; fi
  if [[ "$fixture" == metadata_empty ]]; then echo "sdkVersion:'30'"; exit 0; fi
  echo "package: name='$pkg' versionCode='1' versionName='test'"
  exit 0
fi
case "$*" in
  install*) echo Success; [[ "$fixture" != install_exit_error ]]; exit $? ;;
  'shell getprop ro.product.cpu.abilist') echo 'x86_64,arm64-v8a' ;;
  'shell getprop ro.build.version.sdk') echo "$FIXTURE_SDK" ;;
  'shell pm path '*)
    if [[ "${!#}" == "$pkg" && "$fixture" != missing_package ]]; then
      echo 'package:/data/app/fixture/base.apk'
    fi ;;
  'shell pidof '*)
    [[ "${!#}" == "$pkg" && "$fixture" != dead_process ]] || exit 1
    echo 1234 ;;
  'shell am start '*)
    if [[ "$*" == *com.dl1991.mibot* || "$fixture" == start_error_zero ||
          ( "$fixture" == link_error && "$*" == *' -d '* ) ||
          ( "$fixture" == zh_error && "$*" == *ci_lang* ) ||
          ( "$fixture" == vd_error && "$*" == *ci_vd_usb* ) ]]; then
      echo 'Error type 3' >&2
      echo 'Error: Activity class does not exist.' >&2
    elif [[ "$fixture" == start_exit_error ]]; then
      echo 'adb: device offline' >&2; exit 1
    else
      echo "Starting: Intent { cmp=$pkg/com.openminis.app.MainActivity }"
      echo 'Status: ok'
    fi ;;
  'shell dumpsys window') echo "mCurrentFocus=Window{fixture $pkg/com.openminis.app.MainActivity}" ;;
  'exec-out cat /sdcard/ui.xml')
    echo '<hierarchy>'
    for label in 'Open sidebar' 'Back' Feed Ideas 'Group chats' Settings Chats 'Warm white' 'Dusk glass' \
        'Mikova Accounts Entry' 'Mibot Voice Entry' 'Mibot Task Queue Entry' \
        'Mibot WeChat Entry' 'Mibot Wallet Entry' 'Mikova Self Host Entry' \
        对话 群聊 设置 打开侧边栏 新对话 试装 启动 运行中 '查看日志（1）'; do
      printf '<node content-desc="%s" text="%s" bounds="[0,0][100,100]"/>' "$label" "$label"
    done
    echo '</hierarchy>' ;;
  'exec-out screencap'*) echo 'fixture screenshot' ;;
  'logcat -d'*)
    echo 'fixture diagnostics'
    if [[ "$fixture" == crash ]]; then
      printf 'FATAL EXCEPTION: main\nProcess: %s\nfixture crash\n' "$pkg"
    fi ;;
esac
exit 0
'''


class SmokeTest(unittest.TestCase):
    def run_smoke(self, case="ok", package="com.mikova.app", sdk="31", sdk_tool=False):
        with tempfile.TemporaryDirectory(prefix="smoke-test-") as temp:
            root = Path(temp)
            tools = root / "bin"
            tools.mkdir()
            for name in ["adb", "sleep", "aapt2"]:
                tool = tools / name
                tool.write_text(FAKE_TOOL, encoding="utf-8")
                tool.chmod(0o755)
            android_home = root / "sdk"
            if sdk_tool:
                sdk_bin = android_home / "build-tools" / "35.0.0"
                sdk_bin.mkdir(parents=True)
                (tools / "aapt2").rename(sdk_bin / "aapt2")
            apk = root / "fixture app.apk"
            apk.touch()
            out = root / "screens"
            calls = root / "calls.jsonl"
            env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"],
                       ANDROID_HOME=str(android_home), ANDROID_SDK_ROOT=str(android_home),
                       SMOKE_CALLS=str(calls), FIXTURE_CASE=case,
                       FIXTURE_PACKAGE=package, FIXTURE_SDK=sdk)
            result = subprocess.run(["bash", str(HERE / "smoke.sh"), str(apk), str(out)],
                                    env=env, capture_output=True, text=True, timeout=180)
            trace = [line.rstrip("\0").split("\0") for line in calls.read_text().splitlines()]
            files = {str(p.relative_to(out)): p.read_bytes() for p in out.rglob("*") if p.is_file()}
            return result, trace, files

    def assert_failed_early(self, case):
        result, trace, files = self.run_smoke(case)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("01-chats.png", files, result.stdout + result.stderr)
        self.assertIn("logcat.txt", files)
        self.assertIn("crash.txt", files)
        self.assertFalse(any(call[:4] == ["adb", "shell", "input", "tap"] for call in trace))
        return trace, files

    def test_application_id_from_apk_for_all_calls(self):
        for package, sdk in [("com.mikova.app", "30"), ("org.example.rebranded.debug", "31")]:
            with self.subTest(package=package, sdk=sdk):
                result, trace, files = self.run_smoke(package=package, sdk=sdk)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                calls = json.dumps([call for call in trace if call[:2] == ["adb", "shell"]])
                self.assertNotIn("com.dl1991.mibot", calls)
                self.assertIn(["adb", "shell", "pm", "path", package], trace)
                for call in trace:
                    if call[:4] == ["adb", "shell", "am", "start"]:
                        if "-n" in call:
                            self.assertEqual(call[call.index("-n") + 1],
                                             package + "/com.openminis.app.MainActivity")
                        else:
                            self.assertEqual(call[-1], package)
                self.assertIn(package + "/com.openminis.app.MainActivity", calls)
                self.assertIn("minis://settings/", calls)
                self.assertIn("zh/dark-24-connect-github.png", files)
                self.assertEqual("vd/vd-live.png" in files, sdk == "31")
                if sdk == "31":
                    self.assertIn("/sdcard/Android/data/" + package + "/files/usb-start.sh", calls)

    def test_discovers_installed_sdk_aapt2(self):
        result, trace, files = self.run_smoke(sdk_tool=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(any(call[:3] == ["aapt2", "dump", "badging"] for call in trace))

    def test_bad_metadata_never_installs_or_launches(self):
        for case in ["metadata_error", "metadata_empty"]:
            with self.subTest(case=case):
                trace, files = self.assert_failed_early(case)
                self.assertFalse(any(call[:2] == ["adb", "install"] for call in trace))

    def test_missing_installed_package_never_launches(self):
        trace, files = self.assert_failed_early("missing_package")
        self.assertFalse(any(call[:4] == ["adb", "shell", "am", "start"] for call in trace))

    def test_install_exit_status_is_checked(self):
        self.assert_failed_early("install_exit_error")

    def test_start_error_with_zero_exit_status(self):
        trace, files = self.assert_failed_early("start_error_zero")
        self.assertIn(b"Error type 3", files["start-en.txt"])

    def test_start_nonzero_exit_status(self):
        self.assert_failed_early("start_exit_error")

    def test_dead_process_stops_before_ui_walk(self):
        self.assert_failed_early("dead_process")

    def test_later_launches_are_also_checked(self):
        for case, absent in [("link_error", "08-backdrop-settings.png"),
                             ("zh_error", "zh/light-01-onboarding.png"),
                             ("vd_error", "vd/vd-live.png")]:
            with self.subTest(case=case):
                result, trace, files = self.run_smoke(case)
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertNotIn(absent, files)
                self.assertIn("logcat.txt", files)

    def test_crash_gate_is_preserved(self):
        result, trace, files = self.run_smoke("crash")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"FATAL EXCEPTION", files["crash.txt"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
