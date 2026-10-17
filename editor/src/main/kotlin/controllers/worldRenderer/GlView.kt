package controllers.worldRenderer

import controllers.worldRenderer.helpers.GLC
import controllers.worldRenderer.helpers.Gl
import javafx.application.Platform
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Pane
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** What draws into a [GlView]. Every call runs on the view's render thread with its OpenGL context current. */
interface GlListener {
    fun init()
    fun reshape(width: Int, height: Int)
    /** Draws one frame and returns the framebuffer whose colour attachment 0 holds the picture. */
    fun display(): Int
    fun dispose()
}

/**
 * The 3D view: an OpenGL 4.5 core context (LWJGL 3 + GLFW, in a hidden window) drawing on its own thread into an
 * offscreen framebuffer, shown in JavaFX as an image. It's a normal JavaFX node, so menus, popups and docking panels
 * sit over it properly and it gets keyboard and mouse events like any other node.
 */
class GlView(private val listener: GlListener) {
    private val image = ImageView().apply { isPreserveRatio = false; isSmooth = false; scaleY = -1.0 /* GL rows start at the bottom */ }
    /** Add this to the scene; mouse and key events arrive on it, with x/y from its top-left corner. */
    val node = Pane(image).apply { isFocusTraversable = true; minWidth = 1.0; minHeight = 1.0 }

    /** Physical pixels per JavaFX unit (1.5 on a 150% Windows display); mouse positions are multiplied by it. */
    @Volatile var pixelScale = 1.0
        private set
    @Volatile private var wantW = 800
    @Volatile private var wantH = 600
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var fps = 0.0
        private set
    /** The OpenGL version and graphics card, once started (or why it couldn't start). */
    @Volatile var info = ""
        private set
    /** Frames per second to aim for. */
    @Volatile var targetFps = 60
    /** While true nothing is drawn (the view is hidden, e.g. another tab is open): no CPU or GPU use. */
    @Volatile var paused = false

    private class Frame(val w: Int, val h: Int) {
        val bytes: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val pixels = PixelBuffer(w, h, bytes, PixelFormat.getByteBgraPreInstance())
        val image = WritableImage(pixels)
    }
    private var frames = arrayOfNulls<Frame>(2)
    @Volatile private var shown = 0
    private val handing = AtomicBoolean(false)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "3d-view").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        val t = thread ?: return
        if (t !== Thread.currentThread()) t.join(3000)
        thread = null
    }

    /** Sets the size in JavaFX units (call on the JavaFX thread). */
    fun resize(width: Double, height: Double) {
        val scale = node.scene?.window?.renderScaleX ?: 1.0
        pixelScale = scale
        node.setPrefSize(width, height)
        image.fitWidth = max(1.0, width); image.fitHeight = max(1.0, height)
        wantW = max(1, (width * scale).roundToInt()); wantH = max(1, (height * scale).roundToInt())
    }

    fun requestFocus() = node.requestFocus()

    private fun run() {
        var window = 0L
        try {
            synchronized(GLFW_LOCK) {
                if (!glfwStarted) {
                    GLFWErrorCallback.createPrint(System.err).set()
                    check(glfwInit()) { "GLFW couldn't start" }
                    glfwStarted = true
                }
                window = createContext()
            }
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            glfwSwapInterval(0)
            info = "OpenGL ${GL11C.glGetString(GL11C.GL_VERSION)} on ${GL11C.glGetString(GL11C.GL_RENDERER)}"
            println("3D view: $info")
            listener.init()
            loop()
            listener.dispose()
        } catch (e: Throwable) {
            info = "The 3D view couldn't start: ${e.message}. It needs a graphics card with OpenGL 4.3 or newer (update the graphics driver)."
            println(info)
            e.printStackTrace()
        } finally {
            running = false
            if (window != 0L) synchronized(GLFW_LOCK) { glfwMakeContextCurrent(0L); glfwDestroyWindow(window) }
        }
    }

    /** OpenGL 4.5 core if the driver has it, else 4.3 (the oldest with the compute shaders the view needs). */
    private fun createContext(): Long {
        // a version the driver doesn't have just fails to make the window: try the next one quietly
        val errors = glfwSetErrorCallback(null)
        try {
        for ((major, minor) in listOf(4 to 6, 4 to 5, 4 to 3)) {
            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, major)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, minor)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE)
            val w = glfwCreateWindow(16, 16, "3D view", 0L, 0L)
            if (w != 0L) return w
        }
        } finally { glfwSetErrorCallback(errors) }
        error("no OpenGL 4.3+ core context")
    }

    private val pbo = IntArray(2)
    private var pboW = 0
    private var pboH = 0
    private var frameNo = 0L

    private fun loop() {
        var curW = -1; var curH = -1
        var fpsFrames = 0; var fpsStart = System.nanoTime()
        while (running) {
            if (paused) { Thread.sleep(50); continue }
            val start = System.nanoTime()
            val w = wantW; val h = wantH
            if (w != curW || h != curH) {
                listener.reshape(w, h)
                curW = w; curH = h
            }
            val fbo = listener.display()
            present(fbo, w, h)
            fpsFrames++
            val now = System.nanoTime()
            if (now - fpsStart >= 500_000_000L) { fps = fpsFrames * 1e9 / (now - fpsStart); fpsFrames = 0; fpsStart = now }
            val budget = 1_000_000_000L / targetFps.coerceIn(5, 240)
            val left = budget - (System.nanoTime() - start)
            if (left > 1_000_000L) Thread.sleep(left / 1_000_000L, (left % 1_000_000L).toInt())
        }
        if (pbo[0] != 0) Gl.glDeleteBuffers(2, pbo, 0)
    }

    /**
     * Copies the finished picture to JavaFX. The read goes into one of two pixel buffers on the card and the one from
     * the frame before is copied out, so the CPU never waits for the card to finish drawing.
     */
    private fun present(fbo: Int, w: Int, h: Int) {
        // the picture's alpha isn't always 1 (shaders write it); JavaFX would blend it with what's behind
        Gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, fbo)
        Gl.glDrawBuffer(GLC.GL_COLOR_ATTACHMENT0)
        Gl.glColorMask(false, false, false, true)
        Gl.glClearColor(0f, 0f, 0f, 1f)
        Gl.glClear(GLC.GL_COLOR_BUFFER_BIT)
        Gl.glColorMask(true, true, true, true)
        Gl.glBindFramebuffer(GLC.GL_DRAW_FRAMEBUFFER, 0)

        if (pbo[0] == 0) Gl.glGenBuffers(2, pbo, 0)
        if (w != pboW || h != pboH) {
            for (b in pbo) { Gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, b); Gl.glBufferData(GLC.GL_PIXEL_PACK_BUFFER, w * h * 4L, null, GLC.GL_STREAM_READ) }
            pboW = w; pboH = h; frameNo = 0
        }
        val write = (frameNo and 1L).toInt()
        Gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, fbo)
        Gl.glReadBuffer(GLC.GL_COLOR_ATTACHMENT0)
        Gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pbo[write])
        Gl.glReadPixels(0, 0, w, h, GLC.GL_BGRA, GLC.GL_UNSIGNED_INT_8_8_8_8_REV, 0L)
        Gl.glBindFramebuffer(GLC.GL_READ_FRAMEBUFFER, 0)
        if (frameNo > 0 && !handing.get()) {
            Gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, pbo[1 - write])
            val mapped = Gl.glMapBuffer(GLC.GL_PIXEL_PACK_BUFFER, GLC.GL_READ_ONLY)
            val back = 1 - shown
            val f = frames[back]?.takeIf { it.w == w && it.h == h } ?: Frame(w, h).also { frames[back] = it }
            f.bytes.clear()
            MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), MemoryUtil.memAddress(f.bytes), w * h * 4L)
            Gl.glUnmapBuffer(GLC.GL_PIXEL_PACK_BUFFER)
            handing.set(true)
            Platform.runLater {
                try {
                    f.pixels.updateBuffer { null }
                    if (image.image !== f.image) image.image = f.image
                    shown = back
                } finally { handing.set(false) }
            }
        }
        Gl.glBindBuffer(GLC.GL_PIXEL_PACK_BUFFER, 0)
        frameNo++
    }

    companion object {
        private val GLFW_LOCK = Any()
        private var glfwStarted = false
    }
}
