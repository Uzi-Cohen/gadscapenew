package cache

import com.displee.cache.CacheLibrary
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * The one place the editor opens caches and asks what format they are in.
 *
 * The cache library is the GadScape fork of Displee's rs-cache-library 6.4 (editor/rs-cache-library), which reads and
 * writes both GadScape's own rev 211 caches and build 241 caches (reference-table flags 0x4/0x8, recomputed sizes,
 * protocol 7 tables for ids above 65535). Code that needs to know which config/map/model format to read or write asks
 * [revision] / [is241]; the rules are the server's (mgi.tools.jagcached.CacheFormat.detect):
 *  - the system property [REVISION_PROPERTY] (211 or 241) wins,
 *  - a cache with archive 24 (gamevals, added after 211) is 241,
 *  - a cache with archive 22 (skeletal keyframes) and sized reference tables (flag 0x4 on the config archive) is 241,
 *  - anything else is 211.
 */
object CacheIO {
    const val REV_211 = 211
    const val REV_241 = 241

    /** Forces the detected revision (211 or 241), like the server's -Dgadscape.cache.revision. */
    const val REVISION_PROPERTY = "gadscape.cache.revision"

    // archive (index) ids
    const val FRAMES = 0
    const val FRAMEMAPS = 1
    const val CONFIGS = 2
    const val INTERFACES = 3
    const val MAPS = 5
    const val MODELS = 7
    const val SPRITES = 8
    const val TEXTURES = 9
    const val BINARY = 10
    const val CLIENTSCRIPTS = 12
    /** Skeletal animation keyframes ("animayas"): archive 22 from build 241 on (archive 0 in 211 caches). */
    const val KEYFRAMES = 22
    /** Jagex's game value names; only in post-211 caches, which is how a 241 cache is recognised. */
    const val GAMEVALS = 24

    private val revisions: MutableMap<CacheLibrary, Int> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Opens the cache in [path] (a folder with main_file_cache.dat2 + idx files, or a 317 cache). Throws if an
     * archive the cache has can't be read, instead of opening it with that archive silently missing.
     */
    fun open(path: String): CacheLibrary = open(path, false)

    /** Opens a cache that must not change (another server's cache, a reference cache): every write fails. */
    fun openReadOnly(path: String): CacheLibrary = open(path, true)

    private fun open(path: String, readOnly: Boolean): CacheLibrary {
        val lib = CacheLibrary(path, readOnly = readOnly)
        val bad = unreadableArchives(lib)
        if (bad.isNotEmpty()) {
            lib.close()
            throw IllegalStateException("Can't read archive(s) ${bad.joinToString(", ")} of the cache in $path (is it a cache this editor supports?)")
        }
        return lib
    }

    fun open(dir: File): CacheLibrary = open(dir.path)

    /** Opens [path], runs [block], closes it again. */
    fun <T> use(path: String, block: (CacheLibrary) -> T): T {
        val lib = open(path)
        try { return block(lib) } finally { lib.close() }
    }

    /** 241 or 211 (see the class comment). A 317 cache counts as 211 (it has its own code paths). Cached per cache. */
    fun revision(lib: CacheLibrary): Int {
        forcedRevision()?.let { return it }
        revisions[lib]?.let { return it }
        val rev = detect(lib)
        revisions[lib] = rev
        return rev
    }

    fun is241(lib: CacheLibrary): Boolean = revision(lib) >= REV_241

    /** The revision of the cache in [dir], or null if there is no readable cache there. */
    fun revisionOf(dir: File): Int? = try {
        if (!File(dir, "main_file_cache.dat2").isFile && !File(dir, "main_file_cache.dat").isFile) null
        else { val lib = CacheLibrary(dir.path, readOnly = true); try { revision(lib) } finally { lib.close() } }
    } catch (e: Exception) { null }

    private fun forcedRevision(): Int? {
        val forced = System.getProperty(REVISION_PROPERTY)?.trim()
        if (forced.isNullOrEmpty()) return null
        val rev = forced.toIntOrNull()
        require(rev == REV_211 || rev == REV_241) { "$REVISION_PROPERTY must be 211 or 241, was $forced" }
        return rev
    }

    private fun detect(lib: CacheLibrary): Int {
        if (lib.is317()) return REV_211
        if (hasTable(lib, GAMEVALS)) return REV_241
        if (hasTable(lib, KEYFRAMES) && lib.exists(CONFIGS) && lib.index(CONFIGS).hasFlag4()) return REV_241
        return REV_211
    }

    /** True if [lib] has archive [id] and its reference table was read. */
    fun hasTable(lib: CacheLibrary, id: Int): Boolean = lib.exists(id) && lib.index(id).version != 0

    /**
     * Archives that are in the cache (an idx file and a reference table in idx255) but couldn't be read. The library
     * only prints such failures and carries on: without the archive when its table doesn't decode, or with the archive
     * loaded empty (protocol 0, no groups) when the table's sectors can't be read at all (idx255 entry past the end of
     * main_file_cache.dat2, broken sector chain: Index.readArchiveSector returns null). Saving into such an empty
     * archive would write a table with only the new groups and lose every other group, so both count as unreadable.
     */
    fun unreadableArchives(lib: CacheLibrary): List<Int> {
        if (lib.is317()) return emptyList()
        val master = lib.index255 ?: return emptyList()
        val count = (master.raf.length() / 6).toInt()
        val bad = ArrayList<Int>()
        for (i in 0 until count) {
            if (lib.exists(i)) {
                // loaded, but without a table although idx255 says there is one
                if (lib.index(i).version == 0 && masterEntryLength(master.raf, i) > 0) bad.add(i)
                continue
            }
            if (!File(lib.path, "main_file_cache.idx$i").isFile) continue
            val sector = try { master.readArchiveSector(i) } catch (e: Exception) { null }
            if (sector != null && sector.data.isNotEmpty()) bad.add(i)
        }
        return bad
    }

    /**
     * The length field (first 3 bytes) of archive [id]'s 6-byte entry in idx255, read straight from the file (not
     * through readArchiveSector, which gives up on the same entries this is meant to find). 0 when there is none.
     */
    private fun masterEntryLength(raf: java.io.RandomAccessFile, id: Int): Int = synchronized(raf) {
        if (raf.length() < 6L * id + 6) return 0
        val b = ByteArray(3)
        raf.seek(6L * id)
        raf.readFully(b)
        ((b[0].toInt() and 255) shl 16) or ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)
    }

    /** Where skeletal keyframe sets live: archive 22 in a 241 cache, archive 0 (with the frame sets) in a 211 cache. */
    fun keyframeArchive(lib: CacheLibrary): Int = if (is241(lib)) KEYFRAMES else FRAMES

    /**
     * The highest model id the game can use, in both formats. Rev 211 configs store model ids in 2 bytes. Build 241
     * configs store 4 bytes, but the 241 client requests JS5 groups with a 16-bit group id (rsprot's JS5 decoder reads
     * g1 archive + g2 group and the server refuses groups above 65535): model 100000 would be asked for as archive 8
     * group 34464, so it is never downloaded.
     */
    const val MAX_MODEL_ID = 65535

    /**
     * The highest id the editor hands out for models and objects ([IdKind.MODEL], [IdKind.LOC]): 65535 is the "none"
     * value of 16-bit model and object fields (e.g. the object transform lists, IF model ids).
     */
    const val MAX_NEW_U16_ID = 65534

    /** How many bytes items, NPCs and objects use for a model id: 4 in build 241, 2 in rev 211. */
    fun modelIdBytes(lib: CacheLibrary): Int = if (is241(lib)) 4 else 2

    /** The highest model id configs can point at and the game can load: [MAX_MODEL_ID] in both formats. */
    @Suppress("UNUSED_PARAMETER")
    fun maxModelId(lib: CacheLibrary): Int = MAX_MODEL_ID

    /** "build 241" / "rev 211", for messages. */
    fun describe(lib: CacheLibrary): String = if (lib.is317()) "317" else if (is241(lib)) "build 241" else "rev 211"
}
