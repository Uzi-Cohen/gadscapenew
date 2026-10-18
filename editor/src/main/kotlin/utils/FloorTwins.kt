package utils

import cache.ConfigType
import cache.IndexType
import com.displee.cache.CacheLibrary

/**
 * Twin floors for View > 117 HD floors: the same overlay/underlay under another number that 117 HD has no rule for,
 * so the floor shows in the game exactly as painted. Uses an identical floor the cache already has (overlay 34 is
 * overlay 10's) or adds a copy to the exported cache, where the map is saved from and deployed.
 */
object FloorTwins {
    private val twins = HashMap<Pair<Boolean, Int>, Int>()

    /** The twin's stored number (file id + 1) for [stored], or null if none could be made. */
    @Synchronized
    fun twinFor(overlay: Boolean, stored: Int, add: (fileId: Int, bytes: ByteArray) -> Unit): Int? {
        twins[overlay to stored]?.let { return it }
        val group = if (overlay) ConfigType.OVERLAY.id else ConfigType.UNDERLAY.id
        val lib = CacheLibrary(GadTools.ensureExportCache().path)
        try {
            val archive = lib.index(IndexType.CONFIGS.id).archive(group) ?: return null
            val ids = archive.fileIds()
            val mine = lib.data(IndexType.CONFIGS.id, group, stored - 1)?.trimZeros() ?: return null
            // an identical floor HD doesn't know by number
            for (f in ids) {
                if (f == stored - 1 || HdFloors.hasRules(overlay, f + 1)) continue
                val other = lib.data(IndexType.CONFIGS.id, group, f)?.trimZeros() ?: continue
                if (other.contentEquals(mine)) return (f + 1).also { twins[overlay to stored] = it }
            }
            // none: add a copy after the last one
            var f = (ids.maxOrNull() ?: 0) + 1
            while (HdFloors.hasRules(overlay, f + 1)) f++
            lib.put(IndexType.CONFIGS.id, group, f, mine)
            lib.index(IndexType.CONFIGS.id).update()
            add(f, mine)
            return (f + 1).also { twins[overlay to stored] = it }
        } catch (e: Exception) {
            println("Twin floor for ${if (overlay) "overlay" else "underlay"} $stored failed: ${e.message}")
            return null
        } finally {
            lib.close()
        }
    }

    private fun ByteArray.trimZeros(): ByteArray {
        var n = size
        while (n > 1 && this[n - 1] == 0.toByte() && this[n - 2] == 0.toByte()) n--
        return copyOf(n)
    }
}
