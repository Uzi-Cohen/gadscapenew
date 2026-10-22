package utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * Items from the newest Old School cache of build 241 or older ([NEWEST_BUILD], the build GadScape's game runs; when
 * the live game moves past it, the newest 241 cache is used and the import says so), brought into this cache (build
 * 241 or rev 211): the item list, item data and models are read from the OpenRS2 cache archive (archive.openrs2.org),
 * one piece at a time, and kept in the user folder. Live items are read as they are, in the build 241 layout ([read]); [ItemCopy] writes each one in the format
 * of the cache it goes into: the live bytes unchanged in a build 241 cache, a 211 item (2-byte model ids, the newer
 * settings left out and reported) only in a rev 211 cache. Live content keeps its live ids where they are free (the
 * converter's id policy, see [ItemCopy.copyInto]). The item's server stats come from its own params ([stats]).
 */
object LiveOsrs {
    private const val ARCHIVE = "https://archive.openrs2.org"
    private val dir get() = File(System.getProperty("user.home"), ".uzis-map-editor/live-osrs").apply { mkdirs() }

    class Entry(val id: Int, val name: String) { override fun toString() = "$name  ($id)" }

    /**
     * The newest game build whose content this editor brings in: build 241, the build GadScape's game client runs.
     * Later builds can add item and animation settings, model and keyframe encodings the 241 client can't read, so
     * live content always comes from a cache of this build or older (see [latestCache]).
     */
    const val NEWEST_BUILD = ConfigRev.R241

    /** A live cache in the archive: its archive id and game build, and the newest live build the archive has. */
    class Latest(val cacheId: Int, val build: Int, val newestBuild: Int)

    /**
     * The newest live Old School cache in the archive of game build [maxBuild] or older, and the newest live build
     * there is (when the live game has moved past [maxBuild], the import says so). A cache the archive lists under
     * more than one build counts as the newest of them up to [maxBuild]; caches without a known build are skipped.
     */
    fun latestCache(maxBuild: Int = NEWEST_BUILD): Latest = pickLatest(text("$ARCHIVE/caches.json"), maxBuild)

    /** [latestCache] from the archive's cache list ([cachesJson], its caches.json). */
    fun pickLatest(cachesJson: String, maxBuild: Int = NEWEST_BUILD): Latest {
        val all = JsonParser.parseString(cachesJson).asJsonArray
        fun builds(c: JsonObject): List<Int> = c["builds"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { b ->
            b.takeIf { it.isJsonObject }?.asJsonObject?.get("major")?.takeIf { it.isJsonPrimitive }?.asInt?.takeIf { it > 0 }
        } ?: emptyList()
        val live = all.filter { it.isJsonObject }.map { it.asJsonObject }
            .filter { it["game"]?.asString == "oldschool" && it["environment"]?.asString == "live" && it["language"]?.asString == "en" }
        if (live.isEmpty()) error("the cache archive has no Old School cache")
        val newest = live.maxOf { c -> builds(c).maxOrNull() ?: 0 }
        val best = live.mapNotNull { c -> builds(c).filter { it <= maxBuild }.maxOrNull()?.let { b -> c to b } }
            .maxByOrNull { (c, b) -> b * 10_000_000_000L + (c["id"]?.asLong ?: 0L) }
            ?: error("the cache archive has no live Old School cache of build $maxBuild or older")
        return Latest(best.first["id"].asInt, best.second, newest)
    }

    /**
     * All of a cache's items, by id (the raw newer-format data), with their names for searching. [newestBuild]: the
     * newest live build there is (newer than [build] when the live game has moved past what this editor brings in).
     */
    class Catalog(val cacheId: Int, val build: Int, val raw: Map<Int, ByteArray>, val newestBuild: Int = build) {
        private val peeks = raw.mapValues { (_, b) -> peek(b) }
        val entries: List<Entry> = peeks.mapNotNull { (id, p) ->
            if (p.noted || p.name == null || p.name == "null") null else Entry(id, p.name)
        }.sortedBy { it.name.lowercase() }

        /** Items that can't be read as build [NEWEST_BUILD] items (so the game couldn't read them either): not listed. */
        val unreadable: Int = peeks.values.count { !it.ok }

        /** "Build 241: 17734 items.", with the items left out and whether the live game is on a newer build. */
        fun describe(): String = "Build $build: ${entries.size} items." +
                (if (unreadable > 0) " $unreadable item(s) can't be read as build $NEWEST_BUILD items, so they aren't listed." else "") +
                (if (newestBuild > build) " The live game is on build $newestBuild now; these are from the newest build $build cache " +
                        "(cache $cacheId), because GadScape's game runs build $NEWEST_BUILD and can't read newer content." else "")

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
        progress("Finding the newest Old School cache of build $NEWEST_BUILD or older...")
        val latest = latestCache()
        progress("Getting the item list from the build ${latest.build} cache...")
        val files = groupFiles(latest.cacheId, 2, 10)
        return Catalog(latest.cacheId, latest.build, files, latest.newestBuild).also { catalog = it }
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
    fun groupFiles(cacheId: Int, archive: Int, group: Int): Map<Int, ByteArray> {
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

    // ---------------- live items (build 241 layout) ----------------

    private class Peek(val name: String?, val noted: Boolean, val ok: Boolean = true)

    private fun peek(b: ByteArray): Peek = try {
        val it = read(-1, b).item
        Peek(it.name, it.noteTemplate >= 0)
    } catch (e: Exception) { Peek(null, false, ok = false) }

    /** [animations]: the item's own animation list (setting 161, build 241 on), when it has one. */
    class Converted(val item: ItemFull, val examine: String?, val animations: IntArray? = null)

    /**
     * A live item, read in the build 241 layout and kept that way: 4-byte model ids, and the newer settings (not
     * tradeable 15, 99, 160, its animation list 161, sub-options 200-202, 251, long params) byte for byte, so it goes
     * into a build 241 cache exactly as the game has it. Throws when it uses a setting newer than build 241, which the
     * game's 241 client doesn't read either.
     */
    fun read(id: Int, b: ByteArray): Converted {
        val d = ItemFull.decodeAs(id, b, ConfigRev.R241)
        require(d.readOk) { "it can't be read as a build 241 item (${d.problem}), so the game couldn't read it either" }
        val animations = d.newerSettings.firstOrNull { it.first == 161 }?.second?.let { s ->
            fun u16(p: Int) = ((s[p].toInt() and 0xFF) shl 8) or (s[p + 1].toInt() and 0xFF)
            val n = u16(0)
            if (s.size >= 2 + 2 * n) IntArray(n) { u16(2 + 2 * it) } else null
        }
        return Converted(d, d.examine3, animations)
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
    fun stats(c: Converted, server: File?, animations: Map<String, Int>? = null): JsonObject? {
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
            // the item's own animations, brought in with it (LiveAnims), over the guessed ones
            animations?.forEach { (k, v) -> w.addProperty(k, v) }
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

    /** Everything [ItemCopy] needs to copy items from the newest cache (live content: build 241 layout, live ids). */
    class Source(private val cat: Catalog, private val server: File?) : ItemCopy.Source {
        init {
            require(cat.build <= NEWEST_BUILD) {
                "the live cache is build ${cat.build}, newer than build $NEWEST_BUILD (GadScape's game), which can't read its content"
            }
        }
        private val converted = HashMap<Int, Converted?>()
        /** Live item id -> its own animations, already in this cache (LiveAnims.importFor). */
        val animations = HashMap<Int, Map<String, Int>>()
        fun conv(id: Int) = converted.getOrPut(id) { cat.raw[id]?.let { read(id, it) } }
        override fun format(): Int = ConfigRev.R241
        override fun item(id: Int): ItemFull? = conv(id)?.item
        override fun model(id: Int): ByteArray? = cat.model(id)
        override fun stats(id: Int): String? = conv(id)?.let { stats(it, server, animations[id])?.toString() }
    }
}
