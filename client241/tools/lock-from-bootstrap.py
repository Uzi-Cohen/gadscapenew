#!/usr/bin/env python3
"""Turn RuneLite's bootstrap.json into client241/runelite-<version>.lock.

Usage: python3 -I lock-from-bootstrap.py <bootstrap.json> <out.lock>

The lock lists every jar RuneLite's own launcher would download for that version:
  <sha256> <size> <platform> <file name> <url>
platform is "all", or os[-arch] (win, win-x86, win-aarch64, linux, linux-aarch64, macos, macos-aarch64).
It also records the JVM arguments RuneLite's launcher passes to the client: lines starting with "jvm " (all
systems, clientJvm17Arguments) and "jvm-mac " (macOS, clientJvm17MacArguments; used instead of the "jvm" lines there).
"""
import json
import sys

src, out = sys.argv[1], sys.argv[2]
with open(src) as f:
    d = json.load(f)

lines = [
    "# RuneLite %s artifact lock for the GadScape 241 test client." % d["version"],
    "# Source: https://static.runelite.net/bootstrap.json (version %s)." % d["version"],
    "# Columns: sha256 size platform file url. platform = all | os[-arch]. Regenerate with tools/lock-from-bootstrap.py.",
    "version %s" % d["version"],
]
for a in d["artifacts"]:
    plats = a.get("platform")
    if not plats:
        plat = "all"
    else:
        names = []
        for p in plats:
            n = p["name"]
            if p.get("arch"):
                n += "-" + p["arch"]
            names.append(n)
        plat = ",".join(names)
    lines.append("%s %d %s %s %s" % (a["hash"], a["size"], plat, a["name"], a["path"]))
for arg in d.get("clientJvm17Arguments", []):
    lines.append("jvm %s" % arg)
for arg in d.get("clientJvm17MacArguments", []):
    lines.append("jvm-mac %s" % arg)
with open(out, "w") as f:
    f.write("\n".join(lines) + "\n")
print("wrote %s (%d artifacts)" % (out, len(d["artifacts"])))
