#!/bin/bash
# 寮€蹇冩灉鍙戠増涓€鏉￠緳锛歜ump 鐗堟湰鍙?鈫?鏋勫缓 鈫?绛惧悕 鈫?閮ㄧ讲 鈫?鐢熸垚鏇存柊娓呭崟
#
# 鐢ㄦ硶锛?#   release.sh "鏇存柊璇存槑"        姝ｅ父鍙戠増锛堢増鏈彿 +1锛?#   release.sh --resume "璇存槑"   鐗堟湰鍙峰凡鏀硅繃锛岀洿鎺ユ瀯寤?閮ㄧ讲/鍐欐竻鍗?#
# 娉ㄦ剰锛氳剼鏈敤 set -euo pipefail锛屾墍鏈夈€屽懡浠?| head銆嶅紡绠￠亾閮戒細鍥?head 鎻愬墠閫€鍑?# 瑙﹀彂 SIGPIPE 鑰岃鏉€鑴氭湰 鈥斺€?涓€寰嬪厛鍙栬繘鍙橀噺鍐嶆墦鍗般€?set -euo pipefail
cd /opt/hongguo-redo

MODE=normal
if [ "${1:-}" = "--resume" ]; then MODE=resume; shift; fi
NOTES="${1:-}"
[ -z "$NOTES" ] && { echo "鐢ㄦ硶: release.sh [--resume] \"鏇存柊璇存槑\""; exit 1; }

GRADLE=/opt/gradle/bin/gradle
BT=/opt/android-sdk/build-tools/35.0.0
UP=/srv/hgtv-update

# ---------- 1) 鐗堟湰鍙?----------
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
echo "== 鐗堟湰锛?VC2/$VN2"

# ---------- 2) 鏋勫缓 ----------
# 鏈嶅姟鍣ㄥ湴鍧€涓嶅啀鍐欐鍦ㄦ簮鐮侀噷锛屽彧鑳介潬鏋勫缓鍙傛暟娉ㄥ叆锛?#   - 鑷敤鍙戠増锛堟湰鑴氭湰锛夊繀椤诲甫杩欎簲涓弬鏁帮紝鍚﹀垯瑁呭埌鐢佃涓婁細鍙樻垚銆岃鎵嬪姩濉鍚嶆湇鍔°€?#   - GitHub 涓婂彂甯冪殑鍏紑鍖呮晠鎰忎笉甯︼紝婧愮爜鍜屽畨瑁呭寘閲岄兘涓嶅惈浠讳綍鍦板潃
SERVER_ARGS="-PsignUrl=http://192.168.1.54:9100"
SERVER_ARGS="$SERVER_ARGS -PplayBases=http://192.168.1.54:8080,https://hgtv.fabao.xin"
SERVER_ARGS="$SERVER_ARGS -PplayToken=ROTATED_OUT_old_token"
SERVER_ARGS="$SERVER_ARGS -PupdateBases=http://192.168.1.54:8080,https://hgtv.fabao.xin"
SERVER_ARGS="$SERVER_ARGS -PhistoryBase=https://hgtv.fabao.xin"

S=$(date +%s)
OUT=$($GRADLE :pad:assembleTvRelease :pad:assemblePadDebug $SERVER_ARGS 2>&1) || { echo "$OUT" | grep -E '^e:|BUILD' | head -10; echo "!! 鏋勫缓澶辫触"; exit 1; }
BUILD_LINE=$(echo "$OUT" | grep -E 'BUILD' | head -1)
echo "== 鏋勫缓锛?BUILD_LINE锛?(( $(date +%s) - S )) 绉掞級"

# ---------- 3) 绛惧悕 ----------
cd pad/build/outputs/apk/tv/release
$BT/zipalign -f -p 4 pad-tv-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks /root/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey --out signed.apk aligned.apk
CERT=$($BT/apksigner verify --print-certs signed.apk | grep -m1 'SHA-256 digest' || true)
BADGE=$($BT/aapt2 dump badging signed.apk | grep -m1 'package: name' || true)
echo "== 绛惧悕锛?CERT"
echo "== 鍖咃細$BADGE"
cd /opt/hongguo-redo

# ---------- 4) 閮ㄧ讲 ----------
cp pad/build/outputs/apk/tv/release/signed.apk "$UP/app-tv-release.apk"
cp pad/build/outputs/apk/pad/debug/pad-pad-debug.apk "$UP/hongguo-pad-$VN2.apk"
echo "== 宸查儴缃插埌 $UP"

# ---------- 5) 鐢熸垚鏇存柊娓呭崟 ----------
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
    print(f"娓呭崟 {name}: {d['size']} 瀛楄妭  sha256={d['sha256'][:16]}鈥?)

write('version-hgpad.json', f'hongguo-pad-{VN}.apk')
write('version-tv.json', 'app-tv-release.apk')
PYEOF
