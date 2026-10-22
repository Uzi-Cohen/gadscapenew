#!/usr/bin/env python3
"""rev241 static interface check: every (interface, component) the server names must exist in the cache it runs on.

    python3 -I static_check.py --server <server dir> --dump <comps.tsv> --runtime <runtime.tsv> [--dump211 <comps211.tsv>]
                               [--remap cacheV.component-remap.tsv] [--out static-check.txt] [--idmap idmap.json]

Inputs come from IfStaticCheck.java (run both through static_check.sh):
  --dump      every component of the target cache (cacheV), decoded with the server's own ComponentDefinitions
  --runtime   the server's run-time tables: GameInterface ids (after Gad211.iface), InterfacePosition per pane (pane enums),
              put(component, name) of every Interface plugin after attach(), getInterfaceIds() of every UserInterface
  --dump211   optional: the same dump of GadScape's 211 interfaces (cacheU) for the type comparison (with --remap)

Source references (Java + Kotlin under core, core-restricted, plugins, scripts, cache/src/main/java; tests, build output,
cache-convert and the cache packing DSL are skipped):
  call     the (interface, component) arguments of sendComponent*, sendClearItemContainer, sendUpdateItemContainer,
           sendUpdateItemsInSlot, sendLineSpacing, ComponentDefinitions.get, sendInterface(id, comp, PaneType.X, ..),
           closeInterfaceSpecific(comp, PaneType.X), sendMoveInterface, getInterface(PaneType.X, comp), ...
  hash     `iface << 16 | comp`, `(iface << 16) + comp`, Kotlin `iface shl 16 or comp` and `iface component comp`
  handler  `componentId == N`, `case N` of `switch (componentId)`, Kotlin `when (componentId) { N -> }`, range bounds,
           in a file that handles exactly one interface (getInterface() / GameInterface.X { DSL / getInterfaceIds())
  cshash   integer literals passed to sendClientScript/runClientScript that decode to an existing 211/241 component
           (heuristic; listed as WARN only)
  iface    interface ids opened or closed by literal (sendInterface(InterfacePosition.X, N), closeInterface(N), ...)
Interface expressions resolve: literals, GameInterface.NAME (+ .getId() / .id, through the runtime table so Gad211.iface
applies), constants of the same file or Class.NAME of any file (int literal or GameInterface.X.getId()), and
getInterface()/getId()/`interface`/interfaceId inside a file that handles exactly one interface. Component expressions
resolve: literals and constants. Everything else is counted as unresolved and listed at the end (their put()/runtime
side is still checked through --runtime).

Verdicts: MISS (interface or component absent from the cache), PRE-211 (absent, and absent from the 211 interface a
relocated interface was copied from: it never worked on 211 either), LITERAL-RELOCATED (a literal interface id that idmap.json
moves: the literal names 241's interface, use GameInterface/Gad211.iface), WARN (type differs from the 211 component it
was remapped from, clientscript hash literal that does not exist, reused-by-241 interface), OK.
Exit status 1 when there is a MISS or LITERAL-RELOCATED.
"""
import argparse, collections, json, os, re, sys

# skipped: build output, tests, the cache converter and the packers that build GadScape's interfaces into a 211 cache
# (they never run on an external 241 cache)
SKIP_DIRS = ('/build/', '/.gradle/', '/cache-convert/', '/docs/', '/src/test/', '/buildSrc/', '/out/',
             '/cache/src/main/kotlin/com/gadscape/cache/', '/cache/src/main/kotlin/com/gadscape/cache_tool/',
             '/cache/src/main/java/mgi/types/component/custom/', '/cache/src/main/java/mgi/tools/parser/',
             '/cache/src/main/java/mgi/custom/')
ROOTS = ('core', 'core-restricted', 'plugins', 'scripts', 'cache/src/main/java', 'cache/src/main/kotlin', 'net', 'api')

CALLS = {  # method -> list of (iface arg index, component arg index); negative = from the end
    'sendComponentText': [(0, 1)], 'sendComponentVisibility': [(0, 1)], 'sendComponentSettings': [(0, 1)],
    'sendComponentItem': [(0, 1)], 'sendComponentPlayerHead': [(0, 1)], 'sendComponentNPCHead': [(0, 1)],
    'sendComponentModel': [(0, 1)], 'sendComponentAnimation': [(0, 1)], 'sendComponentSpriteColour': [(0, 1)],
    'sendComponentPosition': [(0, 1)], 'sendComponentSprite': [(0, 1)], 'sendComponentScrollPosition': [(0, 1)],
    'sendComponentColour': [(0, 1)], 'sendComponentObject': [(0, 1)], 'sendClearItemContainer': [(0, 1)],
    'sendUpdateItemContainer': [(1, 2)], 'sendUpdateItemsInSlot': [(1, 2)], 'sendLineSpacing': [(-2, -1)],
    'ComponentDefinitions.get': [(0, 1)], 'getIndexByButton': [(0, 1)], 'sendMoveInterface': [(0, 1), (2, 3)],
    'sendIfEvents': [(0, 1)], 'setComponentText': [(0, 1)],
}
PANES = {'FIXED': 548, 'RESIZABLE': 161, 'SIDE_PANELS': 164, 'FULL_SCREEN': 165, 'ORB_OF_OCULUS': 16, 'GAME_SCREEN': 80,
         'CHATBOX': 162, 'LOADING_CHATBOX': 293, 'JOURNAL_TAB_HEADER': 629, 'ADVANCED_SETTINGS': 134,
         'CHAT_TAB_HEADER': 707, 'IRON_GROUP_SOCIALS_TAB_HEADER': 727, 'IRON_GROUP_SETTINGS': 730, 'IRON_BANK': 724,
         'MOBILE': 601, 'TOA_MANAGEMENT': 774}
SELF_EXPRS = {'getInterface()', 'getInterface().getId()', 'getId()', 'this.getId()', '`interface`', '`interface`.id',
              'interfaceId', 'interfaceID', 'id', 'this.id', 'interface.id', 'getInterface().id', 'gameInterface',
              'gameInterface.getId()', 'gameInterface.id', 'this', 'inter', 'interfaceId()', 'interfaceScript.id'}
INT = re.compile(r'^(?:0x[0-9a-fA-F]+|\d+)$')


def load_tsv(path):
    rows = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            rows.append(line.rstrip('\n').split('\t'))
    return rows


def strip_comments(text):
    """Blank out comments, keep strings and line numbers."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', text[i:j]))
            i = j
        elif c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and text[j] != '"' and text[j] != '\n':
                    j += 2 if text[j] == '\\' else 1
                j += 1
            out.append(text[i:j])
            i = j
        elif c == "'":
            j = i + 1
            while j < n and text[j] != "'" and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            j += 1
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def split_args(text, start):
    """text[start] == '(' -> (list of argument strings, index after ')')."""
    depth, i, n = 0, start, len(text)
    args, cur = [], []
    while i < n:
        c = text[i]
        if c in '([{':
            depth += 1
            if depth > 1:
                cur.append(c)
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                args.append(''.join(cur).strip())
                return [a for a in args if a != ''] if args != [''] else [], i + 1
            cur.append(c)
        elif c == ',' and depth == 1:
            args.append(''.join(cur).strip())
            cur = []
        elif c == '"' or c == "'":
            q = c
            j = i + 1
            while j < n and text[j] != q and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            cur.append(text[i:j + 1])
            i = j
        else:
            cur.append(c)
        i += 1
    return None, n


def block_at(text, i):
    """text[i] == '{' -> index after the matching '}'."""
    depth, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == '"' or c == "'":
            q = c
            j = i + 1
            while j < n and text[j] != q and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            i = j
        i += 1
    return n


class Src:
    def __init__(self, server, rel):
        self.rel = rel
        self.raw = open(os.path.join(server, rel), encoding='utf-8', errors='replace').read()
        self.text = strip_comments(self.raw)
        self.lines = self.raw.split('\n')
        self.cls = os.path.splitext(os.path.basename(rel))[0]
        self.consts = {}
        self.own = set()

    def line(self, pos):
        return self.text.count('\n', 0, pos) + 1

    def src_line(self, ln):
        return self.lines[ln - 1].strip()[:150] if 0 < ln <= len(self.lines) else ''


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--server', required=True)
    ap.add_argument('--dump', required=True)
    ap.add_argument('--runtime', required=True)
    ap.add_argument('--dump211')
    ap.add_argument('--remap')
    ap.add_argument('--idmap')
    ap.add_argument('--out', default='-')
    ap.add_argument('--cache', default='')
    a = ap.parse_args()
    server = os.path.abspath(a.server)
    idmap_path = a.idmap or os.path.join(server, 'cache/src/main/resources/gadscape/rev241/idmap.json')

    # ---------- cache side
    comps = {}  # (iface, comp) -> (type, name)
    ifaces = collections.defaultdict(int)
    for r in load_tsv(a.dump):
        i, c = int(r[0]), int(r[1])
        comps[(i, c)] = (int(r[2]), r[6] if len(r) > 6 else '')
        ifaces[i] += 1
    comps211 = {}
    if a.dump211:
        for r in load_tsv(a.dump211):
            comps211[(int(r[0]), int(r[1]))] = (int(r[2]), r[6] if len(r) > 6 else '')
    rev_remap = collections.defaultdict(list)  # (iface, c241) -> [(c211, confidence)]
    remap_ifaces = set()
    if a.remap:
        for r in load_tsv(a.remap):
            try:
                i, c211, c241 = int(r[0]), int(r[2]), int(r[3])
            except (ValueError, IndexError):
                continue
            remap_ifaces.add(i)
            if c241 >= 0:
                rev_remap[(i, c241)].append((c211, r[4] if len(r) > 4 else ''))
    idmap = json.load(open(idmap_path)) if os.path.isfile(idmap_path) else {}
    relocated_if = {int(k): v for k, v in idmap.get('interface', {}).items()}

    # ---------- runtime side
    runtime = load_tsv(a.runtime)
    gi = {}  # GameInterface name -> id (after Gad211.iface)
    positions = set()
    for r in runtime:
        if r[0] == 'gameinterface' and r[3].startswith('GameInterface.'):
            gi[r[3].split('.', 1)[1]] = int(r[1])
        if r[3].startswith('InterfacePosition.'):
            positions.add(r[3].split('.', 1)[1])

    # ---------- sources
    files = []
    for root in ROOTS:
        base = os.path.join(server, root)
        for dp, dn, fn in os.walk(base):
            if any(s in dp + '/' for s in SKIP_DIRS):
                continue
            for f in fn:
                if f.endswith(('.java', '.kt')):
                    files.append(Src(server, os.path.relpath(os.path.join(dp, f), server)))
    gi_re = re.compile(r'^(?:GameInterface\.)?([A-Z][A-Z0-9_]*)(?:\.getId\(\)|\.id)?$')
    const_re = re.compile(r'\b(?:static\s+final\s+int|final\s+static\s+int|const\s+val|(?:private\s+|internal\s+|public\s+)?val)'
                          r'\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*Int)?\s*=\s*([^;\n]+?)\s*(?:;|\n)')
    iface_const_re = re.compile(r'^\s*(?:public\s+)?int\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;\n]+?)\s*;', re.M)
    global_consts = {}
    for s in files:
        for m in list(const_re.finditer(s.text)) + list(iface_const_re.finditer(s.text)):
            name, expr = m.group(1), m.group(2).strip()
            v = None
            if INT.match(expr):
                v = int(expr, 0)
            else:
                g = gi_re.match(expr)
                if g and g.group(1) in gi and ('GameInterface.' in expr or expr.endswith(('.getId()', '.id'))):
                    v = gi[g.group(1)]
                else:
                    m2 = re.match(r'^Gad211\.iface\(\s*(\d+|[A-Za-z_][A-Za-z0-9_]*)\s*\)$', expr)
                    if m2:
                        raw = int(m2.group(1)) if m2.group(1).isdigit() else s.consts.get(m2.group(1))
                        if raw is not None:
                            v = relocated_if.get(raw, raw)
            if v is not None:
                if name in s.consts and s.consts[name] != v:
                    s.consts[name] = None  # ambiguous inside the file
                else:
                    s.consts.setdefault(name, v)
                global_consts.setdefault(s.cls + '.' + name, v)
        # Kotlin DSL with `import com.zenyte.game.GameInterface.*`: `NAME {`
        if re.search(r'^import\s+com\.zenyte\.game\.GameInterface\.\*', s.text, re.M):
            for m in re.finditer(r'^\s*([A-Z][A-Z0-9_]+)\s*\{', s.text, re.M):
                if m.group(1) in gi:
                    s.own.add(gi[m.group(1)])
        # the interface(s) this file handles
        for m in re.finditer(r'getInterface\(\)[^{=]*?(?:\{\s*return\s+|=\s*)(?:GameInterface\.)?([A-Z][A-Z0-9_]+)\s*;?', s.text):
            if m.group(1) in gi:
                s.own.add(gi[m.group(1)])
        for m in re.finditer(r'\bGameInterface\.([A-Z][A-Z0-9_]+)\s*\{', s.text):
            if m.group(1) in gi:
                s.own.add(gi[m.group(1)])
        for m in re.finditer(r'getInterfaceIds\(\)[\s\S]{0,200}?(?:new\s+int\s*\[\]\s*\{([^}]*)\}|intArrayOf\(([^)]*)\))', s.text):
            for tok in re.split(r'\s*,\s*', (m.group(1) or m.group(2) or '').strip()):
                v = resolve_if(tok, s, gi, global_consts, None)
                if v is not None:
                    s.own.add(v)

    # static imports: `import static pkg.Class.NAME;` / `import static pkg.Class.*;` (and Kotlin `import pkg.Class.NAME`)
    by_class = collections.defaultdict(dict)
    for key, v in global_consts.items():
        c, n = key.split('.', 1)
        by_class[c][n] = v
    for s in files:
        for m in re.finditer(r'^import\s+(?:static\s+)?[\w.]*?\.([A-Z]\w*)\.(\*|[A-Z][A-Z0-9_]*)\s*;?\s*$', s.text, re.M):
            cls, name = m.group(1), m.group(2)
            if name == '*':
                for n, v in by_class.get(cls, {}).items():
                    s.consts.setdefault(n, v)
            elif name in by_class.get(cls, {}):
                s.consts.setdefault(name, by_class[cls][name])

    refs = []  # (kind, iface, comp, where, expr, note)

    def add(kind, iface, comp, s, pos, expr, note=''):
        ln = s.line(pos)
        refs.append((kind, iface, comp, '%s:%d' % (s.rel, ln), expr, note, s.src_line(ln)))

    unresolved = []
    call_re = re.compile(r'(?<![A-Za-z0-9_])(' + '|'.join(re.escape(k) for k in sorted(CALLS, key=len, reverse=True))
                         + r'|sendInterface|closeInterfaceSpecific|getInterface|closeInterface|isVisible|sendClientScript|runClientScript)\s*\(')
    for s in files:
        own = next(iter(s.own)) if len(s.own) == 1 else None
        t = s.text
        for m in call_re.finditer(t):
            name = m.group(1)
            before = t[max(0, m.start() - 80):m.start()]
            if re.search(r'(?:\bfun\s+(?:[\w.<>?]+\.)?|\b(?:void|int|boolean|Unit)\s+)$', before):
                continue  # a declaration
            args, _ = split_args(t, m.end() - 1)
            if args is None:
                continue
            if name in CALLS:
                if name == 'ComponentDefinitions.get' and len(args) != 2:
                    continue
                if name == 'sendComponentSettings':
                    masks = [x.startswith(('AccessMask.', 'CLICK_OP', 'MaskBuilder', '*')) for x in args]
                    base = len(args) >= 4 and not masks[2] and not masks[3]
                    if not base:
                        recv = t[max(0, m.start() - 40):m.start()]
                        if args and (args[0].startswith('"') or (s.rel.endswith('.kt') and args[0] in ('this', 'player', 'it')
                                                                  and not re.search(r'(?:packetDispatcher|[dD]ispatcher)\s*\.\s*$', recv))):
                            continue  # NamedInterfaceHandler / name forms: the component is a put() name (runtime side)
                        cv = resolve_int(args[0], s, global_consts) if args else None
                        if own is not None and cv is not None:
                            add('call', own, cv, s, m.start(), 'sendComponentSettings(%s, ..) [InterfaceScript]' % args[0])
                        elif args:
                            unresolved.append(('call', '%s:%d' % (s.rel, s.line(m.start())), 'sendComponentSettings(%s)' % ', '.join(args[:2])))
                        continue
                if name in ('sendUpdateItemContainer', 'sendUpdateItemsInSlot') and len(args) < 4:
                    continue
                for ii, ci in CALLS[name]:
                    if len(args) < 2 or max(abs(ii), abs(ci)) > len(args) or (ii >= 0 and ii >= len(args)) or (ci >= 0 and ci >= len(args)):
                        continue
                    ie, ce = args[ii], args[ci]
                    if re.match(r'^-\d+$', ie.replace(' ', '')):
                        continue  # interface -1/-2: an inventory transmitted without an interface
                    iv = resolve_if(ie, s, gi, global_consts, own)
                    cv = resolve_int(ce, s, global_consts)
                    if iv is None or cv is None:
                        if cv is not None or INT.match(ce or ''):
                            unresolved.append(('call', '%s:%d' % (s.rel, s.line(m.start())), '%s(%s, %s)' % (name, ie, ce)))
                        continue
                    add('call', iv, cv, s, m.start(), '%s(%s, %s)' % (name, ie, ce), literal_note(ie, relocated_if))
            elif name == 'sendInterface':
                if len(args) == 4 and args[2].startswith('PaneType.'):
                    pane = PANES.get(args[2].split('.', 1)[1])
                    cv = resolve_int(args[1], s, global_consts)
                    if pane is not None and cv is not None:
                        add('call', pane, cv, s, m.start(), 'sendInterface(%s, %s, %s)' % (args[0], args[1], args[2]))
                    iv = resolve_if(args[0], s, gi, global_consts, own)
                    if iv is not None:
                        add('iface', iv, -1, s, m.start(), 'sendInterface(%s, ..)' % args[0], literal_note(args[0], relocated_if))
                elif len(args) in (2, 3) and (args[0].split('.')[-1] in positions or args[0].startswith('position') or args[0].endswith('getPosition()')):
                    iv = resolve_if(args[1], s, gi, global_consts, None)
                    if iv is not None:
                        add('iface', iv, -1, s, m.start(), 'sendInterface(%s, %s)' % (args[0], args[1]), literal_note(args[1], relocated_if))
            elif name == 'closeInterfaceSpecific':
                if len(args) >= 2 and args[1].startswith('PaneType.'):
                    pane = PANES.get(args[1].split('.', 1)[1])
                    cv = resolve_int(args[0], s, global_consts)
                    if pane is not None and cv is not None:
                        add('call', pane, cv, s, m.start(), 'closeInterfaceSpecific(%s, %s)' % (args[0], args[1]))
            elif name == 'getInterface':
                if len(args) == 2 and args[0].startswith('PaneType.'):
                    pane = PANES.get(args[0].split('.', 1)[1])
                    cv = resolve_int(args[1], s, global_consts)
                    if pane is not None and cv is not None:
                        add('call', pane, cv, s, m.start(), 'getInterface(%s, %s)' % (args[0], args[1]))
            elif name in ('closeInterface', 'isVisible'):
                if len(args) == 1 and INT.match(args[0]):
                    add('iface', int(args[0], 0), -1, s, m.start(), '%s(%s)' % (name, args[0]), literal_note(args[0], relocated_if))
                elif name == 'isVisible' and len(args) == 2 and INT.match(args[0]) and INT.match(args[1]):
                    add('call', int(args[0], 0), int(args[1], 0), s, m.start(), 'isVisible(%s, %s)' % tuple(args))
            elif name in ('sendClientScript', 'runClientScript'):
                for ex in args[1:]:
                    if INT.match(ex):
                        v = int(ex, 0)
                        if 65536 <= v < 2 ** 31:
                            i2, c2 = v >> 16, v & 0xFFFF
                            if c2 < 2000 and (i2 in ifaces or any(k[0] == i2 for k in ((i2, c2),) if k in comps211)):
                                add('cshash', i2, c2, s, m.start(), '%s(%s, .. %s ..)' % (name, args[0] if args else '', ex))
        # hashes
        ident = r'(?:`?[A-Za-z_][A-Za-z0-9_]*`?(?:\(\))?(?:\.`?[A-Za-z_][A-Za-z0-9_]*`?(?:\(\))?)*|\d+)'
        for m in re.finditer(r'(\(?\s*(' + ident + r')\s*(?:(?:<<\s*16|shl\s+16)\s*\)?\s*(?:\||\+|\bor\b)|\bcomponent\b)\s*\(?\s*([A-Za-z_][A-Za-z0-9_.]*|\d+))', t):
            ie, ce = m.group(2), m.group(3)
            if re.match(r'\s*(?:<<|shl\b)', t[m.end():m.end() + 6]):
                continue  # r << 16 | g << 8 | b: a colour
            iv = resolve_if(ie, s, gi, global_consts, own)
            cv = resolve_int(ce, s, global_consts)
            if iv is None or cv is None:
                if INT.match(ce) or INT.match(ie):
                    unresolved.append(('hash', '%s:%d' % (s.rel, s.line(m.start())), m.group(1).strip()))
                continue
            add('hash', iv, cv, s, m.start(), m.group(1).strip(), literal_note(ie, relocated_if))
        # handlers in a file that handles one interface
        if own is not None:
            for m in re.finditer(r'\b(?:componentId|componentID|component|buttonId|childId)\s*(==|!=|>=|<=|>|<)\s*(\d+)\b', t):
                add('handler', own, int(m.group(2)), s, m.start(), m.group(0))
            for m in re.finditer(r'\bswitch\s*\(\s*(?:componentId|componentID|component|buttonId)\s*\)\s*\{', t):
                end = block_at(t, m.end() - 1)
                body = t[m.end():end - 1]
                depth = 0
                for cm in re.finditer(r'[{}]|\bcase\s+([0-9][0-9,\s]*)(?=[:\-])', body):
                    tok = cm.group(0)
                    if tok == '{':
                        depth += 1
                    elif tok == '}':
                        depth -= 1
                    elif depth == 0:
                        for v in re.findall(r'\d+', cm.group(1)):
                            add('handler', own, int(v), s, m.end() + cm.start(), 'case ' + v)
            for m in re.finditer(r'\bwhen\s*\(\s*(?:componentId|componentID|component|buttonId)\s*\)\s*\{', t):
                end = block_at(t, m.end() - 1)
                body = t[m.end():end - 1]
                depth = 0
                for cm in re.finditer(r'[{}]|^\s*([0-9][0-9,\s]*)\s*->', body, re.M):
                    tok = cm.group(0)
                    if tok == '{':
                        depth += 1
                    elif tok == '}':
                        depth -= 1
                    elif depth == 0 and cm.group(1):
                        for v in re.findall(r'\d+', cm.group(1)):
                            add('handler', own, int(v), s, m.end() + cm.start(), 'when ' + v)

    # runtime refs
    for r in runtime:
        kind, iface, comp, where, note = r[0], int(r[1]), int(r[2]), r[3], (r[4] if len(r) > 4 else '')
        if kind == 'error':
            refs.append(('error', iface, comp, where, '', note, ''))
        else:
            refs.append((kind, iface, comp, where, note, '', ''))

    # ---------- verdicts
    reused = set()
    gi_src = [s for s in files if s.rel.endswith('com/zenyte/game/GameInterface.java')]
    if gi_src:
        m = re.search(r'REUSED_BY_241\s*=\s*[^(]*\(([^)]*)\)', gi_src[0].text)
        if m:
            reused = {int(x) for x in re.findall(r'\d+', m.group(1))}
    miss, reloc, warn, ok, errors, pre211 = [], [], [], [], [], []
    moved_from = {v: k for k, v in relocated_if.items()}
    seen = set()
    for ref in refs:
        kind, iface, comp, where, expr, note, line = ref
        key = (kind, iface, comp, where, expr)
        if key in seen:
            continue
        seen.add(key)
        if kind == 'error':
            errors.append(ref)
            continue
        name = comps.get((iface, comp), (None, ''))[1] if comp >= 0 else comps.get((iface, 0), (None, ''))[1].split(':')[0]
        if note.startswith('LITERAL-RELOCATED') or (iface in relocated_if and kind != 'cshash'):
            # GameInterface / Gad211.iface give the moved id; the old number reaching a call is 241's interface
            reloc.append(ref[:5] + (note or 'LITERAL-RELOCATED %d -> %d' % (iface, relocated_if[iface]),) + ref[6:] + (name,))
            continue
        if iface in reused and kind not in ('position', 'cshash'):
            warn.append(ref + ('interface %d is in GameInterface.REUSED_BY_241 (not opened on 241)%s' % (
                iface, '' if comp < 0 or (iface, comp) in comps else '; component absent'),))
            continue
        if iface not in ifaces:
            if kind == 'cshash':
                warn.append(ref + ('cs arg decodes to a missing interface',))
            else:
                miss.append(ref + ('interface %d not in cache' % iface,))
            continue
        if comp >= 0 and (iface, comp) not in comps:
            if kind == 'cshash':
                if (iface, comp) in comps211:
                    warn.append(ref + ('cs arg hash: 211 component, absent in this cache',))
                continue
            orig = moved_from.get(iface)
            if orig is not None and comps211 and (orig, comp) not in comps211 and any(k[0] == orig for k in comps211):
                # a relocated GadScape interface keeps its 211 numbering: absent there too, so it never worked on 211
                pre211.append(ref + ('component %d:%d absent; also absent from the 211 interface %d it was moved from'
                                     % (iface, comp, orig),))
                continue
            miss.append(ref + ('component %d:%d not in cache (interface has %d)' % (iface, comp, ifaces[iface]),))
            continue
        if kind == 'cshash':
            continue  # existing: noise
        typ = comps[(iface, comp)][0] if comp >= 0 else None
        if comps211 and comp >= 0 and iface in remap_ifaces and kind in ('call', 'hash', 'put', 'handler'):
            # a type change is expected where 241 restructured a screen and the row was matched by name ("high");
            # elsewhere it is a hint that the component means something else
            src = [c for c, conf in rev_remap.get((iface, comp), []) if conf != 'high']
            if src:
                t211 = {comps211.get((iface, c), (None,))[0] for c in src}
                if typ not in t211 and None not in t211:
                    warn.append(ref + ('type %s in cache (%s), 211 %s:%s type %s' % (typ, name, iface, '/'.join(map(str, src)), '/'.join(map(str, sorted(t211)))),))
                    continue
        ok.append(ref + (name,))

    out = sys.stdout if a.out == '-' else open(a.out, 'w')
    p = lambda *x: print(*x, file=out)
    p('# rev241 static interface check (docs/rev241/tools/static_check.sh)')
    p('# cache: %s  (%d interfaces, %d components)' % (a.cache, len(ifaces), len(comps)))
    p('# idmap: %s  interface moves: %s' % (os.path.relpath(idmap_path, server), relocated_if))
    p('# references: %d (source files %d); MISS %d, PRE-211 %d, LITERAL-RELOCATED %d, WARN %d, OK %d, plugin errors %d, unresolved %d'
      % (len(seen), len(files), len(miss), len(pre211), len(reloc), len(warn), len(ok), len(errors), len(unresolved)))
    by = collections.Counter(r[0] for r in ok)
    p('# OK by kind: ' + ', '.join('%s %d' % kv for kv in sorted(by.items())))
    p('')
    p('== MISS: interface/component absent from the cache')
    for r in sorted(miss, key=lambda r: (r[1], r[2], r[3])):
        p('MISS\t%s\t%d:%d\t%s\t%s\t%s\t| %s' % (r[0], r[1], r[2], r[3], r[4], r[7], r[6]))
    p('')
    p('== PRE-211: absent in this cache and already absent on 211 (the feature never worked; not a regression)')
    for r in sorted(pre211, key=lambda r: (r[1], r[2], r[3])):
        p('PRE211\t%s\t%d:%d\t%s\t%s\t%s\t| %s' % (r[0], r[1], r[2], r[3], r[4], r[7], r[6]))
    p('')
    p('== LITERAL-RELOCATED: literal interface ids idmap.json moves')
    for r in sorted(reloc, key=lambda r: (r[1], r[2], r[3])):
        p('RELOC\t%s\t%d:%d\t%s\t%s\t%s\t| %s' % (r[0], r[1], r[2], r[3], r[4], r[5], r[6]))
    p('')
    p('== plugin errors (IfStaticCheck could not instantiate/attach/build: check by hand)')
    for r in sorted(errors, key=lambda r: r[3]):
        p('ERROR\t%s\t%d\t%s' % (r[3], r[1], r[5]))
    p('')
    p('== WARN')
    for r in sorted(warn, key=lambda r: (r[1], r[2], r[3])):
        p('WARN\t%s\t%d:%d\t%s\t%s\t%s\t| %s' % (r[0], r[1], r[2], r[3], r[4], r[7], r[6]))
    p('')
    p('== unresolved expressions (not checked statically)')
    for u in sorted(set(unresolved)):
        p('UNRES\t%s\t%s\t%s' % u)
    p('')
    p('== OK (interface:component name | kind | where | expression)')
    for r in sorted(ok, key=lambda r: (r[1], r[2], r[0], r[3])):
        p('OK\t%d:%d\t%s\t%s\t%s\t%s' % (r[1], r[2], r[7], r[0], r[3], r[4]))
    if out is not sys.stdout:
        out.close()
    print('MISS %d, PRE-211 %d, LITERAL-RELOCATED %d, WARN %d, OK %d, plugin errors %d, unresolved %d' % (len(miss), len(pre211), len(reloc), len(warn), len(ok), len(errors), len(unresolved)))
    return 1 if miss or reloc else 0


def literal_note(expr, relocated_if):
    if expr and INT.match(expr.strip()) and int(expr.strip(), 0) in relocated_if:
        return 'LITERAL-RELOCATED %s -> %d' % (expr.strip(), relocated_if[int(expr.strip(), 0)])
    return ''


def resolve_int(expr, s, gconsts):
    if expr is None:
        return None
    e = expr.strip()
    if INT.match(e):
        return int(e, 0)
    if re.match(r'^[A-Za-z_][A-Za-z0-9_]*$', e):
        v = s.consts.get(e)
        if v is not None:
            return v
        return None
    m = re.match(r'^([A-Z][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)$', e)
    if m:
        return gconsts.get(e)
    return None


def resolve_if(expr, s, gi, gconsts, own):
    if expr is None:
        return None
    e = re.sub(r'\s+', '', expr.strip())
    if INT.match(e):
        return int(e, 0)
    m = re.match(r'^(?:GameInterface\.)?([A-Z][A-Z0-9_]*)(?:\.getId\(\)|\.id)?$', e)
    if m and m.group(1) in gi and (e.startswith('GameInterface.') or m.group(1) not in s.consts):
        return gi[m.group(1)]
    m = re.match(r'^Gad211\.iface\((\d+)\)$', e)
    if m:
        return None
    if e in SELF_EXPRS or e in ('plugin.getInterface()', 'inter.getInterface()', 'script.`interface`'):
        if e in s.consts:
            return s.consts[e]
        if e in ('id', 'this.id', 'this') and not s.rel.endswith('.kt'):
            return None  # a Java field/local or `this`, not Interface.getId()
        return own
    v = resolve_int(e, s, gconsts)
    if v is not None:
        return v
    m = re.match(r'^([A-Z][A-Za-z0-9_]*\.[A-Z_][A-Z0-9_]*)(?:\.getId\(\)|\.id)$', e)
    if m:
        return gconsts.get(m.group(1))
    return None


if __name__ == '__main__':
    sys.exit(main())
