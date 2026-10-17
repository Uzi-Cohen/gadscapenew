package controllers.worldRenderer.helpers

import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryUtil
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.IntBuffer

/**
 * OpenGL for the 3D view, through LWJGL 3. Same call shapes the renderer was written against (JOGL's: arrays with an
 * offset, a size next to each buffer), so the renderer code reads the same. Every call needs the render thread's
 * context current (see [GlView]).
 */
object Gl {
    private fun addr(b: Buffer?) = if (b == null) 0L else MemoryUtil.memAddress(b)

    /**
     * [b] in native memory: OpenGL reads pixels / data straight from an address, which a heap buffer (ByteBuffer.wrap)
     * doesn't have, so those are copied first (what JOGL did on its own).
     */
    private inline fun <T> withDirect(b: Buffer?, block: (Long) -> T): T {
        if (b == null || b.isDirect) return block(addr(b))
        val bytes = when (b) {
            is ByteBuffer -> b.remaining()
            is java.nio.IntBuffer, is java.nio.FloatBuffer -> b.remaining() * 4
            is java.nio.ShortBuffer -> b.remaining() * 2
            else -> error("unsupported buffer ${b.javaClass.simpleName}")
        }
        val tmp = MemoryUtil.memAlloc(bytes)
        try {
            when (b) {
                is ByteBuffer -> tmp.put(b.duplicate())
                is java.nio.IntBuffer -> tmp.asIntBuffer().put(b.duplicate())
                is java.nio.FloatBuffer -> tmp.asFloatBuffer().put(b.duplicate())
                is java.nio.ShortBuffer -> tmp.asShortBuffer().put(b.duplicate())
                else -> {}
            }
            return block(MemoryUtil.memAddress0(tmp))
        } finally { MemoryUtil.memFree(tmp) }
    }

    fun glActiveTexture(t: Int) = GL13C.glActiveTexture(t)
    fun glAttachShader(p: Int, s: Int) = GL20C.glAttachShader(p, s)
    fun glBindBuffer(t: Int, b: Int) = GL15C.glBindBuffer(t, b)
    fun glBindBufferBase(t: Int, i: Int, b: Int) = GL30C.glBindBufferBase(t, i, b)
    fun glBindFramebuffer(t: Int, f: Int) = GL30C.glBindFramebuffer(t, f)
    fun glBindRenderbuffer(t: Int, r: Int) = GL30C.glBindRenderbuffer(t, r)
    fun glBindTexture(t: Int, tex: Int) = GL11C.glBindTexture(t, tex)
    fun glBindVertexArray(v: Int) = GL30C.glBindVertexArray(v)
    fun glBlendFunc(s: Int, d: Int) = GL11C.glBlendFunc(s, d)
    fun glBlitFramebuffer(sx0: Int, sy0: Int, sx1: Int, sy1: Int, dx0: Int, dy0: Int, dx1: Int, dy1: Int, mask: Int, filter: Int) =
        GL30C.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter)
    /** [size] bytes from [data]'s position (or uninitialised when null). */
    fun glBufferData(t: Int, size: Long, data: Buffer?, usage: Int) =
        if (data == null) GL15C.glBufferData(t, size, usage) else withDirect(data) { GL15C.nglBufferData(t, size, it, usage) }
    fun glBufferSubData(t: Int, offset: Long, size: Long, data: Buffer) = withDirect(data) { GL15C.nglBufferSubData(t, offset, size, it) }
    fun glCheckFramebufferStatus(t: Int) = GL30C.glCheckFramebufferStatus(t)
    fun glClear(mask: Int) = GL11C.glClear(mask)
    fun glClearColor(r: Float, g: Float, b: Float, a: Float) = GL11C.glClearColor(r, g, b, a)
    fun glColorMask(r: Boolean, g: Boolean, b: Boolean, a: Boolean) = GL11C.glColorMask(r, g, b, a)
    fun glCompileShader(s: Int) = GL20C.glCompileShader(s)
    fun glCreateProgram() = GL20C.glCreateProgram()
    fun glCreateShader(t: Int) = GL20C.glCreateShader(t)
    fun glDeleteBuffers(n: Int, ids: IntArray, offset: Int) = GL15C.glDeleteBuffers(ids.copyOfRange(offset, offset + n))
    fun glDeleteFramebuffers(n: Int, ids: IntArray, offset: Int) = GL30C.glDeleteFramebuffers(ids.copyOfRange(offset, offset + n))
    fun glDeleteProgram(p: Int) = GL20C.glDeleteProgram(p)
    fun glDeleteRenderbuffers(n: Int, ids: IntArray, offset: Int) = GL30C.glDeleteRenderbuffers(ids.copyOfRange(offset, offset + n))
    fun glDeleteShader(s: Int) = GL20C.glDeleteShader(s)
    fun glDeleteTextures(n: Int, ids: IntArray, offset: Int) = GL11C.glDeleteTextures(ids.copyOfRange(offset, offset + n))
    fun glDeleteVertexArrays(n: Int, ids: IntArray, offset: Int) = GL30C.glDeleteVertexArrays(ids.copyOfRange(offset, offset + n))
    fun glDepthFunc(f: Int) = GL11C.glDepthFunc(f)
    fun glDepthRangef(n: Float, f: Float) = GL41C.glDepthRangef(n, f)
    fun glDetachShader(p: Int, s: Int) = GL20C.glDetachShader(p, s)
    fun glDisable(c: Int) = GL11C.glDisable(c)
    fun glDispatchCompute(x: Int, y: Int, z: Int) = GL43C.glDispatchCompute(x, y, z)
    fun glDrawArrays(mode: Int, first: Int, count: Int) = GL11C.glDrawArrays(mode, first, count)
    fun glDrawBuffer(b: Int) = GL11C.glDrawBuffer(b)
    fun glDrawBuffers(n: Int, bufs: IntBuffer) = withDirect(bufs) { GL20C.nglDrawBuffers(n, it) }
    fun glEnable(c: Int) = GL11C.glEnable(c)
    fun glEnableVertexAttribArray(i: Int) = GL20C.glEnableVertexAttribArray(i)
    fun glFramebufferRenderbuffer(t: Int, a: Int, rt: Int, r: Int) = GL30C.glFramebufferRenderbuffer(t, a, rt, r)
    fun glFramebufferTexture2D(t: Int, a: Int, tt: Int, tex: Int, level: Int) = GL30C.glFramebufferTexture2D(t, a, tt, tex, level)
    fun glGenBuffers(n: Int, ids: IntArray, offset: Int) { val t = IntArray(n); GL15C.glGenBuffers(t); t.copyInto(ids, offset) }
    fun glGenFramebuffers(n: Int, ids: IntArray, offset: Int) { val t = IntArray(n); GL30C.glGenFramebuffers(t); t.copyInto(ids, offset) }
    fun glGenRenderbuffers(n: Int, ids: IntArray, offset: Int) { val t = IntArray(n); GL30C.glGenRenderbuffers(t); t.copyInto(ids, offset) }
    fun glGenTextures(n: Int, ids: IntArray, offset: Int) { val t = IntArray(n); GL11C.glGenTextures(t); t.copyInto(ids, offset) }
    fun glGenVertexArrays(n: Int, ids: IntArray, offset: Int) { val t = IntArray(n); GL30C.glGenVertexArrays(t); t.copyInto(ids, offset) }
    fun glGetInteger(pname: Int) = GL11C.glGetInteger(pname)
    fun glGetProgrami(p: Int, pname: Int) = GL20C.glGetProgrami(p, pname)
    fun glGetProgramInfoLog(p: Int): String = GL20C.glGetProgramInfoLog(p)
    fun glGetShaderi(s: Int, pname: Int) = GL20C.glGetShaderi(s, pname)
    fun glGetShaderInfoLog(s: Int): String = GL20C.glGetShaderInfoLog(s)
    fun glGetUniformBlockIndex(p: Int, name: String) = GL31C.glGetUniformBlockIndex(p, name)
    fun glGetUniformLocation(p: Int, name: String) = GL20C.glGetUniformLocation(p, name)
    fun glIsEnabled(c: Int) = GL11C.glIsEnabled(c)
    fun glLinkProgram(p: Int) = GL20C.glLinkProgram(p)
    fun glMapBuffer(t: Int, access: Int): ByteBuffer = GL15C.glMapBuffer(t, access) ?: error("glMapBuffer failed")
    fun glMemoryBarrier(bits: Int) = GL42C.glMemoryBarrier(bits)
    fun glPixelStorei(p: Int, v: Int) = GL11C.glPixelStorei(p, v)
    fun glReadBuffer(b: Int) = GL11C.glReadBuffer(b)
    /** Into the bound pixel pack buffer, at [offset]. */
    fun glReadPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, offset: Long) = GL11C.glReadPixels(x, y, w, h, format, type, offset)
    fun glReadPixels(x: Int, y: Int, w: Int, h: Int, format: Int, type: Int, into: Buffer) {
        require(into.isDirect) { "glReadPixels needs a direct buffer" }
        GL11C.nglReadPixels(x, y, w, h, format, type, addr(into))
    }
    fun glRenderbufferStorage(t: Int, f: Int, w: Int, h: Int) = GL30C.glRenderbufferStorage(t, f, w, h)
    fun glRenderbufferStorageMultisample(t: Int, samples: Int, f: Int, w: Int, h: Int) = GL30C.glRenderbufferStorageMultisample(t, samples, f, w, h)
    fun glShaderSource(s: Int, source: String) = GL20C.glShaderSource(s, source)
    fun glTexImage2D(t: Int, level: Int, internal: Int, w: Int, h: Int, border: Int, format: Int, type: Int, pixels: Buffer?) =
        withDirect(pixels) { GL11C.nglTexImage2D(t, level, internal, w, h, border, format, type, it) }
    fun glTexImage2DMultisample(t: Int, samples: Int, internal: Int, w: Int, h: Int, fixed: Boolean) = GL32C.glTexImage2DMultisample(t, samples, internal, w, h, fixed)
    fun glTexParameteri(t: Int, p: Int, v: Int) = GL11C.glTexParameteri(t, p, v)
    fun glTexStorage3D(t: Int, levels: Int, internal: Int, w: Int, h: Int, d: Int) = GL42C.glTexStorage3D(t, levels, internal, w, h, d)
    fun glTexSubImage3D(t: Int, level: Int, x: Int, y: Int, z: Int, w: Int, h: Int, d: Int, format: Int, type: Int, pixels: Buffer) =
        withDirect(pixels) { GL12C.nglTexSubImage3D(t, level, x, y, z, w, h, d, format, type, it) }
    fun glUniform1f(l: Int, v: Float) = GL20C.glUniform1f(l, v)
    fun glUniform1i(l: Int, v: Int) = GL20C.glUniform1i(l, v)
    fun glUniform2f(l: Int, a: Float, b: Float) = GL20C.glUniform2f(l, a, b)
    /** [count] vec2s from [v] at [offset]. */
    fun glUniform2fv(l: Int, count: Int, v: FloatArray, offset: Int) = GL20C.glUniform2fv(l, v.copyOfRange(offset, minOf(v.size, offset + count * 2)))
    fun glUniform2i(l: Int, a: Int, b: Int) = GL20C.glUniform2i(l, a, b)
    fun glUniformBlockBinding(p: Int, block: Int, binding: Int) = GL31C.glUniformBlockBinding(p, block, binding)
    fun glUniformMatrix4fv(l: Int, count: Int, transpose: Boolean, v: FloatArray, offset: Int) = GL20C.glUniformMatrix4fv(l, transpose, v.copyOfRange(offset, offset + 16 * count))
    fun glUnmapBuffer(t: Int) = GL15C.glUnmapBuffer(t)
    fun glUseProgram(p: Int) = GL20C.glUseProgram(p)
    fun glValidateProgram(p: Int) = GL20C.glValidateProgram(p)
    fun glVertexAttribIPointer(i: Int, size: Int, type: Int, stride: Int, offset: Long) = GL30C.glVertexAttribIPointer(i, size, type, stride, offset)
    fun glVertexAttribPointer(i: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Long) = GL20C.glVertexAttribPointer(i, size, type, normalized, stride, offset)
    fun glViewport(x: Int, y: Int, w: Int, h: Int) = GL11C.glViewport(x, y, w, h)
}

/** The OpenGL constants the 3D view uses (values from the OpenGL spec, as LWJGL defines them). */
object GLC {
    const val GL_ARRAY_BUFFER = 0x8892
    const val GL_BGRA_INTEGER = 0x8D9B
    const val GL_BLEND = 0xBE2
    const val GL_CLAMP_TO_EDGE = 0x812F
    const val GL_COLOR_ATTACHMENT0 = 0x8CE0
    const val GL_COLOR_ATTACHMENT1 = 0x8CE1
    const val GL_COLOR_BUFFER_BIT = 0x4000
    const val GL_COMPILE_STATUS = 0x8B81
    const val GL_COMPUTE_SHADER = 0x91B9
    const val GL_CULL_FACE = 0xB44
    const val GL_DEPTH_ATTACHMENT = 0x8D00
    const val GL_DEPTH_BUFFER_BIT = 0x100
    const val GL_DEPTH_COMPONENT = 0x1902
    const val GL_DEPTH_TEST = 0xB71
    const val GL_DRAW_FRAMEBUFFER = 0x8CA9
    const val GL_DYNAMIC_DRAW = 0x88E8
    const val GL_FALSE = 0x0
    const val GL_FLOAT = 0x1406
    const val GL_FRAGMENT_SHADER = 0x8B30
    const val GL_FRAMEBUFFER = 0x8D40
    const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
    const val GL_GEOMETRY_SHADER = 0x8DD9
    const val GL_INT = 0x1404
    const val GL_LEQUAL = 0x203
    const val GL_LINK_STATUS = 0x8B82
    const val GL_MAX_SAMPLES = 0x8D57
    const val GL_MULTISAMPLE = 0x809D
    const val GL_NEAREST = 0x2600
    const val GL_ONE_MINUS_SRC_ALPHA = 0x303
    const val GL_PIXEL_PACK_BUFFER = 0x88EB
    const val GL_R32I = 0x8235
    const val GL_R8 = 0x8229
    const val GL_READ_FRAMEBUFFER = 0x8CA8
    const val GL_READ_ONLY = 0x88B8
    const val GL_RED = 0x1903
    const val GL_RED_INTEGER = 0x8D94
    const val GL_RENDERBUFFER = 0x8D41
    const val GL_RGBA = 0x1908
    const val GL_RGBA8 = 0x8058
    const val GL_SHADER_STORAGE_BARRIER_BIT = 0x2000
    const val GL_SHADER_STORAGE_BUFFER = 0x90D2
    const val GL_SRC_ALPHA = 0x302
    const val GL_STATIC_COPY = 0x88E6
    const val GL_STREAM_DRAW = 0x88E0
    const val GL_STREAM_READ = 0x88E1
    const val GL_TEXTURE0 = 0x84C0
    const val GL_TEXTURE1 = 0x84C1
    const val GL_TEXTURE2 = 0x84C2
    const val GL_TEXTURE_2D = 0xDE1
    const val GL_TEXTURE_2D_ARRAY = 0x8C1A
    const val GL_TEXTURE_2D_MULTISAMPLE = 0x9100
    const val GL_TEXTURE_MAG_FILTER = 0x2800
    const val GL_TEXTURE_MIN_FILTER = 0x2801
    const val GL_TEXTURE_WRAP_S = 0x2802
    const val GL_TEXTURE_WRAP_T = 0x2803
    const val GL_TRIANGLES = 0x4
    const val GL_TRUE = 0x1
    const val GL_UNIFORM_BUFFER = 0x8A11
    const val GL_UNPACK_ALIGNMENT = 0xCF5
    const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_VALIDATE_STATUS = 0x8B83
    const val GL_VERTEX_SHADER = 0x8B31
    const val GL_BGRA = 0x80E1
    const val GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367
}
