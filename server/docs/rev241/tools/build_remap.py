"""Build the 211->241 component remap for every vanilla interface the server references.

python3 -I build_remap.py <server-dir> <out.json>
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ifmap  # noqa: E402

SERVER, OUT = sys.argv[1], sys.argv[2]

# ---------------------------------------------------------------- interface ids referenced by the server
src = []
for dp, dn, fn in os.walk(SERVER):
    if "/build" in dp or "/assets" in dp or "/.gradle" in dp:
        continue
    for f in fn:
        if f.endswith((".java", ".kt")):
            src.append(open(os.path.join(dp, f), errors="replace").read())
T = "\n".join(src)
ids = set()
gi = open(os.path.join(SERVER, "core/src/main/java/com/zenyte/game/GameInterface.java")).read()
ids |= {int(x) for x in re.findall(r"^\s*[A-Z_0-9]+\(\s*(\d+)", gi, re.M)}
ids |= {548, 161, 164, 165, 601, 16, 80, 162, 293, 629, 134, 707, 727, 730, 724, 774}
CALLS = r"(?:sendComponent\w*|sendClearItemContainer|sendInterface|ComponentDefinitions\.get|sendLineSpacing\w*)"
for m in re.finditer(CALLS + r"\(\s*(\d+)\s*,", T):
    ids.add(int(m.group(1)))
for m in re.finditer(r"sendUpdateItem\w*\(\s*[^,()]+,\s*(\d+)\s*,\s*\d+", T):
    ids.add(int(m.group(1)))
for m in re.finditer(r"getInterfaceIds[\s\S]{0,120}?(?:new int\[\]\s*\{([^}]*)\}|intArrayOf\(([^)]*)\))", T):
    for x in re.findall(r"\b\d+\b", (m.group(1) or "") + (m.group(2) or "")):
        ids.add(int(x))
for m in re.finditer(r"\b(\d{1,4})\s*<<\s*16\b", T):
    ids.add(int(m.group(1)))

van = sorted(i for i in ids if ifmap.git_show(ifmap.C211, "interface/interface_%d.if3" % i) is not None)
print("referenced interface ids:", len(ids), "vanilla in 211:", len(van), file=sys.stderr)

# ---------------------------------------------------------------- evidence anchors (crossref.py: scripts + enums)
EVID = {}
if os.path.exists(os.path.join(os.path.dirname(os.path.abspath(__file__)), "evidence.json")):
    ev = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "evidence.json")))
    for i, comps in ev.items():
        anc = {}
        for c, (best, bv, tot, _mine) in comps.items():
            if bv * 2 > tot:
                anc[int(c)] = best
        inv = {}
        for x, y in anc.items():
            inv.setdefault(y, []).append(x)
        EVID[int(i)] = {x: y for x, y in anc.items() if len(inv[y]) == 1}


def evid(i, extra=None):
    a = dict(EVID.get(i, {}))
    if extra:
        # enum anchors win; drop evidence anchors that collide with them
        used = set(extra.values())
        a = {x: y for x, y in a.items() if x not in extra and y not in used}
        a.update(extra)
    return a or None


# ---------------------------------------------------------------- special semantic anchors
import cfg  # noqa: E402


def prayer_anchors():
    """541: prayer p sits at enum_859[p] in 211 and at oc_param(enum_4956[p], param_1751) in 241."""
    e1 = {v.split(",")[0]: v.split(",")[1] for v in cfg.get(cfg.blocks(ifmap.C211, "enum")["enum_859"], "val")}
    e2 = {v.split(",")[0]: v.split(",")[1] for v in cfg.get(cfg.blocks(ifmap.C241, "enum")["enum_4956"], "val")}
    objs = cfg.blocks(ifmap.C241, "obj")
    names = {v: k for k, v in ifmap.COMP241.items()}  # "if:c" -> name  (COMP241 is still id->name here)
    out = {}
    for p, comp in e1.items():
        o = e2.get(p)
        if o is None:
            continue
        par = [x for x in cfg.get(objs[o], "param") if x.startswith("param_1751,")]
        if not par:
            continue
        nm = par[0].split(",", 1)[1]
        cid = {v: k for k, v in ifmap.COMP241.items()}.get(nm)
        out[int(comp.split("com_")[1])] = int(cid.split(":")[1])
    return out


SPECIAL = {541: {**prayer_anchors(), 4: 3}}  # 211 com_4 = the 8x9 prayer container layer (241 'container')
# Hand-verified (241 component names + bankmain_init argument order + menu hook scripts 517/1428/1270/2207/2211).
MANUAL = {
    12: {15: 22, 17: 23, 24: 25, 26: 28, 28: 29, 30: 31, 32: 33, 34: 35, 36: 37, 38: 40, 39: 41, 40: 42, 41: 43,
         42: 47, 43: 48, 44: 49, 45: 50, 50: 129, 53: 130, 56: 140, 57: 142, 68: 153, 87: 80,
         **{c: c - 8 for c in range(90, 109)}},
    160: {0: 0},  # root layer; 241 inserted 'triggers' at 1
    593: {0: 0, 3: 4},  # universe (onload renamed combat_interface_setup -> _init), level text
    # settings_side: volume rows matched by their init-script arguments, dropdown buttons by
    # settings_player/npc_attack_options arguments, bottom toggles by settings_side_bottom_init arguments.
    116: {29: 97, 30: 110, 31: 98, 41: 108, 43: 111, 44: 124, 45: 112, 55: 122, 57: 125, 58: 138, 59: 126,
          69: 136, 81: 38, 82: 39, 87: 43, 72: 29, 73: 30, 74: 31, 75: 32, 76: 33},
    239: {0: 0, 1: 6, 2: 7, 3: 8, 5: 11, 6: 12, 7: 3, 8: 4, 9: 14, 11: 16},  # music redesign, by names/music_init args
    15: {0: 0, 1: 1, 2: 2, 3: 3, 4: 4, 5: 5, 8: 6, 9: 7, 10: 8, 11: 9, 12: 10, 13: 11},  # bankside, by bankside_init args
    270: {13: 14, **{c: c + 1 for c in range(14, 24)}},  # skillmulti buttons a..j (241 adds k..r after them)
    629: {33: 43},  # side_journal tab_container
}
for k, v in MANUAL.items():
    SPECIAL[k] = {**SPECIAL.get(k, {}), **v}
print("541 prayer anchors:", sorted(SPECIAL[541].items()), file=sys.stderr)
for k, v in SPECIAL.items():
    EVID[k] = {**{x: y for x, y in EVID.get(k, {}).items() if x not in v and y not in set(v.values())}, **v}

# ---------------------------------------------------------------- top-levels first, anchored by the remap enums
res = {}
res[161] = ifmap.map_iface(161, evid(161))


def enum_vals(commit, name):
    t = ifmap.git_show(commit, "config/dump.enum")
    out, cur = [], None
    for line in t.splitlines():
        m = re.match(r"^\[(.*)\]$", line)
        if m:
            cur = m.group(1)
            continue
        if cur == name and line.startswith("val="):
            out.append(line[4:])
    return out


def p211(s):
    m = re.match(r"interface_(\d+):com_(\d+)$", s)
    return (int(m.group(1)), int(m.group(2))) if m else None


def p241(s):
    v = ifmap.COMP241.get(s)
    if v is None:
        return None
    a, b = v.split(":")
    return int(a), int(b)


COMP241_BY_NAME = {v: k for k, v in ifmap.COMP241.items()}
ifmap.COMP241 = COMP241_BY_NAME  # name -> "if:c"


def rowmap(r):
    return {row[0]: row[1] for row in r.get("rows", [])}


for target, en in ((548, 1129), (164, 1131), (165, 1132), (601, 1745)):
    a = [tuple(map(p211, v.split(","))) for v in enum_vals(ifmap.C211, "enum_%d" % en)]
    b = {}
    for v in enum_vals(ifmap.C241, "enum_%d" % en):
        k, val = map(p241, v.split(","))
        if k and val:
            b[k] = val
    anchors = {}
    for k, val in a:
        if not k or not val or val[0] != target or k[0] not in res:
            continue
        mk = (k[0], rowmap(res[k[0]]).get(k[1], -1))
        if mk in b and b[mk][0] == target:
            anchors[val[1]] = b[mk][1]
    # drop conflicting anchors (two 211 comps to one 241 comp) and non-monotone ones
    inv = {}
    for x, y in anchors.items():
        inv.setdefault(y, []).append(x)
    anchors = {x: y for x, y in anchors.items() if len(inv[y]) == 1}
    mono = anchors  # ifmap handles non-monotone (reordered) anchors separately
    res[target] = ifmap.map_iface(target, evid(target, mono))
    res[target]["anchors"] = len(mono)
    print(target, "anchored by enum", en, ":", len(mono), "anchors", file=sys.stderr)

for i in van:
    if i in res:
        continue
    res[i] = ifmap.map_iface(i, evid(i))

summary = {}
for i, r in sorted(res.items()):
    if "rows" not in r:
        summary[i] = r
        continue
    rows = r["rows"]
    summary[i] = dict(name=r["name241"], n211=r["n211"], n241=r["n241"],
                      moved=sum(1 for x in rows if x[0] != x[1]),
                      high=sum(1 for x in rows if x[2] == "high"),
                      medium=sum(1 for x in rows if x[2] == "medium"),
                      low=sum(1 for x in rows if x[2] == "low"))
json.dump({"interfaces": {str(k): v for k, v in res.items()}, "summary": {str(k): v for k, v in summary.items()}},
          open(OUT, "w"), indent=0)
print("wrote", OUT, len(res), "interfaces", file=sys.stderr)
