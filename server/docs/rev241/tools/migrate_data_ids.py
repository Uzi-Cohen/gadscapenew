#!/usr/bin/env python3
"""Rewrites a GadScape server data directory from GadScape's 211 ids to the ids of the 241 cache (cacheV).

gadconv (server/cache-convert) builds cacheV from openrs2 2746 + GadScape's content. Where a GadScape id collided with
a different 2746 entry it moved GadScape's entry and recorded the move in idmap.json (seq 15110 -> 40319, obj 34723 ->
60002, loc 35000 -> 64502, varp 3811 -> 9011, ...). The server's code resolves those ids at run time (Gad211), and
player saves are migrated at login (core-restricted M005_Rev241Ids). The data directory is plain data, so it is
rewritten once with this script, before the server runs on cacheV.

    python3 migrate_data_ids.py --idmap cacheV.idmap.json --root <server dir> --dry-run   # list what would change
    python3 migrate_data_ids.py --idmap cacheV.idmap.json --root <server dir>             # rewrite in place

--root is the server's working directory (the one holding data/ and cache/data/); a data/ directory itself is
accepted too. Nothing outside these files is touched:

  data/items/ItemDefinitions.json           item id (obj), weaponDefinition *Animation (seq), interfaceVarbit (varbit)
  data/items/ItemRequirements.json          item id (obj)
  data/items/special-attacks.json           keys = item ids (obj)
  data/items/protection_values.csv          first column = item id (obj)
  data/item_variations.json                 item ids (obj)
  data/grandexchange/*.json, cache/data/grandexchange/*.json   item ids (obj), items {"id","amount"} (obj)
  data/examines/Forced object examines.json loc id (loc) (the wiki's "Object examines.json" stays: vanilla ids)
  data/components.json                      interface id (interface)
  cache/data/npcs/combat/*.npc.json         *animation* {"id"} (seq)
  cache/data/npcs/drops/*.drops.json        itemId (obj)
  cache/data/objects/Door definitions.json  keys and values = loc ids (loc)
  every other *.json under data/            items {"id","amount"} and "itemId" fields (obj)

Skipped on purpose: *.bak, data/characters* (player saves: migrated at login, recorded per account), data/osrsbox-db (Jagex's
own item/monster data, already in 2746's id space), data/raids (Raids 4 uses 2746 ids), data/cs2_raws, data/map.

The ids an idmap moves are never ids GadScape used, so every id this script writes is a new id and running it twice
changes nothing. The applied map is written to data/rev241-idmap.applied.json; with a newer idmap the script moves the
ids it wrote before to their new targets (and back, for a move the newer idmap no longer makes), so a migrated data
directory follows later cache builds.

Seqs: idmap.json also moves vanilla 211 seqs that Jagex re-rigged or reused by 2746 ("vanilla 211 seq N ... ported for
GadScape content"). Those ports are for GadScape's own content inside the cache. The data directory names them for
vanilla NPCs, which cacheV takes from 2746 together with 2746's re-rigged models and animations (e.g. the gnomes:
2746 stand 2331 on framemap 2402, while the ported 211 seqs 189-200 are on the old framemap 319), so they are left as
they are and listed; --rewrite-vanilla-seqs rewrites them as well (only for data about GadScape's own NPCs/items).
"""
import argparse
import csv
import io
import json
import os
import re
import sys
from collections import defaultdict

KINDS = ('seq', 'obj', 'loc', 'npc', 'spot', 'varp', 'varbit', 'varc', 'interface', 'enum', 'struct', 'param', 'dbrow',
         'sprite', 'script')
STAMP = 'rev241-idmap.applied.json'


def load_idmap(path):
    with open(path, encoding='utf-8') as f:
        raw = json.load(f)
    maps = {k: {int(a): int(b) for a, b in raw.get(k, {}).items()} for k in KINDS}
    reasons = raw.get('_reasons', {})
    vanilla_seqs = {int(a) for a, r in reasons.get('seq', {}).items() if str(r).startswith('vanilla 211')}
    return maps, vanilla_seqs


def effective_map(new, previous):
    """The map to apply to a directory already migrated with `previous` (None: never migrated)."""
    if previous is None:
        return {k: dict(v) for k, v in new.items()}
    eff = {}
    for k in KINDS:
        n, p = new.get(k, {}), previous.get(k, {})
        m = {}
        for old, prev_new in p.items():
            target = n.get(old, old)
            if target != prev_new:
                m[prev_new] = target  # moved again, or no longer moved (back to the 211 id)
        for old, target in n.items():
            if old not in p:
                m[old] = target
        eff[k] = m
    return eff


class Migration:
    def __init__(self, maps, vanilla_seqs, keep_vanilla_seqs):
        self.maps = maps
        self.vanilla_seqs = vanilla_seqs
        self.keep_vanilla_seqs = keep_vanilla_seqs
        self.changes = defaultdict(list)    # file -> [(kind, old, new, where)]
        self.vanilla_refs = defaultdict(list)

    def map(self, kind, value, file, where):
        if not isinstance(value, int) or isinstance(value, bool):
            return value
        target = self.maps.get(kind, {}).get(value)
        if target is None:
            return value
        if kind == 'seq' and value in self.vanilla_seqs:
            self.vanilla_refs[file].append((value, target, where))
            if self.keep_vanilla_seqs:
                return value
        self.changes[file].append((kind, value, target, where))
        return target


# ------------------------------------------------------------------------------------------------ JSON helpers

def strip_comments(text):
    out, i, n, in_str = [], 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        if text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c); i += 1
    return ''.join(out)


def read_json(path):
    with open(path, encoding='utf-8') as f:
        text = f.read()
    try:
        return json.loads(text), text, False
    except ValueError:
        return json.loads(strip_comments(text)), text, True


def dump_like(obj, original):
    indent = '\t' if re.search(r'^\t', original, re.M) else 2
    m = re.search(r'^( +)\S', original, re.M)
    if indent != '\t' and m:
        indent = len(m.group(1))
    out = json.dumps(obj, indent=indent, ensure_ascii=False)
    return out + ('\n' if original.endswith('\n') else '')


def walk_items(node, mig, file, path='$'):
    """Generic: {"id": N, "amount": M} objects are items, "itemId" fields are item ids."""
    if isinstance(node, dict):
        if isinstance(node.get('id'), int) and 'amount' in node:
            node['id'] = mig.map('obj', node['id'], file, path + '.id')
        for k, v in list(node.items()):
            if k in ('itemId', 'item_id', 'itemID') and isinstance(v, int):
                node[k] = mig.map('obj', v, file, path + '.' + k)
            else:
                walk_items(v, mig, file, path + '.' + k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_items(v, mig, file, '%s[%d]' % (path, i))


def walk_seq_fields(node, mig, file, path):
    """*Animation int fields (seq) and interfaceVarbit (varbit), at any depth."""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if isinstance(v, int) and not isinstance(v, bool):
                if k.endswith('Animation') or k.endswith('animation'):
                    node[k] = mig.map('seq', v, file, path + '.' + k)
                elif k == 'interfaceVarbit':
                    node[k] = mig.map('varbit', v, file, path + '.' + k)
            elif isinstance(v, dict) and 'nimation' in k and isinstance(v.get('id'), int):
                v['id'] = mig.map('seq', v['id'], file, path + '.' + k + '.id')
            else:
                walk_seq_fields(v, mig, file, path + '.' + k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk_seq_fields(v, mig, file, '%s[%d]' % (path, i))


# ------------------------------------------------------------------------------------------------ file rules

def rule_item_definitions(data, mig, f):
    for i, e in enumerate(data if isinstance(data, list) else []):
        if isinstance(e, dict):
            if isinstance(e.get('id'), int):
                e['id'] = mig.map('obj', e['id'], f, '[%d].id' % i)
            walk_seq_fields(e, mig, f, '[%d]' % i)
    return data


def rule_id_list(kind):
    def rule(data, mig, f):
        for i, e in enumerate(data if isinstance(data, list) else []):
            if isinstance(e, dict) and isinstance(e.get('id'), int):
                e['id'] = mig.map(kind, e['id'], f, '[%d].id' % i)
        if kind == 'obj':
            walk_items(data, mig, f)
        return data
    return rule


def rule_keys(kind, values_too=False):
    def rule(data, mig, f):
        if not isinstance(data, dict):
            return data
        out = {}
        for k, v in data.items():
            nk = k
            if re.fullmatch(r'-?\d+', k):
                nk = str(mig.map(kind, int(k), f, 'key ' + k))
            if values_too and isinstance(v, int):
                v = mig.map(kind, v, f, 'value of ' + k)
            out[nk] = v
        return out
    return rule


def rule_item_variations(data, mig, f):
    if isinstance(data, dict):
        for k, v in data.items():
            if isinstance(v, list):
                data[k] = [mig.map('obj', x, f, '%s[%d]' % (k, i)) for i, x in enumerate(v)]
    return data


def rule_components(data, mig, f):
    for i, e in enumerate(data if isinstance(data, list) else []):
        if isinstance(e, dict) and isinstance(e.get('id'), int):
            e['id'] = mig.map('interface', e['id'], f, '[%d].id' % i)
            for j, c in enumerate(e.get('information') or []):
                d = c.get('definitions') if isinstance(c, dict) else None
                if isinstance(d, dict) and isinstance(d.get('interfaceId'), int):
                    d['interfaceId'] = mig.map('interface', d['interfaceId'], f, '[%d].information[%d]' % (i, j))
    return data


def rule_npc_combat(data, mig, f):
    walk_seq_fields(data, mig, f, '$')
    return data


def rule_generic_items(data, mig, f):
    walk_items(data, mig, f)
    return data


SKIP_DIRS = ('characters', 'characters-hiscores', 'characters-hardware', 'osrsbox-db', 'raids', 'cs2_raws', 'map', 'logs',
             'polls')


def rules_for(rel):
    """(rule, kind label) for a path relative to the root, or None."""
    p = rel.replace(os.sep, '/')
    low = p.lower()
    name = os.path.basename(low)
    if name.endswith('.bak'):
        return None                     # backups are left alone
    if low.endswith('items/itemdefinitions.json'):
        return rule_item_definitions
    if name == 'itemrequirements.json':
        return rule_id_list('obj')
    if name == 'special-attacks.json':
        return rule_keys('obj')
    if name == 'item_variations.json':
        return rule_item_variations
    if '/grandexchange/' in '/' + low:
        return rule_id_list('obj')
    if '/examines/' in '/' + low and name.startswith('forced') and 'object' in name:
        return rule_id_list('loc')      # GadScape's own examines for its locs
    if '/examines/' in '/' + low:
        return None                     # the wiki's examines of vanilla ids: they stay with the 2746 entries
    if name == 'components.json':
        return rule_components
    if '/npcs/combat/' in '/' + low and name.endswith('.json'):
        return rule_npc_combat
    if '/npcs/drops/' in '/' + low and name.endswith('.json'):
        return rule_generic_items
    if name == 'door definitions.json':
        return rule_keys('loc', values_too=True)
    if name.endswith('.json'):
        return rule_generic_items
    return None


def csv_first_column(path, mig, rel):
    with open(path, encoding='utf-8', newline='') as f:
        text = f.read()
    rows = list(csv.reader(io.StringIO(text)))
    changed = False
    for i, row in enumerate(rows):
        if row and re.fullmatch(r'\d+', row[0].strip()):
            old = int(row[0])
            new = mig.map('obj', old, rel, 'row %d' % (i + 1))
            if new != old:
                row[0] = str(new); changed = True
    if not changed:
        return None
    out = io.StringIO()
    w = csv.writer(out, lineterminator='\r\n' if '\r\n' in text else '\n')
    w.writerows(rows)
    return out.getvalue()


def textual_item_ids(text, mig, rel):
    """Files with comments: only "itemId": N and {"id": N, "amount": ...} are rewritten, in place."""
    def sub_item_id(m):
        old = int(m.group(2))
        new = mig.map('obj', old, rel, m.group(1).strip())
        return m.group(1) + str(new)
    text = re.sub(r'("itemId"\s*:\s*)(\d+)', sub_item_id, text)
    text = re.sub(r'("id"\s*:\s*)(\d+)(?=\s*,\s*(?://[^\n]*\n\s*)?"amount")', sub_item_id, text)
    return text


def report_saves(directory, root, maps):
    """Player saves are migrated at login (M005_Rev241Ids); only say which ones hold moved ids."""
    objs, varps = maps.get('obj', {}), maps.get('varp', {})
    holding = []
    for fn in sorted(os.listdir(directory)):
        if not fn.endswith('.json'):
            continue
        try:
            with open(os.path.join(directory, fn), encoding='utf-8') as f:
                save = json.load(f)
        except Exception:
            continue
        items = []

        def walk(o):
            if isinstance(o, dict):
                if isinstance(o.get('id'), int) and 'amount' in o and o['id'] in objs:
                    items.append(o['id'])
                for v in o.values():
                    walk(v)
            elif isinstance(o, list):
                for v in o:
                    walk(v)
        walk(save)
        sv = ((save.get('varManager') or {}).get('serializedVars') or {}) if isinstance(save, dict) else {}
        vs = [int(k) for k in sv if re.fullmatch(r'\d+', k) and int(k) in varps]
        if items or vs:
            holding.append('%s (items %s, varps %s)' % (fn, sorted(set(items)), sorted(vs)))
    if holding:
        print('player saves holding moved ids (moved at login by M005_Rev241Ids, not by this script): %s: %s' % (
            os.path.relpath(directory, root), '; '.join(holding)))


def find_root(root):
    root = os.path.abspath(root)
    if os.path.basename(root) == 'data' and not os.path.isdir(os.path.join(root, 'data')):
        return os.path.dirname(root), ['data']
    tops = [d for d in ('data', os.path.join('cache', 'data')) if os.path.isdir(os.path.join(root, d))]
    return root, tops


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--idmap', required=True, help='gadconv idmap.json of the cache the server will run on')
    ap.add_argument('--root', required=True, help='server working directory (holds data/ and cache/data/)')
    ap.add_argument('--dry-run', action='store_true', help='only list the changes')
    ap.add_argument('--rewrite-vanilla-seqs', action='store_true', help='also rewrite vanilla 211 seqs gadconv ported (see above)')
    ap.add_argument('--previous', help='idmap applied before (default: data/%s if present)' % STAMP)
    ap.add_argument('--verbose', action='store_true', help='print every change')
    args = ap.parse_args()

    new_maps, vanilla_seqs = load_idmap(args.idmap)
    root, tops = find_root(args.root)
    if not tops:
        sys.exit('no data/ or cache/data/ under ' + root)
    stamp_path = os.path.join(root, 'data', STAMP) if 'data' in tops else os.path.join(root, tops[0], STAMP)
    previous = None
    prev_path = args.previous or (stamp_path if os.path.isfile(stamp_path) else None)
    if prev_path:
        previous, _ = load_idmap(prev_path)
        print('previously applied: %s' % prev_path)
    maps = effective_map(new_maps, previous)
    mig = Migration(maps, vanilla_seqs, not args.rewrite_vanilla_seqs)
    print('id map: %s (%s)' % (args.idmap, ', '.join('%s %d' % (k, len(v)) for k, v in maps.items() if v)))

    to_write = {}
    skipped = []
    for top in tops:
        for dirpath, dirnames, filenames in os.walk(os.path.join(root, top)):
            rel_dir = os.path.relpath(dirpath, os.path.join(root, top))
            first = rel_dir.split(os.sep)[0]
            if top == 'data' and first in SKIP_DIRS:
                dirnames[:] = []
                skipped.append(os.path.join(top, first))
                if first == 'characters':
                    report_saves(dirpath, root, maps)
                continue
            dirnames.sort()
            for fn in sorted(filenames):
                path = os.path.join(dirpath, fn)
                rel = os.path.relpath(path, root)
                if fn == STAMP:
                    continue
                if fn.lower().endswith('.csv') and 'protection_values' in fn.lower():
                    out = csv_first_column(path, mig, rel)
                    if out is not None:
                        to_write[path] = out
                    continue
                rule = rules_for(rel)
                if rule is None:
                    continue
                try:
                    data, text, commented = read_json(path)
                except Exception as e:  # not JSON (or broken): report, never touch
                    print('  ! %s: not readable as JSON (%s); left as is' % (rel, str(e)[:80]))
                    continue
                before = len(mig.changes.get(rel, []))
                if commented:
                    if rule is rule_generic_items:
                        out = textual_item_ids(text, mig, rel)
                        if out != text:
                            to_write[path] = out
                    else:
                        rule(data, mig, rel)
                        if len(mig.changes.get(rel, [])) > before:
                            print('  ! %s has comments and needs %d change(s): edit it by hand' % (rel, len(mig.changes[rel]) - before))
                    continue
                data = rule(data, mig, rel)
                if len(mig.changes.get(rel, [])) > before:
                    to_write[path] = dump_like(data, text)

    total = 0
    for rel in sorted(mig.changes):
        ch = mig.changes[rel]
        total += len(ch)
        by_kind = defaultdict(set)
        for kind, old, new, where in ch:
            by_kind[kind].add((old, new))
        print('%s: %d change(s); %s' % (rel, len(ch), '; '.join(
            '%s %s' % (k, ', '.join('%d->%d' % p for p in sorted(v)[:12]) + (' ...' if len(v) > 12 else ''))
            for k, v in sorted(by_kind.items()))))
        if args.verbose:
            for kind, old, new, where in ch:
                print('    %s %s %d -> %d' % (where, kind, old, new))
    if mig.vanilla_refs:
        n = sum(len(v) for v in mig.vanilla_refs.values())
        print('vanilla 211 seqs gadconv ported (%s): %d reference(s) in %d file(s), e.g. %s' % (
            'rewritten' if args.rewrite_vanilla_seqs else 'kept', n, len(mig.vanilla_refs),
            ', '.join('%s %d' % (os.path.basename(f), v[0][0]) for f, v in sorted(mig.vanilla_refs.items())[:8])))
    if skipped:
        print('skipped: %s' % ', '.join(sorted(set(skipped))))
    print('%d change(s) in %d file(s)%s' % (total, len(to_write), ' (dry run: nothing written)' if args.dry_run else ''))
    if args.dry_run:
        return
    for path, content in to_write.items():
        tmp = path + '.rev241.tmp'
        with open(tmp, 'w', encoding='utf-8', newline='') as f:
            f.write(content)
        os.replace(tmp, path)
    stamp = {k: {str(a): b for a, b in sorted(v.items())} for k, v in new_maps.items() if v}
    stamp['_format'] = 1
    stamp['_source'] = os.path.abspath(args.idmap)
    os.makedirs(os.path.dirname(stamp_path), exist_ok=True)
    with open(stamp_path, 'w', encoding='utf-8') as f:
        json.dump(stamp, f, indent=1, sort_keys=False)
    print('wrote %d file(s); applied map recorded in %s' % (len(to_write), os.path.relpath(stamp_path, root)))


if __name__ == '__main__':
    main()
