package com.gadscape.cacheconv.core;

/**
 * Id spaces that can be relocated, where their ids live in a cache, and the reserved GadScape range relocated ids go
 * to (from the gadconv spec; -1/-1 = no range: relocating that kind needs {@link IdMap#setRange} first).
 * A kind is either the groups of an archive ({@link #configGroup} == -1) or the files of one config group.
 */
public enum Kind {
    SEQ("seq", Arch.CONFIGS, Arch.CFG_SEQ, 40000, 49999),
    /** Archive 0 frame groups; seq frame refs are (group << 16 | file). */
    FRAME("frame", Arch.FRAMES, -1, 30000, 32767),
    FRAMEMAP("framemap", Arch.FRAMEMAPS, -1, 30000, 32767),
    /** Archive 22 skeletal keyframe groups (seq opcode 13). */
    KEYFRAME("keyframe", Arch.KEYFRAMES, -1, 20000, 29999),
    SCRIPT("script", Arch.SCRIPTS, -1, 47000, 47999),
    SPRITE("sprite", Arch.SPRITES, -1, 20000, 29999),
    DBROW("dbrow", Arch.CONFIGS, Arch.CFG_DBROW, 60000, 64999),
    DBTABLE("dbtable", Arch.CONFIGS, Arch.CFG_DBTABLE, -1, -1),
    MODEL("model", Arch.MODELS, -1, 100000, 199999),
    LOC("loc", Arch.CONFIGS, Arch.CFG_LOC, 70000, 79999),
    OBJ("obj", Arch.CONFIGS, Arch.CFG_OBJ, -1, -1),
    NPC("npc", Arch.CONFIGS, Arch.CFG_NPC, -1, -1),
    SPOT("spot", Arch.CONFIGS, Arch.CFG_SPOT, -1, -1),
    VARBIT("varbit", Arch.CONFIGS, Arch.CFG_VARBIT, 30000, 34999),
    VARP("varp", Arch.CONFIGS, Arch.CFG_VARP, 9000, 9999),
    VARC("varc", Arch.CONFIGS, Arch.CFG_VARC, -1, -1),
    STRUCT("struct", Arch.CONFIGS, Arch.CFG_STRUCT, 13000, 13999),
    ENUM("enum", Arch.CONFIGS, Arch.CFG_ENUM, 20000, 20999),
    PARAM("param", Arch.CONFIGS, Arch.CFG_PARAM, 9000, 9999),
    INTERFACE("interface", Arch.INTERFACES, -1, -1, -1);

    /** Name used in idmap.json and report.json. */
    public final String key;
    public final int archive;
    /** Config group holding this kind, or -1 when the ids are group ids of {@link #archive}. */
    public final int configGroup;
    public final int rangeLo, rangeHi;

    Kind(String key, int archive, int configGroup, int lo, int hi) {
        this.key = key; this.archive = archive; this.configGroup = configGroup; this.rangeLo = lo; this.rangeHi = hi;
    }

    public boolean isConfig() { return configGroup >= 0; }

    /** Whether the id exists in the cache. */
    public boolean exists(CacheView c, int id) {
        return isConfig() ? c.hasFile(Arch.CONFIGS, configGroup, id) : c.hasGroup(archive, id);
    }

    /** All ids of this kind in the cache, ascending. */
    public int[] ids(CacheView c) {
        return isConfig() ? c.fileIds(Arch.CONFIGS, configGroup) : c.groupIds(archive);
    }

    /** The entry's bytes (config file, or file 0 of the group) copied, or null. */
    public byte[] bytes(CacheView c, int id) {
        return isConfig() ? c.config(configGroup, id) : c.single(archive, id);
    }

    public static Kind byKey(String key) {
        for (Kind k : values()) if (k.key.equals(key)) return k;
        throw new IllegalArgumentException("unknown kind " + key);
    }
}
