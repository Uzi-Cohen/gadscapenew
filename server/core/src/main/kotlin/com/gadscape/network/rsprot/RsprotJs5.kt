package com.gadscape.network.rsprot

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.rsprot.protocol.api.js5.Js5GroupProvider
import net.rsprot.protocol.api.js5.Js5Service
import org.apache.tools.bzip2.CBZip2InputStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * A minimal, read-only reader for a Jagex `main_file_cache.dat2` + `.idxN` cache.
 *
 * It is deliberately self-contained (no jagcached) so the rev-241 network layer does not depend on
 * the server's cache library, which is being reworked separately. It only knows how to:
 *  - read the raw stored bytes of a group (container + optional 2-byte version trailer),
 *  - decompress a container (none / bzip2 / gzip; no XTEA - rev 241 maps are unencrypted),
 *  - parse the parts of a reference table it needs (version, group ids, name hashes).
 */
class RawJs5Cache(val directory: Path) : Closeable {

    private val data: FileChannel = FileChannel.open(directory.resolve("main_file_cache.dat2"), StandardOpenOption.READ)
    private val indexes = arrayOfNulls<FileChannel>(256)

    init {
        for (archive in 0..255) {
            val file = directory.resolve("main_file_cache.idx$archive")
            if (Files.exists(file)) indexes[archive] = FileChannel.open(file, StandardOpenOption.READ)
        }
        requireNotNull(indexes[255]) { "No main_file_cache.idx255 in $directory" }
    }

    /** Number of archives listed in idx255 (one 6-byte entry each, including empty ones). */
    val archiveCount: Int = (indexes[255]!!.size() / INDEX_ENTRY_SIZE).toInt()

    fun groupCapacity(archive: Int): Int {
        val index = indexes[archive] ?: return 0
        return (index.size() / INDEX_ENTRY_SIZE).toInt()
    }

    /** Raw stored bytes of [archive]:[group], or null if it does not exist. Thread-safe (positional reads). */
    fun read(archive: Int, group: Int): ByteArray? {
        val index = indexes[archive] ?: return null
        val entryOffset = group.toLong() * INDEX_ENTRY_SIZE
        if (group < 0 || entryOffset + INDEX_ENTRY_SIZE > index.size()) return null
        val entry = ByteBuffer.allocate(INDEX_ENTRY_SIZE)
        readFully(index, entry, entryOffset)
        entry.flip()
        val size = entry.getMedium()
        var sector = entry.getMedium()
        if (size <= 0 || sector <= 0) return null

        val extended = group > 0xFFFF
        val headerSize = if (extended) 10 else 8
        val out = ByteArray(size)
        var written = 0
        var part = 0
        val block = ByteBuffer.allocate(SECTOR_SIZE)
        while (written < size) {
            if (sector <= 0) return null
            block.clear()
            val chunk = minOf(size - written, SECTOR_SIZE - headerSize)
            block.limit(headerSize + chunk)
            readFully(data, block, sector.toLong() * SECTOR_SIZE)
            block.flip()
            val sectorGroup = if (extended) block.int else block.short.toInt() and 0xFFFF
            val sectorPart = block.short.toInt() and 0xFFFF
            val nextSector = block.getMedium()
            val sectorArchive = block.get().toInt() and 0xFF
            if (sectorGroup != group || sectorPart != part || sectorArchive != archive) {
                logger.warn("Corrupt sector chain for {}:{} (sector {}, part {})", archive, group, sector, part)
                return null
            }
            block.get(out, written, chunk)
            written += chunk
            sector = nextSector
            part++
        }
        return out
    }

    override fun close() {
        data.close()
        indexes.forEach { it?.close() }
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, pos)
            if (read < 0) break
            pos += read
        }
    }

    private fun ByteBuffer.getMedium(): Int =
        ((get().toInt() and 0xFF) shl 16) or ((get().toInt() and 0xFF) shl 8) or (get().toInt() and 0xFF)

    companion object {
        private val logger = LoggerFactory.getLogger(RawJs5Cache::class.java)
        private const val INDEX_ENTRY_SIZE = 6
        private const val SECTOR_SIZE = 520

        /** Length of the container itself (compression + length header + payload), i.e. without a version trailer. */
        @JvmStatic
        fun containerLength(stored: ByteArray): Int {
            if (stored.size < 5) return stored.size
            val compression = stored[0].toInt() and 0xFF
            val length = ((stored[1].toInt() and 0xFF) shl 24) or ((stored[2].toInt() and 0xFF) shl 16) or
                ((stored[3].toInt() and 0xFF) shl 8) or (stored[4].toInt() and 0xFF)
            val total = 5 + length + if (compression == 0) 0 else 4
            return minOf(total, stored.size)
        }

        /** Decompresses an (unencrypted) container. */
        @JvmStatic
        fun decompress(container: ByteArray): ByteArray {
            val compression = container[0].toInt() and 0xFF
            val length = ((container[1].toInt() and 0xFF) shl 24) or ((container[2].toInt() and 0xFF) shl 16) or
                ((container[3].toInt() and 0xFF) shl 8) or (container[4].toInt() and 0xFF)
            return when (compression) {
                0 -> container.copyOfRange(5, 5 + length)
                1 -> {
                    val decompressedLength = ((container[5].toInt() and 0xFF) shl 24) or
                        ((container[6].toInt() and 0xFF) shl 16) or ((container[7].toInt() and 0xFF) shl 8) or
                        (container[8].toInt() and 0xFF)
                    // Jagex strips the "BZh1" magic; Ant's decoder expects the stream after "BZ".
                    val stream = ByteArray(2 + length)
                    stream[0] = 'h'.code.toByte()
                    stream[1] = '1'.code.toByte()
                    System.arraycopy(container, 9, stream, 2, length)
                    val out = ByteArray(decompressedLength)
                    DataInputStream(CBZip2InputStream(ByteArrayInputStream(stream))).use { it.readFully(out) }
                    out
                }
                2 -> GZIPInputStream(ByteArrayInputStream(container, 9, length)).use { it.readBytes() }
                else -> error("Unsupported container compression $compression")
            }
        }
    }
}

/** The parts of a JS5 reference table (archive 255 group N) that the network layer needs. */
class Js5ReferenceTable(val protocol: Int, val version: Int, val groupIds: IntArray, val nameHashes: IntArray?) {

    fun groupByName(name: String): Int {
        val hashes = nameHashes ?: return -1
        val hash = nameHash(name)
        for (i in groupIds.indices) if (hashes[i] == hash) return groupIds[i]
        return -1
    }

    companion object {
        /** Jagex name hash (java String#hashCode of the lower-cased name). */
        @JvmStatic
        fun nameHash(name: String): Int = name.lowercase().hashCode()

        @JvmStatic
        fun decode(decompressed: ByteArray): Js5ReferenceTable {
            val buf = ByteBuffer.wrap(decompressed)
            val protocol = buf.get().toInt() and 0xFF
            require(protocol in 5..7) { "Unsupported reference table protocol $protocol" }
            val version = if (protocol >= 6) buf.int else 0
            val flags = buf.get().toInt() and 0xFF
            val count = if (protocol >= 7) buf.bigSmart() else buf.short.toInt() and 0xFFFF
            val ids = IntArray(count)
            var last = 0
            for (i in 0 until count) {
                last += if (protocol >= 7) buf.bigSmart() else buf.short.toInt() and 0xFFFF
                ids[i] = last
            }
            val names = if (flags and 0x1 != 0) IntArray(count) { buf.int } else null
            return Js5ReferenceTable(protocol, version, ids, names)
        }

        private fun ByteBuffer.bigSmart(): Int {
            val peek = get(position()).toInt()
            return if (peek >= 0) short.toInt() and 0xFFFF else int and Int.MAX_VALUE
        }
    }
}

/**
 * Serves raw JS5 groups (including the archive-255 reference tables and the 255/255 master index)
 * straight out of the cache directory, prepared in the 512-byte block layout rsprot expects.
 *
 * Groups are prepared lazily on first request and kept off-heap (the whole 241 cache is ~250 MB, the
 * same order as the old JS5Responses preload). Set -Dgadscape.js5.preload=true to prepare everything
 * at boot instead.
 */
class RsprotJs5(val cache: RawJs5Cache) : Js5GroupProvider {

    private val prepared = ConcurrentHashMap<Int, ByteBuf>()

    /** CRC of each archive's reference table, as listed in the master index (0 for absent archives). */
    val archiveCrcs: IntArray

    /** Version of each archive's reference table (0 for absent archives). */
    val archiveVersions: IntArray

    private val masterIndex: ByteArray

    init {
        val count = cache.archiveCount
        archiveCrcs = IntArray(count)
        archiveVersions = IntArray(count)
        for (archive in 0 until count) {
            val stored = cache.read(255, archive) ?: continue
            val container = stored.copyOf(RawJs5Cache.containerLength(stored))
            val crc = CRC32()
            crc.update(container)
            archiveCrcs[archive] = crc.value.toInt()
            archiveVersions[archive] = try {
                Js5ReferenceTable.decode(RawJs5Cache.decompress(container)).version
            } catch (e: Exception) {
                logger.warn("Could not read reference table version of archive {}", archive, e)
                0
            }
        }
        // 255/255: an uncompressed container holding (crc, version) for every archive slot.
        val payload = ByteBuffer.allocate(count * 8)
        for (archive in 0 until count) {
            payload.putInt(archiveCrcs[archive])
            payload.putInt(archiveVersions[archive])
        }
        masterIndex = ByteBuffer.allocate(5 + payload.capacity()).put(0).putInt(payload.capacity())
            .put(payload.array()).array()
        logger.info(
            "JS5 cache {}: {} archive slots, master index {} bytes",
            cache.directory, count, masterIndex.size
        )
    }

    /** Uncompressed contents of the single-file group [archive]:[name] (e.g. 10:"huffman"). */
    fun readNamedGroup(archive: Int, name: String): ByteArray? {
        val table = readReferenceTable(archive) ?: return null
        val group = table.groupByName(name)
        if (group == -1) return null
        val stored = cache.read(archive, group) ?: return null
        return RawJs5Cache.decompress(stored.copyOf(RawJs5Cache.containerLength(stored)))
    }

    fun readReferenceTable(archive: Int): Js5ReferenceTable? {
        val stored = cache.read(255, archive) ?: return null
        return Js5ReferenceTable.decode(RawJs5Cache.decompress(stored.copyOf(RawJs5Cache.containerLength(stored))))
    }

    override fun provide(archive: Int, group: Int): ByteBuf? {
        if (archive !in 0..255 || group !in 0..0xFFFF) return null
        val key = (archive shl 16) or group
        val existing = prepared[key]
        if (existing != null) return existing.duplicate()
        val built = build(archive, group) ?: return null
        val winner = prepared.putIfAbsent(key, built) ?: built
        return winner.duplicate()
    }

    fun preloadAll() {
        var groups = 0
        provide(255, 255)?.let { groups++ }
        for (archive in 0 until cache.archiveCount) {
            if (provide(255, archive) != null) groups++
            val table = try { readReferenceTable(archive) } catch (e: Exception) { null } ?: continue
            for (group in table.groupIds) if (provide(archive, group) != null) groups++
        }
        logger.info("Preloaded {} JS5 groups", groups)
    }

    private fun build(archive: Int, group: Int): ByteBuf? {
        val container: ByteArray = if (archive == 255 && group == 255) {
            masterIndex
        } else {
            val stored = cache.read(archive, group) ?: return null
            // reference tables (255/x) carry no version trailer; regular groups do and it must not be sent.
            stored.copyOf(RawJs5Cache.containerLength(stored))
        }
        val input = Unpooled.wrappedBuffer(container)
        val output = Unpooled.directBuffer(container.size + 3 + container.size / 511 + 1)
        Js5Service.prepareJs5Buffer(archive, group, input, output)
        // rsprot releases the slices it writes; the prepared buffer is shared and lives for the whole run.
        return Unpooled.unreleasableBuffer(output)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RsprotJs5::class.java)
    }
}
