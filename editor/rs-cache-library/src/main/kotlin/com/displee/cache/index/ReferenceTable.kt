package com.displee.cache.index

import com.displee.cache.CacheLibrary
import com.displee.cache.index.Index.Companion.WHIRLPOOL_SIZE
import com.displee.cache.index.archive.Archive
import com.displee.cache.index.archive.Archive317
import com.displee.cache.index.archive.file.File
import com.displee.compress.CompressionType
import com.displee.compress.decompress
import com.displee.io.impl.InputBuffer
import com.displee.io.impl.OutputBuffer
import java.util.*
import kotlin.collections.ArrayList

open class ReferenceTable(protected val origin: CacheLibrary, val id: Int) : Comparable<ReferenceTable> {

    var revision = 0
    private var mask = 0x0
    private var needUpdate = false
    protected var archives: SortedMap<Int, Archive> = TreeMap()

    var version = 0

    override fun compareTo(other: ReferenceTable): Int {
        return id.compareTo(other.id)
    }

    open fun read(buffer: InputBuffer) {
        version = buffer.readUnsignedByte()
        if (version < 5 || version > 7) {
            throw RuntimeException("Unknown version: $version")
        }
        revision = if (version >= 6) buffer.readInt() else 0
        mask = buffer.readByte().toInt()
        val named = mask and FLAG_NAME != 0
        val whirlpool = mask and FLAG_WHIRLPOOL != 0
        val flag4 = mask and FLAG_4 != 0
        val flag8 = mask and FLAG_8 != 0

        val readFun: () -> (Int) = if (version >= 7) {
            {
                // GadScape fork: an unsigned big smart, as the client reads it (disio's readBigSmart turns 32767 into -1)
                if (buffer.raw()[buffer.offset] < 0) buffer.readInt() and Int.MAX_VALUE else buffer.readUnsignedShort()
            }
        } else {
            {
                buffer.readUnsignedShort()
            }
        }

        val archiveIds = IntArray(readFun())
        for (i in archiveIds.indices) {
            val archiveId = readFun() + if (i == 0) 0 else archiveIds[i - 1]
            archiveIds[i] = archiveId.also { archives[it] = Archive(it) }
        }
        val archives = archives()
        if (named) {
            archives.forEach { it.hashName = buffer.readInt() }
        }
        // GadScape fork: the optional per-group fields are read whenever the table's flags say they are there (6.4 only
        // read flags 0x4/0x8 for RS3 caches, so OSRS rev 229+ tables, e.g. build 241, failed or read wrong versions).
        // Order, as in the client and OpenRS2's Js5Index: crcs, uncompressed crcs (0x8), whirlpool (0x2), sizes (0x4).
        archives.forEach { it.crc = buffer.readInt() }
        if (flag8) {
            archives.forEach { it.flag8Value = buffer.readInt() }
        }
        if (whirlpool) {
            archives.forEach {
                var archiveWhirlpool = it.whirlpool
                if (archiveWhirlpool == null) {
                    archiveWhirlpool = ByteArray(WHIRLPOOL_SIZE)
                    it.whirlpool = archiveWhirlpool
                }
                buffer.readBytes(archiveWhirlpool)
            }
        }
        if (flag4) {
            archives.forEach {
                it.flag4Value1 = buffer.readInt()
                it.flag4Value2 = buffer.readInt()
            }
        }
        archives.forEach { it.revision = buffer.readInt() }
        val archiveFileSizes = IntArray(archives.size)
        for (i in archives.indices) {
            archiveFileSizes[i] = readFun()
        }
        for (i in archives.indices) {
            val archive = archives[i]
            var fileId = 0
            for (fileIndex in 0 until archiveFileSizes[i]) {
                fileId += readFun()
                archive.files[fileId] = File(fileId)
            }
        }
        if (named) {
            for (i in archives.indices) {
                val archive = archives[i]
                val fileIds = archive.fileIds()
                for (fileIndex in 0 until archiveFileSizes[i]) {
                    archive.file(fileIds[fileIndex])?.hashName = buffer.readInt()
                }
            }
        }
    }

    open fun write(): ByteArray {
        // GadScape fork: protocols 5 and 6 store ids and counts in 2 bytes; anything above 65535 (e.g. model 100000)
        // needs protocol 7 (big smarts), which every OSRS client reads. A protocol 7 table is never downgraded.
        if (version < 7 && needsBigSmarts()) {
            version = 7
        }
        val buffer = OutputBuffer(10_000_000) //10mb
        buffer.writeByte(version)
        if (version >= 6) {
            buffer.writeInt(revision)
        }
        buffer.writeByte(mask)

        val writeFun: (Int) -> Unit = if (version >= 7) {
            {
                buffer.writeBigSmart(it)
            }
        } else {
            {
                buffer.writeShort(it)
            }
        }

        writeFun(archives.size)
        val archiveIds = archiveIds()
        val archives = archives()
        for (i in archives.indices) {
            writeFun(archiveIds[i] - if (i == 0) 0 else archiveIds[i - 1])
        }
        if (isNamed()) {
            archives.forEach { buffer.writeInt(it.hashName) }
        }
        // GadScape fork: same field order as read(), driven by the table's flags only (see read()).
        archives.forEach { buffer.writeInt(it.crc) }
        if (hasFlag8()) {
            archives.forEach { buffer.writeInt(it.flag8Value) }
        }
        if (hasWhirlpool()) {
            val empty = ByteArray(WHIRLPOOL_SIZE)
            archives.forEach { buffer.writeBytes(it.whirlpool ?: empty) }
        }
        if (hasFlag4()) {
            archives.forEach { buffer.writeInt(it.flag4Value1).writeInt(it.flag4Value2) }
        }
        archives.forEach { buffer.writeInt(it.revision) }
        archives.forEach { writeFun(it.files.size) }
        archives.forEach {
            val fileIds = it.fileIds()
            for (fileIndex in fileIds.indices) {
                writeFun(fileIds[fileIndex] - if (fileIndex == 0) 0 else fileIds[fileIndex - 1])
            }
        }
        if (isNamed()) {
            archives.forEach { archive ->
                archive.files().forEach { file ->
                    buffer.writeInt(file.hashName)
                }
            }
        }
        return buffer.array()
    }

    @JvmOverloads
    fun add(data: ByteArray, xtea: IntArray? = null): Archive {
        val archive = add(xtea = xtea)
        archive.add(data)
        return archive
    }

    fun add(vararg archives: Archive?, overwrite: Boolean = true): Array<Archive> {
        val newArchives = ArrayList<Archive>(archives.size)
        archives.forEach {
            it ?: return@forEach
            newArchives.add(add(it, overwrite = overwrite))
        }
        return newArchives.toTypedArray()
    }

    @JvmOverloads
    fun add(archive: Archive, newId: Int = archive.id, xtea: IntArray? = null, overwrite: Boolean = true): Archive {
        val new = add(newId, archive.hashName, xtea, overwrite)
        if (overwrite) {
            new.clear()
            new.add(*archive.copyFiles())
            new.flag()
        }
        return new
    }

    @JvmOverloads
    fun add(name: String? = null, xtea: IntArray? = null): Archive {
        var id = if (name == null) nextId() else archiveId(name)
        if (id == -1) {
            id = nextId()
        }
        return add(id, toHash(name ?: ""), xtea)
    }

    @JvmOverloads
    fun add(id: Int, hashName: Int = -1, xtea: IntArray? = null, overwrite: Boolean = true): Archive {
        var existing = archive(id, direct = true)
        if (existing != null && !existing.read && !existing.new && !existing.flagged()) {
            existing = archive(id, xtea)
        }
        if (existing == null) {
            if (this is Index317) {
                existing = Archive317(id, if (hashName == -1) 0 else hashName)
                if (this.id != 0) {
                    existing.compressionType = CompressionType.GZIP
                }
            } else {
                existing = Archive(id, if (hashName == -1) 0 else hashName, xtea)
                existing.compressionType = CompressionType.GZIP
            }
            archives[id] = existing
            existing.new = true
            existing.flag()
            flag()
        } else if (overwrite) {
            var flag = false
            val existingXtea = existing.xtea
            if (xtea == null && existingXtea != null || xtea != null && existingXtea == null ||
                    xtea != null && existingXtea != null && !xtea.contentEquals(existingXtea)) {
                existing.xtea = xtea
                flag = true
            }
            if (hashName != -1 && existing.hashName != hashName) {
                existing.hashName = hashName
                flag = true
            }
            if (flag) {
                existing.flag()
                flag()
            }
        }
        return existing
    }

    fun archive(name: String, direct: Boolean = false): Archive? {
        return archive(archiveId(name), direct)
    }

    @JvmOverloads
    fun archive(name: String, xtea: IntArray? = null, direct: Boolean = false): Archive? {
        return archive(archiveId(name), xtea, direct)
    }

    fun archive(id: Int, direct: Boolean = false): Archive? {
        return archive(id, null, direct)
    }

    @JvmOverloads
    open fun archive(id: Int, xtea: IntArray? = null, direct: Boolean = false): Archive? {
        check(!origin.closed) { "Cache is closed." }
        val archive = archives[id] ?: return null
        if (direct || archive.read || archive.new) {
            return archive
        }
        val sector = origin.index(this.id).readArchiveSector(id)
        if (sector == null) {
            archive.read = true
            archive.new = true
            archive.clear()
        } else {
            val is317 = is317()
            if (is317) {
                (archive as Archive317).compressionType = if (this.id == 0) CompressionType.BZIP2 else CompressionType.GZIP
                archive.read(InputBuffer(sector.data))
            } else {
                val decompressed = sector.decompress(xtea)
                archive.compressionType = sector.compressionType
                if (decompressed.isNotEmpty()) {
                    archive.read(InputBuffer(decompressed))
                    archive.xtea = xtea
                }
            }
            val mapsIndex = if (is317) 4 else 5
            if (this.id == mapsIndex && !archive.containsData()) {
                archive.read = false
            }
            if (!is317) {
                // GadScape fork: the 2-byte version trailer comes after the container header (5 bytes, 9 when
                // compressed) and the data. 6.4 left the 4-byte uncompressed length out, so for a group stored
                // without a trailer (OpenRS2 caches have them) it took the last 2 bytes of the data as the version.
                val sectorBuffer = InputBuffer(sector.data)
                val type = sectorBuffer.readUnsignedByte()
                val header = if (type == CompressionType.NONE.ordinal) 5 else 9
                val remaining: Int = sector.data.size - (sectorBuffer.readInt() + header)
                if (remaining >= 2) {
                    sectorBuffer.offset = sector.data.size - 2
                    val trailer = sectorBuffer.readUnsignedShort()
                    // the table keeps the full version, the trailer its low 16 bits: keep the table's when they agree
                    if (archive.revision and 0xFFFF != trailer) {
                        archive.revision = trailer
                    }
                }
            }
        }
        return archive
    }

    fun contains(id: Int): Boolean {
        return archives.containsKey(id)
    }

    fun contains(name: String): Boolean {
        return archiveId(name) != -1
    }

    fun remove(id: Int): Archive? {
        val archive = archives.remove(id) ?: return null
        flag()
        return archive
    }

    fun remove(name: String): Archive? {
        return remove(archiveId(name))
    }

    fun first(): Archive? {
        if (archives.isEmpty()) {
            return null
        }
        return archive(archives.firstKey())
    }

    fun last(): Archive? {
        if (archives.isEmpty()) {
            return null
        }
        return archive(archives.lastKey())
    }

    fun archiveId(name: String): Int {
        val hashName = toHash(name)
        archives.values.forEach {
            if (it.hashName == hashName) {
                return it.id
            }
        }
        return -1
    }

    fun nextId(): Int {
        val last = last()
        return if (last == null) 0 else last.id + 1
    }

    fun copyArchives(): Array<Archive> {
        val archives = archives()
        val copy = ArrayList<Archive>(archives.size)
        for (i in archives.indices) {
            copy.add(i, Archive(archives[i]))
        }
        return copy.toTypedArray()
    }

    fun flag() {
        needUpdate = true
    }

    fun flagged(): Boolean {
        return needUpdate
    }

    fun unFlag() {
        needUpdate = false
    }

    fun mask(referenceTable: ReferenceTable) {
        this.mask = referenceTable.mask
    }

    fun flagMask(flag: Int) {
        mask = mask or flag
    }

    fun unFlagMask(flag: Int) {
        mask = mask and flag.inv()
    }

    fun isNamed(): Boolean {
        return mask and FLAG_NAME != 0
    }

    fun hasWhirlpool(): Boolean {
        return mask and FLAG_WHIRLPOOL != 0
    }

    fun hasFlag4(): Boolean {
        return mask and FLAG_4 != 0
    }

    fun hasFlag8(): Boolean {
        return mask and FLAG_8 != 0
    }

    /** True if an id or count in this table doesn't fit the 2 bytes protocols 5 and 6 use. */
    fun needsBigSmarts(): Boolean {
        if (archives.size > 65535) {
            return true
        }
        for (archive in archives.values) {
            if (archive.id > 65535 || archive.files.size > 65535) {
                return true
            }
            if (archive.files.isNotEmpty() && archive.files.lastKey() > 65535) {
                return true
            }
        }
        return false
    }

    fun archiveIds(): IntArray {
        return archives.keys.toIntArray()
    }

    fun archives(): Array<Archive> {
        return archives.values.toTypedArray()
    }

    open fun toHash(name: String): Int {
        return name.hashCode()
    }

    fun is317(): Boolean {
        return this is Index317
    }

    companion object {
        const val FLAG_NAME = 0x1
        const val FLAG_WHIRLPOOL = 0x2
        const val FLAG_4 = 0x4
        const val FLAG_8 = 0x8
    }

}