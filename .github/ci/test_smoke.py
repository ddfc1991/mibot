#!/usr/bin/env python3
"""Run the real smoke script against deterministic SDK/adb fixtures."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent

# Only external commands are faked: smoke.sh and ui_probe.py run unchanged.
# Distinct pages and click targets prevent a wrong navigation from passing.
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
      python3 "$FIXTURE_UI" "$@"
    fi ;;
  'shell dumpsys window') echo "mCurrentFocus=Window{fixture $pkg/com.openminis.app.MainActivity}" ;;
  'shell wm size') echo 'Physical size: 1080x2400' ;;
  'exec-out cat /sdcard/ui.xml'|'shell input '*|'shell pm clear '*)
    exec python3 "$FIXTURE_UI" "$@" ;;
  'exec-out screencap'*) echo 'FIXTURE ONLY - NOT A DEVICE SCREENSHOT' ;;
  'logcat -d'*)
    echo 'fixture diagnostics'
    if [[ "$fixture" == crash ]]; then
      printf 'FATAL EXCEPTION: main\nProcess: %s\nfixture crash\n' "$pkg"
    fi ;;
esac
exit 0
'''


FAKE_UI = r'''import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

path = Path(os.environ['FIXTURE_STATE'])
s = json.loads(path.read_text()) if path.exists() else {'page': 'chats', 'zh': False, 'draft': ''}
a = sys.argv[1:]
case = os.environ['FIXTURE_CASE']
if a[:3] == ['shell', 'am', 'start']:
    s['zh'] = s['zh'] or 'ci_lang' in a
    s['page'] = 'legacy' if '-d' in a else 'chats'
    s['catalog'] = False
    if '-d' in a and 'backdrop' in a[a.index('-d') + 1]:
        s['page'] = 'backdrop'
elif a[:3] == ['shell', 'pm', 'clear']:
    s = {'page': 'chats', 'zh': False, 'draft': ''}
elif a[:3] == ['shell', 'input', 'tap']:
    x, y = map(int, a[3:5])
    target = s.get('targets', {}).get(f'{x},{y}')
    if target == 'back':
        s['page'] = {'voice-settings': 'voice', 'task-chat': 'tasks', 'chat': 'chats',
                     'sidebar': 'chats', 'legacy': 'chats', 'backdrop': 'chats'}.get(s['page'], 'settings')
    elif target == 'guide':
        s['guide'] = True
    elif target == 'catalog':
        s['catalog'] = True
    elif target == 'transcript':
        s['editing'] = True
    elif target:
        s['page'] = target
        if target == 'selfhost':
            s['guide'] = False
        if target == 'accounts':
            s['catalog'] = False
elif a[:3] == ['shell', 'input', 'text']:
    s['draft'] = a[3]
    s['keyboard'] = True
elif a[:3] == ['shell', 'input', 'keyevent'] and a[-1] == 'KEYCODE_BACK':
    if s.get('keyboard'):
        s['keyboard'] = False
    else:
        s['page'] = {'sidebar': 'chats', 'chat': 'chats', 'task-chat': 'tasks',
                     'voice-settings': 'voice'}.get(s['page'], 'chats')
if a == ['exec-out', 'cat', '/sdcard/ui.xml']:
    root = ET.Element('hierarchy', fixture='true')
    panel = ET.SubElement(root, 'node', bounds='[0,0][1080,2400]', enabled='true', scrollable='true')
    s['targets'] = {}
    idx = 0
    def node(text='', desc='', target=None):
        global idx
        idx += 1
        y = 60 + idx * 65
        n = ET.SubElement(panel, 'node', text=text, bounds=f'[40,{y}][1000,{y+50}]',
                          enabled='true', clickable='true' if target else 'false',
                          **{'content-desc': desc})
        if target:
            s['targets'][f'520,{y+25}'] = target
        return n
    def t(zh, en):
        return zh if s['zh'] else en
    p = s['page']
    if p in ['chats', 'feed', 'ideas', 'groups', 'settings']:
        for zh, en, page in [('对话', 'Chats', 'chats'), ('动态', 'Feed', 'feed'),
                              ('点子', 'Ideas', 'ideas'), ('群聊', 'Group chats', 'groups'),
                              ('设置', 'Settings', 'settings')]:
            node(desc=t(zh, en), target=page)
    else:
        node(desc='Back', target='back')
    if p == 'chats':
        node(t('欢迎使用 Mibot', 'Welcome to Mibot'))
        node(t('添加供应商', 'Add a Provider'))
        node(t('选择模型', 'Select Models'))
        node(desc=t('打开侧边栏', 'Open sidebar'), target='sidebar')
    elif p == 'sidebar':
        node(desc=t('新对话', 'New chat'), target='chat')
    elif p in ['chat', 'task-chat']:
        node(desc='Attach', target='attach')
        node('Empty unconfigured chat')
    elif p == 'feed':
        node(t('动态', 'Feed'))
        node(t('应用里发生的事，最新的在最上面', 'What happened in the app, newest first'))
    elif p == 'ideas':
        node(t('点子', 'Ideas'))
        node(t('新点子', 'New idea'))
    elif p == 'groups':
        node(t('群聊', 'Group chats'))
        node(t('新建群聊', 'New group chat'))
    elif p == 'settings':
        node(t('设置', 'Settings'))
        node(t('模型', 'Model'))
        for label, page in [('Wallet', 'wallet'), ('WeChat', 'wechat'), ('Self Host', 'selfhost'),
                            ('Virtual Display', 'virtual-display'), ('Accounts', 'accounts'),
                            ('Task Queue', 'tasks'), ('Voice', 'voice')]:
            if case == 'module_missing' and page == 'wechat':
                continue
            node(desc=f'Mikova {label} Entry', target=page)
    elif p == 'wechat':
        node(t('微信接入', 'WeChat bridge'))
        node(t('微信通道', 'WeChat channel'))
        if case == 'placeholder':
            node('未接入占位 / placeholder / 设计稿')
    elif p == 'wallet':
        for zh, en in [('钱包', 'Wallet'), ('可用余额', 'Available balance'), ('未接支付通道', 'No pay channel')]:
            node(t(zh, en))
    elif p == 'selfhost':
        node('自托管连接' if s['zh'] else 'Self-host connection')
        for label in ['URL', 'Save', 'Test']:
            node(desc=f'Mikova SelfHost {label}')
        node('收起说明' if s.get('guide') else '展开说明', desc='Mikova SelfHost Guide', target='guide')
        if s.get('guide'):
            node('连接配置说明：这里只保存服务地址，HTTP 响应不代表任务执行成功。')
    elif p == 'virtual-display':
        node('虚拟屏多开')
        node('新建虚拟屏')
    elif p == 'accounts':
        node(t('账号连接中心', 'Account hub'))
        node(t('收起目录', 'Collapse') if s.get('catalog') else t('+ 添加账号', '+ Add account'), target='catalog')
    elif p == 'tasks':
        node(t('任务队列', 'Task queue'), desc='Mibot Task Queue')
        node(desc='Mibot New Task', target='task-chat')
        node(desc='Mibot Scheduled Tasks')
    elif p == 'voice':
        node('语音')
        node(desc='Mikova Voice Settings', target='voice-settings')
        node(s['draft'], desc='Mikova Voice Transcript', target='transcript')
    elif p == 'voice-settings':
        node('语音设置')
        node('系统识别(兜底)')
    elif p == 'backdrop':
        node('Warm white', target='warm')
        node('Dusk glass', target='dusk')
    else:
        node('Legacy diagnostic fixture')
    print(ET.tostring(root, encoding='unicode'))
path.write_text(json.dumps(s))
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
            fixture_ui = root / "fixture_ui.py"
            fixture_ui.write_text(FAKE_UI, encoding="utf-8")
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
                       FIXTURE_PACKAGE=package, FIXTURE_SDK=sdk,
                       FIXTURE_UI=str(fixture_ui), FIXTURE_STATE=str(root / "state.json"))
            result = subprocess.run(["bash", str(HERE / "smoke.sh"), str(apk), str(out)],
                                    env=env, capture_output=True, text=True, timeout=420)
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
                for prefix in ['modules', 'zh/light-modules', 'zh/dark-modules']:
                    for page in ['accounts', 'accounts-catalog', 'voice', 'voice-settings',
                                 'voice-return', 'tasks', 'task-new-chat', 'task-return',
                                 'wechat', 'wallet', 'selfhost', 'selfhost-guide', 'virtual-display']:
                        self.assertIn(f'{prefix}/{page}.xml', files)
                        self.assertEqual(files[f'{prefix}/{page}.png'],
                                         b'FIXTURE ONLY - NOT A DEVICE SCREENSHOT\n')
                self.assertIn(b'MikovaDraft42', files['zh/dark-modules/voice-return.xml'])
                # Both themes' new modules precede the first legacy network page.
                captures = [call for call in trace if call[:3] == ['adb', 'shell', 'input']]
                self.assertFalse(any('KEYCODE_ENTER' in call for call in captures))
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

    def test_missing_required_module_fails_without_fallback(self):
        result, trace, files = self.run_smoke('module_missing')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('entry missing', result.stdout)
        self.assertNotIn('modules/wechat.png', files)
        self.assertIn('settings-entry.xml', files)
        self.assertIn('last-ui.xml', files)
        swipes = [call for call in trace if call[:4] == ['adb', 'shell', 'input', 'swipe']]
        self.assertLessEqual(len(swipes), 22)

    def test_placeholder_page_fails_even_with_correct_title(self):
        result, trace, files = self.run_smoke('placeholder')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('placeholder/design-only UI', result.stderr)
        self.assertNotIn('modules/wechat.png', files)
        self.assertIn(b'WeChat bridge', files['modules/wechat.xml'])
        self.assertIn(b'placeholder', files['modules/wechat.xml'])
        self.assertIn('failure.png', files)

    def test_crash_gate_is_preserved(self):
        result, trace, files = self.run_smoke("crash")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"FATAL EXCEPTION", files["crash.txt"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
