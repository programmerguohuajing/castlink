#!/usr/bin/env python3
"""抓取 Gradle 插件 marker 构件（plugin id -> 真实插件坐标的桥接 pom）。"""
import os
import fetch_deps as fd

# 把本地仓库里已有的构件标为 visited，避免重复抓取整棵树
import re as _re
for root, dirs, files in os.walk(fd.REPO_DIR):
    segs = os.path.relpath(root, fd.REPO_DIR).split(os.sep)
    if len(segs) < 3:
        continue
    v, a, gsegs = segs[-1], segs[-2], segs[:-2]
    for f in files:
        if _re.fullmatch(_re.escape(a) + "-" + _re.escape(v) + r"\.(pom|jar|aar)", f):
            fd.visited.add((".".join(gsegs), a, v))
            break

MARKERS = [
    ("com.android.application", "com.android.application.gradle.plugin", "8.5.2"),
    ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.android.gradle.plugin", "2.0.21"),
]

for g, a, v in MARKERS:
    fd.enqueue(g, a, v, 0)

n = 0
while fd.queued:
    g, a, v, depth = fd.queued.pop(0)
    n += 1
    fd.process(g, a, v, depth)

print(f"MARKERS DONE: {n} artifacts, {fd.downloaded_files} files, {len(fd.failed)} failures")
for f in fd.failed:
    print("FAIL:", f)
