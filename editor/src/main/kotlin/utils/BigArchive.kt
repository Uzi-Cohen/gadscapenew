package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reads an archive straight from the cache files. The cache library refuses archives that unpack to more than
 * 16 MB; some server cache packers pad every animation to 100 KB, which makes the animations archive 200 MB,
 * so the library sees all animations as empty while the game reads them fine.
 */
object BigArchive {
    private const val SECTOR = 520

    /** The files of [archive] in [index], by file id; null if it can't be read this way. */
    fun readFiles(lib: CacheLibrary, index: Int, archive: Int): Map<Int, ByteArray>? {
        val ids = try { lib.index(index).archive(archive)?.fileIds()?.sorted() } catch (e: Exception) { null } ?: return null
        val dir = File(lib.path)
        val packed = readRaw(dir, index, archive) ?: return null
        val data = unpack(packed) ?: return null
        return split(data, ids)
    }

    fun readRaw(dir: File, index: Int, archive: Int): ByteArray? {
        val idx = File(dir, "main_file_cache.idx$index"); val dat = File(dir, "main_file_cache.dat2")
        if (!idx.isFile || !dat.isFile) return null
        val e = ByteArray(6)
        RandomAccessFile(idx, "r").use { ri ->
            if ((archive + 1) * 6L > ri.length()) return null
            ri.seek(archive * 6L)
            ri.readFully(e)
        }
        val size = ((e[0].toInt() and 255) shl 16) or ((e[1].toInt() and 255) shl 8) or (e[2].toInt() and 255)
        var sector = ((e[3].toInt() and 255) shl 16) or ((e[4].toInt() and 255) shl 8) or (e[5].toInt() and 255)
        if (size <= 0 || sector <= 0) return null
        val big = archive > 0xFFFF
        val head = if (big) 10 else 8
        val out = ByteArray(size)
        var read = 0; var part = 0
        RandomAccessFile(dat, "r").use { rd ->
            val buf = ByteArray(SECTOR)
            while (read < size) {
                if (sector <= 0 || sector.toLong() * SECTOR >= rd.length()) return null
                rd.seek(sector.toLong() * SECTOR)
                val n = minOf(SECTOR - head, size - read)
                rd.readFully(buf, 0, head + n)
                var p = 0
                fun u8() = buf[p++].toInt() and 255
                val aid = if (big) (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8() else (u8() shl 8) or u8()
                val pt = (u8() shl 8) or u8()
                val next = (u8() shl 16) or (u8() shl 8) or u8()
                val ix = u8()
                if (aid != archive || pt != part || ix != index) return null
                System.arraycopy(buf, head, out, read, n)
                read += n; part++; sector = next
            }
        }
        return out
    }

    /** Takes off the compression header and unpacks (no compression or gzip). */
    fun unpack(b: ByteArray): ByteArray? {
        if (b.size < 5) return null
        val type = b[0].toInt() and 255
        val len = ((b[1].toInt() and 255) shl 24) or ((b[2].toInt() and 255) shl 16) or ((b[3].toInt() and 255) shl 8) or (b[4].toInt() and 255)
        return when (type) {
            0 -> b.copyOfRange(5, 5 + len)
            2 -> {
                val full = ((b[5].toInt() and 255) shl 24) or ((b[6].toInt() and 255) shl 16) or ((b[7].toInt() and 255) shl 8) or (b[8].toInt() and 255)
                val o = ByteArrayOutputStream(full.coerceIn(0, 1 shl 28))
                GZIPInputStream(b.inputStream(9, len)).use { it.copyTo(o) }
                o.toByteArray()
            }
            else -> null
        }
    }

    /** Splits an unpacked archive into its files (the chunked layout with the size table at the end). */
    fun split(d: ByteArray, ids: List<Int>): Map<Int, ByteArray>? {
        val n = ids.size
        if (n == 0) return emptyMap()
        if (n == 1) return mapOf(ids[0] to d)
        val chunks = d[d.size - 1].toInt() and 255
        var t = d.size - 1 - chunks * n * 4
        if (chunks <= 0 || t < 0) return null
        fun i32(p: Int) = ((d[p].toInt() and 255) shl 24) or ((d[p + 1].toInt() and 255) shl 16) or ((d[p + 2].toInt() and 255) shl 8) or (d[p + 3].toInt() and 255)
        val sizes = Array(chunks) { IntArray(n) }
        val total = IntArray(n)
        for (c in 0 until chunks) {
            var acc = 0
            for (f in 0 until n) { acc += i32(t); t += 4; sizes[c][f] = acc; total[f] += acc }
        }
        val out = Array(n) { ByteArray(total[it]) }
        val filled = IntArray(n)
        var p = 0
        for (c in 0 until chunks) for (f in 0 until n) {
            val s = sizes[c][f]
            if (s < 0 || p + s > d.size) return null
            System.arraycopy(d, p, out[f], filled[f], s); filled[f] += s; p += s
        }
        return ids.indices.associate { ids[it] to out[it] }
    }

    /**
     * An animation without what comes after its end marker (padding): the game and the server both stop reading at
     * the end marker, so it's the same animation to them.
     */
    fun trimSeq(id: Int, raw: ByteArray): ByteArray {
        val s = SeqDef.decode(id, raw)
        return if (s.end in 1 until raw.size) raw.copyOf(s.end) else raw
    }

    /**
     * True when the cache library can't read the animations archive (all of them come back empty) but the archive
     * has animations in it.
     */
    fun animationsNeedRepair(lib: CacheLibrary): Boolean {
        val a = try { lib.index(2).archive(12) } catch (e: Exception) { null } ?: return false
        val ids = a.fileIds()
        if (ids.isEmpty()) return false
        return ids.take(20).all { (lib.data(2, 12, it)?.size ?: 0) == 0 }
    }

    /**
     * Puts every animation back at its real length (the padding after the end marker taken off), so the archive is
     * small again. The game reads them the same. Returns how many were made shorter, or null if it couldn't.
     */
    fun repairAnimations(lib: CacheLibrary): Int? {
        val files = readFiles(lib, 2, 12) ?: return null
        var trimmed = 0
        for ((id, raw) in files) {
            val keep = trimSeq(id, raw)
            if (keep.size != raw.size) trimmed++
            lib.put(2, 12, id, keep)
        }
        lib.index(2).update()
        return trimmed
    }
}
