#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""架构世界树 CI 校验：Markdown 节点、HTML 数据、文件引用必须一致。"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD = os.path.join(ROOT, "docs", "TECH_TREE.md")
HTML = os.path.join(ROOT, "docs", "tech-tree.html")


def fail(msg):
    print("FAIL:", msg)
    raise SystemExit(1)


def main():
    md = open(MD, encoding="utf-8").read()
    html = open(HTML, encoding="utf-8").read()
    m = re.search(r'<script id="tree-data" type="application/json">(.*?)</script>', html, re.S)
    if not m:
        fail("tech-tree.html 缺少 tree-data")
    data = json.loads(m.group(1))
    html_ids = {n["id"] for n in data["nodes"]}
    md_ids = {
        x for x in re.findall(r"`([A-Z]{1,2}(?:-[A-Z0-9]+)+)`", md)
        if x.split("-")[0] in {"S", "P", "A", "D", "C", "X", "V", "R", "U", "I"}
    }
    if html_ids != md_ids:
        fail("节点不一致: html_only=%s md_only=%s" % (sorted(html_ids - md_ids), sorted(md_ids - html_ids)))
    if len(data["nodes"]) != len(html_ids):
        fail("HTML 节点重复")
    for n in data["nodes"]:
        for f in n.get("files", []):
            if not (f.startswith("src/") or f.startswith("android/") or f.startswith("shared/")):
                continue
            base = f.split(":")[0].split(" ")[0]
            if not os.path.exists(os.path.join(ROOT, base)):
                fail("%s 引用文件不存在: %s" % (n["id"], f))
    print("OK nodes=%d edges=%d" % (len(html_ids), data.get("edgeCount", 0)))


if __name__ == "__main__":
    main()
