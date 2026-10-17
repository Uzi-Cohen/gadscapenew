package controllers.worldRenderer

import com.jogamp.opengl.GL
import com.jogamp.opengl.GL4
import controllers.worldRenderer.helpers.GLUtil
import models.scene.Scene
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws each tile's numbers on top of the 3D view: "underlay/overlay", floors 1-3 prefixed "1:" etc.
 * Red = a tile that will look dead in game (no paint at all, or a see-through overlay).
 * Plain screen-space quads with a tiny built-in pixel font, drawn after the scene, so nothing else changes.
 */
class TileLabels(private val overlayLoader: cache.loaders.OverlayLoader) {
    companion object {
        private const val CHARS = "0123456789/-:X"
        // 5 x 7 pixel font, one string per row, '#' = on
        private val GLYPHS = arrayOf(
            arrayOf(" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "), // 0
            arrayOf("  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "), // 1
            arrayOf(" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"), // 2
            arrayOf("#### ", "    #", "    #", " ### ", "    #", "    #", "#### "), // 3
            arrayOf("   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "), // 4
            arrayOf("#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "), // 5
            arrayOf(" ### ", "#    ", "#    ", "#### ", "#   #", "#   #", " ### "), // 6
            arrayOf("#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "), // 7
            arrayOf(" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "), // 8
            arrayOf(" ### ", "#   #", "#   #", " ####", "    #", "    #", " ### "), // 9
            arrayOf("    #", "   # ", "   # ", "  #  ", " #   ", " #   ", "#    "), // /
            arrayOf("     ", "     ", "     ", "#####", "     ", "     ", "     "), // -
            arrayOf("     ", "  #  ", "  #  ", "     ", "  #  ", "  #  ", "     "), // :
            arrayOf("#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #")  // X
        )
        private const val GW = 5; private const val GH = 7; private const val CELL = 6
        private const val PX = 2f           // screen pixels per font pixel
        private const val MAX_LABELS = 4000
        private const val MIN_SPACING = 30f // don't draw when tiles are closer than this on screen

        private val FLOOR_COLOURS = arrayOf(floatArrayOf(1f, 1f, 1f), floatArrayOf(0.45f, 0.85f, 1f),
            floatArrayOf(0.55f, 1f, 0.55f), floatArrayOf(1f, 0.6f, 0.95f))
        private val DEAD = floatArrayOf(1f, 0.3f, 0.3f)

        private const val VERT = "#version 330 core\n" +
                "layout(location = 0) in vec2 pos;\n" +
                "layout(location = 1) in vec2 uv;\n" +
                "layout(location = 2) in vec4 col;\n" +
                "uniform vec2 screen;\n" +
                "out vec2 vuv; out vec4 vcol;\n" +
                "void main() { vuv = uv; vcol = col; gl_Position = vec4(pos.x / screen.x * 2.0 - 1.0, 1.0 - pos.y / screen.y * 2.0, 0.0, 1.0); }\n"
        private const val FRAG = "#version 330 core\n" +
                "in vec2 vuv; in vec4 vcol;\n" +
                "uniform sampler2D atlas;\n" +
                "out vec4 fragColor;\n" +
                "void main() { float a = vuv.x < 0.0 ? 1.0 : texture(atlas, vuv).r; if (vcol.a * a < 0.01) discard; fragColor = vec4(vcol.rgb, vcol.a * a); }\n"
    }

    /** A label at a world point (x / z across the ground in 1/128 tiles, y = height): NPC ids above their heads. */
    class Extra(val x: Int, val y: Int, val z: Int, val text: String, val highlight: Boolean)
    private val NPC_COLOUR = floatArrayOf(1f, 0.85f, 0.3f)
    private val NPC_SELECTED = floatArrayOf(0.3f, 1f, 0.4f)

    private var program = 0
    private var vao = 0
    private var vbo = 0
    private var atlas = 0
    private var uniScreen = -1
    private var uniAtlas = -1
    private var failed = false
    private var data: FloatBuffer = ByteBuffer.allocateDirect(4 * 8 * 6 * 64).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var count = 0 // vertices

    private fun compile(gl: GL4, type: Int, src: String): Int {
        val s = gl.glCreateShader(type)
        gl.glShaderSource(s, 1, arrayOf(src), null)
        gl.glCompileShader(s)
        if (GLUtil.glGetShader(gl, s, GL4.GL_COMPILE_STATUS) != GL.GL_TRUE) throw IllegalStateException("tile number shader: " + GLUtil.glGetShaderInfoLog(gl, s))
        return s
    }

    private fun init(gl: GL4) {
        val vs = compile(gl, GL4.GL_VERTEX_SHADER, VERT)
        val fs = compile(gl, GL4.GL_FRAGMENT_SHADER, FRAG)
        program = gl.glCreateProgram()
        gl.glAttachShader(program, vs); gl.glAttachShader(program, fs)
        gl.glLinkProgram(program)
        if (GLUtil.glGetProgram(gl, program, GL4.GL_LINK_STATUS) != GL.GL_TRUE) throw IllegalStateException("tile number program: " + GLUtil.glGetProgramInfoLog(gl, program))
        gl.glDeleteShader(vs); gl.glDeleteShader(fs)
        uniScreen = gl.glGetUniformLocation(program, "screen")
        uniAtlas = gl.glGetUniformLocation(program, "atlas")

        // font atlas: one row of 6 x 8 cells
        val w = CHARS.length * CELL; val h = 8
        val px = ByteBuffer.allocateDirect(w * h)
        for (c in CHARS.indices) for (y in 0 until GH) for (x in 0 until GW)
            if (GLYPHS[c][y][x] == '#') px.put(y * w + c * CELL + x, 255.toByte())
        val ids = IntArray(1)
        gl.glGenTextures(1, ids, 0); atlas = ids[0]
        gl.glActiveTexture(GL.GL_TEXTURE2)
        gl.glBindTexture(GL.GL_TEXTURE_2D, atlas)
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL4.GL_R8, w, h, 0, GL4.GL_RED, GL.GL_UNSIGNED_BYTE, px)
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 4)
        gl.glActiveTexture(GL.GL_TEXTURE0)

        vao = GLUtil.glGenVertexArrays(gl)
        vbo = GLUtil.glGenBuffers(gl)
        gl.glBindVertexArray(vao)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        val stride = 8 * 4
        gl.glEnableVertexAttribArray(0); gl.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, stride, 0L)
        gl.glEnableVertexAttribArray(1); gl.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, stride, 8L)
        gl.glEnableVertexAttribArray(2); gl.glVertexAttribPointer(2, 4, GL.GL_FLOAT, false, stride, 16L)
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
    }

    private fun ensure(vertices: Int) {
        val need = (count + vertices) * 8
        if (data.capacity() >= need) return
        val bigger = ByteBuffer.allocateDirect(maxOf(need, data.capacity() * 2) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.flip(); bigger.put(data); data = bigger
    }

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, r: Float, g: Float, b: Float, a: Float) {
        ensure(6)
        fun v(x: Float, y: Float, u: Float, vv: Float) { data.put(x).put(y).put(u).put(vv).put(r).put(g).put(b).put(a) }
        v(x0, y0, u0, v0); v(x1, y0, u1, v0); v(x1, y1, u1, v1)
        v(x0, y0, u0, v0); v(x1, y1, u1, v1); v(x0, y1, u0, v1)
        count += 6
    }

    private fun text(s: String, cx: Float, cy: Float, col: FloatArray) {
        val atlasW = (CHARS.length * CELL).toFloat()
        val charW = (GW + 1) * PX
        val w = s.length * charW - PX
        val h = GH * PX
        var x = cx - w / 2
        val y = cy - h / 2
        quad(x - 3, y - 3, x + w + 3, y + h + 3, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 0.62f)
        for (ch in s) {
            val i = CHARS.indexOf(ch)
            if (i >= 0) {
                val u0 = (i * CELL) / atlasW; val u1 = (i * CELL + GW) / atlasW
                quad(x, y, x + GW * PX, y + h, u0, 0f, u1, GH / 8f, col[0], col[1], col[2], 1f)
            }
            x += charW
        }
    }

    /**
     * Project a world point the way the shaders do (to_screen.glsl): x/z across the ground, y = height.
     * Returns screen x, y (top-left origin) or null when it's behind the camera.
     */
    private fun toScreen(camera: Camera, wx: Int, wy: Int, wz: Int, out: FloatArray): Boolean {
        val unit = Math.PI / 1024.0
        val vx = (wx - camera.cameraX).toDouble()
        val vy = (wy - camera.cameraZ).toDouble()
        val vz = (wz - camera.cameraY).toDouble()
        val yawSin = Math.sin(camera.yaw * unit); val yawCos = Math.cos(camera.yaw * unit)
        val pitchSin = Math.sin(camera.pitch * unit); val pitchCos = Math.cos(camera.pitch * unit)
        val rx = vz * yawSin + vx * yawCos
        val rz = vz * yawCos - vx * yawSin
        val v13 = vy * pitchCos - rz * pitchSin
        val v12 = vy * pitchSin + rz * pitchCos
        if (v12 < 50) return false
        out[0] = (rx * camera.scale / v12 + camera.centerX).toFloat()
        out[1] = (v13 * camera.scale / v12 + camera.centerY).toFloat()
        return true
    }

    private fun seeThrough(overlay: Int): Boolean {
        val d = overlayLoader.get(overlay) ?: return true
        return d.texture < 0 && d.rgbColor == 0xFF00FF && d.hideUnderlay
    }

    /** [floors]: which floors to label. Call after the scene is on the default framebuffer. */
    fun draw(gl: GL4, scene: Scene, camera: Camera, width: Int, height: Int, floors: IntRange?, extras: List<Extra> = emptyList()) {
        if (failed || width <= 0 || height <= 0 || !scene.isLoaded()) return
        try {
            if (program == 0) init(gl)
        } catch (e: Exception) {
            failed = true
            println("Tile numbers can't be drawn on this graphics card: ${e.message}")
            return
        }
        data.clear(); count = 0
        val size = scene.radius * 64
        val camTx = camera.cameraX / 128; val camTy = camera.cameraY / 128
        val reach = 110
        val a = FloatArray(2); val b = FloatArray(2)
        var labels = 0
        for (e in extras) {
            if (!toScreen(camera, e.x, e.y, e.z, a)) continue
            if (a[0] < -40 || a[1] < -20 || a[0] > width + 40 || a[1] > height + 20) continue
            text(e.text, a[0], a[1], if (e.highlight) NPC_SELECTED else NPC_COLOUR)
        }
        if (floors != null) loop@ for (sx in maxOf(0, camTx - reach) until minOf(size, camTx + reach)) for (sy in maxOf(0, camTy - reach) until minOf(size, camTy + reach)) {
            val region = scene.getRegionFromSceneCoord(sx, sy) ?: continue
            val def = region.regionDefinition
            val lx = sx and 63; val ly = sy and 63
            for (z in floors) {
                val t = def.tiles[z][lx][ly] ?: continue
                val under = t.underlayId
                val over = if (t.attrOpcode > 0) t.overlayId else 0
                if (z > 0 && under == 0 && over == 0) continue
                val wx = sx * 128 + 64; val wz = sy * 128 + 64
                if (!toScreen(camera, wx, t.height, wz, a)) continue
                if (a[0] < -40 || a[1] < -20 || a[0] > width + 40 || a[1] > height + 20) continue
                // too far away to read: skip (checked against the tile next to it)
                if (!toScreen(camera, wx + 128, t.height, wz, b)) continue
                val dx = b[0] - a[0]; val dy = b[1] - a[1]
                if (dx * dx + dy * dy < MIN_SPACING * MIN_SPACING) continue
                val dead = (under == 0 && over == 0) || (over > 0 && seeThrough(over - 1) && under == 0)
                val txt = (if (z > 0) "$z:" else "") + (if (under == 0 && over == 0) "X" else
                    (if (under > 0) "${under - 1}" else "-") + "/" + (if (over > 0) "${over - 1}" else "-"))
                val col = if (dead || (over > 0 && seeThrough(over - 1))) DEAD else FLOOR_COLOURS[z]
                // floors stack upwards on screen a little so they don't sit on top of each other
                text(txt, a[0], a[1] - z * 9f, col)
                if (++labels >= MAX_LABELS) break@loop
            }
        }
        if (count == 0) return

        data.flip()
        val depth = gl.glIsEnabled(GL.GL_DEPTH_TEST)
        val cull = gl.glIsEnabled(GL.GL_CULL_FACE)
        gl.glDisable(GL.GL_DEPTH_TEST); gl.glDisable(GL.GL_CULL_FACE)
        gl.glEnable(GL.GL_BLEND)
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA)
        gl.glUseProgram(program)
        gl.glUniform2f(uniScreen, width.toFloat(), height.toFloat())
        gl.glActiveTexture(GL.GL_TEXTURE2)
        gl.glBindTexture(GL.GL_TEXTURE_2D, atlas)
        gl.glUniform1i(uniAtlas, 2)
        gl.glBindVertexArray(vao)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        gl.glBufferData(GL.GL_ARRAY_BUFFER, count * 8 * 4L, data, GL4.GL_STREAM_DRAW)
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, count)
        gl.glBindVertexArray(0)
        gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0)
        gl.glUseProgram(0)
        gl.glActiveTexture(GL.GL_TEXTURE0)
        gl.glDisable(GL.GL_BLEND)
        if (depth) gl.glEnable(GL.GL_DEPTH_TEST)
        if (cull) gl.glEnable(GL.GL_CULL_FACE)
    }
}
