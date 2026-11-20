#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""版本一致性门禁（2026-11-16）。

解决的实际事故：代码改完、编译通过，但没有构建/发布 PC 新版，
服务器 version-pc.json 还是旧版，用户在旧版里点「检查更新」显示已是最新。

任何用户可见的修复，完成标准必须包含：
  源码版本 == 服务器清单版本 == 已安装版本（PC）
  源码版本 == 服务器 tv/pad 清单版本（Android）

用法：
  python tools/check_versions.py                  # 双端都查（默认）
  python tools/check_versions.py --platform pc
  python tools/check_versions.py --platform android
  python tools/check_versions.py --no-installed   # 不查本机安装版本

退出码 0 = 一致；1 = 有版本没落地（会列出差在哪）。
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_BASE = os.environ.get("KXG_BASE", "http://192.168.1.54:8080")
INSTALL_DIR = r"D:\Kaixinguo"


def die(msg):
    print("VERSION-FAIL:", msg)
    raise SystemExit(1)


def read_pc_version():
    text = open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8").read()
    m = (re.search(r'val pcVersion\s*=.*?\?:\s*"([^"]+)"', text, re.S)
         or re.search(r'val pcVersion\s*=\s*"([^"]+)"', text))
    if not m:
        die("读不到根 build.gradle.kts 的 pcVersion")
    return m.group(1).strip()


def read_android_version():
    text = open(os.path.join(ROOT, "android", "pad", "build.gradle.kts"), encoding="utf-8").read()
    code = (re.search(r"versionCode\s*=.*?\?:\s*(\d+)", text, re.S)
            or re.search(r"versionCode\s*=\s*(\d+)", text))
    name = (re.search(r'versionName\s*=.*?\?:\s*"([^"]+)"', text, re.S)
            or re.search(r'versionName\s*=\s*"([^"]+)"', text))
    if not code or not name:
        die("读不到 android/pad/build.gradle.kts 的版本号")
    return name.group(1).strip(), int(code.group(1))


def fetch_manifest(base, name):
    url = base.rstrip("/") + "/" + name
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except Exception as exc:
        die("拉不到 %s：%s" % (url, exc))


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_artifacts(base):
    """额外核对 manifest 的 abis/sha256 与本地 dist 产物是否一致；本地无 dist 时只查 abis 数量。
    这是补 check_versions 只比版本号的缺口：sha 错 / ABI 少一个，版本号却照样一致。"""
    bad = []
    try:
        tv = fetch_manifest(base, "version-tv.json")
    except SystemExit:
        return bad
    abis = tv.get("abis") or {}
    if isinstance(abis, dict) and len(abis) < 3:
        bad.append("version-tv.json abis 少于3个(%d)，部分机型可能拿不到包" % len(abis))
    if isinstance(abis, dict):
        ver = str(tv.get("versionName") or "").strip()
        ddir = os.path.join(ROOT, "dist", "android", ver)
        for abi, info in abis.items():
            path = os.path.join(ddir, str(info.get("path") or ""))
            if os.path.isfile(path):
                if _sha256(path) != str(info.get("sha256") or "").lower():
                    bad.append("AND abi %s sha256 不一致：%s" % (abi, path))
    try:
        pc = fetch_manifest(base, "version-pc.json")
    except SystemExit:
        return bad
    pver = str(pc.get("versionName") or "").strip()
    ppath = os.path.join(ROOT, "dist", "pc", pver, str(pc.get("path") or ""))
    if os.path.isfile(ppath) and _sha256(ppath) != str(pc.get("sha256") or "").lower():
        bad.append("PC msi sha256 不一致：%s" % ppath)
    return bad


def installed_pc_version():
    """读本机安装目录里的 exe ProductVersion；路径用字符码拼，避免 PowerShell 中文乱码。"""
    if os.name != "nt" or not os.path.isdir(INSTALL_DIR):
        return None
    ps = (
        "$app=-join([char]0x5F00,[char]0x5FC3,[char]0x679C);"
        "$p=Join-Path (Join-Path 'D:\\Kaixinguo' $app) ($app+'.exe');"
        "if (Test-Path $p) { (Get-Item $p).VersionInfo.ProductVersion } else { '' }"
    )
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps],
            capture_output=True, text=True, timeout=30,
        ).stdout.strip()
        return out or None
    except Exception:
        return None


def check_pc(base, check_installed):
    local = read_pc_version()
    remote = str(fetch_manifest(base, "version-pc.json").get("versionName") or "").strip()
    print("PC  源码=%s 服务器=%s" % (local, remote or "?"))
    bad = []
    if remote != local:
        bad.append("服务器 version-pc.json 还是 %s（源码已是 %s）→ 需要 release.py pc 发布" % (remote or "?", local))
    if check_installed:
        inst = installed_pc_version()
        print("PC  本机安装=%s" % (inst or "未安装/读不到"))
        if inst and inst != local:
            bad.append("本机安装还是 %s（源码已是 %s）→ 需要构建 MSI 并装机" % (inst, local))
    return bad


def check_android(base):
    local_name, local_code = read_android_version()
    bad = []
    for manifest in ("version-tv.json", "version-hgpad.json"):
        d = fetch_manifest(base, manifest)
        rname = str(d.get("versionName") or "").strip()
        rcode = int(d.get("versionCode") or 0)
        print("AND %-18s 源码=%s(%s) 服务器=%s(%s)" % (
            manifest, local_name, local_code, rname or "?", rcode))
        if rname != local_name or rcode != local_code:
            bad.append("%s 还是 %s(%s)（源码已是 %s(%s)）→ 需要 release.py android 发布"
                       % (manifest, rname or "?", rcode, local_name, local_code))
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--platform", choices=["both", "pc", "android"], default="both")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--no-installed", action="store_true")
    a = ap.parse_args()

    bad = []
    if a.platform in ("both", "pc"):
        bad += check_pc(a.base, not a.no_installed)
    if a.platform in ("both", "android"):
        bad += check_android(a.base)
    bad += verify_artifacts(a.base)

    if bad:
        print("\n版本没落地：")
        for b in bad:
            print(" -", b)
        raise SystemExit(1)
    print("\nOK：源码版本、服务器清单、本机安装版本一致")


if __name__ == "__main__":
    main()
