package com.gadscape.network.rsprot

import com.zenyte.game.world.entity.player.Player
import net.rsprot.protocol.game.incoming.buttons.IfScriptTrigger
import net.rsprot.protocol.game.incoming.buttons.IfScriptTrigger.ParameterTypes
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side handlers for IF_SCRIPT_TRIGGER (E1): the packet the 241 client sends when a clientscript runs
 * `if_runscript*(ifscript_<crc>, <component>, <sub>)(args...)`.
 *
 * The packet carries the ifscript's crc, the component, sub and obj, and the argument values; it does **not**
 * carry their types, so every crc is registered with the cs2 signature of its call site and the arguments are
 * decoded with rsprot's [IfScriptTrigger.decode] and [ParameterTypes]:
 * - `string` -> 's' (String);
 * - `stringarray` -> 'X' (Array<String>), any other `...array` -> 'W' (IntArray);
 * - every other cs2 type (`int`, `boolean`, `dbrow`, `obj`, `component`, `coord`, `enum`, ...) -> 'i' (Int).
 *   The client writes all of them as a var-int; `boolean` arrives as 0/1, a null of any type as -1.
 *
 * Known crcs (2746 client scripts):
 *
 * | crc (unsigned / signed Int)    | sent by                                      | signature            |
 * |--------------------------------|----------------------------------------------|----------------------|
 * | 2481737999 / -1813229297       | tfa_hud:content_names (974:5), script 10040  | int slot, int        |
 * | 1373305897                     | tfa_hud:trigger_generic (974:1), script 10035| int action           |
 * | 2359217665 / -1935749631       | tfa_stats:all_stats_tables (979:34), proc 10123 | dbrow category    |
 * | 3642382788 / -652584508        | tfa_stats (979:24), debug setter (rental worlds) | dbrow, dbrow, int |
 * | 407137374                      | omnishop_main:trigger_buy (819:4), proc omnishop_main_buy | dbrow shop, int stockIndex, int quantityKey |
 * | 3336794041 / -958173255        | omnishop_main:trigger_examine (819:3), proc omnishop_main_examine | obj |
 * | 1754529215                     | omnishop_main:trigger_request_info (819:5), proc omnishop_main_info_setup | dbrow shop, int stockIndex |
 *
 * Unknown crcs are logged at debug and dropped, as before this router existed. Handlers run on the world
 * thread, where rsprot hands the message over ([RsprotSession.process]), exactly like every other client event.
 *
 * Usage (Kotlin):
 * ```
 * IfScriptTriggerRouter.register(2481737999L, "int int", interfaceId = 974, componentId = 5) { player, trigger ->
 *     spectate(player, slot = trigger.int(0))
 * }
 * ```
 * Java: `IfScriptTriggerRouter.register(crc, "dbrow int int", 819, 4, true, (player, trigger) -> ...)`.
 */
object IfScriptTriggerRouter {

    private val log = LoggerFactory.getLogger(IfScriptTriggerRouter::class.java)

    /** A handler for one crc. */
    fun interface Handler {
        fun handle(player: Player, trigger: ScriptTrigger)
    }

    /**
     * One registered crc.
     * @property interfaceId the interface the trigger must come from, or -1 for any.
     * @property componentId the component the trigger must come from, or -1 for any.
     * @property requireOpen whether [interfaceId] must be open for the player (ignored when [interfaceId] is -1).
     */
    class Route internal constructor(
        val crc: Int,
        val signature: String,
        val parameterTypes: ParameterTypes,
        val interfaceId: Int,
        val componentId: Int,
        val requireOpen: Boolean,
        val handler: Handler,
    ) {
        override fun toString(): String =
            "Route(crc=${unsigned(crc)}, signature='$signature', component=$interfaceId:$componentId, requireOpen=$requireOpen)"
    }

    private val routes = ConcurrentHashMap<Int, Route>()

    /** Crcs whose decode failure was already reported (one WARN per crc per boot). */
    private val reported: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /**
     * Registers [handler] for [crc] (the signed 32-bit value the packet carries; see the [Long] overload for the
     * unsigned numbers in the tables). [signature] is the cs2 argument list of the call site, e.g. `"dbrow int int"`
     * (names separated by spaces or commas; the one-letter rsprot codes i/s/W/X work as well).
     * A second registration for the same crc replaces the first (logged).
     */
    @JvmStatic
    @JvmOverloads
    fun register(
        crc: Int,
        signature: String,
        interfaceId: Int = -1,
        componentId: Int = -1,
        requireOpen: Boolean = true,
        handler: Handler,
    ): Route {
        val route = Route(crc, signature, parameterTypes(signature), interfaceId, componentId, requireOpen, handler)
        val previous = routes.put(crc, route)
        if (previous != null && previous.handler !== handler) {
            log.warn("IF_SCRIPT_TRIGGER crc {} re-registered: {} replaces {}", unsigned(crc), route, previous)
        }
        return route
    }

    /** [register] with the crc written as the unsigned number the cache tools print (e.g. 2481737999). */
    @JvmStatic
    @JvmOverloads
    fun register(
        crc: Long,
        signature: String,
        interfaceId: Int = -1,
        componentId: Int = -1,
        requireOpen: Boolean = true,
        handler: Handler,
    ): Route {
        require(crc in Int.MIN_VALUE.toLong()..0xFFFF_FFFFL) { "crc $crc is not a 32-bit value" }
        return register(crc.toInt(), signature, interfaceId, componentId, requireOpen, handler)
    }

    /** Removes the handler of [crc]; returns it, or null. */
    @JvmStatic
    fun unregister(crc: Int): Route? = routes.remove(crc)

    @JvmStatic
    fun route(crc: Int): Route? = routes[crc]

    @JvmStatic
    fun isRegistered(crc: Int): Boolean = routes.containsKey(crc)

    /** One line per registered crc (debug dumps). */
    @JvmStatic
    fun describe(): List<String> = routes.values.sortedBy { unsigned(it.crc) }.map { it.toString() }

    /**
     * Handles one IF_SCRIPT_TRIGGER (called from [RsprotIncoming]). Always releases the message's buffer.
     * @return true if a handler ran.
     */
    @JvmStatic
    fun dispatch(player: Player, message: IfScriptTrigger): Boolean {
        val route = routes[message.crc]
        if (route == null) {
            try {
                log.debug(
                    "IF_SCRIPT_TRIGGER {}:{} sub {} obj {} crc {} ({} bytes): no handler, dropped",
                    message.interfaceId, message.componentId, message.sub, message.obj, unsigned(message.crc),
                    message.buffer.readableBytes(),
                )
            } finally {
                releaseQuietly(message)
            }
            return false
        }
        val trigger = try {
            decode(message, route)
        } catch (e: Exception) {
            if (reported.add(route.crc)) {
                log.warn("IF_SCRIPT_TRIGGER crc {} from {}: arguments do not match '{}' ({}); dropped (logged once per crc)",
                    unsigned(route.crc), player, route.signature, e.message)
            }
            return false
        } finally {
            releaseQuietly(message)
        }
        if (!accepts(player, route, trigger)) {
            log.debug("IF_SCRIPT_TRIGGER crc {} from {} refused: component {}:{} (route {})",
                unsigned(route.crc), player, trigger.interfaceId, trigger.componentId, route)
            return false
        }
        return try {
            route.handler.handle(player, trigger)
            true
        } catch (e: Throwable) {
            log.error("IF_SCRIPT_TRIGGER crc {} handler failed for {}: {}", unsigned(route.crc), player, trigger, e)
            false
        }
    }

    /** Decodes [message] with [route]'s types (the buffer is released by rsprot's decode). */
    @JvmStatic
    fun decode(message: IfScriptTrigger, route: Route): ScriptTrigger =
        decode(message, route.parameterTypes)

    /** Decodes [message] with [types]; throws if the bytes do not match. */
    @JvmStatic
    fun decode(message: IfScriptTrigger, types: ParameterTypes): ScriptTrigger {
        val interfaceId = message.interfaceId
        val componentId = message.componentId
        val sub = message.sub
        val obj = message.obj
        val crc = message.crc
        val args = message.decode(types)
        return ScriptTrigger(crc, interfaceId, componentId, sub, obj, ArrayList(args.args))
    }

    private fun accepts(player: Player, route: Route, trigger: ScriptTrigger): Boolean {
        if (route.interfaceId < 0) return true
        if (trigger.interfaceId != route.interfaceId) return false
        if (route.componentId >= 0 && trigger.componentId != route.componentId) return false
        if (!route.requireOpen) return true
        return try {
            player.interfaceHandler.isVisible(route.interfaceId)
        } catch (e: Exception) {
            false
        }
    }

    private fun releaseQuietly(message: IfScriptTrigger) {
        try {
            if (message.refCnt() > 0) message.release()
        } catch (_: Exception) {
        }
    }

    /**
     * rsprot [ParameterTypes] for a cs2 signature such as `"dbrow, int, int"` or `"int int"`; an empty string is
     * no arguments.
     */
    @JvmStatic
    fun parameterTypes(signature: String): ParameterTypes {
        val tokens = signature.split(',', ' ', '\t', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }
        val chars = CharArray(tokens.size) { index -> typeChar(tokens[index]) }
        return ParameterTypes(chars)
    }

    /** The rsprot type code of one cs2 type name (or code). */
    @JvmStatic
    fun typeChar(type: String): Char {
        val name = type.lowercase()
        return when {
            type == "i" -> ParameterTypes.INT
            type == "s" -> ParameterTypes.STRING
            type == "W" -> ParameterTypes.INT_ARRAY
            type == "X" -> ParameterTypes.STRING_ARRAY
            name == "string" -> ParameterTypes.STRING
            name == "stringarray" -> ParameterTypes.STRING_ARRAY
            name.endsWith("array") -> ParameterTypes.INT_ARRAY
            else -> ParameterTypes.INT
        }
    }

    /** [crc] as the unsigned number the cache tools print. */
    @JvmStatic
    fun unsigned(crc: Int): Long = crc.toLong() and 0xFFFF_FFFFL
}

/**
 * The decoded arguments of one IF_SCRIPT_TRIGGER, with the component that sent it.
 * Accessors throw [IllegalArgumentException] on a missing or mistyped argument (a handler bug or a forged packet;
 * the router logs it).
 */
class ScriptTrigger(
    val crc: Int,
    val interfaceId: Int,
    val componentId: Int,
    /** The sub-component (cc index), or -1. */
    val sub: Int,
    /** The obj in the sub-component, or -1. */
    val obj: Int,
    val args: List<Any>,
) {
    val size: Int get() = args.size

    fun int(index: Int): Int = args.getOrNull(index) as? Int
        ?: throw IllegalArgumentException("argument $index of crc ${IfScriptTriggerRouter.unsigned(crc)} is not an int: ${args.getOrNull(index)}")

    fun boolean(index: Int): Boolean = int(index) == 1

    fun string(index: Int): String = args.getOrNull(index) as? String
        ?: throw IllegalArgumentException("argument $index of crc ${IfScriptTriggerRouter.unsigned(crc)} is not a string: ${args.getOrNull(index)}")

    fun intArray(index: Int): IntArray = args.getOrNull(index) as? IntArray
        ?: throw IllegalArgumentException("argument $index of crc ${IfScriptTriggerRouter.unsigned(crc)} is not an int array")

    @Suppress("UNCHECKED_CAST")
    fun stringArray(index: Int): Array<String> = (args.getOrNull(index) as? Array<*>)?.takeIf { it.isArrayOf<String>() } as? Array<String>
        ?: throw IllegalArgumentException("argument $index of crc ${IfScriptTriggerRouter.unsigned(crc)} is not a string array")

    override fun toString(): String =
        "ScriptTrigger(crc=${IfScriptTriggerRouter.unsigned(crc)}, component=$interfaceId:$componentId, sub=$sub, obj=$obj, args=" +
            args.joinToString(prefix = "[", postfix = "]") {
                when (it) {
                    is IntArray -> it.contentToString()
                    is Array<*> -> it.contentToString()
                    else -> it.toString()
                }
            } + ")"
}
