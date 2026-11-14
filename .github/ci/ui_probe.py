#!/usr/bin/env python3
"""Bounded UIAutomator lookup and evidence checks; no app or network fallback."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


class ProbeError(RuntimeError):
    pass


def bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else (0, 0, 0, 0)


def normalize(value):
    return " ".join(value.casefold().split())


class Screen:
    def __init__(self, xml, size=(1080, 2400)):
        try:
            self.root = ET.fromstring(xml)
        except ET.ParseError as exc:
            raise ProbeError(f"invalid UI XML: {exc}") from exc
        self.nodes = list(self.root.iter("node"))
        self.parents = {child: parent for parent in self.root.iter() for child in parent}
        self.size = size
        if not self.nodes:
            raise ProbeError("empty UI hierarchy")

    def visible_bounds(self, node, enabled=False):
        x1, y1, x2, y2 = bounds(node)
        x1, y1 = max(x1, 0), max(y1, 0)
        x2, y2 = min(x2, self.size[0]), min(y2, self.size[1])
        current = node
        while current is not None:
            if current.get('visible-to-user') == 'false' or (enabled and current.get('enabled') == 'false'):
                return None
            if current is not node and current.tag == 'node':
                a, b, c, d = bounds(current)
                if c > a and d > b:
                    x1, y1, x2, y2 = max(x1, a), max(y1, b), min(x2, c), min(y2, d)
            current = self.parents.get(current)
        return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None

    def visible(self, node, enabled=False):
        return self.visible_bounds(node, enabled) is not None

    def selected(self, kind, value):
        for node in self.nodes:
            if not self.visible(node, enabled=True) or not self.matches(node, kind, value):
                continue
            current = node
            while current is not None:
                if current.get('selected') == 'true':
                    return True
                current = self.parents.get(current)
        return False

    def matches(self, node, kind, value):
        attr = "content-desc" if kind == "desc" else "text"
        own = normalize(node.get(attr, ""))
        wanted = normalize(value)
        if own == wanted:
            return True
        # Compose rows can merge their title/subtitle into the semantic label.
        # Require a description anchor; never match an arbitrary root subtree.
        return kind == "desc" and bool(own) and all(token in re.findall(r"\w+", own) for token in re.findall(r"\w+", wanted))

    def lookup(self, kind, value, clickable=False):
        for node in self.nodes:
            if not self.visible(node, enabled=clickable) or not self.matches(node, kind, value):
                continue
            target = node
            if clickable:
                while target is not None and target.get("clickable") != "true":
                    target = self.parents.get(target)
                if target is None or not self.visible(target, enabled=True):
                    continue
                # Tap inside the matched child AND its actual clickable parent.
                a, b, c, d = self.visible_bounds(node, enabled=True)
                e, f, g, h = self.visible_bounds(target, enabled=True)
                x1, y1 = max(a, e, 0), max(b, f, 0)
                x2, y2 = min(c, g, self.size[0]), min(d, h, self.size[1])
                if x2 <= x1 or y2 <= y1:
                    continue
                return ((x1 + x2) // 2, (y1 + y2) // 2)
            return node
        return None

    def scroll_bounds(self):
        candidates = [n for n in self.nodes if n.get("scrollable") == "true" and self.visible(n, enabled=True)]
        if not candidates:
            raise ProbeError("no visible scroll container")
        node = max(candidates, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
        a, b, c, d = bounds(node)
        return max(a, 0), max(b, 0), min(c, self.size[0]), min(d, self.size[1])

    def fingerprint(self):
        return [(n.get("text"), n.get("content-desc"), n.get("bounds")) for n in self.nodes if self.visible(n)]

    def check(self, texts=(), descs=(), draft=None, expanded_from=None):
        # Inspect UI content, not attribute names (EditText hints are legitimate).
        for node in self.nodes:
            content = node.get("text", "") + " " + node.get("content-desc", "")
            if re.search(r"占位|placeholder|设计稿", content, re.I):
                raise ProbeError("placeholder/design-only UI found")
        for kind, groups in (("text", texts), ("desc", descs)):
            for alternatives in groups:
                if not any(self.lookup(kind, value) is not None for value in alternatives.split("|")):
                    raise ProbeError(f"missing page {kind}: {alternatives}")
        if expanded_from:
            before = Screen(Path(expanded_from).read_bytes(), self.size)
            old = {n.get('text', '') for n in before.nodes}
            added = [n.get('text', '') for n in self.nodes if self.visible(n) and n.get('text', '') not in old]
            if not any(len(text.strip()) >= 20 for text in added):
                raise ProbeError('expanded guide has no new visible explanatory content')
        if draft:
            label, expected = draft
            node = self.lookup("desc", label)
            if node is None or not any(n.get("text") == expected for n in node.iter("node")):
                raise ProbeError(f"draft not retained in {label}")


def adb(*args):
    try:
        result = subprocess.run(["adb", *args], capture_output=True, timeout=15, check=True)
    except (subprocess.SubprocessError, OSError) as exc:
        raise ProbeError(f"adb failed: {' '.join(args)}: {exc}") from exc
    return result.stdout


def capture():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("exec-out", "cat", "/sdcard/ui.xml")


def swipe(screen, direction):
    a, b, c, d = screen.scroll_bounds()
    x = (a + c) // 2
    low, high = b + (d - b) // 5, b + (d - b) * 4 // 5
    start, end = (low, high) if direction == "top" else (high, low)
    adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "300")
    # Let Compose settle once per gesture; lookup itself has no nested retries.
    time.sleep(0.25)


def scroll_top(screen, refresh, limit):
    for _ in range(limit):
        swipe(screen, "top")
        after = refresh()
        if screen.fingerprint() == after.fingerprint():
            return after
        screen = after
    raise ProbeError(f"scroll-to-top did not settle within {limit} gestures")


def locate(screen, refresh, kind, value, limit, click):
    for attempt in range(limit + 1):
        found = screen.lookup(kind, value, clickable=click)
        if found is not None:
            return found
        if attempt == limit:
            break
        swipe(screen, "down")
        after = refresh()
        if screen.fingerprint() == after.fingerprint():
            break
        screen = after
    raise ProbeError(f"not found: {kind}={value!r} (at most {limit} scrolls)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("tap", "check", "top", "seek", "select"))
    parser.add_argument("kind", nargs="?", choices=("desc", "text"))
    parser.add_argument("value", nargs="?")
    parser.add_argument("--xml", type=Path, help="check a saved hierarchy without adb")
    parser.add_argument("--save", type=Path)
    parser.add_argument("--text", action="append", default=[])
    parser.add_argument("--desc", action="append", default=[])
    parser.add_argument("--draft", nargs=2, metavar=("LABEL", "VALUE"))
    parser.add_argument("--expanded-from", type=Path)
    parser.add_argument("--top", action="store_true")
    parser.add_argument("--scrolls", type=int, default=0)
    args = parser.parse_args()
    if not 0 <= args.scrolls <= 12:
        parser.error("--scrolls must be in 0..12")
    if args.action in ('tap', 'seek', 'select') and (not args.kind or not args.value):
        parser.error('tap/seek/select requires kind and value')
    if args.xml and args.action != "check":
        parser.error("--xml is only for check")
    size = (1080, 2400)
    if not args.xml:
        sizes = re.findall(rb"(\d+)x(\d+)", adb("shell", "wm", "size"))
        if not sizes:
            raise ProbeError("cannot determine display bounds")
        size = tuple(map(int, sizes[-1]))

    def refresh():
        xml = args.xml.read_bytes() if args.xml else capture()
        if args.save:
            args.save.parent.mkdir(parents=True, exist_ok=True)
            args.save.write_bytes(xml)
        return Screen(xml, size)

    screen = refresh()
    if args.top or args.action == "top":
        screen = scroll_top(screen, refresh, 12)
    if args.action == 'select' and screen.selected(args.kind, args.value):
        print(f'already selected: {args.value}')
        return
    if args.action in ('tap', 'seek', 'select'):
        target = locate(screen, refresh, args.kind, args.value, args.scrolls, args.action != 'seek')
        if args.action in ('tap', 'select'):
            adb("shell", "input", "tap", str(target[0]), str(target[1]))
        print(f"{args.action}: {args.kind}={args.value}")
    elif args.action == "check":
        screen.check(args.text, args.desc, args.draft, args.expanded_from)
        print("UI XML assertions passed")


if __name__ == "__main__":
    try:
        main()
    except ProbeError as error:
        print(f"UI probe: {error}", file=sys.stderr)
        sys.exit(1)
