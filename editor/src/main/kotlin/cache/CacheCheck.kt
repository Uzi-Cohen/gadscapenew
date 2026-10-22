package cache

import com.displee.cache.CacheLibrary
import com.displee.compress.CompressionType
import com.displee.compress.decompress
import com.displee.util.generateCrc
import java.io.File
import java.util.zip.CRC32

/**
 * Checks that the cache library reads and writes a cache's container layer exactly:
 *  - [tables]: every archive's reference table, decoded and encoded again, gives the same bytes as in the cache
 *    (protocol, flags 0x1/0x2/0x4/0x8, versions, file ids, names);
 *  - [groups]: for every group, the reference table's crc, compressed and uncompressed sizes (flag 0x4) and
 *    uncompressed crc (flag 0x8) match the group's data, the values the editor now writes for every group it saves.
 *
 * Run it on any cache:  java -cp uzis-map-editor.jar cache.CacheCheckKt <cache folder>...
 * (read-only: the cache is opened with every file read-only).
 */
object CacheCheck {

    class Result(val cache: String) {
        val lines = ArrayList<String>()
        val errors = ArrayList<String>()
        var tables = 0
        var tablesSame = 0
        /** Tables stored with zero bytes after their end (identical otherwise). */
        var padded = 0
        var groups = 0L
        var groupsOk = 0L
        val ok get() = errors.isEmpty()
        fun error(s: String) { if (errors.size < 200) errors.add(s) else if (errors.size == 200) errors.add("... more errors not listed") }
        override fun toString() = "$cache: $tablesSame/$tables reference tables byte-identical" +
                (if (padded > 0) " ($padded of them stored with zero padding after the end)" else "") +
                ", $groupsOk/$groups groups match their table" +
                (if (errors.isEmpty()) "" else ", ${errors.size} error(s):\n  " + errors.joinToString("\n  "))
    }

    /** The decompressed reference table of archive [id] exactly as stored in idx255. */
    fun storedTable(lib: CacheLibrary, id: Int): ByteArray? {
        val sector = lib.index255?.readArchiveSector(id) ?: return null
        return sector.decompress()
    }

    /** Re-encodes every archive's reference table and compares it with the stored bytes. */
    fun tables(lib: CacheLibrary, r: Result = Result(lib.path)): Result {
        for (index in lib.indices()) {
            if (index.id == 255) continue
            val stored = storedTable(lib, index.id) ?: continue
            r.tables++
            val again = index.write()
            if (again.contentEquals(stored)) r.tablesSame++
            // GadScape's old server-side packer over-allocated tables: the same table followed by zero bytes
            else if (stored.size > again.size && again.contentEquals(stored.copyOf(again.size)) &&
                    (again.size until stored.size).all { stored[it].toInt() == 0 }) { r.tablesSame++; r.padded++ }
            else r.error("archive ${index.id}: reference table re-encodes to ${again.size} bytes, stored ${stored.size}" +
                    firstDifference(stored, again))
        }
        return r
    }

    /** Checks every group of every archive (or of [only]) against its reference-table entry. */
    fun groups(lib: CacheLibrary, r: Result = Result(lib.path), only: Collection<Int>? = null): Result {
        for (index in lib.indices()) {
            if (index.id == 255 || (only != null && index.id !in only)) continue
            val sizes = index.hasFlag4(); val ucrc = index.hasFlag8()
            for (archive in index.archives()) {
                r.groups++
                val sector = index.readArchiveSector(archive.id)
                if (sector == null) { r.error("archive ${index.id} group ${archive.id}: in the table but has no data"); continue }
                val data = sector.data
                val type = data[0].toInt() and 255
                val compressed = ((data[1].toInt() and 255) shl 24) or ((data[2].toInt() and 255) shl 16) or
                        ((data[3].toInt() and 255) shl 8) or (data[4].toInt() and 255)
                val container = compressed + if (type == CompressionType.NONE.ordinal) 5 else 9
                if (container > data.size || (data.size != container && data.size != container + 2)) {
                    r.error("archive ${index.id} group ${archive.id}: bad container (${data.size} bytes, header says $container)"); continue
                }
                var good = true
                if (data.generateCrc(length = container) != archive.crc) { good = false; r.error("archive ${index.id} group ${archive.id}: crc mismatch") }
                if (sizes && archive.flag4Value1 != container) {
                    good = false; r.error("archive ${index.id} group ${archive.id}: compressed size ${archive.flag4Value1} in the table, container is $container")
                }
                if (sizes || ucrc) {
                    val raw = try { sector.decompress() } catch (e: Exception) { null }
                    // 211 map loc groups are XTEA-encrypted: nothing to compare without the keys
                    if (raw == null || raw.isEmpty() && compressed > 0) {
                        if (index.id != CacheIO.MAPS) { good = false; r.error("archive ${index.id} group ${archive.id}: can't decompress") }
                    } else {
                        if (sizes && archive.flag4Value2 != raw.size) {
                            good = false; r.error("archive ${index.id} group ${archive.id}: uncompressed size ${archive.flag4Value2} in the table, data is ${raw.size}")
                        }
                        if (ucrc) {
                            val c = CRC32().apply { update(raw) }.value.toInt()
                            if (c != archive.flag8Value) { good = false; r.error("archive ${index.id} group ${archive.id}: uncompressed crc mismatch") }
                        }
                    }
                }
                if (good) r.groupsOk++
            }
        }
        return r
    }

    /** Both checks, on a cache opened read-only. */
    fun all(dir: String): Result {
        val lib = CacheIO.openReadOnly(dir)
        try {
            val r = Result(dir)
            r.lines.add("${CacheIO.describe(lib)} cache, ${lib.indices().size} archives")
            tables(lib, r)
            groups(lib, r)
            // reading every group must not change any table (versions read from the group trailers included)
            val again = Result(dir)
            tables(lib, again)
            if (again.tablesSame != again.tables) again.errors.forEach { r.error("after reading the groups: $it") }
            return r
        } finally { lib.close() }
    }

    /**
     * Writes into a copy of the cache in [src] (made in [work], which is emptied first) what the editor writes when it
     * adds content: a model, a frame set, a keyframe set (241), a skeleton, an animation, an object and a sprite, each
     * a copy of an existing one at the id [IdAllocator] hands out. Then reopens the copy and checks that each entry
     * reads back the same, that the ids are in GadScape's ranges ([IdKind]), and runs [tables] and [groups] over the
     * whole copy (so the recomputed sizes / crcs of flags 0x4 and 0x8 are checked against the data written).
     */
    fun writeRoundTrip(src: String, work: File): Result {
        val r = Result("$src (written copy in ${work.path})")
        work.deleteRecursively(); work.mkdirs()
        File(src).listFiles()!!.filter { it.isFile && it.name.startsWith("main_file_cache") }.forEach { it.copyTo(File(work, it.name)) }
        val written = LinkedHashMap<String, Triple<Int, Int, Map<Int, ByteArray>>>() // what -> (archive, group, files)
        val lib = CacheIO.open(work.path)
        val rev: Int
        try {
            rev = CacheIO.revision(lib)
            r.lines.add("${CacheIO.describe(lib)} cache copied to ${work.path}")
            fun copyGroup(what: String, archive: Int, kind: IdKind) {
                if (!lib.exists(archive)) return
                val from = lib.index(archive).archiveIds().firstOrNull { g -> lib.index(archive).archive(g)?.fileIds()?.isNotEmpty() == true } ?: return
                val files = lib.index(archive).archive(from)!!.fileIds().associateWith { f -> lib.data(archive, from, f)!! }
                val to = IdAllocator(lib, kind).next()
                checkNewId(r, lib, what, kind, to)
                for ((f, b) in files) lib.put(archive, to, f, b)
                written[what] = Triple(archive, to, files)
            }
            fun copyConfig(what: String, group: Int, kind: IdKind) {
                val from = lib.index(CacheIO.CONFIGS).archive(group)?.fileIds()?.firstOrNull() ?: return
                val b = lib.data(CacheIO.CONFIGS, group, from)!!
                val to = IdAllocator(lib, kind).next()
                checkNewId(r, lib, what, kind, to)
                lib.put(CacheIO.CONFIGS, group, to, b)
                written[what] = Triple(CacheIO.CONFIGS, group, mapOf(to to b))
            }
            copyGroup("model", CacheIO.MODELS, IdKind.MODEL)
            copyGroup("frame set", CacheIO.FRAMES, IdKind.FRAME_SET)
            if (CacheIO.is241(lib)) copyGroup("keyframe set", CacheIO.KEYFRAMES, IdKind.KEYFRAME_SET)
            copyGroup("skeleton", CacheIO.FRAMEMAPS, IdKind.FRAMEMAP)
            copyGroup("sprite", CacheIO.SPRITES, IdKind.SPRITE)
            copyConfig("animation", 12, IdKind.SEQ)
            copyConfig("object", 6, IdKind.LOC)
            for (i in written.values.map { it.first }.distinct()) lib.index(i).update()
            r.lines.add("wrote " + written.entries.joinToString(", ") { (k, v) ->
                if (v.first == CacheIO.CONFIGS) "$k ${v.third.keys.first()}" else "$k ${v.second}" })
        } finally { lib.close() }
        val again = CacheIO.openReadOnly(work.path)
        try {
            if (CacheIO.revision(again) != rev) r.error("the copy reads as ${CacheIO.describe(again)} after writing")
            for ((what, v) in written) {
                val (archive, group, files) = v
                for ((f, b) in files) {
                    val back = again.data(archive, group, f)
                    if (back == null || !back.contentEquals(b)) r.error("$what ${if (archive == CacheIO.CONFIGS) f else group}: doesn't read back the same")
                }
            }
            tables(again, r)
            groups(again, r)
        } finally { again.close() }
        return r
    }

    /**
     * Checks that [to], the id [IdAllocator] handed out for a new [kind] in [lib], is where [IdKind] says: in the
     * range of [lib]'s format, the highest free id of it for kinds handed out from the top down (models and objects:
     * in a 241 cache the lowest would be right after the game's own), and never above 65534 for models and objects
     * (the 241 client fetches models with 16-bit group ids, the game protocol carries object ids in 16 bits).
     */
    private fun checkNewId(r: Result, lib: CacheLibrary, what: String, kind: IdKind, to: Int) {
        val range = kind.rangeFor(lib)
        if (to !in range) r.error("$what: new id $to is outside the range ${range.first}-${range.last}")
        if (kind.usesReserved(lib) && !kind.isReserved(to)) r.error("$what: new id $to is outside GadScape's range ${kind.describeRange()}")
        if ((kind == IdKind.MODEL || kind == IdKind.LOC) && to > CacheIO.MAX_NEW_U16_ID) r.error("$what: new id $to is above ${CacheIO.MAX_NEW_U16_ID}")
        if (kind.usesReserved(lib) && kind.topDown) {
            val used = kind.usedIds(lib).toHashSet()
            val freeAbove = (to + 1..range.last).firstOrNull { it !in used }
            if (freeAbove != null) r.error("$what: new id $to, but $freeAbove above it is free (new ${kind.label}s are handed out from the top down)")
        }
    }

    /** For each [IdKind]: how many ids the cache has, its highest, where new ones come from (and how many of those are taken), the next new id. */
    fun idReport(lib: CacheLibrary): List<String> = IdKind.values().map { k ->
        val used = k.usedIds(lib)
        val range = k.rangeFor(lib)
        val next = try { IdAllocator(lib, k).next().toString() } catch (e: Exception) { "none (${e.message})" }
        val from = if (k.usesReserved(lib)) "GadScape's range ${k.describeRange(range)}" else "the first run of 50 free ids in ${range.first}-${range.last}"
        "%-13s %6d ids, highest %6d; new ids from %s (%d taken), next new id %s".format(k.label, used.size,
            used.maxOrNull() ?: -1, from, used.count { it in range }, next)
    }

    private fun firstDifference(a: ByteArray, b: ByteArray): String {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return " (first difference at byte $i)"
        return " (one is a prefix of the other)"
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("usage: CacheCheck <cache folder>...                 (read-only checks)")
        println("       CacheCheck --write <scratch folder> <cache folder>...   (also writes into copies made in the scratch folder)")
        println("       CacheCheck --ids <cache folder>...                     (ids in use and the next new id of each kind)")
        return
    }
    if (args[0] == "--ids") {
        for (a in args.drop(1)) {
            val lib = CacheIO.openReadOnly(a)
            try { println("$a (${CacheIO.describe(lib)}):"); CacheCheck.idReport(lib).forEach { println("  $it") } } finally { lib.close() }
        }
        return
    }
    var bad = 0
    val write = args[0] == "--write"
    val scratch = if (write) File(args[1]) else null
    for (a in if (write) args.drop(2) else args.toList()) {
        if (!File(a, "main_file_cache.dat2").isFile) { println("$a: no main_file_cache.dat2"); bad++; continue }
        val t = System.currentTimeMillis()
        val r = CacheCheck.all(a)
        r.lines.forEach { println("$a: $it") }
        println("$r  (${(System.currentTimeMillis() - t) / 1000.0} s)")
        if (!r.ok) bad++
        if (scratch != null) {
            val t2 = System.currentTimeMillis()
            val w = CacheCheck.writeRoundTrip(a, File(scratch, File(a).absoluteFile.parentFile.name + "-" + File(a).name))
            w.lines.forEach { println("$a: $it") }
            println("$w  (${(System.currentTimeMillis() - t2) / 1000.0} s)")
            if (!w.ok) bad++
        }
    }
    if (bad > 0) System.exit(1)
}
