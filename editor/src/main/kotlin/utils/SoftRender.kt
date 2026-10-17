package utils

import cache.definitions.ModelDefinition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A small software renderer for models (with a z-buffer and flat shading), so the AI bridge can show models, items
 * and animation frames without the editor's windows being open.
 */
object SoftRender {
    /**
     * Draws [md] with the vertex positions [xs]/[ys]/[zs] (default: the model's own). [yaw] turns it round (0-2047,
     * like the game; 0 = facing the camera), [pitch] tilts the camera down (0-512).
     */
    fun render(md: ModelDefinition, width: Int = 512, height: Int = 512, yaw: Int = 0, pitch: Int = 64, zoom: Double = 1.0,
               xs: IntArray = md.vertexPositionsX, ys: IntArray = md.vertexPositionsY, zs: IntArray = md.vertexPositionsZ,
               textures: cache.loaders.TextureLoader? = null, background: Int = 0x28241f): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val px = IntArray(width * height) { background }
        val depth = FloatArray(width * height) { Float.MAX_VALUE }
        val vc = md.vertexCount
        val a = md.faceVertexIndices1; val b = md.faceVertexIndices2; val c = md.faceVertexIndices3
        if (vc == 0 || a == null || b == null || c == null) { img.setRGB(0, 0, width, height, px, 0, width); return img }
        // models face +z in the game; turned half way so yaw 0 shows the front
        val ya = (yaw + 1024) * Math.PI / 1024.0; val pa = pitch * Math.PI / 1024.0
        val cy = cos(ya); val sy = sin(ya); val cp = cos(pa); val sp = sin(pa)
        // camera space: x right, y down (the game's y already grows downwards), z away from the camera
        val rx = DoubleArray(vc); val ry = DoubleArray(vc); val rz = DoubleArray(vc)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (v in 0 until vc) {
            val x0 = xs[v].toDouble(); val y0 = ys[v].toDouble(); val z0 = zs[v].toDouble()
            val x1 = x0 * cy + z0 * sy; val z1 = -x0 * sy + z0 * cy
            val y2 = y0 * cp - z1 * sp; val z2 = y0 * sp + z1 * cp
            rx[v] = -x1; ry[v] = y2; rz[v] = -z2
            minX = minOf(minX, rx[v]); maxX = maxOf(maxX, rx[v]); minY = minOf(minY, ry[v]); maxY = maxOf(maxY, ry[v])
        }
        val size = maxOf(maxX - minX, maxY - minY, 1.0)
        val scale = minOf(width, height) * 0.86 / size * zoom
        val cx = (minX + maxX) / 2; val cyy = (minY + maxY) / 2
        val sx = DoubleArray(vc) { width / 2.0 + (rx[it] - cx) * scale }
        val sY = DoubleArray(vc) { height / 2.0 + (ry[it] - cyy) * scale }
        val colors = md.faceColors; val alphas = md.faceAlphas; val texs = md.faceTextures
        // light from the top-left, towards the model
        val lx = -0.45; val ly = -0.65; val lz = -0.6; val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (f in 0 until md.faceCount) {
            val tex = texs?.get(f)?.toInt() ?: -1
            val col = (colors?.get(f)?.toInt() ?: 0) and 0xFFFF
            if (tex == -1 && col == 65535) continue
            val alpha = 255 - ((alphas?.get(f)?.toInt() ?: 0) and 255)
            if (alpha <= 0) continue
            val i0 = a[f]; val i1 = b[f]; val i2 = c[f]
            if (i0 >= vc || i1 >= vc || i2 >= vc) continue
            val ux = rx[i1] - rx[i0]; val uy = ry[i1] - ry[i0]; val uz = rz[i1] - rz[i0]
            val wx = rx[i2] - rx[i0]; val wy = ry[i2] - ry[i0]; val wz = rz[i2] - rz[i0]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val nl = sqrt(nx * nx + ny * ny + nz * nz); if (nl > 0) { nx /= nl; ny /= nl; nz /= nl }
            val light = Math.abs((nx * lx + ny * ly + nz * lz) / ll)
            val shade = 0.45 + 0.65 * light
            val hsl = if (tex >= 0) (textures?.getAverageTextureRGB(tex) ?: 0x7F7F) else col
            val rgb = ModelCodec.palette[hsl and 0xFFFF]
            val r = ((rgb shr 16 and 255) * shade).toInt().coerceIn(0, 255)
            val g = ((rgb shr 8 and 255) * shade).toInt().coerceIn(0, 255)
            val bl = ((rgb and 255) * shade).toInt().coerceIn(0, 255)
            fill(px, depth, width, height, sx[i0], sY[i0], rz[i0], sx[i1], sY[i1], rz[i1], sx[i2], sY[i2], rz[i2], (r shl 16) or (g shl 8) or bl, alpha)
        }
        img.setRGB(0, 0, width, height, px, 0, width)
        return img
    }

    private fun fill(px: IntArray, depth: FloatArray, w: Int, h: Int, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
                     x2: Double, y2: Double, z2: Double, rgb: Int, alpha: Int) {
        val area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0)
        if (Math.abs(area) < 1e-9) return
        val minX = maxOf(0, minOf(x0, x1, x2).toInt()); val maxX = minOf(w - 1, maxOf(x0, x1, x2).toInt() + 1)
        val minY = maxOf(0, minOf(y0, y1, y2).toInt()); val maxY = minOf(h - 1, maxOf(y0, y1, y2).toInt() + 1)
        for (y in minY..maxY) for (x in minX..maxX) {
            val px0 = x + 0.5; val py0 = y + 0.5
            val e0 = ((x2 - x1) * (py0 - y1) - (y2 - y1) * (px0 - x1)) / area
            val e1 = ((x0 - x2) * (py0 - y2) - (y0 - y2) * (px0 - x2)) / area
            val e2 = 1.0 - e0 - e1
            if (e0 < -1e-6 || e1 < -1e-6 || e2 < -1e-6) continue
            val z = (e0 * z0 + e1 * z1 + e2 * z2).toFloat()
            val i = y * w + x
            if (alpha >= 255) {
                if (z >= depth[i]) continue
                depth[i] = z; px[i] = rgb
            } else {
                if (z >= depth[i]) continue
                val o = px[i]; val ia = 255 - alpha
                px[i] = ((((o shr 16 and 255) * ia + (rgb shr 16 and 255) * alpha) / 255) shl 16) or
                        ((((o shr 8 and 255) * ia + (rgb shr 8 and 255) * alpha) / 255) shl 8) or (((o and 255) * ia + (rgb and 255) * alpha) / 255)
            }
        }
    }

    fun png(img: BufferedImage): ByteArray { val o = ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "png", o); return o.toByteArray() }

    /** Lays [images] side by side (e.g. animation frames) in one picture. */
    fun strip(images: List<BufferedImage>): BufferedImage {
        val w = images.sumBy { it.width }; val h = images.map { it.height }.max() ?: 1
        val out = BufferedImage(maxOf(w, 1), h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics(); var x = 0
        for (i in images) { g.drawImage(i, x, 0, null); x += i.width }
        g.dispose()
        return out
    }
}
