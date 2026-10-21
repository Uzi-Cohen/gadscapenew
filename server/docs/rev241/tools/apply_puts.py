"""Rewrite put(componentId[, slot], "name") ids in an Interface subclass with the 211->241 remap.

python3 -I apply_puts.py <remap.json> <iface> <file> [--dry] [--override c211=c241 ...]
Only high-confidence rows (or overrides) are applied; others are reported and left untouched.
A trailing comment "// 211: N" is added to every rewritten line.
"""
import json, re, sys

remap = json.load(open(sys.argv[1]))["interfaces"]
iface = sys.argv[2]
path = sys.argv[3]
dry = "--dry" in sys.argv
over = {}
if "--override" in sys.argv:
    for a in sys.argv[sys.argv.index("--override") + 1:]:
        if "=" in a:
            x, y = a.split("=")
            over[int(x)] = int(y)
rows = {r[0]: r for r in remap[iface]["rows"]}
src = open(path).read().split("\n")
out, keys, report = [], {}, []
for n, line in enumerate(src):
    m = re.match(r"^(\s*)put\(\s*(\d+)\s*(,\s*\d+\s*)?,(.*)$", line)
    if not m or "// 211:" in line:
        out.append(line)
        continue
    c = int(m.group(2))
    if c in over:
        new, why = over[c], "override"
    elif c in rows and rows[c][2] == "high" and rows[c][1] >= 0:
        new, why = rows[c][1], "high:" + rows[c][4]
    else:
        r = rows.get(c)
        report.append("  KEEP  line %d put(%d): %s" % (n + 1, c, ("%s %s" % (r[2], r[1])) if r else "out of range"))
        keys.setdefault((c, m.group(3)), []).append(n + 1)
        out.append(line)
        continue
    nl = "%sput(%d%s,%s" % (m.group(1), new, m.group(3) or "", m.group(4))
    if new != c:
        nl += " // 211: %d" % c
    report.append("  %s line %d put(%d) -> %d (%s)" % ("SAME " if new == c else "MOVE ", n + 1, c, new, why))
    keys.setdefault((new, m.group(3)), []).append(n + 1)
    out.append(nl)
dups = {k: v for k, v in keys.items() if len(v) > 1}
print(path.split("/")[-1], iface)
print("\n".join(report))
if dups:
    print("  !! DUPLICATE keys after rewrite:", dups)
if not dry and not dups:
    open(path, "w").write("\n".join(out))
    print("  written")
