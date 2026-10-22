package com.gadscape.raids.tfa.ids

import com.gadscape.raids.tfa.TfaPaths
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap

/**
 * The kinds of cache ids that [TfaIds] holds and that `remap.tsv` can move.
 *
 * [aliases] are the spellings accepted in the first column of `remap.tsv` (case-insensitive).
 */
enum class IdKind(vararg val aliases: String) {
    NPC("npc"),
    LOC("loc", "object"),
    OBJ("obj", "item"),
    SEQ("seq", "anim", "animation"),
    SPOTANIM("spotanim", "spot", "gfx"),
    INTERFACE("interface", "if"),
    CLIENTSCRIPT("clientscript", "script", "proc"),
    VARP("varp", "varplayer"),
    VARBIT("varbit", "varplayerbit"),
    VARC("varc", "varcint", "varcstr"),
    DBROW("dbrow"),
    DBTABLE("dbtable"),
    ENUM("enum"),
    STRUCT("struct"),
    INV("inv"),
    PARAM("param"),
    HEADBAR("headbar"),
    HITSPLAT("hitsplat"),
    MIDI("midi", "music"),
    JINGLE("jingle"),
    SYNTH("synth", "sound"),
    AMBIENCE("ambience"),
    MODEL("model"),
    SPRITE("sprite", "graphic"),
    REGION("region", "mapsquare"),
    ;

    companion object {
        private val byAlias: Map<String, IdKind> = entries.flatMap { k -> k.aliases.map { it to k } }.toMap()

        /** The kind for a `remap.tsv` kind column, or null if unknown. */
        @JvmStatic
        fun parse(text: String): IdKind? = byAlias[text.trim().lowercase()]
    }
}

/**
 * Old → new id mappings per [IdKind], read from `data/raids/tfa/remap.tsv` (written by the asset port, WU-01).
 *
 * Every id in the spec is a live 2746 id. The port may move raid assets that clash with GadScape content
 * (spec §0.2: seqs 15000-15162 → 15300+, clientscripts 10034-10121 → 11000+, structs 1216-1774 → 13000+).
 * [TfaIds] resolves each of its ids through [active] once, when its group is first used, so server code never
 * holds a raw live id. An id that the table does not mention maps to itself; with no file at all the table is
 * the identity.
 *
 * ### File format (tab-separated, UTF-8)
 * ```
 * # comment lines start with '#'; blank lines are ignored; a header line "kind<TAB>old<TAB>new" is skipped
 * kind <TAB> old <TAB> new [<TAB> free-form note]
 * seq          15000-15162   15300-15462      # a range maps onto a range of the same length, in order
 * clientscript 10036         11002            # a single id
 * ```
 * `kind` is one of the [IdKind.aliases] (e.g. `npc`, `loc`, `obj`, `seq`, `spotanim`, `clientscript`, `struct`).
 * A range on the left may also map onto a single start id on the right (`15000-15162 15300`).
 *
 * Load order: `data/raids/tfa/remap.tsv` if present, otherwise a bundled `tfa/remap.tsv` resource if the module
 * ships one, otherwise identity. Problems are logged and listed in [problems]; a bad line is skipped, never fatal.
 */
class RemapTable private constructor(
    private val maps: Map<IdKind, Int2IntOpenHashMap>,
    /** Where the table came from, for logs ("identity", a path or a classpath resource). */
    val source: String,
    /** Parse and consistency problems, one human-readable line each (with the line number). */
    val problems: List<String>,
) {

    /** Resolves a live 2746 id of [kind] to the id the server must use. */
    fun resolve(kind: IdKind, liveId: Int): Int {
        val map = maps[kind] ?: return liveId
        return if (map.containsKey(liveId)) map.get(liveId) else liveId
    }

    /** Number of remapped ids of [kind]. */
    fun size(kind: IdKind): Int = maps[kind]?.size ?: 0

    /** Total number of remapped ids. */
    val size: Int get() = maps.values.sumOf { it.size }

    val isIdentity: Boolean get() = size == 0

    /** All mappings of [kind] as (old, new) pairs, sorted by old id. For dumps and tests. */
    fun entries(kind: IdKind): List<Pair<Int, Int>> {
        val map = maps[kind] ?: return emptyList()
        return map.keys.toIntArray().sorted().map { it to map.get(it) }
    }

    override fun toString(): String =
        "RemapTable(source=$source, ${if (isIdentity) "identity" else maps.entries.joinToString { "${it.key}=${it.value.size}" }})"

    companion object {

        private val log = LoggerFactory.getLogger(RemapTable::class.java)

        /** Bundled fallback: `src/main/resources/tfa/remap.tsv` (the module ships none today). */
        const val BUNDLED_RESOURCE = "${TfaPaths.BUNDLED_RESOURCE_DIR}/remap.tsv"

        @JvmField
        val IDENTITY = RemapTable(emptyMap(), "identity", emptyList())

        @Volatile
        private var loaded: RemapTable? = null

        /**
         * The table [TfaIds] resolves through, loaded on first use. A change to `remap.tsv` needs a restart:
         * [TfaIds] values are resolved once (see [TfaIds] for why).
         */
        @JvmStatic
        val active: RemapTable
            get() = loaded ?: synchronized(this) {
                loaded ?: loadDefault().also {
                    loaded = it
                    logSummary(it)
                }
            }

        /**
         * Installs [table] as [active]. Only valid before any [TfaIds] group has been read (tests, tools);
         * afterwards it throws, because the resolved ids are already fixed.
         */
        @JvmStatic
        fun install(table: RemapTable) = synchronized(this) {
            check(!TfaIds.anyGroupInitialised()) { "TfaIds were already resolved through ${loaded?.source}; install the remap first" }
            loaded = table
        }

        /** Loads [path], or the bundled resource, or [IDENTITY]. */
        @JvmStatic
        fun loadDefault(path: Path = TfaPaths.remapFile): RemapTable {
            if (Files.isRegularFile(path)) {
                return try {
                    parse(Files.readAllLines(path, StandardCharsets.UTF_8), path.toString())
                } catch (e: Exception) {
                    log.error("Could not read {}; Fractured Archive ids stay unmapped", path, e)
                    RemapTable(emptyMap(), "identity (unreadable $path)", listOf("unreadable: ${e.message}"))
                }
            }
            val resource = RemapTable::class.java.classLoader.getResourceAsStream(BUNDLED_RESOURCE)
            if (resource != null) {
                return resource.use { parse(it.reader(StandardCharsets.UTF_8).readLines(), "classpath:$BUNDLED_RESOURCE") }
            }
            return IDENTITY
        }

        /** Parses the lines of a `remap.tsv`. Never throws for bad content: bad lines go to [problems]. */
        @JvmStatic
        fun parse(lines: List<String>, source: String): RemapTable {
            val maps = EnumMap<IdKind, Int2IntOpenHashMap>(IdKind::class.java)
            val problems = ArrayList<String>()
            // new id -> first (old id, line) that claimed it, per kind, to report two old ids landing on one new id
            val claimed = EnumMap<IdKind, Int2IntOpenHashMap>(IdKind::class.java)
            lines.forEachIndexed { index, raw ->
                val lineNo = index + 1
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val cols = line.split('\t', ' ').filter { it.isNotBlank() }
                if (cols.size < 3) {
                    problems += "$source:$lineNo: expected 'kind<TAB>old<TAB>new', got \"$raw\""
                    return@forEachIndexed
                }
                val kind = IdKind.parse(cols[0])
                if (kind == null) {
                    if (index == 0 && cols[1].equals("old", ignoreCase = true)) return@forEachIndexed // header
                    problems += "$source:$lineNo: unknown kind \"${cols[0]}\" (one of ${IdKind.entries.joinToString { it.aliases[0] }})"
                    return@forEachIndexed
                }
                val old = parseRange(cols[1])
                val new = parseRange(cols[2])
                if (old == null || new == null) {
                    problems += "$source:$lineNo: bad id or range in \"$raw\""
                    return@forEachIndexed
                }
                val newFirst = new.first
                if (new.count() != 1 && new.count() != old.count()) {
                    problems += "$source:$lineNo: range ${cols[1]} has ${old.count()} ids but ${cols[2]} has ${new.count()}"
                    return@forEachIndexed
                }
                val map = maps.getOrPut(kind) { Int2IntOpenHashMap() }
                val taken = claimed.getOrPut(kind) { Int2IntOpenHashMap() }
                for ((offset, oldId) in old.withIndex()) {
                    val newId = newFirst + offset
                    if (map.containsKey(oldId) && map.get(oldId) != newId) {
                        problems += "$source:$lineNo: $kind $oldId is already mapped to ${map.get(oldId)}; ignoring -> $newId"
                        continue
                    }
                    if (taken.containsKey(newId) && taken.get(newId) != oldId) {
                        problems += "$source:$lineNo: $kind $oldId and ${taken.get(newId)} both map to $newId"
                    }
                    map.put(oldId, newId)
                    taken.put(newId, oldId)
                }
            }
            maps.values.removeIf { it.isEmpty() }
            return RemapTable(maps, source, problems)
        }

        private fun parseRange(text: String): IntRange? {
            val parts = text.split('-', limit = 2)
            val lo = parts[0].trim().toIntOrNull() ?: return null
            val hi = if (parts.size == 2) parts[1].trim().toIntOrNull() ?: return null else lo
            if (lo < 0 || hi < lo) return null
            return lo..hi
        }

        private fun logSummary(table: RemapTable) {
            if (table.isIdentity) {
                log.info("Fractured Archive id remap: identity ({}).", table.source)
            } else {
                log.info("Fractured Archive id remap: {} ids from {}: {}", table.size, table.source,
                    IdKind.entries.filter { table.size(it) > 0 }.joinToString { "$it=${table.size(it)}" })
            }
            for (problem in table.problems) log.warn("remap.tsv: {}", problem)
        }
    }
}
