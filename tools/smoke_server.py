#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开心果服务端冒烟 / 黄金用例（Phase 0）。

用法（不要把 token 写进仓库）：
  KXG_BASE=http://192.168.1.54:8080 \
  KXG_TOKEN=... \
  KXG_VIDS=766...,766... \
  python3 tools/smoke_server.py

覆盖：
- /api/flags 服务端功能开关
- /api/admin/tree 架构世界树
- /api/history 最近真实播放合并（低集数/0秒旧记录/真实播放）
- /api/playinfo 取流（带 X-Request-Id，验证 requestId 链路入口）
- version-tv/hgpad manifest 的 path/size/sha/abis 结构
"""
import json
import os
import secrets
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("KXG_BASE", "http://192.168.1.54:8080").rstrip("/")
TOKEN = os.environ.get("KXG_TOKEN", "")
VIDS = [x.strip() for x in os.environ.get("KXG_VIDS", "").split(",") if x.strip()]
PLAY_TOKEN = os.environ.get("KXG_PLAY_TOKEN", "hgtv-play-2026")


def req(path, method="GET", body=None, headers=None):
    data = None
    h = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if headers:
        h.update(headers)
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    with urllib.request.urlopen(r, timeout=75) as resp:
        return resp.status, resp.read()


def get_json(path, headers=None):
    code, raw = req(path, headers=headers)
    return code, json.loads(raw)


def check(ok, name, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ((" :: " + detail) if detail else ""))
    if not ok:
        raise SystemExit(1)


def main():
    if not TOKEN:
        print("需要 KXG_TOKEN（看板 token）")
        return 2

    code, flags = get_json("/api/flags", {"X-Token": PLAY_TOKEN})
    check(code == 200 and isinstance(flags.get("flags"), dict), "flags", str(flags)[:120])

    code, raw = req("/api/admin/tree?token=" + TOKEN)
    check(code == 200 and b"edgeCount" in raw, "admin/tree")

    key = secrets.token_hex(32)
    h = {"X-Token": PLAY_TOKEN}

    def hist(rows):
        return get_json("/api/history?key=" + key, h)

    def row(ep, at, pos):
        return {"seriesId": "smoke-999", "title": "smoke", "episode": ep,
                "total": 60, "positionMs": pos, "at": at}

    _, out = req("/api/history?key=" + key, "POST", {"items": [row(34, 1000, 10000)]}, h)
    items = json.loads(out)["items"]
    check(items and items[0]["episode"] == 34, "history seed")
    check(items and items[0].get("schema") == 3, "history schema=3")

    _, out = req("/api/history?key=" + key, "POST", {"items": [row(15, 2000, 0)]}, h)
    items = json.loads(out)["items"]
    check(items and items[0]["episode"] == 34, "history 0-sec old row must not win")

    _, out = req("/api/history?key=" + key, "POST", {"items": [row(15, 3000, 5000)]}, h)
    items = json.loads(out)["items"]
    check(items and items[0]["episode"] == 15, "history real playback wins")

    _, out = req("/api/history?key=" + key, "POST", {"items": [row(34, 4000, 7000)]}, h)
    items = json.loads(out)["items"]
    check(items and items[0]["episode"] == 34, "history later progress wins")

    req("/api/history?key=" + key, "POST", {"mode": "delete", "seriesIds": []}, h)

    # 同集 0 秒记录不能压过真实秒数
    key2 = secrets.token_hex(32)
    req("/api/history?key=" + key2, "POST", {"items": [row(10, 5000, 0)]}, h)
    _, out = req("/api/history?key=" + key2, "POST", {"items": [row(10, 1000, 5000)]}, h)
    items = json.loads(out)["items"]
    check(items and items[0]["episode"] == 10 and items[0]["positionMs"] == 5000,
          "history zero same-episode guard")
    req("/api/history?key=" + key2, "POST", {"mode": "delete", "seriesIds": []}, h)

    for i, vid in enumerate(VIDS):
        rid = "smoke-" + secrets.token_hex(4)
        _, out = req("/api/playinfo", "POST", {"vid": vid, "want": "540p"},
                     {"X-Token": PLAY_TOKEN, "X-Request-Id": rid})
        d = json.loads(out).get("data") or {}
        check(bool(d.get("url")) and int(d.get("height") or 0) > 0,
              "playinfo " + vid, "rid=" + rid + " def=" + str(d.get("definition")))

    for name in ("version-tv.json", "version-hgpad.json"):
        code, man = get_json("/" + name)
        abis = man.get("abis") or {}
        check(code == 200 and man.get("path") and man.get("size") and man.get("sha256")
              and abis, name)

    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
