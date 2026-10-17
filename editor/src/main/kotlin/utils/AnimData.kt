package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Animations in the 211 cache: sequences (config archive 12), their frames (index 0, one group per frame set)
 * and skeletons (index 1). Read and written the way the 211 client does it.
 */
class SeqDef(val id: Int, val raw: ByteArray) {
    /** Frame ids: (frame group << 16) | frame file. */
    var frameIds = IntArray(0)
    var delays = IntArray(0)
    var chatFrameIds: IntArray? = null
    /** Uses the newer skeletal format (opcode 14), which this editor can't play. */
    var skeletalModel = -1
    var loopOffset = -1
    var maxLoops = 99
    var priority = 5
    var shield = -1
    var weapon = -1
    var readOk = true
    /** Where the end marker stops (after it); -1 if there was none. */
    var end = -1
    /** (opcode, start, end) of each setting in [raw], the end marker left out. */
    internal val parts = ArrayList<Triple<Int, Int, Int>>()

    val frameCount get() = frameIds.size
    /** Length in game ticks of 20 ms (client cycles). */
    val lengthCycles get() = delays.sum()

    override fun toString() = "$id" + if (skeletalModel != -1) "  (skeletal)" else "  ($frameCount frames)"

    /** The same definition with new frames/delays; every other setting byte-for-byte as it was. */
    fun encode(newFrames: IntArray, newDelays: IntArray, newChatFrames: IntArray? = chatFrameIds): ByteArray {
        require(newFrames.size == newDelays.size) { "every frame needs a delay" }
        require(newFrames.size in 1..65535) { "an animation needs 1 to 65535 frames" }
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        var wroteFrames = false
        var wroteChat = false
        fun frames() {
            if (wroteFrames) return; wroteFrames = true
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
                1 -> frames()
                12 -> chat()
                else -> out.write(raw, s, e - s)
            }
        }
        frames(); chat()
        out.write(0)
        return out.toByteArray()
    }

    companion object {
        fun decode(id: Int, b: ByteArray): SeqDef {
            val d = SeqDef(id, b)
            var p = 0
            fun u8() = b[p++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
            try {
                while (p < b.size) {
                    val start = p
                    val op = u8()
                    // the game stops at the first end marker; anything after it (padding, or bytes some packers leave) is ignored
                    if (op == 0) { d.end = p; break }
                    when (op) {
                        1 -> {
                            val n = u16()
                            d.delays = IntArray(n) { u16() }
                            d.frameIds = IntArray(n) { u16() }
                            for (i in 0 until n) d.frameIds[i] += u16() shl 16
                        }
                        2 -> d.loopOffset = u16()
                        3 -> { val n = u8(); p += n }
                        4 -> {}
                        5 -> d.priority = u8()
                        6 -> d.shield = u16()
                        7 -> d.weapon = u16()
                        8 -> d.maxLoops = u8()
                        9, 10, 11 -> u8()
                        12 -> {
                            val n = u8()
                            val c = IntArray(n) { u16() }
                            for (i in 0 until n) c[i] += u16() shl 16
                            d.chatFrameIds = c
                        }
                        13 -> { val n = u8(); repeat(n) { u24() } }
                        14 -> { d.skeletalModel = (u16() shl 16) or u16() }
                        15 -> { val n = u16(); repeat(n) { u16(); u24() } }
                        16 -> { u16(); u16() }
                        17 -> { val n = u8(); p += n }
                        else -> { d.readOk = false; return d }
                    }
                    d.parts.add(Triple(op, start, p))
                }
            } catch (e: Exception) { d.readOk = false }
            return d
        }
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
            val max = vertexSkins.max() ?: 0
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

/** Reads sequences, frames and skeletons from one cache folder. */
class AnimCache(private val lib: CacheLibrary) {
    private val skeletons = HashMap<Int, Skeleton?>()
    private val frameGroups = HashMap<Int, Map<Int, Frame>?>()

    /** The animations read straight from the files, when the cache library can't (see [BigArchive]). */
    private val bigSeqs: Map<Int, ByteArray>? by lazy {
        if (!BigArchive.animationsNeedRepair(lib)) null
        else BigArchive.readFiles(lib, 2, 12)?.mapValues { (id, b) -> BigArchive.trimSeq(id, b) }
    }
    /** True when the animations had to be read the slow way; saving fixes the archive. */
    val needsRepair get() = bigSeqs != null

    fun seqIds(): List<Int> = lib.index(2).archive(12)?.fileIds()?.sorted() ?: emptyList()
    fun seqData(id: Int): ByteArray? = bigSeqs?.get(id) ?: lib.data(2, 12, id)?.takeIf { it.isNotEmpty() }
    fun seq(id: Int): SeqDef? = seqData(id)?.let { SeqDef.decode(id, it) }

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
}
