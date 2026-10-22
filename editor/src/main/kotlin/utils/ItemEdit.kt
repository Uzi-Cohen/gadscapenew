package utils

import cache.CacheIO
import com.displee.cache.CacheLibrary

/**
 * A whole item definition (config archive 10), read and written exactly like the game reads it, so items can be made
 * and changed in the editor. Both cache formats are supported:
 *  - rev 211: model ids are 2 bytes (opcodes 1, 23-26, 78, 79, 90-93),
 *  - build 241: model ids are 4-byte ints (opcodes 44-54), plus the newer settings (15, 99, 160, 161, 200-202, 251,
 *    long params). The 211 model opcodes still exist in the 241 client but carry no data there, so a 211 item written
 *    into a 241 cache breaks: [encode] always writes the format of the cache the item goes into.
 * Writing puts the settings back in the item's own order; an item that wasn't changed is written back byte for byte
 * (checked on every item of the OSRS 211 cache, the stock 241 caches and GadScape's 241 cache).
 */
class ItemFull(var id: Int) {
    var inventoryModel = 0
    var name = "null"
    var examine3: String? = null     // opcode 3 (examine text)
    var zoom2d = 2000
    var xan2d = 0
    var yan2d = 0
    var zan2d = 0
    var offsetX2d = 0
    var offsetY2d = 0
    var op9: String? = null
    var stackable = false
    var cost = 1
    var wearPos1 = -1
    var wearPos2 = -1
    var wearPos3 = -1
    var members = false
    var maleModel0 = -1; var maleOffset = 0; var maleModel1 = -1; var maleModel2 = -1
    var femaleModel0 = -1; var femaleOffset = 0; var femaleModel1 = -1; var femaleModel2 = -1
    var maleHead = -1; var maleHead2 = -1; var femaleHead = -1; var femaleHead2 = -1
    /**
     * Right-click options on the ground (opcodes 30-34), as stored; null = the default (the 3rd is "Take"). In a rev
     * 211 cache "Hidden" removes one, the default Take too. The build 241 client skips a "Hidden" option instead (the
     * slot keeps its default), so a 241 item can't remove Take that way.
     */
    val groundOptions = arrayOfNulls<String>(5)
    /** Inventory options (opcodes 35-39); null = none. */
    val inventoryOptions = arrayOfNulls<String>(5)
    var recolorFrom = ShortArray(0); var recolorTo = ShortArray(0)
    var retextureFrom = ShortArray(0); var retextureTo = ShortArray(0)
    var shiftClickIndex: Int? = null
    /**
     * Opcode 43 (GadScape's 211 client and build 241): sub-options of an inventory option, kept byte for byte. Each
     * entry is everything after the opcode: option index, then (sub-option + 1, text) pairs, then 0.
     */
    val subOptions43 = ArrayList<ByteArray>()
    var tradeable = false
    var weight: Int? = null
    var category = -1
    var noteLinked = -1
    var noteTemplate = -1
    var countObj: IntArray? = null; var countCo: IntArray? = null
    var resizeX = 128; var resizeY = 128; var resizeZ = 128
    var ambient = 0; var contrast = 0
    var team = 0
    var boughtLink = -1; var boughtTemplate = -1
    var placeholderLink = -1; var placeholderTemplate = -1
    /** Params (opcode 249), in their order: key -> Int, String, or Long (build 241). Bonuses and requirements live here. */
    val params = LinkedHashMap<Int, Any>()
    /**
     * Build 241 settings the editor keeps byte for byte without showing them, in their order: (opcode, data after the
     * opcode). From the 241 client: 15 (no data: not tradeable, see [notTradeable]), 99 (u16), 160 (no data: stack
     * kind 2), 161 (u16 count + u16 list), 200 / 201 / 202 (sub-options and conditional options), 251 (no data, a
     * flag). A rev 211 cache can't hold them. (65, "tradeable" in 211, is the Grand Exchange flag in 241.)
     */
    val newerSettings = ArrayList<Pair<Int, ByteArray>>()

    /** Build 241 opcode 15: the item can't be traded (the client's isTradeable() is false). Not in rev 211 caches. */
    var notTradeable: Boolean
        get() = newerSettings.any { it.first == 15 }
        set(v) {
            if (v == notTradeable) return
            if (v) newerSettings.add(15 to ByteArray(0))
            else {
                // keep the other entries' order keys pointing at the right entries
                val keep = newerSettings.withIndex().filter { it.value.first != 15 }
                val keyOf = keep.withIndex().associate { (newIdx, old) -> NEWER_KEYS.first + old.index to NEWER_KEYS.first + newIdx }
                newerSettings.clear(); newerSettings.addAll(keep.map { it.value })
                val o = order.mapNotNull { k -> if (k in NEWER_KEYS) keyOf[k] else k }
                order.clear(); order.addAll(o)
            }
        }
    /** The game's own setting order (model settings under their 241 numbers 44-54), to write the item back the way it was. */
    private val order = ArrayList<Int>()
    /** The format this item was read in; 0 for an item made in the editor. */
    var rev = 0
        private set
    /** The bytes it was read from, and what [encodeFields] gave for it right then: unchanged items are written back as read. */
    private var source: ByteArray? = null
    private var sourceFields: ByteArray? = null
    var readOk = true
    var problem = ""

    val isNoted get() = noteTemplate != -1
    override fun toString() = "$id  $name" + if (isNoted) " (noted)" else ""

    /** Every model this item points at. */
    fun modelIds(): List<Int> = listOf(inventoryModel, maleModel0, maleModel1, maleModel2, femaleModel0, femaleModel1, femaleModel2,
        maleHead, maleHead2, femaleHead, femaleHead2).filter { it >= 0 }

    /** The same item under [newId] (in the format it was read in). */
    fun copy(newId: Int): ItemFull { val r = if (rev == 0) ConfigRev.current() else rev; return decode(newId, encode(r), r) }

    /** Points every model id in [moved] (old -> new) at its new id. */
    fun remapModels(moved: Map<Int, Int>) {
        fun m(v: Int) = moved[v] ?: v
        inventoryModel = m(inventoryModel)
        maleModel0 = m(maleModel0); maleModel1 = m(maleModel1); maleModel2 = m(maleModel2)
        femaleModel0 = m(femaleModel0); femaleModel1 = m(femaleModel1); femaleModel2 = m(femaleModel2)
        maleHead = m(maleHead); maleHead2 = m(maleHead2); femaleHead = m(femaleHead); femaleHead2 = m(femaleHead2)
    }

    /** What a cache of format [target] can't hold of this item (it is left out when written there). */
    fun lostIn(target: Int): List<String> {
        if (ConfigRev.is241(target)) return emptyList()
        val out = ArrayList<String>()
        if (newerSettings.isNotEmpty()) out.add("newer settings ${newerSettings.map { it.first }.distinct().joinToString(", ")}")
        val longs = params.filter { it.value is Long && (it.value as Long) !in Int.MIN_VALUE..Int.MAX_VALUE }
        if (longs.isNotEmpty()) out.add("long params ${longs.keys.joinToString(", ")}")
        return out
    }

    /**
     * The item in the format of a cache of revision [target] (by default the cache the editor has open; pass the
     * revision of the cache it is written into). A rev 211 item can only use model ids up to 65535.
     */
    fun encode(target: Int = ConfigRev.current()): ByteArray {
        require(readOk) { "item $id has settings this editor can't read, so it can't be rewritten" }
        val out = encodeFields(target)
        val src = source
        if (src != null && target == rev && out.contentEquals(sourceFields)) return src.copyOf()
        return out
    }

    private fun encodeFields(target: Int): ByteArray {
        val r241 = ConfigRev.is241(target)
        val o = CfgOut()
        val written = HashSet<Int>()
        fun had(k: Int) = k in order
        /** A model setting, [k] = its 241 number. */
        fun model(k: Int, v: Int, offset: Int? = null) {
            if (r241) { o.u8(k); o.i32(v) }
            else {
                require(v in 0..65535) { "item $id uses model id $v, but rev 211 items can only use model ids up to 65535" }
                o.u8(OP_211[k]!!); o.u16(v)
            }
            if (offset != null) o.u8(offset)
        }
        fun op(k: Int) {
            if (!written.add(k)) return
            when (k) {
                44 -> if (had(44) || inventoryModel != 0) model(44, inventoryModel)
                2 -> if (had(2) || name != "null") { o.u8(2); o.str(name) }
                3 -> examine3?.let { o.u8(3); o.str(it) }
                4 -> if (had(4) || zoom2d != 2000) { o.u8(4); o.u16(zoom2d) }
                5 -> if (had(5) || xan2d != 0) { o.u8(5); o.u16(xan2d) }
                6 -> if (had(6) || yan2d != 0) { o.u8(6); o.u16(yan2d) }
                7 -> if (had(7) || offsetX2d != 0) { o.u8(7); o.u16(offsetX2d and 0xFFFF) }
                8 -> if (had(8) || offsetY2d != 0) { o.u8(8); o.u16(offsetY2d and 0xFFFF) }
                9 -> op9?.let { o.u8(9); o.str(it) }
                11 -> if (stackable) o.u8(11)
                12 -> if (had(12) || cost != 1) { o.u8(12); o.i32(cost) }
                13 -> if (wearPos1 != -1) { o.u8(13); o.u8(wearPos1) }
                14 -> if (wearPos2 != -1) { o.u8(14); o.u8(wearPos2) }
                16 -> if (members) o.u8(16)
                45 -> if (maleModel0 != -1) model(45, maleModel0, maleOffset)
                46 -> if (maleModel1 != -1) model(46, maleModel1)
                47 -> if (maleModel2 != -1) model(47, maleModel2)
                48 -> if (femaleModel0 != -1) model(48, femaleModel0, femaleOffset)
                49 -> if (femaleModel1 != -1) model(49, femaleModel1)
                50 -> if (femaleModel2 != -1) model(50, femaleModel2)
                51 -> if (maleHead != -1) model(51, maleHead)
                52 -> if (maleHead2 != -1) model(52, maleHead2)
                53 -> if (femaleHead != -1) model(53, femaleHead)
                54 -> if (femaleHead2 != -1) model(54, femaleHead2)
                27 -> if (wearPos3 != -1) { o.u8(27); o.u8(wearPos3) }
                in 30..34 -> groundOptions[k - 30]?.let { o.u8(k); o.str(it) }
                in 35..39 -> inventoryOptions[k - 35]?.let { o.u8(k); o.str(it) }
                40 -> if (recolorFrom.isNotEmpty()) { o.u8(40); o.u8(recolorFrom.size); for (i in recolorFrom.indices) { o.u16(recolorFrom[i].toInt()); o.u16(recolorTo[i].toInt()) } }
                41 -> if (retextureFrom.isNotEmpty()) { o.u8(41); o.u8(retextureFrom.size); for (i in retextureFrom.indices) { o.u16(retextureFrom[i].toInt()); o.u16(retextureTo[i].toInt()) } }
                42 -> shiftClickIndex?.let { o.u8(42); o.u8(it) }
                43 -> for (raw in subOptions43) { o.u8(43); o.raw(raw) }
                65 -> if (tradeable) o.u8(65)
                75 -> weight?.let { o.u8(75); o.u16(it and 0xFFFF) }
                94 -> if (category != -1) { o.u8(94); o.u16(category) }
                95 -> if (had(95) || zan2d != 0) { o.u8(95); o.u16(zan2d) }
                97 -> if (noteLinked != -1) { o.u8(97); o.u16(noteLinked) }
                98 -> if (noteTemplate != -1) { o.u8(98); o.u16(noteTemplate) }
                in 100..109 -> { val co = countObj; val cc = countCo
                    if (co != null && cc != null && (had(k) || co[k - 100] != 0 || cc[k - 100] != 0)) { o.u8(k); o.u16(co[k - 100]); o.u16(cc[k - 100]) } }
                110 -> if (had(110) || resizeX != 128) { o.u8(110); o.u16(resizeX) }
                111 -> if (had(111) || resizeY != 128) { o.u8(111); o.u16(resizeY) }
                112 -> if (had(112) || resizeZ != 128) { o.u8(112); o.u16(resizeZ) }
                113 -> if (had(113) || ambient != 0) { o.u8(113); o.u8(ambient) }
                114 -> if (had(114) || contrast != 0) { o.u8(114); o.u8(contrast) }
                115 -> if (had(115) || team != 0) { o.u8(115); o.u8(team) }
                139 -> if (boughtLink != -1) { o.u8(139); o.u16(boughtLink) }
                140 -> if (boughtTemplate != -1) { o.u8(140); o.u16(boughtTemplate) }
                148 -> if (placeholderLink != -1) { o.u8(148); o.u16(placeholderLink) }
                149 -> if (placeholderTemplate != -1) { o.u8(149); o.u16(placeholderTemplate) }
                249 -> if (params.isNotEmpty()) { o.u8(249); CfgParams.write(o, params, target) }
                in NEWER_KEYS -> if (r241) newerSettings.getOrNull(k - NEWER_KEYS.first)?.let { (op, data) -> o.u8(op); o.raw(data) }
            }
        }
        // the order the item had, then anything new in the game's usual order
        for (k in order) op(k)
        for (k in ALL_OPS) op(k)
        if (r241) for (n in newerSettings.indices) op(NEWER_KEYS.first + n)
        o.u8(0)
        return o.done()
    }

    companion object {
        /** The 211 opcode of each model setting (under its 241 number). */
        val OP_211 = mapOf(44 to 1, 45 to 23, 46 to 24, 47 to 78, 48 to 25, 49 to 26, 50 to 79, 51 to 90, 52 to 92, 53 to 91, 54 to 93)
        private val FROM_211 = OP_211.entries.associate { (k, v) -> v to k }
        /** Order keys for [newerSettings] entries (one key per entry, so repeated ones keep their places). */
        private val NEWER_KEYS = 1000..1999

        /** Settings in the game's usual order (model settings under their 241 numbers), for ones an item didn't have. */
        val ALL_OPS = listOf(44, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 16, 45, 46, 48, 49, 27) + (30..39) +
                listOf(40, 41, 42, 43, 65, 75, 47, 50, 51, 53, 52, 54, 94, 95, 97, 98) + (100..109) +
                listOf(110, 111, 112, 113, 114, 115, 139, 140, 148, 149, 249)

        /**
         * Reads an item. [rev] is the format it should be in (the cache it came from); when it doesn't read that way
         * but does in the other format (an item a 211 tool wrote into a 241 cache, or one from a bundle), that wins.
         */
        fun decode(id: Int, b: ByteArray, rev: Int = ConfigRev.current()): ItemFull {
            val first = decodeAs(id, b, rev)
            if (first.readOk) return first
            val second = decodeAs(id, b, ConfigRev.other(rev))
            return if (second.readOk) second else first
        }

        /** Reads an item in exactly the format [rev]. */
        fun decodeAs(id: Int, b: ByteArray, rev: Int): ItemFull {
            val r241 = ConfigRev.is241(rev)
            val d = ItemFull(id)
            d.rev = if (r241) ConfigRev.R241 else ConfigRev.R211
            val i = CfgIn(b)
            try {
                while (true) {
                    if (i.p >= b.size) { d.readOk = false; d.problem = "it has no end marker"; break }
                    val o = i.u8()
                    // zeros after the end marker are padding some server packers leave; the game ignores them
                    if (o == 0) { if (i.p != b.size && (i.p until b.size).any { b[it].toInt() != 0 }) { d.readOk = false; d.problem = "there's data after its end" }; break }
                    // repeated settings: the last one wins, the game does the same
                    val key = if (!r241 && o in FROM_211) FROM_211[o]!! else o
                    if (key !in d.order && key !in 200..202 && key !in NEWER_SINGLE) d.order.add(key)
                    when {
                        o == 2 -> d.name = i.str()
                        o == 3 -> d.examine3 = i.str()
                        o == 4 -> d.zoom2d = i.u16()
                        o == 5 -> d.xan2d = i.u16()
                        o == 6 -> d.yan2d = i.u16()
                        o == 7 -> d.offsetX2d = i.s16()
                        o == 8 -> d.offsetY2d = i.s16()
                        o == 9 -> d.op9 = i.str()
                        o == 11 -> d.stackable = true
                        o == 12 -> d.cost = i.i32()
                        o == 13 -> d.wearPos1 = i.u8()
                        o == 14 -> d.wearPos2 = i.u8()
                        o == 16 -> d.members = true
                        o == 27 -> d.wearPos3 = i.u8()
                        o in 30..34 -> d.groundOptions[o - 30] = i.str()
                        o in 35..39 -> d.inventoryOptions[o - 35] = i.str()
                        o == 40 -> { val n = i.u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n)
                            for (k in 0 until n) { d.recolorFrom[k] = i.u16().toShort(); d.recolorTo[k] = i.u16().toShort() } }
                        o == 41 -> { val n = i.u8(); d.retextureFrom = ShortArray(n); d.retextureTo = ShortArray(n)
                            for (k in 0 until n) { d.retextureFrom[k] = i.u16().toShort(); d.retextureTo[k] = i.u16().toShort() } }
                        o == 42 -> d.shiftClickIndex = i.u8()
                        o == 43 -> { val st = i.p; i.u8(); while (i.u8() != 0) i.skipStr(); d.subOptions43.add(i.bytes(st)) }
                        o == 65 -> d.tradeable = true
                        o == 75 -> d.weight = i.s16()
                        o == 94 -> d.category = i.u16()
                        o == 95 -> d.zan2d = i.u16()
                        o == 97 -> d.noteLinked = i.u16()
                        o == 98 -> d.noteTemplate = i.u16()
                        o in 100..109 -> {
                            if (d.countObj == null) { d.countObj = IntArray(10); d.countCo = IntArray(10) }
                            d.countObj!![o - 100] = i.u16(); d.countCo!![o - 100] = i.u16()
                        }
                        o == 110 -> d.resizeX = i.u16()
                        o == 111 -> d.resizeY = i.u16()
                        o == 112 -> d.resizeZ = i.u16()
                        o == 113 -> d.ambient = i.u8()
                        o == 114 -> d.contrast = i.u8()
                        o == 115 -> d.team = i.u8()
                        o == 139 -> d.boughtLink = i.u16()
                        o == 140 -> d.boughtTemplate = i.u16()
                        o == 148 -> d.placeholderLink = i.u16()
                        o == 149 -> d.placeholderTemplate = i.u16()
                        o == 249 -> CfgParams.read(i, rev, d.params)
                        // model ids: 2 bytes in 211, 4 in 241
                        !r241 && o in FROM_211 -> d.setModel(key, i.u16(), i)
                        r241 && o in 44..54 -> d.setModel(key, i.i32(), i)
                        // build 241 settings kept as bytes
                        r241 && (o in NEWER_SINGLE || o in 200..202) -> {
                            val st = i.p
                            when (o) {
                                15, 160, 251 -> {}
                                99 -> i.skip(2)
                                161 -> i.skip(2 * i.u16())
                                200 -> { i.skip(2); i.skipStr() }
                                201 -> { i.skip(13); i.skipStr() }
                                202 -> { i.skip(15); i.skipStr() }
                            }
                            d.order.add(NEWER_KEYS.first + d.newerSettings.size)
                            d.newerSettings.add(o to i.bytes(st))
                        }
                        else -> { d.readOk = false; d.problem = "it uses setting $o, which ${ConfigRev.name(rev)} items don't have"; return d }
                    }
                }
            } catch (e: Exception) { d.readOk = false; d.problem = "it ends in the middle of a setting" }
            if (d.readOk) try { d.sourceFields = d.encodeFields(d.rev); d.source = b.copyOf() } catch (e: Exception) { d.sourceFields = null }
            return d
        }

        /** Build 241 settings without a field of their own (see [newerSettings]); 200-202 too. */
        private val NEWER_SINGLE = setOf(15, 99, 160, 161, 251)

        /** Equipment slots (wearPos1), as the game numbers them. */
        val SLOTS = linkedMapOf(-1 to "Not wearable", 0 to "Head", 1 to "Cape", 2 to "Neck", 3 to "Weapon", 4 to "Body", 5 to "Shield",
            7 to "Legs", 9 to "Hands", 10 to "Feet", 12 to "Ring", 13 to "Ammo")
        /** What wearing it hides (wearPos2 / wearPos3). */
        val HIDES = linkedMapOf(-1 to "Nothing", 0 to "Head slot", 3 to "Weapon slot", 5 to "Shield slot", 6 to "Arms", 7 to "Legs slot", 8 to "Hair", 9 to "Hands slot", 10 to "Feet slot", 11 to "Beard / jaw")
    }

    /** Sets the model setting [k] (its 241 number) to [v]; the worn ones carry an offset byte after the id. */
    private fun setModel(k: Int, v: Int, i: CfgIn) {
        when (k) {
            44 -> inventoryModel = v
            45 -> { maleModel0 = v; maleOffset = i.u8() }
            46 -> maleModel1 = v
            47 -> maleModel2 = v
            48 -> { femaleModel0 = v; femaleOffset = i.u8() }
            49 -> femaleModel1 = v
            50 -> femaleModel2 = v
            51 -> maleHead = v
            52 -> maleHead2 = v
            53 -> femaleHead = v
            54 -> femaleHead2 = v
        }
    }
}

/** Reading and writing items in a cache. */
object ItemStore {
    fun ids(lib: CacheLibrary): List<Int> = lib.index(2).archive(10)?.fileIds()?.sorted() ?: emptyList()
    /** Item [id] of [lib], read in the cache's own format. */
    fun read(lib: CacheLibrary, id: Int): ItemFull? = lib.data(2, 10, id)?.let { ItemFull.decode(id, it, ConfigRev.of(lib)) }

    /**
     * The item as it will be written into a cache of format [target], checked by reading it back. Rev 211 items can
     * only use model ids up to 65535; build 241 ones any.
     */
    fun checkedBytes(it: ItemFull, target: Int): ByteArray {
        val b = it.encode(target)
        val back = ItemFull.decodeAs(it.id, b, target)
        require(back.readOk && back.encode(target).contentEquals(b)) { "item ${it.id} didn't read back the same, nothing was written" }
        return b
    }

    /**
     * Writes [items] into the exported cache (after a backup), in that cache's format. Each is checked by reading it
     * back first. Models they use that were only imported to try on them ([PendingModels]) go in too, in the same
     * undo step.
     */
    fun save(items: List<ItemFull>, what: String) {
        val target = ConfigRev.ofDir(GadTools.ensureExportCache())
        items.forEach { checkedBytes(it, target) }
        CacheEdits.backup(what)
        CacheEdits.withExportCache { lib ->
            val rev = ConfigRev.of(lib)
            val moved = PendingModels.writeInto(lib, items.flatMap { it.modelIds() })
            if (moved.isNotEmpty()) for (it in items) it.remapModels(moved)
            for (it in items) lib.put(2, 10, it.id, checkedBytes(it, rev))
            lib.index(2).update()
        }
    }

    /**
     * Removes items from the exported cache (after a backup). Items at the end of the list are deleted; any other
     * one is turned into an empty "null" item instead, because item ids after it must keep their numbers.
     */
    fun remove(ids: List<Int>, what: String) {
        CacheEdits.backup(what)
        CacheEdits.withExportCache { lib ->
            val rev = ConfigRev.of(lib)
            for (id in ids.sortedDescending()) {
                if (lib.data(2, 10, id) == null) continue
                if (id == nextFreeId(lib) - 1) lib.remove(2, 10, id)
                else lib.put(2, 10, id, ItemFull(id).apply { name = "null" }.encode(rev))
            }
            lib.index(2).update()
        }
    }

    fun nextFreeId(lib: CacheLibrary): Int = (lib.index(2).archive(10)?.fileIds()?.maxOrNull() ?: -1) + 1

    /** The noted version's template (the "paper" item, 799 in OSRS): the one most noted items use. */
    fun noteTemplate(lib: CacheLibrary): Int {
        val counts = HashMap<Int, Int>()
        for (id in ids(lib)) { val d = read(lib, id) ?: continue; if (d.noteTemplate != -1) counts[d.noteTemplate] = (counts[d.noteTemplate] ?: 0) + 1 }
        return counts.maxByOrNull { it.value }?.key ?: 799
    }

    /** A noted item for [real], as the game makes them: only the links, the rest comes from the real item and the template. */
    fun notedFor(real: ItemFull, notedId: Int, template: Int): ItemFull = ItemFull(notedId).apply {
        noteLinked = real.id; noteTemplate = template
    }
}

/**
 * Copying items, with their models, from another cache, an item bundle or the live game (see [copyInto] for the id
 * policy and the formats).
 */
object ItemCopy {
    /**
     * [newIds]: items written (in the order asked for). [alreadyHere]: items that were already in the cache at their
     * own id (same item), so nothing was copied for them.
     */
    class Result(val message: String, val newIds: List<Int>, val alreadyHere: List<Int> = emptyList())

    /** Where copied items come from: another cache, or an item bundle zip. */
    interface Source {
        fun item(id: Int): ItemFull?; fun model(id: Int): ByteArray?
        /** What the item does in game (its server stats entry, JSON), if the source has it. */
        fun stats(id: Int): String? = null
        /** The format (211 / 241) its items are in, when it is one format; null = mixed or unknown. */
        fun format(): Int? = null
    }

    /** Another cache, of either format (open it read-only: [cache.CacheIO.openReadOnly]). */
    class CacheSource(private val other: CacheLibrary) : Source {
        private val rev = ConfigRev.of(other)
        override fun item(id: Int) = ItemStore.read(other, id)
        override fun model(id: Int) = other.data(7, id, 0)
        override fun format(): Int = rev
    }

    /** An item bundle: items/<id>.dat, models/<id>.dat and stats/<id>.json (server stats) in a zip, made by [exportBundle]. */
    class BundleSource(zip: java.io.File) : Source {
        val items = java.util.TreeMap<Int, ByteArray>(); private val models = HashMap<Int, ByteArray>(); private val statsJson = HashMap<Int, String>()
        init {
            java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val b = z.readBytes()
                    val parts = e.name.trim('/').split('/')
                    if (parts.size != 2) continue
                    val id = parts[1].substringBefore('.').toIntOrNull() ?: continue
                    when (parts[0]) { "items" -> items[id] = b; "models" -> models[id] = b; "stats" -> statsJson[id] = String(b, Charsets.UTF_8) }
                }
            }
            require(items.isNotEmpty()) { "That zip has no items in it (it should have an items folder)." }
        }
        // a bundle's items are in the format of the cache they were saved from: decode() works out which
        override fun item(id: Int) = items[id]?.let { ItemFull.decode(id, it) }
        override fun model(id: Int) = models[id]
        override fun stats(id: Int) = statsJson[id]
        /** The real items (not the noted ones), which are what gets imported. */
        fun mainIds() = items.keys.filter { item(it)?.isNoted == false }
    }

    /** Saves [ids] (and their noted versions) with every model they use, to move them to another cache. */
    fun exportBundle(lib: CacheLibrary, ids: List<Int>, out: java.io.File): String {
        val done = HashSet<Int>(); val modelIds = HashSet<Int>()
        java.util.zip.ZipOutputStream(out.outputStream()).use { z ->
            fun put(name: String, b: ByteArray) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(b); z.closeEntry() }
            for (id in ids.distinct()) {
                val raw = lib.data(2, 10, id) ?: continue
                val d = ItemFull.decode(id, raw, ConfigRev.of(lib))
                put("items/$id.dat", raw); done.add(id)
                if (d.noteLinked >= 0 && !d.isNoted) lib.data(2, 10, d.noteLinked)?.let { put("items/${d.noteLinked}.dat", it) }
                modelIds.addAll(listOf(d.inventoryModel, d.maleModel0, d.maleModel1, d.maleModel2, d.femaleModel0, d.femaleModel1, d.femaleModel2,
                    d.maleHead, d.maleHead2, d.femaleHead, d.femaleHead2).filter { it >= 0 })
            }
            for (m in modelIds) lib.data(7, m, 0)?.let { put("models/$m.dat", it) }
            // what each item does in game, from the server folder (bonuses, speed, animations, requirements)
            ServerSource.guess()?.let { srv -> for (id in done) try { ItemStats.effective(srv, id)?.let { put("stats/$id.json", it.toString().toByteArray()) } } catch (e: Exception) {} }
            put("bundle.txt", "# Uzi's Map Editor item bundle\n${done.joinToString("\n") { "item $it" }}\n".toByteArray())
        }
        return "Saved ${done.size} item(s) with ${modelIds.size} model(s)."
    }

    fun fromOtherCache(other: CacheLibrary, ids: List<Int>, keepIds: Boolean): Result = copy(CacheSource(other), ids, keepIds)

    fun importBundle(zip: java.io.File, keepIds: Boolean): Result { val b = BundleSource(zip); return copy(b, b.mainIds(), keepIds) }

    /** [copyInto] the exported cache, after a backup. */
    fun copy(other: Source, ids: List<Int>, keepIds: Boolean): Result {
        CacheEdits.backup("items")
        return CacheEdits.withExportCache { lib -> copyInto(lib, other, ids, keepIds, ServerSource.guess()) }
    }

    /** True if [here] (the item at that id in this cache) is the same item as [incoming]: same name, or same bytes. */
    private fun sameItem(here: ItemFull, incoming: ItemFull, rev: Int): Boolean {
        if (!here.readOk) return false
        val name = incoming.name.trim()
        if (name.isNotEmpty() && name != "null" && name.equals(here.name.trim(), ignoreCase = true)) return true
        return try { incoming.encode(rev).contentEquals(here.encode(rev)) } catch (e: Exception) { false }
    }

    /**
     * Copies items [ids] of [other] (with their noted versions and models) into [lib]. Nothing that is in [lib] is
     * ever written over.
     *
     * Ids follow the converter's policy (plan/gadconv-spec.md):
     *  - [keepIds] (content from the live game or a stock OSRS cache, which shares the game's ids): an item keeps its
     *    id where that id is free here. An item that is already here at its own id (same name, or the same bytes) is
     *    not copied again ([Result.alreadyHere]); a different item at that id (GadScape's own) keeps it, and the
     *    copied one gets the next free id. Models keep their ids where those are free or hold the same model.
     *  - not [keepIds] (another server's own content): items go after the last item, and models that aren't here
     *    byte for byte get new ids.
     *  New model ids come from [CacheEdits.ModelIds]: the highest free ids up to 65534 in a build 241 cache. A model
     *  kept at its own id is marked as taken there, so a later model of the same copy never gets that id.
     *  "The same model": the same bytes, or, in a rev 211 cache, the same but for the face-depth ending build 241
     *  models have (the 211 cache's own copy of a live model, [ModelTail]); that copy is used as it is.
     *
     * Formats: items are written in [lib]'s format whatever format they come in. Into a build 241 cache: live and
     * 241 items keep their own bytes (4-byte model ids, newer settings); rev 211 items are converted (model settings
     * to 44-54, the same as the converter's Conv walker). Into a rev 211 cache: settings it can't hold are left out
     * and reported, and model ids over 65535 get free ids. Models going into a build 241 cache get the face-depth
     * tail 241 reads ([ModelTail]); models are otherwise copied as they are. [server]: the server folder, for the
     * items' server stats (bundles and the live game have them).
     */
    fun copyInto(lib: CacheLibrary, other: Source, ids: List<Int>, keepIds: Boolean, server: java.io.File?): Result {
        // items are written in this cache's format, whatever format they come in
        val rev = ConfigRev.of(lib)
        val maxModel = CacheIO.maxModelId(lib)
        val notes = ArrayList<String>()
        val statsFor = LinkedHashMap<Int, String>()
        val modelIds = CacheEdits.ModelIds(lib)
        var nextItem = ItemStore.nextFreeId(lib)
        val template = ItemStore.noteTemplate(lib)
        val taken = HashSet<Int>()
        fun free(id: Int) = id !in taken && lib.data(2, 10, id) == null
        val modelMap = HashMap<Int, Int>()
        var newModels = 0; var tailed = 0
        // a rev 211 cache's own copy of a live (build 241) model differs only by the face-depth ending 241 models
        // have: it's the same model, and the one this cache's format reads (most of GadScape's 211 models are the
        // live ones without that ending)
        fun sameButTheEnding(here: ByteArray, raw: ByteArray): Boolean = !ConfigRev.is241(rev) &&
                try { ModelTail.forBuild241(here).contentEquals(ModelTail.forBuild241(raw)) } catch (e: Exception) { false }
        fun model(m: Int): Int {
            if (m < 0) return m
            modelMap[m]?.let { return it }
            val raw = other.model(m) ?: run { if (lib.data(7, m, 0) == null) notes.add("model $m is missing"); return m }
            // 211-era models need the face-depth tail in a 241 cache (a no-op for 241 models)
            val data = ModelTail.forRevision(raw, rev)
            val here = lib.data(7, m, 0)
            val to = when {
                here != null && (here.contentEquals(data) || here.contentEquals(raw) || sameButTheEnding(here, raw)) -> m
                here == null && keepIds && m <= maxModel -> {
                    lib.put(7, m, 0, data)
                    // the allocator only knows the ids the cache had when it was made: a later model must not get this one
                    modelIds.take(m)
                    newModels++; if (data !== raw) tailed++; m
                }
                else -> {
                    // never an id this copy has written already (a second guard: kept ids are taken above)
                    var n = modelIds.next()
                    while (lib.index(7).contains(n)) n = modelIds.next()
                    lib.put(7, n, 0, data); newModels++; if (data !== raw) tailed++; n
                }
            }
            modelMap[m] = to
            return to
        }
        val added = ArrayList<Int>()
        val already = ArrayList<Int>()
        val from = other.format()
        for (id in ids.distinct()) {
            val src = other.item(id) ?: run { notes.add("item $id isn't in the other cache"); null } ?: continue
            if (!src.readOk) { notes.add("item $id can't be read (${src.problem})"); continue }
            if (src.isNoted) continue // the noted version is made with the real item
            // live / stock content: the same item already at its own id isn't copied again
            if (keepIds && !free(id)) {
                val here = ItemStore.read(lib, id)
                if (here != null && sameItem(here, src, rev)) {
                    already.add(id); taken.add(id)
                    // its server stats only when the server has none for it (never over what it has)
                    val hasStats = try { server != null && ItemStats.get(server, id) != null } catch (e: Exception) { false }
                    if (!hasStats) other.stats(id)?.let { json -> statsFor[id] = json }
                    continue
                }
                notes.add("${src.name}: id $id is taken here by ${here?.name ?: "another item"}, so it was added under a new id")
            }
            val it = src.copy(if (keepIds && free(id)) id else { while (!free(nextItem)) nextItem++; nextItem++ })
            taken.add(it.id)
            it.lostIn(rev).let { lost -> if (lost.isNotEmpty()) notes.add("${it.name}: this ${ConfigRev.name(rev)} cache can't hold its ${lost.joinToString(" and ")}, so they were left out") }
            // a model id this cache's format can't hold (over 65535 in a 211 cache) gets a free id
            it.inventoryModel = model(src.inventoryModel)
            it.maleModel0 = model(src.maleModel0); it.maleModel1 = model(src.maleModel1); it.maleModel2 = model(src.maleModel2)
            it.femaleModel0 = model(src.femaleModel0); it.femaleModel1 = model(src.femaleModel1); it.femaleModel2 = model(src.femaleModel2)
            it.maleHead = model(src.maleHead); it.maleHead2 = model(src.maleHead2); it.femaleHead = model(src.femaleHead); it.femaleHead2 = model(src.femaleHead2)
            it.placeholderLink = -1; it.placeholderTemplate = -1; it.boughtLink = -1; it.boughtTemplate = -1
            val hadNote = src.noteLinked
            it.noteLinked = -1
            if (hadNote >= 0) {
                val nid = if (keepIds && free(hadNote)) hadNote else { while (!free(nextItem)) nextItem++; nextItem++ }
                taken.add(nid)
                it.noteLinked = nid
                lib.put(2, 10, nid, ItemStore.notedFor(it, nid, template).encode(rev))
            }
            if (it.countObj != null) notes.add("${it.name} has stack pictures (other item ids) that weren't copied")
            lib.put(2, 10, it.id, ItemStore.checkedBytes(it, rev))
            added.add(it.id)
            other.stats(id)?.let { json -> statsFor[it.id] = json }
        }
        if (newModels > 0) lib.index(7).update()
        if (added.isNotEmpty()) lib.index(2).update()
        if (from != null && from != rev && added.isNotEmpty()) notes.add("converted from ${ConfigRev.name(from)} to this cache's ${ConfigRev.name(rev)} format")
        if (tailed > 0) notes.add("$tailed model(s) got the face-depth ending build 241 models need")
        if (already.isNotEmpty()) notes.add("already here: ${already.joinToString(", ")} (the same item at its own id), so not copied again; " +
                "untick keeping the ids to add a copy under a new id")
        // the bundle's server stats go into the server folder's ItemDefinitions.json under the new ids
        if (statsFor.isNotEmpty()) {
            if (server == null || !ItemStats.file(server).isFile) notes.add("the bundle has server stats, but the server folder wasn't found: pick it in the item's In game section and import again")
            else try {
                for ((newId, json) in statsFor) ItemStats.put(server, com.google.gson.JsonParser.parseString(json).asJsonObject.also { o -> o.addProperty("id", newId) })
                notes.add("its server stats (bonuses, requirements) were saved too; send them to the server to put them live")
            } catch (e: Exception) { notes.add("couldn't save its server stats: ${e.message}") }
        }
        val head = if (added.isEmpty() && already.isNotEmpty()) "Nothing new to copy." else
            "Copied ${added.size} item(s)" + (if (added.isEmpty()) "" else " as id ${added.joinToString(", ")}") + ", with $newModels new model(s)."
        return Result(head + (if (notes.isEmpty()) "" else " " + notes.distinct().take(8).joinToString("; ") + "."), added, already)
    }
}
