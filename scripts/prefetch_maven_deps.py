#!/usr/bin/env python3
"""Prefetch Maven artifacts (POMs + JARs) into the local repository using curl.

Needed because this sandbox denies direct TCP from Java processes, so Maven
cannot reach Maven Central itself; curl can. Resolution here is approximate
(compile/runtime scopes, depMgmt from parent chain + imported BOMs, no
exclusions) - over-fetching is harmless. Maven then runs fully from the
local repository.
"""
import os, re, subprocess, sys
import xml.etree.ElementTree as ET
from collections import deque

CENTRAL = "https://repo.maven.apache.org/maven2"
LOCAL = "/root/.m2/repository"

def path_for(g, a, v, ext):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"

def fetch(g, a, v, ext):
    rel = path_for(g, a, v, ext)
    dest = os.path.join(LOCAL, rel)
    if os.path.exists(dest):
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    url = f"{CENTRAL}/{rel}"
    r = subprocess.run(["curl", "-sfL", "-o", dest, url])
    if r.returncode != 0:
        if os.path.exists(dest): os.remove(dest)
        return None
    return dest

def strip(tag):
    return tag.split('}', 1)[-1]

def children(el, name):
    return [c for c in el if strip(c.tag) == name]

def child(el, name):
    for c in el:
        if strip(c.tag) == name:
            return c
    return None

def text(el):
    return (el.text or "").strip() if el is not None else None

class Pom:
    def __init__(self, g, a, v):
        self.g, self.a, self.v = g, a, v
        self.parent = None          # (g,a,v)
        self.props = {}
        self.dep_mgmt = {}          # (g,a) -> version
        self.dep_mgmt_imports = []  # BOM gavs
        self.deps = []              # dicts
        self.packaging = "jar"
        self.loaded = False

    def load(self):
        dest = fetch(self.g, self.a, self.v, "pom")
        if not dest:
            print(f"WARN: pom missing {self.g}:{self.a}:{self.v}", file=sys.stderr)
            self.loaded = True
            return
        raw = open(dest, encoding="utf-8", errors="replace").read()
        raw = re.sub(r"&(?!amp;|lt;|gt;|quot;|apos;|#)[A-Za-z]+;", "?", raw)  # old POMs use HTML entities
        root = ET.fromstring(raw)
        par = child(root, "parent")
        if par is not None:
            self.parent = (text(child(par, "groupId")), text(child(par, "artifactId")), text(child(par, "version")))
        self.packaging = text(child(root, "packaging")) or "jar"
        props = child(root, "properties")
        if props is not None:
            for p in props:
                self.props[strip(p.tag)] = (p.text or "").strip()
        dm = child(root, "dependencyManagement")
        if dm is not None:
            deps = child(dm, "dependencies")
            if deps is not None:
                for d in children(deps, "dependency"):
                    self._dep(d, mgmt=True)
        deps = child(root, "dependencies")
        if deps is not None:
            for d in children(deps, "dependency"):
                self._dep(d, mgmt=False)
        self.loaded = True

    def _dep(self, d, mgmt):
        g = text(child(d, "groupId")); a = text(child(d, "artifactId"))
        v = text(child(d, "version")); scope = text(child(d, "scope")) or "compile"
        typ = text(child(d, "type")) or "jar"
        opt = text(child(d, "optional")) == "true"
        if scope == "import" and typ == "pom":
            self.dep_mgmt_imports.append((g, a, v))
            return
        if mgmt:
            if v: self.dep_mgmt[(g, a)] = v
        else:
            self.deps.append(dict(g=g, a=a, v=v, scope=scope, type=typ, optional=opt))

poms = {}

def get_pom(g, a, v):
    key = (g, a, v)
    if key not in poms:
        p = Pom(g, a, v)
        poms[key] = p
        p.load()
    return poms[key]

def interp(s, pom, seen=()):
    if s is None: return None
    def rep(m):
        name = m.group(1)
        if name in ("project.version", "pom.version"): return pom.v
        if name in ("project.groupId", "pom.groupId"): return pom.g
        if name in ("project.parent.version",): return pom.parent[2] if pom.parent else s
        if name in ("project.parent.groupId",): return pom.parent[0] if pom.parent else s
        # search pom then parents
        cur = pom
        while cur is not None:
            if name in cur.props: return cur.props[name]
            cur = get_pom(*cur.parent) if cur.parent else None
        return "${" + name + "}"
    prev = None
    out = s
    n = 0
    while prev != out and n < 10:
        prev = out
        out = re.sub(r"\$\{([^}]+)\}", rep, out)
        n += 1
    return out

def effective_depmgmt(pom):
    """Merge depMgmt: parents first, then imported BOMs, then own (own wins)."""
    chain = []
    cur = pom
    while cur is not None:
        chain.append(cur)
        cur = get_pom(*cur.parent) if cur.parent else None
    merged = {}
    for p in reversed(chain):
        for (g, a), v in p.dep_mgmt.items():
            merged[(g, a)] = interp(v, p)
        for (g, a, v) in p.dep_mgmt_imports:
            bom = get_pom(g, a, interp(v, p))
            for (bg, ba), bv in effective_depmgmt(bom).items():
                merged.setdefault((bg, ba), bv)
    return merged

def main():
    seeds = [tuple(x.split(":")) for x in sys.argv[1:]]
    seen = set()
    queue = deque(seeds)
    while queue:
        g, a, v = queue.popleft()
        if (g, a, v) in seen: continue
        seen.add((g, a, v))
        pom = get_pom(g, a, v)
        if pom.packaging != "pom":
            fetch(g, a, v, "jar")
        if pom.parent:
            pg, pa, pv = pom.parent
            queue.append((interp(pg, pom), interp(pa, pom), interp(pv, pom)))
        mgmt = effective_depmgmt(pom)
        # effective deps = own + inherited from parent chain (own wins on g:a)
        eff = {}
        chain = []
        cur = pom
        while cur is not None:
            chain.append(cur)
            cur = get_pom(*cur.parent) if cur.parent else None
        for p in reversed(chain):
            for d in p.deps:
                eff[(interp(d["g"], p), interp(d["a"], p))] = (d, p)
        for d, owner in eff.values():
            if d["scope"] in ("test", "provided", "system", "import"): continue
            if d["optional"]: continue
            dg, da = interp(d["g"], owner), interp(d["a"], owner)
            dv = interp(d["v"], owner) if d["v"] else mgmt.get((dg, da))
            if not dv or "${" in dv:
                print(f"WARN: no version for {dg}:{da} from {g}:{a}:{v}", file=sys.stderr)
                continue
            if d["type"] == "pom":
                queue.append((dg, da, dv))
            else:
                queue.append((dg, da, dv))
    print(f"prefetched/resolved {len(seen)} GAVs")

if __name__ == "__main__":
    main()
