package cache

import com.google.gson.Gson
import java.io.File

/**
 * The map XTEA keys from `xteas.json` in [path] (the map folder). Only rev 211 caches need them: Jagex encrypted the
 * objects group of each square. Build 241 caches have no map encryption, so a folder holding a build 241 cache (in
 * [path] itself, `cache` or `cache-out`) needs no xteas.json and this is then simply empty; for anything else a
 * missing xteas.json is still an error (the editor would show those squares without objects and could save them
 * empty). Accepts OpenRS2's format (`mapsquare` + `key`, optional `name` "l{x}_{y}") and the older one (`region` +
 * `keys`).
 */
class XteaManager(path: String) {
    private val xteaKeys: MutableMap<Int, IntArray> = HashMap()

    /** Whether an xteas.json was found and read. */
    val present: Boolean

    init {
        val xteaFile = File(path, "xteas.json")
        present = xteaFile.isFile
        if (!present && !holds241Cache(File(path))) {
            throw java.io.FileNotFoundException("${xteaFile.path} is missing: a rev 211 map folder needs its map keys (xteas.json)")
        }
        if (present) {
            // OpenRS2's files start with a byte-order mark
            val contents = xteaFile.bufferedReader().use { it.readText() }.removePrefix("\uFEFF")
            val keys = Gson().fromJson(contents, Array<XteaKey>::class.java) ?: emptyArray()
            keys.forEach {
                val key = it.key ?: it.keys ?: return@forEach
                val square = when {
                    it.mapsquare > 0 -> it.mapsquare
                    it.region > 0 -> it.region
                    else -> squareOfName(it.name) ?: return@forEach
                }
                xteaKeys[square] = key
            }
        }
    }

    fun getKeys(region: Int): IntArray? {
        return xteaKeys[region]
    }

    val size: Int get() = xteaKeys.size

    companion object {
        /** True if [dir], or its `cache` / `cache-out` folder, holds a build 241 cache (maps without XTEA). */
        fun holds241Cache(dir: File): Boolean = listOf(dir, File(dir, "cache"), File(dir, "cache-out"))
            .any { CacheIO.revisionOf(it) == CacheIO.REV_241 }
    }

    private fun squareOfName(name: String?): Int? {
        val m = name?.let { Regex("^[lm](\\d+)_(\\d+)$").find(it) } ?: return null
        return (m.groupValues[1].toInt() shl 8) or m.groupValues[2].toInt()
    }

    data class XteaKey(
        val region: Int,
        val keys: IntArray?,
        val mapsquare: Int,
        val key: IntArray?,
        val name: String? = null
    )
}
