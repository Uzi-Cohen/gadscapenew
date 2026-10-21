"""Side-by-side view of an interface mapping. python3 -I show.py <remap.json> <iface> [all]"""
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ifmap  # noqa

d = json.load(open(sys.argv[1]))["interfaces"]
iface = int(sys.argv[2])
full = len(sys.argv) > 3
r = d[str(iface)]
A, _ = ifmap.load_iface(211, iface)
B, name = ifmap.load_iface(241, iface)


def summ(c):
    p = c["_props"]
    keys = ["type", "layer", "x", "y", "width", "height", "text", "op1", "op2", "graphic", "model", "onload", "onop", "inv", "hide"]
    return " ".join("%s=%s" % (k, p[k][:40].replace("\n", "/")) for k in keys if k in p)


print(iface, name, "n211", r["n211"], "n241", r["n241"], "added241", r["added241"])
for row in r["rows"]:
    a = A[row[0]]
    b = B[row[1]] if row[1] >= 0 else None
    flag = "" if row[2] == "high" else "  <<<"
    if not full and row[2] == "high" and row[0] == row[1]:
        continue
    print("%3d -> %3d %-6s %.2f %-28s %s%s" % (row[0], row[1], row[2], row[3], row[4][:28], ",".join(row[5]), flag))
    print("      211: " + summ(a))
    if b:
        print("      241: " + summ(b))
for c, nm in r["added241"]:
    print("  +241 %3d %s: %s" % (c, nm, summ(B[c])))
