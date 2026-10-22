package com.gadscape.network.rsprot

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.rsprot.buffer.extensions.g4
import net.rsprot.buffer.extensions.g8
import net.rsprot.buffer.extensions.gVarInt2
import net.rsprot.buffer.extensions.gVarInt2s
import net.rsprot.buffer.extensions.gjstr
import net.rsprot.crypto.cipher.NopStreamCipher
import net.rsprot.crypto.cipher.StreamCipher
import net.rsprot.protocol.game.outgoing.misc.player.RunClientScript
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RUNCLIENTSCRIPT array arguments (E2, WU-02): the values go through [RsprotOut.toClientScriptValue] /
 * [RsprotOut.runClientScript], are encoded by rsprot's own RUNCLIENTSCRIPT encoder, and are read back the way
 * the 241 client reads the packet (type string, then the values last to first, then the script id).
 */
class RunClientScriptArraysTest {

    /** Reads an encoded RUNCLIENTSCRIPT like the client: returns (script id, values in argument order). */
    private fun readBack(buf: ByteBuf): Pair<Int, List<Any>> {
        val types = buf.gjstr()
        val values = arrayOfNulls<Any>(types.length)
        for (i in types.length - 1 downTo 0) {
            values[i] = when (types[i]) {
                'W' -> IntArray(buf.gVarInt2()) { buf.gVarInt2s() }
                'X' -> Array(buf.gVarInt2()) { buf.gjstr() }
                's' -> buf.gjstr()
                'Ï' -> buf.g8()
                else -> buf.g4()
            }
        }
        val id = buf.g4()
        assertEquals(0, buf.readableBytes(), "trailing bytes")
        return id to values.map { it!! }
    }

    private fun encode(message: RunClientScript): ByteBuf {
        // rsprot's own encoder (osrs-241-desktop is only on :core's runtime classpath, hence the reflection;
        // its encode takes the JagByteBuf value class, i.e. a ByteBuf at runtime)
        val buf = Unpooled.buffer()
        val encoderClass = Class.forName("net.rsprot.protocol.game.outgoing.codec.misc.player.RunClientScriptEncoder")
        val encoder = encoderClass.getDeclaredConstructor().newInstance()
        val encode = encoderClass.methods.first {
            it.name.startsWith("encode") && it.parameterCount == 3 && it.parameterTypes[0] == StreamCipher::class.java &&
                it.parameterTypes[1] == ByteBuf::class.java && it.parameterTypes[2] == RunClientScript::class.java
        }
        encode.invoke(encoder, NopStreamCipher, buf, message)
        return buf
    }

    @Test
    fun `script 10139 room splits with three arrays round-trips`() {
        // 10139(int overallTicks, int state, stringarray roomNames, intarray roomTicks, graphicarray icons) [CACHE 2746]
        val names = arrayOf("Archive Guardian", "Ezekiel", "Moia", "Xarth", "Ocellus", "Overall")
        val ticks = listOf(345, 610, 702, 655, 1488, 3800)
        val icons = intArrayOf(8730, 8734, 8735, 8732, 8737, -1)
        val message = assertNotNull(RsprotOut.runClientScript(10139, arrayOf(3800, 4, names, ticks, icons)))
        assertEquals(10139, message.id)
        assertContentEquals(charArrayOf('i', 'i', 'X', 'W', 'W'), message.types)

        val (id, values) = readBack(encode(message))
        assertEquals(10139, id)
        assertEquals(3800, values[0])
        assertEquals(4, values[1])
        @Suppress("UNCHECKED_CAST")
        assertContentEquals(names, values[2] as Array<String>)
        assertContentEquals(ticks.toIntArray(), values[3] as IntArray)
        assertContentEquals(icons, values[4] as IntArray)
    }

    @Test
    fun `array-like values become int or string arrays`() {
        assertContentEquals(intArrayOf(1, -1, 3), RsprotOut.toClientScriptValue(listOf(1, null, 3L)) as IntArray)
        assertContentEquals(intArrayOf(1, 0), RsprotOut.toClientScriptValue(arrayOf(true, false)) as IntArray)
        assertContentEquals(intArrayOf(7, 8), RsprotOut.toClientScriptValue(shortArrayOf(7, 8)) as IntArray)
        @Suppress("UNCHECKED_CAST")
        val strings = RsprotOut.toClientScriptValue(listOf("a", null, "c")) as Array<String>
        assertContentEquals(arrayOf("a", "", "c"), strings)
        @Suppress("UNCHECKED_CAST")
        val empty = RsprotOut.toClientScriptValue(emptyArray<String>()) as Array<String>
        assertEquals(0, empty.size)
        assertContentEquals(intArrayOf(), RsprotOut.toClientScriptValue(emptyList<Int>()) as IntArray)
        assertTrue(RsprotOut.toClientScriptValue(null) == -1)
        assertTrue(RsprotOut.toClientScriptValue(true) == 1)
        assertTrue(RsprotOut.toClientScriptValue(5L) == 5L)
    }

    @Test
    fun `scalars are unchanged`() {
        val message = assertNotNull(RsprotOut.runClientScript(10152, arrayOf(false)))
        val (id, values) = readBack(encode(message))
        assertEquals(10152, id)
        assertEquals(listOf<Any>(0), values)

        val mixed = assertNotNull(RsprotOut.runClientScript(10136, arrayOf(0, 1200, 340, 1, 2, "Zezima")))
        val (_, back) = readBack(encode(mixed))
        assertEquals(listOf<Any>(0, 1200, 340, 1, 2, "Zezima"), back)
    }
}
