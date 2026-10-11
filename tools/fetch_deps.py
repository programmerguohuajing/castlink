#!/usr/bin/env python3
"""BFS抓取Maven依赖 -> 本地文件仓库，供Gradle离线构建。

用法: python3 fetch_deps.py
产物: ~/toolchains/m2repo/<groupPath>/<artifact>/<version>/...
"""
import os
import re
import sys
import time
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET

REPO_DIR = os.path.expanduser("~/toolchains/m2repo")
REPOS = [
    "https://dl.google.com/dl/android/maven2/",
    "https://repo.maven.apache.org/maven2/",
    "https://repo1.maven.org/maven2/",
]

ROOTS = [
    ("com.android.tools.build", "gradle", "8.5.2"),
    ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.0.21"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
    ("com.google.zxing", "core", "3.5.3"),
]

MAX_RETRIES = 4
visited = set()          # (g,a,v) 已处理
queued = []              # 待处理 (g,a,v,depth)
downloaded_files = 0
failed = []

# ---------- http ----------

def http_get(url):
    import datetime
    req = urllib.request.Request(url, headers={"User-Agent": "castlink-depfetch/1.0"})
    last = None
    for i in range(MAX_RETRIES):
        try:
            ts = datetime.datetime.now().strftime("%H:%M:%S")
            print(f"    [get {ts} try{i+1}] {url}", flush=True)
            with urllib.request.urlopen(req, timeout=45) as r:
                data = r.read()
            print(f"    [ok {len(data)}B] {url}", flush=True)
            return data
        except urllib.error.HTTPError as e:
            # 404 直接换仓库，不重试
            if e.code == 404:
                print(f"    [404] {url}", flush=True)
                raise
            last = e
            print(f"    [fail] {url} -> {type(e).__name__}: {e}", flush=True)
            time.sleep(1 + i)
        except Exception as e:  # noqa: BLE001
            last = e
            print(f"    [fail] {url} -> {type(e).__name__}: {e}", flush=True)
            time.sleep(1 + i)
    raise last


def repo_fetch(rel_path):
    """按顺序尝试各仓库，返回(bytes, base_url)"""
    last = None
    for base in REPOS:
        url = base + rel_path
        try:
            return http_get(url), base
        except Exception as e:  # noqa: BLE001
            last = e
    raise Exception(f"not found in any repo: {rel_path} ({last})")


# ---------- pom 解析 ----------

def tag(e, name):
    for child in e:
        if child.tag == name or child.tag.endswith("}" + name):
            return child
    return None


def tagtext(e, name):
    t = tag(e, name)
    return t.text.strip() if t is not None and t.text else ""


def parse_props(e):
    props = {}
    p = tag(e, "properties")
    if p is not None:
        for c in p:
            key = c.tag.split("}")[-1]
            props[key] = (c.text or "").strip()
    return props


PROP_RE = re.compile(r"\$\{([^}]+)\}")


def resolve_props(s, props):
    def sub(m):
        return props.get(m.group(1), m.group(0))
    prev = None
    cur = s
    for _ in range(10):
        prev, cur = cur, PROP_RE.sub(sub, cur)
        if cur == prev:
            break
    return cur


def load_pom_chain(g, a, v):
    """返回 (pom_root, merged_props, managed{ga->v}, parent_chain)。"""
    chain = []
    props = {}
    managed = {}
    cg, ca, cv = g, a, v
    for _ in range(8):  # 最多8层parent
        rel = f"{cg.replace('.', '/')}/{ca}/{cv}/{ca}-{cv}.pom"
        data, _ = repo_fetch(rel)
        root = ET.fromstring(data)
        chain.append(root)
        # 先合并本层properties（子优先，已有不覆盖）
        for k, val in parse_props(root).items():
            props.setdefault(k, val)
        cg2 = tagtext(root, "groupId") or cg
        parent = tag(root, "parent")
        if parent is None:
            break
        cg, ca, cv = (tagtext(parent, "groupId") or cg2,
                      tagtext(parent, "artifactId"),
                      tagtext(parent, "version"))
        if not ca or not cv:
            break
    # project.* 基础属性
    props.setdefault("project.groupId", g)
    props.setdefault("project.artifactId", a)
    props.setdefault("project.version", v)
    # 从子到父收集 dependencyManagement（子优先）
    for root in chain:
        dm = tag(root, "dependencyManagement")
        if dm is None:
            continue
        deps = tag(dm, "dependencies")
        if deps is None:
            continue
        for d in deps:
            if not (d.tag == "dependency" or d.tag.endswith("}dependency")):
                continue
            dg = resolve_props(tagtext(d, "groupId"), props)
            da = resolve_props(tagtext(d, "artifactId"), props)
            dv = resolve_props(tagtext(d, "version"), props)
            scope = tagtext(d, "scope") or "compile"
            dtype = tagtext(d, "type") or "jar"
            if scope == "import" and dtype == "pom" and dv:
                # BOM：抓取并合并其 managed
                try:
                    _, _, sub_managed, _ = load_pom_chain(dg, da, dv)
                    for k, val in sub_managed.items():
                        managed.setdefault(k, val)
                except Exception as e:  # noqa: BLE001
                    print(f"  [warn] BOM fetch failed {dg}:{da}:{dv}: {e}", flush=True)
                continue
            if dv and f"{dg}:{da}" not in managed:
                managed[f"{dg}:{da}"] = dv
    return chain[0], props, managed, chain


def pick_version_from_range(g, a, vr):
    """版本范围如 [1.8,2.0) -> 取 maven-metadata 的最高 release。"""
    rel = f"{g.replace('.', '/')}/{a}/maven-metadata.xml"
    data, _ = repo_fetch(rel)
    root = ET.fromstring(data)
    vers = []
    for v in root.iter():
        if v.tag == "version" or v.tag.endswith("}version"):
            if v.text:
                vers.append(v.text.strip())
    if not vers:
        raise Exception(f"no versions in metadata for {g}:{a}")
    # 简单排序：按数字段
    def key(s):
        return [int(x) if x.isdigit() else x for x in re.split(r"(\d+)", s)]
    vers.sort(key=key)
    # 尊重范围上限（粗略：去掉明显超出上限的）
    m = re.search(r"[,\[]\s*([^,\]\)]+)[\]\)]", vr)
    upper = None
    upper_inclusive = vr.rstrip().endswith("]")
    if m and m.group(1).strip():
        upper = m.group(1).strip()
    cands = [x for x in vers if upper is None or (key(x) < key(upper) or (upper_inclusive and key(x) == key(upper)))]
    return (cands or vers)[-1]


def enqueue(g, a, v, depth):
    if (g, a, v) not in visited:
        visited.add((g, a, v))
        queued.append((g, a, v, depth))


def exclusions_of(dep_el, props):
    out = set()
    ex = tag(dep_el, "exclusions")
    if ex is None:
        return out
    for x in ex:
        if not (x.tag == "exclusion" or x.tag.endswith("}exclusion")):
            continue
        eg = resolve_props(tagtext(x, "groupId"), props)
        ea = resolve_props(tagtext(x, "artifactId"), props)
        out.add(f"{eg}:{ea}")
    return out


def process(g, a, v, depth):
    global downloaded_files
    indent = "  " * min(depth, 4)
    print(f"{indent}*{g}:{a}:{v}", flush=True)
    try:
        root, props, managed, _ = load_pom_chain(g, a, v)
    except Exception as e:  # noqa: BLE001
        failed.append(f"{g}:{a}:{v} pom: {e}")
        print(f"{indent}  [FAIL pom] {e}", flush=True)
        return

    # 下载主构件（按packaging）
    packaging = tagtext(root, "packaging") or "jar"
    ext = {"jar": "jar", "aar": "aar", "bundle": "jar"}.get(packaging, None)
    rel_base = f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}"
    dest_dir = os.path.join(REPO_DIR, g.replace(".", "/"), a, v)
    os.makedirs(dest_dir, exist_ok=True)
    # pom 一定要（已存在且非空则跳过）
    pom_path = os.path.join(dest_dir, f"{a}-{v}.pom")
    if os.path.exists(pom_path) and os.path.getsize(pom_path) > 0:
        print(f"{indent}  [cached] {a}-{v}.pom", flush=True)
    else:
        try:
            data, _ = repo_fetch(rel_base + ".pom")
            with open(pom_path, "wb") as f:
                f.write(data)
            downloaded_files += 1
        except Exception as e:  # noqa: BLE001
            failed.append(f"{g}:{a}:{v} pom-file: {e}")
    if ext:
        art_path = os.path.join(dest_dir, f"{a}-{v}.{ext}")
        if os.path.exists(art_path) and os.path.getsize(art_path) > 0:
            print(f"{indent}  [cached] {a}-{v}.{ext}", flush=True)
        else:
            try:
                data, _ = repo_fetch(rel_base + "." + ext)
                with open(art_path, "wb") as f:
                    f.write(data)
                downloaded_files += 1
            except Exception as e:  # noqa: BLE001
                failed.append(f"{g}:{a}:{v} {ext}: {e}")
                print(f"{indent}  [FAIL {ext}] {e}", flush=True)

    # 依赖
    deps_el = tag(root, "dependencies")
    if deps_el is None:
        return
    for d in deps_el:
        if not (d.tag == "dependency" or d.tag.endswith("}dependency")):
            continue
        scope = tagtext(d, "scope") or "compile"
        if scope in ("test", "provided", "system"):
            continue
        if (tagtext(d, "optional") or "").lower() == "true":
            continue
        dg = resolve_props(tagtext(d, "groupId"), props)
        da = resolve_props(tagtext(d, "artifactId"), props)
        dv = resolve_props(tagtext(d, "version"), props)
        if not dv:
            dv = managed.get(f"{dg}:{da}", "")
        if not dv:
            print(f"{indent}  [skip] no version for {dg}:{da}", flush=True)
            continue
        if dv.startswith("${"):
            print(f"{indent}  [skip] unresolved {dg}:{da}:{dv}", flush=True)
            continue
        if dv.startswith("[") or dv.startswith("("):
            try:
                dv = pick_version_from_range(dg, da, dv)
            except Exception as e:  # noqa: BLE001
                print(f"{indent}  [skip range] {dg}:{da}:{dv}: {e}", flush=True)
                continue
        # exclusions 本层生效（简化：只记日志，不深传；常见冲突后续再处理）
        enqueue(dg, da, dv, depth + 1)


def main():
    os.makedirs(REPO_DIR, exist_ok=True)
    # 断点续抓：仓库里已有的构件不再重复处理
    for root, dirs, files in os.walk(REPO_DIR):
        segs = os.path.relpath(root, REPO_DIR).split(os.sep)
        if len(segs) < 3:
            continue
        vv, aa, gg = segs[-1], segs[-2], ".".join(segs[:-2])
        if any(f == f"{aa}-{vv}.pom" for f in files):
            visited.add((gg, aa, vv))
    if visited:
        print(f"resuming: {len(visited)} artifacts already cached", flush=True)
    root_keys = {(g, a, v) for g, a, v in ROOTS}
    for g, a, v in ROOTS:
        # root 强制重新展开依赖（文件层有 [cached] 保护）
        visited.discard((g, a, v))
        queued.append((g, a, v, 0))
        visited.add((g, a, v))
    n = 0
    while queued:
        g, a, v, depth = queued.pop(0)
        n += 1
        try:
            process(g, a, v, depth)
        except Exception as e:  # noqa: BLE001
            failed.append(f"{g}:{a}:{v}: {e}")
            print(f"  [ERROR] {g}:{a}:{v}: {e}", flush=True)
        if n % 25 == 0:
            print(f"--- progress: {n} processed, {len(queued)} queued, {downloaded_files} files ---", flush=True)
    print(f"DONE: {n} artifacts, {downloaded_files} files, {len(failed)} failures")
    for f in failed[:40]:
        print("FAIL:", f)


if __name__ == "__main__":
    main()
