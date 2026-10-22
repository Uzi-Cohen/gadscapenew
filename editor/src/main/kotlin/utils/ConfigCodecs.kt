package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/*
 * Config formats that changed between rev 211 (GadScape's old cache) and build 241 (the cache the game runs on now),
 * and the small codecs for identity kits, spot animations and textures. Items, NPCs and objects have their own files
 * (ItemEdit.kt, NpcDefs.kt, ObjDefs.kt, cache/loaders/ObjectLoader.kt); they use the revision helpers and the 211->241
 * converters here.
 *
 * The 241 opcode tables were read off the real build-241 client (injected-client 1.13.3: items class ok, NPCs ps,
 * objects or, identity kits oy, spot animations ob, textures fr) and checked against every entry of the stock 241
 * cache, OpenRS2 2746 and GadScape's merged 241 cache. In 241 every model id is a 4-byte int, and the old 2-byte model
 * opcodes still exist in the client's switch but carry no data: writing a 211 model opcode into a 241 cache makes
 * the client read the model id as the next opcodes, so the codecs here never do that.
 */

/** Which config format a cache (or a byte array) uses. */
object ConfigRev {
    const val R211 = CacheIO.REV_211
    const val R241 = CacheIO.REV_241

    fun is241(rev: Int) = rev >= R241
    fun other(rev: Int) = if (is241(rev)) R211 else R241
    fun name(rev: Int) = if (is241(rev)) "build 241" else "rev 211"
    /**
     * The highest model id items, NPCs, objects, kits and graphics can point at: 65535 in both formats (2-byte ids in
     * 211; 241 stores 4 bytes but its client fetches models with 16-bit group ids, see [CacheIO.MAX_MODEL_ID]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun maxModelId(rev: Int) = CacheIO.MAX_MODEL_ID

    /** The revision of an open cache. */
    fun of(lib: CacheLibrary): Int = CacheIO.revision(lib)

    /** What [ofDir] found for a folder, and what it checks to tell whether the folder still holds that cache. */
    private class Known(val rev: Int, val masterLength: Long, val forced: String)
    private val byDir = ConcurrentHashMap<String, Known>()
    /** The cache the editor had open ([cache.CachePaths]) when [byDir] was filled. */
    @Volatile private var openedAs = ""

    /**
     * The revision of the cache in [dir] (211 when there is no readable cache), remembered per folder: saving changes
     * the cache files on every edit, so this must not open the cache again each time (it is called while typing).
     * Forgotten when the editor opens a cache ([forget]), and for one folder when its idx255 changes length (another
     * archive count: a different cache was put there, or one was made where there was none).
     */
    fun ofDir(dir: File): Int {
        val opened = cache.CachePaths.cacheDir + "|" + cache.CachePaths.revision
        if (opened != openedAs) { byDir.clear(); openedAs = opened }
        val path = dir.absoluteFile.normalize().path
        val length = File(dir, "main_file_cache.idx255").length()
        val forced = System.getProperty(CacheIO.REVISION_PROPERTY) ?: ""
        byDir[path]?.let { if (it.masterLength == length && it.forced == forced) return it.rev }
        val rev = CacheIO.revisionOf(dir) ?: R211
        byDir[path] = Known(rev, length, forced)
        return rev
    }

    /** Forget what [ofDir] remembered (after a cache folder was replaced). */
    fun forget() = byDir.clear()

    /** The revision of the cache the editor has open (the map's cache, [cache.CachePaths.cacheDir]). */
    fun current(): Int = ofDir(File(cache.CachePaths.cacheDir))
}

/** Reads a config entry (big-endian, as the client does). */
class CfgIn(val b: ByteArray, var p: Int = 0) {
    val left get() = b.size - p
    fun u8() = b[p++].toInt() and 0xFF
    fun s8() = b[p++].toInt()
    fun u16() = (u8() shl 8) or u8()
    fun s16() = u16().let { if (it > 32767) it - 65536 else it }
    fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
    fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
    fun i64() = (i32().toLong() shl 32) or (i32().toLong() and 0xFFFFFFFFL)
    fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
    fun skip(n: Int) { p += n; if (p > b.size) throw IndexOutOfBoundsException("past the end") }
    fun skipStr() { while (b[p].toInt() != 0) p++; p++ }
    /** Big smart (2 bytes, or 4 when the top bit is set). */
    fun skipBigSmart() = skip(if (b[p] < 0) 4 else 2)
    /** Smart (1 byte below 128, else 2). */
    fun skipSmart() = skip(if ((b[p].toInt() and 0xFF) < 128) 1 else 2)
    fun bytes(from: Int, to: Int = p) = b.copyOfRange(from, to)
}

/** Writes a config entry. */
class CfgOut {
    val o = ByteArrayOutputStream()
    fun u8(v: Int) = o.write(v and 0xFF)
    fun u16(v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
    fun u24(v: Int) { u8(v shr 16); u16(v and 0xFFFF) }
    fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
    fun i64(v: Long) { i32((v ushr 32).toInt()); i32(v.toInt()) }
    fun str(s: String) { o.write(s.toByteArray(Charsets.ISO_8859_1)); o.write(0) }
    fun raw(b: ByteArray, from: Int = 0, to: Int = b.size) = o.write(b, from, to - from)
    fun done(): ByteArray = o.toByteArray()
}

/** Params (opcode 249 of items, NPCs, objects): type 0 int, 1 string, 2 long (241 only). */
object CfgParams {
    fun read(i: CfgIn, rev: Int, into: MutableMap<Int, Any>? = null) {
        val n = i.u8()
        repeat(n) {
            val type = i.u8()
            val key = i.u24()
            val v: Any = when {
                type == 1 -> i.str()
                type == 2 && ConfigRev.is241(rev) -> i.i64()
                else -> i.i32()
            }
            into?.put(key, v)
        }
    }

    fun write(o: CfgOut, params: Map<Int, Any>, rev: Int) {
        val keep = params.filter { (_, v) -> v is String || v is Int || (v is Long && (ConfigRev.is241(rev) || v in Int.MIN_VALUE..Int.MAX_VALUE)) }
        require(keep.size <= 255) { "at most 255 params" }
        o.u8(keep.size)
        for ((k, v) in keep) when (v) {
            is String -> { o.u8(1); o.u24(k); o.str(v) }
            is Long -> if (ConfigRev.is241(rev)) { o.u8(2); o.u24(k); o.i64(v) } else { o.u8(0); o.u24(k); o.i32(v.toInt()) }
            else -> { o.u8(0); o.u24(k); o.i32(v as Int) }
        }
    }
}

/**
 * Rev 211 -> build 241 converters for one config entry, the same rules as the converter (rev241/tools/Conv.java):
 * model opcodes move to their 4-byte twins, NPC "follower" 111 becomes 122 + 123, objects get the sound retain byte.
 * Everything else is copied byte for byte. Each throws on an opcode the 211 format doesn't have, or one that has no
 * build 241 equivalent.
 */
object ConfigConv {
    /** Items: 1/23/24/25/26/78/79/90-93 -> 44-54 (4-byte model ids). */
    fun item211to241(id: Int, b: ByteArray): ByteArray {
        val i = CfgIn(b); val o = CfgOut()
        while (true) {
            val start = i.p
            val op = i.u8()
            if (op == 0) { o.u8(0); return o.done() }
            when (op) {
                1 -> { o.u8(44); o.i32(i.u16()); continue }
                23 -> { o.u8(45); o.i32(i.u16()); o.u8(i.u8()); continue }
                24 -> { o.u8(46); o.i32(i.u16()); continue }
                25 -> { o.u8(48); o.i32(i.u16()); o.u8(i.u8()); continue }
                26 -> { o.u8(49); o.i32(i.u16()); continue }
                78 -> { o.u8(47); o.i32(i.u16()); continue }
                79 -> { o.u8(50); o.i32(i.u16()); continue }
                90 -> { o.u8(51); o.i32(i.u16()); continue }
                91 -> { o.u8(53); o.i32(i.u16()); continue }
                92 -> { o.u8(52); o.i32(i.u16()); continue }
                93 -> { o.u8(54); o.i32(i.u16()); continue }
                2, 3, 9, in 30..39 -> i.skipStr()
                4, 5, 6, 7, 8, 75, 94, 95, 97, 98, 110, 111, 112, 139, 140, 148, 149 -> i.skip(2)
                11, 16, 65 -> {}
                12 -> i.skip(4)
                13, 14, 27, 42, 113, 114, 115 -> i.skip(1)
                40, 41 -> i.skip(4 * i.u8())
                43 -> { i.u8(); while (i.u8() != 0) i.skipStr() }
                in 100..109 -> i.skip(4)
                249 -> CfgParams.read(i, ConfigRev.R211)
                else -> throw IllegalStateException("item $id: setting $op isn't a rev 211 item setting")
            }
            o.raw(b, start, i.p)
        }
    }

    /**
     * NPCs: 1/60 -> 61/62 (4-byte model ids). 211 opcode 111 set two flags, "follower" and "follower options below
     * Walk here"; 241 has them as 122 and 123 (its 111 is render priority), so it becomes both.
     */
    fun npc211to241(id: Int, b: ByteArray): ByteArray {
        val i = CfgIn(b); val o = CfgOut()
        while (true) {
            val start = i.p
            val op = i.u8()
            if (op == 0) { o.u8(0); return o.done() }
            when (op) {
                1, 60 -> { val n = i.u8(); o.u8(if (op == 1) 61 else 62); o.u8(n); repeat(n) { o.i32(i.u16()) }; continue }
                111 -> { o.u8(122); o.u8(123); continue }
                else -> NpcDefs.skip211(i, op, id)
            }
            o.raw(b, start, i.p)
        }
    }

    /**
     * Objects: 1/5 -> 6/7 (4-byte model ids); the ambient sound opcodes 78/79 get the retain byte 241 always reads
     * (GadScape's 211 client only had it on ids above [wideAbove]). The 211-only opcodes: 63 (no data) is left out,
     * 60 (u16) throws, like the converter (rev241/tools/Conv.java): the 241 client reads both as settings without
     * data, so 60's two bytes would be read as settings, and it isn't known to be the old form of a 241 setting (82 is
     * the map area in both). No 211 cache in hand uses either. The result is checked by reading it as a 241 object.
     */
    fun loc211to241(id: Int, b: ByteArray, wideAbove: Int = ObjDefs.WIDE_SOUNDS_ABOVE): ByteArray {
        val wide = id > wideAbove
        val i = CfgIn(b); val o = CfgOut()
        while (true) {
            val start = i.p
            val op = i.u8()
            if (op == 0) {
                o.u8(0)
                val out = o.done()
                ObjDefs.decodeWith(id, out, true, ConfigRev.R241)
                    ?: throw IllegalStateException("object $id didn't convert to a readable build 241 object")
                return out
            }
            when (op) {
                1 -> { val n = i.u8(); o.u8(6); o.u8(n); repeat(n) { o.i32(i.u16()); o.u8(i.u8()) }; continue }
                5 -> { val n = i.u8(); o.u8(7); o.u8(n); repeat(n) { o.i32(i.u16()) }; continue }
                60 -> throw IllegalStateException("object $id: rev 211 setting 60 has no build 241 equivalent")
                63 -> continue
                78 -> { i.skip(3); o.raw(b, start, i.p); o.u8(if (wide) i.u8() else 0); continue }
                79 -> {
                    i.skip(5); o.raw(b, start, i.p); o.u8(if (wide) i.u8() else 0)
                    val s = i.p; i.skip(2 * i.u8()); o.raw(b, s, i.p); continue
                }
                else -> ObjDefs.skip(i, op, id, ConfigRev.R211, wide)
            }
            o.raw(b, start, i.p)
        }
    }

    /** Identity kits: 2 -> 5 (4-byte models), chathead models 60-64 -> 70-74 (both clients have 5 slots). */
    fun kit211to241(id: Int, b: ByteArray): ByteArray {
        val i = CfgIn(b); val o = CfgOut()
        while (true) {
            val start = i.p
            val op = i.u8()
            if (op == 0) { o.u8(0); return o.done() }
            when (op) {
                2 -> { val n = i.u8(); o.u8(5); o.u8(n); repeat(n) { o.i32(i.u16()) }; continue }
                in 60..64 -> { o.u8(op + 10); o.i32(i.u16()); continue }
                else -> KitCodec.skip(i, op, id, ConfigRev.R211)
            }
            o.raw(b, start, i.p)
        }
    }

    /** Spot animations: 1 -> 3 (4-byte model id). */
    fun spot211to241(id: Int, b: ByteArray): ByteArray {
        val i = CfgIn(b); val o = CfgOut()
        while (true) {
            val start = i.p
            val op = i.u8()
            if (op == 0) { o.u8(0); return o.done() }
            when (op) {
                1 -> { o.u8(3); o.i32(i.u16()); continue }
                else -> SpotCodec.skip(i, op, id, ConfigRev.R211)
            }
            o.raw(b, start, i.p)
        }
    }

    /** Textures: the 211 12+ byte layout -> the 241 7-byte one (one sprite per texture; see [TextureCodec]). */
    fun texture211to241(id: Int, b: ByteArray): ByteArray = TextureCodec.encode(TextureCodec.decode(id, b, ConfigRev.R211), ConfigRev.R241)
}

// ---------------------------------------------------------------------------------------------------------------------
// Identity kits (config archive 3)

/** An identity kit (a body part players can pick: hair, jaw, torso...). Model ids are -1 when unset. */
class KitDef(val id: Int) {
    var bodyPart = -1
    var models = IntArray(0)
    var selectable = true
    var recolorFrom = ShortArray(0); var recolorTo = ShortArray(0)
    var retextureFrom = ShortArray(0); var retextureTo = ShortArray(0)
    /**
     * Chathead models (211: opcodes 60-64, 241: 70-74). Both clients keep 5 and throw on 65-69 / 75-79 (an array of 5
     * indexed by opcode - 60 / - 70), so a kit with those counts as unreadable.
     */
    val heads = IntArray(5) { -1 }
    var rev = ConfigRev.R211
    var readOk = true
    /** The bytes it was read from and their canonical encoding then: an unchanged kit is written back as read. */
    internal var source: ByteArray? = null
    internal var sourceCanon: ByteArray? = null
}

object KitCodec {
    /** Skips one opcode's data ([op] already read). Throws on an opcode the [rev] client doesn't have. */
    fun skip(i: CfgIn, op: Int, id: Int, rev: Int) {
        val r241 = ConfigRev.is241(rev)
        when {
            op == 1 -> i.skip(1)
            op == 3 -> {}
            op == 2 && !r241 -> i.skip(2 * i.u8())
            op == 5 && r241 -> i.skip(4 * i.u8())
            op == 40 || op == 41 -> i.skip(4 * i.u8())
            op in 60..64 && !r241 -> i.skip(2)
            op in 70..74 && r241 -> i.skip(4)
            else -> throw IllegalStateException("identity kit $id: setting $op isn't a ${ConfigRev.name(rev)} kit setting")
        }
    }

    fun decode(id: Int, b: ByteArray, rev: Int): KitDef {
        val d = KitDef(id).also { it.rev = rev }
        val i = CfgIn(b)
        val r241 = ConfigRev.is241(rev)
        try {
            while (true) {
                val op = i.u8()
                if (op == 0) break
                when {
                    op == 1 -> d.bodyPart = i.u8()
                    op == 2 && !r241 -> { val n = i.u8(); d.models = IntArray(n) { i.u16() } }
                    op == 5 && r241 -> { val n = i.u8(); d.models = IntArray(n) { i.i32() } }
                    op == 3 -> d.selectable = false
                    op == 40 -> { val n = i.u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n); for (k in 0 until n) { d.recolorFrom[k] = i.u16().toShort(); d.recolorTo[k] = i.u16().toShort() } }
                    op == 41 -> { val n = i.u8(); d.retextureFrom = ShortArray(n); d.retextureTo = ShortArray(n); for (k in 0 until n) { d.retextureFrom[k] = i.u16().toShort(); d.retextureTo[k] = i.u16().toShort() } }
                    op in 60..64 && !r241 -> d.heads[op - 60] = i.u16()
                    op in 70..74 && r241 -> d.heads[op - 70] = i.i32()
                    else -> { d.readOk = false; break }
                }
            }
        } catch (e: Exception) { d.readOk = false }
        if (d.readOk) try { d.sourceCanon = canonical(d, d.rev); d.source = b.copyOf() } catch (e: Exception) {}
        return d
    }

    /** Reads [b] as [prefer] first, then as the other revision if that fails. */
    fun decodeAny(id: Int, b: ByteArray, prefer: Int = ConfigRev.current()): KitDef =
        decode(id, b, prefer).takeIf { it.readOk } ?: decode(id, b, ConfigRev.other(prefer)).takeIf { it.readOk } ?: decode(id, b, prefer)

    /** [d] in format [rev]; an unchanged kit comes back byte for byte as it was read. */
    fun encode(d: KitDef, rev: Int): ByteArray {
        val out = canonical(d, rev)
        val src = d.source
        return if (src != null && rev == d.rev && out.contentEquals(d.sourceCanon)) src.copyOf() else out
    }

    private fun canonical(d: KitDef, rev: Int): ByteArray {
        val o = CfgOut()
        val r241 = ConfigRev.is241(rev)
        if (d.bodyPart >= 0) { o.u8(1); o.u8(d.bodyPart) }
        if (d.models.isNotEmpty()) {
            if (r241) { o.u8(5); o.u8(d.models.size); d.models.forEach { o.i32(it) } }
            else { require(d.models.all { it in 0..65535 }) { "kit ${d.id}: rev 211 kits can only use model ids up to 65535" }; o.u8(2); o.u8(d.models.size); d.models.forEach { o.u16(it) } }
        }
        if (!d.selectable) o.u8(3)
        if (d.recolorFrom.isNotEmpty()) { o.u8(40); o.u8(d.recolorFrom.size); for (k in d.recolorFrom.indices) { o.u16(d.recolorFrom[k].toInt()); o.u16(d.recolorTo[k].toInt()) } }
        if (d.retextureFrom.isNotEmpty()) { o.u8(41); o.u8(d.retextureFrom.size); for (k in d.retextureFrom.indices) { o.u16(d.retextureFrom[k].toInt()); o.u16(d.retextureTo[k].toInt()) } }
        for (k in d.heads.indices) if (d.heads[k] != -1) {
            if (r241) { o.u8(70 + k); o.i32(d.heads[k]) } else { o.u8(60 + k); o.u16(d.heads[k]) }
        }
        o.u8(0)
        return o.done()
    }

    /** Every kit in [lib], read in the cache's own format. */
    fun all(lib: CacheLibrary): Map<Int, KitDef> {
        val out = HashMap<Int, KitDef>()
        val rev = ConfigRev.of(lib)
        val arch = lib.index(2).archive(3) ?: return out
        for (id in arch.fileIds()) { val b = lib.data(2, 3, id) ?: continue; out[id] = decode(id, b, rev) }
        return out
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Spot animations (config archive 13): graphics like spell effects

class SpotDef(val id: Int) {
    var model = -1
    var sequence = -1
    var resizeX = 128; var resizeY = 128
    var rotation = 0
    var ambient = 0; var contrast = 0
    var recolorFrom = ShortArray(0); var recolorTo = ShortArray(0)
    var retextureFrom = ShortArray(0); var retextureTo = ShortArray(0)
    /** 241 opcode 10 (no data): the client clears a flag of the graphic (the server's decoder calls it "not clickable"). */
    var flag10 = false
    /** 241 opcode 42 (full recolour), -1 when unset. */
    var recolorAll = -1
    var rev = ConfigRev.R211
    var readOk = true
    /** The bytes it was read from and their canonical encoding then: an unchanged graphic is written back as read. */
    internal var source: ByteArray? = null
    internal var sourceCanon: ByteArray? = null
}

object SpotCodec {
    /**
     * Skips one opcode's data. 241 reads 3 (4-byte model), 10 (flag) and 42 (u16); its opcodes 1 and 9 carry no data
     * (the client's switch ignores them; no 241 cache uses them).
     */
    fun skip(i: CfgIn, op: Int, id: Int, rev: Int) {
        val r241 = ConfigRev.is241(rev)
        when {
            op == 1 && !r241 -> i.skip(2)
            op == 3 && r241 -> i.skip(4)
            op == 2 || op == 4 || op == 5 || op == 6 -> i.skip(2)
            op == 7 || op == 8 -> i.skip(1)
            op == 10 && r241 -> {}
            op == 42 && r241 -> i.skip(2)
            op == 40 || op == 41 -> i.skip(4 * i.u8())
            else -> throw IllegalStateException("graphic $id: setting $op isn't a ${ConfigRev.name(rev)} graphic setting")
        }
    }

    fun decode(id: Int, b: ByteArray, rev: Int): SpotDef {
        val d = SpotDef(id).also { it.rev = rev }
        val i = CfgIn(b)
        val r241 = ConfigRev.is241(rev)
        try {
            while (true) {
                val op = i.u8()
                if (op == 0) break
                when {
                    op == 1 && !r241 -> d.model = i.u16()
                    op == 3 && r241 -> d.model = i.i32()
                    op == 2 -> d.sequence = i.u16().let { if (it == 65535) -1 else it }
                    op == 4 -> d.resizeX = i.u16()
                    op == 5 -> d.resizeY = i.u16()
                    op == 6 -> d.rotation = i.u16()
                    op == 7 -> d.ambient = i.u8()
                    op == 8 -> d.contrast = i.u8()
                    op == 10 && r241 -> d.flag10 = true
                    op == 42 && r241 -> d.recolorAll = i.u16()
                    op == 40 -> { val n = i.u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n); for (k in 0 until n) { d.recolorFrom[k] = i.u16().toShort(); d.recolorTo[k] = i.u16().toShort() } }
                    op == 41 -> { val n = i.u8(); d.retextureFrom = ShortArray(n); d.retextureTo = ShortArray(n); for (k in 0 until n) { d.retextureFrom[k] = i.u16().toShort(); d.retextureTo[k] = i.u16().toShort() } }
                    else -> { d.readOk = false; break }
                }
            }
        } catch (e: Exception) { d.readOk = false }
        if (d.readOk) try { d.sourceCanon = canonical(d, d.rev); d.source = b.copyOf() } catch (e: Exception) {}
        return d
    }

    fun decodeAny(id: Int, b: ByteArray, prefer: Int = ConfigRev.current()): SpotDef =
        decode(id, b, prefer).takeIf { it.readOk } ?: decode(id, b, ConfigRev.other(prefer)).takeIf { it.readOk } ?: decode(id, b, prefer)

    /** The animation a graphic plays, or null; reads either format. */
    fun sequenceOf(b: ByteArray, prefer: Int = ConfigRev.current()): Int? = decodeAny(-1, b, prefer).takeIf { it.readOk }?.sequence?.takeIf { it >= 0 }

    /** [d] in format [rev]; an unchanged graphic comes back byte for byte as it was read. */
    fun encode(d: SpotDef, rev: Int): ByteArray {
        val out = canonical(d, rev)
        val src = d.source
        return if (src != null && rev == d.rev && out.contentEquals(d.sourceCanon)) src.copyOf() else out
    }

    private fun canonical(d: SpotDef, rev: Int): ByteArray {
        val o = CfgOut()
        val r241 = ConfigRev.is241(rev)
        if (d.model >= 0) {
            if (r241) { o.u8(3); o.i32(d.model) }
            else { require(d.model <= 65535) { "graphic ${d.id}: rev 211 graphics can only use model ids up to 65535" }; o.u8(1); o.u16(d.model) }
        }
        if (d.sequence >= 0) { o.u8(2); o.u16(d.sequence) }
        if (d.resizeX != 128) { o.u8(4); o.u16(d.resizeX) }
        if (d.resizeY != 128) { o.u8(5); o.u16(d.resizeY) }
        if (d.rotation != 0) { o.u8(6); o.u16(d.rotation) }
        if (d.ambient != 0) { o.u8(7); o.u8(d.ambient) }
        if (d.contrast != 0) { o.u8(8); o.u8(d.contrast) }
        if (r241 && d.flag10) o.u8(10)
        if (d.recolorFrom.isNotEmpty()) { o.u8(40); o.u8(d.recolorFrom.size); for (k in d.recolorFrom.indices) { o.u16(d.recolorFrom[k].toInt()); o.u16(d.recolorTo[k].toInt()) } }
        if (d.retextureFrom.isNotEmpty()) { o.u8(41); o.u8(d.retextureFrom.size); for (k in d.retextureFrom.indices) { o.u16(d.retextureFrom[k].toInt()); o.u16(d.retextureTo[k].toInt()) } }
        if (r241 && d.recolorAll != -1) { o.u8(42); o.u16(d.recolorAll) }
        o.u8(0)
        return o.done()
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Textures (archive 9, group 0)

/**
 * A texture's settings. Rev 211 stores (average colour u16, opaque u8, sprite count u8, sprite ids u16[count],
 * count-1 blend types, count-1 more bytes, colour ops i32[count], animation direction u8, speed u8): 12 bytes for the
 * usual one sprite. Build 241 stores one sprite: (sprite u16, average colour u16, opaque u8, direction u8, speed u8),
 * 7 bytes.
 */
class TextureData(val id: Int) {
    var sprites = IntArray(0)
    var averageRgb = 0
    var opaque = false
    var animDirection = 0
    var animSpeed = 0
    /** 211 only, for textures made of more than one sprite. */
    var blendTypes = IntArray(0)
    var blendExtra = IntArray(0)
    /** 211 only: a colour op per sprite (0 = none). */
    var colourOps = IntArray(0)
    var rev = ConfigRev.R211
}

object TextureCodec {
    fun decode(id: Int, b: ByteArray, rev: Int): TextureData {
        val t = TextureData(id).also { it.rev = rev }
        val i = CfgIn(b)
        if (ConfigRev.is241(rev)) {
            t.sprites = intArrayOf(i.u16())
            t.averageRgb = i.u16()
            t.opaque = i.u8() == 1
            t.animDirection = i.u8()
            t.animSpeed = i.u8()
            t.colourOps = IntArray(1)
        } else {
            t.averageRgb = i.u16()
            t.opaque = i.u8() != 0
            val n = i.u8()
            t.sprites = IntArray(n) { i.u16() }
            if (n > 1) { t.blendTypes = IntArray(n - 1) { i.u8() }; t.blendExtra = IntArray(n - 1) { i.u8() } }
            t.colourOps = IntArray(n) { i.i32() }
            t.animDirection = i.u8()
            t.animSpeed = i.u8()
        }
        return t
    }

    /** Reads either layout: a 241 texture is exactly 7 bytes, a 211 one at least 12. */
    fun decodeAny(id: Int, b: ByteArray, prefer: Int = ConfigRev.current()): TextureData =
        decode(id, b, if (b.size == 7) ConfigRev.R241 else if (b.size >= 12) ConfigRev.R211 else prefer)

    fun encode(t: TextureData, rev: Int): ByteArray {
        val o = CfgOut()
        if (ConfigRev.is241(rev)) {
            require(t.sprites.size == 1) { "texture ${t.id} uses ${t.sprites.size} sprites; build 241 textures use exactly one" }
            require(t.colourOps.all { it == 0 }) { "texture ${t.id} recolours its sprite, which build 241 textures can't" }
            o.u16(t.sprites[0]); o.u16(t.averageRgb); o.u8(if (t.opaque) 1 else 0); o.u8(t.animDirection); o.u8(t.animSpeed)
        } else {
            val n = t.sprites.size
            o.u16(t.averageRgb); o.u8(if (t.opaque) 1 else 0); o.u8(n)
            t.sprites.forEach { o.u16(it) }
            if (n > 1) { for (k in 0 until n - 1) o.u8(t.blendTypes.getOrElse(k) { 0 }); for (k in 0 until n - 1) o.u8(t.blendExtra.getOrElse(k) { 0 }) }
            for (k in 0 until n) o.i32(t.colourOps.getOrElse(k) { 0 })
            o.u8(t.animDirection); o.u8(t.animSpeed)
        }
        return o.done()
    }

    /** Every texture in [lib], in the cache's own format. */
    fun all(lib: CacheLibrary): Map<Int, TextureData> {
        val out = HashMap<Int, TextureData>()
        val rev = ConfigRev.of(lib)
        val arch = lib.index(9).archive(0) ?: return out
        for (f in arch.fileIds()) { val b = lib.data(9, 0, f) ?: continue; out[f] = decode(f, b, rev) }
        return out
    }
}
