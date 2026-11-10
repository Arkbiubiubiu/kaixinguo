#!/bin/bash
# 开心果发版一条龙：bump 版本号 → 构建 → 签名 → 部署 → 生成更新清单
#
# 用法：
#   release.sh "更新说明"        正常发版（版本号 +1）
#   release.sh --resume "说明"   版本号已改过，直接构建/部署/写清单
#
# 注意：脚本用 set -euo pipefail，所有「命令 | head」式管道都会因 head 提前退出
# 触发 SIGPIPE 而误杀脚本 —— 一律先取进变量再打印。
set -euo pipefail
cd /opt/hongguo-redo

MODE=normal
if [ "${1:-}" = "--resume" ]; then MODE=resume; shift; fi
NOTES="${1:-}"
[ -z "$NOTES" ] && { echo "用法: release.sh [--resume] \"更新说明\""; exit 1; }

GRADLE=/opt/gradle/bin/gradle
BT=/opt/android-sdk/build-tools/35.0.0
UP=/srv/hgtv-update

# ---------- 1) 版本号 ----------
VC=$(grep -o 'versionCode = [0-9]*' pad/build.gradle.kts | head -1 | grep -o '[0-9]*' || true)
VN=$(grep -o 'versionName = "[0-9.]*"' pad/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/' || true)
if [ "$MODE" = "normal" ]; then
    VC2=$((VC + 1))
    VN2=$(python3 - "$VN" <<'PYEOF'
import sys
v = sys.argv[1].split('.')
v[-1] = str(int(v[-1]) + 1)
print('.'.join(v))
PYEOF
)
    sed -i "s/versionCode = $VC/versionCode = $VC2/; s/versionName = \"$VN\"/versionName = \"$VN2\"/" pad/build.gradle.kts
else
    VC2=$VC; VN2=$VN
fi
echo "== 版本：$VC2/$VN2"

# ---------- 2) 构建 ----------
# 服务器地址不再写死在源码里，只能靠构建参数注入：
#   - 自用发版（本脚本）必须带这五个参数，否则装到电视上会变成「要手动填签名服务」
#   - GitHub 上发布的公开包故意不带，源码和安装包里都不含任何地址
SERVER_ARGS="-PsignUrl=http://192.168.1.54:9100"
SERVER_ARGS="$SERVER_ARGS -PplayBases=http://192.168.1.54:8080,https://hgtv.fabao.xin"
SERVER_ARGS="$SERVER_ARGS -PplayToken=hgtv-play-2026"
SERVER_ARGS="$SERVER_ARGS -PupdateBases=http://192.168.1.54:8080,https://hgtv.fabao.xin"
SERVER_ARGS="$SERVER_ARGS -PhistoryBase=https://hgtv.fabao.xin"

S=$(date +%s)
OUT=$($GRADLE :pad:assembleTvRelease :pad:assemblePadDebug $SERVER_ARGS 2>&1) || { echo "$OUT" | grep -E '^e:|BUILD' | head -10; echo "!! 构建失败"; exit 1; }
BUILD_LINE=$(echo "$OUT" | grep -E 'BUILD' | head -1)
echo "== 构建：$BUILD_LINE（$(( $(date +%s) - S )) 秒）"

# ---------- 3) 签名 ----------
cd pad/build/outputs/apk/tv/release
$BT/zipalign -f -p 4 pad-tv-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks /root/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey --out signed.apk aligned.apk
CERT=$($BT/apksigner verify --print-certs signed.apk | grep -m1 'SHA-256 digest' || true)
BADGE=$($BT/aapt2 dump badging signed.apk | grep -m1 'package: name' || true)
echo "== 签名：$CERT"
echo "== 包：$BADGE"
cd /opt/hongguo-redo

# ---------- 4) 部署 ----------
cp pad/build/outputs/apk/tv/release/signed.apk "$UP/app-tv-release.apk"
cp pad/build/outputs/apk/pad/debug/pad-pad-debug.apk "$UP/hongguo-pad-$VN2.apk"
echo "== 已部署到 $UP"

# ---------- 5) 生成更新清单 ----------
python3 - "$UP" "$VC2" "$VN2" "$NOTES" <<'PYEOF'
import hashlib, json, os, sys
UP, VC, VN, NOTES = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]

def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for c in iter(lambda: f.read(1 << 20), b''):
            h.update(c)
    return h.hexdigest()

def write(name, apk):
    p = os.path.join(UP, apk)
    d = {
        "versionCode": VC,
        "versionName": VN,
        "url": "",
        "notes": NOTES,
        "path": apk,
        "size": os.path.getsize(p),
        "sha256": sha(p),
    }
    json.dump(d, open(os.path.join(UP, name), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print(f"清单 {name}: {d['size']} 字节  sha256={d['sha256'][:16]}…")

write('version-hgpad.json', f'hongguo-pad-{VN}.apk')
write('version-tv.json', 'app-tv-release.apk')
PYEOF
