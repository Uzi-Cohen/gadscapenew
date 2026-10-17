package controllers.worldRenderer.shaders

import controllers.worldRenderer.helpers.GLC
import controllers.worldRenderer.helpers.Gl
import controllers.worldRenderer.helpers.GLUtil
import controllers.worldRenderer.helpers.GLUtil.glGetProgramInfoLog
import java.util.*


class Shader {
    private val units: MutableList<Unit> = ArrayList()

    internal class Unit(internal val type: Int, internal val filename: String)

    fun add(type: Int, name: String): Shader {
        units.add(Unit(type, name))
        return this
    }

    @Throws(ShaderException::class)
    fun compile(gl: Gl, template: Template): Int {
        val program = gl.glCreateProgram()
        val shaders = IntArray(units.size)
        var i = 0
        var ok = false
        try {
            while (i < shaders.size) {
                val unit = units[i]
                val shader = gl.glCreateShader(unit.type)
                val source: String = template.load(unit.filename)
                gl.glShaderSource(shader, source)
                gl.glCompileShader(shader)
                if (GLUtil.glGetShader(gl, shader, GLC.GL_COMPILE_STATUS) != GLC.GL_TRUE) {
                    val err: String = GLUtil.glGetShaderInfoLog(gl, shader)
                    gl.glDeleteShader(shader)
                    throw ShaderException(err)
                }
                gl.glAttachShader(program, shader)
                shaders[i++] = shader
            }
            gl.glLinkProgram(program)
            if (GLUtil.glGetProgram(gl, program, GLC.GL_LINK_STATUS) == GLC.GL_FALSE) {
                val err: String = glGetProgramInfoLog(gl, program)
                throw ShaderException(err)
            }
            // validation checks the program against the current GL state (in a core context that includes "is a
            // vertex array bound", which it isn't yet here), so a failure is only reported, not fatal
            gl.glValidateProgram(program)
            if (GLUtil.glGetProgram(gl, program, GLC.GL_VALIDATE_STATUS) == GLC.GL_FALSE) {
                println("Shader check (not fatal): " + glGetProgramInfoLog(gl, program).trim())
            }
            ok = true
        } finally {
            while (i > 0) {
                val shader = shaders[--i]
                gl.glDetachShader(program, shader)
                gl.glDeleteShader(shader)
            }
            if (!ok) {
                gl.glDeleteProgram(program)
            }
        }
        return program
    }

    companion object {
        const val LINUX_VERSION_HEADER = "#version 420\n" +
                "#extension GL_ARB_compute_shader : require\n" +
                "#extension GL_ARB_shader_storage_buffer_object : require\n" +
                "#extension GL_ARB_explicit_attrib_location : require\n"
        const val WINDOWS_VERSION_HEADER = "#version 430\n"
        val PROGRAM = Shader()
            .add(GLC.GL_VERTEX_SHADER, "/gpu/vert.glsl")
            .add(GLC.GL_GEOMETRY_SHADER, "/gpu/geom.glsl")
            .add(GLC.GL_FRAGMENT_SHADER, "/gpu/frag.glsl")
        val COMPUTE_PROGRAM = Shader()
            .add(GLC.GL_COMPUTE_SHADER, "/gpu/comp.glsl")
        val SMALL_COMPUTE_PROGRAM = Shader()
            .add(GLC.GL_COMPUTE_SHADER, "/gpu/comp_small.glsl")
        val UNORDERED_COMPUTE_PROGRAM = Shader()
            .add(GLC.GL_COMPUTE_SHADER, "/gpu/comp_unordered.glsl")
    }
}