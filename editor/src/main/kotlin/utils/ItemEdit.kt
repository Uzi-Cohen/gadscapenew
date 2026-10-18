package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * A whole item definition (config archive 10), read and written exactly like the 211 client reads it, so items can be
 * made and changed in the editor. Writing puts the settings back in the game's own order; [decode] followed by
 * [encode] gives the same bytes for normal items (checked on the whole OSRS 211 cache).
 */
class ItemFull(var id: Int) {
    var inventoryModel = 0
    var name = "null"
    var examine3: String? = null     // opcode 3 (older caches / some custom caches)
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
    /** Right-click options on the ground (opcodes 30-34); null = the default. "Hidden" hides one. */
    val groundOptions = arrayOfNulls<String>(5)
    /** Inventory options (opcodes 35-39); null = none. */
    val inventoryOptions = arrayOfNulls<String>(5)
    var recolorFrom = ShortArray(0); var recolorTo = ShortArray(0)
    var retextureFrom = ShortArray(0); var retextureTo = ShortArray(0)
    var shiftClickIndex: Int? = null
    /**
     * Opcode 43 (GadScape client): sub-options of an inventory option, kept byte for byte. Each entry is everything
     * after the opcode: option index, then (sub-option + 1, text) pairs, then 0.
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
    /** Params (opcode 249), in their order: key -> Int or String. Equipment bonuses and requirements live here. */
    val params = LinkedHashMap<Int, Any>()
    /** The game's own setting order, to write the item back the way it was. */
    private val order = ArrayList<Int>()
    var readOk = true
    var problem = ""

    val isNoted get() = noteTemplate != -1
    override fun toString() = "$id  $name" + if (isNoted) " (noted)" else ""

    /** Every model this item points at. */
    fun modelIds(): List<Int> = listOf(inventoryModel, maleModel0, maleModel1, maleModel2, femaleModel0, femaleModel1, femaleModel2,
        maleHead, maleHead2, femaleHead, femaleHead2).filter { it >= 0 }

    fun copy(newId: Int): ItemFull = decode(newId, encode())

    /** Points every model id in [moved] (old -> new) at its new id. */
    fun remapModels(moved: Map<Int, Int>) {
        fun m(v: Int) = moved[v] ?: v
        inventoryModel = m(inventoryModel)
        maleModel0 = m(maleModel0); maleModel1 = m(maleModel1); maleModel2 = m(maleModel2)
        femaleModel0 = m(femaleModel0); femaleModel1 = m(femaleModel1); femaleModel2 = m(femaleModel2)
        maleHead = m(maleHead); maleHead2 = m(maleHead2); femaleHead = m(femaleHead); femaleHead2 = m(femaleHead2)
    }

    fun encode(): ByteArray {
        require(readOk) { "item $id has settings this editor can't read, so it can't be rewritten" }
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun u24(v: Int) { u8(v shr 16); u16(v and 0xFFFF) }
        fun str(s: String) { out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
        val written = HashSet<Int>()
        fun had(o: Int) = o in order
        fun op(o: Int) {
            if (!written.add(o)) return
            when (o) {
                1 -> if (had(1) || inventoryModel != 0) { u8(1); u16(inventoryModel) }
                2 -> if (had(2) || name != "null") { u8(2); str(name) }
                3 -> examine3?.let { u8(3); str(it) }
                4 -> if (had(4) || zoom2d != 2000) { u8(4); u16(zoom2d) }
                5 -> if (had(5) || xan2d != 0) { u8(5); u16(xan2d) }
                6 -> if (had(6) || yan2d != 0) { u8(6); u16(yan2d) }
                7 -> if (had(7) || offsetX2d != 0) { u8(7); u16(offsetX2d and 0xFFFF) }
                8 -> if (had(8) || offsetY2d != 0) { u8(8); u16(offsetY2d and 0xFFFF) }
                9 -> op9?.let { u8(9); str(it) }
                11 -> if (stackable) u8(11)
                12 -> if (had(12) || cost != 1) { u8(12); i32(cost) }
                13 -> if (wearPos1 != -1) { u8(13); u8(wearPos1) }
                14 -> if (wearPos2 != -1) { u8(14); u8(wearPos2) }
                16 -> if (members) u8(16)
                23 -> if (maleModel0 != -1) { u8(23); u16(maleModel0); u8(maleOffset) }
                24 -> if (maleModel1 != -1) { u8(24); u16(maleModel1) }
                25 -> if (femaleModel0 != -1) { u8(25); u16(femaleModel0); u8(femaleOffset) }
                26 -> if (femaleModel1 != -1) { u8(26); u16(femaleModel1) }
                27 -> if (wearPos3 != -1) { u8(27); u8(wearPos3) }
                in 30..34 -> groundOptions[o - 30]?.let { u8(o); str(it) }
                in 35..39 -> inventoryOptions[o - 35]?.let { u8(o); str(it) }
                40 -> if (recolorFrom.isNotEmpty()) { u8(40); u8(recolorFrom.size); for (i in recolorFrom.indices) { u16(recolorFrom[i].toInt()); u16(recolorTo[i].toInt()) } }
                41 -> if (retextureFrom.isNotEmpty()) { u8(41); u8(retextureFrom.size); for (i in retextureFrom.indices) { u16(retextureFrom[i].toInt()); u16(retextureTo[i].toInt()) } }
                42 -> shiftClickIndex?.let { u8(42); u8(it) }
                43 -> for (raw in subOptions43) { u8(43); out.write(raw) }
                65 -> if (tradeable) u8(65)
                75 -> weight?.let { u8(75); u16(it and 0xFFFF) }
                78 -> if (maleModel2 != -1) { u8(78); u16(maleModel2) }
                79 -> if (femaleModel2 != -1) { u8(79); u16(femaleModel2) }
                90 -> if (maleHead != -1) { u8(90); u16(maleHead) }
                91 -> if (femaleHead != -1) { u8(91); u16(femaleHead) }
                92 -> if (maleHead2 != -1) { u8(92); u16(maleHead2) }
                93 -> if (femaleHead2 != -1) { u8(93); u16(femaleHead2) }
                94 -> if (category != -1) { u8(94); u16(category) }
                95 -> if (had(95) || zan2d != 0) { u8(95); u16(zan2d) }
                97 -> if (noteLinked != -1) { u8(97); u16(noteLinked) }
                98 -> if (noteTemplate != -1) { u8(98); u16(noteTemplate) }
                in 100..109 -> { val co = countObj; val cc = countCo
                    if (co != null && cc != null && (had(o) || co[o - 100] != 0 || cc[o - 100] != 0)) { u8(o); u16(co[o - 100]); u16(cc[o - 100]) } }
                110 -> if (had(110) || resizeX != 128) { u8(110); u16(resizeX) }
                111 -> if (had(111) || resizeY != 128) { u8(111); u16(resizeY) }
                112 -> if (had(112) || resizeZ != 128) { u8(112); u16(resizeZ) }
                113 -> if (had(113) || ambient != 0) { u8(113); u8(ambient) }
                114 -> if (had(114) || contrast != 0) { u8(114); u8(contrast) }
                115 -> if (had(115) || team != 0) { u8(115); u8(team) }
                139 -> if (boughtLink != -1) { u8(139); u16(boughtLink) }
                140 -> if (boughtTemplate != -1) { u8(140); u16(boughtTemplate) }
                148 -> if (placeholderLink != -1) { u8(148); u16(placeholderLink) }
                149 -> if (placeholderTemplate != -1) { u8(149); u16(placeholderTemplate) }
                249 -> if (params.isNotEmpty()) {
                    require(params.size <= 255) { "an item can have at most 255 params" }
                    u8(249); u8(params.size)
                    for ((k, v) in params) { if (v is String) { u8(1); u24(k); str(v) } else { u8(0); u24(k); i32(v as Int) } }
                }
            }
        }
        // the order the item had, then anything new in the game's usual order
        for (o in order) op(o)
        for (o in ALL_OPS) op(o)
        out.write(0)
        return out.toByteArray()
    }

    companion object {
        val ALL_OPS = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 16, 23, 24, 25, 26, 27) + (30..39) +
                listOf(40, 41, 42, 65, 75, 78, 79, 90, 91, 92, 93, 94, 95, 97, 98) + (100..109) +
                listOf(110, 111, 112, 113, 114, 115, 139, 140, 148, 149, 249)

        fun decode(id: Int, b: ByteArray): ItemFull {
            val d = ItemFull(id)
            var p = 0
            fun u8() = b[p++].toInt() and 0xFF
            fun s8() = b[p++].toInt()
            fun u16() = (u8() shl 8) or u8()
            fun s16() = u16().let { if (it > 32767) it - 65536 else it }
            fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
            fun u24() = (u8() shl 16) or (u8() shl 8) or u8()
            fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
            try {
                while (true) {
                    if (p >= b.size) { d.readOk = false; d.problem = "it has no end marker"; break }
                    val o = u8()
                    // zeros after the end marker are padding some server packers leave; the game ignores them
                    if (o == 0) { if (p != b.size && (p until b.size).any { b[it].toInt() != 0 }) { d.readOk = false; d.problem = "there's data after its end" }; break }
                    if (o !in 100..109 && o in d.order && o != 249) { /* repeated setting: keep the last, the game does too */ }
                    if (o !in d.order) d.order.add(o)
                    when (o) {
                        1 -> d.inventoryModel = u16()
                        2 -> d.name = str()
                        3 -> d.examine3 = str()
                        4 -> d.zoom2d = u16()
                        5 -> d.xan2d = u16()
                        6 -> d.yan2d = u16()
                        7 -> d.offsetX2d = s16()
                        8 -> d.offsetY2d = s16()
                        9 -> d.op9 = str()
                        11 -> d.stackable = true
                        12 -> d.cost = i32()
                        13 -> d.wearPos1 = u8()
                        14 -> d.wearPos2 = u8()
                        16 -> d.members = true
                        23 -> { d.maleModel0 = u16(); d.maleOffset = u8() }
                        24 -> d.maleModel1 = u16()
                        25 -> { d.femaleModel0 = u16(); d.femaleOffset = u8() }
                        26 -> d.femaleModel1 = u16()
                        27 -> d.wearPos3 = u8()
                        in 30..34 -> d.groundOptions[o - 30] = str()
                        in 35..39 -> d.inventoryOptions[o - 35] = str()
                        40 -> { val n = u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n)
                            for (i in 0 until n) { d.recolorFrom[i] = u16().toShort(); d.recolorTo[i] = u16().toShort() } }
                        41 -> { val n = u8(); d.retextureFrom = ShortArray(n); d.retextureTo = ShortArray(n)
                            for (i in 0 until n) { d.retextureFrom[i] = u16().toShort(); d.retextureTo[i] = u16().toShort() } }
                        42 -> d.shiftClickIndex = u8()
                        43 -> { val st = p; u8(); while (u8() != 0) str(); d.subOptions43.add(b.copyOfRange(st, p)) }
                        65 -> d.tradeable = true
                        75 -> d.weight = s16()
                        78 -> d.maleModel2 = u16()
                        79 -> d.femaleModel2 = u16()
                        90 -> d.maleHead = u16()
                        91 -> d.femaleHead = u16()
                        92 -> d.maleHead2 = u16()
                        93 -> d.femaleHead2 = u16()
                        94 -> d.category = u16()
                        95 -> d.zan2d = u16()
                        97 -> d.noteLinked = u16()
                        98 -> d.noteTemplate = u16()
                        in 100..109 -> {
                            if (d.countObj == null) { d.countObj = IntArray(10); d.countCo = IntArray(10) }
                            d.countObj!![o - 100] = u16(); d.countCo!![o - 100] = u16()
                        }
                        110 -> d.resizeX = u16()
                        111 -> d.resizeY = u16()
                        112 -> d.resizeZ = u16()
                        113 -> d.ambient = u8()
                        114 -> d.contrast = u8()
                        115 -> d.team = u8()
                        139 -> d.boughtLink = u16()
                        140 -> d.boughtTemplate = u16()
                        148 -> d.placeholderLink = u16()
                        149 -> d.placeholderTemplate = u16()
                        249 -> { val n = u8(); repeat(n) { val isStr = u8() == 1; val k = u24(); d.params[k] = if (isStr) str() else i32() } }
                        else -> { d.readOk = false; d.problem = "it uses setting $o, which the 211 game doesn't have"; return d }
                    }
                }
            } catch (e: Exception) { d.readOk = false; d.problem = "it ends in the middle of a setting" }
            return d
        }

        /** Equipment slots (wearPos1), as the game numbers them. */
        val SLOTS = linkedMapOf(-1 to "Not wearable", 0 to "Head", 1 to "Cape", 2 to "Neck", 3 to "Weapon", 4 to "Body", 5 to "Shield",
            7 to "Legs", 9 to "Hands", 10 to "Feet", 12 to "Ring", 13 to "Ammo")
        /** What wearing it hides (wearPos2 / wearPos3). */
        val HIDES = linkedMapOf(-1 to "Nothing", 0 to "Head slot", 3 to "Weapon slot", 5 to "Shield slot", 6 to "Arms", 7 to "Legs slot", 8 to "Hair", 9 to "Hands slot", 10 to "Feet slot", 11 to "Beard / jaw")
    }
}

/** Reading and writing items in a cache. */
object ItemStore {
    fun ids(lib: CacheLibrary): List<Int> = lib.index(2).archive(10)?.fileIds()?.sorted() ?: emptyList()
    fun read(lib: CacheLibrary, id: Int): ItemFull? = lib.data(2, 10, id)?.let { ItemFull.decode(id, it) }

    /**
     * Writes [items] into the exported cache (after a backup). Each is checked by reading it back first. Models they
     * use that were only imported to try on them ([PendingModels]) go in too, in the same undo step.
     */
    fun save(items: List<ItemFull>, what: String) {
        fun check(it: ItemFull): ByteArray {
            val big = it.modelIds().filter { m -> m > 65535 }
            require(big.isEmpty()) { "item ${it.id} uses model id ${big.first()}, but items can only use model ids up to 65535" }
            val b = it.encode()
            val back = ItemFull.decode(it.id, b)
            require(back.readOk && back.encode().contentEquals(b)) { "item ${it.id} didn't read back the same, nothing was written" }
            return b
        }
        items.forEach { check(it) }
        CacheEdits.backup(what)
        CacheEdits.withExportCache { lib ->
            val moved = PendingModels.writeInto(lib, items.flatMap { it.modelIds() })
            if (moved.isNotEmpty()) for (it in items) it.remapModels(moved)
            for (it in items) lib.put(2, 10, it.id, check(it))
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
            for (id in ids.sortedDescending()) {
                if (lib.data(2, 10, id) == null) continue
                if (id == nextFreeId(lib) - 1) lib.remove(2, 10, id)
                else lib.put(2, 10, id, ItemFull(id).apply { name = "null" }.encode())
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

/** Copying items, with their models, from another cache. */
object ItemCopy {
    class Result(val message: String, val newIds: List<Int>)

    /** Where copied items come from: another cache, or an item bundle zip. */
    interface Source {
        fun item(id: Int): ItemFull?; fun model(id: Int): ByteArray?
        /** What the item does in game (its server stats entry, JSON), if the source has it. */
        fun stats(id: Int): String? = null
    }

    class CacheSource(private val other: CacheLibrary) : Source {
        override fun item(id: Int) = ItemStore.read(other, id)
        override fun model(id: Int) = other.data(7, id, 0)
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
                val d = ItemFull.decode(id, raw)
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

    fun copy(other: Source, ids: List<Int>, keepIds: Boolean): Result {
        CacheEdits.backup("items")
        return CacheEdits.withExportCache { lib ->
            val notes = ArrayList<String>()
            val statsFor = LinkedHashMap<Int, String>()
            val modelIds = CacheEdits.ModelIds(lib)
            var nextItem = ItemStore.nextFreeId(lib)
            val template = ItemStore.noteTemplate(lib)
            val taken = HashSet<Int>()
            fun free(id: Int) = id !in taken && lib.data(2, 10, id) == null
            val modelMap = HashMap<Int, Int>()
            var newModels = 0
            fun model(m: Int): Int {
                if (m < 0) return m
                modelMap[m]?.let { return it }
                val data = other.model(m) ?: run { if (lib.data(7, m, 0) == null) notes.add("model $m is missing"); return m }
                val here = lib.data(7, m, 0)
                val to = when {
                    here != null && here.contentEquals(data) -> m
                    here == null && keepIds && m <= 65535 -> { lib.put(7, m, 0, data); newModels++; m }
                    else -> { val n = modelIds.next(); lib.put(7, n, 0, data); newModels++; n }
                }
                modelMap[m] = to
                return to
            }
            val added = ArrayList<Int>()
            for (id in ids.distinct()) {
                val src = other.item(id) ?: run { notes.add("item $id isn't in the other cache"); null } ?: continue
                if (!src.readOk) { notes.add("item $id can't be read (${src.problem})"); continue }
                if (src.isNoted) continue // the noted version is made with the real item
                val it = src.copy(if (keepIds && free(id)) id else { while (!free(nextItem)) nextItem++; nextItem++ })
                taken.add(it.id)
                // from src: an item from a newer cache can have model ids over 65535, which copy() (as 211) can't keep
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
                    lib.put(2, 10, nid, ItemStore.notedFor(it, nid, template).encode())
                }
                if (it.countObj != null) notes.add("${it.name} has stack pictures (other item ids) that weren't copied")
                lib.put(2, 10, it.id, it.encode())
                added.add(it.id)
                other.stats(id)?.let { json -> statsFor[it.id] = json }
            }
            lib.index(7).update(); lib.index(2).update()
            // the bundle's server stats go into the server folder's ItemDefinitions.json under the new ids
            if (statsFor.isNotEmpty()) {
                val srv = ServerSource.guess()
                if (srv == null || !ItemStats.file(srv).isFile) notes.add("the bundle has server stats, but the server folder wasn't found: pick it in the item's In game section and import again")
                else try {
                    for ((newId, json) in statsFor) ItemStats.put(srv, com.google.gson.JsonParser.parseString(json).asJsonObject.also { o -> o.addProperty("id", newId) })
                    notes.add("its server stats (bonuses, requirements) were saved too; send them to the server to put them live")
                } catch (e: Exception) { notes.add("couldn't save its server stats: ${e.message}") }
            }
            Result("Copied ${added.size} item(s) as id ${added.joinToString(", ")}, with $newModels new model(s)." +
                    (if (notes.isEmpty()) "" else " " + notes.distinct().take(6).joinToString("; ") + "."), added)
        }
    }
}
