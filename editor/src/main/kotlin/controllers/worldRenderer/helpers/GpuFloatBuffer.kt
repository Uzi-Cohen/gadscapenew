package controllers.worldRenderer.helpers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GpuFloatBuffer {
    var buffer = allocateDirect(65536)
        private set

    fun put(texture: Float, u: Float, v: Float, pad: Float) {
        buffer.put(texture).put(u).put(v).put(pad)
    }

    fun flip() {
        buffer.flip()
    }

    fun clear() {
        buffer.clear()
    }

    /** Gives back the memory of a buffer that grew large (it starts small again). */
    fun shrink() {
        if (buffer.capacity() > 65536) buffer = allocateDirect(65536) else buffer.clear()
    }

    fun ensureCapacity(size: Int) {
        var capacity = buffer.capacity()
        val position = buffer.position()
        if (capacity - position < size) {
            do {
                capacity *= 2
            } while (capacity - position < size)
            val newB = allocateDirect(capacity)
            buffer.flip()
            newB.put(buffer)
            buffer = newB
        }
    }

    companion object {
        fun allocateDirect(size: Int): FloatBuffer {
            return ByteBuffer.allocateDirect(size * java.lang.Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        }
    }
}