#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""双副本一致性守门：比对 PC 与 Android 的 Api.kt / Playback.kt。

这两份是"物理副本"（按 AGENTS 规则只允许三处平台差异 + 清理噪音）。忽略空白后
差异若超过阈值，说明可能是一端改了另一端没跟上 —— 报红，提醒人工核对/同步。

用法：python tools/check_api_parity.py   退出码 0=OK，1=超阈值
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PAIRS = [
    ("Api.kt",
     os.path.join(ROOT, "src/main/kotlin/com/hongguotv/pad/Api.kt"),
     os.path.join(ROOT, "android/core/src/main/kotlin/com/hongguotv/pad/Api.kt"),
     340),
    ("Playback.kt",
     os.path.join(ROOT, "src/main/kotlin/com/hongguotv/pad/Playback.kt"),
     os.path.join(ROOT, "android/core/src/main/kotlin/com/hongguotv/pad/Playback.kt"),
     20),
]


def diff_lines(a, b):
    r = subprocess.run(
        ["git", "--no-pager", "diff", "--no-index",
         "--ignore-all-space", "--ignore-blank-lines", "--numstat", "--", a, b],
        capture_output=True, text=True)
    out = (r.stdout or "").strip()
    if not out:
        return 0
    first = out.splitlines()[0]
    parts = first.split("\t")
    try:
        return int(parts[0]) + int(parts[1])
    except Exception:
        return 0


def main():
    bad = False
    for name, a, b, thresh in PAIRS:
        if not (os.path.isfile(a) and os.path.isfile(b)):
            print("SKIP %s（缺文件）" % name)
            continue
        n = diff_lines(a, b)
        ok = n <= thresh
        print("%-13s 忽略空白差异 %4d 行 (阈值 %d) -> %s" % (name, n, thresh, "OK" if ok else "FAIL"))
        if not ok:
            bad = True
    if bad:
        print("FAIL: 双副本差异超阈值，可能一端改了另一端没跟上，请人工核对后再提交。")
        sys.exit(1)
    print("OK: 双副本差异在允许范围（搬家/注释/换行噪音内）。")


if __name__ == "__main__":
    main()
