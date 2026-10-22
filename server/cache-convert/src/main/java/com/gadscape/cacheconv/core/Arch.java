package com.gadscape.cacheconv.core;

/**
 * JS5 archive ids and config (archive 2) group ids, as build 241 lays them out.
 * Build 211 has no archives 22 and 24; build 241 has no archives 16 and 23.
 */
public final class Arch {
    private Arch() {}

    // archives
    public static final int FRAMES = 0;            // 211/241 frame groups (group<<16 | file)
    public static final int FRAMEMAPS = 1;         // skeleton bases
    public static final int CONFIGS = 2;
    public static final int INTERFACES = 3;
    public static final int SYNTHS = 4;
    public static final int MAPS = 5;
    public static final int MUSIC = 6;
    public static final int MODELS = 7;
    public static final int SPRITES = 8;
    public static final int TEXTURES = 9;
    public static final int BINARY = 10;
    public static final int JINGLES = 11;
    public static final int SCRIPTS = 12;
    public static final int FONTS = 13;
    public static final int VORBIS = 14;
    public static final int INSTRUMENTS = 15;
    public static final int WORLDMAP_LEGACY = 16;  // gone in 241
    public static final int DEFAULTS = 17;
    public static final int WORLDMAP_GEOGRAPHY = 18;
    public static final int WORLDMAP = 19;
    public static final int WORLDMAP_GROUND = 20;
    public static final int DBTABLE_INDEX = 21;
    public static final int KEYFRAMES = 22;        // skeletal (animaya) keyframes, 241 only
    public static final int GAMEVALS = 24;         // 241 only

    // config groups (archive 2)
    public static final int CFG_UNDERLAY = 1;
    public static final int CFG_IDENTKIT = 3;
    public static final int CFG_OVERLAY = 4;
    public static final int CFG_INV = 5;
    public static final int CFG_LOC = 6;
    public static final int CFG_ENUM = 8;
    public static final int CFG_NPC = 9;
    public static final int CFG_OBJ = 10;
    public static final int CFG_PARAM = 11;
    public static final int CFG_SEQ = 12;
    public static final int CFG_SPOT = 13;
    public static final int CFG_VARBIT = 14;
    public static final int CFG_VARCSTR = 15;
    public static final int CFG_VARP = 16;
    public static final int CFG_VARC = 19;
    public static final int CFG_HITSPLAT = 32;
    public static final int CFG_HEALTHBAR = 33;
    public static final int CFG_STRUCT = 34;
    public static final int CFG_AREA = 35;
    public static final int CFG_DBROW = 38;
    public static final int CFG_DBTABLE = 39;

    public static String name(int archive) {
        return switch (archive) {
            case FRAMES -> "frames"; case FRAMEMAPS -> "framemaps"; case CONFIGS -> "configs"; case INTERFACES -> "interfaces";
            case SYNTHS -> "synths"; case MAPS -> "maps"; case MUSIC -> "music"; case MODELS -> "models"; case SPRITES -> "sprites";
            case TEXTURES -> "textures"; case BINARY -> "binary"; case JINGLES -> "jingles"; case SCRIPTS -> "clientscripts";
            case FONTS -> "fonts"; case VORBIS -> "vorbis"; case INSTRUMENTS -> "instruments"; case WORLDMAP_LEGACY -> "worldmap-legacy";
            case DEFAULTS -> "defaults"; case WORLDMAP_GEOGRAPHY -> "worldmap-geography"; case WORLDMAP -> "worldmap";
            case WORLDMAP_GROUND -> "worldmap-ground"; case DBTABLE_INDEX -> "dbtable-index"; case KEYFRAMES -> "keyframes";
            case GAMEVALS -> "gamevals"; default -> "archive" + archive;
        };
    }

    public static String configName(int group) {
        return switch (group) {
            case CFG_UNDERLAY -> "underlay"; case CFG_IDENTKIT -> "identkit"; case CFG_OVERLAY -> "overlay"; case CFG_INV -> "inv";
            case CFG_LOC -> "loc"; case CFG_ENUM -> "enum"; case CFG_NPC -> "npc"; case CFG_OBJ -> "obj"; case CFG_PARAM -> "param";
            case CFG_SEQ -> "seq"; case CFG_SPOT -> "spotanim"; case CFG_VARBIT -> "varbit"; case CFG_VARCSTR -> "varcstr";
            case CFG_VARP -> "varp"; case CFG_VARC -> "varc"; case CFG_HITSPLAT -> "hitsplat"; case CFG_HEALTHBAR -> "healthbar";
            case CFG_STRUCT -> "struct"; case CFG_AREA -> "area"; case CFG_DBROW -> "dbrow"; case CFG_DBTABLE -> "dbtable";
            default -> "config" + group;
        };
    }
}
