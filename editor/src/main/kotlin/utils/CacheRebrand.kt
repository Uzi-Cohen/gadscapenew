package utils

import com.displee.cache.CacheLibrary
import java.io.File

/**
 * Finds old server names in the cache's text (interfaces, client scripts, configs like item / NPC / object names,
 * enums and structs) and swaps them. Strings in these files end with a 0 byte and are read one after another, so a
 * name of a different length is fine. Works on the exported cache (cache-out); deploy the map to send it.
 */
object CacheRebrand {
    /** Longest first, so web addresses are swapped before the bare name. */
    val replacements = listOf(
        "www.near-reality.org" to "gadscape.com", "www.near-reality.com" to "gadscape.com",
        "near-reality.org" to "gadscape.com", "near-reality.com" to "gadscape.com",
        "Near-Reality" to "GadScape", "Near Reality" to "GadScape", "NEAR-REALITY" to "GADSCAPE",
        "Near-reality" to "GadScape", "near-reality" to "gadscape", "NearReality" to "GadScape"
    )
    private val INDEXES = listOf(2 to "configs", 3 to "interfaces", 12 to "client scripts")

    class Hit(val index: Int, val archive: Int, val file: Int, val what: String, val sample: String) {
        override fun toString() = "$what $archive" + (if (file != 0 || index != 12) ":$file" else "") + "   $sample"
    }

    private fun swap(s: String): String { var r = s; for ((a, b) in replacements) r = r.replace(a, b); return r }

    private fun samples(text: String): String =
        Regex("""[ -~]{0,40}(?:near.?reality)[ -~]{0,40}""", RegexOption.IGNORE_CASE).findAll(text).take(3).joinToString("  |  ") { it.value.trim() }

    fun scan(dir: File, progress: (String) -> Unit): List<Hit> {
        val out = ArrayList<Hit>()
        val lib = CacheLibrary(dir.path)
        try {
            for ((index, what) in INDEXES) {
                val idx = try { lib.index(index) } catch (e: Exception) { continue }
                progress("Reading $what...")
                for (a in idx.archiveIds()) {
                    val archive = try { idx.archive(a) } catch (e: Exception) { null } ?: continue
                    for (f in archive.fileIds()) {
                        val d = try { lib.data(index, a, f) } catch (e: Exception) { null } ?: continue
                        val t = String(d, Charsets.ISO_8859_1)
                        if (!t.contains("eality", true)) continue
                        if (swap(t) != t) out.add(Hit(index, a, f, what, samples(t)))
                    }
                    if (index == 2) idx.unCache()
                }
                idx.unCache()
            }
        } finally { lib.close() }
        return out
    }

    fun apply(dir: File, hits: List<Hit>): Int {
        val lib = CacheLibrary(dir.path)
        var n = 0
        try {
            for ((index, group) in hits.groupBy { it.index }) {
                for (h in group) {
                    val d = lib.data(index, h.archive, h.file) ?: continue
                    val t = String(d, Charsets.ISO_8859_1)
                    val r = swap(t)
                    if (r != t) { lib.put(index, h.archive, h.file, r.toByteArray(Charsets.ISO_8859_1)); n++ }
                }
                lib.index(index).update()
            }
        } finally { lib.close() }
        return n
    }
}
