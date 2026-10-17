package utils

import com.displee.cache.CacheLibrary
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What the server knows about an item that the cache doesn't: equipment bonuses, attack speed, the weapon's stand /
 * walk / attack animations, skill requirements and its special attack. Kept in the server's
 * data/items/ItemDefinitions.json (bonuses, weapon) and data/items/special-attacks.json (special), plus cache enum 906
 * (special energy, which the client also reads to draw the special bar).
 */
class ServerItemStats(val id: Int) {
    var weight = 0.0
    var slot = -1
    var equipmentType = "DEFAULT"
    /** stab, slash, crush, magic, ranged attack; stab, slash, crush, magic, ranged defence; strength, ranged strength, magic damage, prayer. */
    var bonuses = IntArray(14)
    /** skill index (0 Attack, 1 Defence, 2 Strength, 3 Hitpoints, 4 Ranged, 5 Prayer, 6 Magic...) -> level */
    val requirements = java.util.TreeMap<Int, Int>()
    var weapon: Weapon? = null
    /** Name of the special attack (see [ServerItems.specialNames]), or null. */
    var special: String? = null
    /** Special energy in percent (cache enum 906), or null for none. */
    var specialEnergy: Int? = null

    class Weapon {
        var twoHanded = false
        /** In game ticks (0.6 s): 4 = whip, 5 = scythe, 6 = slow. The file stores 10 - ticks. */
        var attackSpeed = 4
        /** Combat options shown (varbit 357): see [ServerItems.WEAPON_CATEGORIES]. */
        var category = 0
        var stand = 808; var walk = 819; var run = 824; var standTurn = 823
        var turn90 = 821; var turn180 = 820; var turn270 = 822
        var block = 424
        var accurate = 422; var aggressive = 423; var controlled = 422; var defensive = 422
        var normalDistance = 0; var longDistance = 0

        fun animations(): LinkedHashMap<String, Int> = linkedMapOf("stand" to stand, "walk" to walk, "run" to run, "standTurn" to standTurn,
            "turn90" to turn90, "turn180" to turn180, "turn270" to turn270, "block" to block,
            "accurate" to accurate, "aggressive" to aggressive, "controlled" to controlled, "defensive" to defensive)
    }

    fun copy(): ServerItemStats = ServerItemStats(id).also { c ->
        c.weight = weight; c.slot = slot; c.equipmentType = equipmentType; c.bonuses = bonuses.clone()
        c.requirements.putAll(requirements); c.special = special; c.specialEnergy = specialEnergy
        weapon?.let { w -> c.weapon = Weapon().also { n ->
            n.twoHanded = w.twoHanded; n.attackSpeed = w.attackSpeed; n.category = w.category
            n.stand = w.stand; n.walk = w.walk; n.run = w.run; n.standTurn = w.standTurn; n.turn90 = w.turn90; n.turn180 = w.turn180; n.turn270 = w.turn270
            n.block = w.block; n.accurate = w.accurate; n.aggressive = w.aggressive; n.controlled = w.controlled; n.defensive = w.defensive
            n.normalDistance = w.normalDistance; n.longDistance = w.longDistance } }
    }
}

object ServerItems {
    val BONUS_NAMES = listOf("Stab att", "Slash att", "Crush att", "Magic att", "Ranged att",
        "Stab def", "Slash def", "Crush def", "Magic def", "Ranged def", "Strength", "Ranged str", "Magic dmg %", "Prayer")
    val SKILLS = listOf("Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing",
        "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction")
    val EQUIPMENT_TYPES = listOf("DEFAULT", "FULL_BODY", "FULL_HELM", "FULL_MASK", "FULL_LEGS")
    /** The server's AttackStyleDefinition order (varbit 357). */
    val WEAPON_CATEGORIES = listOf("Unarmed", "Axe", "Maul", "Bow", "Claws", "Crossbow", "Salamander", "Chinchompa", "Bazooka",
        "Scimitar", "Two-handed", "Pickaxe", "Halberd", "Staff (no autocast)", "Scythe", "Spear", "Mace", "Dagger", "Wand", "Thrown",
        "Whip", "Staff", "Unused scimitar", "Thrown magic", "Unused spear", "Unused halberd", "Unused maul", "Dinh's bulwark",
        "Unused thrown magic", "Partisan")
    const val SPECIAL_ENUM = 906

    private val pretty = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    fun dataDir(server: File): File = File(server, "data")
    fun definitionsFile(server: File): File = File(server, "data/items/ItemDefinitions.json")
    fun specialsFile(server: File): File = File(server, "data/items/special-attacks.json")

    /**
     * The item's stats from the server's ItemDefinitions.json. With [effective] (the default), OSRS items get the OSRS
     * wiki data merged in the way the server does it, so this shows what the item really has in game.
     */
    fun read(server: File, id: Int, effective: Boolean = true): ServerItemStats =
        fromJson(id, if (effective) ItemStats.effective(server, id) else ItemStats.get(server, id))

    /** True when the server's file has its own entry for [id]. */
    fun has(server: File, id: Int) = ItemStats.get(server, id) != null

    fun fromJson(id: Int, o: JsonObject?): ServerItemStats {
        val s = ServerItemStats(id)
        o ?: return s
        o["weight"]?.takeIf { !it.isJsonNull }?.let { s.weight = it.asDouble }
        o["slot"]?.takeIf { !it.isJsonNull }?.let { s.slot = it.asInt }
        o["equipmentType"]?.takeIf { !it.isJsonNull }?.let { s.equipmentType = it.asString }
        val eq = o["equipmentDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return s
        eq["bonuses"]?.takeIf { !it.isJsonNull }?.asString?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.let { list ->
            for (i in 0 until minOf(14, list.size)) s.bonuses[i] = list[i]
        }
        eq["requirements"]?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
            val skill = k.toIntOrNull(); if (skill != null && !v.isJsonNull) s.requirements[skill] = v.asInt
        }
        val w = eq["weaponDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return s
        fun int(k: String, d: Int) = w[k]?.takeIf { !it.isJsonNull }?.asInt ?: d
        s.weapon = ServerItemStats.Weapon().apply {
            twoHanded = w["twoHanded"]?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            attackSpeed = w["attackSpeed"]?.takeIf { !it.isJsonNull }?.let { ItemStats.ticks(it.asInt) } ?: attackSpeed
            category = int("interfaceVarbit", category)
            stand = int("standAnimation", stand); walk = int("walkAnimation", walk); run = int("runAnimation", run)
            standTurn = int("standTurnAnimation", standTurn); turn90 = int("rotate90Animation", turn90)
            turn180 = int("rotate180Animation", turn180); turn270 = int("rotate270Animation", turn270)
            block = int("blockAnimation", block); accurate = int("accurateAnimation", accurate); aggressive = int("aggressiveAnimation", aggressive)
            controlled = int("controlledAnimation", controlled); defensive = int("defensiveAnimation", defensive)
            normalDistance = int("normalAttackDistance", 0); longDistance = int("longAttackDistance", 0)
        }
        return s
    }

    /** [s] written over [base] (the item's current entry, so fields the editor doesn't show stay as they are). */
    fun toJson(s: ServerItemStats, base: JsonObject?): JsonObject {
        val o = base?.deepCopy() ?: JsonObject()
        o.addProperty("id", s.id)
        ItemStats.setWeight(o, s.weight)
        o.addProperty("slot", s.slot)
        o.addProperty("equipmentType", s.equipmentType)
        val wearable = s.slot >= 0 || s.bonuses.any { it != 0 } || s.requirements.isNotEmpty() || s.weapon != null
        if (!wearable) { o.remove("equipmentDefinition"); return o }
        val eq = o["equipmentDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { o.add("equipmentDefinition", it) }
        val w = s.weapon
        if (w == null) eq.remove("weaponDefinition") else {
            val wd = eq["weaponDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            wd.addProperty("twoHanded", w.twoHanded)
            wd.addProperty("blockAnimation", w.block); wd.addProperty("standAnimation", w.stand); wd.addProperty("walkAnimation", w.walk)
            wd.addProperty("runAnimation", w.run); wd.addProperty("standTurnAnimation", w.standTurn)
            wd.addProperty("rotate90Animation", w.turn90); wd.addProperty("rotate180Animation", w.turn180); wd.addProperty("rotate270Animation", w.turn270)
            wd.addProperty("accurateAnimation", w.accurate); wd.addProperty("aggressiveAnimation", w.aggressive)
            wd.addProperty("controlledAnimation", w.controlled); wd.addProperty("defensiveAnimation", w.defensive)
            wd.addProperty("attackSpeed", ItemStats.stored(w.attackSpeed)); wd.addProperty("interfaceVarbit", w.category)
            wd.addProperty("normalAttackDistance", w.normalDistance); wd.addProperty("longAttackDistance", w.longDistance)
            eq.add("weaponDefinition", wd)
        }
        if (s.requirements.isEmpty()) eq.remove("requirements") else eq.add("requirements", JsonObject().also { r ->
            for ((k, v) in s.requirements) r.addProperty(k.toString(), v)
        })
        eq.addProperty("bonuses", s.bonuses.joinToString(", "))
        if (eq.has("equipmentType")) eq.addProperty("equipmentType", s.equipmentType)
        return o
    }

    /** Saves [s] into the server's ItemDefinitions.json (only that item's entry changes) and marks it for Deploy. */
    fun write(server: File, s: ServerItemStats) {
        backup(definitionsFile(server))
        ItemStats.put(server, toJson(s, ItemStats.get(server, s.id)))
    }

    /** item id -> special attack name, from data/items/special-attacks.json. */
    fun readSpecials(server: File): java.util.TreeMap<Int, String> {
        val out = java.util.TreeMap<Int, String>()
        val f = specialsFile(server)
        if (!f.isFile) return out
        val o = JsonParser().parse(f.readText(Charsets.UTF_8)).asJsonObject
        for ((k, v) in o.entrySet()) { val id = k.trim().toIntOrNull() ?: continue; if (!v.isJsonNull) out[id] = v.asString }
        return out
    }

    fun writeSpecials(server: File, map: Map<Int, String>) {
        val f = specialsFile(server)
        f.parentFile.mkdirs()
        if (f.isFile) backup(f)
        val o = JsonObject()
        for ((k, v) in map.toSortedMap()) o.addProperty(k.toString(), v)
        f.writeText(pretty.toJson(o) + "\n", Charsets.UTF_8)
        markPending("items/special-attacks.json")
    }

    /**
     * The special attacks the server has, by name, read from its source (SpecialAttack.java and the Kotlin specials
     * registered next to it), with the weapons each already belongs to.
     */
    fun specialNames(server: File): LinkedHashMap<String, List<Int>> {
        val out = LinkedHashMap<String, List<Int>>()
        val src = server.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "build" }
            .firstOrNull { it.isFile && it.name == "SpecialAttack.java" } ?: return out
        val text = src.readText()
        val body = text.substringAfter("implements ISpecialAttack {").substringBefore("public static final Map")
        // enum constants start at 4 spaces: NAME(AttackType.X, new int[]{...} or a single id
        val constant = Regex("""(?m)^ {4}([A-Z][A-Z0-9_]*)\(\s*AttackType\.[A-Z_]+\s*,\s*(new int\[\]\s*\{([^}]*)\}|[A-Za-z0-9_.]+)""")
        for (m in constant.findAll(body)) {
            val ids = (m.groupValues[3].ifEmpty { m.groupValues[2] }).split(',').mapNotNull { it.trim().toIntOrNull() }
            out[m.groupValues[1]] = ids
        }
        return out
    }

    /** The animation each special attack plays (its `new Animation(id)` in SpecialAttack.java), where it has one. */
    fun specialAnimations(server: File): Map<String, Int> {
        val src = server.walkTopDown().onEnter { !it.name.startsWith(".") && it.name != "build" }
            .firstOrNull { it.isFile && it.name == "SpecialAttack.java" } ?: return emptyMap()
        val body = src.readText().substringAfter("implements ISpecialAttack {").substringBefore("public static final Map")
        val out = HashMap<String, Int>()
        val starts = Regex("""(?m)^ {4}([A-Z][A-Z0-9_]*)\(\s*AttackType\.""").findAll(body).toList()
        for ((i, m) in starts.withIndex()) {
            val end = starts.getOrNull(i + 1)?.range?.first ?: body.length
            // the header is everything up to the attack script's lambda
            val header = body.substring(m.range.first, end).substringBefore("->")
            Regex("""new Animation\((\d+)""").find(header)?.let { out[m.groupValues[1]] = it.groupValues[1].toInt() }
        }
        return out
    }

    /** Special energy per weapon in the cache (enum 906, stored as percent x 10). */
    fun specialEnergy(lib: CacheLibrary, item: Int): Int? = EnumDef.read(lib, SPECIAL_ENUM)?.ints?.get(item)?.let { it / 10 }

    /** Sets (or with null, removes) a weapon's special energy in enum 906 of the exported cache. */
    fun setSpecialEnergy(item: Int, percent: Int?, backup: Boolean = true) {
        if (backup) CacheEdits.backup("special-energy-$item")
        CacheEdits.withExportCache { lib ->
            val e = EnumDef.read(lib, SPECIAL_ENUM) ?: EnumDef(SPECIAL_ENUM).apply { keyType = 'o'; valueType = 'i' }
            if (percent == null) e.ints.remove(item) else e.ints[item] = percent.coerceIn(0, 100) * 10
            lib.put(2, 8, SPECIAL_ENUM, e.encode())
            lib.index(2).update()
        }
    }

    // ---------------- backups and what's waiting to be deployed ----------------

    private fun backup(f: File) {
        if (!f.isFile) return
        val dir = File(System.getProperty("user.home"), ".uzis-map-editor/server-data-backups")
        dir.mkdirs()
        f.copyTo(File(dir, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date()) + "_" + f.name), true)
        val all = dir.listFiles()?.filter { it.name.endsWith(f.name) }?.sortedBy { it.name } ?: emptyList()
        if (all.size > 10) all.take(all.size - 10).forEach { it.delete() }
    }

    private val pendingFile get() = File(System.getProperty("user.home"), ".uzis-map-editor/data-pending.txt")

    /** Server data files (relative to data/) saved here and not deployed yet, other than item stats ([ItemStats.pending]). */
    fun pending(): Set<String> = pendingFile.takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun markPending(rel: String) { pendingFile.parentFile.mkdirs(); pendingFile.writeText((pending() + rel).joinToString("\n")) }
    fun clearPending() { pendingFile.delete() }
}

/** An enum config (config archive 8): a typed key -> value table. */
class EnumDef(val id: Int) {
    var keyType = 'i'
    var valueType = 'i'
    var defaultString = "null"
    var defaultInt = 0
    val ints = java.util.TreeMap<Int, Int>()
    val strings = java.util.TreeMap<Int, String>()
    private var hadDefaultString = false
    private var hadDefaultInt = false

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun i32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        fun str(s: String) { out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
        u8(1); u8(keyType.toInt())
        u8(2); u8(valueType.toInt())
        if (hadDefaultString || defaultString != "null") { u8(3); str(defaultString) }
        if (hadDefaultInt || defaultInt != 0) { u8(4); i32(defaultInt) }
        if (valueType == 's') { u8(5); u16(strings.size); for ((k, v) in strings) { i32(k); str(v) } }
        else { u8(6); u16(ints.size); for ((k, v) in ints) { i32(k); i32(v) } }
        u8(0)
        return out.toByteArray()
    }

    companion object {
        fun read(lib: CacheLibrary, id: Int): EnumDef? = lib.data(2, 8, id)?.let { decode(id, it) }

        fun decode(id: Int, b: ByteArray): EnumDef {
            val d = EnumDef(id)
            var p = 0
            fun u8() = b[p++].toInt() and 0xFF
            fun u16() = (u8() shl 8) or u8()
            fun i32() = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
            fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
            while (p < b.size) {
                when (u8()) {
                    0 -> return d
                    1 -> d.keyType = u8().toChar()
                    2 -> d.valueType = u8().toChar()
                    3 -> { d.defaultString = str(); d.hadDefaultString = true }
                    4 -> { d.defaultInt = i32(); d.hadDefaultInt = true }
                    5 -> repeat(u16()) { val k = i32(); d.strings[k] = str() }
                    6 -> repeat(u16()) { val k = i32(); d.ints[k] = i32() }
                    else -> error("enum $id uses a setting this editor doesn't know")
                }
            }
            return d
        }
    }
}
