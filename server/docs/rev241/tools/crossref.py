"""Independent evidence for the component remap: component references inside clientscripts and enums
that exist in both dumps. For every script present in both revisions, lines that are identical after
normalising component references pair their references in order. Enums pair by key.

python3 -I crossref.py <remap.json> <out-evidence.json>
"""
import difflib, json, os, re, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ifmap  # noqa
import cfg  # noqa

remap = json.load(open(sys.argv[1]))["interfaces"]
NAME2ID = {v: k for k, v in ifmap.COMP241.items()}  # "ifname:cname" -> "if:c"
R211 = re.compile(r"interface_(\d+):com_(\d+)")
R241 = re.compile(r"\b([a-z0-9_]+:[a-z0-9_]+)\b")


def refs211(line):
    return [(int(a), int(b)) for a, b in R211.findall(line)]


def refs241(line):
    out = []
    for m in R241.findall(line):
        v = NAME2ID.get(m)
        if v:
            a, b = v.split(":")
            out.append((int(a), int(b)))
    return out


def norm211(line):
    return R211.sub("<C>", line)


def norm241(line):
    return R241.sub(lambda m: "<C>" if m.group(1) in NAME2ID else m.group(1), line)


votes = defaultdict(Counter)


def vote(a, b, w=1):
    if a[0] == b[0]:
        votes[a][b[1]] += w


def scripts(commit):
    out = {}
    for line in ifmap.git_show(commit, "symbols/clientscript.sym").splitlines():
        if line.strip():
            k, v = line.split("\t", 1)
            out[int(k)] = v
    return out


s211, s241 = scripts(ifmap.C211), scripts(ifmap.C241)
n = 0
for sid, name in s211.items():
    if s241.get(sid) is None:
        continue
    ta = ifmap.git_show(ifmap.C211, "script/%s.cs2" % name)
    if ta is None or "interface_" not in ta:
        continue
    tb = ifmap.git_show(ifmap.C241, "script/%s.cs2" % s241[sid])
    if tb is None:
        continue
    la, lb = ta.splitlines(), tb.splitlines()
    na, nb = [norm211(x) for x in la], [norm241(x) for x in lb]
    sm = difflib.SequenceMatcher(None, na, nb, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag != "equal":
            continue
        for k in range(i2 - i1):
            ra, rb = refs211(la[i1 + k]), refs241(lb[j1 + k])
            if len(ra) == len(rb):
                for x, y in zip(ra, rb):
                    vote(x, y)
    n += 1
print("scripts compared:", n, file=sys.stderr)

E1, E2 = cfg.blocks(ifmap.C211, "enum"), cfg.blocks(ifmap.C241, "enum")
ne = 0
for en, blk in E1.items():
    if en not in E2:
        continue
    a = {v.split(",", 1)[0]: v.split(",", 1)[1] for v in cfg.get(blk, "val")}
    b = {v.split(",", 1)[0]: v.split(",", 1)[1] for v in cfg.get(E2[en], "val")}
    hit = False
    for k, va in a.items():
        ra = refs211(va)
        if not ra or k not in b:
            continue
        rb = refs241(b[k])
        if len(ra) == len(rb):
            for x, y in zip(ra, rb):
                vote(x, y, 2)
            hit = True
    # component-keyed enums
    ka = {refs211(k)[0]: v for k, v in a.items() if refs211(k)}
    kb = {refs241(k)[0]: v for k, v in b.items() if refs241(k)}
    if ka and kb:
        # pair keys by identical values when values are unique
        vb = defaultdict(list)
        for k, v in kb.items():
            vb[v].append(k)
        for k, v in ka.items():
            if not refs211(v) and len(vb.get(v, [])) == 1:
                vote(k, vb[v][0], 1)
                hit = True
    ne += hit
print("enums with component evidence:", ne, file=sys.stderr)

out = {}
dis = 0
for (i, c), cnt in sorted(votes.items()):
    best, bv = cnt.most_common(1)[0]
    tot = sum(cnt.values())
    r = remap.get(str(i))
    mine = None
    if r and "rows" in r:
        for row in r["rows"]:
            if row[0] == c:
                mine = row[1]
    out.setdefault(str(i), {})[str(c)] = [best, bv, tot, mine]
    if mine is not None and mine != best and bv * 2 > tot:
        dis += 1
json.dump(out, open(sys.argv[2], "w"), indent=0)
print("components with evidence:", len(votes), "disagreements with remap:", dis, file=sys.stderr)
