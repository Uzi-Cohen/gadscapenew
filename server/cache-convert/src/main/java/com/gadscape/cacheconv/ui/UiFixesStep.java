package com.gadscape.cacheconv.ui;

import com.gadscape.cacheconv.convert.Cs2;
import com.gadscape.cacheconv.convert.ScriptConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;
import com.gadscape.cacheconv.uimerge.IfComp;
import com.gadscape.cacheconv.uimerge.ScriptEdit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ui-fixes: the QA fixes of GadScape's own UI (owner standard: no interface transparent, incomplete or without working
 * buttons), applied after ui-merge/ui-convert on every build:
 * <ul>
 *   <li><b>interface edits</b> ({@value #RESOURCE}; {@code --ui.fixesTable=FILE|none}): rows
 *       {@code IFACE<TAB>COMPS<TAB>ACTION<TAB>ARG<TAB>FINDING<TAB>NOTE} on the converted component fields
 *       ({@link IfComp} names): {@code set FIELD VALUE} (int, or {@code str:TEXT}), {@code move-hook FROM TO} (a hook
 *       moved to an empty hook), {@code drop-hook HOOK}, {@code hook-args HOOK N} (keep the script and its first N
 *       arguments). IFACE is a USER interface id (mapped through the id map). Each row checks that the component exists
 *       and that the edit changes something;</li>
 *   <li><b>script recipes</b> ({@link #recipes}): instruction edits of GadScape scripts located by anchors (they fail
 *       loudly when the code they expect is not there);</li>
 *   <li><b>new scripts</b> ({@code ui/scripts/ID-NAME.cs2}, 211 bytes, converted like custom scripts): scripts the
 *       server calls that the user cache lacks (hide roofs 10719).</li>
 * </ul>
 * Every change is a {@code fixed} (or {@code added}) decision in report.json; a fix that no longer applies is an error.
 */
public final class UiFixesStep implements Step {
    public static final String NAME = "ui-fixes";
    public static final String RESOURCE = "ui/fixes.tsv";

    /**
     * New scripts: id, resource, name, finding, why. The resources are 211-layout scripts, converted like USER's.
     * {@code 10719-hide_roofs.cs2} (28 bytes): empty name; {@code iload 0; setremoveroofs (3112); return}; footer
     * 3 instructions, 1 int local, 1 int arg, no switches.
     */
    static final String[][] NEW_SCRIPTS = {
            {"10719", "ui/scripts/10719-hide_roofs.cs2", "gadscape_hide_roofs", "IF-CS10700-09",
                    "ClientScripts.HIDE_ROOFS: 1 int arg (1 = hide roofs) -> setremoveroofs (3112); 10700 stays the Completionist Cape progress script"},
    };

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        int n = 0;
        n += interfaces(ctx);
        n += recipes(ctx);
        n += newScripts(ctx);
        ctx.report.count("ui fixes applied", n);
        ctx.log("%d UI fixes applied", n);
    }

    // ------------------------------------------------------------------ interfaces

    record Row(int line, int iface, String comps, String action, String arg, String finding, String note) {}

    static List<Row> rows(Ctx ctx) {
        String opt = ctx.opt.get("ui.fixesTable", null);
        if ("none".equals(opt)) return List.of();
        List<String> lines;
        try {
            if (opt != null) lines = Files.readAllLines(Path.of(opt), StandardCharsets.UTF_8);
            else try (InputStream in = UiFixesStep.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) return List.of();
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        List<Row> rows = new ArrayList<>();
        int n = 0;
        for (String l : lines) {
            n++;
            if (l.isBlank() || l.startsWith("#")) continue;
            String[] c = l.split("\t", -1);
            if (c.length < 5) throw new IllegalArgumentException(RESOURCE + ":" + n + ": expected IFACE<TAB>COMPS<TAB>ACTION<TAB>ARG<TAB>FINDING[<TAB>NOTE]");
            rows.add(new Row(n, Integer.parseInt(c[0].trim()), c[1].trim(), c[2].trim(), c[3], c[4].trim(), c.length > 5 ? c[5].trim() : ""));
        }
        return rows;
    }

    static int interfaces(Ctx ctx) {
        int n = 0;
        for (Row r : rows(ctx)) {
            int g = ctx.ids.map(Kind.INTERFACE, r.iface);
            for (int f : UiScope.ids(r.comps)) {
                String where = "component " + g + ":" + f;
                try {
                    byte[] b = ctx.out.hasGroup(Arch.INTERFACES, g) ? ctx.out.group(Arch.INTERFACES, g).dataRef(f) : null;
                    if (b == null) throw new IllegalStateException(where + " is not in the output");
                    IfComp c = IfComp.decode(b);
                    if (!c.if3) throw new IllegalStateException(where + " is an IF1 component");
                    edit(c, r);
                    byte[] nb = c.encode();
                    if (Arrays.equals(nb, b)) throw new IllegalStateException(where + ": " + r.action + " " + r.arg.trim() + " changes nothing (already fixed upstream? delete the row)");
                    IfComp.decode(nb); // round trip
                    ctx.out.putFile(Arch.INTERFACES, g, f, nb);
                    ctx.report.add(Decision.FIXED, "component", (long) g << 16 | f, r.finding + ": " + where + " " + r.action + " " + r.arg.trim() + (r.note.isEmpty() ? "" : " (" + r.note + ")"));
                    n++;
                } catch (RuntimeException e) {
                    ctx.report.error("component", (long) g << 16 | f, "ui-fixes line " + r.line + " (" + r.finding + "): " + e.getMessage());
                }
            }
        }
        return n;
    }

    static void edit(IfComp c, Row r) {
        String a = r.arg.trim();
        switch (r.action) {
            case "set" -> {
                int sp = a.indexOf(' ');
                String field = a.substring(0, sp), v = a.substring(sp + 1);
                if (!c.f.containsKey(field)) throw new IllegalStateException("component has no field " + field);
                Object old = c.f.get(field);
                Object nv = v.startsWith("str:") ? v.substring(4) : (Object) Integer.parseInt(v.trim());
                if (old != null && nv.getClass() != old.getClass()) throw new IllegalStateException("field " + field + " is a " + old.getClass().getSimpleName());
                c.f.put(field, nv);
            }
            case "move-hook" -> {
                String[] h = a.split(" ");
                Object v = c.f.get(h[0]);
                if (!(v instanceof List<?> l) || l.isEmpty()) throw new IllegalStateException("no " + h[0] + " hook");
                Object t = c.f.get(h[1]);
                if (t instanceof List<?> tl && !tl.isEmpty()) throw new IllegalStateException(h[1] + " hook is already set");
                c.f.put(h[1], v);
                c.f.put(h[0], null);
            }
            case "drop-hook" -> {
                Object v = c.f.get(a);
                if (!(v instanceof List<?> l) || l.isEmpty()) throw new IllegalStateException("no " + a + " hook");
                c.f.put(a, null);
            }
            case "hook-args" -> {
                String[] h = a.split(" ");
                Object v = c.f.get(h[0]);
                if (!(v instanceof List<?> l) || l.isEmpty()) throw new IllegalStateException("no " + h[0] + " hook");
                int keep = Integer.parseInt(h[1]);
                if (l.size() <= keep + 1) throw new IllegalStateException(h[0] + " has " + (l.size() - 1) + " arguments, not more than " + keep);
                c.f.put(h[0], new ArrayList<>(l.subList(0, keep + 1)));
            }
            default -> throw new IllegalArgumentException("unknown action '" + r.action + "'");
        }
    }

    // ------------------------------------------------------------------ scripts

    /** One recipe: the USER script id it edits (mapped), the finding, and the edit. */
    record Recipe(int script, String finding, String why, java.util.function.BiConsumer<Ctx, ScriptEdit> edit) {}

    static List<Recipe> recipes() {
        List<Recipe> r = new ArrayList<>();
        r.add(new Recipe(10593, "TELE-05", "'Previous teleport' label: the pane enum returns -1 (not null) for the fullscreen world map pane 165; "
                + "if_setop(3, text, -1) threw ArrayIndexOutOfBounds. The null test becomes 'component >= 0'", (ctx, e) -> {
            int at = e.find(1, "33:1", "0:" + Integer.MIN_VALUE, "7:1", "6:*");
            e.operand(at, 0, null, "compare with 0 instead of null");
            e.s().code.get(at + 1).op = 32;    // branch_greater_than_or_equals: continue when the component is >= 0 (null and -1 both return)
            e.note("instruction " + (at + 1) + ": branch_not -> branch_greater_than_or_equals");
        }));
        r.add(new Recipe(12576, "F01", "staff-only debug line 'perk enum count' in Remnant Perks: staffmodlevel >= 2 becomes >= 127 (never)", (ctx, e) -> {
            int at = e.find(1, "3316:0", "0:2", "32:1", "6:*", "3:\"perk enum count");
            e.operand(at, 127, null, "staffmodlevel is at most 2");
        }));
        r.add(new Recipe(205, "DUEL-2000", "duel_initworn (GadScape's version for DUEL_SETTINGS 2000): 241's cc_create throws RuntimeException when the "
                + "parent is not a layer, and the second cc_create of each slot targets the text component 2000:91 (hidden marker graphics that "
                + "never show, children of a text component are not drawn on 211 either); that half of the loop body is skipped", (ctx, e) -> {
            int dueling = ctx.ids.map(Kind.INTERFACE, 2000);
            int text = (dueling << 16) | 91;
            int at = e.find(0, "0:" + text, "0:5", "33:0", "0:0", "100:0");
            int inc = e.find(0, "33:0", "0:1", "4000:0", "34:0", "6:*");
            if (inc <= at) throw new IllegalStateException("loop increment not after the cc_create");
            Cs2.Ins in = e.s().code.get(at);
            in.op = Cs2.JUMP;
            in.i = inc - at - 1;
            e.note("instruction " + at + ": push " + dueling + ":91 -> jump to the slot increment at " + inc);
            e.remove(at + 1, inc, "the cc_create on " + dueling + ":91 and its cc_set* calls, unreachable now");
        }));
        return r;
    }

    static int recipes(Ctx ctx) {
        int n = 0;
        for (Recipe r : recipes()) {
            int id = ctx.ids.map(Kind.SCRIPT, r.script);
            try {
                byte[] b = ctx.out.single(Arch.SCRIPTS, id);
                if (b == null) throw new IllegalStateException("script " + id + " is not in the output");
                ScriptEdit e = new ScriptEdit(id, Cs2.parse241(b));
                r.edit.accept(ctx, e);
                byte[] nb = e.bytes();
                if (Arrays.equals(nb, b)) throw new IllegalStateException("the recipe changes nothing");
                Cs2.parse241(nb);
                ctx.out.putSingle(Arch.SCRIPTS, id, nb);
                ctx.report.add(Decision.FIXED, Kind.SCRIPT, id, r.finding + ": " + r.why + " [" + String.join("; ", e.log()) + "]");
                n++;
            } catch (RuntimeException ex) {
                ctx.report.error("script", id, "ui-fixes recipe " + r.finding + ": " + ex.getMessage());
            }
        }
        return n;
    }

    static int newScripts(Ctx ctx) {
        int n = 0;
        for (String[] s : NEW_SCRIPTS) {
            int id = Integer.parseInt(s[0]);
            try {
                if (ctx.base.hasGroup(Arch.SCRIPTS, id) || ctx.user().hasGroup(Arch.SCRIPTS, id) || ctx.out.hasGroup(Arch.SCRIPTS, id))
                    throw new IllegalStateException("script id " + id + " is taken (BASE, USER or output)");
                byte[] src;
                try (InputStream in = UiFixesStep.class.getClassLoader().getResourceAsStream(s[1])) {
                    if (in == null) throw new IllegalStateException("missing resource " + s[1]);
                    src = in.readAllBytes();
                }
                byte[] conv = ScriptConv.convertAll(Map.of(id, src)).scripts.get(id);
                Cs2.parse241(conv);
                ctx.ids.claimNew(Kind.SCRIPT, s[2], id, s[3] + ": " + s[4]);
                ctx.out.putGroup(Arch.SCRIPTS, GroupData.single(id, Bytes.nameHash(s[2]), conv));
                ctx.report.add(Decision.ADDED, Kind.SCRIPT, id, s[3] + ": " + s[4]);
                n++;
            } catch (IOException | RuntimeException ex) {
                ctx.report.error("script", id, "ui-fixes new script " + s[2] + ": " + ex.getMessage());
            }
        }
        return n;
    }
}
