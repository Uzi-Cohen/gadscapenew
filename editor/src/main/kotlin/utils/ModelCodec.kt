package utils

import cache.definitions.ModelDefinition
import cache.utils.ColorPalette
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Writing models: the game's old model format (which the 211 client still reads), OBJ export with the game's
 * colours, and OBJ import. The OBJ import is the same conversion as Mallyyy's rsps-cache-viewer, ported to this cache.
 */
object ModelCodec {
    /** hsl (16 bit) -> rgb, the game's colour table at its normal brightness. */
    val palette: IntArray by lazy { ColorPalette(0.8, 0, 512).colorPalette }

    private val nearestCache = HashMap<Int, Int>()

    /** The game colour (hsl) that looks closest to [rgb]. */
    fun nearestHsl(rgb: Int): Int = synchronized(nearestCache) {
        nearestCache.getOrPut(rgb and 0xFFFFFF) {
            val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
            var best = 0; var bestD = Long.MAX_VALUE
            for (hsl in 0 until 65536) {
                val c = palette[hsl]
                val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
                val d = (dr * dr * 3 + dg * dg * 4 + db * db * 2).toLong()
                if (d < bestD) { bestD = d; best = hsl; if (d == 0L) break }
            }
            best
        }
    }

    class EncodeResult(val data: ByteArray, val warnings: List<String>)

    /**
     * [md] in the old model format. Keeps vertices, faces, colours, textures (planar), render types, priorities,
     * transparency and the animation labels, so animations still work on it.
     */
    fun encodeOld(md: ModelDefinition): EncodeResult {
        val warnings = ArrayList<String>()
        val vc = md.vertexCount
        val fc = md.faceCount
        require(vc in 1..65535 && fc in 1..65535) { "the model is empty or too big ($vc vertices, $fc faces)" }

        // textures: faces without a mapping triangle get one made of their own corners (what the game does anyway)
        val texA = ArrayList<Int>(); val texB = ArrayList<Int>(); val texC = ArrayList<Int>()
        for (i in 0 until md.textureTriangleCount) {
            texA.add(md.textureTriangleVertexIndices1!![i].toInt() and 0xFFFF)
            texB.add(md.textureTriangleVertexIndices2!![i].toInt() and 0xFFFF)
            texC.add(md.textureTriangleVertexIndices3!![i].toInt() and 0xFFFF)
            val type = md.textureRenderTypes?.getOrNull(i)?.toInt() ?: 0
            if (type != 0 && warnings.none { it.startsWith("Some textures") })
                warnings.add("Some textures are wrapped round the model (cylinder/cube mapping); the old format can only lay them flat.")
        }
        val faceTex = IntArray(fc) { md.faceTextures?.get(it)?.toInt() ?: -1 }
        val faceCoord = IntArray(fc) { i -> if (faceTex[i] == -1) -1 else (md.textureCoordinates?.get(i)?.toInt() ?: -1).let { if (it == -1) -1 else it and 0xFF } }
        val made = HashMap<Long, Int>()
        for (i in 0 until fc) if (faceTex[i] != -1 && faceCoord[i] == -1) {
            val a = md.faceVertexIndices1!![i]; val b = md.faceVertexIndices2!![i]; val c = md.faceVertexIndices3!![i]
            faceCoord[i] = made.getOrPut((a.toLong() shl 40) or (b.toLong() shl 20) or c.toLong()) {
                texA.add(a); texB.add(b); texC.add(c); texA.size - 1
            }
        }
        require(texA.size <= 63 || faceTex.all { it == -1 }) { "the model uses ${texA.size} texture mappings, the old format holds 63" }
        val textured = faceTex.any { it != -1 } || md.faceRenderTypes?.any { it.toInt() and 1 != 0 } == true
        val vSkins = md.vertexSkins
        val fSkins = md.faceSkins
        if (vSkins != null) require(vSkins.all { it in 0..255 }) { "animation labels above 255 don't fit the old format" }
        if (fSkins != null) require(fSkins.all { it in 0..255 }) { "transparency labels above 255 don't fit the old format" }
        val priorities = md.faceRenderPriorities

        val flags = ByteArrayOutputStream(); val xs = ByteArrayOutputStream(); val ys = ByteArrayOutputStream(); val zs = ByteArrayOutputStream()
        var px = 0; var py = 0; var pz = 0
        for (v in 0 until vc) {
            val x = md.vertexPositionsX[v]; val y = md.vertexPositionsY[v]; val z = md.vertexPositionsZ[v]
            var f = 0
            if (x != px) { f = f or 1; smart(xs, x - px) }
            if (y != py) { f = f or 2; smart(ys, y - py) }
            if (z != pz) { f = f or 4; smart(zs, z - pz) }
            flags.write(f); px = x; py = y; pz = z
        }
        val kinds = ByteArrayOutputStream(); val fdata = ByteArrayOutputStream(); val colours = ByteArrayOutputStream()
        val alphas = ByteArrayOutputStream(); val pri = ByteArrayOutputStream(); val texFlags = ByteArrayOutputStream()
        val fsk = ByteArrayOutputStream(); val vsk = ByteArrayOutputStream()
        var last = 0
        for (i in 0 until fc) {
            kinds.write(1)
            for (idx in intArrayOf(md.faceVertexIndices1!![i], md.faceVertexIndices2!![i], md.faceVertexIndices3!![i])) { smart(fdata, idx - last); last = idx }
            val colour = if (faceTex[i] != -1) faceTex[i] else (md.faceColors?.get(i)?.toInt() ?: 0)
            colours.write(colour shr 8); colours.write(colour)
            if (textured) {
                var t = (md.faceRenderTypes?.get(i)?.toInt() ?: 0) and 1
                if (faceTex[i] != -1) t = t or 2 or (faceCoord[i] shl 2)
                texFlags.write(t)
            }
            if (priorities != null) pri.write(priorities[i].toInt())
            alphas.write(md.faceAlphas?.get(i)?.toInt() ?: 0)
            if (fSkins != null) fsk.write(fSkins[i])
        }
        if (vSkins != null) for (v in 0 until vc) vsk.write(vSkins[v])
        val tex = ByteArrayOutputStream()
        for (i in texA.indices) { u16(tex, texA[i]); u16(tex, texB[i]); u16(tex, texC[i]) }

        val anyAlpha = md.faceAlphas?.any { it.toInt() != 0 } == true
        val out = ByteArrayOutputStream()
        // same order the game reads them in
        out.write(flags.toByteArray())
        out.write(kinds.toByteArray())
        if (priorities != null) out.write(pri.toByteArray())
        if (fSkins != null) out.write(fsk.toByteArray())
        if (textured) out.write(texFlags.toByteArray())
        if (vSkins != null) out.write(vsk.toByteArray())
        if (anyAlpha) out.write(alphas.toByteArray())
        out.write(fdata.toByteArray())
        out.write(colours.toByteArray())
        if (textured) out.write(tex.toByteArray())
        out.write(xs.toByteArray()); out.write(ys.toByteArray()); out.write(zs.toByteArray())
        u16(out, vc); u16(out, fc)
        out.write(if (textured) texA.size else 0)
        out.write(if (textured) 1 else 0)
        out.write(if (priorities != null) 255 else md.priority.toInt() and 255)
        out.write(if (anyAlpha) 1 else 0)
        out.write(if (fSkins != null) 1 else 0)
        out.write(if (vSkins != null) 1 else 0)
        u16(out, xs.size()); u16(out, ys.size()); u16(out, zs.size()); u16(out, fdata.size())
        val bytes = out.toByteArray()
        // the game tells formats apart by the last two bytes; an old-format model must not look like a newer one
        require(!(bytes[bytes.size - 2].toInt() == -1 && bytes[bytes.size - 1].toInt() in -3..-1)) { "the model is too big for the old format" }
        return EncodeResult(bytes, warnings)
    }

    /** The model as OBJ text, in game units (a tile is 128), upright, with the game's colours per vertex. */
    fun toObj(md: ModelDefinition, name: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(name).append(", exported by Uzi's Map Editor\n")
        // a vertex per face corner so each face keeps its own colour
        var n = 0
        val faces = StringBuilder()
        for (i in 0 until md.faceCount) {
            val c = md.faceColors?.get(i)?.toInt() ?: 0
            if ((c and 0xFFFF) == 65535 && (md.faceTextures?.get(i)?.toInt() ?: -1) == -1) continue // never drawn
            if ((md.faceRenderTypes?.get(i)?.toInt() ?: 0) == 2 && md.faceTextures == null) continue
            val rgb = palette[c and 0xFFFF]
            val r = (rgb shr 16 and 255) / 255.0; val g = (rgb shr 8 and 255) / 255.0; val b = (rgb and 255) / 255.0
            for (v in intArrayOf(md.faceVertexIndices1!![i], md.faceVertexIndices2!![i], md.faceVertexIndices3!![i])) {
                sb.append(String.format(Locale.ROOT, "v %d %d %d %.4f %.4f %.4f\n",
                    md.vertexPositionsX[v], -md.vertexPositionsY[v], -md.vertexPositionsZ[v], r, g, b))
            }
            faces.append("f ").append(n + 1).append(' ').append(n + 2).append(' ').append(n + 3).append('\n')
            n += 3
        }
        sb.append(faces)
        return sb.toString()
    }

    class ObjResult(val data: ByteArray, val vertices: Int, val faces: Int, val dropped: Int, val colours: Int)

    /** An OBJ (+ MTL for colours) as a game model. [flip] = the same axes as the OBJ export (and Blender's default). */
    fun fromObj(objBytes: ByteArray, mtlBytes: ByteArray?, scale: Double, flip: Boolean): ObjResult {
        require(scale > 0 && scale <= 100000) { "the scale must be a positive number" }
        val mats = if (mtlBytes == null) HashMap() else parseMtl(mtlBytes)
        val pos = ArrayList<DoubleArray>(); val vcol = ArrayList<DoubleArray?>()
        val tris = ArrayList<IntArray>() // a, b, c, rgb, alpha
        var cur: DoubleArray? = null // r, g, b, a
        val lines = String(objBytes, Charsets.UTF_8).split(Regex("\\r?\\n"))
        lineLoop@ for ((n, raw) in lines.withIndex()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue@lineLoop
            val t = line.split(Regex("\\s+"))
            when (t[0]) {
                "v" -> {
                    require(t.size >= 4) { "line ${n + 1}: a vertex needs x y z" }
                    pos.add(doubleArrayOf(num(t[1], n), num(t[2], n), num(t[3], n)))
                    vcol.add(if (t.size >= 7) doubleArrayOf(num(t[4], n), num(t[5], n), num(t[6], n)) else null)
                }
                "usemtl" -> cur = if (t.size > 1) mats[t[1]] else null
                "f" -> {
                    if (t.size < 4) continue@lineLoop
                    val idx = IntArray(t.size - 1) { k ->
                        val s = t[k + 1].substringBefore('/')
                        var v = s.toIntOrNull() ?: throw IllegalArgumentException("line ${n + 1}: bad face '${t[k + 1]}'")
                        v = if (v > 0) v - 1 else pos.size + v
                        require(v >= 0 && v < pos.size) { "line ${n + 1}: a face uses a vertex that doesn't exist" }
                        v
                    }
                    var alpha = 0
                    val rgb = when {
                        cur != null -> { if (cur[3] < 1) alpha = Math.round((1 - Math.max(0.0, cur[3])) * 255).toInt(); pack(cur[0], cur[1], cur[2]) }
                        vcol[idx[0]] != null -> vcol[idx[0]]!!.let { pack(it[0], it[1], it[2]) }
                        else -> 0x808080
                    }
                    for (k in 1 until idx.size - 1) tris.add(intArrayOf(idx[0], idx[k], idx[k + 1], rgb, alpha))
                }
            }
        }
        require(tris.isNotEmpty()) { "no faces found in the OBJ" }
        val mapped = IntArray(pos.size) { -1 }
        val weld = HashMap<Long, Int>()
        val verts = ArrayList<IntArray>(); val faces = ArrayList<IntArray>()
        var dropped = 0
        for (tri in tris) {
            val w = IntArray(3)
            for (k in 0 until 3) {
                val o = tri[k]
                if (mapped[o] < 0) {
                    val p = pos[o]
                    val x = Math.round(p[0] * scale); val y = Math.round((if (flip) -p[1] else p[1]) * scale); val z = Math.round((if (flip) -p[2] else p[2]) * scale)
                    require(Math.abs(x) < 1_000_000 && Math.abs(y) < 1_000_000 && Math.abs(z) < 1_000_000) { "coordinates are too large after scaling (check the scale)" }
                    val key = ((x + 1_048_576L) shl 42) or ((y + 1_048_576L) shl 21) or (z + 1_048_576L)
                    mapped[o] = weld.getOrPut(key) { verts.add(intArrayOf(x.toInt(), y.toInt(), z.toInt())); verts.size - 1 }
                }
                w[k] = mapped[o]
            }
            if (w[0] == w[1] || w[1] == w[2] || w[0] == w[2]) { dropped++; continue }
            faces.add(intArrayOf(w[0], w[1], w[2], tri[3], tri[4]))
        }
        require(faces.isNotEmpty()) { "every face collapsed to a line or point (is the scale far too small?)" }
        require(verts.size <= 16383) { "${verts.size} vertices is too many (limit 16383)" }
        require(faces.size <= 30000) { "${faces.size} faces is too many (limit 30000)" }
        val md = ModelDefinition()
        md.vertexCount = verts.size; md.faceCount = faces.size
        md.vertexPositionsX = IntArray(verts.size) { verts[it][0] }
        md.vertexPositionsY = IntArray(verts.size) { verts[it][1] }
        md.vertexPositionsZ = IntArray(verts.size) { verts[it][2] }
        md.faceVertexIndices1 = IntArray(faces.size) { faces[it][0] }
        md.faceVertexIndices2 = IntArray(faces.size) { faces[it][1] }
        md.faceVertexIndices3 = IntArray(faces.size) { faces[it][2] }
        md.faceColors = ShortArray(faces.size) { nearestHsl(faces[it][3]).toShort() }
        if (faces.any { it[4] > 0 }) md.faceAlphas = ByteArray(faces.size) { faces[it][4].toByte() }
        md.priority = 10
        val enc = encodeOld(md)
        return ObjResult(enc.data, verts.size, faces.size, dropped, md.faceColors!!.toSet().size)
    }

    private fun parseMtl(mtl: ByteArray): HashMap<String, DoubleArray> {
        val mats = HashMap<String, DoubleArray>()
        var cur: DoubleArray? = null
        for (raw in String(mtl, Charsets.UTF_8).split(Regex("\\r?\\n"))) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val t = line.split(Regex("\\s+"))
            try {
                when {
                    t[0] == "newmtl" && t.size > 1 -> { cur = doubleArrayOf(0.5, 0.5, 0.5, 1.0); mats[t[1]] = cur!! }
                    cur != null && t[0] == "Kd" && t.size >= 4 -> { cur!![0] = t[1].toDouble(); cur!![1] = t[2].toDouble(); cur!![2] = t[3].toDouble() }
                    cur != null && t[0] == "d" && t.size >= 2 -> cur!![3] = t[1].toDouble()
                    cur != null && t[0] == "Tr" && t.size >= 2 -> cur!![3] = 1 - t[1].toDouble()
                }
            } catch (e: NumberFormatException) {}
        }
        return mats
    }

    private fun num(s: String, line: Int) = s.toDoubleOrNull() ?: throw IllegalArgumentException("line ${line + 1}: '$s' isn't a number")
    private fun pack(r: Double, g: Double, b: Double): Int {
        val k = if (Math.max(r, Math.max(g, b)) > 1.0) 1 / 255.0 else 1.0 // some exporters write 0-255
        fun c(v: Double) = Math.max(0, Math.min(255, Math.round(v * 255).toInt()))
        return c(r * k) shl 16 or (c(g * k) shl 8) or c(b * k)
    }
    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v shr 8); out.write(v) }
    private fun smart(out: ByteArrayOutputStream, v: Int) {
        when {
            v >= -64 && v < 64 -> out.write(v + 64)
            v >= -16384 && v < 16384 -> u16(out, v + 49152)
            else -> throw IllegalArgumentException("two points are too far apart to store (${Math.abs(v)} units)")
        }
    }
}
