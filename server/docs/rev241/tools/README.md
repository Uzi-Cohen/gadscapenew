# rev241 interface remap tools

Python 3 scripts that produced `../interface-remap.tsv` and `../../rev241-interfaces.md`.
They read a clone of https://github.com/Joshua-F/osrs-dumps (set `OSRS_DUMPS=/path/to/clone`;
commits `c43ec08` = rev 211 and `6e5033a` = rev 241 must be present).

    python3 crossref.py remap.json evidence.json      # script/enum evidence (needs a first remap.json)
    python3 build_remap.py <server-dir> remap.json    # structural alignment + anchors -> remap.json
    python3 ifargs.py remap.json evidence.json        # hook-argument evidence, then rerun build_remap
    python3 enumchk.py <interfaces-json> 1129 1130 1131 1132 1745   # verify panes against the remap enums
    python3 scripts.py <server-dir>; python3 scripts2.py  # clientscript signatures -> scripts.json
    python3 varchk.py                                  # varbit/varp definitions -> varchk.json
    python3 gen_doc.py <server-dir>                    # writes the docs
    python3 show.py remap.json <iface> [all]           # side-by-side view of one interface
    python3 usage.py <server-dir> remap.json <iface>.. # literal component ids used by the server
    python3 apply_puts.py remap.json <iface> <file>    # rewrite put(id, name) with high-confidence rows

`build_remap.py` holds the hand-verified overrides (`MANUAL`) and the prayer anchors.
