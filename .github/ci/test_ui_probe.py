#!/usr/bin/env python3
"""Check production XML behavior with adversarial, hand-written hierarchies."""
import unittest
from unittest.mock import patch

from ui_probe import ProbeError, Screen, locate, scroll_top, capture


class UiProbeTest(unittest.TestCase):
    def screen(self, nodes):
        return Screen('<hierarchy><node bounds="[0,0][1080,2400]" enabled="true">' + nodes + '</node></hierarchy>')

    def test_compose_merged_description_and_clickable_parent(self):
        screen = self.screen('''<node bounds="[40,180][1040,360]" clickable="true" enabled="true">
          <node content-desc="Mikova WeChat Entry, WeChat channel" bounds="[60,200][300,260]" enabled="true"/>
          <node text="WeChat" bounds="[60,280][300,330]"/>
        </node>''')
        self.assertEqual(screen.lookup('desc', 'Mikova WeChat Entry', True), (180, 230))
        self.assertIsNone(screen.lookup('desc', 'Mibot WeChat Entry', True))

    def test_selected_tab_is_not_an_actionable_button(self):
        screen = self.screen('''<node selected="true" clickable="false" bounds="[845,2096][1048,2248]">
          <node content-desc="Settings" bounds="[845,2096][1048,2248]"/>
        </node>''')
        self.assertTrue(screen.selected('desc', 'Settings'))
        self.assertIsNone(screen.lookup('desc', 'Settings', True))
        self.assertFalse(screen.selected('desc', 'Chats'))

    def test_click_uses_visible_parent_intersection_not_raw_center(self):
        screen = self.screen('''<node scrollable="true" bounds="[0,0][1080,1800]">
          <node clickable="true" bounds="[20,1700][1060,2300]">
            <node content-desc="Below fold" bounds="[20,1700][1060,2300]"/>
          </node>
        </node>''')
        self.assertEqual(screen.lookup('desc', 'Below fold', True), (540, 1750))

    def test_capture_retries_only_dump_and_never_repeats_an_input(self):
        with patch('ui_probe.adb', side_effect=[ProbeError('timeout'), b'', b'<hierarchy/>']) as command, patch('ui_probe.time.sleep'):
            self.assertEqual(capture(), b'<hierarchy/>')
            self.assertEqual(command.call_count, 3)
            self.assertTrue(all('input' not in call.args for call in command.call_args_list))
        with patch('ui_probe.adb', side_effect=ProbeError('timeout')) as command, patch('ui_probe.time.sleep'):
            with self.assertRaises(ProbeError):
                capture()
            self.assertEqual(command.call_count, 2)

    def test_invalid_targets_never_tap(self):
        for attrs in ['enabled="false" bounds="[0,0][100,100]"',
                      'enabled="true" bounds="[0,0][0,100]"',
                      'enabled="true" bounds="[1200,0][1300,100]"',
                      'enabled="true" bounds="[-200,0][-100,100]"',
                      'enabled="true" bounds="[0,2500][100,2600]"',
                      'enabled="true" visible-to-user="false" bounds="[0,0][100,100]"']:
            with self.subTest(attrs=attrs):
                screen = self.screen(f'<node content-desc="Back" clickable="true" {attrs}/>')
                self.assertIsNone(screen.lookup('desc', 'Back', True))

    def test_disabled_ancestor_and_nonclickable_text_are_not_targets(self):
        screen = self.screen('''<node enabled="false" clickable="true" bounds="[0,0][500,500]">
            <node text="Save" enabled="true" bounds="[0,0][100,100]"/>
        </node><node text="Title" bounds="[0,600][200,700]"/>''')
        self.assertIsNone(screen.lookup('text', 'Save', True))
        self.assertIsNone(screen.lookup('text', 'Title', True))

    def test_correct_title_does_not_hide_placeholder(self):
        for bad in ['占位', 'Placeholder', '设计稿']:
            screen = self.screen(f'<node text="WeChat bridge" bounds="[0,0][500,80]"/><node text="{bad}" bounds="[0,100][500,180]"/>')
            with self.assertRaisesRegex(ProbeError, 'placeholder'):
                screen.check(texts=['微信接入|WeChat bridge'])

    def test_wrong_page_and_offscreen_title_fail(self):
        for nodes in ['<node text="Settings" bounds="[0,0][500,80]"/>',
                      '<node text="Wallet" bounds="[0,2500][500,2580]"/>']:
            with self.assertRaisesRegex(ProbeError, 'missing page'):
                self.screen(nodes).check(texts=['钱包|Wallet'])

    def test_visible_chinese_page_with_disabled_controls_can_be_evidence(self):
        self.screen('''<node text="语音设置" bounds="[0,0][500,80]"/>
          <node text="系统识别(兜底)" bounds="[0,100][500,180]" enabled="false"/>
          <node content-desc="Back" bounds="[0,200][50,250]"/>''').check(
              texts=['语音设置', '系统识别(兜底)'], descs=['Back'])

    def test_draft_belongs_to_expected_input(self):
        screen = self.screen('''<node content-desc="Mikova Voice Transcript" text="MikovaDraft42" bounds="[0,0][500,200]"/>
          <node content-desc="Mikova Voice TTS Input" text="other" bounds="[0,220][500,400]"/>''')
        screen.check(draft=('Mikova Voice Transcript', 'MikovaDraft42'))
        with self.assertRaisesRegex(ProbeError, 'draft not retained'):
            screen.check(draft=('Mikova Voice TTS Input', 'MikovaDraft42'))

    def test_draft_semantics_child_reads_only_owning_edit_text(self):
        screen = self.screen('''<node class="android.widget.EditText" text="MikovaDraft42" bounds="[79,1225][1001,1415]">
          <node content-desc="Mikova Voice Transcript" text="" bounds="[79,1225][1001,1415]"/>
        </node><node class="android.widget.EditText" text="" bounds="[79,1768][1001,1958]">
          <node content-desc="Mikova Voice TTS Input" bounds="[79,1768][1001,1958]"/>
        </node>''')
        screen.check(draft=('Mikova Voice Transcript', 'MikovaDraft42'))
        with self.assertRaisesRegex(ProbeError, 'draft not retained'):
            screen.check(draft=('Mikova Voice TTS Input', 'MikovaDraft42'))
        with self.assertRaisesRegex(ProbeError, 'draft not retained'):
            screen.check(draft=('Mikova Voice Transcript', 'wrong value'))

    def test_malformed_and_empty_xml_fail(self):
        for xml in ['not xml', '<hierarchy/>']:
            with self.assertRaises(ProbeError):
                Screen(xml)

    def test_missing_lookup_has_no_nested_retries(self):
        screen = self.screen('<node text="Settings" bounds="[0,0][500,80]"/>')
        with patch('ui_probe.swipe') as gesture:
            with self.assertRaisesRegex(ProbeError, 'not found'):
                locate(screen, lambda: screen, 'desc', 'Missing', 10, True)
            self.assertEqual(gesture.call_count, 1)

    def test_scroll_top_finishes_at_boundary_and_has_hard_cap(self):
        screen = self.screen('<node text="Settings" bounds="[0,0][500,80]"/>')
        with patch('ui_probe.swipe') as gesture:
            self.assertIs(scroll_top(screen, lambda: screen, 12), screen)
            self.assertEqual(gesture.call_count, 1)
        counter = iter(range(20))
        def changing():
            return self.screen(f'<node text="{next(counter)}" bounds="[0,0][500,80]"/>')
        with patch('ui_probe.swipe') as gesture:
            with self.assertRaisesRegex(ProbeError, 'within 3 gestures'):
                scroll_top(screen, changing, 3)
            self.assertEqual(gesture.call_count, 3)


if __name__ == '__main__':
    unittest.main(verbosity=2)
