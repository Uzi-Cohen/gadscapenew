package com.gadscape.game.migrations.rev241

import com.gadscape.game.rev241.Gad211
import com.gadscape.game.rev241.IdMapTable
import com.gadscape.game.rev241.IdMapTable.Kind
import com.zenyte.game.item.Item
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Rev241IdsTest {

    private val table = IdMapTable.of(
        mapOf(
            Kind.OBJ to mapOf(34721 to 60000, 34722 to 60001, 34723 to 60002, 34726 to 60003),
            Kind.VARP to mapOf(3811 to 9011, 4506 to 9014),
            Kind.SEQ to mapOf(15110 to 40319),
        ), "test"
    )

    /** Shapes like the save's: containers keyed by slot, lists, nested holders, item attributes, a back-reference. */
    class Container(val items: Int2ObjectLinkedOpenHashMap<Item> = Int2ObjectLinkedOpenHashMap())
    class Preset(val name: String, val items: List<Item>, val runes: Array<Item?>)
    class Account(
        val inventory: Container,
        val bank: Container,
        val presets: MutableList<Preset>,
        val untouched: Map<String, Any>,
        @Transient val cached: Item,
    ) {
        var self: Account? = null

        companion object {
            /** A static field of a class in a save: not part of the save. */
            @JvmField
            val SHARED = Item(34721)
        }
    }

    @Test
    fun `a fresh account gets every move of the kinds saves hold`() {
        val plan = Rev241Ids.plan(table, emptyMap())
        assertEquals(mapOf(34721 to 60000, 34722 to 60001, 34723 to 60002, 34726 to 60003), plan[Kind.OBJ]!!.toMap())
        assertEquals(mapOf(3811 to 9011, 4506 to 9014), plan[Kind.VARP]!!.toMap())
        assertNull(plan[Kind.SEQ], "seqs are not stored in saves")
    }

    @Test
    fun `an account that applied the table has nothing left to do`() {
        val record = Rev241Ids.formatRecord(Rev241Ids.recordOf(table))
        val plan = Rev241Ids.plan(table, Rev241Ids.parseRecord(record))
        assertTrue(plan.values.all { it.isEmpty() }, "plan after the record: $plan")
    }

    @Test
    fun `a newer table moves what it changed and adds what it added`() {
        val applied = mapOf(Kind.OBJ to mapOf(34721 to 60000, 34723 to 60002, 30000 to 61000), Kind.VARP to mapOf(3811 to 9011))
        val newer = IdMapTable.of(
            mapOf(
                Kind.OBJ to mapOf(34721 to 60005, 34723 to 60002, 34726 to 60003),
                Kind.VARP to mapOf(3811 to 9011, 3800 to 9000),
            ), "newer"
        )
        val plan = Rev241Ids.plan(newer, applied)
        // 34721 moved again (60000 -> 60005), 30000 is no longer moved (61000 -> 30000), 34726 is new
        assertEquals(mapOf(60000 to 60005, 61000 to 30000, 34726 to 60003), plan[Kind.OBJ]!!.toMap())
        assertEquals(mapOf(3800 to 9000), plan[Kind.VARP]!!.toMap())
    }

    @Test
    fun `record round trip`() {
        val record = Rev241Ids.formatRecord(Rev241Ids.recordOf(table))
        assertEquals("obj:34721=60000;obj:34722=60001;obj:34723=60002;obj:34726=60003;varp:3811=9011;varp:4506=9014", record)
        assertEquals(Rev241Ids.recordOf(table), Rev241Ids.parseRecord(record))
        assertTrue(Rev241Ids.parseRecord("junk;obj:x=1;nokind:1=2;").isEmpty())
    }

    @Test
    fun `items are found in containers, lists, arrays and attributes, not in transient fields`() {
        val inventory = Container().apply {
            items.put(0, Item(34723, 1))
            items.put(1, Item(995, 100))
            items.put(5, Item(34722, 3)) // the noted copy
        }
        val looted = Item(34726, 1)
        val withContents = Item(11941, 1, mutableMapOf<String, Any>("contents" to mutableListOf(looted)))
        val bank = Container().apply {
            items.put(0, withContents)
            items.put(1, Item(34721, 1))
        }
        val presetItem = Item(34721)
        val account = Account(
            inventory, bank,
            mutableListOf(Preset("pk", listOf(presetItem, Item(4151)), arrayOf(null, Item(34723)))),
            mapOf("note" to "34721", "count" to 34721),
            cached = Item(34721),
        )
        account.self = account

        val objs = Int2IntOpenHashMap(Rev241Ids.plan(table, emptyMap())[Kind.OBJ]!!)
        val changed = Rev241Ids.remapItems(account, objs)

        assertEquals(6, changed)
        assertEquals(listOf(60002, 995, 60001), inventory.items.values.map { it.id })
        assertEquals(listOf(11941, 60000), bank.items.values.map { it.id })
        assertEquals(60003, looted.id)
        assertEquals(60000, presetItem.id)
        assertEquals(60002, account.presets[0].runes[1]!!.id)
        assertEquals(34721, account.cached.id, "transient fields are not part of a save")
        assertEquals(34721, account.untouched["count"], "plain numbers are not items")
        assertEquals(34721, Account.SHARED.id, "static fields are not part of a save")

        // idempotent: the moved ids are not moved again
        assertEquals(0, Rev241Ids.remapItems(account, objs))
    }

    @Test
    fun `varps move and a value already written at the target wins`() {
        val vars = Int2IntOpenHashMap().apply {
            put(3811, 1)
            put(4506, 250)
            put(9014, 300) // written by the 241 server already
            put(1055, 256)
        }
        val moved = Rev241Ids.remapVarps(vars, Int2IntOpenHashMap(mapOf(3811 to 9011, 4506 to 9014)))
        assertEquals(2, moved)
        assertEquals(mapOf(9011 to 1, 9014 to 300, 1055 to 256), vars.toMap())
        assertEquals(0, Rev241Ids.remapVarps(vars, Int2IntOpenHashMap(mapOf(3811 to 9011, 4506 to 9014))))
    }

    @Test
    fun `the bundled cacheV id map moves GadScape's items and varps`() {
        val bundled = Gad211::class.java.getResourceAsStream(Gad211.RESOURCE)!!.reader().use { IdMapTable.parse(it, "bundled") }
        // gadconv's reserved obj range is 60000-64999; the exact ids follow the cache build (idmap.json)
        val moved = listOf(34721 to "GadScape's Ascension crossbows copy", 34723 to "GadScape's Obligator recolour",
            34726 to "Gad obligator").map { (id, what) ->
            val to = bundled.map(Kind.OBJ, id)
            assertTrue(to in 60000..64999, "$what $id -> $to")
            to
        }
        assertEquals(9011, bundled.map(Kind.VARP, 3811), "slayer statues")
        assertEquals(5200, bundled.map(Kind.INTERFACE, 866), "PvP world perks interface")
        assertEquals(34681, bundled.map(Kind.OBJ, 34681), "2746's own Ascension crossbows does not move")
        // the SKSP-15 kit pieces are new objs (_new.obj), never on an id a moved GadScape item took
        val kit = bundled.added(Kind.OBJ, "sksp-15:Pegasian masori mask (f)")
        assertTrue(kit in 60000..64999 && kit !in moved, "kit piece at $kit")
        assertEquals(-1, bundled.added(Kind.OBJ, "no such entry"))
        for (kind in Kind.values()) {
            val entries = bundled.entries(kind)
            for (e in entries.int2IntEntrySet()) {
                // a moved id is never an id the map moves: applying the map twice changes nothing
                assertTrue(!entries.containsKey(e.intValue) || kind == Kind.MODEL || kind == Kind.STRUCT,
                    "${kind.key} ${e.intKey} -> ${e.intValue} is itself moved")
            }
        }
    }

    @Test
    fun `an id map file parses, metadata is ignored`() {
        val t = IdMapTable.parse(StringReader("""{"obj": {"1": 2}, "frame": {"5": 6}, "_reasons": {"obj": {"1": "x"}}, "_format": 1}"""), "s")
        assertEquals(2, t.map(Kind.OBJ, 1))
        assertEquals(7, t.map(Kind.OBJ, 7))
        assertEquals(setOf(Kind.OBJ), t.kinds())
        assertSame(IdMapTable.IDENTITY.entries(Kind.OBJ), IdMapTable.IDENTITY.entries(Kind.SEQ))
    }
}
