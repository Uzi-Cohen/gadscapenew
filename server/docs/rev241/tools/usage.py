"""Find literal component-id usages for the given interfaces in the server sources.

python3 -I usage.py <server-dir> <remap.json> <iface> [iface...]
Prints: iface comp -> new (conf, name241) | file:line | kind | source line
"""
import json, os, re, sys

SERVER, REMAP = sys.argv[1], sys.argv[2]
IFACES = [int(x) for x in sys.argv[3:]]
remap = json.load(open(REMAP))["interfaces"]
gi = open(os.path.join(SERVER, "core/src/main/java/com/zenyte/game/GameInterface.java")).read()
GNAMES = {}
for m in re.finditer(r"^\s*([A-Z_0-9]+)\(\s*(\d+)", gi, re.M):
    GNAMES.setdefault(int(m.group(2)), []).append(m.group(1))

files = {}
for dp, dn, fn in os.walk(SERVER):
    if "/build" in dp or "/.gradle" in dp:
        continue
    for f in fn:
        if f.endswith((".java", ".kt")):
            p = os.path.join(dp, f)
            files[os.path.relpath(p, SERVER)] = open(p, errors="replace").read()


def rowinfo(i, c):
    r = remap.get(str(i))
    if not r or "rows" not in r:
        return "?"
    for row in r["rows"]:
        if row[0] == c:
            return "%d %s %s" % (row[1], row[2], row[4])
    return "OUT-OF-RANGE(n211=%d)" % r["n211"]


def lineof(t, pos):
    return t.count("\n", 0, pos) + 1


CALL = r"(?:sendComponent\w*|sendClearItemContainer|ComponentDefinitions\.get|sendInterfaceSettings|setComponent\w*)"
out = []
for i in IFACES:
    names = GNAMES.get(i, [])
    ref = r"(?:%d|%s)" % (i, "|".join(["GameInterface\\.%s" % n for n in names] + ["%s" % n for n in names]) or "NONE")
    for path, t in files.items():
        gm = re.search(r"getInterface\(\)[^{=;]*(?:\{\s*return\s+|=\s*)(?:GameInterface\.)?([A-Z_0-9]+)", t)
        own = bool(gm and gm.group(1) in names)
        if not own:
            im = re.search(r"getInterfaceIds\(\)[\s\S]{0,120}?(?:new int\[\]\s*\{([^}]*)\}|intArrayOf\(([^)]*)\))", t)
            if im and str(i) in re.findall(r"\b\d+\b", (im.group(1) or "") + (im.group(2) or "")):
                own = True
        selfref = r"(?:getInterface\(\)|getId\(\)|INTERFACE|INTERFACE_ID|interfaceId|ID)"
        pats = [(CALL + r"\(\s*" + ref + r"\s*,\s*(\d+)\b", "call"),
                (r"sendUpdateItem\w*\(\s*[^,()]+,\s*" + ref + r"\s*,\s*(\d+)\b", "container"),
                (r"sendLineSpacing\([^;]*?,\s*" + ref + r"\s*,\s*(\d+)\s*\)", "linespacing"),
                (r"\b" + ref + r"\s*<<\s*16\s*\)?\s*[|+]\s*(\d+)\b", "hash")]
        if own:
            pats += [(r"\bput\(\s*(\d+)\b", "put"),
                     (CALL + r"\(\s*" + selfref + r"\s*,\s*(\d+)\b", "selfcall"),
                     (r"sendUpdateItem\w*\(\s*[^,()]+,\s*" + selfref + r"\s*,\s*(\d+)\b", "selfcontainer"),
                     (r"componentId\s*==\s*(\d+)\b", "cmp"),
                     (r"\bcase\s+(\d+)\s*(?::|->|,)", "case?")]
        for pat, kind in pats:
            for m in re.finditer(pat, t):
                c = int(m.group(1))
                ln = lineof(t, m.start())
                src = t.splitlines()[ln - 1].strip()
                out.append((i, c, rowinfo(i, c), "%s:%d" % (path, ln), kind, src[:150]))
seen = set()
for o in sorted(out, key=lambda x: (x[0], x[3], x[1])):
    k = (o[0], o[1], o[3], o[4])
    if k in seen:
        continue
    seen.add(k)
    print("%d:%d -> %s | %s | %s | %s" % o)
