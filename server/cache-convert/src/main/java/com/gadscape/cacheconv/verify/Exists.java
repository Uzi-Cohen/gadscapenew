package com.gadscape.cacheconv.verify;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Fast "does this id exist" lookups on one cache (bit sets built on first use). */
public final class Exists {
    private final CacheView c;
    private final Map<String, BitSet> sets = new HashMap<>();
    private final Map<Long, BitSet> files = new HashMap<>();

    public Exists(CacheView c) { this.c = c; }

    public CacheView cache() { return c; }

    private BitSet groups(int archive) {
        return sets.computeIfAbsent("a" + archive, k -> { BitSet b = new BitSet(); for (int g : c.groupIds(archive)) b.set(g); return b; });
    }

    private BitSet config(int group) {
        return sets.computeIfAbsent("c" + group, k -> { BitSet b = new BitSet(); for (int f : c.fileIds(Arch.CONFIGS, group)) b.set(f); return b; });
    }

    /** Files of one group (archive << 32 | group). */
    private BitSet files(int archive, int group) {
        return files.computeIfAbsent(((long) archive << 32) | (group & 0xffffffffL), k -> { BitSet b = new BitSet(); for (int f : c.fileIds(archive, group)) b.set(f); return b; });
    }

    public boolean group(int archive, int id) { return id >= 0 && groups(archive).get(id); }
    public boolean config(int group, int id) { return id >= 0 && config(group).get(id); }
    public boolean file(int archive, int group, int file) { return group >= 0 && file >= 0 && group(archive, group) && files(archive, group).get(file); }

    public boolean exists(CfgRefs.Target t, int id) {
        if (id < 0) return false;
        return switch (t) {
            case MODEL -> group(Arch.MODELS, id);
            case SEQ -> config(Arch.CFG_SEQ, id);
            case OBJ -> config(Arch.CFG_OBJ, id);
            case NPC -> config(Arch.CFG_NPC, id);
            case LOC -> config(Arch.CFG_LOC, id);
            case SPOT -> config(Arch.CFG_SPOT, id);
            case VARBIT -> config(Arch.CFG_VARBIT, id);
            case VARP -> config(Arch.CFG_VARP, id);
            case PARAM -> config(Arch.CFG_PARAM, id);
            case STRUCT -> config(Arch.CFG_STRUCT, id);
            case ENUM -> config(Arch.CFG_ENUM, id);
            case DBROW -> config(Arch.CFG_DBROW, id);
            case DBTABLE -> config(Arch.CFG_DBTABLE, id);
            case INV -> config(Arch.CFG_INV, id);
            case IDKIT -> config(Arch.CFG_IDENTKIT, id);
            case SPRITE -> group(Arch.SPRITES, id);
            case FONT -> group(Arch.FONTS, id);
            case SCRIPT -> group(Arch.SCRIPTS, id);
            case INTERFACE -> group(Arch.INTERFACES, id);
            case COMPONENT -> (id & 0xffff) == 0xffff ? group(Arch.INTERFACES, id >>> 16) : file(Arch.INTERFACES, id >>> 16, id & 0xffff);
            case FRAME -> file(Arch.FRAMES, id >>> 16, id & 0xffff);
            case KEYFRAME -> file(Arch.KEYFRAMES, id >>> 16, id & 0xffff);
            case FRAMEMAP -> group(Arch.FRAMEMAPS, id);
        };
    }

    /** Human form of an id ("component 548:12", "frame 1234:5", or the plain id). */
    public static String show(CfgRefs.Target t, int id) {
        return switch (t) {
            case COMPONENT, FRAME, KEYFRAME -> t.key + " " + (id >>> 16) + ":" + (id & 0xffff);
            default -> t.key + " " + id;
        };
    }
}
