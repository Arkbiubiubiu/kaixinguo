#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Android 发布清单生成器：从 APK 目录一次生成 tv/pad 两份 manifest。

用法：
  python tools/release_manifest.py \
    --dir android/pad/build/outputs/apk \
    --out docs/generated-manifests \
    --version 2.2.189 --code 309 --notes "..."

目录约定：
  tv/release/pad-tv-universal-release.apk
  tv/release/pad-tv-arm64-v8a-release.apk
  tv/release/pad-tv-armeabi-v7a-release.apk
  tv/release/pad-tv-x86_64-release.apk
  pad/release/ 同样四份
"""
import argparse
import hashlib
import json
import os


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def node(path):
    return {"path": os.path.basename(path), "size": os.path.getsize(path), "sha256": sha(path)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--code", type=int, required=True)
    ap.add_argument("--notes", default="")
    ap.add_argument("--url-base", default="https://dl.fabao.xin:44468")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for flavor, prefix, manifest in (
        ("tv", "hongguo-tv", "version-tv.json"),
        ("pad", "hongguo-pad", "version-hgpad.json"),
    ):
        release = os.path.join(a.dir, flavor, "release")
        uni = os.path.join(release, f"pad-{flavor}-universal-release.apk")
        if not os.path.exists(uni):
            raise SystemExit("缺少通用包: " + uni)
        abis = {
            key: node(os.path.join(release, f"pad-{flavor}-{abi}-release.apk"))
            for key, abi in (
                ("arm64-v8a", "arm64-v8a"),
                ("armeabi-v7a", "armeabi-v7a"),
                ("x86_64", "x86_64"),
            )
        }
        for k, v in abis.items():
            v["path"] = f"{prefix}-{a.version}-{ {'arm64-v8a':'arm64','armeabi-v7a':'v7a','x86_64':'x86'}[k] }.apk"
        uni_node = node(uni)
        uni_node["path"] = f"{prefix}-{a.version}.apk"
        data = {
            "versionCode": a.code,
            "versionName": a.version,
            "notes": a.notes,
            "path": uni_node["path"],
            "size": uni_node["size"],
            "sha256": uni_node["sha256"],
            "abis": abis,
        }
        with open(os.path.join(a.out, manifest), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        print(manifest, a.code, a.version, uni_node["size"])


if __name__ == "__main__":
    main()
