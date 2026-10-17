package utils

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import cache.loaders.TextureLoader
import com.displee.cache.CacheLibrary
import javafx.scene.image.Image
import javafx.scene.image.WritableImage
import kotlin.math.sqrt

/**
 * Pictures of items the way the game makes them: the inventory picture (36 x 32, with the item's zoom, angles and
 * offsets, a black outline and a shadow) and the worn models, on their own or on a plain player.
 */
object ItemPreview {
    /** The game draws a face only when its corners go round this way on screen. */
    private const val cullSign = -1
    private val SIN = IntArray(2048) { (65536.0 * Math.sin(it * 0.0030679615)).toInt() }
    private val COS = IntArray(2048) { (65536.0 * Math.cos(it * 0.0030679615)).toInt() }

    private fun copy(md: ModelDefinition): ModelDefinition = ModelDefinition.merge(listOf(md)).also { it.priority = md.priority; it.id = md.id }

    /** The item's inventory model with its recolours, retextures and size applied (a copy). */
    fun inventoryModel(item: ItemFull, models: ModelLoader): ModelDefinition? {
        val base = try { models.get(item.inventoryModel) } catch (e: Exception) { null } ?: return null
        val md = copy(base)
        recolour(md, item)
        if (item.resizeX != 128 || item.resizeY != 128 || item.resizeZ != 128) for (v in 0 until md.vertexCount) {
            md.vertexPositionsX[v] = md.vertexPositionsX[v] * item.resizeX / 128
            md.vertexPositionsY[v] = md.vertexPositionsY[v] * item.resizeY / 128
            md.vertexPositionsZ[v] = md.vertexPositionsZ[v] * item.resizeZ / 128
        }
        return md
    }

    fun recolour(md: ModelDefinition, item: ItemFull) {
        val fc = md.faceColors
        if (fc != null) for (i in fc.indices) for (k in item.recolorFrom.indices) if (fc[i] == item.recolorFrom[k]) { fc[i] = item.recolorTo[k]; break }
        val ft = md.faceTextures
        if (ft != null) for (i in ft.indices) for (k in item.retextureFrom.indices) if (ft[i] == item.retextureFrom[k]) { ft[i] = item.retextureTo[k]; break }
    }

    /** The inventory picture, 36 x 32 like in game. [notedOn] = the paper picture behind a noted item. */
    fun icon(item: ItemFull, models: ModelLoader, textures: TextureLoader?, scale: Int = 1): Image? {
        val md = inventoryModel(item, models) ?: return null
        return iconOf(md, item, textures, scale)
    }

    /** The inventory picture of [md] (already recoloured and sized) with [item]'s camera. */
    fun iconOf(md: ModelDefinition, item: ItemFull, textures: TextureLoader?, scale: Int = 1): Image? {
        val w = 36; val h = 32
        val px = IntArray(w * h)
        draw(md, item, textures, px, w, h)
        // the game's black outline, then the shadow down-right
        val outline = IntArray(px.size)
        for (y in 0 until h) for (x in 0 until w) {
            if (px[y * w + x] != 0) continue
            val near = (x > 0 && px[y * w + x - 1] != 0) || (x < w - 1 && px[y * w + x + 1] != 0) ||
                    (y > 0 && px[(y - 1) * w + x] != 0) || (y < h - 1 && px[(y + 1) * w + x] != 0)
            if (near) outline[y * w + x] = 0xFF000001.toInt()
        }
        for (i in px.indices) if (outline[i] != 0) px[i] = outline[i]
        for (y in h - 1 downTo 1) for (x in w - 1 downTo 1) if (px[y * w + x] == 0 && px[(y - 1) * w + x - 1] != 0) px[y * w + x] = 0xFF302020.toInt()
        val img = WritableImage(w * scale, h * scale)
        val pw = img.pixelWriter
        for (y in 0 until h * scale) for (x in 0 until w * scale) pw.setArgb(x, y, px[(y / scale) * w + x / scale])
        return img
    }

    /** Software drawing with the game's item camera (ItemComposition sprite code, zoom 512, centre 16,16). */
    private fun draw(md: ModelDefinition, item: ItemFull, textures: TextureLoader?, px: IntArray, w: Int, h: Int) {
        val vc = md.vertexCount; val fc = md.faceCount
        val a = md.faceVertexIndices1 ?: return; val b = md.faceVertexIndices2 ?: return; val c = md.faceVertexIndices3 ?: return
        var modelHeight = 0
        for (v in 0 until vc) if (-md.vertexPositionsY[v] > modelHeight) modelHeight = -md.vertexPositionsY[v]
        val zoom = if (item.isNoted) (item.zoom2d * 1.5).toInt() else item.zoom2d
        val xan = item.xan2d and 2047; val yan = item.yan2d and 2047; val zan = item.zan2d and 2047
        val s17 = zoom * SIN[xan] shr 16
        val s18 = zoom * COS[xan] shr 16
        val xOff = item.offsetX2d; val yOff = modelHeight / 2 + s17 + item.offsetY2d; val zOff = s18 + item.offsetY2d
        val sinX = SIN[xan]; val cosX = COS[xan]
        val sx = DoubleArray(vc); val sy = DoubleArray(vc); val sz = DoubleArray(vc)
        for (i in 0 until vc) {
            var x = md.vertexPositionsX[i]; var y = md.vertexPositionsY[i]; var z = md.vertexPositionsZ[i]
            if (zan != 0) { val t = y * SIN[zan] + x * COS[zan] shr 16; y = y * COS[zan] - x * SIN[zan] shr 16; x = t }
            if (yan != 0) { val t = z * SIN[yan] + x * COS[yan] shr 16; z = z * COS[yan] - x * SIN[yan] shr 16; x = t }
            x += xOff; y += yOff; z += zOff
            val t = y * cosX - z * sinX shr 16
            z = y * sinX + z * cosX shr 16
            sz[i] = z.toDouble()
            if (z < 50) { sx[i] = Double.NaN; continue }
            sx[i] = x * 512.0 / z + 16; sy[i] = t * 512.0 / z + 16
        }
        val colors = md.faceColors
        val types = md.faceRenderTypes
        // light from the game's direction (-50, -10, -50) in model space
        val lx = -50.0; val ly = -10.0; val lz = -50.0; val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val amb = (item.ambient.toByte().toInt()); val con = (item.contrast.toByte().toInt())
        val order = (0 until fc).filter { f ->
            val col = colors?.get(f)?.toInt() ?: 0
            val tex = md.faceTextures?.get(f)?.toInt() ?: -1
            val hidden = (types != null && (types[f].toInt() and 3) == 2) && col == -1
            !hidden && !(tex == -1 && (col and 0xFFFF) == 65535) && (md.faceAlphas?.get(f)?.toInt()?.and(255) ?: 0) != 255 &&
                    !sx[a[f]].isNaN() && !sx[b[f]].isNaN() && !sx[c[f]].isNaN()
        }.sortedByDescending { f -> sz[a[f]] + sz[b[f]] + sz[c[f]] }
        for (f in order) {
            val i0 = a[f]; val i1 = b[f]; val i2 = c[f]
            // back faces aren't drawn by the game
            val area = (sx[i1] - sx[i0]) * (sy[i2] - sy[i0]) - (sy[i1] - sy[i0]) * (sx[i2] - sx[i0])
            if (area * cullSign <= 0) continue
            val ux = (md.vertexPositionsX[i1] - md.vertexPositionsX[i0]).toDouble(); val uy = (md.vertexPositionsY[i1] - md.vertexPositionsY[i0]).toDouble(); val uz = (md.vertexPositionsZ[i1] - md.vertexPositionsZ[i0]).toDouble()
            val vx = (md.vertexPositionsX[i2] - md.vertexPositionsX[i0]).toDouble(); val vy = (md.vertexPositionsY[i2] - md.vertexPositionsY[i0]).toDouble(); val vz = (md.vertexPositionsZ[i2] - md.vertexPositionsZ[i0]).toDouble()
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz); if (nl > 0) { nx /= nl; ny /= nl; nz /= nl }
            val dot = (nx * lx + ny * ly + nz * lz) / ll
            // the game lights items with ambient 64 + ambient and contrast 768 + contrast * 5
            val shade = (0.62 * (64 + amb) / 64.0 + 0.55 * (768 + con * 5) / 768.0 * dot).coerceIn(0.25, 1.35)
            val tex = md.faceTextures?.get(f)?.toInt() ?: -1
            val hsl = if (tex >= 0) (textures?.getAverageTextureRGB(tex) ?: 0) else (colors?.get(f)?.toInt() ?: 0) and 0xFFFF
            val rgb = ModelCodec.palette[hsl and 0xFFFF]
            val r = ((rgb shr 16 and 255) * shade).toInt().coerceIn(0, 255)
            val g = ((rgb shr 8 and 255) * shade).toInt().coerceIn(0, 255)
            val bl = ((rgb and 255) * shade).toInt().coerceIn(0, 255)
            val alpha = 255 - (md.faceAlphas?.get(f)?.toInt()?.and(255) ?: 0)
            fill(px, w, h, sx[i0], sy[i0], sx[i1], sy[i1], sx[i2], sy[i2], r, g, bl, alpha)
        }
    }

    private fun fill(px: IntArray, w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double, x2: Double, y2: Double, r: Int, g: Int, b: Int, alpha: Int) {
        val minX = maxOf(0, minOf(x0, x1, x2).toInt()); val maxX = minOf(w - 1, maxOf(x0, x1, x2).toInt() + 1)
        val minY = maxOf(0, minOf(y0, y1, y2).toInt()); val maxY = minOf(h - 1, maxOf(y0, y1, y2).toInt() + 1)
        val area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        if (area == 0.0) return
        for (y in minY..maxY) for (x in minX..maxX) {
            val cx = x + 0.5; val cy = y + 0.5
            val e0 = ((x1 - x0) * (cy - y0) - (y1 - y0) * (cx - x0)) / area
            val e1 = ((x2 - x1) * (cy - y1) - (y2 - y1) * (cx - x1)) / area
            val e2 = ((x0 - x2) * (cy - y2) - (y0 - y2) * (cx - x2)) / area
            if (e0 < -0.0001 || e1 < -0.0001 || e2 < -0.0001) continue
            val i = y * w + x
            if (alpha >= 255 || px[i] == 0) {
                // the game's sprites use 0 for "nothing here", so a black face must not be 0
                val v = (r shl 16) or (g shl 8) or b
                px[i] = (0xFF shl 24) or (if (v == 0) 1 else v)
            } else {
                val o = px[i]; val ia = 255 - alpha
                val nr = ((o shr 16 and 255) * ia + r * alpha) / 255; val ng = ((o shr 8 and 255) * ia + g * alpha) / 255; val nb = ((o and 255) * ia + b * alpha) / 255
                px[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    // ---------------- worn ----------------

    /** The models the item shows when worn, merged and moved by the worn offset (a copy), or null. */
    fun wornModel(item: ItemFull, female: Boolean, models: ModelLoader): ModelDefinition? {
        val ids = if (female) listOf(item.femaleModel0, item.femaleModel1, item.femaleModel2) else listOf(item.maleModel0, item.maleModel1, item.maleModel2)
        val parts = ids.filter { it >= 0 }.mapNotNull { try { models.get(it) } catch (e: Exception) { null } }
        if (parts.isEmpty()) return null
        val md = ModelDefinition.merge(parts)
        recolour(md, item)
        val off = if (female) item.femaleOffset else item.maleOffset
        if (off != 0) for (v in 0 until md.vertexCount) md.vertexPositionsY[v] += off
        return md
    }

    class Kit(val bodyPart: Int, val models: IntArray, val selectable: Boolean, val from: ShortArray, val to: ShortArray)

    private fun kits(lib: CacheLibrary): Map<Int, Kit> {
        val out = HashMap<Int, Kit>()
        val arch = lib.index(2).archive(3) ?: return out
        for (id in arch.fileIds()) {
            val b = lib.data(2, 3, id) ?: continue
            var p = 0
            fun u8() = b[p++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            var part = -1; var models = IntArray(0); var sel = true; var from = ShortArray(0); var to = ShortArray(0)
            try {
                loop@ while (p < b.size) {
                    when (val o = u8()) {
                        0 -> break@loop
                        1 -> part = u8()
                        2 -> { val n = u8(); models = IntArray(n) { u16() } }
                        3 -> sel = false
                        40 -> { val n = u8(); from = ShortArray(n); to = ShortArray(n); for (i in 0 until n) { from[i] = u16().toShort(); to[i] = u16().toShort() } }
                        41 -> { val n = u8(); repeat(n) { u16(); u16() } }
                        in 60..69 -> u16()
                        else -> { part = -1; break@loop }
                    }
                }
            } catch (e: Exception) { continue }
            if (part >= 0) out[id] = Kit(part, models, sel, from, to)
        }
        return out
    }

    /**
     * A plain player (the first kit of each body part) wearing [item]: the parts the item covers or hides are left
     * out, like the game does.
     */
    fun onPlayer(item: ItemFull, female: Boolean, lib: CacheLibrary, models: ModelLoader, also: List<ItemFull> = emptyList()): ModelDefinition? {
        val kits = kits(lib)
        val base = if (female) 7 else 0
        val skip = HashSet<Int>()
        for (it in listOf(item) + also) {
            val hides = setOf(it.wearPos1, it.wearPos2, it.wearPos3)
            // equipment slot -> the body part it replaces
            when (it.wearPos1) { 4 -> skip.add(2); 7 -> skip.add(5); 9 -> skip.add(4); 10 -> skip.add(6) }
            if (6 in hides) skip.add(3)
            if (8 in hides) skip.add(0)
            if (11 in hides) skip.add(1)
        }
        val parts = ArrayList<ModelDefinition>()
        for (bp in 0..6) {
            if (bp in skip) continue
            val kit = kits.entries.filter { it.value.bodyPart == base + bp && it.value.selectable }.minByOrNull { it.key }?.value ?: continue
            for (m in kit.models) {
                val md = try { models.get(m) } catch (e: Exception) { null } ?: continue
                val cp = copy(md)
                val fc = cp.faceColors
                if (fc != null) for (i in fc.indices) for (k in kit.from.indices) if (fc[i] == kit.from[k]) fc[i] = kit.to[k]
                parts.add(cp)
            }
        }
        for (it in listOf(item) + also) wornModel(it, female, models)?.let { parts.add(it) }
        if (parts.isEmpty()) return null
        return ModelDefinition.merge(parts)
    }
}
