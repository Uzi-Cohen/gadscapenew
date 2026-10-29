package utils.gambling

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Sprite helpers for the IF3 screens ([If3]): a graphic by id and resource path, and the encoder that packs a PNG as
 * a one-frame sprite group (the Premade Kits tab icon, see KitsPatch).
 *
 * Pixels: exact magenta (255, 0, 255) and alpha 0 are transparent; partial alpha is kept (the sprite's alpha flag).
 * A graphic with more than 255 colours is reduced to 255 by median cut (the format's palette limit).
 */
object Sprites {

    class S(val id: Int, val path: String) {
        val image: BufferedImage by lazy {
            val stream = Sprites::class.java.getResourceAsStream("/$path.png") ?: error("missing sprite resource $path")
            stream.use { ImageIO.read(it) }
        }
        val w: Int get() = image.width
        val h: Int get() = image.height
    }

    /** The sprite group bytes: one frame, palette of up to 255 colours, per-pixel alpha when the image has any. */
    fun encode(image: BufferedImage): ByteArray {
        val w = image.width
        val h = image.height
        val argb = IntArray(w * h) { image.getRGB(it % w, it / w) }
        for (i in argb.indices) {
            val a = argb[i] ushr 24
            if (a == 0 || argb[i] and 0xFFFFFF == 0xFF00FF) argb[i] = 0
        }
        val opaque = argb.filter { it ushr 24 != 0 }.map { it and 0xFFFFFF }
        val palette = Quantizer.palette(opaque, 255)
        val lookup = HashMap<Int, Int>()
        val index = ByteArray(w * h)
        val alpha = ByteArray(w * h)
        var partial = false
        for (i in argb.indices) {
            val a = argb[i] ushr 24
            if (a == 0) continue
            val rgb = argb[i] and 0xFFFFFF
            val p = lookup.getOrPut(rgb) { Quantizer.nearest(palette, rgb) + 1 }
            index[i] = p.toByte()
            alpha[i] = a.toByte()
            if (a != 255) partial = true
        }
        val out = ByteArrayOutputStream()
        out.write(if (partial) 2 else 0) // flags: row-major, alpha follows when 2
        out.write(index)
        if (partial) out.write(alpha)
        for (c in palette) {
            val v = if (c == 0) 1 else c // 0 would read as transparent
            out.write(v shr 16); out.write(v shr 8); out.write(v)
        }
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        u16(w); u16(h)
        out.write(palette.size) // palette length - 1 (index 0 is transparent)
        u16(0); u16(0); u16(w); u16(h) // offset x, offset y, width, height of the one frame
        u16(1)
        return out.toByteArray()
    }
}

/** Median-cut colour reduction; exact when there are few enough colours. */
object Quantizer {

    fun palette(colours: List<Int>, max: Int): IntArray {
        val distinct = colours.distinct()
        if (distinct.size <= max) return distinct.toIntArray()
        val counts = HashMap<Int, Int>()
        for (c in colours) counts.merge(c, 1, Int::plus)
        var boxes = mutableListOf(counts.keys.toList())
        while (boxes.size < max) {
            val box = boxes.filter { it.size > 1 }.maxByOrNull { range(it) * it.size } ?: break
            boxes.remove(box)
            val channel = (0..2).maxBy { ch -> box.maxOf { comp(it, ch) } - box.minOf { comp(it, ch) } }
            val sorted = box.sortedBy { comp(it, channel) }
            // split at the weighted median
            val total = sorted.sumOf { counts[it]!! }
            var acc = 0
            var cut = 1
            for ((i, c) in sorted.withIndex()) {
                acc += counts[c]!!
                if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break }
            }
            boxes.add(sorted.subList(0, cut))
            boxes.add(sorted.subList(cut, sorted.size))
        }
        return boxes.map { box ->
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (c in box) { val k = counts[c]!!.toLong(); r += comp(c, 0) * k; g += comp(c, 1) * k; b += comp(c, 2) * k; n += k }
            ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }.toIntArray()
    }

    private fun range(box: List<Int>) = (0..2).maxOf { ch -> box.maxOf { comp(it, ch) } - box.minOf { comp(it, ch) } }

    private fun comp(c: Int, ch: Int) = (c shr (16 - ch * 8)) and 0xFF

    fun nearest(palette: IntArray, c: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for ((i, p) in palette.withIndex()) {
            val dr = comp(p, 0) - comp(c, 0)
            val dg = comp(p, 1) - comp(c, 1)
            val db = comp(p, 2) - comp(c, 2)
            val d = dr * dr * 2 + dg * dg * 4 + db * db * 3
            if (d < bestD) { bestD = d; best = i; if (d == 0) break }
        }
        return best
    }
}
