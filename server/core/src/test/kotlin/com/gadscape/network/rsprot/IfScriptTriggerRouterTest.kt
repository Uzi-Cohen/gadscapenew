package com.gadscape.network.rsprot

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.rsprot.buffer.extensions.p2Alt1
import net.rsprot.buffer.extensions.p4Alt2
import net.rsprot.buffer.extensions.pSmart1or2
import net.rsprot.buffer.extensions.pVarInt2s
import net.rsprot.buffer.extensions.pjstr
import net.rsprot.protocol.game.incoming.buttons.IfScriptTrigger
import net.rsprot.protocol.game.incoming.buttons.IfScriptTrigger.ParameterTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * IF_SCRIPT_TRIGGER decoding (E1, WU-02). Each payload is built byte for byte the way the 241 client sends the
 * packet for one `if_runscript*` call site in the 2746 scripts (osrs-dumps 6e5033af), then decoded by rsprot's
 * own IF_SCRIPT_TRIGGER decoder and the router's typed decode:
 * combinedId (g4Alt2), obj (g2Alt1), sub (g2Alt1), crc (g4Alt2), then each argument (int = var-int, string =
 * jstr, arrays = smart count + elements).
 */
class IfScriptTriggerRouterTest {

    private fun packet(interfaceId: Int, componentId: Int, crc: Int, sub: Int = -1, obj: Int = -1, args: (ByteBuf) -> Unit): IfScriptTrigger {
        val buf = Unpooled.buffer()
        buf.p4Alt2(interfaceId shl 16 or componentId)
        buf.p2Alt1(obj and 0xFFFF)
        buf.p2Alt1(sub and 0xFFFF)
        buf.p4Alt2(crc)
        args(buf)
        return decodeWithRsprot(buf)
    }

    /**
     * rsprot's own IF_SCRIPT_TRIGGER decoder (osrs-241-desktop is only on the runtime classpath of :core, so it is
     * looked up reflectively; its decode takes the JagByteBuf value class, i.e. a ByteBuf at runtime).
     */
    private fun decodeWithRsprot(buf: ByteBuf): IfScriptTrigger {
        val decoderClass = Class.forName("net.rsprot.protocol.game.incoming.codec.buttons.IfScriptTriggerDecoder")
        val decoder = decoderClass.getDeclaredConstructor().newInstance()
        val decode = decoderClass.methods.first {
            it.name.startsWith("decode") && it.parameterCount == 1 && it.parameterTypes[0] == ByteBuf::class.java &&
                it.returnType == IfScriptTrigger::class.java
        }
        return decode.invoke(decoder, buf) as IfScriptTrigger
    }

    private fun decode(message: IfScriptTrigger, signature: String): ScriptTrigger =
        IfScriptTriggerRouter.decode(message, IfScriptTriggerRouter.parameterTypes(signature))

    @Test
    fun `spectate slot trigger 2481737999 on 974-5 (script 10040)`() {
        // script10040(int $int0, int $int1): if_runscript*(ifscript_2481737999, tfa_hud:content_names, -1)($int0, $int1)
        val crc = 2481737999L.toInt()
        assertEquals(-1813229297, crc)
        val viewSlot = packet(974, 5, crc) { it.pVarInt2s(3); it.pVarInt2s(1) }
        assertEquals(974, viewSlot.interfaceId)
        assertEquals(5, viewSlot.componentId)
        assertEquals(-1, viewSlot.sub)
        assertEquals(-1, viewSlot.obj)
        assertEquals(crc, viewSlot.crc)
        val trigger = decode(viewSlot, "int int")
        assertEquals(listOf<Any>(3, 1), trigger.args)
        assertEquals(3, trigger.int(0))
        assertEquals(1, trigger.int(1))
        assertEquals(974, trigger.interfaceId)
        assertEquals(5, trigger.componentId)

        // slot -1 = room view: a negative var-int
        val room = decode(packet(974, 5, crc) { it.pVarInt2s(-1); it.pVarInt2s(1) }, "int int")
        assertEquals(-1, room.int(0))
    }

    @Test
    fun `hud action trigger 1373305897 on 974-1 (script 10035)`() {
        // script10035(component, int, int $int2): if_runscript*(ifscript_1373305897, tfa_hud:trigger_generic, -1)($int2)
        for (action in intArrayOf(1, 2)) {
            val trigger = decode(packet(974, 1, 1373305897) { it.pVarInt2s(action) }, "int")
            assertEquals(1, trigger.size)
            assertEquals(action, trigger.int(0))
        }
    }

    @Test
    fun `stats category trigger 2359217665 on 979-34 (proc 10123)`() {
        // if_runscript*(ifscript_2359217665, tfa_stats:all_stats_tables, -1)($dbrow): dbrow travels as an int
        val crc = 2359217665L.toInt()
        assertEquals(-1935749631, crc)
        for (category in intArrayOf(17133, 17134, 17139)) {
            val trigger = decode(packet(979, 34, crc) { it.pVarInt2s(category) }, "dbrow")
            assertEquals(category, trigger.int(0))
        }
    }

    @Test
    fun `omnishop buy trigger 407137374 on 819-4 (proc omnishop_main_buy)`() {
        // omnishop_main_list_item_op case 3 -> omnishop_main_buy($dbrow shop, $int2 stock index, q2 key)
        // Alliance Supplies 17150, row 4 = Manta ray, omnishop_buy_q2 = 5 (enum_5844: buy 2)
        val message = packet(819, 4, 407137374) { it.pVarInt2s(17150); it.pVarInt2s(4); it.pVarInt2s(5) }
        val trigger = decode(message, "dbrow int int")
        assertEquals(listOf<Any>(17150, 4, 5), trigger.args)
        assertEquals(819, trigger.interfaceId)
        assertEquals(4, trigger.componentId)
    }

    @Test
    fun `omnishop examine trigger 3336794041 carries an obj`() {
        val crc = 3336794041L.toInt()
        assertEquals(-958173255, crc)
        val trigger = decode(packet(819, 3, crc) { it.pVarInt2s(10_000) }, "obj")
        assertEquals(10_000, trigger.int(0))
    }

    @Test
    fun `strings and arrays decode with their codes`() {
        val message = packet(974, 1, 12345, sub = 7, obj = 4151) {
            it.pjstr("Ocellus")
            it.pSmart1or2(3)
            it.pVarInt2s(1); it.pVarInt2s(-2); it.pVarInt2s(300_000)
            it.pSmart1or2(2)
            it.pjstr("a"); it.pjstr("b")
        }
        assertEquals(7, message.sub)
        assertEquals(4151, message.obj)
        val trigger = decode(message, "string intarray stringarray")
        assertEquals("Ocellus", trigger.string(0))
        assertContentEquals(intArrayOf(1, -2, 300_000), trigger.intArray(1))
        assertContentEquals(arrayOf("a", "b"), trigger.stringArray(2))
    }

    @Test
    fun `a payload that does not match the signature is rejected`() {
        // two ints sent, one declared: rsprot refuses the leftover bytes
        assertFailsWith<IllegalStateException> {
            decode(packet(974, 5, 1) { it.pVarInt2s(3); it.pVarInt2s(1) }, "int")
        }
    }

    @Test
    fun `cs2 type names map to rsprot codes`() {
        assertContentEquals(charArrayOf('i', 'i', 'i'), IfScriptTriggerRouter.parameterTypes("dbrow, int, int").chars)
        assertContentEquals(charArrayOf('s', 'X', 'W', 'W', 'i'),
            IfScriptTriggerRouter.parameterTypes("string stringarray intarray graphicarray boolean").chars)
        assertContentEquals(charArrayOf('i', 's', 'W', 'X'), IfScriptTriggerRouter.parameterTypes("i s W X").chars)
        assertEquals(ParameterTypes(charArrayOf()), IfScriptTriggerRouter.parameterTypes(""))
        assertEquals(2481737999L, IfScriptTriggerRouter.unsigned(-1813229297))
    }

    @Test
    fun `routes register by signed and unsigned crc`() {
        val route = IfScriptTriggerRouter.register(2481737999L, "int int", 974, 5) { _, _ -> }
        try {
            assertEquals(-1813229297, route.crc)
            assertTrue(IfScriptTriggerRouter.isRegistered(-1813229297))
            assertContentEquals(charArrayOf('i', 'i'), route.parameterTypes.chars)
        } finally {
            IfScriptTriggerRouter.unregister(route.crc)
        }
    }
}
