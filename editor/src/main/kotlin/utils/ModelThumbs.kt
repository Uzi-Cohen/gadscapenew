package utils

import cache.definitions.ModelDefinition
import cache.loaders.TextureLoader
import cache.utils.ColorPalette
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.Canvas
import javafx.scene.image.Image
import javafx.scene.paint.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Small flat-shaded pictures of object models for the object picker.
 * Software painter's algorithm on a Canvas: fast, no 3D scene needed.
 */
object ModelThumbs {
    private val palette: IntArray by lazy { ColorPalette(0.8, 0, 512).colorPalette }

    /** Must be called on the JavaFX thread (Canvas snapshot). */
    fun render(md: ModelDefinition, size: Double, textureLoader: TextureLoader): Image? {
        val vc = md.vertexCount
        val fc = md.faceCount
        if (vc == 0 || fc == 0) return null
        val colors = md.faceColors ?: return null
        val a = md.faceVertexIndices1 ?: return null
        val b = md.faceVertexIndices2 ?: return null
        val c = md.faceVertexIndices3 ?: return null

        // rotate: turn 35 degrees, look down 25 degrees
        val yaw = Math.toRadians(35.0)
        val pitch = Math.toRadians(25.0)
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val px = DoubleArray(vc); val py = DoubleArray(vc); val pz = DoubleArray(vc)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until vc) {
            val x = md.vertexPositionsX[i].toDouble()
            val y = md.vertexPositionsY[i].toDouble() // negative is up, same as screen
            val z = md.vertexPositionsZ[i].toDouble()
            val x1 = x * cy - z * sy
            val z1 = x * sy + z * cy
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            px[i] = x1; py[i] = y2; pz[i] = z2
            if (x1 < minX) minX = x1; if (x1 > maxX) maxX = x1
            if (y2 < minY) minY = y2; if (y2 > maxY) maxY = y2
        }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1.0)
        val scale = size * 0.88 / span
        val ox = size / 2 - (minX + maxX) / 2 * scale
        val oy = size / 2 - (minY + maxY) / 2 * scale

        val hidden = md.faceRenderTypes
        val order = (0 until fc).filter { f ->
            val col = colors[f].toInt()
            col != -2 && !(hidden != null && (hidden[f].toInt() and 3) == 2 && col == -1)
        }.sortedByDescending { f -> pz[a[f]] + pz[b[f]] + pz[c[f]] }

        val canvas = Canvas(size, size)
        val g = canvas.graphicsContext2D
        val xs = DoubleArray(3); val ys = DoubleArray(3)
        // light from the upper front left
        val lx = -0.4; val ly = -0.7; val lz = -0.6
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (f in order) {
            val i0 = a[f]; val i1 = b[f]; val i2 = c[f]
            val ux = px[i1] - px[i0]; val uy = py[i1] - py[i0]; val uz = pz[i1] - pz[i0]
            val vx = px[i2] - px[i0]; val vy = py[i2] - py[i0]; val vz = pz[i2] - pz[i0]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            if (nl > 0) { nx /= nl; ny /= nl; nz /= nl }
            val shade = 0.55 + 0.45 * kotlin.math.abs((nx * lx + ny * ly + nz * lz) / ll)

            val tex = md.faceTextures?.let { it[f].toInt() } ?: -1
            val hsl = if (tex >= 0) textureLoader.getAverageTextureRGB(tex) else colors[f].toInt() and 0xFFFF
            val rgb = palette[hsl and 0xFFFF]
            val alpha = md.faceAlphas?.let { 1.0 - (it[f].toInt() and 0xFF) / 255.0 } ?: 1.0
            g.fill = Color.rgb(
                ((rgb shr 16 and 0xFF) * shade).toInt().coerceIn(0, 255),
                ((rgb shr 8 and 0xFF) * shade).toInt().coerceIn(0, 255),
                ((rgb and 0xFF) * shade).toInt().coerceIn(0, 255),
                alpha.coerceIn(0.0, 1.0)
            )
            xs[0] = px[i0] * scale + ox; ys[0] = py[i0] * scale + oy
            xs[1] = px[i1] * scale + ox; ys[1] = py[i1] * scale + oy
            xs[2] = px[i2] * scale + ox; ys[2] = py[i2] * scale + oy
            g.fillPolygon(xs, ys, 3)
            g.stroke = g.fill; g.lineWidth = 0.6; g.strokePolygon(xs, ys, 3) // hide hairline gaps
        }
        val params = SnapshotParameters().apply { fill = Color.TRANSPARENT }
        return canvas.snapshot(params, null)
    }
}
