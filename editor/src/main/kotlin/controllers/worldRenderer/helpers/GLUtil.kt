package controllers.worldRenderer.helpers

object GLUtil {
    private val buf = IntArray(1)
    fun glGetInteger(gl: Gl, pname: Int): Int = gl.glGetInteger(pname)

    fun glGetShader(gl: Gl, shader: Int, pname: Int): Int = gl.glGetShaderi(shader, pname)

    fun glGetProgram(gl: Gl, program: Int, pname: Int): Int = gl.glGetProgrami(program, pname)

    fun glGetShaderInfoLog(gl: Gl, shader: Int): String = gl.glGetShaderInfoLog(shader)

    fun glGetProgramInfoLog(gl: Gl, program: Int): String = gl.glGetProgramInfoLog(program)

    fun glGenVertexArrays(gl: Gl): Int {
        gl.glGenVertexArrays(1, buf, 0)
        return buf[0]
    }

    fun glDeleteVertexArrays(gl: Gl, vertexArray: Int) {
        buf[0] = vertexArray
        gl.glDeleteVertexArrays(1, buf, 0)
    }

    fun glGenBuffers(gl: Gl): Int {
        gl.glGenBuffers(1, buf, 0)
        return buf[0]
    }

    fun glDeleteBuffer(gl: Gl, buffer: Int) {
        buf[0] = buffer
        gl.glDeleteBuffers(1, buf, 0)
    }

    fun glDeleteBuffers(gl: Gl, buffer: IntArray) {
        gl.glDeleteBuffers(1, buffer, 0)
    }

    fun glGenTexture(gl: Gl): Int {
        gl.glGenTextures(1, buf, 0)
        return buf[0]
    }

    fun glDeleteTexture(gl: Gl, texture: Int) {
        buf[0] = texture
        gl.glDeleteTextures(1, buf, 0)
    }

    fun glGenFrameBuffer(gl: Gl): Int {
        gl.glGenFramebuffers(1, buf, 0)
        return buf[0]
    }

    fun glDeleteFrameBuffer(gl: Gl, frameBuffer: Int) {
        buf[0] = frameBuffer
        gl.glDeleteFramebuffers(1, buf, 0)
    }

    fun glGenRenderbuffer(gl: Gl): Int {
        gl.glGenRenderbuffers(1, buf, 0)
        return buf[0]
    }

    fun glDeleteRenderbuffers(gl: Gl, renderBuffer: Int) {
        buf[0] = renderBuffer
        gl.glDeleteRenderbuffers(1, buf, 0)
    }
}
