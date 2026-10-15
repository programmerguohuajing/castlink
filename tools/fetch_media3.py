#!/usr/bin/env python3
"""下载 Media3 (ExoPlayer) 最小依赖集合 -> ~/toolchains/m2repo，供 build_manual.sh 离线构建。

DLNA-DMR-INTEGRATION-PLAN.md §4：仅取 HLS/MP4 播放必需的最小集合：
  media3-common, media3-datasource, media3-decoder, media3-container,
  media3-exoplayer, media3-exoplayer-hls (+ POM 传递的 compile/runtime 依赖)

用法: python3 tools/fetch_media3.py [--version 1.11.1]
产物:
  ~/toolchains/m2repo/<groupPath>/<artifact>/<version>/
    <artifact>-<version>.aar/.jar (+ POM)
    <artifact>-<version>-classes.jar   (从 AAR 解出的 classes.jar，供 kotlinc/d8 直接引用)
构建脚本引用: tools/build_manual.sh 中的 EXO_CP 变量。
"""
import os
import sys
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
import zipfile

VERSION = "1.11.1"
for i, a in enumerate(sys.argv):
    if a == "--version" and i + 1 < len(sys.argv):
        VERSION = sys.argv[i + 1]

REPO_DIR = os.path.expanduser("~/toolchains/m2repo")
REPOS = [
    "https://dl.google.com/dl/android/maven2/",   # Media3 官方仓库
    "https://repo.maven.apache.org/maven2/",
    "https://repo1.maven.org/maven2/",
]

ROOTS = [
    ("androidx.media3", "media3-common", VERSION),
    ("androidx.media3", "media3-datasource", VERSION),
    ("androidx.media3", "media3-decoder", VERSION),
    ("androidx.media3", "media3-container", VERSION),
    ("androidx.media3", "media3-exoplayer", VERSION),
    ("androidx.media3", "media3-exoplayer-hls", VERSION),
]

MAX_RETRIES = 4
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
visited = set()
queued = []
downloaded = 0
failed = []


def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "castlink-media3fetch/1.0"})
    last = None
    for i in range(MAX_RETRIES):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None  # 换仓库
            last = e
            time.sleep(1 + i)
        except Exception as e:
            last = e
            time.sleep(1 + i)
    print(f"    [FAIL] {url}: {last}", flush=True)
    return None


def group_path(g):
    return g.replace(".", "/")


def fetch_one(g, a, v):
    """下载某构件的 pom + jar/aar；AAR 额外解出 classes.jar。返回 POM 依赖列表。"""
    global downloaded
    key = (g, a, v)
    if key in visited:
        return []
    visited.add(key)
    base = f"{group_path(g)}/{a}/{v}"
    outdir = os.path.join(REPO_DIR, base)
    os.makedirs(outdir, exist_ok=True)

    pom_data = None
    for repo in REPOS:
        url = f"{repo}{base}/{a}-{v}.pom"
        pom_data = http_get(url)
        if pom_data is not None:
            break
    if pom_data is None:
        failed.append(f"{g}:{a}:{v} (pom)")
        return []
    with open(os.path.join(outdir, f"{a}-{v}.pom"), "wb") as f:
        f.write(pom_data)

    try:
        root = ET.fromstring(pom_data)
    except Exception as e:
        failed.append(f"{g}:{a}:{v} (pom parse: {e})")
        return []

    packaging = (root.findtext("m:packaging", default="jar", namespaces=NS) or "jar").strip()
    deps = []
    for d in root.findall("m:dependencies/m:dependency", NS):
        dg = (d.findtext("m:groupId", namespaces=NS) or "").strip()
        da = (d.findtext("m:artifactId", namespaces=NS) or "").strip()
        dv = (d.findtext("m:version", namespaces=NS) or "").strip()
        scope = (d.findtext("m:scope", namespaces=NS) or "compile").strip()
        dtype = (d.findtext("m:type", namespaces=NS) or "jar").strip()
        optional = (d.findtext("m:optional", namespaces=NS) or "false").strip() == "true"
        if not dg or not da or not dv:
            continue
        if scope in ("test", "provided") or optional:
            continue
        if dtype not in ("jar", "aar"):
            continue
        # 占位版本号（如 BOM 引用）跳过
        if "${" in dv:
            continue
        deps.append((dg, da, dv))

    # 找 parent pom 的依赖（BFS 只跟一层 parent，避免无限展开）
    parent = root.find("m:parent", NS)
    if parent is not None:
        pg = (parent.findtext("m:groupId", namespaces=NS) or "").strip()
        pa = (parent.findtext("m:artifactId", namespaces=NS) or "").strip()
        pv = (parent.findtext("m:version", namespaces=NS) or "").strip()
        if pg and pa and pv and "${" not in pv:
            queued.append((pg, pa, pv))

    # 下载构件本体
    ext = "aar" if packaging == "aar" else "jar"
    artifact_path = os.path.join(outdir, f"{a}-{v}.{ext}")
    classes_jar = os.path.join(outdir, f"{a}-{v}-classes.jar")
    if ext == "jar" and os.path.exists(artifact_path):
        print(f"  [skip] {g}:{a}:{v}", flush=True)
    elif ext == "aar" and os.path.exists(classes_jar):
        print(f"  [skip] {g}:{a}:{v} (classes.jar 已存在)", flush=True)
    else:
        data = None
        for repo in REPOS:
            url = f"{repo}{base}/{a}-{v}.{ext}"
            data = http_get(url)
            if data is not None:
                break
        if data is None:
            failed.append(f"{g}:{a}:{v} (.{ext})")
        else:
            with open(artifact_path, "wb") as f:
                f.write(data)
            downloaded += 1
            print(f"  [ok {len(data)//1024}KB] {g}:{a}:{v} (.{ext})", flush=True)
            if ext == "aar":
                try:
                    with zipfile.ZipFile(artifact_path) as z:
                        with z.open("classes.jar") as src, open(classes_jar, "wb") as dst:
                            dst.write(src.read())
                    print(f"       -> classes.jar 已解出", flush=True)
                except KeyError:
                    failed.append(f"{g}:{a}:{v} (aar 内无 classes.jar)")

    return deps


def main():
    print(f"=== 拉取 Media3 {VERSION} 最小依赖集 -> {REPO_DIR} ===", flush=True)
    for r in ROOTS:
        queued.append(r)
    while queued:
        g, a, v = queued.pop(0)
        if (g, a, v) in visited:
            continue
        print(f"[*] {g}:{a}:{v}", flush=True)
        for dep in fetch_one(g, a, v):
            if dep not in visited:
                queued.append(dep)
    print(f"\n=== 完成：新下载 {downloaded} 个文件，失败 {len(failed)} ===", flush=True)
    for f in failed:
        print(f"  FAIL: {f}", flush=True)
    # 汇总 classes.jar 清单，供 build_manual.sh 核对
    print("\n=== classes.jar 清单 ===", flush=True)
    for dirpath, _, files in os.walk(REPO_DIR):
        for fn in sorted(files):
            if fn.endswith("-classes.jar"):
                print(" ", os.path.join(dirpath, fn)[len(REPO_DIR) + 1:], flush=True)


if __name__ == "__main__":
    main()
