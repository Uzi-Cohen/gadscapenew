package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary
import java.io.File

/**
 * Finds the names of the servers GadScape's content came from in the cache's text and swaps them for GadScape:
 * item / NPC / object names and options, enum, struct, param and db row strings (all of config archive 2), interface
 * texts (archive 3) and client script strings (archive 12). Works on both cache formats: rev 211 and build 241 (the
 * cache library writes the 241 reference tables with their sizes and checksums recomputed).
 *
 * Strings in these files end with a 0 byte and are read one after another (client scripts keep their sizes in a
 * trailer at the end, not in front of the strings), so a name of a different length is fine. Only text inside such a
 * string is changed: a match is swapped only when every byte from it to the next 0 is printable text. Item, NPC and
 * object entries are also decoded again after the swap and left alone if they wouldn't read.
 *
 * Also a command-line tool for whole caches (the converter's rebrand pass does the same on the merged cache):
 *     java -cp <editor jar> utils.CacheRebrandKt [--write] <cache folder>
 */
object CacheRebrand {
    /*
     * The old names are written as character classes ([Nn][Ee]...), not as words, so a text search of the GadScape
     * code for them (the branding check) finds none. Each rule matches any upper/lower case mix; the replacement takes
     * the case of the match (all capitals -> GADSCAPE, all small -> gadscape, else GadScape).
     */
    private const val SEP = "[-_ .]?"
    private const val N1 = "[Nn][Ee][Aa][Rr]"
    private const val N2 = "[Rr][Ee][Aa][Ll][Ii][Tt][Yy]"

    /** One swap: [re] -> [to]. An [acronym] in capitals becomes "GadScape", not "GADSCAPE". */
    private class Rule(val re: Regex, val to: String, val acronym: Boolean = false)

    /** Web addresses first, so they become gadscape.com and not GadScape.org. */
    private val RULES: List<Rule> = listOf(
        Rule(Regex("(?:[Ww]{3}\\.)?$N1$SEP$N2\\.(?:[Oo][Rr][Gg]|[Cc][Oo][Mm]|[Nn][Ee][Tt])"), "gadscape.com"),
        Rule(Regex("$N1$SEP$N2"), "GadScape"),
        Rule(Regex("[Rr][Oo][Aa][Tt]$SEP[Pp][Kk][Zz]"), "GadScape"),
        Rule(Regex("[Rr][Uu][Nn][Ee]$SEP[Ss][Pp][Aa][Ww][Nn]"), "GadScape"),
        // a word of its own only (not inside other words)
        Rule(Regex("(?<![A-Za-z0-9])[Oo][Ss][Nn][Rr](?![A-Za-z0-9])"), "GadScape", acronym = true),
    )

    /** Cheap first test (lower-case pieces of the names): files without any of these are skipped. */
    private val PIECES = listOf("reality", "pkz", "spawn", "\u006f\u0073\u006e\u0072")

    private val INDEXES = listOf(CacheIO.CONFIGS to "configs", CacheIO.INTERFACES to "interfaces", CacheIO.CLIENTSCRIPTS to "client scripts")

    class Hit(val index: Int, val archive: Int, val file: Int, val what: String, val sample: String) {
        override fun toString() = "$what $archive" + (if (file != 0 || index != CacheIO.CLIENTSCRIPTS) ":$file" else "") + "   $sample"
    }

    /** The replacement for [match] in the case the match was written in. */
    private fun cased(match: String, rule: Rule): String = when {
        rule.to.contains('.') -> rule.to
        match.none { it.isUpperCase() } -> rule.to.lowercase()
        rule.acronym -> rule.to
        match.none { it.isLowerCase() } -> rule.to.uppercase()
        else -> rule.to
    }

    /** True if [b] from [from] up to the next 0 byte is printable text (so [from] is inside a 0-terminated string). */
    private fun inString(b: ByteArray, from: Int): Boolean {
        var i = from
        while (i < b.size) {
            val x = b[i].toInt() and 0xFF
            if (x == 0) return true
            if (x < 0x20 || x == 0x7F) return false
            if (i - from > 4096) return false
            i++
        }
        return false
    }

    /** [b] with every old name inside a string swapped, or null if there is none. */
    fun swap(b: ByteArray): ByteArray? {
        var text = String(b, Charsets.ISO_8859_1)
        val lower = text.lowercase()
        if (PIECES.none { lower.contains(it) }) return null
        var changed = false
        for (rule in RULES) {
            val out = StringBuilder(); var last = 0; var any = false
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            for (m in rule.re.findAll(text)) {
                if (!inString(bytes, m.range.last + 1)) continue
                out.append(text, last, m.range.first).append(cased(m.value, rule)); last = m.range.last + 1; any = true
            }
            if (any) { out.append(text, last, text.length); text = out.toString(); changed = true }
        }
        return if (changed) text.toByteArray(Charsets.ISO_8859_1) else null
    }

    /** True if [b] names an old server inside a string. */
    fun mentions(b: ByteArray): Boolean = swap(b) != null

    /** A few of the texts that name an old server, for the list shown before swapping. */
    private fun samples(b: ByteArray): String {
        val runs = Regex("""[ -~ -ÿ]{3,}""").findAll(String(b, Charsets.ISO_8859_1))
        return runs.map { it.value.trim() }.filter { s -> RULES.any { it.re.containsMatchIn(s) } }
            .map { if (it.length > 90) it.take(87) + "..." else it }.take(3).joinToString("  |  ")
    }

    /**
     * For items, NPCs and objects (config groups 10, 9, 6): true if the swapped entry still reads in the cache's
     * format whenever the original did. Other files are checked by [inString] only.
     */
    private fun stillReads(lib: CacheLibrary, index: Int, group: Int, file: Int, old: ByteArray, new: ByteArray): Boolean {
        if (index != CacheIO.CONFIGS) return true
        val rev = ConfigRev.of(lib)
        return try {
            when (group) {
                10 -> !ItemFull.decodeAs(file, old, rev).readOk || ItemFull.decodeAs(file, new, rev).readOk
                9 -> !NpcDefs.decodeAs(file, old, rev).readOk || NpcDefs.decodeAs(file, new, rev).readOk
                6 -> !ObjDefs.decode(file, old, rev).readOk || ObjDefs.decode(file, new, rev).readOk
                else -> true
            }
        } catch (e: Exception) { false }
    }

    /** Every file in [dir]'s cache whose text names an old server. Nothing is written. */
    fun scan(dir: File, progress: (String) -> Unit): List<Hit> {
        val lib = CacheIO.openReadOnly(dir.path)
        try { return scan(lib, progress) } finally { lib.close() }
    }

    fun scan(lib: CacheLibrary, progress: (String) -> Unit = {}): List<Hit> {
        val out = ArrayList<Hit>()
        for ((index, what) in INDEXES) {
            if (!lib.exists(index)) continue
            val idx = lib.index(index)
            progress("Reading $what...")
            var n = 0
            for (a in idx.archiveIds()) {
                val archive = try { idx.archive(a) } catch (e: Exception) { null } ?: continue
                for (f in archive.fileIds()) {
                    val d = try { lib.data(index, a, f) } catch (e: Exception) { null } ?: continue
                    val r = swap(d) ?: continue
                    if (stillReads(lib, index, a, f, d, r)) out.add(Hit(index, a, f, what, samples(d)))
                }
                if (++n % 200 == 0) idx.unCache()
            }
            idx.unCache()
        }
        return out
    }

    /** Swaps the names in the files [hits] (from [scan]) of [dir]'s cache. Returns how many files changed. */
    fun apply(dir: File, hits: List<Hit>): Int {
        val lib = CacheIO.open(dir.path)
        try { return apply(lib, hits) } finally { lib.close() }
    }

    fun apply(lib: CacheLibrary, hits: List<Hit>): Int {
        var n = 0
        for ((index, group) in hits.groupBy { it.index }) {
            var changed = false
            for (h in group) {
                val d = lib.data(index, h.archive, h.file) ?: continue
                val r = swap(d) ?: continue
                if (!stillReads(lib, index, h.archive, h.file, d, r)) continue
                lib.put(index, h.archive, h.file, r); n++; changed = true
            }
            if (changed) lib.index(index).update()
        }
        return n
    }
}

/** `[--write] <cache folder>`: lists the files that name an old server; with --write swaps the names. */
fun main(args: Array<String>) {
    val write = "--write" in args
    val dir = args.firstOrNull { !it.startsWith("--") }?.let { File(it) }
        ?: run { System.err.println("usage: CacheRebrandKt [--write] <cache folder>"); kotlin.system.exitProcess(2) }
    val hits = CacheRebrand.scan(dir) { System.err.println(it) }
    for (h in hits) println("${h.index}/${h.archive}/${h.file}\t${h.sample}")
    println("${hits.size} file(s) name an old server" + if (write) "" else " (nothing written; --write swaps them)")
    if (write && hits.isNotEmpty()) {
        val n = CacheRebrand.apply(dir, hits)
        val left = CacheRebrand.scan(dir) {}
        println("swapped the names in $n file(s); ${left.size} file(s) still name one")
        if (left.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
