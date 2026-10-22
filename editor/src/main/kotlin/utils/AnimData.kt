package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Animations: sequences (config archive 12), their frames (archive 0, one group per frame set), skeletons (archive 1)
 * and skeletal keyframe sets (archive 22 in build 241, archive 0 in rev 211, see [Keyframes]).
 *
 * Sequences have two layouts. Settings 1-12 are the same in both; the rest moved in rev 226 (build 241 uses it):
 *  - rev 211: 13 = sounds by frame (u8 count, then a u24 per frame), 14 = skeletal keyframe set (int),
 *    15 = skeletal sounds (u16 count, then u16 frame + u24), 16 = skeletal range (u16 start, u16 end), 17 = bone masks.
 *  - build 241: 13 = skeletal keyframe set (int), 14 = sounds (u16 count, then u16 frame, u16 id, u8 weight, u8 loops,
 *    u8 location, u8 retain), 15 = skeletal range, 16 = vertical offset (s8), 17 = bone masks, 18 = debug name
 *    (string), 19 = sounds heard across world views (no data).
 * Read and written the way each client does it (the 241 table is the server's AnimationDefinitions.decodeRev226 and
 * the real 241 client's). [SeqConv] converts between the two.
 */
class SeqDef(val id: Int, val raw: ByteArray, val rev: Int = ConfigRev.R211) {
    /** Frame ids: (frame group << 16) | frame file. */
    var frameIds = IntArray(0)
    var delays = IntArray(0)
    var chatFrameIds: IntArray? = null
    /** Skeletal keyframe set ((group << 16) | file; opcode 14 in 211, 13 in 241), or -1 for a frame animation. */
    var skeletalModel = -1
    /**
     * Skeletal length: frames run 0 until (end - start), one client cycle each (opcode 16 in 211, 15 in 241). Both
     * clients take end - start as it is, so a skeletal animation without a range plays for 0 cycles ([Keyframes.lengthless]).
     */
    var skeletalStart = 0
    var skeletalEnd = 0
    /** Bones the animation drives when it plays over a walk/stand (opcode 17), or null. */
    var skeletalMasks: BooleanArray? = null
    var loopOffset = -1
    var maxLoops = 99
    var priority = 5
    var shield = -1
    var weapon = -1
    /** How many sound entries it has (either layout). */
    var soundCount = 0
    /** Build 241 only: how far the model is raised while it plays (opcode 16). */
    var verticalOffset = 0
    /** Build 241 only: Jagex's own name for it (opcode 18), or null. */
    var debugName: String? = null
    var readOk = true
    /** Where the end marker stops (after it); -1 if there was none. */
    var end = -1
    /** (opcode, start, end) of each setting in [raw], the end marker left out. */
    internal val parts = ArrayList<Triple<Int, Int, Int>>()

    val is241 get() = ConfigRev.is241(rev)
    val frameCount get() = frameIds.size
    /** Length in game ticks of 20 ms (client cycles). */
    val lengthCycles get() = delays.sum()

    override fun toString() = "$id" + if (skeletalModel != -1) "  (skeletal, ${skeletalEnd - skeletalStart} cycles)" else "  ($frameCount frames)"

    /**
     * The same definition with new frames/delays (and keyframe set), in its own layout ([rev]); every other setting
     * byte-for-byte as it was. Unchanged values give back [raw] exactly (with anything after its end marker).
     */
    fun encode(newFrames: IntArray, newDelays: IntArray, newChatFrames: IntArray? = chatFrameIds, newSkeletal: Int = skeletalModel): ByteArray {
        require(readOk) { "animation $id has settings this editor can't read, so it can't be rewritten" }
        require(newFrames.size == newDelays.size) { "every frame needs a delay" }
        // skeletal animations (and the odd empty one, written back unchanged) have no frame list
        require(newFrames.size in 1..65535 || (newFrames.isEmpty() && (skeletalModel != -1 || frameIds.isEmpty()))) { "an animation needs 1 to 65535 frames" }
        require(newChatFrames == null || newChatFrames.size <= 255) { "at most 255 chat-head frames" }
        val skeletalOp = if (is241) 13 else 14
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        var wroteFrames = false
        var wroteChat = false
        fun frames(hadIt: Boolean = false) {
            if (wroteFrames) return
            // skeletal animations keep an empty frame list where they had one
            if (newFrames.isEmpty() && !hadIt) return
            wroteFrames = true
            out.write(1); u16(newFrames.size)
            for (d in newDelays) u16(d)
            for (f in newFrames) u16(f and 0xFFFF)
            for (f in newFrames) u16(f ushr 16)
        }
        fun chat() {
            if (wroteChat || newChatFrames == null) return; wroteChat = true
            out.write(12); out.write(newChatFrames.size)
            for (f in newChatFrames) u16(f and 0xFFFF)
            for (f in newChatFrames) u16(f ushr 16)
        }
        for ((op, s, e) in parts) {
            when (op) {
                1 -> frames(true)
                12 -> chat()
                skeletalOp -> { out.write(skeletalOp); u16(newSkeletal ushr 16); u16(newSkeletal and 0xFFFF) }
                else -> out.write(raw, s, e - s)
            }
        }
        frames(); chat()
        out.write(0)
        val bytes = out.toByteArray()
        // nothing changed: the bytes as they were, including anything after the end marker (vanilla 211 has such)
        if (end in 1 until raw.size && bytes.size == end && raw.copyOf(end).contentEquals(bytes)) return raw
        return bytes
    }

    /**
     * The same definition with skeletal range [start]..[end] (opcode 15 in build 241, 16 in rev 211), put where the
     * opcode order has it (an old range is replaced); every other setting byte for byte as it was.
     */
    fun withRange(start: Int, end: Int): ByteArray {
        require(readOk) { "animation $id has settings this editor can't read, so it can't be rewritten" }
        require(start in 0..65535 && end in 0..65535) { "a skeletal range is two numbers 0-65535" }
        val rangeOp = if (is241) 15 else 16
        val out = ByteArrayOutputStream()
        var wrote = false
        fun range() {
            if (wrote) return; wrote = true
            out.write(rangeOp); out.write(start shr 8); out.write(start); out.write(end shr 8); out.write(end)
        }
        for ((op, s, e) in parts) {
            if (op == rangeOp) { range(); continue }
            if (op > rangeOp) range()
            out.write(raw, s, e - s)
        }
        range()
        out.write(0)
        return out.toByteArray()
    }

    companion object {
        /**
         * Reads a sequence in layout [rev] (211 or 241; default: the cache the editor has open). [readOk] is false
         * when it has a setting that layout doesn't have or runs past the end.
         */
        fun decode(id: Int, b: ByteArray, rev: Int = ConfigRev.current()): SeqDef {
            val d = SeqDef(id, b, rev)
            val r241 = ConfigRev.is241(rev)
            var p = 0
            fun u8() = b[p++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
            fun i32() = (u16() shl 16) or u16()
            fun skip(n: Int) { p += n; if (p > b.size) throw IndexOutOfBoundsException("past the end") }
            try {
                while (p < b.size) {
                    val start = p
                    val op = u8()
                    // the game stops at the first end marker; anything after it (padding, or bytes some packers leave) is ignored
                    if (op == 0) { d.end = p; break }
                    when {
                        op == 1 -> {
                            val n = u16()
                            d.delays = IntArray(n) { u16() }
                            d.frameIds = IntArray(n) { u16() }
                            for (i in 0 until n) d.frameIds[i] += u16() shl 16
                        }
                        op == 2 -> d.loopOffset = u16()
                        op == 3 -> skip(u8())
                        op == 4 -> {}
                        op == 5 -> d.priority = u8()
                        op == 6 -> d.shield = u16()
                        op == 7 -> d.weapon = u16()
                        op == 8 -> d.maxLoops = u8()
                        op == 9 || op == 10 || op == 11 -> u8()
                        op == 12 -> {
                            val n = u8()
                            val c = IntArray(n) { u16() }
                            for (i in 0 until n) c[i] += u16() shl 16
                            d.chatFrameIds = c
                        }
                        // ---- build 241 (rev 226+) ----
                        r241 && op == 13 -> d.skeletalModel = i32()
                        r241 && op == 14 -> { val n = u16(); skip(n * 8); d.soundCount = n }
                        r241 && op == 15 -> { d.skeletalStart = u16(); d.skeletalEnd = u16() }
                        r241 && op == 16 -> d.verticalOffset = b[p++].toInt()
                        r241 && op == 17 -> { val n = u8(); val m = BooleanArray(256); repeat(n) { m[u8()] = true }; d.skeletalMasks = m }
                        r241 && op == 18 -> { val s = p; while (b[p].toInt() != 0) p++; d.debugName = String(b, s, p - s, Charsets.ISO_8859_1); p++ }
                        r241 && op == 19 -> {}
                        // ---- rev 211 ----
                        !r241 && op == 13 -> { val n = u8(); skip(n * 3); d.soundCount = n }
                        !r241 && op == 14 -> d.skeletalModel = i32()
                        !r241 && op == 15 -> { val n = u16(); skip(n * 5); d.soundCount = n }
                        !r241 && op == 16 -> { d.skeletalStart = u16(); d.skeletalEnd = u16() }
                        !r241 && op == 17 -> { val n = u8(); val m = BooleanArray(256); repeat(n) { m[u8()] = true }; d.skeletalMasks = m }
                        else -> { d.readOk = false; return d }
                    }
                    if (p > b.size) throw IndexOutOfBoundsException("past the end")
                    d.parts.add(Triple(op, start, p))
                }
            } catch (e: Exception) { d.readOk = false }
            return d
        }

        /** [decode] in the layout of [lib]'s cache. */
        fun decode(lib: CacheLibrary, id: Int, b: ByteArray): SeqDef = decode(id, b, CacheIO.revision(lib))
    }
}

/**
 * Sequence layout conversion (see [SeqDef] for the two layouts). 211 -> 241 follows the cache converter
 * (rev241/tools/Conv.seq): sounds become 241 records with weight 100 (what most build-241 sounds use) and retain 0.
 * 241 -> 211 is the reverse; what 211 has no setting for (vertical offset, debug name, setting 19, sound weight and
 * retain, sounds on frames past 255) is left out and listed in [Result.dropped].
 */
object SeqConv {
    /** What [convert] made, and what it had to leave out (empty when nothing was lost). */
    class Result(val bytes: ByteArray, val dropped: List<String>)

    /** Weight of converted 211 sounds (Conv.seqWeight: what 4,183 of 4,500 build-241 sound entries use). */
    const val SOUND_WEIGHT = 100

    /** [b] (layout [from]) in layout [to]; the bytes as they are when both are the same. Throws on an unknown setting. */
    fun convert(id: Int, b: ByteArray, from: Int, to: Int): Result = when {
        ConfigRev.is241(from) == ConfigRev.is241(to) -> Result(b, emptyList())
        ConfigRev.is241(to) -> Result(to241(id, b), emptyList())
        else -> to211(id, b)
    }

    private class In(val b: ByteArray) {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
        fun i32() = (u16() shl 16) or u16()
        fun skip(n: Int) { p += n; if (p > b.size) throw IndexOutOfBoundsException("past the end") }
    }

    private class Out {
        val o = ByteArrayOutputStream()
        fun u8(v: Int) = o.write(v and 0xFF)
        fun u16(v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        fun u24(v: Int) { u8(v shr 16); u16(v and 0xFFFF) }
        fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun raw(b: ByteArray, from: Int, to: Int) = o.write(b, from, to - from)
    }

    /** Settings 1-12, which both layouts share: copies one (op already read) and returns true, or false if it isn't one. */
    private fun common(op: Int, i: In, o: Out, start: Int): Boolean {
        when (op) {
            1 -> i.skip(i.u16() * 6)
            2, 6, 7 -> i.skip(2)
            3 -> i.skip(i.u8())
            4 -> {}
            5, 8, 9, 10, 11 -> i.skip(1)
            12 -> i.skip(i.u8() * 4)
            else -> return false
        }
        o.raw(i.b, start, i.p)
        return true
    }

    /** Rev 211 -> build 241 (Conv.seq). */
    fun to241(id: Int, b: ByteArray): ByteArray {
        val i = In(b); val o = Out()
        val sounds = java.util.TreeMap<Int, IntArray>() // frame -> id, loops, location
        var maya = -1; var range: IntArray? = null; var masks: ByteArray? = null
        fun sound(frame: Int, v: Int) { if (v shr 8 >= 1) sounds[frame] = intArrayOf(v shr 8, v shr 4 and 7, v and 15) }
        while (i.p < b.size) {
            val start = i.p
            val op = i.u8()
            if (op == 0) break
            if (common(op, i, o, start)) continue
            when (op) {
                13 -> { val n = i.u8(); for (f in 0 until n) sound(f, i.u24()) }
                14 -> maya = i.i32()
                15 -> { val n = i.u16(); repeat(n) { val f = i.u16(); sound(f, i.u24()) } }
                16 -> range = intArrayOf(i.u16(), i.u16())
                17 -> { val s = i.p; i.skip(i.u8()); masks = b.copyOfRange(s, i.p) }
                else -> throw IllegalStateException("animation $id: setting $op isn't a rev 211 animation setting")
            }
        }
        if (maya != -1) { o.u8(13); o.i32(maya) }
        if (sounds.isNotEmpty()) {
            o.u8(14); o.u16(sounds.size)
            for ((f, s) in sounds) { o.u16(f); o.u16(s[0]); o.u8(SOUND_WEIGHT); o.u8(s[1]); o.u8(s[2]); o.u8(0) }
        }
        range?.let { o.u8(15); o.u16(it[0]); o.u16(it[1]) }
        masks?.let { o.u8(17); o.raw(it, 0, it.size) }
        o.u8(0)
        return o.o.toByteArray()
    }

    /** Build 241 -> rev 211: skeletal 13 -> 14, range 15 -> 16, sounds 14 -> 13 (by frame) or 15 (skeletal). */
    fun to211(id: Int, b: ByteArray): Result {
        val i = In(b); val o = Out()
        val dropped = LinkedHashSet<String>()
        var frames = -1; var skeletal = false
        while (i.p < b.size) {
            val start = i.p
            val op = i.u8()
            if (op == 0) break
            if (op == 1) frames = ((b[i.p].toInt() and 0xFF) shl 8) or (b[i.p + 1].toInt() and 0xFF)
            if (common(op, i, o, start)) continue
            when (op) {
                13 -> { o.u8(14); o.i32(i.i32()); skeletal = true }
                14 -> {
                    val n = i.u16()
                    val list = ArrayList<IntArray>() // frame, packed 211 sound
                    repeat(n) {
                        val f = i.u16(); val sid = i.u16(); val weight = i.u8(); val loops = i.u8(); val loc = i.u8(); val retain = i.u8()
                        if (weight != SOUND_WEIGHT) dropped.add("sound weights")
                        if (retain != 0) dropped.add("sound retain flags")
                        if (loops > 7 || loc > 15 || sid > 0xFFFF) dropped.add("sound settings 211 can't hold")
                        if (sid in 1..0xFFFF) list.add(intArrayOf(f, (sid shl 8) or ((loops and 7) shl 4) or (loc and 15)))
                    }
                    if (skeletal) {
                        o.u8(15); o.u16(list.size)
                        for (s in list) { o.u16(s[0]); o.u24(s[1]) }
                    } else {
                        // 211 keeps one sound per frame (a u8 count): as many as there are frames, or up to the last sound
                        val count = minOf(maxOf(frames, list.maxOfOrNull { it[0] + 1 } ?: 0), 255)
                        val per = IntArray(count)
                        for (s in list) if (s[0] < count) per[s[0]] = s[1] else dropped.add("sounds on frames 211 can't hold")
                        if (count > 0) { o.u8(13); o.u8(count); for (v in per) o.u24(v) }
                    }
                }
                15 -> { o.u8(16); o.u16(i.u16()); o.u16(i.u16()) }
                16 -> { if (b[i.p].toInt() != 0) dropped.add("vertical offset"); i.skip(1) }
                17 -> { val s = i.p; i.skip(i.u8()); o.u8(17); o.raw(b, s, i.p) }
                18 -> { while (b[i.p].toInt() != 0) i.p++; i.p++ }
                19 -> dropped.add("sounds across world views")
                else -> throw IllegalStateException("animation $id: setting $op isn't a build 241 animation setting")
            }
        }
        o.u8(0)
        return Result(o.o.toByteArray(), dropped.toList())
    }
}

class Skeleton(val id: Int, val raw: ByteArray) {
    val count: Int
    val types: IntArray
    val labels: Array<IntArray>
    init {
        var p = 0
        fun u8() = raw[p++].toInt() and 0xFF
        count = u8()
        types = IntArray(count) { u8() }
        val sizes = IntArray(count) { u8() }
        labels = Array(count) { i -> IntArray(sizes[i]) { u8() } }
    }
}

/** One animation frame: which skeleton groups move, and by how much. */
class Frame(val raw: ByteArray, val skeleton: Skeleton) {
    val skeletonId get() = ((raw[0].toInt() and 255) shl 8) or (raw[1].toInt() and 255)
    val groups: IntArray
    val xs: IntArray
    val ys: IntArray
    val zs: IntArray
    init {
        val g = ArrayList<Int>(); val x = ArrayList<Int>(); val y = ArrayList<Int>(); val z = ArrayList<Int>()
        var p = 2
        val n = raw[p++].toInt() and 0xFF
        var q = p + n
        fun smart(): Int {
            val v = raw[q].toInt() and 0xFF
            return if (v < 128) { q++; v - 64 } else { val r = ((raw[q].toInt() and 0xFF) shl 8 or (raw[q + 1].toInt() and 0xFF)) - 49152; q += 2; r }
        }
        var last = -1
        for (i in 0 until n) {
            val f = raw[p++].toInt() and 0xFF
            if (f <= 0) continue
            if (skeleton.types[i] != 0) {
                // the game puts back the origin this group turns around, if the frame skipped it
                var j = i - 1
                while (j > last) {
                    if (skeleton.types[j] == 0) { g.add(j); x.add(0); y.add(0); z.add(0); break }
                    j--
                }
            }
            g.add(i)
            val def = if (skeleton.types[i] == 3) 128 else 0
            x.add(if (f and 1 != 0) smart() else def)
            y.add(if (f and 2 != 0) smart() else def)
            z.add(if (f and 4 != 0) smart() else def)
            last = i
        }
        groups = g.toIntArray(); xs = x.toIntArray(); ys = y.toIntArray(); zs = z.toIntArray()
    }
}

/** Puts a model in the pose of a frame (the game's own transform), on copies of its vertex positions. */
class Animator(private val baseX: IntArray, private val baseY: IntArray, private val baseZ: IntArray, vertexSkins: IntArray?) {
    val x = baseX.clone(); val y = baseY.clone(); val z = baseZ.clone()
    private val groups: Array<IntArray>
    val animatable get() = groups.isNotEmpty()

    init {
        if (vertexSkins == null) groups = emptyArray()
        else {
            val max = vertexSkins.maxOrNull() ?: 0
            val lists = Array(max + 1) { ArrayList<Int>() }
            for (v in vertexSkins.indices) lists[vertexSkins[v]].add(v)
            groups = Array(max + 1) { lists[it].toIntArray() }
        }
    }

    private var tx = 0; private var ty = 0; private var tz = 0

    fun pose(frame: Frame?) {
        System.arraycopy(baseX, 0, x, 0, x.size); System.arraycopy(baseY, 0, y, 0, y.size); System.arraycopy(baseZ, 0, z, 0, z.size)
        if (frame == null || groups.isEmpty()) return
        tx = 0; ty = 0; tz = 0
        for (i in frame.groups.indices) {
            val g = frame.groups[i]
            transform(frame.skeleton.types[g], frame.skeleton.labels[g], frame.xs[i], frame.ys[i], frame.zs[i])
        }
    }

    private fun transform(type: Int, labels: IntArray, dx: Int, dy: Int, dz: Int) {
        when (type) {
            0 -> {
                var n = 0; var sx = 0; var sy = 0; var sz = 0
                for (l in labels) if (l < groups.size) for (v in groups[l]) { sx += x[v]; sy += y[v]; sz += z[v]; n++ }
                if (n > 0) { tx = dx + sx / n; ty = dy + sy / n; tz = dz + sz / n } else { tx = dx; ty = dy; tz = dz }
            }
            1 -> for (l in labels) if (l < groups.size) for (v in groups[l]) { x[v] += dx; y[v] += dy; z[v] += dz }
            2 -> for (l in labels) if (l < groups.size) for (v in groups[l]) {
                x[v] -= tx; y[v] -= ty; z[v] -= tz
                val rx = (dx and 255) * 8; val ry = (dy and 255) * 8; val rz = (dz and 255) * 8
                if (rz != 0) { val s = SIN[rz]; val c = COS[rz]; val t = s * y[v] + c * x[v] shr 16; y[v] = c * y[v] - s * x[v] shr 16; x[v] = t }
                if (rx != 0) { val s = SIN[rx]; val c = COS[rx]; val t = c * y[v] - s * z[v] shr 16; z[v] = s * y[v] + c * z[v] shr 16; y[v] = t }
                if (ry != 0) { val s = SIN[ry]; val c = COS[ry]; val t = s * z[v] + c * x[v] shr 16; z[v] = c * z[v] - s * x[v] shr 16; x[v] = t }
                x[v] += tx; y[v] += ty; z[v] += tz
            }
            3 -> for (l in labels) if (l < groups.size) for (v in groups[l]) {
                x[v] -= tx; y[v] -= ty; z[v] -= tz
                x[v] = dx * x[v] / 128; y[v] = dy * y[v] / 128; z[v] = dz * z[v] / 128
                x[v] += tx; y[v] += ty; z[v] += tz
            }
            // 5 = transparency: not shown in the preview
        }
    }

    companion object {
        val SIN = IntArray(2048) { (65536.0 * Math.sin(it * 0.0030679615)).toInt() }
        val COS = IntArray(2048) { (65536.0 * Math.cos(it * 0.0030679615)).toInt() }
    }
}

/** Reads sequences, frames, skeletons and keyframe sets from one cache folder, in that cache's format. */
class AnimCache(private val lib: CacheLibrary) {
    /** The cache's format (211 or 241): which sequence layout it uses and where its keyframe sets are. */
    val rev: Int = CacheIO.revision(lib)
    private val skeletons = HashMap<Int, Skeleton?>()
    private val frameGroups = HashMap<Int, Map<Int, Frame>?>()

    /** The animations read straight from the files, when the cache library can't (see [BigArchive]). */
    private val bigSeqs: Map<Int, ByteArray>? by lazy {
        if (!BigArchive.animationsNeedRepair(lib)) null
        else BigArchive.readFiles(lib, 2, 12)?.mapValues { (id, b) -> BigArchive.trimSeq(id, b, rev) }
    }
    /** True when the animations had to be read the slow way; saving fixes the archive. */
    val needsRepair get() = bigSeqs != null

    fun seqIds(): List<Int> = lib.index(2).archive(12)?.fileIds()?.sorted() ?: emptyList()
    fun seqData(id: Int): ByteArray? = bigSeqs?.get(id) ?: lib.data(2, 12, id)?.takeIf { it.isNotEmpty() }
    fun seq(id: Int): SeqDef? = seqData(id)?.let { SeqDef.decode(id, it, rev) }

    fun skeleton(id: Int): Skeleton? = skeletons.getOrPut(id) { lib.data(1, id, 0)?.let { Skeleton(id, it) } }

    /** All frames of a frame group, by file id. */
    fun frames(group: Int): Map<Int, Frame>? = frameGroups.getOrPut(group) {
        val arch = lib.index(0).archive(group) ?: return@getOrPut null
        val out = HashMap<Int, Frame>()
        for (f in arch.fileIds()) {
            val b = lib.data(0, group, f) ?: continue
            if (b.size < 3) continue
            val skel = skeleton(((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)) ?: continue
            try { out[f] = Frame(b, skel) } catch (e: Exception) {}
        }
        lib.index(0).unCache()
        out
    }

    fun frame(frameId: Int): Frame? = frames(frameId ushr 16)?.get(frameId and 0xFFFF)

    /**
     * A skeletal animation's keyframe set (archive 22 in build 241, archive 0 in rev 211; a version byte, then the
     * skeleton id), or null. In a 241 cache a set that is only in archive 0 (left there by a 211 -> 241 merge, see
     * [Keyframes.stranded]) is read from there too, so it can be looked at and exported; [keyframeArchive] says where
     * it was found.
     */
    fun keyframeData(group: Int, file: Int): ByteArray? = Keyframes.read(lib, group, file, unCache = true)

    /** Where keyframe set (group, file) is: [CacheIO.KEYFRAMES], [CacheIO.FRAMES] (a 211 cache, or stranded), or null. */
    fun keyframeArchive(group: Int, file: Int): Int? = Keyframes.locate(lib, group, file)

    fun skeletonData(id: Int): ByteArray? = lib.data(1, id, 0)
}

/**
 * Skeletal keyframe sets ("animayas"): archive 22 in build 241, archive 0 (next to the frame sets) in rev 211; group
 * = keyframe id >> 16, file = keyframe id & 0xFFFF. The bytes are the same in both (version byte, u16 skeleton, ...).
 */
object Keyframes {
    /** The archive the game reads keyframe sets from in [lib]'s format. */
    fun archive(lib: CacheLibrary): Int = CacheIO.keyframeArchive(lib)

    /** The skeleton (archive 1 group) a keyframe set moves, or null. */
    fun skeletonOf(b: ByteArray): Int? = if (b.size >= 3) ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255) else null

    /** True if [lib]'s [archive] lists (group, file), without reading it. */
    fun has(lib: CacheLibrary, archive: Int, group: Int, file: Int): Boolean =
        lib.exists(archive) && lib.index(archive).archive(group, true)?.file(file) != null

    /**
     * (group, file) of [archive], or null. [unCache] lets the library drop what it read (archive 0 is big); never
     * on a cache with writes that aren't saved yet ([com.displee.cache.index.Index.unCache] drops those too).
     */
    private fun data(lib: CacheLibrary, archive: Int, group: Int, file: Int, unCache: Boolean = false): ByteArray? {
        if (!has(lib, archive, group, file)) return null
        return try { lib.data(archive, group, file)?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }
        finally { if (unCache) lib.index(archive).unCache() }
    }

    /** True if [b] reads as a keyframe set whose skeleton is in [lib] (so it isn't a frame file sitting at that number). */
    fun looksLikeKeyframes(lib: CacheLibrary, b: ByteArray): Boolean = try {
        SkeletalAnims.KeyframeSet(b) { lib.data(1, it, 0) }; true
    } catch (e: Exception) { false }

    /** Where (group, file) is (see [AnimCache.keyframeArchive]). */
    fun locate(lib: CacheLibrary, group: Int, file: Int): Int? {
        val a = archive(lib)
        if (has(lib, a, group, file)) return a
        if (a != CacheIO.FRAMES) data(lib, CacheIO.FRAMES, group, file)?.takeIf { looksLikeKeyframes(lib, it) }?.let { return CacheIO.FRAMES }
        return null
    }

    /** Keyframe set (group, file) from where the game reads it, else (241 only) a stranded copy in archive 0. */
    fun read(lib: CacheLibrary, group: Int, file: Int, unCache: Boolean = false): ByteArray? {
        val a = archive(lib)
        data(lib, a, group, file, unCache)?.let { return it }
        if (a == CacheIO.FRAMES) return null
        return data(lib, CacheIO.FRAMES, group, file, unCache)?.takeIf { looksLikeKeyframes(lib, it) }
    }

    /**
     * Build 241 caches only: keyframe sets skeletal animations use that aren't in archive 22 but are in archive 0 (a
     * 211 -> 241 merge that copied the 211 archive 0 and not these; the game finds nothing and the animation doesn't
     * play), keyed by keyframe id. Empty for a 211 cache. Moving them ([moveStranded]) isn't always enough: the same
     * merge left many of these animations without a length too (see [lengthless]).
     */
    fun stranded(lib: CacheLibrary): Map<Int, ByteArray> {
        if (!CacheIO.is241(lib)) return emptyMap()
        val anims = AnimCache(lib)
        val keys = java.util.TreeSet<Int>()
        for (id in anims.seqIds()) {
            val s = try { anims.seq(id) } catch (e: Exception) { null } ?: continue
            if (s.readOk && s.skeletalModel != -1) keys.add(s.skeletalModel)
        }
        val out = java.util.TreeMap<Int, ByteArray>()
        for (k in keys) {
            val g = k ushr 16; val f = k and 0xFFFF
            if (has(lib, CacheIO.KEYFRAMES, g, f)) continue
            val b = data(lib, CacheIO.FRAMES, g, f) ?: continue
            if (looksLikeKeyframes(lib, b)) out[k] = b
        }
        return out
    }

    /**
     * Copies [stranded] keyframe sets into archive 22 under the same numbers (the animations already point there), so
     * the game finds them. That alone doesn't make an animation play: it needs a length too ([lengthless],
     * [fixLengths]). Archive 0 is left as it is. Returns a note, or "" if there was nothing to copy.
     */
    fun moveStranded(lib: CacheLibrary): String {
        val s = stranded(lib)
        if (s.isEmpty()) return ""
        require(lib.exists(CacheIO.KEYFRAMES)) { "the cache has no keyframe archive (22)" }
        for ((k, b) in s) lib.put(CacheIO.KEYFRAMES, k ushr 16, k and 0xFFFF, b)
        lib.index(CacheIO.KEYFRAMES).update()
        return "Copied ${s.size} skeletal keyframe set(s) the game couldn't find (they were only in archive 0, where " +
                "build 241 doesn't look) into archive 22. "
    }

    /**
     * The range a keyframe set's header holds (u16 start, u16 end after the version byte and the skeleton; the
     * client reads and ignores them), or null when end <= start. Jagex writes the sequence's range there too: for all
     * 944 skeletal animations of stock build 241 the sequence's range (setting 15) is exactly this.
     */
    fun headerRange(b: ByteArray): IntArray? {
        if (b.size < 7) return null
        val start = ((b[3].toInt() and 255) shl 8) or (b[4].toInt() and 255)
        val end = ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
        return if (end > start) intArrayOf(start, end) else null
    }

    /**
     * Skeletal animations without a length: no skeletal range (setting 15 in build 241, 16 in rev 211) or end <= start.
     * Both clients play a skeletal animation for end - start cycles with no fallback (241: oh.ch(); 211:
     * SequenceDefinition.method4055), so these play nothing even when the game finds their keyframes. Keyed by
     * animation id: the range their keyframe set holds ([headerRange]), or null when it holds none or the keyframes
     * are missing. [ids]: only these animations (default: all).
     *
     * A 211 -> 241 merge left 289 in cacheU (e.g. 10174-10181, the Duke), all among the 549 with [stranded]
     * keyframes (audit BOSS-X1/X2); the user's 211 cache has the same 289; stock 241 and vanilla 211 have none. For
     * all 289 the keyframe set's range is the stock build 241 sequence's range.
     */
    fun lengthless(lib: CacheLibrary, anims: AnimCache = AnimCache(lib), ids: Collection<Int>? = null): Map<Int, IntArray?> {
        val out = java.util.TreeMap<Int, IntArray?>()
        for (id in ids?.toSortedSet() ?: anims.seqIds()) {
            val s = try { anims.seq(id) } catch (e: Exception) { null } ?: continue
            if (!s.readOk || s.skeletalModel == -1 || s.skeletalEnd > s.skeletalStart) continue
            val k = s.skeletalModel
            out[id] = read(lib, k ushr 16, k and 0xFFFF)?.let { headerRange(it) }
        }
        return out
    }

    /**
     * Gives each [lengthless] animation whose keyframe set holds a range that range (what the stock record has, see
     * [lengthless]); every other setting stays byte for byte. Returns a note ("" if there was nothing to do) that
     * also lists the ones it couldn't give a length: they need their stock record (audit BOSS-X1). [ids]: only these
     * animations (default: all). Writes nothing while the animations archive needs [BigArchive] repair (see
     * [CacheEdits.repairAnimationsIfNeeded]).
     */
    fun fixLengths(lib: CacheLibrary, ids: Collection<Int>? = null): String {
        val anims = AnimCache(lib)
        val todo = lengthless(lib, anims, ids)
        if (todo.isEmpty()) return ""
        if (anims.needsRepair) return "${todo.size} skeletal animation(s) have no length, so the game plays them for 0 " +
                "cycles (${idList(todo.keys)}); importing or saving an animation gives them one. "
        val fixed = ArrayList<Int>()
        for ((id, r) in todo) {
            if (r == null) continue
            val s = anims.seq(id) ?: continue
            val out = s.withRange(r[0], r[1])
            val back = SeqDef.decode(id, out, anims.rev)
            if (!back.readOk || back.skeletalModel != s.skeletalModel || back.skeletalStart != r[0] || back.skeletalEnd != r[1] ||
                !back.frameIds.contentEquals(s.frameIds) || back.soundCount != s.soundCount) continue
            lib.put(2, 12, id, out)
            fixed.add(id)
        }
        if (fixed.isNotEmpty()) lib.index(2).update()
        val stuck = todo.keys - fixed.toSet()
        return (if (fixed.isEmpty()) "" else "Gave ${fixed.size} skeletal animation(s) the length their keyframes hold " +
                "(${idList(fixed)}): they had none, so the game played them for 0 cycles. ") +
                (if (stuck.isEmpty()) "" else "${stuck.size} skeletal animation(s) still have no length, so the game " +
                        "plays them for 0 cycles (${idList(stuck)}): their keyframes don't say how long they are (or are " +
                        "missing). They need their stock record (audit BOSS-X1: copy them from a stock cache of this format). ")
    }

    /** "10174-10181, 10230" with at most [max] runs, then "and N more". */
    fun idList(ids: Collection<Int>, max: Int = 12): String {
        val runs = ArrayList<IntRange>()
        for (id in ids.toSortedSet()) {
            if (runs.isNotEmpty() && runs.last().last == id - 1) runs[runs.size - 1] = runs.last().first..id else runs.add(id..id)
        }
        val shown = runs.take(max)
        val text = shown.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}" }
        val rest = runs.drop(max).sumOf { it.last - it.first + 1 }
        return if (rest > 0) "$text and $rest more" else text
    }
}
