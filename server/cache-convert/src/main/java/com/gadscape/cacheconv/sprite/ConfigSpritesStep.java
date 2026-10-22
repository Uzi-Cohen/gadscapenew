package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.config.CfgCodec;
import com.gadscape.cacheconv.config.ParamTypeTable;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * config-sprites: vanilla 211 sprites that Jagex deleted by 241 (BASE has no group at the id) but that the configs
 * written for GadScape content still name (enum/param/struct values typed graphic, obj/npc icons, ...) are copied from
 * USER at their id. {@link SpritesStep} leaves vanilla sprites to BASE, and ui-convert copies only the ones GadScape's
 * own UI names, so without this step such a config reference would dangle (e.g. user enum 11001 -> sprite 4567).
 * Only USER groups that are vanilla (USER == VAN211), not a relocation target and absent from the output are copied;
 * the sprite format did not change between 211 and 241. Run it after {@code configs}.
 */
public final class ConfigSpritesStep implements Step {
    public static final String NAME = "config-sprites";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        var out = ctx.out;
        var user = ctx.user();
        ParamTypeTable types = new ParamTypeTable(out);
        TreeMap<Integer, TreeSet<String>> wanted = new TreeMap<>();
        for (int g : out.groupIds(Arch.CONFIGS)) {
            if (g == Arch.CFG_SEQ || !CfgCodec.walked(g)) continue;
            for (int id : out.configIds(g)) {
                byte[] b = out.configRef(g, id), base = ctx.base.configRef(g, id);
                if (base != null && Arrays.equals(base, b)) continue;           // BASE's own entries name BASE's sprites
                CfgCodec.Entry e;
                try { e = CfgCodec.parse(g, id, b, types); } catch (RuntimeException ex) { continue; } // configs-refcheck reports it
                for (CfgCodec.Ref r : e.allRefs()) {
                    if (r.none() || r.kind() != Kind.SPRITE || out.hasGroup(Arch.SPRITES, r.value())) continue;
                    TreeSet<String> who = wanted.computeIfAbsent(r.value(), k -> new TreeSet<>());
                    if (who.size() < 6) who.add(Arch.configName(g) + " " + id);
                }
            }
            out.release(Arch.CONFIGS);
        }
        Set<Integer> baseNames = new HashSet<>();
        if (!wanted.isEmpty()) for (int g : ctx.base.groupIds(Arch.SPRITES)) baseNames.add(ctx.base.groupName(Arch.SPRITES, g));
        int copied = 0, missing = 0;
        for (var w : wanted.entrySet()) {
            int id = w.getKey();
            boolean ok = user.hasGroup(Arch.SPRITES, id) && !ctx.base.hasGroup(Arch.SPRITES, id) && ctx.ids.origin(Kind.SPRITE, id) < 0
                    && Origin.ofGroup(Arch.SPRITES, id, user, ctx.van211()) == Origin.VANILLA;
            GroupData gu = ok ? user.group(Arch.SPRITES, id) : null;
            if (gu == null) { missing++; continue; }                            // configs-refcheck / verify report it
            int name = gu.name != -1 && !baseNames.contains(gu.name) ? gu.name : SpritesStep.gadscapeName(id);
            out.putGroup(Arch.SPRITES, SpritesStep.rename(gu, id, name));
            copied++;
            ctx.report.add(Decision.NOTE, Kind.SPRITE, id, "vanilla 211 sprite that BASE no longer has, named by " + String.join(", ", w.getValue()) + ": copied from USER");
        }
        ctx.report.count("sprites.copied-for-configs", copied);
        if (missing > 0) ctx.report.count("sprites.named-by-configs-but-not-copyable", missing);
        ctx.log("%d deleted vanilla sprites copied for configs, %d named sprites not copyable", copied, missing);
    }
}
