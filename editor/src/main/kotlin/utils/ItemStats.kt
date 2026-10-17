package utils

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What an item does in game, which the server keeps outside the cache: data/items/ItemDefinitions.json in the
 * server folder (slot, weight, two-handed, the 14 bonuses, attack speed, weapon animations, requirements).
 * The server reads it once at startup. Entries are kept as JSON, so settings the editor doesn't show stay as they are.
 */
object ItemStats {
    val BONUS_NAMES = listOf("Stab", "Slash", "Crush", "Magic", "Ranged",
        "Stab def", "Slash def", "Crush def", "Magic def", "Ranged def",
        "Melee str", "Ranged str", "Magic dmg %", "Prayer")
    /** Weapon animations in the file, with the names shown for them. */
    val ANIMS = linkedMapOf("standAnimation" to "Stand", "walkAnimation" to "Walk", "runAnimation" to "Run",
        "standTurnAnimation" to "Turn", "rotate90Animation" to "Turn 90", "rotate180Animation" to "Turn 180", "rotate270Animation" to "Turn 270",
        "blockAnimation" to "Block", "accurateAnimation" to "Attack (accurate)", "aggressiveAnimation" to "Attack (aggressive)",
        "controlledAnimation" to "Attack (controlled)", "defensiveAnimation" to "Attack (defensive)")
    val SKILLS = listOf("Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
        "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer",
        "Farming", "Runecrafting", "Hunter", "Construction")

    private var loadedFrom: File? = null
    private var loadedAt = 0L
    /** The file's entries in their order (it has a few ids twice; the server uses the last one). */
    private var array = JsonArray()
    private var entries = LinkedHashMap<Int, JsonObject>()

    fun file(server: File) = File(server, "data/items/ItemDefinitions.json")

    /** [key] as an object, or null when it's missing or null (the OSRS data has "equipment": null for most items). */
    fun obj(o: JsonObject?, key: String): JsonObject? = o?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun ensure(server: File): LinkedHashMap<Int, JsonObject> {
        val f = file(server)
        require(f.isFile) { "${f.path} isn't there (pick the server folder)" }
        if (loadedFrom != f || loadedAt != f.lastModified()) {
            val arr = f.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonArray
            val m = LinkedHashMap<Int, JsonObject>()
            for (e in arr) if (e.isJsonObject && e.asJsonObject["id"]?.isJsonPrimitive == true) m[e.asJsonObject["id"].asInt] = e.asJsonObject
            array = arr; entries = m; loadedFrom = f; loadedAt = f.lastModified()
        }
        return entries
    }

    /** A copy of the entry for [id], or null if the server has none for it (then it can't be worn). */
    fun get(server: File, id: Int): JsonObject? = ensure(server)[id]?.deepCopy()

    /**
     * Puts [e] in for its id (replacing what was there), writes the file in the server's own layout (tabs), and
     * remembers the id so Deploy sends it. A copy of the old file is kept next to it the first time each day.
     */
    fun put(server: File, e: JsonObject) {
        val id = e["id"].asInt
        val m = ensure(server)
        val f = file(server)
        val bak = File(f.parentFile, "ItemDefinitions.json.before-editor-" + SimpleDateFormat("yyyyMMdd").format(Date()))
        if (!bak.isFile) f.copyTo(bak)
        // the entry is changed where it is (the last one with that id, which is the one the server uses);
        // a new id goes in before the first bigger id. Every other entry stays exactly as it was.
        val copy = e.deepCopy()
        val arr = array
        val at = (arr.size() - 1 downTo 0).firstOrNull { i -> arr[i].let { it.isJsonObject && it.asJsonObject["id"]?.asInt == id } }
        if (at != null) arr.set(at, copy)
        else {
            val before = (0 until arr.size()).firstOrNull { i -> arr[i].let { it.isJsonObject && (it.asJsonObject["id"]?.asInt ?: -1) > id } }
            if (before == null) arr.add(copy) else {
                val rebuilt = JsonArray()
                for (i in 0 until arr.size()) { if (i == before) rebuilt.add(copy); rebuilt.add(arr[i]) }
                array = rebuilt
            }
        }
        m[id] = copy
        val total = array.size()
        val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(array)
        // gson indents with 2 spaces; the server's file uses tabs
        val nl = if (f.readText(Charsets.UTF_8).let { it.indexOf("\r\n").let { i -> i in 0..4096 } }) "\r\n" else "\n"
        val text = json.lines().joinToString(nl) { l -> val n = l.length - l.trimStart(' ').length; "\t".repeat(n / 2) + l.substring(n) }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text + nl, Charsets.UTF_8)
        // check it reads back before replacing the real file
        tmp.reader(Charsets.UTF_8).use { JsonParser.parseReader(it) }.asJsonArray.let { require(it.size() == total) { "it didn't write right, nothing was changed" } }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        loadedAt = f.lastModified()
        markPending(id)
    }

    // ---------- the entry, field by field ----------

    fun equip(e: JsonObject): JsonObject = obj(e, "equipmentDefinition") ?: JsonObject().also { e.add("equipmentDefinition", it) }
    fun weapon(e: JsonObject): JsonObject = obj(equip(e), "weaponDefinition") ?: JsonObject().also { equip(e).add("weaponDefinition", it) }

    fun bonuses(e: JsonObject): IntArray {
        val s = obj(e, "equipmentDefinition")?.get("bonuses")?.takeIf { it.isJsonPrimitive }?.asString ?: return IntArray(14)
        val p = s.split(',').map { it.trim().toIntOrNull() ?: 0 }
        return IntArray(14) { p.getOrElse(it) { 0 } }
    }
    fun setBonuses(e: JsonObject, b: IntArray) { equip(e).addProperty("bonuses", b.joinToString(", ")) }

    fun requirements(e: JsonObject): Map<Int, Int> =
        obj(obj(e, "equipmentDefinition"), "requirements")?.entrySet()
            ?.mapNotNull { (k, v) -> if (!v.isJsonPrimitive) null else k.toIntOrNull()?.let { it to v.asInt } }?.toMap() ?: emptyMap()
    fun setRequirements(e: JsonObject, r: Map<Int, Int>) {
        if (r.isEmpty()) { obj(e, "equipmentDefinition")?.remove("requirements"); return }
        val o = JsonObject(); r.toSortedMap().forEach { (k, v) -> o.addProperty(k.toString(), v) }
        equip(e).add("requirements", o)
    }

    fun int(o: JsonObject?, key: String, def: Int = 0) = o?.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: def
    fun bool(o: JsonObject?, key: String) = o?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    fun weight(e: JsonObject) = e.get("weight")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
    /** Whole numbers stay whole in the file (0, not 0.0), like the server writes them. */
    fun setWeight(e: JsonObject, kg: Double) { e.add("weight", if (kg == Math.floor(kg)) JsonPrimitive(kg.toLong()) else JsonPrimitive(kg)) }

    /**
     * The OSRS wiki data the server also reads (data/osrsbox-db/items-complete.json) for one item, or null. For ids
     * in there the server takes the higher of each bonus and the OSRS attack speed, so the JSON alone isn't the
     * whole story for original items.
     */
    fun osrs(server: File, id: Int): JsonObject? {
        val f = File(server, "data/osrsbox-db/items-complete.json")
        if (!f.isFile) return null
        osrsCache[id]?.let { return it.orElse(null) }
        val key = id.toString()
        var found: JsonObject? = null
        com.google.gson.stream.JsonReader(f.reader(Charsets.UTF_8).buffered()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == key) { found = JsonParser.parseReader(r).asJsonObject; break } else r.skipValue()
            }
        }
        osrsCache[id] = java.util.Optional.ofNullable(found)
        return found
    }
    private val osrsCache = HashMap<Int, java.util.Optional<JsonObject>>()

    private val OSRS_BONUS = listOf("attack_stab", "attack_slash", "attack_crush", "attack_magic", "attack_ranged",
        "defence_stab", "defence_slash", "defence_crush", "defence_magic", "defence_ranged",
        "melee_strength", "ranged_strength", "magic_damage", "prayer")

    /**
     * The item's stats as the server ends up using them: the JSON entry with the OSRS data merged in the way the
     * server does it (higher bonus wins; attack speed and requirements from OSRS). Null if neither has the item.
     */
    fun effective(server: File, id: Int): JsonObject? {
        val e = get(server, id)
        val o = osrs(server, id) ?: return e
        val eq = obj(o, "equipment") ?: return e
        val out = e ?: JsonObject().apply { addProperty("id", id); addProperty("equipmentType", "DEFAULT"); addProperty("slot", 0) }
        val b = bonuses(out); val hadBonuses = obj(out, "equipmentDefinition")?.get("bonuses")?.isJsonPrimitive == true
        val ob = IntArray(14) { eq[OSRS_BONUS[it]]?.takeIf { v -> v.isJsonPrimitive }?.asInt ?: 0 }
        // the server compares magic attack but then takes crush (its own bug); copy what it really ends up with
        val merged = if (!hadBonuses) ob else IntArray(14) { i -> if (ob[i] > b[i]) (if (i == 3) ob[2] else ob[i]) else b[i] }
        setBonuses(out, merged)
        obj(o, "weapon")?.get("attack_speed")?.takeIf { it.isJsonPrimitive }?.asInt?.let { weapon(out).addProperty("attackSpeed", 10 - it) }
        if (eq["slot"]?.takeIf { it.isJsonPrimitive }?.asString == "2h") weapon(out).addProperty("twoHanded", true)
        obj(eq, "requirements")?.let { r ->
            val req = requirements(out).toMutableMap()
            for ((name, lvl) in r.entrySet()) { val k = SKILLS.indexOfFirst { it.equals(name, true) }; if (k >= 0 && k !in req && lvl.isJsonPrimitive) req[k] = lvl.asInt }
            setRequirements(out, req)
        }
        return out
    }

    /**
     * A new entry for [id] with what [from] really has in game (a whip, a scythe...). A new id has no OSRS data,
     * so the merged values are written into its own entry.
     */
    fun copyFrom(server: File, from: Int, id: Int): JsonObject? = effective(server, from)?.also { it.addProperty("id", id) }

    /** Attack speed in game ticks (the file stores 10 - ticks). */
    fun ticks(stored: Int) = 10 - stored
    fun stored(ticks: Int) = 10 - ticks

    // ---------- what Deploy still has to send ----------

    private fun pendingFile() = File(System.getProperty("user.home"), ".uzis-map-editor/itemstats-pending.txt")
    fun pending(): Set<Int> = pendingFile().takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
    private fun markPending(id: Int) { pendingFile().parentFile.mkdirs(); pendingFile().writeText((pending() + id).sorted().joinToString("\n")) }
    fun clearPending() { pendingFile().delete() }

    /** The pending entries as one JSON array (what Deploy merges into the live file). */
    fun pendingJson(server: File): String? {
        val ids = pending(); if (ids.isEmpty()) return null
        val m = ensure(server)
        val arr = JsonArray(); ids.sorted().mapNotNull { m[it] }.forEach { arr.add(it) }
        return GsonBuilder().disableHtmlEscaping().create().toJson(arr)
    }
}
