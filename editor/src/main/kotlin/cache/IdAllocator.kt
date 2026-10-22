package cache

import com.displee.cache.CacheLibrary

/**
 * Kinds of cache entries the editor creates, and where new ones go.
 *
 * In a build 241 cache, new GadScape content takes ids from the range reserved for it (plan/gadconv-spec.md, the
 * same ranges the cache converter uses), skipping ids already taken. It never takes an id the 241 base uses and never
 * goes outside the range: the next OSRS cache adds its new content right after its current highest ids, so "highest
 * id + 1" would collide with it later (the Obligator frame sets 4478-4481 did exactly that, CRIT-18).
 *
 * Models and objects are the exception: their ids must stay at or below 65534 in every format, although build 241
 * configs store model ids in 4 bytes. The 241 client asks the JS5 server for a group with a 16-bit group id (rsprot
 * reads g1 archive + g2 group, and the server refuses groups above 65535), so a model above 65535 can never be
 * downloaded; the game protocol carries object ids in 16 bits (rsprot OpLoc / LocAddChange), so clicking object 70000
 * reaches the server as object 4464. 65535 is the "none" value of 16-bit model and object fields, so the top is 65534.
 * The 241 base's own models and objects end near 62000-63300 and the next OSRS caches add theirs right after, so new
 * ones are handed out from 65534 downwards (the highest free id first), as the converter packs the models it moves
 * (models-pack=top): the ids right after the game's own stay free as long as possible.
 *  - models: any free id up to 65534, from the top down. The range can't start at "the cache's highest model + 1":
 *    GadScape's merged cache already has models up to 65535 (the 211 cache's own models, at their 211 ids).
 *  - objects: 64000-65534, from the top down (the 241 base's objects end at 63306 in OpenRS2 2746).
 *
 * In a rev 211 cache the same ranges are used where the 211 formats can hold them; models and objects keep the old
 * rule there: the first run of 50 free ids, at most 65534.
 */
enum class IdKind(
    val label: String,
    /** The reserved GadScape range in a 241 cache. */
    val reserved: IntRange,
    /** The highest id the 211 formats can refer to (for [oldRule211] kinds: the highest id handed out in 211). */
    val max211: Int,
    /** New ids are handed out from the top of the range down (the highest free id first). */
    val topDown: Boolean = false,
    /** In a 211 cache new ids keep the old rule (the first run of 50 free ids up to [max211]) instead of [reserved]. */
    val oldRule211: Boolean = false,
) {
    /** Archive 7 groups. 4-byte references in 241, but the client fetches groups with 16-bit ids: never above 65534. */
    MODEL("model", 0..CacheIO.MAX_NEW_U16_ID, CacheIO.MAX_NEW_U16_ID, topDown = true, oldRule211 = true),
    /** Archive 0 groups. The client gets the set back with a signed shift (frame >> 16): never above 32767. */
    FRAME_SET("frame set", 30000..32767, 32767),
    /** Archive 1 groups (skeletons / frame maps). Frame and keyframe files store the id in 2 bytes. */
    FRAMEMAP("skeleton", 30000..32767, 32767),
    /** Archive 22 groups in 241 (archive 0 groups in 211). Same signed shift as frame sets. */
    KEYFRAME_SET("keyframe set", 20000..29999, 32767),
    /** Config 12 files. */
    SEQ("animation", 40000..49999, 65535),
    /** Archive 8 groups. */
    SPRITE("sprite", 20000..29999, 65535),
    /** Config 6 files. The game protocol carries object ids in 16 bits: never above 65534. */
    LOC("object", 64000..CacheIO.MAX_NEW_U16_ID, CacheIO.MAX_NEW_U16_ID, topDown = true, oldRule211 = true),
    /** Archive 12 groups. */
    SCRIPT("clientscript", 47000..47999, 65535),
    /** Config 8 files. */
    ENUM("enum", 20000..20999, 65535),
    /** Config 34 files. */
    STRUCT("struct", 13000..13999, 65535),
    /** Config 11 files. */
    PARAM("param", 9000..9999, 65535),
    /** Config 14 files. */
    VARBIT("varbit", 30000..34999, 65535),
    /** Config 16 files. */
    VARP("varp", 9000..9999, 65535),
    /** Config 38 files. */
    DBROW("db row", 60000..64999, 65535);

    /** The ids of this kind that [lib] already has. */
    fun usedIds(lib: CacheLibrary): IntArray {
        fun groups(archive: Int) = if (lib.exists(archive)) lib.index(archive).archiveIds() else IntArray(0)
        fun configs(group: Int) = if (lib.exists(CacheIO.CONFIGS)) lib.index(CacheIO.CONFIGS).archive(group)?.fileIds() ?: IntArray(0) else IntArray(0)
        return when (this) {
            MODEL -> groups(CacheIO.MODELS)
            FRAME_SET -> groups(CacheIO.FRAMES)
            // in a 211 cache keyframe sets are archive 0 groups too (their range doesn't overlap the frame sets')
            KEYFRAME_SET -> groups(CacheIO.keyframeArchive(lib))
            FRAMEMAP -> groups(CacheIO.FRAMEMAPS)
            SEQ -> configs(12)
            SPRITE -> groups(CacheIO.SPRITES)
            LOC -> configs(6)
            SCRIPT -> groups(CacheIO.CLIENTSCRIPTS)
            ENUM -> configs(8)
            STRUCT -> configs(34)
            PARAM -> configs(11)
            VARBIT -> configs(14)
            VARP -> configs(16)
            DBROW -> configs(38)
        }
    }

    /** True if new ids in [lib] come from [reserved] (else from the 211 rule, see the class comment). */
    fun usesReserved(lib: CacheLibrary): Boolean =
        CacheIO.is241(lib) || (!oldRule211 && reserved.last <= max211)

    /** The range new ids of this kind come from in [lib]'s format (see the class comment). */
    fun rangeFor(lib: CacheLibrary): IntRange = if (usesReserved(lib)) reserved else 0..max211

    /** True if [id] is in GadScape's reserved range for this kind (for models: any id a 241 cache can serve). */
    fun isReserved(id: Int): Boolean = id in reserved

    /** "30000-32767", or "65534 down to 64000" for kinds handed out from the top. */
    fun describeRange(range: IntRange = reserved): String =
        if (topDown) "${range.last} down to ${range.first}" else "${range.first}-${range.last}"
}

/**
 * Hands out new ids of one [kind] for [lib] (see [IdKind]). [alsoTaken]: ids to treat as used although they aren't in
 * the cache yet (e.g. models waiting to be written). Ids handed out are remembered, so one allocator gives each id once.
 */
class IdAllocator(lib: CacheLibrary, val kind: IdKind, alsoTaken: Collection<Int> = emptyList()) {
    private val used = HashSet<Int>().apply { kind.usedIds(lib).forEach { add(it) }; addAll(alsoTaken) }
    private val inReserved = kind.usesReserved(lib)
    private val range = kind.rangeFor(lib)
    private val down = inReserved && kind.topDown
    private val format = CacheIO.describe(lib)
    private var next: Int = if (!inReserved) firstRunStart() else if (down) range.last else range.first

    /** The 211 fallback: where the first run of 50 free ids starts (after the game's own), or the first free id. */
    private fun firstRunStart(): Int {
        var start = -1; var run = 0
        for (i in range) {
            if (i in used) run = 0 else { if (run == 0) start = i; run++; if (run >= 50) return start }
        }
        return range.firstOrNull { it !in used } ?: (range.last + 1)
    }

    /** Marks [id] as taken (e.g. one picked by hand), so [next] never hands it out. */
    fun take(id: Int) { used.add(id) }

    fun isFree(id: Int): Boolean = id !in used

    fun next(): Int {
        if (down) {
            while (next >= range.first && next in used) next--
            require(next >= range.first) { "There are no free ${kind.label} ids left in GadScape's range ${kind.describeRange(range)} ($format cache)." }
            used.add(next)
            return next--
        }
        while (next <= range.last && next in used) next++
        require(next <= range.last) {
            if (inReserved) "There are no free ${kind.label} ids left in GadScape's range ${range.first}-${range.last} ($format cache)."
            else "There are no free ${kind.label} ids left (the $format format only goes up to ${range.last})."
        }
        used.add(next)
        return next++
    }
}
