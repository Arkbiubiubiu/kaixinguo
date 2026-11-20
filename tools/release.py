#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开心果正式发布流水线（Phase 9）。

把「改版本号 → 构建 → 校验私参 → 生成清单 → 上传 → 远端校验 → 桌面留包」
串成一条命令，避免每次发版靠手敲 Gradle/scp 出错（2.2.88 漏传私参事故）。

用法（Windows Git Bash）：

  # 安卓 TV + Pad 全量（版本号自动 +1）
  python tools/release.py android --bump --notes "本次改动说明"

  # 只构建不上传
  python tools/release.py android --version 2.2.190 --code 310 --no-upload

  # PC MSI
  python tools/release.py pc --bump --notes "..."

私参从本地文件读取（不入库）：
  默认 D:/kaixinguo-backup/private/release.env，可用 --env 指定。
  格式每行 KEY=VALUE：
    SIGN_URL=...
    PLAY_BASES=...
    PLAY_TOKEN=...
    UPDATE_BASES=...
    HISTORY_BASE=...
    DEVICE_IID=...
    DEVICE_ID=...
    DEVICE_CDID=...
  也可用同名环境变量（KXG_SIGN_URL 等），文件优先。

安全约束：
  - 私参只写进构建参数和 BuildConfig，不打印、不入库；
  - 校验 APK 的 dex 里确实含 playToken（漏传参数直接失败）；
  - 上传后从服务器读 sha256 与本地比对。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, "android")
SDK = os.environ.get("ANDROID_HOME") or "D:/AndroidDev/sdk"
JDK = os.environ.get("JAVA_HOME") or "D:/Tools/jdk-21.0.12.1+1"
GRADLE_ANDROID = os.environ.get("KXG_GRADLE_ANDROID") or "D:/AndroidDev/gradle-8.14.3/bin/gradle.bat"
GRADLE_PC = os.environ.get("KXG_GRADLE_PC") or "D:/AndroidDev/gradle-8.7/bin/gradle.bat"
SERVER = os.environ.get("KXG_RELEASE_SERVER") or "root@192.168.1.54"
SERVER_DIR = "/srv/hgtv-update"
DESKTOP_APK = os.path.join(os.path.expanduser("~"), "Desktop", "开心果apk")
DEFAULT_ENV = os.environ.get("KXG_RELEASE_ENV") or "D:/kaixinguo-backup/private/release.env"

PROP_KEYS = (
    ("signUrl", "SIGN_URL"),
    ("playBases", "PLAY_BASES"),
    ("playToken", "PLAY_TOKEN"),
    ("updateBases", "UPDATE_BASES"),
    ("historyBase", "HISTORY_BASE"),
    ("deviceIid", "DEVICE_IID"),
    ("deviceId", "DEVICE_ID"),
    ("deviceCdid", "DEVICE_CDID"),
)


def die(msg):
    print("RELEASE-FAIL:", msg)
    raise SystemExit(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_version_android():
    text = open(os.path.join(ANDROID, "pad", "build.gradle.kts"), encoding="utf-8").read()
    code = (re.search(r"versionCode\s*=.*?\?:\s*(\d+)", text, re.S)
            or re.search(r"versionCode\s*=\s*(\d+)", text))
    name = (re.search(r'versionName\s*=.*?\?:\s*"([^"]+)"', text, re.S)
            or re.search(r'versionName\s*=\s*"([^"]+)"', text))
    if not code or not name:
        die("读不到 android/pad/build.gradle.kts 的版本号")
    return name.group(1), int(code.group(1))


def bump_version(name):
    parts = name.split(".")
    if len(parts) != 3:
        die("版本号必须是 x.y.z: " + name)
    parts[2] = str(int(parts[2]) + 1)
    return ".".join(parts)


def write_version_android(version, code):
    path = os.path.join(ANDROID, "pad", "build.gradle.kts")
    text = open(path, encoding="utf-8").read()
    text2 = re.sub(r"(versionCode\s*=.*?\?:\s*)\d+", r"\g<1>%d" % code, text, count=1, flags=re.S)
    text2 = re.sub(r'(versionName\s*=.*?\?:\s*)"[^"]+"', r'\g<1>"%s"' % version, text2, count=1, flags=re.S)
    if text2 == text:
        die("版本号没有写入（build.gradle.kts 结构变了）")
    open(path, "w", encoding="utf-8").write(text2)
    print("版本号已写入 android/pad/build.gradle.kts:", version, code)


def read_version_pc():
    text = open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8").read()
    m = (re.search(r'val pcVersion\s*=.*?\?:\s*"([^"]+)"', text, re.S)
         or re.search(r'val pcVersion\s*=\s*"([^"]+)"', text))
    if not m:
        die("读不到根 build.gradle.kts 的 pcVersion")
    name = m.group(1)
    a, b, c = (int(x) for x in name.split("."))
    return name, a * 10000 + b * 100 + c


def write_version_pc(version):
    path = os.path.join(ROOT, "build.gradle.kts")
    text = open(path, encoding="utf-8").read()
    text2 = re.sub(r'(val pcVersion\s*=.*?\?:\s*)"[^"]+"', r'\g<1>"%s"' % version, text, count=1, flags=re.S)
    if text2 == text:
        die("pcVersion 没有写入（build.gradle.kts 结构变了）")
    open(path, "w", encoding="utf-8").write(text2)
    print("pcVersion 已写入 build.gradle.kts:", version)


def load_props(env_path):
    vals = {}
    if env_path and os.path.isfile(env_path):
        for line in open(env_path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            vals[k.strip()] = v.strip().strip('"').strip("'")
    out = {}
    for prop, key in PROP_KEYS:
        v = vals.get(key) or os.environ.get("KXG_" + key) or ""
        if v:
            out[prop] = v
    return out


def run(cmd, cwd=None, env=None):
    print("+ " + " ".join(str(c) for c in cmd), flush=True)
    e = dict(os.environ)
    e.update(env or {})
    p = subprocess.run(cmd, cwd=cwd, env=e)
    if p.returncode != 0:
        die("命令失败（%d）：%s" % (p.returncode, cmd[0]))


def build_android(version, code, props):
    args = [
        GRADLE_ANDROID,
        ":pad:assembleTvRelease",
        ":pad:assemblePadRelease",
        "-PversionCode=%d" % code,
        "-PversionName=%s" % version,
    ]
    for k, v in props.items():
        args.append("-P%s=%s" % (k, v))
    run(args, cwd=ANDROID, env={"JAVA_HOME": JDK, "ANDROID_HOME": SDK})


def apk_path(flavor, abi=None):
    name = "pad-%s-%s-release.apk" % (flavor, abi or "universal")
    return os.path.join(ANDROID, "pad", "build", "outputs", "apk", flavor, "release", name)


def verify_dex(apk, token):
    if not token:
        die("没有 playToken，拒绝发布（客户端会取流失败）")
    try:
        with zipfile.ZipFile(apk) as z:
            for n in z.namelist():
                if re.match(r"classes\d*\.dex$", n):
                    if token.encode("utf-8") in z.read(n):
                        return
    except Exception as exc:
        die("读 APK 失败 %s: %s" % (apk, exc))
    die("APK 的 dex 里找不到 playToken，说明构建漏传私参：" + apk)


def stage_android(version, code, notes, url_base):
    out = os.path.join(ROOT, "dist", "android", version)
    stage = os.path.join(out, "manifest-src")
    os.makedirs(out, exist_ok=True)
    files = {}
    for flavor, prefix in (("tv", "hongguo-tv"), ("pad", "hongguo-pad")):
        uni = apk_path(flavor)
        if not os.path.isfile(uni):
            die("缺少构建产物: " + uni)
        os.makedirs(os.path.join(stage, flavor, "release"), exist_ok=True)
        shutil.copy2(uni, os.path.join(stage, flavor, "release",
                                       "pad-%s-universal-release.apk" % flavor))
        uni_dst = os.path.join(out, "%s-%s.apk" % (prefix, version))
        shutil.copy2(uni, uni_dst)
        files[os.path.basename(uni_dst)] = uni_dst
        for abi, tag in (("arm64-v8a", "arm64"), ("armeabi-v7a", "v7a"), ("x86_64", "x86")):
            src = apk_path(flavor, abi)
            if not os.path.isfile(src):
                die("缺少 ABI 包: " + src)
            shutil.copy2(src, os.path.join(stage, flavor, "release",
                                           "pad-%s-%s-release.apk" % (flavor, abi)))
            dst = os.path.join(out, "%s-%s-%s.apk" % (prefix, version, tag))
            shutil.copy2(src, dst)
            files[os.path.basename(dst)] = dst
    run([sys.executable, os.path.join(ROOT, "tools", "release_manifest.py"),
         "--dir", stage, "--out", out, "--version", version,
         "--code", str(code), "--notes", notes, "--url-base", url_base])
    manifests = [os.path.join(out, "version-tv.json"), os.path.join(out, "version-hgpad.json")]
    for m in manifests:
        if not os.path.isfile(m):
            die("清单未生成: " + m)
    shutil.rmtree(stage, ignore_errors=True)
    return files, manifests


def build_pc(version, props, no_build=False):
    if not no_build:
        args = [GRADLE_PC, "packageMsi", "-PpcVersion=%s" % version]
        for k, v in props.items():
            args.append("-P%s=%s" % (k, v))
        run(args, cwd=ROOT, env={"JAVA_HOME": JDK})
    msi_dir = os.path.join(ROOT, "build", "compose", "binaries", "main", "msi")
    if not os.path.isdir(msi_dir):
        die("找不到 MSI 输出目录: " + msi_dir)
    names = [n for n in os.listdir(msi_dir) if n.endswith(".msi")]
    if not names:
        die("MSI 输出为空: " + msi_dir)
    names.sort(key=lambda n: os.path.getmtime(os.path.join(msi_dir, n)))
    return os.path.join(msi_dir, names[-1])


def stage_pc(version, notes, props=None, no_build=False):
    src = build_pc(version, props or {}, no_build)
    out = os.path.join(ROOT, "dist", "pc", version)
    os.makedirs(out, exist_ok=True)
    dst = os.path.join(out, "kaixinguo-%s.msi" % version)
    shutil.copy2(src, dst)
    code = sum(int(x) * m for x, m in zip(version.split("."), (10000, 100, 1)))
    data = {
        "versionCode": code,
        "versionName": version,
        "notes": notes,
        "path": os.path.basename(dst),
        "size": os.path.getsize(dst),
        "sha256": sha256(dst),
    }
    manifest = os.path.join(out, "version-pc.json")
    with open(manifest, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    return {os.path.basename(dst): dst}, [manifest]


def upload(files, manifests):
    for path in list(files.values()) + manifests:
        run(["scp", "-C", path, "%s:%s/" % (SERVER, SERVER_DIR)])
    for name, path in files.items():
        local = sha256(path)
        p = subprocess.run(
            ["ssh", SERVER, "sha256sum %s/%s" % (SERVER_DIR, os.path.basename(path))],
            capture_output=True, text=True)
        if p.returncode != 0 or local not in p.stdout:
            die("远端校验失败: " + name)
    print("上传并校验通过: %d 个文件" % (len(files) + len(manifests)))


def prune_remote(keep=2):
    """发版后立即在服务器上只保留每个产品最新 N 版安装包，更旧的归档到数据盘。
    服务器上由 ark-prune-pkgs.sh 执行；失败只告警，不影响本次发版结果。"""
    try:
        p = subprocess.run(
            ["ssh", SERVER, "/usr/local/bin/ark-prune-pkgs.sh %d" % keep],
            capture_output=True, text=True)
        msg = (p.stdout or "").strip() or (p.stderr or "").strip()
        if p.returncode == 0:
            print("远端旧包清理:", msg)
        else:
            print("WARN: 远端旧包清理未成功（不影响本次发布）：", msg)
    except Exception as exc:
        print("WARN: 远端旧包清理异常（不影响本次发布）：", exc)


def copy_to_desktop(version):
    os.makedirs(DESKTOP_APK, exist_ok=True)
    keep = {
        "hongguo-tv-%s.apk" % version: os.path.join(ROOT, "dist", "android", version, "hongguo-tv-%s.apk" % version),
        "hongguo-pad-%s.apk" % version: os.path.join(ROOT, "dist", "android", version, "hongguo-pad-%s.apk" % version),
    }
    for name, src in keep.items():
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(DESKTOP_APK, name))
    for name in os.listdir(DESKTOP_APK):
        if name.lower().endswith(".apk") and name not in keep:
            try:
                os.remove(os.path.join(DESKTOP_APK, name))
            except OSError:
                pass
    print("桌面 APK 已更新为", version, "（只留 TV/pad 两个通用包）")


def cmd_android(a):
    cur_name, cur_code = read_version_android()
    version = a.version or (bump_version(cur_name) if a.bump else cur_name)
    code = a.code or (cur_code + 1 if a.bump else cur_code)
    if a.bump:
        write_version_android(version, code)
    props = load_props(a.env)
    missing = [p for p, _ in PROP_KEYS if p in ("playToken", "playBases", "updateBases") and not props.get(p)]
    if missing:
        die("缺少私参 %s；请在 %s 里配置（参考文件头注释）" % (",".join(missing), a.env))
    if not a.no_build:
        build_android(version, code, props)
    for flavor in ("tv", "pad"):
        verify_dex(apk_path(flavor), props.get("playToken"))
    print("APK 私参校验通过（playToken 在 dex 里）")
    files, manifests = stage_android(version, code, a.notes, a.url_base)
    print("产物目录:", os.path.join(ROOT, "dist", "android", version))
    if not a.no_upload:
        upload(files, manifests)
        prune_remote()
    if a.desktop_copy:
        copy_to_desktop(version)


def cmd_pc(a):
    cur_name, _ = read_version_pc()
    version = a.version or (bump_version(cur_name) if a.bump else cur_name)
    if a.bump:
        write_version_pc(version)
    props = load_props(a.env)
    files, manifests = stage_pc(version, a.notes, props, no_build=a.no_build)
    print("产物目录:", os.path.join(ROOT, "dist", "pc", version))
    if not a.no_upload:
        upload(files, manifests)
        prune_remote()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform", choices=["android", "pc"])
    ap.add_argument("--version")
    ap.add_argument("--code", type=int)
    ap.add_argument("--bump", action="store_true")
    ap.add_argument("--notes", default="")
    ap.add_argument("--env", default=DEFAULT_ENV)
    ap.add_argument("--url-base", default="https://dl.fabao.xin:44468")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-upload", action="store_true")
    ap.add_argument("--no-desktop-copy", dest="desktop_copy", action="store_false")
    ap.set_defaults(desktop_copy=True)
    a = ap.parse_args()
    if a.platform == "android":
        cmd_android(a)
    else:
        cmd_pc(a)


if __name__ == "__main__":
    main()
