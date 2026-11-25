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
    PAIR_BASE=...
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
import time
import urllib.error
import urllib.request
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
PAIR_BASE_DEFAULT = os.environ.get("KXG_PAIR_BASE") or "https://dl.fabao.xin:44468"
WEB_BASE = os.environ.get("KXG_WEB_BASE") or "https://kxg.fabao.xin"
MANIFEST_BASE = os.environ.get("KXG_BASE") or "http://192.168.1.54:8080"

PROP_KEYS = (
    ("signUrl", "SIGN_URL"),
    ("playBases", "PLAY_BASES"),
    ("playToken", "PLAY_TOKEN"),
    ("updateBases", "UPDATE_BASES"),
    ("historyBase", "HISTORY_BASE"),
    ("pairBase", "PAIR_BASE"),
    ("deviceIid", "DEVICE_IID"),
    ("deviceId", "DEVICE_ID"),
    ("deviceCdid", "DEVICE_CDID"),
)


def die(msg):
    print("RELEASE-FAIL:", msg)
    raise SystemExit(1)


# ---------------- 分阶段计时（只增加计时输出，不改任何发版逻辑/产物） ----------------
_WALL0 = time.perf_counter()
_PHASE = {}  # 阶段名 -> 累计秒数


class _Timer:
    def __init__(self, name):
        self.name = name

    def __enter__(self):
        self._t = time.perf_counter()
        return self

    def __exit__(self, *exc):
        _PHASE[self.name] = _PHASE.get(self.name, 0.0) + (time.perf_counter() - self._t)
        return False


def timed(name):
    return _Timer(name)


def _sec(name):
    return _PHASE.get(name, 0.0)


def print_timing():
    """打印各阶段耗时明细 + 汇总表 BUILD/UPLOAD/MANIFEST/VERIFY/TOTAL。"""
    detail = " ".join(
        "%s=%.1fs" % (k, v) for k, v in (
            ("bump", _sec("bump")),
            ("build", _sec("build")),
            ("verify_dex", _sec("verify_dex")),
            ("manifest", _sec("manifest")),
            ("upload_scp", _sec("upload_scp")),
            ("verify_sha", _sec("verify_sha")),
            ("prune", _sec("prune")),
            ("publish_github", _sec("publish_github")),
            ("mirror_tag", _sec("mirror_tag")),
            ("desktop", _sec("desktop")),
            ("check_versions", _sec("check_versions")),
            ("notice", _sec("notice")),
            ("dlcheck", _sec("dlcheck")),
        )
    )
    build = _sec("build")
    upload = _sec("upload_scp") + _sec("publish_github") + _sec("mirror_tag")
    manifest = _sec("manifest")
    verify = _sec("verify_dex") + _sec("verify_sha") + _sec("check_versions")
    total = time.perf_counter() - _WALL0
    print("\n[timing] " + detail, flush=True)
    print("[timing] BUILD=%.1fs UPLOAD=%.1fs MANIFEST=%.1fs VERIFY=%.1fs TOTAL=%.1fs"
          % (build, upload, manifest, verify, total), flush=True)


def run_check_versions():
    """可选（--check）：发版后跑版本一致性门禁并计时；只读校验，失败也不影响发版结果。"""
    path = os.path.join(ROOT, "tools", "check_versions.py")
    if not os.path.isfile(path):
        return
    with timed("check_versions"):
        subprocess.run([sys.executable, path, "--no-installed"], check=False)


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
        "--build-cache",
        "--parallel",
        ":pad:assembleTvRelease",
        ":pad:assemblePadRelease",
        "-PversionCode=%d" % code,
        "-PversionName=%s" % version,
    ]
    for k, v in props.items():
        args.append("-P%s=%s" % (k, v))
    with timed("build"):
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
    with timed("manifest"):
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
        args = [GRADLE_PC, "--build-cache", "--parallel", "packageMsi", "-PpcVersion=%s" % version]
        for k, v in props.items():
            args.append("-P%s=%s" % (k, v))
        with timed("build"):
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
    manifest = os.path.join(out, "version-pc.json")
    with timed("manifest"):
        data = {
            "versionCode": code,
            "versionName": version,
            "notes": notes,
            "path": os.path.basename(dst),
            "size": os.path.getsize(dst),
            "sha256": sha256(dst),
        }
        with open(manifest, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    return {os.path.basename(dst): dst}, [manifest]


def upload(files, manifests):
    with timed("upload_scp"):
        for path in list(files.values()) + manifests:
            run(["scp", "-C", path, "%s:%s/" % (SERVER, SERVER_DIR)])
    with timed("verify_sha"):
        for name, path in files.items():
            local = sha256(path)
            p = subprocess.run(
                ["ssh", SERVER, "sha256sum %s/%s" % (SERVER_DIR, os.path.basename(path))],
                capture_output=True, text=True, encoding="utf-8", errors="replace")
            if p.returncode != 0 or local not in p.stdout:
                die("远端校验失败: " + name)
    print("上传并校验通过: %d 个文件" % (len(files) + len(manifests)))


def prune_remote(keep=2):
    """发版后立即在服务器上只保留每个产品最新 N 版安装包，更旧的归档到数据盘。
    服务器上由 ark-prune-pkgs.sh 执行；失败只告警，不影响本次发版结果。"""
    try:
        p = subprocess.run(
            ["ssh", SERVER, "/usr/local/bin/ark-prune-pkgs.sh %d" % keep],
            capture_output=True, text=True, encoding="utf-8", errors="replace")
        msg = (p.stdout or "").strip() or (p.stderr or "").strip()
        if p.returncode == 0:
            print("远端旧包清理:", msg)
        else:
            print("WARN: 远端旧包清理未成功（不影响本次发布）：", msg)
    except Exception as exc:
        print("WARN: 远端旧包清理异常（不影响本次发布）：", exc)


def publish_github(assets):
    """发版后把产物同步到 GitHub Release 并更新服务器 github-release-tag。
    尽力而为：失败只告警，不影响本地/服务器发布（服务器清单已上传即可供更新器使用）。"""
    try:
        try:
            pcv = read_version_pc()[0]
        except Exception:
            pcv = ""
        try:
            andv = read_version_android()[0]
        except Exception:
            andv = ""
        tag = os.environ.get("KXG_RELEASE_TAG") or ("v%s-%s" % (pcv, andv))
        repo = os.environ.get("KXG_GH_REPO", "Arkbiubiubiu/kaixinguo")
        assets = [f for f in assets if os.path.isfile(f)]
        if not assets or shutil.which("gh") is None:
            print("WARN: 跳过 GitHub Release（无资产或未安装 gh）")
            return
        with timed("publish_github"):
            view = subprocess.run(["gh", "release", "view", tag, "--repo", repo], capture_output=True)
            if view.returncode != 0:
                subprocess.run(["gh", "release", "create", tag, "--repo", repo,
                                "--title", tag, "--notes", "发布 " + tag] + assets, check=False)
            else:
                subprocess.run(["gh", "release", "upload", tag, "--repo", repo, "--clobber"] + assets, check=False)
        with timed("mirror_tag"):
            subprocess.run(["ssh", SERVER, "printf '%%s' '%s' > %s/github-release-tag" % (tag, SERVER_DIR)], check=False)
        print("GitHub Release 已同步:", tag)
    except Exception as exc:
        print("WARN: GitHub 发布异常（不影响本次）：", exc)


def update_notice_nonlatest(platform, code):
    """发版后维护 notice.json 的「仅非最新版可见」阈值（幂等、尽力而为、失败不阻断）。
    把 id=update-pc-nonlatest / update-android-nonlatest 的 maxCode 设为 (本次发布 versionCode - 1)：
    最新版(curCode=code) > maxCode 故不会被弹，只有非最新版(curCode<=code-1)才看到「请更新」。
    只改目标 id 的 maxCode 数字，其余字段/其它 notices 原样不动；写回前备份 notice.json.bak-<ts>。"""
    if not code or code <= 0:
        return
    notice_id = "update-pc-nonlatest" if platform == "pc" else "update-android-nonlatest"
    try:
        with timed("notice"):
            p = subprocess.run(["ssh", SERVER, "cat %s/notice.json" % SERVER_DIR],
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
            if p.returncode != 0 or not p.stdout.strip():
                print("WARN: 读 notice.json 失败，跳过阈值维护（不影响发版）")
                return
            raw = p.stdout
            m = re.search(r'"id"\s*:\s*"%s"' % re.escape(notice_id), raw)
            if not m:
                print("WARN: notice.json 未找到 %s，跳过阈值维护（不影响发版）" % notice_id)
                return
            head, seg = raw[:m.end()], raw[m.end():]
            mm = re.search(r'("maxCode"\s*:\s*)(\d+)', seg)
            if not mm:
                print("WARN: %s 未找到 maxCode，跳过阈值维护（不影响发版）" % notice_id)
                return
            cur = int(mm.group(2))
            newv = code - 1
            if cur == newv:
                print("notice 阈值已是最新：%s maxCode=%d（幂等，无需改动）" % (notice_id, cur))
                return
            new_raw = head + seg[:mm.start()] + mm.group(1) + str(newv) + seg[mm.end():]
            ts = time.strftime("%Y%m%d-%H%M%S")
            subprocess.run(["ssh", SERVER, "cp -a %s/notice.json %s/notice.json.bak-%s" % (SERVER_DIR, SERVER_DIR, ts)], check=False)
            tmp = os.path.join(ROOT, "dist", "_notice_upload.json")
            with open(tmp, "w", encoding="utf-8", newline="") as f:
                f.write(new_raw)
            subprocess.run(["scp", "-C", tmp, "%s:%s/notice.json" % (SERVER, SERVER_DIR)], check=False)
            try:
                os.remove(tmp)
            except OSError:
                pass
            print("notice 阈值已更新：%s maxCode %d→%d（备份 notice.json.bak-%s）" % (notice_id, cur, newv, ts))
    except Exception as exc:
        print("WARN: notice 阈值维护异常（不影响发版）：", exc)


def _probe_url(url, timeout=15):
    """HEAD 优先，失败回退 GET（只取响应头，不读全 body），返回 (http_code, content_length)。"""
    last = (-1, -1)
    for method in ("HEAD", "GET"):
        try:
            req = urllib.request.Request(url, method=method, headers={"User-Agent": "kxg-release-check"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                code = getattr(r, "status", None) or r.getcode()
                cl = r.headers.get("Content-Length")
                return int(code), (int(cl) if cl and str(cl).isdigit() else -1)
        except urllib.error.HTTPError as he:
            last = (he.code, -1)
            if method == "GET":
                return last
        except Exception:
            last = (-1, -1)
    return last


def _manifest_size(server_name, local_path):
    """取清单 size：优先服务器 version-*.json（官网/更新器同源），失败回退本地 dist 清单。"""
    try:
        with urllib.request.urlopen(MANIFEST_BASE.rstrip("/") + "/" + server_name, timeout=10) as r:
            return int(json.loads(r.read().decode("utf-8", "replace")).get("size") or 0)
    except Exception:
        pass
    try:
        if os.path.isfile(local_path):
            return int(json.load(open(local_path, encoding="utf-8")).get("size") or 0)
    except Exception:
        pass
    return 0


def _server_version(server_name):
    """取服务器清单 versionName（判断该平台是否已发布到本次目标版本）。"""
    try:
        with urllib.request.urlopen(MANIFEST_BASE.rstrip("/") + "/" + server_name, timeout=10) as r:
            return str(json.loads(r.read().decode("utf-8", "replace")).get("versionName") or "").strip()
    except Exception:
        return ""


def manifest_sizes_for(pc_name, android_name):
    return {
        "pc": _manifest_size("version-pc.json", os.path.join(ROOT, "dist", "pc", pc_name, "version-pc.json")),
        "pad": _manifest_size("version-hgpad.json", os.path.join(ROOT, "dist", "android", android_name, "version-hgpad.json")),
        "tv": _manifest_size("version-tv.json", os.path.join(ROOT, "dist", "android", android_name, "version-tv.json")),
    }


def verify_download_links(pc_name, android_name, manifest_sizes):
    """发版后校验官网三条下载链接（WEB_BASE + /文件名）：HTTP 200 且 Content-Length==清单 size。
    保护：仅当"服务器清单 versionName 已==本次目标版本"时才探测该平台；未发布/未上传的平台 SKIP、不提前探测，
    避免把尚未上传的文件探成 CDN 负缓存 404。2~3 次重试；逐条 OK/FAIL(FAIL 醒目 ERROR)；返回每项 (ok,code,got,exp)。
    不阻断发版：异常只 WARN，不 die。"""
    items = [
        ("PC  msi", "kaixinguo-%s.msi" % pc_name, manifest_sizes.get("pc", 0), "version-pc.json", pc_name),
        ("Pad apk", "hongguo-pad-%s.apk" % android_name, manifest_sizes.get("pad", 0), "version-hgpad.json", android_name),
        ("TV  apk", "hongguo-tv-%s.apk" % android_name, manifest_sizes.get("tv", 0), "version-tv.json", android_name),
    ]
    results = []
    try:
        with timed("dlcheck"):
            print("[dlcheck] 校验官网下载链接（%s）：" % WEB_BASE)
            checked = 0
            ok_n = 0
            skipped = 0
            for label, fname, exp, svname, target in items:
                sv = _server_version(svname)
                if sv and target and sv != target:
                    skipped += 1
                    print("[dlcheck] SKIP  %s %s（服务器清单 versionName=%s≠目标 %s，该平台未发布到本次版本，跳过不探测）" % (label, fname, sv, target))
                    continue
                checked += 1
                url = WEB_BASE.rstrip("/") + "/" + fname
                code, got = -1, -1
                for _attempt in range(3):
                    code, got = _probe_url(url)
                    if code == 200 and exp > 0 and got == exp:
                        break
                    time.sleep(1.0)
                ok = (code == 200 and exp > 0 and got == exp)
                results.append((ok, code, got, exp))
                if ok:
                    ok_n += 1
                    print("[dlcheck] OK    %s %s  %d bytes" % (label, fname, got))
                else:
                    print("[dlcheck] ERROR %s %s  HTTP=%s Content-Length=%s 期望=%s  <== 官网下载链接异常，请查 /get/、静态文件、CDN！" % (label, fname, code, got, exp))
            if checked == 0:
                print("[dlcheck] 汇总：本次无可校验项（各平台清单均未到达目标版本，已全部 SKIP，不探测以免污染 CDN）")
            elif ok_n == checked:
                print("[dlcheck] 汇总：%d/%d OK (all pass)%s" % (ok_n, checked, ("，另 %d 项 SKIP(未发布)" % skipped) if skipped else ""))
            else:
                print("[dlcheck] 汇总：%d/%d OK，%d SKIP —— 有 FAIL（见上面 ERROR 行），发版继续但请立即排查！" % (ok_n, checked, skipped))
    except Exception as exc:
        print("[dlcheck] WARN: 官网下载链接校验异常（不影响发版返回码）：", exc)
    return results


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
        with timed("bump"):
            write_version_android(version, code)
    props = load_props(a.env)
    props.setdefault("pairBase", PAIR_BASE_DEFAULT)
    missing = [p for p, _ in PROP_KEYS if p in ("playToken", "playBases", "updateBases", "signUrl", "historyBase", "pairBase") and not props.get(p)]
    if missing:
        die("缺少私参 %s；请在 %s 里配置（参考文件头注释）" % (",".join(missing), a.env))
    if getattr(a, "no_sign_url", False):
        props.pop("signUrl", None)
        print("按要求剔除 signUrl：本次构建 BuildDefaults.SIGN_URL 为空（每机唯一本地签名版）")
    if not a.no_build:
        build_android(version, code, props)
    with timed("verify_dex"):
        for flavor in ("tv", "pad"):
            verify_dex(apk_path(flavor), props.get("playToken"))
    print("APK 私参校验通过（playToken 在 dex 里）")
    files, manifests = stage_android(version, code, a.notes, a.url_base)
    print("产物目录:", os.path.join(ROOT, "dist", "android", version))
    if not a.no_upload:
        upload(files, manifests)
        with timed("prune"):
            prune_remote()
        publish_github(list(files.values()) + manifests)
        update_notice_nonlatest("android", code)
        _pcv = read_version_pc()[0]
        verify_download_links(_pcv, version, manifest_sizes_for(_pcv, version))
    if a.desktop_copy:
        with timed("desktop"):
            copy_to_desktop(version)
    if getattr(a, "check", False):
        run_check_versions()
    print_timing()


def cmd_pc(a):
    cur_name, _ = read_version_pc()
    version = a.version or (bump_version(cur_name) if a.bump else cur_name)
    code = sum(int(x) * m for x, m in zip(version.split("."), (10000, 100, 1)))
    if a.bump:
        with timed("bump"):
            write_version_pc(version)
    props = load_props(a.env)
    props.setdefault("pairBase", PAIR_BASE_DEFAULT)
    missing = [p for p, _ in PROP_KEYS if p in ("playToken", "playBases", "updateBases", "signUrl", "historyBase", "pairBase") and not props.get(p)]
    if missing:
        die("缺少私参 %s；请在 %s 里配置（参考文件头注释）" % (",".join(missing), a.env))
    if getattr(a, "no_sign_url", False):
        props.pop("signUrl", None)
        print("按要求剔除 signUrl：本次构建 BuildDefaults.SIGN_URL 为空（每机唯一本地签名版）")
    files, manifests = stage_pc(version, a.notes, props, no_build=a.no_build)
    print("产物目录:", os.path.join(ROOT, "dist", "pc", version))
    if not a.no_upload:
        upload(files, manifests)
        with timed("prune"):
            prune_remote()
        publish_github(list(files.values()) + manifests)
        update_notice_nonlatest("pc", code)
        _andv = read_version_android()[0]
        verify_download_links(version, _andv, manifest_sizes_for(version, _andv))
    if getattr(a, "check", False):
        run_check_versions()
    print_timing()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform", choices=["android", "pc"])
    ap.add_argument("--version")
    ap.add_argument("--code", type=int)
    ap.add_argument("--bump", action="store_true")
    ap.add_argument("--notes", default="")
    ap.add_argument("--notes-file", dest="notes_file", default=None,
                    help="从 UTF-8 文件读取更新说明(notes)，优先于 --notes；避免中文/引号经命令行传参丢失")
    ap.add_argument("--env", default=DEFAULT_ENV)
    ap.add_argument("--url-base", default="https://dl.fabao.xin:44468")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--no-upload", action="store_true")
    ap.add_argument("--no-desktop-copy", dest="desktop_copy", action="store_false")
    ap.add_argument("--check", action="store_true", help="发版后跑 check_versions.py --no-installed 并计时（只读）")
    ap.add_argument("--no-sign-url", dest="no_sign_url", action="store_true",
                    help="构建时不传 -PsignUrl，使 BuildDefaults.SIGN_URL 为空（每机唯一本地签名版；PLAY_BASES/HISTORY_BASE/PLAY_TOKEN 仍保留）")
    ap.set_defaults(desktop_copy=True)
    a = ap.parse_args()
    if getattr(a, "notes_file", None):
        a.notes = open(a.notes_file, encoding="utf-8").read().strip()
    global _WALL0
    _WALL0 = time.perf_counter()
    if a.platform == "android":
        cmd_android(a)
    else:
        cmd_pc(a)


if __name__ == "__main__":
    main()
