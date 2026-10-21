"""Add evidence from interface hook arguments: a hook (onload/onop/...) whose script name occurs once in an
interface in both revisions passes component references in the same argument order. Arguments are aligned
with difflib (references become <if:?> placeholders), then referenced components are paired.

python3 -I ifargs.py <remap.json> <evidence.json>   (updates evidence.json in place)
"""
import difflib, json, os, re, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ifmap  # noqa

remap = json.load(open(sys.argv[1]))["interfaces"]
ev = json.load(open(sys.argv[2]))
NAME2ID = {v: k for k, v in ifmap.COMP241.items()}
HOOKS = ("onload", "onop", "onmouseover", "onmouseleave", "onvartransmit", "oninvtransmit", "onstattransmit", "ontimer",
         "onclick", "onhold", "onrelease", "ondrag", "ondragcomplete", "onkey", "onscrollwheel", "onresize")


def split_call(v):
    m = re.match(r"^([a-z0-9_]+)\((.*)\)(\{.*\})?$", v.strip(), re.S)
    if not m:
        return None, []
    args, depth, cur = [], 0, ""
    for ch in m.group(2):
        if ch == "," and depth == 0:
            args.append(cur.strip())
            cur = ""
            continue
        if ch in "([{":
            depth += 1
        if ch in ")]}":
            depth -= 1
        cur += ch
    if cur.strip():
        args.append(cur.strip())
    return m.group(1), args


def ref211(a):
    m = re.match(r"^interface_(\d+):com_(\d+)$", a)
    return (int(m.group(1)), int(m.group(2))) if m else None


def ref241(a):
    v = NAME2ID.get(a)
    if not v:
        return None
    x, y = v.split(":")
    return int(x), int(y)


def tok(a, r):
    return "<%d:?>" % r[0] if r else a


votes = defaultdict(Counter)
for i in remap:
    i = int(i)
    A, _ = ifmap.load_iface(211, i)
    B, _ = ifmap.load_iface(241, i)
    if not A or not B:
        continue
    calls_a, calls_b = defaultdict(list), defaultdict(list)
    for c in A:
        for h in HOOKS:
            if h in c["_props"]:
                n, args = split_call(c["_props"][h])
                if n:
                    calls_a[(h, n)].append(args)
    for c in B:
        for h in HOOKS:
            if h in c["_props"]:
                n, args = split_call(c["_props"][h])
                if n:
                    calls_b[(h, n)].append(args)
    for key, la in calls_a.items():
        lb = calls_b.get(key)
        if not lb or len(la) != 1 or len(lb) != 1:
            continue
        aa, bb = la[0], lb[0]
        ra = [ref211(x) for x in aa]
        rb = [ref241(x) for x in bb]
        if not any(ra):
            continue
        rows = {row[0]: row for row in remap[str(i)].get("rows", [])} if "rows" in remap[str(i)] else {}

        def guide(x, r):
            if not r:
                return x
            if r[0] == i and r[1] in rows and rows[r[1]][2] == "high" and rows[r[1]][1] >= 0:
                return "R%d" % rows[r[1]][1]
            if r[0] != i:
                return "<%d:?>" % r[0]
            return "U%d" % r[1]  # unknown: never equal to anything

        ta = [guide(x, r) for x, r in zip(aa, ra)]
        tb = [("R%d" % r[1] if r[0] == i else "<%d:?>" % r[0]) if r else x for x, r in zip(bb, rb)]
        sm = difflib.SequenceMatcher(None, ta, tb, autojunk=False)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "replace" and i2 - i1 == j2 - j1:
                for k in range(i2 - i1):
                    x, y = ra[i1 + k], rb[j1 + k]
                    if x and y and x[0] == y[0]:
                        votes[x][y[1]] += 2

added = 0
for (i, c), cnt in votes.items():
    d = ev.setdefault(str(i), {})
    old = d.get(str(c))
    merged = Counter()
    if old:
        merged[old[0]] += old[1]
        # keep the minority mass of the old record as an unknown competitor
        if old[2] > old[1]:
            merged[-99] += old[2] - old[1]
    merged.update(cnt)
    best, bv = merged.most_common(1)[0]
    d[str(c)] = [best, bv, sum(merged.values()), old[3] if old else None]
    added += 1
json.dump(ev, open(sys.argv[2], "w"), indent=0)
print("hook-argument evidence for", added, "components", file=sys.stderr)
