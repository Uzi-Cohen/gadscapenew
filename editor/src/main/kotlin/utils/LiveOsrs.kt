package utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Items from the newest Old School cache, brought into this (211) cache: the item list, item data and models are
 * read from the OpenRS2 cache archive (archive.openrs2.org), one piece at a time, and kept in the user folder.
 * Newer items are written in a format the 211 game doesn't read (model ids as ints, new settings), so each one is
 * turned into a 211 item first ([convert]); its server stats come from the item's own params ([stats]).
 */
object LiveOsrs {
    private const val ARCHIVE = "https://archive.openrs2.org"
    private val dir get() = File(System.getProperty("user.home"), ".uzis-map-editor/live-osrs").apply { mkdirs() }

    class Entry(val id: Int, val name: String) { override fun toString() = "$name  ($id)" }

    /** The newest live Old School cache in the archive: (its archive id, game build). */
    fun latestCache(): Pair<Int, Int> {
        val all = JsonParser.parseString(text("$ARCHIVE/caches.json")).asJsonArray
        val best = all.map { it.asJsonObject }
            .filter { it["game"]?.asString == "oldschool" && it["environment"]?.asString == "live" && it["language"]?.asString == "en" }
            .maxByOrNull { c ->
                val build = c["builds"]?.takeIf { it.isJsonArray }?.asJsonArray?.lastOrNull()?.asJsonObject?.get("major")?.asInt ?: 0
                build * 10_000_000_000L + (c["id"]?.asLong ?: 0L)
            } ?: error("the cache archive has no Old School cache")
        val build = best["builds"]?.takeIf { it.isJsonArray }?.asJsonArray?.lastOrNull()?.asJsonObject?.get("major")?.asInt ?: 0
        return best["id"].asInt to build
    }

    /** All of a cache's items, by id (the raw newer-format data), with their names for searching. */
    class Catalog(val cacheId: Int, val build: Int, val raw: Map<Int, ByteArray>) {
        val entries: List<Entry> = raw.mapNotNull { (id, b) ->
            val p = peek(b)
            if (p.noted || p.name == null || p.name == "null") null else Entry(id, p.name)
        }.sortedBy { it.name.lowercase() }

        fun search(q: String): List<Entry> {
            val t = q.trim().lowercase()
            if (t.isEmpty()) return emptyList()
            t.toIntOrNull()?.let { id -> return entries.filter { it.id == id } }
            return entries.filter { it.name.lowercase().contains(t) }
                .sortedWith(compareBy({ !it.name.lowercase().startsWith(t) }, { it.name.length }))
        }

        fun model(id: Int): ByteArray? = try { group(cacheId, 7, id) } catch (e: Exception) { null }
    }

    @Volatile private var catalog: Catalog? = null

    /** The newest cache's items (downloaded once per cache build; later calls are instant). */
    fun catalog(progress: (String) -> Unit = {}): Catalog {
        catalog?.let { return it }
        progress("Finding the newest Old School cache...")
        val (id, build) = latestCache()
        progress("Getting the item list from the build $build cache...")
        val files = groupFiles(id, 2, 10)
        return Catalog(id, build, files).also { catalog = it }
    }

    // ---------------- reading the cache archive ----------------

    private fun text(url: String): String = URI(url).toURL().openStream().use { String(it.readBytes(), Charsets.UTF_8) }

    /** One group (raw container), from the user folder or downloaded. */
    private fun rawGroup(cacheId: Int, archive: Int, group: Int): ByteArray {
        val f = File(dir, "$cacheId/$archive-$group.dat")
        if (f.isFile && f.length() > 0) return f.readBytes()
        f.parentFile.mkdirs()
        val b = URI("$ARCHIVE/caches/runescape/$cacheId/archives/$archive/groups/$group.dat").toURL().openStream().use { it.readBytes() }
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeBytes(b); tmp.renameTo(f)
        return b
    }

    fun group(cacheId: Int, archive: Int, group: Int): ByteArray = unpack(rawGroup(cacheId, archive, group))

    /** A JS5 container: compression type, length, (uncompressed length), data. */
    private fun unpack(b: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(b)
        val type = bb.get().toInt() and 0xFF
        val len = bb.int
        if (type == 0) return b.copyOfRange(5, 5 + len)
        bb.int // uncompressed length
        val data = b.copyOfRange(9, 9 + len)
        return when (type) {
            1 -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                ByteArrayInputStream(byteArrayOf('B'.code.toByte(), 'Z'.code.toByte(), 'h'.code.toByte(), '1'.code.toByte()) + data)).use { it.readBytes() }
            2 -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            else -> error("compression $type isn't supported")
        }
    }

    /** The file ids in each group of an archive, from its reference table (any protocol up to 7). */
    private fun table(cacheId: Int, archive: Int): Map<Int, IntArray> {
        val b = ByteBuffer.wrap(group(cacheId, 255, archive))
        val protocol = b.get().toInt() and 0xFF
        if (protocol >= 6) b.int
        val flags = b.get().toInt() and 0xFF
        val big = protocol >= 7
        fun smart(): Int = if (!big) b.short.toInt() and 0xFFFF
            else if (b.get(b.position()).toInt() and 0x80 != 0) b.int and 0x7FFFFFFF else b.short.toInt() and 0xFFFF
        val n = smart()
        val ids = IntArray(n); var last = 0
        for (i in 0 until n) { last += smart(); ids[i] = last }
        if (flags and 1 != 0) repeat(n) { b.int }          // name hashes
        repeat(n) { b.int }                                 // checksums
        if (flags and 8 != 0) repeat(n) { b.int }           // uncompressed checksums
        if (flags and 2 != 0) b.position(b.position() + 64 * n) // digests
        if (flags and 4 != 0) repeat(n) { b.int; b.int }    // lengths
        repeat(n) { b.int }                                 // versions
        val counts = IntArray(n) { smart() }
        val out = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            var f = 0
            out[ids[i]] = IntArray(counts[i]) { f += smart(); f }
        }
        return out
    }

    /** The files of one group, split. */
    private fun groupFiles(cacheId: Int, archive: Int, group: Int): Map<Int, ByteArray> {
        val fileIds = table(cacheId, archive)[group] ?: error("group $group isn't in archive $archive")
        val d = group(cacheId, archive, group)
        if (fileIds.size == 1) return mapOf(fileIds[0] to d)
        val chunks = d[d.size - 1].toInt() and 0xFF
        val bb = ByteBuffer.wrap(d)
        bb.position(d.size - 1 - chunks * fileIds.size * 4)
        val sizes = Array(chunks) { IntArray(fileIds.size) }
        for (c in 0 until chunks) { var acc = 0; for (f in fileIds.indices) { acc += bb.int; sizes[c][f] = acc } }
        val parts = Array(fileIds.size) { java.io.ByteArrayOutputStream() }
        var off = 0
        for (c in 0 until chunks) for (f in fileIds.indices) { parts[f].write(d, off, sizes[c][f]); off += sizes[c][f] }
        return fileIds.indices.associate { fileIds[it] to parts[it].toByteArray() }
    }

    // ---------------- newer item format -> 211 ----------------

    private class Peek(val name: String?, val noted: Boolean)

    private fun peek(b: ByteArray): Peek = try {
        val it = convert(-1, b).item
        Peek(it.name, it.noteTemplate >= 0)
    } catch (e: Exception) { Peek(null, false) }

    class Converted(val item: ItemFull, val examine: String?)

    /**
     * A newer item as a 211 item. Model ids are kept as they are (ints: [ItemCopy] gives one over 65535 a free id),
     * settings the 211 game doesn't have are left out (examine goes to the server stats instead).
     */
    fun convert(id: Int, b: ByteArray): Converted {
        val d = ItemFull(id)
        var examine: String? = null
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun s8() = b[p++].toInt()
        fun u16() = (u8() shl 8) or u8()
        fun s16() = u16().let { if (it > 32767) it - 65536 else it }
        fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u24() = (u8() shl 16) or u16()
        fun str(): String { val s = p; while (b[p].toInt() != 0) p++; return String(b, s, p - s, Charsets.ISO_8859_1).also { p++ } }
        while (true) {
            when (val o = u8()) {
                0 -> break
                1 -> d.inventoryModel = u16()
                44 -> d.inventoryModel = i32()
                2 -> d.name = str()
                3 -> examine = str()
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
                15, 160, 251 -> {} // newer flags with no data
                16 -> d.members = true
                23 -> { d.maleModel0 = u16(); d.maleOffset = u8() }
                24 -> d.maleModel1 = u16()
                25 -> { d.femaleModel0 = u16(); d.femaleOffset = u8() }
                26 -> d.femaleModel1 = u16()
                27 -> d.wearPos3 = u8()
                45 -> { d.maleModel0 = i32(); d.maleOffset = u8() }
                46 -> d.maleModel1 = i32()
                47 -> d.maleModel2 = i32()
                48 -> { d.femaleModel0 = i32(); d.femaleOffset = u8() }
                49 -> d.femaleModel1 = i32()
                50 -> d.femaleModel2 = i32()
                51 -> d.maleHead = i32()
                52 -> d.maleHead2 = i32()
                53 -> d.femaleHead = i32()
                54 -> d.femaleHead2 = i32()
                in 30..34 -> d.groundOptions[o - 30] = str().takeUnless { it.equals("Hidden", true) }
                in 35..39 -> d.inventoryOptions[o - 35] = str()
                40 -> { val n = u8(); d.recolorFrom = ShortArray(n); d.recolorTo = ShortArray(n)
                    for (i in 0 until n) { d.recolorFrom[i] = u16().toShort(); d.recolorTo[i] = u16().toShort() } }
                41 -> { val n = u8(); d.retextureFrom = ShortArray(n); d.retextureTo = ShortArray(n)
                    for (i in 0 until n) { d.retextureFrom[i] = u16().toShort(); d.retextureTo[i] = u16().toShort() } }
                42 -> d.shiftClickIndex = s8().coerceAtLeast(0)
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
                113 -> d.ambient = s8() and 0xFF
                114 -> d.contrast = s8() and 0xFF
                115 -> d.team = u8()
                139, 140, 148, 149 -> u16() // bought / placeholder links: other items, not copied
                161 -> { val n = u16(); repeat(n) { u16() } } // a list of ids the newer game uses (build 241+); nothing the 211 game reads
                249 -> { val n = u8(); repeat(n) { val isStr = u8() == 1; val k = u24(); d.params[k] = if (isStr) str() else i32() } }
                else -> error("it uses a setting ($o) this editor can't convert yet")
            }
        }
        return Converted(d, examine)
    }

    // ---------------- server stats from the item's params ----------------

    private val BONUS_PARAMS = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 189, 299, 11)
    private val REQUIREMENT_PARAMS = listOf(434 to 436, 435 to 437, 191 to 613, 579 to 614)

    /** The weapon style (ServerItems.WEAPON_CATEGORIES index) a weapon most likely has, from its name. */
    fun guessCategory(name: String): Int {
        val n = name.lowercase()
        return when {
            "pickaxe" in n -> 11
            "crossbow" in n || "ballista" in n -> 5
            "chinchompa" in n -> 7
            "bulwark" in n -> 27
            "partisan" in n -> 29
            "scythe" in n -> 14
            "halberd" in n -> 12
            "godsword" in n || "2h" in n || "greatsword" in n -> 10
            "claw" in n -> 4
            "whip" in n || "tentacle" in n -> 20
            "wand" in n -> 18
            "blowpipe" in n || "dart" in n || "knife" in n || "javelin" in n || "thrownaxe" in n -> 19
            "bow" in n -> 3
            "staff" in n || "sceptre" in n || "trident" in n || "shadow" in n || "sang" in n -> 21
            "spear" in n || "hasta" in n -> 15
            "maul" in n || "hammer" in n || "warhammer" in n -> 2
            "mace" in n || "flail" in n || "chainmace" in n -> 16
            "dagger" in n || "rapier" in n || "fang" in n || "keris" in n -> 17
            "axe" in n -> 1
            else -> 9 // swords, scimitars, blades
        }
    }

    /**
     * The item's server stats entry (ItemDefinitions.json), from its params: bonuses, requirements, attack speed,
     * weight and slot. A weapon's animations are copied from a weapon of the same style on this server, if it has one.
     */
    fun stats(c: Converted, server: File?): JsonObject? {
        val it = c.item
        val p = it.params
        fun param(k: Int) = (p[k] as? Int) ?: 0
        val wearable = it.wearPos1 >= 0
        if (!wearable && c.examine == null) return null
        val o = JsonObject()
        o.addProperty("id", it.id)
        it.weight?.let { w -> ItemStats.setWeight(o, w / 1000.0) } ?: o.addProperty("weight", 0)
        o.addProperty("slot", it.wearPos1)
        // what the server hides when it's worn (hair, jaw, arms), from what the item hides in the cache
        o.addProperty("equipmentType", when {
            it.wearPos1 == 0 && it.wearPos2 == 8 && it.wearPos3 == 11 -> "FULL_MASK"
            it.wearPos1 == 0 && (it.wearPos2 == 8 || it.wearPos3 == 8) -> "FULL_HELM"
            it.wearPos1 == 4 && (it.wearPos2 == 6 || it.wearPos3 == 6) -> "FULL_BODY"
            else -> "DEFAULT"
        })
        c.examine?.let { o.addProperty("examine", it) }
        if (it.tradeable) o.addProperty("tradable", true)
        if (!wearable) return o
        val eq = JsonObject(); o.add("equipmentDefinition", eq)
        eq.addProperty("bonuses", BONUS_PARAMS.joinToString(", ") { k -> (if (k == 299) param(k) / 10 else param(k)).toString() })
        val req = JsonObject()
        for ((sk, lv) in REQUIREMENT_PARAMS) if (p.containsKey(sk) && p.containsKey(lv)) req.addProperty(param(sk).toString(), param(lv))
        if (req.size() > 0) eq.add("requirements", req)
        if (it.wearPos1 == 3) {
            val category = guessCategory(it.name)
            val w = animationsLike(server, category) ?: JsonObject().apply {
                val keys = mapOf("stand" to "standAnimation", "walk" to "walkAnimation", "run" to "runAnimation", "standTurn" to "standTurnAnimation",
                    "turn90" to "rotate90Animation", "turn180" to "rotate180Animation", "turn270" to "rotate270Animation", "block" to "blockAnimation",
                    "accurate" to "accurateAnimation", "aggressive" to "aggressiveAnimation", "controlled" to "controlledAnimation", "defensive" to "defensiveAnimation")
                for ((k, v) in ServerItemStats.Weapon().animations()) addProperty(keys[k] ?: continue, v)
            }
            w.addProperty("twoHanded", it.wearPos2 == 5)
            w.addProperty("attackSpeed", ItemStats.stored(param(14).takeIf { it > 0 } ?: 4))
            w.addProperty("interfaceVarbit", category)
            if (!w.has("normalAttackDistance")) { w.addProperty("normalAttackDistance", 0); w.addProperty("longAttackDistance", 0) }
            eq.add("weaponDefinition", w)
        }
        return o
    }

    /** The animations most weapons of [category] use on this server (a copy of one's weaponDefinition), or null. */
    private fun animationsLike(server: File?, category: Int): JsonObject? {
        server ?: return null
        val f = ItemStats.file(server)
        if (!f.isFile) return null
        val arr = try { f.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonArray } catch (e: Exception) { return null }
        val same = arr.mapNotNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.get("equipmentDefinition")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("weaponDefinition")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { w -> w["interfaceVarbit"]?.takeIf { it.isJsonPrimitive }?.asInt == category && (w["accurateAnimation"]?.asInt ?: 0) > 0 }
        }
        if (same.isEmpty()) return null
        // the most common set of animations among them
        return same.groupBy { w -> listOf("standAnimation", "walkAnimation", "accurateAnimation", "aggressiveAnimation").map { w[it]?.toString() } }
            .maxByOrNull { it.value.size }!!.value.first().deepCopy()
    }

    /** Everything [ItemCopy] needs to copy items from the newest cache. */
    class Source(private val cat: Catalog, private val server: File?) : ItemCopy.Source {
        private val converted = HashMap<Int, Converted?>()
        private fun conv(id: Int) = converted.getOrPut(id) { cat.raw[id]?.let { convert(id, it) } }
        override fun item(id: Int): ItemFull? = conv(id)?.item
        override fun model(id: Int): ByteArray? = cat.model(id)
        override fun stats(id: Int): String? = conv(id)?.let { stats(it, server)?.toString() }
    }
}
