package cache

import com.displee.cache.CacheLibrary
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import utils.ItemFull
import utils.KitCodec
import utils.NpcDefs
import utils.ObjDefs
import utils.SeqDef
import utils.SpotCodec
import utils.TextureCodec
import java.io.File

/**
 * Every config type the editor writes, decoded and encoded again in the cache's own format, must give the same
 * bytes, for every entry of every cache given with -Pgadscape.test.caches (skipped without it). An entry the codec
 * can't read counts as a failure too. Each (cache, type) pair is its own test, so one codec's problems don't hide
 * another's.
 */
class ConfigRoundTripTest {

    /** decode -> encode of one entry, null when the codec can't read it. */
    private class Codec(val name: String, val archive: Int, val group: Int?, val roundTrip: (id: Int, b: ByteArray, rev: Int) -> ByteArray?)

    private val codecs = listOf(
        Codec("items", CacheIO.CONFIGS, 10) { id, b, rev -> ItemFull.decodeAs(id, b, rev).takeIf { it.readOk }?.encode(rev) },
        Codec("npcs", CacheIO.CONFIGS, 9) { id, b, rev -> NpcDefs.decode(id, b, rev).takeIf { it.readOk }?.let { it.encode(it.options) } },
        Codec("objects", CacheIO.CONFIGS, 6) { id, b, rev -> ObjDefs.decode(id, b, rev).takeIf { it.readOk }?.let { it.encode(it.name, it.options) } },
        Codec("sequences", CacheIO.CONFIGS, 12) { id, b, rev -> SeqDef.decode(id, b, rev).takeIf { it.readOk }?.let { it.encode(it.frameIds, it.delays) } },
        Codec("identity kits", CacheIO.CONFIGS, 3) { id, b, rev -> KitCodec.decode(id, b, rev).takeIf { it.readOk }?.let { KitCodec.encode(it, rev) } },
        Codec("spot animations", CacheIO.CONFIGS, 13) { id, b, rev -> SpotCodec.decode(id, b, rev).takeIf { it.readOk }?.let { SpotCodec.encode(it, rev) } },
        Codec("textures", CacheIO.TEXTURES, 0) { id, b, rev -> TextureCodec.decode(id, b, rev).let { TextureCodec.encode(it, rev) } },
    )

    class Tally(val what: String) {
        var total = 0; var same = 0
        val different = ArrayList<Int>(); val unreadable = ArrayList<Int>(); val crashed = LinkedHashMap<Int, String>()
        val ok get() = different.isEmpty() && unreadable.isEmpty() && crashed.isEmpty()
        override fun toString() = "$what: $same/$total byte-identical" +
                (if (different.isNotEmpty()) ", ${different.size} different (${different.take(15).joinToString(", ")})" else "") +
                (if (unreadable.isNotEmpty()) ", ${unreadable.size} unreadable (${unreadable.take(15).joinToString(", ")})" else "") +
                (if (crashed.isNotEmpty()) ", ${crashed.size} threw (${crashed.entries.take(5).joinToString("; ") { "${it.key}: ${it.value}" }})" else "")
    }

    private fun run(lib: CacheLibrary, c: Codec): Tally {
        val rev = CacheIO.revision(lib)
        val t = Tally("${c.name} (${CacheIO.describe(lib)})")
        val groups = if (c.group != null) listOf(c.group) else lib.index(c.archive).archiveIds().toList()
        for (g in groups) {
            val archive = lib.index(c.archive).archive(g) ?: continue
            for (f in archive.fileIds()) {
                val b = lib.data(c.archive, g, f) ?: continue
                val id = if (c.group != null) f else g
                t.total++
                try {
                    val again = c.roundTrip(id, b, rev)
                    when {
                        again == null -> t.unreadable.add(id)
                        again.contentEquals(b) -> t.same++
                        else -> t.different.add(id)
                    }
                } catch (e: Throwable) { t.crashed[id] = e.toString() }
            }
            lib.index(c.archive).unCache()
        }
        return t
    }

    @TestFactory
    fun roundTrips(): List<DynamicNode> {
        val caches = ReferenceCachesTest.caches()
        if (caches.isEmpty()) return listOf(DynamicTest.dynamicTest("no caches given (-Pgadscape.test.caches)") { assumeTrue(false) })
        return caches.map { dir ->
            DynamicContainer.dynamicContainer(dir.path, codecs.map { c ->
                DynamicTest.dynamicTest(c.name) {
                    assumeTrue(File(dir, "main_file_cache.dat2").isFile, "no cache in $dir")
                    val lib = CacheIO.openReadOnly(dir.path)
                    try {
                        val t = run(lib, c)
                        println("${dir.path}: $t")
                        assertTrue(t.ok, t.toString())
                    } finally { lib.close() }
                }
            })
        }
    }
}
