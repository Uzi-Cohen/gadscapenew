"""Align rev211 interface components to rev241 components using the Joshua-F osrs-dumps.

Usage: python3 -I ifmap.py <out.json> [iface ...]
Writes {iface: {"name241": str, "n211": int, "n241": int, "rows": [[c211, c241|-1, conf, score, name241, diffkeys], ...],
                "added241": [[c241, name], ...]}}
"""
import json, os, re, subprocess, sys

REPO = os.environ.get("OSRS_DUMPS", "osrs-dumps")  # clone of github.com/Joshua-F/osrs-dumps (blobs for c43ec08 and 6e5033a)
C211, C241 = "c43ec08", "6e5033a"


_CAT = None
_MEMO = {}


def git_show(commit, path):
    global _CAT
    key = commit + ":" + path
    if key in _MEMO:
        return _MEMO[key]
    if _CAT is None:
        _CAT = subprocess.Popen(["git", "-C", REPO, "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    _CAT.stdin.write((key + "\n").encode())
    _CAT.stdin.flush()
    header = _CAT.stdout.readline().decode()
    if header.endswith("missing\n"):
        _MEMO[key] = None
        return None
    size = int(header.split()[2])
    data = _CAT.stdout.read(size)
    _CAT.stdout.read(1)
    _MEMO[key] = data.decode("utf-8", "replace")
    return _MEMO[key]


def load_syms(commit, kind):
    out = {}
    for line in git_show(commit, "symbols/%s.sym" % kind).splitlines():
        if not line.strip():
            continue
        k, v = line.split("\t", 1)
        out[k] = v
    return out


IF241 = load_syms(C241, "interface")  # "id" -> name
IF241.update(load_syms(C241, "toplevelinterface"))
IF241.update(load_syms(C241, "overlayinterface"))
IF241_BY_NAME = {v: int(k) for k, v in IF241.items()}
COMP241 = load_syms(C241, "component")  # "if:c" -> "ifname:cname"


def parse_if3(text):
    comps = []
    cur = None
    for line in text.splitlines():
        m = re.match(r"^// (\d+):(\d+)$", line)
        if m:
            cur = {"_id": int(m.group(2)), "_props": {}}
            comps.append(cur)
            continue
        if cur is None:
            continue
        m = re.match(r"^\[(.*)\]$", line)
        if m and "_name" not in cur:
            cur["_name"] = m.group(1)
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            # repeated keys (e.g. multiple inv slots) -> append
            if k in cur["_props"]:
                cur["_props"][k] += "\n" + v
            else:
                cur["_props"][k] = v
    return comps


def load_iface(rev, iface):
    if rev == 211:
        t = git_show(C211, "interface/interface_%d.if3" % iface)
    else:
        name = IF241.get(str(iface))
        if name is None:
            return None, None
        t = git_show(C241, "interface/%s.if3" % name)
    if t is None:
        return None, None
    comps = parse_if3(t)
    return comps, (IF241.get(str(iface)) if rev == 241 else "interface_%d" % iface)


def normalise(comps, rev, iface, ifname):
    """Return list of dicts with parent index resolved and component refs normalised to (if, idx)."""
    by_name = {c["_name"]: c["_id"] for c in comps}
    out = []
    for c in comps:
        p = dict(c["_props"])
        layer = p.pop("layer", None)
        parent = by_name.get(layer, -2) if layer is not None else -1
        out.append({"id": c["_id"], "name": c["_name"], "type": p.get("type"), "parent": parent, "props": p})
    return out


REF_RE = re.compile(r"([A-Za-z0-9_]+):([A-Za-z0-9_]+)")


def norm_value(v, rev):
    """Replace interface:component references by if:index ids so 211 and 241 are comparable-ish."""
    def rep(m):
        a, b = m.group(1), m.group(2)
        if rev == 211:
            ma = re.match(r"interface_(\d+)$", a)
            mb = re.match(r"com_(\d+)$", b)
            if ma and mb:
                return "<%s:?>" % ma.group(1)
        else:
            if a in IF241_BY_NAME:
                i = IF241_BY_NAME[a]
                return "<%d:?>" % i
        return m.group(0)
    return REF_RE.sub(rep, v)


IGNORE = set()


def sim(a, b):
    if a["type"] != b["type"]:
        return 0.0, ["type"]
    keys = set(a["props"]) | set(b["props"])
    keys.discard("type")
    tot = 0.0
    got = 0.0
    diff = []
    weights = {"x": 1, "y": 1, "width": 1, "height": 1}
    for k in keys:
        if k == "onload":
            w = 4.0
        elif k in ("text", "op1", "op2", "op3", "graphic", "model", "onop", "inv", "clientcode"):
            w = 2.0
        elif k in ("x", "y", "width", "height"):
            w = 0.5
        else:
            w = 1.0
        tot += w
        va, vb = a["props"].get(k), b["props"].get(k)
        if va is not None and vb is not None:
            na, nb = norm_value(va, 211), norm_value(vb, 241)
            # script calls: compare script name only for partial credit
            if na == nb:
                got += w
            elif re.sub(r"\(.*", "", na, flags=re.S) == re.sub(r"\(.*", "", nb, flags=re.S) and "(" in na:
                got += w * (0.25 if k == "onload" else 0.6)
                diff.append(k)
            else:
                diff.append(k)
        else:
            diff.append(k)
    base = 1.0 if tot == 0 else got / tot
    return base, diff


GEOM = ("x", "y", "width", "height", "xmode", "ymode", "widthmode", "heightmode")


def geom_equal(a, b):
    return a["type"] == b["type"] and all(a["props"].get(k) == b["props"].get(k) for k in GEOM)


def script_equal(a, b):
    va, vb = a["props"].get("onload"), b["props"].get("onload")
    if not va or not vb or a["type"] != b["type"]:
        return False
    na, nb = norm_value(va, 211), norm_value(vb, 241)
    return na == nb and re.search(r"[,(]\s*-?\d+\s*[,)]", na) is not None


def align(A, B, anchors=None):
    n, m = len(A), len(B)
    S_ = [[None] * m for _ in range(n)]
    anchors = anchors or {}
    ai = {a: b for a, b in anchors.items()}
    aj = {b: a for a, b in anchors.items()}
    for i in range(n):
        for j in range(m):
            if i in ai or j in aj:
                if ai.get(i) == j:
                    s, d = sim(A[i], B[j])
                    S_[i][j] = (max(s, 0.999) if s < 1.0 else 1.0, d + ["anchor"] if s < 1.0 else d)
                else:
                    S_[i][j] = (0.0, ["anchor-excl"])
                continue
            # band limit to keep it fast on huge interfaces
            if abs(i - j) > max(60, abs(n - m) + 40):
                S_[i][j] = (0.0, ["band"])
            else:
                S_[i][j] = sim(A[i], B[j])
    # DP maximise sum of (score) for matched pairs with score>=0.35
    dp = [[0.0] * (m + 1) for _ in range(n + 1)]
    bt = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            # tie-break: prefer matching (earliest 241 candidate), then skipping a 241 component (insertions)
            s = S_[i][j][0]
            best, mv = -1.0, 0
            if s >= 0.35:
                best, mv = dp[i + 1][j + 1] + s + 0.05, 3  # small bonus for matching vs skipping
            if dp[i][j + 1] > best + 1e-9:
                best, mv = dp[i][j + 1], 2
            if dp[i + 1][j] > best + 1e-9:
                best, mv = dp[i + 1][j], 1
            dp[i][j] = best
            bt[i][j] = mv
    pairs = {}
    i = j = 0
    while i < n and j < m:
        mv = bt[i][j]
        if mv == 3:
            pairs[i] = j
            i += 1
            j += 1
        elif mv == 1:
            i += 1
        else:
            j += 1
    return pairs, S_


def map_iface(iface, anchors=None):
    A, _ = load_iface(211, iface)
    B, name = load_iface(241, iface)
    if A is None or B is None:
        return {"name241": name, "missing": "211" if A is None else "241"}
    A = normalise(A, 211, iface, None)
    B = normalise(B, 241, iface, name)
    mono = True
    if anchors:
        last = -1
        for x, y in sorted(anchors.items()):
            if y <= last:
                mono = False
            last = y
    if mono:
        pairs, S_ = align(A, B, anchors)
    else:
        # reordered layout: force the anchors, align the rest by content only
        ra = [a for a in A if a["id"] not in anchors]
        rb = [b for b in B if b["id"] not in set(anchors.values())]
        sub, _ = align(ra, rb, None)
        pairs = {ra[x]["id"]: rb[y]["id"] for x, y in sub.items()}
        pairs.update(anchors)
        S_ = [[None] * len(B) for _ in range(len(A))]
        for x, y in pairs.items():
            S_[x][y] = sim(A[x], B[y])
    rows = []
    for i, a in enumerate(A):
        j = pairs.get(i, -1)
        if j == -1:
            rows.append([a["id"], -1, "low", 0.0, "", ["unmatched"]])
            continue
        b = B[j]
        s, diff = S_[i][j]
        # parent consistency
        pa = a["parent"]
        pb = b["parent"]
        pok = (pa < 0 and pb < 0 and pa == pb) or (pa >= 0 and pairs.get(pa, -9) == pb)
        if not pok:
            diff = diff + ["parent"]
        if s == 1.0 and pok:
            conf = "high"
        elif anchors and anchors.get(i) == j:
            conf = "high"
            diff = diff + ["anchored"]
        elif pok and geom_equal(a, b) and len([k for k in diff if k in ("text", "graphic", "model", "inv", "type")]) == 0:
            # same type, geometry and parent; only ops/handlers/flags changed -> same component
            conf = "high"
            diff = diff + ["geometry-match"]
        elif pok and script_equal(a, b):
            # identical onload script call with literal arguments (e.g. stats_init(..., 21, 1)) -> same component
            conf = "high"
            diff = diff + ["script-match"]
        elif s >= 0.8 and pok:
            # unique? no other B within window with same or better score
            rivals = [jj for jj in range(max(0, j - 30), min(len(B), j + 30)) if jj != j and S_[i][jj] is not None and S_[i][jj][0] >= s]
            conf = "high" if not rivals else "medium"
        elif s >= 0.6:
            conf = "medium"
        else:
            conf = "low"
        rows.append([a["id"], b["id"], conf, round(s, 3), b["name"], diff])
    used = set(pairs.values())
    added = [[b["id"], b["name"]] for j, b in enumerate(B) if j not in used]
    return {"name241": name, "n211": len(A), "n241": len(B), "rows": rows, "added241": added}


if __name__ == "__main__":
    out = sys.argv[1]
    ifaces = [int(x) for x in sys.argv[2:]]
    res = {}
    for i in ifaces:
        res[i] = map_iface(i)
        r = res[i]
        if "rows" in r:
            nchg = sum(1 for row in r["rows"] if row[0] != row[1])
            nh = sum(1 for row in r["rows"] if row[2] == "high")
            print(i, r["name241"], r["n211"], "->", r["n241"], "changed ids:", nchg, "high:", nh, "/", r["n211"], file=sys.stderr)
        else:
            print(i, r, file=sys.stderr)
    json.dump(res, open(out, "w"), indent=0)
