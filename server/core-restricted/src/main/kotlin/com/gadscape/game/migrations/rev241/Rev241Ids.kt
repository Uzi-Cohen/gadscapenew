package com.gadscape.game.migrations.rev241

import com.gadscape.game.rev241.Gad211
import com.gadscape.game.rev241.IdMapTable
import com.gadscape.game.rev241.IdMapTable.Kind
import com.zenyte.game.item.Item
import com.zenyte.game.item._Item
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.player.Player
import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Moves the GadScape 211 ids an account holds to the ids of the 241 cache (gadconv's idmap.json, see [Gad211]).
 *
 * A save made on the 211 server (or on the 241 test build before the switch to cacheV) holds GadScape's items and
 * varps at their 211 ids. Two kinds of id live in saves:
 *  - obj: every item the account holds (inventory, equipment, bank, looting bag, rune pouches, seed vault, sacks,
 *    collection log, POH storage, GE offers, presets, death storage, item attributes...). They are found by walking the
 *    fields a save holds (non-static, non-transient), so a container added later is covered too.
 *  - varp: the varps the account keeps (VarManager's serialized vars), e.g. 3811 slayer statues -> 9011. Varbits keep
 *    their bits in their varp, so a moved varbit needs nothing more than its varp's move.
 *
 * What was applied is recorded per account in the attribute [RECORD_ATTRIBUTE] ("obj:34721=60000;varp:3811=9011;...").
 * A later id map (the cache is rebuilt) applies only what changed: new moves, and ids it moved before to their new
 * target (or back to the 211 id when the new map no longer moves them). A moved id is never a 211 id, so applying a
 * plan twice changes nothing; the record still keeps it from re-reading 2746 content at a 211 id as GadScape's.
 */
object Rev241Ids {

    private val log = LoggerFactory.getLogger(Rev241Ids::class.java)

    /** Player attribute (saved with the account) holding the applied moves. */
    const val RECORD_ATTRIBUTE = "rev241 id map"

    /** The id kinds saves hold. */
    @JvmField
    val SAVE_KINDS: List<Kind> = listOf(Kind.OBJ, Kind.VARP)

    /** Upper bound on objects visited per account (a save has a few thousand). */
    private const val MAX_NODES = 2_000_000

    class Result(val items: Int, val varps: Int, val plan: Map<Kind, Int2IntMap>) {
        override fun toString() = "items $items, varps $varps, moves " +
                plan.entries.joinToString { "${it.key.key} ${it.value.size}" }
    }

    /** Migrates [player] with the active table; null when nothing was left to do. */
    @JvmStatic
    @JvmOverloads
    fun migrate(player: Player, table: IdMapTable = Gad211.table()): Result? {
        val attributes = player.attributes
        val applied = parseRecord(attributes[RECORD_ATTRIBUTE] as? String)
        val plan = plan(table, applied)
        val record = formatRecord(recordOf(table))
        if (plan.values.all { it.isEmpty() }) {
            if (attributes[RECORD_ATTRIBUTE] != record && record.isNotEmpty()) attributes[RECORD_ATTRIBUTE] = record
            return null
        }
        val items = remapItems(player, plan[Kind.OBJ] ?: EMPTY)
        val varps = remapVarps(player.varManager.serializedVars, plan[Kind.VARP] ?: EMPTY)
        attributes[RECORD_ATTRIBUTE] = record
        val result = Result(items, varps, plan)
        if (items > 0 || varps > 0) {
            log.info("Moved {}'s GadScape 211 ids to the 241 cache's: {}", player.username, result)
        }
        return result
    }

    private val EMPTY: Int2IntMap = Int2IntOpenHashMap()

    // ------------------------------------------------------------------------------------------- record and plan

    /** The moves [table] makes for the kinds saves hold (what the record stores once they are applied). */
    @JvmStatic
    fun recordOf(table: IdMapTable): Map<Kind, Map<Int, Int>> {
        val out = EnumMap<Kind, Map<Int, Int>>(Kind::class.java)
        for (kind in SAVE_KINDS) {
            val entries = table.entries(kind)
            if (entries.isEmpty()) continue
            out[kind] = entries.int2IntEntrySet().associate { it.intKey to it.intValue }.toSortedMap()
        }
        return out
    }

    /** "obj:34721=60000;obj:34723=60002;varp:3811=9011"; kinds this class does not keep are dropped. */
    @JvmStatic
    fun formatRecord(record: Map<Kind, Map<Int, Int>>): String =
        record.entries.flatMap { (kind, moves) -> moves.entries.map { "${kind.key}:${it.key}=${it.value}" } }
            .joinToString(";")

    @JvmStatic
    fun parseRecord(text: String?): Map<Kind, Map<Int, Int>> {
        val out = EnumMap<Kind, MutableMap<Int, Int>>(Kind::class.java)
        if (text.isNullOrBlank()) return out
        for (part in text.split(';')) {
            val colon = part.indexOf(':')
            val eq = part.indexOf('=')
            if (colon <= 0 || eq <= colon) continue
            val kind = Kind.byKey(part.substring(0, colon).trim()) ?: continue
            val old = part.substring(colon + 1, eq).trim().toIntOrNull() ?: continue
            val new = part.substring(eq + 1).trim().toIntOrNull() ?: continue
            out.getOrPut(kind) { mutableMapOf() }[old] = new
        }
        return out
    }

    /**
     * The moves still to make on an account whose record says [applied], per kind: id the account holds now -> id it
     * must hold. Fresh account (empty record): every move of [table].
     */
    @JvmStatic
    fun plan(table: IdMapTable, applied: Map<Kind, Map<Int, Int>>): Map<Kind, Int2IntMap> {
        val out = EnumMap<Kind, Int2IntMap>(Kind::class.java)
        for (kind in SAVE_KINDS) {
            val moves = Int2IntOpenHashMap()
            val before = applied[kind] ?: emptyMap()
            for ((old, previous) in before) {
                val target = table.map(kind, old)
                if (target != previous) moves.put(previous, target)
            }
            for (e in table.entries(kind).int2IntEntrySet()) {
                if (!before.containsKey(e.intKey)) moves.put(e.intKey, e.intValue)
            }
            out[kind] = moves
        }
        return out
    }

    // ------------------------------------------------------------------------------------------- varps

    /**
     * Moves the values of [vars] (varp id -> value) by [varps]. A value already stored at the target wins (it was
     * written by the 241 server); the 211 entry is dropped either way. Returns the number of entries moved.
     */
    @JvmStatic
    fun remapVarps(vars: Int2IntMap, varps: Int2IntMap): Int {
        if (varps.isEmpty()) return 0
        val moving = vars.keys.toIntArray().filter { varps.containsKey(it) }
        if (moving.isEmpty()) return 0
        val values = moving.associateWith { vars.get(it) }
        moving.forEach { vars.remove(it) }
        for ((from, value) in values) {
            val to = varps.get(from)
            if (!vars.containsKey(to)) vars.put(to, value)
        }
        return moving.size
    }

    // ------------------------------------------------------------------------------------------- items

    /**
     * Sets the id of every item reachable from [root] through the fields a save holds (non-static, non-transient,
     * in GadScape's own classes), collections, maps, arrays and item attributes, by [objs]. Other entities (NPCs,
     * other players) are never entered. Returns the number of items changed.
     */
    @JvmStatic
    fun remapItems(root: Any, objs: Int2IntMap): Int {
        if (objs.isEmpty()) return 0
        val seen: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        val stack = ArrayDeque<Any>()
        stack.add(root)
        var changed = 0
        var nodes = 0
        while (stack.isNotEmpty()) {
            val o = stack.removeLast()
            if (!seen.add(o)) continue
            if (++nodes > MAX_NODES) {
                log.warn("Stopped looking for items after {} objects under {}", MAX_NODES, root.javaClass.simpleName)
                break
            }
            if (o is _Item) {
                val id = o.id
                if (objs.containsKey(id)) {
                    setId(o, objs.get(id))
                    changed++
                }
                o.attributes?.let { push(stack, it, root) }
                continue
            }
            when (o) {
                is Map<*, *> -> for (v in o.values) push(stack, v, root)
                is Iterable<*> -> for (v in o) push(stack, v, root)
                is Array<*> -> for (v in o) push(stack, v, root)
                is Optional<*> -> push(stack, o.orElse(null), root)
                else -> {
                    if (!walkable(o.javaClass)) continue
                    for (f in fieldsOf(o.javaClass)) {
                        val v = try {
                            f.get(o)
                        } catch (e: Exception) {
                            null
                        }
                        push(stack, v, root)
                    }
                }
            }
        }
        return changed
    }

    private fun push(stack: ArrayDeque<Any>, v: Any?, root: Any) {
        if (v == null || v === root) return
        if (v is Number || v is CharSequence || v is Boolean || v is Char || v is Enum<*> || v is Class<*>) return
        if (v is Entity) return
        val c = v.javaClass
        if (c.isArray && c.componentType.isPrimitive) return
        stack.add(v)
    }

    private fun walkable(c: Class<*>): Boolean {
        val n = c.name
        return (n.startsWith("com.zenyte.") || n.startsWith("com.gadscape.")) && !c.isEnum && !c.isSynthetic
    }

    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun fieldsOf(c: Class<*>): List<Field> = fieldCache.computeIfAbsent(c) {
        val out = ArrayList<Field>()
        var k: Class<*>? = c
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                val m = f.modifiers
                if (Modifier.isStatic(m) || Modifier.isTransient(m) || f.isSynthetic || f.type.isPrimitive) continue
                try {
                    f.isAccessible = true
                    out.add(f)
                } catch (e: RuntimeException) {
                    // a class of another module: not part of a save
                }
            }
            k = k.superclass
        }
        out
    }

    private val itemIdField: Field by lazy {
        _Item::class.java.getDeclaredField("id").apply { isAccessible = true }
    }

    private fun setId(item: _Item, id: Int) {
        if (item is Item) item.setId(id) else itemIdField.setInt(item, id)
    }
}
