package com.gadscape.cacheconv.core;

/**
 * Id spaces that can be relocated, where their ids live in a cache, and the reserved GadScape range relocated ids go
 * to (from the gadconv spec, corrected where the 241 client or protocol cannot carry the spec's ids; -1/-1 = no
 * static range: relocating that kind needs {@link IdMap#setRange} first).
 * A kind is either the groups of an archive ({@link #configGroup} == -1) or the files of one config group.
 * <ul>
 *   <li>{@link #MODEL}: the range depends on BASE: {@code [highest BASE model + 1, 65535]} ({@link #MAX_MODEL}; the 241
 *       client's JS5 requests and replies carry 16-bit group ids). {@link Ctx#open} sets it; {@code models} may narrow
 *       it ({@code --models-range}).</li>
 *   <li>{@link #LOC}: 64500-65534, not the spec's 70000-79999: loc ids are u16 on the 241 wire (rsprot OpLoc/LocAddChange)
 *       and in multiloc transform lists.</li>
 *   <li>{@link #INTERFACE} 5200-5299 and {@link #DBTABLE} 4000-4999 were added to the spec's ranges (free in BASE and
 *       USER).</li>
 * </ul>
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
    DBTABLE("dbtable", Arch.CONFIGS, Arch.CFG_DBTABLE, 4000, 4999),
    /** Range computed from BASE: see the class comment and {@link #modelRange}. */
    MODEL("model", Arch.MODELS, -1, -1, -1),
    LOC("loc", Arch.CONFIGS, Arch.CFG_LOC, 64500, 65534),
    OBJ("obj", Arch.CONFIGS, Arch.CFG_OBJ, -1, -1),
    NPC("npc", Arch.CONFIGS, Arch.CFG_NPC, -1, -1),
    SPOT("spot", Arch.CONFIGS, Arch.CFG_SPOT, -1, -1),
    VARBIT("varbit", Arch.CONFIGS, Arch.CFG_VARBIT, 30000, 34999),
    VARP("varp", Arch.CONFIGS, Arch.CFG_VARP, 9000, 9999),
    VARC("varc", Arch.CONFIGS, Arch.CFG_VARC, -1, -1),
    STRUCT("struct", Arch.CONFIGS, Arch.CFG_STRUCT, 13000, 13999),
    ENUM("enum", Arch.CONFIGS, Arch.CFG_ENUM, 20000, 20999),
    PARAM("param", Arch.CONFIGS, Arch.CFG_PARAM, 9000, 9999),
    INTERFACE("interface", Arch.INTERFACES, -1, 5200, 5299),
    /** Inventories (config 5). No range: clashing user inventories are kept at their ids and reported. */
    INV("inv", Arch.CONFIGS, Arch.CFG_INV, -1, -1),
    /** Health bars (config 33). No range: clashing user health bars are kept at their ids and reported. */
    HEALTHBAR("healthbar", Arch.CONFIGS, Arch.CFG_HEALTHBAR, -1, -1);

    /** Highest model id the build-241 client can fetch (JS5 group ids are 16-bit). */
    public static final int MAX_MODEL = 65535;

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

    /**
     * The model relocation range for a cache built on {@code base}: the ids after BASE's highest model up to
     * {@link #MAX_MODEL}, or null when BASE leaves no room.
     */
    public static IdMap.Range modelRange(CacheView base) {
        int[] ids = base.groupIds(Arch.MODELS);
        int lo = ids.length == 0 ? 0 : ids[ids.length - 1] + 1;
        return lo <= MAX_MODEL ? new IdMap.Range(lo, MAX_MODEL) : null;
    }

    public static Kind byKey(String key) {
        for (Kind k : values()) if (k.key.equals(key)) return k;
        throw new IllegalArgumentException("unknown kind " + key);
    }
}
