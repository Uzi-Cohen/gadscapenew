package cache

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reads map files out of an old-style cache (main_file_cache.dat + idx0..idx4), the format most
 * 317-based private-server clients use (Roat Pkz and others), even when they carry OSRS maps.
 *  - idx0 file 5 is the "versionlist" archive with "map_index": region -> terrain file, object file
 *  - idx4 holds the map files themselves (gzipped)
 */
class Cache317Maps(dir: File, private val looksLikeTerrain: ((Int, ByteArray) -> Boolean)? = null) : AutoCloseable {
    private val dat = RandomAccessFile(File(dir, "main_file_cache.dat"), "r")
    private val idx = HashMap<Int, RandomAccessFile>()
    /** region id -> (terrain file, objects file) */
    val mapIndex = HashMap<Int, Pair<Int, Int>>()
    var info = ""
    /** Index the map files are in (4 normally; some clients use another one). */
    private var mapsIdx = 4

    init {
        for (i in 0..15) File(dir, "main_file_cache.idx$i").takeIf { it.isFile }?.let { idx[i] = RandomAccessFile(it, "r") }
        // maps are optional here (the interface viewer only needs archives)
        try { findMaps() } catch (e: Exception) { info = "maps: ${e.message}" }
    }

    /**
     * Find where this client keeps its map list. Tries, in order: "map_index" in the versionlist;
     * any archive entry in idx0 shaped like a map list; map files stored straight by region number.
     * With a terrain check, each guess is only accepted when its files really read as ground.
     */
    private fun findMaps() {
        val tried = ArrayList<String>()
        fun accept(label: String): Boolean {
            if (mapIndex.isEmpty()) return false
            val check = looksLikeTerrain ?: run { info = label; return true }
            // test only regions whose files are actually downloaded
            val sample = mapIndex.keys.filter { val f = mapIndex[it]!!.first; f >= 0 && fileSize(mapsIdx, f) > 0 }.sorted().let { ks -> (listOf(12342, 12598, 12850, 12853, 13878).filter { it in mapIndex } + ks.filterIndexed { i, _ -> i % maxOf(1, ks.size / 25) == 0 }).distinct().take(30) }
            var ok = 0
            for (r in sample) { val t = try { terrain(r) } catch (e: Exception) { null }; if (t != null && check(r, t)) ok++ }
            tried.add("$label: $ok/${sample.size} read")
            if (ok >= maxOf(1, sample.size / 3)) {
                // keep only regions whose ground file is really there (their client downloads maps as you walk)
                val listed = mapIndex.size
                mapIndex.entries.removeIf { (_, f) -> f.first < 0 || fileSize(mapsIdx, f.first) <= 0 }
                info = "$label: ${mapIndex.size} regions downloaded of $listed listed, test $ok/${sample.size}"
                return true
            }
            mapIndex.clear(); return false
        }
        // 1. the standard place
        val versionList = readFile(0, 5)
        if (versionList != null) {
            archiveEntry(versionList, "map_index")?.let { try { parseMapIndex(it); if (accept("map_index")) return } catch (e: Exception) {} }
        }
        // 2. any entry of any idx0 archive that is shaped like a map list
        for (a in 1..10) {
            val raw = readFile(0, a) ?: continue
            val entries = try { archiveEntries(raw) } catch (e: Exception) { continue }
            for ((i, raw) in entries.withIndex()) {
                // some clients gzip their map list inside the archive (Roat Pkz does)
                val b = try { gunzipIfNeeded(raw) } catch (e: Exception) { raw }
                try { mapIndex.clear(); parseMapIndex(b) } catch (e: Exception) { mapIndex.clear(); continue }
                if (mapIndex.size in 50..20000 && accept("archive $a entry $i")) return
            }
        }
        // 3. map files stored straight by region number, in idx4 or a custom index
        for (ix in listOf(4, 5, 6, 7, 8)) {
            val count = idx[ix]?.length()?.div(6)?.toInt() ?: continue
            val schemes = listOf<Pair<String, (Int) -> Pair<Int, Int>>>(
                "idx$ix region*2" to { r -> Pair(r * 2, r * 2 + 1) },
                "idx$ix region*2 (objects first)" to { r -> Pair(r * 2 + 1, r * 2) },
                "idx$ix region (ground) + region+32768 (objects)" to { r -> Pair(r, r + 32768) }
            )
            for ((label, f) in schemes) {
                mapIndex.clear()
                mapsIdx = ix
                for (rx in 0 until 120) for (ry in 0 until 200) {
                    val r = (rx shl 8) or ry
                    val (t, o) = f(r)
                    if (t < count && fileSize(ix, t) > 0) mapIndex[r] = Pair(t, if (o < count) o else -1)
                }
                if (accept(label)) return
            }
        }
        mapsIdx = 4
        mapIndex.clear()
        throw IllegalStateException("couldn't find this client's map list. Tried: " + tried.joinToString("; ").ifBlank { "nothing matched" })
    }

    private fun fileSize(index: Int, file: Int): Int {
        val ix = idx[index] ?: return 0
        if ((file + 1) * 6L > ix.length()) return 0
        val e = ByteArray(6)
        synchronized(ix) { ix.seek(file * 6L); ix.readFully(e) }
        return ((e[0].toInt() and 255) shl 16) or ((e[1].toInt() and 255) shl 8) or (e[2].toInt() and 255)
    }

    /** Every file inside a Jagex archive, in order. */
    fun archiveEntries(raw: ByteArray): List<ByteArray> {
        var data = raw
        val decomp = u24(data, 0); val comp = u24(data, 3)
        var whole = false
        if (decomp != comp) { data = bunzip(data, 6, comp, decomp); whole = true } else data = data.copyOfRange(6, data.size)
        val count = u16(data, 0)
        if (count <= 0 || count > 4000) return emptyList()
        var p = 2
        var off = 2 + count * 10
        val out = ArrayList<ByteArray>()
        for (i in 0 until count) {
            val dsz = u24(data, p + 4); val csz = u24(data, p + 7)
            p += 10
            out.add(if (whole) data.copyOfRange(off, off + dsz) else bunzip(data, off, csz, dsz))
            off += csz
        }
        return out
    }

    fun readFile(index: Int, file: Int): ByteArray? {
        val ix = idx[index] ?: return null
        if ((file + 1) * 6L > ix.length()) return null
        val e = ByteArray(6)
        synchronized(ix) { ix.seek(file * 6L); ix.readFully(e) }
        val size = ((e[0].toInt() and 255) shl 16) or ((e[1].toInt() and 255) shl 8) or (e[2].toInt() and 255)
        var sector = ((e[3].toInt() and 255) shl 16) or ((e[4].toInt() and 255) shl 8) or (e[5].toInt() and 255)
        if (size <= 0 || sector <= 0) return null
        val out = ByteArray(size)
        var read = 0
        val buf = ByteArray(520)
        synchronized(dat) {
            while (read < size) {
                if (sector == 0 || sector * 520L >= dat.length()) return null
                dat.seek(sector * 520L)
                val chunk = minOf(512, size - read)
                dat.readFully(buf, 0, 8 + chunk)
                sector = ((buf[4].toInt() and 255) shl 16) or ((buf[5].toInt() and 255) shl 8) or (buf[6].toInt() and 255)
                System.arraycopy(buf, 8, out, read, chunk)
                read += chunk
            }
        }
        return out
    }

    private fun bunzip(data: ByteArray, off: Int, len: Int, outLen: Int): ByteArray {
        // Jagex strips the "BZh1" header
        val withHeader = ByteArray(len + 4)
        withHeader[0] = 'B'.toByte(); withHeader[1] = 'Z'.toByte(); withHeader[2] = 'h'.toByte(); withHeader[3] = '1'.toByte()
        System.arraycopy(data, off, withHeader, 4, len)
        val input = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(ByteArrayInputStream(withHeader))
        val out = ByteArray(outLen)
        var n = 0
        while (n < outLen) { val r = input.read(out, n, outLen - n); if (r < 0) break; n += r }
        return out
    }

    private fun u24(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 16) or ((b[p + 1].toInt() and 255) shl 8) or (b[p + 2].toInt() and 255)
    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)

    private fun nameHash(n: String): Int { var h = 0; for (c in n.toUpperCase()) h = h * 61 + c.toInt() - 32; return h }

    /** A file inside a Jagex archive (the old "jag" format). */
    fun archiveEntry(raw: ByteArray, name: String): ByteArray? {
        var data = raw
        val decomp = u24(data, 0); val comp = u24(data, 3)
        var whole = false
        if (decomp != comp) { data = bunzip(data, 6, comp, decomp); whole = true } else data = data.copyOfRange(6, data.size)
        val count = u16(data, 0)
        var p = 2
        var off = 2 + count * 10
        val want = nameHash(name)
        for (i in 0 until count) {
            val h = ((data[p].toInt() and 255) shl 24) or ((data[p + 1].toInt() and 255) shl 16) or ((data[p + 2].toInt() and 255) shl 8) or (data[p + 3].toInt() and 255)
            val dsz = u24(data, p + 4); val csz = u24(data, p + 7)
            p += 10
            if (h == want) return if (whole) data.copyOfRange(off, off + dsz) else bunzip(data, off, csz, dsz)
            off += csz
        }
        return null
    }

    /** map_index comes in a few layouts; pick the one that fits the file size. */
    private fun parseMapIndex(b: ByteArray) {
        fun read(start: Int, stride: Int, n: Int) {
            for (i in 0 until n) {
                val p = start + i * stride
                mapIndex[u16(b, p)] = Pair(u16(b, p + 2), u16(b, p + 4))
            }
        }
        val counted = if (b.size >= 2) u16(b, 0) else -1
        when {
            counted > 0 && b.size == 2 + counted * 6 -> { read(2, 6, counted); info = "map_index: $counted regions (count + 6-byte entries)" }
            counted > 0 && b.size == 2 + counted * 7 -> { read(2, 7, counted); info = "map_index: $counted regions (count + 7-byte entries)" }
            b.size % 7 == 0 -> { read(0, 7, b.size / 7); info = "map_index: ${b.size / 7} regions (7-byte entries)" }
            b.size % 6 == 0 -> { read(0, 6, b.size / 6); info = "map_index: ${b.size / 6} regions (6-byte entries)" }
            else -> throw IllegalStateException("map_index has an unknown layout (${b.size} bytes)")
        }
    }

    private fun gunzipIfNeeded(b: ByteArray): ByteArray =
        if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(b)).readBytes() else b

    fun terrain(regionId: Int): ByteArray? = mapIndex[regionId]?.first?.takeIf { it >= 0 }?.let { readFile(mapsIdx, it) }?.let { gunzipIfNeeded(it) }
    fun objects(regionId: Int): ByteArray? = mapIndex[regionId]?.second?.takeIf { it >= 0 }?.let { readFile(mapsIdx, it) }?.let { gunzipIfNeeded(it) }

    override fun close() {
        try { dat.close() } catch (e: Exception) {}
        idx.values.forEach { try { it.close() } catch (e: Exception) {} }
    }

    companion object {
        /** True for an old-style cache folder (.dat, no .dat2). */
        fun isOldStyle(dir: File) = File(dir, "main_file_cache.dat").isFile && !File(dir, "main_file_cache.dat2").isFile
    }
}
