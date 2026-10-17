package utils

import com.displee.cache.CacheLibrary
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A name and a category for every animation id: what it is (whose walk, which weapon's attack) worked out from the
 * cache (NPCs, objects, graphics), the server's item file (weapon animations), and the server's code (special attacks,
 * emotes, pets, and every named `Animation(id)` in it).
 *
 * The editor ships one built from the GadScape cache (resources/data/anim-catalog.json). "Rebuild names" makes one
 * from the cache and server folder in use (~/.uzis-map-editor/anim-catalog.json, used instead of the shipped one).
 * Names typed by hand (~/.uzis-map-editor/anim-names.json) always win.
 */
object AnimCatalog {
    class Entry(val name: String, val category: String, val uses: List<String>)

    val CATEGORIES = listOf("Weapons", "Player", "Emotes", "Skilling", "NPCs", "Pets", "Objects", "Graphics", "Unknown")

    private val home get() = File(System.getProperty("user.home"), ".uzis-map-editor")
    fun userCatalogFile() = File(home, "anim-catalog.json")
    fun namesFile() = File(home, "anim-names.json")

    @Volatile private var base: Map<Int, Entry>? = null
    @Volatile private var custom: Map<Int, String>? = null
    /** Where the names came from, for messages. */
    @Volatile var source = ""
        private set

    // ======================= reading =======================

    private fun baseMap(): Map<Int, Entry> = base ?: synchronized(this) {
        base ?: run {
            val user = userCatalogFile()
            val m = try {
                if (user.isFile) parse(user.readText(Charsets.UTF_8)).also { source = user.path }
                else AnimCatalog::class.java.getResourceAsStream("/data/anim-catalog.json")?.use { parse(it.reader(Charsets.UTF_8).readText()) }
                    ?.also { source = "the names that come with the editor" } ?: emptyMap()
            } catch (e: Exception) { println("Animation names: ${e.message}"); emptyMap() }
            base = m
            m
        }
    }

    private fun customMap(): Map<Int, String> = custom ?: synchronized(this) {
        custom ?: run {
            val f = namesFile()
            val m = try {
                if (!f.isFile) emptyMap() else JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject.entrySet()
                    .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.asString } }.toMap()
            } catch (e: Exception) { println("Animation names you typed: ${e.message}"); emptyMap() }
            custom = m
            m
        }
    }

    private fun parse(text: String): Map<Int, Entry> {
        val out = HashMap<Int, Entry>()
        val arr = JsonParser.parseString(text).asJsonObject.getAsJsonArray("animations")
        for (e in arr) {
            val o = e.asJsonObject
            out[o["id"].asInt] = Entry(o["name"].asString, o["category"].asString, o.getAsJsonArray("uses")?.map { it.asString } ?: emptyList())
        }
        return out
    }

    fun entry(id: Int): Entry? {
        val b = baseMap()[id]
        val c = customMap()[id] ?: return b
        return Entry(c, b?.category ?: "Unknown", b?.uses ?: emptyList())
    }
    fun name(id: Int): String? = entry(id)?.name
    fun category(id: Int): String = entry(id)?.category ?: "Unknown"
    /** True when [id]'s name was typed by hand. */
    fun renamed(id: Int) = id in customMap()

    /** "1658  Abyssal whip - attack", for lists. */
    fun label(id: Int): String = name(id)?.let { "$id  $it" } ?: id.toString()

    /** True when [id] fits the [category] filter (null/"All" = any) and every word of [q] (id or name). */
    fun matches(id: Int, category: String?, q: String): Boolean {
        val e = entry(id)
        if (category != null && category != "All" && (e?.category ?: "Unknown") != category) return false
        val words = q.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val text = (e?.name ?: "").lowercase()
        return words.all { w -> (w.all { it.isDigit() } && id.toString().startsWith(w)) || text.contains(w) }
    }

    /** Gives [id] your own name (null or blank: back to the worked-out one). */
    fun rename(id: Int, name: String?) {
        val m = HashMap(customMap())
        if (name.isNullOrBlank()) m.remove(id) else m[id] = name.trim()
        val o = JsonObject(); m.toSortedMap().forEach { (k, v) -> o.addProperty(k.toString(), v) }
        namesFile().parentFile.mkdirs()
        namesFile().writeText(com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n", Charsets.UTF_8)
        custom = m
    }

    fun counts(ids: Collection<Int>): Map<String, Int> = ids.groupingBy { category(it) }.eachCount()

    // ======================= building =======================

    private class Use(val rank: Int, val category: String?, val text: String, val hint: String = text)

    /** The plain player animations every weapon falls back to. */
    private val CORE = mapOf(808 to "Player - stand", 819 to "Player - walk", 824 to "Player - run", 823 to "Player - turn on the spot",
        820 to "Player - turn around (180)", 821 to "Player - turn right (90)", 822 to "Player - turn left (270)",
        422 to "Player - punch", 423 to "Player - kick", 424 to "Player - block (unarmed)", 836 to "Player - death",
        866 to "Emote - Dance", 4069 to "Player - teleport (standard)")

    private val WEAPON_SLOTS = linkedMapOf("standAnimation" to "stand", "walkAnimation" to "walk", "runAnimation" to "run",
        "standTurnAnimation" to "turn on the spot", "rotate90Animation" to "turn right", "rotate180Animation" to "turn around",
        "rotate270Animation" to "turn left", "blockAnimation" to "block", "accurateAnimation" to "attack (accurate)",
        "aggressiveAnimation" to "attack (aggressive)", "controlledAnimation" to "attack (controlled)", "defensiveAnimation" to "attack (defensive)")

    /**
     * Works out every animation's name from [lib] and, when given, the server folder [server] (its source and its
     * data/items/ItemDefinitions.json). [log] gets progress lines.
     */
    fun build(lib: CacheLibrary, server: File?, log: (String) -> Unit = {}): Map<Int, Entry> {
        val anims = AnimCache(lib)
        val seqIds = anims.seqIds()
        val uses = HashMap<Int, ArrayList<Use>>()
        fun add(seq: Int, u: Use) { if (seq in 0..65534) uses.getOrPut(seq) { ArrayList() }.add(u) }

        val itemNames = HashMap<Int, String>()
        fun itemName(id: Int): String = itemNames.getOrPut(id) { try { ItemStore.read(lib, id)?.name } catch (e: Exception) { null }?.let(::clean) ?: "Item $id" }

        for ((id, n) in CORE) add(id, Use(0, if (n.startsWith("Emote")) "Emotes" else "Player", n))

        // ---- the server: specials, weapons, emotes, pets, and every named Animation(...) in its code ----
        val src = server?.let { sourceFiles(it) } ?: emptyList()
        if (server != null) {
            log("Reading the server's special attacks...")
            try {
                val specialAnims = ServerItems.specialAnimations(server)
                val specials = ServerItems.specialNames(server)
                // specials tied to weapons first, so a shared animation is named after a weapon
                for ((name, anim) in specialAnims.entries.sortedBy { if (specials[it.key].isNullOrEmpty()) 1 else 0 }) {
                    val items = specials[name] ?: emptyList()
                    val label = items.firstOrNull()?.let { "${itemName(it)} - special attack" } ?: ("Special attack - " + humanize(name))
                    add(anim, Use(2, "Weapons", label, "Special attack $name" + if (items.size > 1) " (items ${items.joinToString(", ")})" else ""))
                }
            } catch (e: Exception) { log("Special attacks: ${e.message}") }

            log("Reading the server's weapon animations...")
            try {
                val f = ServerItems.definitionsFile(server)
                if (f.isFile) {
                    // anim -> slot -> item ids
                    val byAnim = HashMap<Int, java.util.TreeMap<String, java.util.TreeSet<Int>>>()
                    for (e in JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonArray) {
                        if (!e.isJsonObject) continue
                        val o = e.asJsonObject
                        val w = o["equipmentDefinition"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("weaponDefinition")?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val item = o["id"]?.asInt ?: continue
                        for ((k, slot) in WEAPON_SLOTS) {
                            val a = w[k]?.takeIf { it.isJsonPrimitive }?.asInt ?: continue
                            if (a <= 0 || a in CORE) continue
                            byAnim.getOrPut(a) { java.util.TreeMap() }.getOrPut(slot) { java.util.TreeSet() }.add(item)
                        }
                    }
                    for ((a, slots) in byAnim) {
                        val items = slots.values.flatten().toSortedSet()
                        // the first item with a real name stands for the rest
                        val who = items.asSequence().map { itemName(it) }.firstOrNull { !it.startsWith("Item ") && it != "null" } ?: itemName(items.first())
                        val slotNames = slots.keys.toList()
                        val what = when {
                            slotNames.all { it.startsWith("attack") } && slotNames.size > 1 -> "attack"
                            slotNames.size > 2 -> slotNames.take(2).joinToString(", ") + "..."
                            else -> slotNames.joinToString(", ")
                        }
                        val more = items.size - 1
                        add(a, Use(1, "Weapons", "$who - $what", "Weapon: $who - ${slotNames.joinToString(", ")}" + if (more > 0) " (+$more more items)" else ""))
                    }
                }
            } catch (e: Exception) { log("Weapon animations: ${e.message}") }
        }

        val npcIdConsts = constants(src.filter { it.name == "NpcId.java" || it.name == "CustomNpcId.java" || it.name == "CustomNpcId.kt" })
        val petNpcs = HashMap<Int, String>()
        for (f in src) {
            val text = f.readText()
            if (f.name.startsWith("Emote") && f.name != "EmoteClue.java") {
                for (m in EMOTE.findAll(text)) add(m.groupValues[2].toInt(), Use(3, "Emotes", "Emote - " + humanize(m.groupValues[1]).replaceFirstChar { it.uppercase() }))
            }
            if (Regex("""enum\s+\w+\s+implements\s+[\w, ]*\bPet\b""").containsMatchIn(text)) {
                for (m in PET.findAll(text)) {
                    val arg = m.groupValues[3].trim()
                    val npc = arg.toIntOrNull() ?: npcIdConsts[arg.substringAfterLast('.')] ?: continue
                    petNpcs[npc] = m.groupValues[1]
                }
            }
        }

        // ---- the cache: NPCs, objects, graphics ----
        log("Reading NPCs...")
        val npcAnimSlots = mapOf(13 to listOf("stand"), 14 to listOf("walk"), 15 to listOf("turn left (idle)"), 16 to listOf("turn right (idle)"),
            17 to listOf("walk", "walk backwards", "walk left", "walk right"), 114 to listOf("run"),
            115 to listOf("run", "run backwards", "run left", "run right"), 116 to listOf("crawl"),
            117 to listOf("crawl", "crawl backwards", "crawl left", "crawl right"))
        // anim -> slot -> npc names (with ids)
        class NpcUse(val npc: Int, val name: String, val slot: String, val pet: Boolean)
        val npcUses = HashMap<Int, ArrayList<NpcUse>>()
        val npcRigUsers = HashMap<Int, ArrayList<String>>()
        lib.index(2).archive(9)?.let { arch ->
            for (id in arch.fileIds()) {
                val raw = lib.data(2, 9, id) ?: continue
                val d = NpcDefs.decode(id, raw)
                val name = clean(d.name)
                if (name == "null" || name.isBlank()) continue
                for ((op, s, _) in d.parts) {
                    val slots = npcAnimSlots[op] ?: continue
                    for ((i, slot) in slots.withIndex()) {
                        val p = s + 1 + i * 2
                        if (p + 1 >= raw.size) break
                        val a = ((raw[p].toInt() and 255) shl 8) or (raw[p + 1].toInt() and 255)
                        if (a == 65535 || a < 0) continue
                        npcUses.getOrPut(a) { ArrayList() }.add(NpcUse(id, name, slot, id in petNpcs))
                    }
                }
            }
        }
        for ((a, list) in npcUses) {
            val pets = list.filter { it.pet }
            val group = pets.ifEmpty { list }
            // the most common name among its users
            val who = group.groupingBy { it.name }.eachCount().maxByOrNull { (n, c) -> c * 10 - n.length / 10 }!!.key
            val slots = group.filter { it.name == who }.map { it.slot }.distinct()
            val ids = group.map { it.npc }.distinct()
            add(a, Use(if (pets.isNotEmpty()) 4 else 5, if (pets.isNotEmpty()) "Pets" else "NPCs", "$who - ${slots.joinToString(", ")}",
                (if (pets.isNotEmpty()) "Pet" else "NPC") + " $who (${ids.take(4).joinToString(", ")}${if (ids.size > 4) ", +${ids.size - 4}" else ""}) - ${slots.joinToString(", ")}"))
        }

        log("Reading the server's code for named animations...")
        val gfxNames = HashMap<Int, String>()
        for (f in src) {
            val cls = f.nameWithoutExtension
            if (cls == "NpcId" || cls == "ItemId" || cls == "ObjectId") continue
            val path = f.invariantSeparatorsPath.lowercase()
            val hint = when {
                "/skills/" in path -> "Skilling"
                cls.startsWith("Emote") -> "Emotes"
                "specialattack" in path -> "Weapons"
                else -> null
            }
            f.forEachLine { line ->
                if (!line.contains("Animation(") && !line.contains("Graphics(")) return@forEachLine
                for (m in ANIM.findAll(line)) {
                    val id = m.groupValues[2].toInt()
                    val what = nameOn(line, m.range.first)
                    val text = humanizeClass(cls) + (what?.let { " - $it" } ?: "")
                    val rank = if (what != null) 6 else 9
                    if (m.groupValues[1] == "Graphics") { if (what != null || id !in gfxNames) gfxNames[id] = text; continue }
                    add(id, Use(rank, hint, text, "Server: $cls" + (what?.let { " ($it)" } ?: "")))
                }
            }
        }

        log("Reading objects...")
        lib.index(2).archive(6)?.let { arch ->
            val byAnim = HashMap<Int, ArrayList<Pair<Int, String>>>()
            for (id in arch.fileIds()) {
                val raw = lib.data(2, 6, id) ?: continue
                val d = try { ObjDefs.decode(id, raw) } catch (e: Exception) { continue }
                for ((op, s, _) in d.parts) if (op == 24 && s + 2 < raw.size) {
                    val a = ((raw[s + 1].toInt() and 255) shl 8) or (raw[s + 2].toInt() and 255)
                    if (a != 65535) byAnim.getOrPut(a) { ArrayList() }.add(id to clean(d.name))
                }
            }
            for ((a, list) in byAnim) {
                val named = list.filter { it.second != "null" && it.second.isNotBlank() }
                val who = named.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
                add(a, Use(7, "Objects", "${who ?: "Object ${list.first().first}"} (object)", "Object ${who ?: ""} (${list.take(4).joinToString(", ") { it.first.toString() }}${if (list.size > 4) ", +${list.size - 4}" else ""})".replace("  ", " ")))
            }
        }

        log("Reading graphics...")
        lib.index(2).archive(13)?.let { arch ->
            for (id in arch.fileIds()) {
                val b = lib.data(2, 13, id) ?: continue
                val a = spotSequence(b) ?: continue
                val n = gfxNames[id]
                add(a, Use(8, "Graphics", if (n != null) "$n (graphic $id)" else "Graphic $id", "Graphic $id" + (n?.let { ": $it" } ?: "")))
            }
        }

        // ---- rigs: which skeleton each animation moves ----
        log("Working out which body each animation is for...")
        val groupRig = HashMap<Int, Int>()
        val rig = HashMap<Int, Int>()
        for (id in seqIds) {
            val s = try { anims.seq(id) } catch (e: Exception) { null } ?: continue
            val r = if (s.skeletalModel != -1) {
                val k = s.skeletalModel
                lib.data(0, k ushr 16, k and 0xFFFF)?.let { if (it.size >= 3) ((it[1].toInt() and 255) shl 8) or (it[2].toInt() and 255) else null }
                    ?.let { 0x100000 or it }
            } else s.frameIds.firstOrNull()?.let { f ->
                groupRig.getOrPut(f ushr 16) {
                    val b = lib.data(0, f ushr 16, f and 0xFFFF)
                    lib.index(0).unCache()
                    if (b != null && b.size >= 2) ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255) else -1
                }.takeIf { it >= 0 }
            }
            if (r != null) rig[id] = r
        }
        val playerRigs = uses.filter { (_, l) -> l.any { it.rank <= 3 } }.keys.mapNotNull { rig[it] }.groupingBy { it }.eachCount()
            .filter { it.value >= 3 }.keys
        val petRigs = uses.filter { (_, l) -> l.any { it.rank == 4 } }.keys.mapNotNull { rig[it] }.toSet()
        // a body a boss shares with its pet (General Graardor and his Jr.) is the boss's
        val npcRigs = uses.filter { (_, l) -> l.any { it.rank == 5 } }.keys.mapNotNull { rig[it] }.toSet()

        // ---- pick each animation's name ----
        val out = java.util.TreeMap<Int, Entry>()
        val rigNamed = HashMap<Int, ArrayList<Pair<Int, Entry>>>()
        for (id in seqIds) {
            val l = uses[id] ?: continue
            l.sortBy { it.rank }
            val best = l.first()
            val r = rig[id]
            val cat = best.category ?: when {
                r != null && r in playerRigs -> "Player"
                r != null && r in npcRigs -> "NPCs"
                r != null && r in petRigs -> "Pets"
                r != null -> "NPCs"
                else -> "Unknown"
            }
            val e = Entry(best.text.replaceFirstChar { it.uppercase() }, cat, usesList(l))
            out[id] = e
            if (r != null) rigNamed.getOrPut(r) { ArrayList() }.add(id to e)
        }
        // the rest: named after the body they move
        for (id in seqIds) {
            if (id in out) continue
            val r = rig[id]
            val mates = r?.let { rigNamed[it] }
            val e = when {
                r != null && r in playerRigs -> Entry("Unnamed player animation", "Player", listOf("Moves the player's body"))
                mates != null && mates.isNotEmpty() -> {
                    // the body's owner: the NPC (or pet) most of its named animations belong to
                    val owner = mates.map { it.second.name.substringBefore(" - ").substringBefore(" (") }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    val cat = mates.map { it.second.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    Entry("Unnamed ($owner's body)", cat, listOf("Same body as ${mates.take(3).joinToString(", ") { "${it.first} ${it.second.name}" }}"))
                }
                else -> Entry("Unknown" + (r?.let { " (body ${if (it >= 0x100000) "skeletal ${it - 0x100000}" else it.toString()})" } ?: ""), "Unknown", emptyList())
            }
            out[id] = e
        }
        log("Named ${out.count { !it.value.name.startsWith("Unnamed") && !it.value.name.startsWith("Unknown") }} of ${out.size} animations.")
        return out
    }

    /** Builds from [lib]/[server], saves it as the catalogue in use, and returns the counts per category. */
    fun rebuild(lib: CacheLibrary, server: File?, log: (String) -> Unit = {}): Map<String, Int> {
        val m = build(lib, server, log)
        userCatalogFile().parentFile.mkdirs()
        write(m, userCatalogFile(), server?.path ?: "no server folder")
        synchronized(this) { base = m; source = userCatalogFile().path }
        return m.values.groupingBy { it.category }.eachCount()
    }

    /** Writes [m] as JSON, one animation per line. */
    fun write(m: Map<Int, Entry>, f: File, from: String) {
        val g = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("{\"version\":1,\"built\":${g.toJson(SimpleDateFormat("yyyy-MM-dd").format(Date()))},\"from\":${g.toJson(from)},\"animations\":[\n")
            var first = true
            for ((id, e) in m.toSortedMap()) {
                if (!first) w.write(",\n"); first = false
                w.write(g.toJson(linkedMapOf("id" to id, "name" to e.name, "category" to e.category, "uses" to e.uses)))
            }
            w.write("\n]}\n")
        }
    }

    // ======================= helpers =======================

    private fun usesList(l: List<Use>): List<String> {
        val texts = l.map { it.hint }.distinct()
        return if (texts.size <= 12) texts else texts.take(12) + "... and ${texts.size - 12} more"
    }

    private fun sourceFiles(server: File): List<File> = server.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") && it.name != "node_modules" && it.name != "data" && it.name != "cache-out" }
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }.toList()

    /** NAME = number constants (NpcId.java style). */
    private fun constants(files: List<File>): Map<String, Int> {
        val out = HashMap<String, Int>()
        val r = Regex("""\b([A-Z][A-Z0-9_]*)\s*(?::\s*Int\s*)?=\s*(\d+)\s*[;\n]""")
        for (f in files) for (m in r.findAll(f.readText())) out.putIfAbsent(m.groupValues[1], m.groupValues[2].toInt())
        return out
    }

    private val EMOTE = Regex("""(?m)^\s*([A-Z][A-Z0-9_]+)\s*\(\s*-?\d+\s*,\s*new Animation\((\d+)""")
    /** An enum constant whose 2nd argument is the pet's NPC id. */
    private val PET = Regex("""(?m)^\s*([A-Z][A-Z0-9_]+)\s*\(\s*([^,()]+)\s*,\s*([^,()]+)\s*[,)]""")
    private val ANIM = Regex("""\b(Animation|Graphics)\(\s*(\d+)\s*[,)]""")
    private val ASSIGN = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*(?:Animation|Graphics)\??\s*)?=\s*(?:new\s+)?$""")
    private val ENUM_CONST = Regex("""^\s*([A-Z][A-Z0-9_]{2,})\s*\(""")
    private val GENERIC = setOf("anim", "animation", "gfx", "graphics", "graphic", "it", "a", "animate", "emote", "start", "end", "spot", "spotanim")

    /** The constant/variable the Animation(...) at [at] in [line] is given to, or the enum constant the line starts. */
    private fun nameOn(line: String, at: Int): String? {
        val raw = ASSIGN.find(line.substring(0, at))?.groupValues?.get(1)
            ?: ENUM_CONST.find(line)?.groupValues?.get(1)
            ?: return null
        val h = humanize(raw)
        return h.takeIf { it.isNotBlank() && it !in GENERIC }
    }

    /** ATTACK_ANIM / attackAnimation -> "attack". */
    fun humanize(s: String): String {
        val words = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").split('_').filter { it.isNotBlank() }.map { it.lowercase() }
        val trimmed = words.dropLastWhile { it in setOf("anim", "animation", "anims", "animations", "gfx", "graphic", "graphics", "seq", "sequence", "id") }
            .dropWhile { it in setOf("m", "s", "k") }
        return trimmed.joinToString(" ")
    }

    /** GeneralGraardor -> "General Graardor". */
    private fun humanizeClass(c: String) = c.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')
        .removeSuffix(" Kt").removeSuffix(" NPC").removeSuffix(" Npc").trim()

    private fun clean(s: String) = s.replace(Regex("<[^>]*>"), "").trim()

    /** The animation a graphic (spot animation) plays, or null. */
    private fun spotSequence(b: ByteArray): Int? {
        var p = 0
        fun u8() = b[p++].toInt() and 255
        fun u16() = (u8() shl 8) or u8()
        try {
            while (p < b.size) {
                when (u8()) {
                    0 -> return null
                    1 -> u16()
                    2 -> return u16().takeIf { it != 65535 }
                    4, 5, 6 -> u16()
                    7, 8, 9 -> u8()
                    40, 41 -> { val n = u8(); p += 4 * n }
                    else -> return null
                }
            }
        } catch (e: Exception) {}
        return null
    }
}
