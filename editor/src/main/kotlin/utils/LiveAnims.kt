package utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A live Old School item's own animations, brought into this (211) cache with it.
 *
 * Which animations an item uses isn't in the item: newer items (build 241 on) list them (item setting 161), and for
 * the rest Jagex's own animation names (RuneLite's gameval list, e.g. SCYTHE_OF_VITUR_READY) carry the item's name.
 * Each name says what the animation is for (READY = standing, WALK_F = walking, ATTACK...), which is how they're
 * matched to the server's weapon animations. The animations are written the way 211 reads them ([to211]) and
 * imported with their frames and skeletons through [CacheEdits.importBundle].
 */
object LiveAnims {
    private const val NAMES_URL = "https://raw.githubusercontent.com/runelite/runelite/master/runelite-api/src/main/java/net/runelite/api/gameval/AnimationID.java"
    private val dir get() = File(System.getProperty("user.home"), ".uzis-map-editor/live-osrs").apply { mkdirs() }

    @Volatile private var names: Map<Int, String>? = null

    /** Jagex's names for the newest animations, by id (downloaded at most once a day). */
    fun names(): Map<Int, String> {
        names?.let { return it }
        val f = File(dir, "AnimationID.java")
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > 86_400_000L) {
            try {
                val b = URI(NAMES_URL).toURL().openStream().use { it.readBytes() }
                val tmp = File(dir, "AnimationID.java.tmp"); tmp.writeBytes(b); tmp.renameTo(f)
            } catch (e: Exception) { if (!f.isFile) throw IllegalStateException("couldn't get the animation names: ${e.message}") }
        }
        val re = Regex("""public static final int (\w+) = (\d+);""")
        return re.findAll(f.readText()).associate { it.groupValues[2].toInt() to it.groupValues[1] }.also { names = it }
    }

    /** The server weapon animation an animation's name is for (ItemStats' weaponDefinition keys), or null. */
    fun role(name: String): String? {
        val n = name.uppercase()
        if (listOf("FEMALE", "VFX", "SPOTANIM", "_OLD", "LOOT", "NPC", "SPEC").any { it in n }) return null
        return when {
            n.endsWith("_READY_TURN") || n.contains("TURN_ON_SPOT") || n.endsWith("_TURN") -> "standTurnAnimation"
            n.endsWith("_READY") || n.endsWith("_STAND") -> "standAnimation"
            n.endsWith("_WALK_F") || n.endsWith("_WALK") -> "walkAnimation"
            n.endsWith("_WALK_B") -> "rotate180Animation"
            n.endsWith("_WALK_R") -> "rotate90Animation"
            n.endsWith("_WALK_L") -> "rotate270Animation"
            n.endsWith("_RUN_F") || n.endsWith("_RUN") -> "runAnimation"
            n.endsWith("_BLOCK") || n.endsWith("_DEFEND") || n.endsWith("_DEFENCE") -> "blockAnimation"
            "ATTACK" in n -> "attack"
            else -> null
        }
    }

    /** The name part an item's animations carry: "Scythe of vitur" -> SCYTHE_OF_VITUR. */
    private fun token(itemName: String) = itemName.uppercase().replace(Regex("""\(.*?\)"""), " ")
        .replace(Regex("[^A-Z0-9]+"), "_").trim('_')

    /**
     * The live animation ids for an item's server weapon animations ("standAnimation" -> id, ...), from the item's
     * own list ([listed], setting 161) or else from animation names carrying its name. Empty when nothing matches.
     */
    fun forItem(itemName: String, listed: IntArray?): Map<String, Int> {
        val all = names()
        val candidates: List<Pair<Int, String>> = if (listed != null && listed.isNotEmpty()) listed.toList().mapNotNull { id -> val n: String? = all[id]; if (n == null) null else Pair(id, n) }
        else {
            // the full name first, then without leading words ("Holy scythe of vitur" -> SCYTHE_OF_VITUR), two words at least
            var words = token(itemName).split('_').filter { it.isNotEmpty() }
            var found = emptyList<Pair<Int, String>>()
            while (found.isEmpty() && words.size >= 2) {
                val t = words.joinToString("_")
                found = all.entries.filter { (_, n) -> n == t || n.startsWith(t + "_") || n.contains("_" + t + "_") || n.endsWith("_$t") }
                    .map { it.key to it.value }
                words = words.drop(1)
            }
            // then names with all of the item's words in any order (HUMAN_AXE_SOULREAPER_... for "Soulreaper axe")
            if (found.isEmpty()) {
                val want = token(itemName).split('_').filter { it.length > 1 && it != "OF" && it != "THE" }.toSet()
                if (want.size >= 2) found = all.entries.filter { (_, n) -> n.split('_').toSet().containsAll(want) }.map { it.key to it.value }
            }
            found
        }
        val out = LinkedHashMap<String, Int>()
        // names with HUMAN in them are the player's own; take those first
        val sorted = candidates.sortedWith(compareBy({ !it.second.contains("HUMAN") }, { it.first }))
        val attacks = ArrayList<Int>()
        for ((id, n) in sorted) {
            val r = role(n) ?: continue
            if (r == "attack") { attacks.add(id); continue }
            out.putIfAbsent(r, id)
        }
        if (attacks.isNotEmpty()) {
            out["accurateAnimation"] = attacks[0]
            out["aggressiveAnimation"] = attacks.getOrElse(1) { attacks[0] }
            out["controlledAnimation"] = attacks.getOrElse(2) { attacks[0] }
            out["defensiveAnimation"] = attacks[0]
        }
        // standing still and turning on the spot look alike when there's no turning of its own
        out["standAnimation"]?.let { out.putIfAbsent("standTurnAnimation", it) }
        return out
    }

    /**
     * A newer (226+) animation definition written the way 211 reads it. Moved settings go back to their 211 numbers
     * (skeletal 13 -> 14, its range 15 -> 16); sounds, height offset (16), debug name (18) and 19 are left out:
     * 211 has nothing to play them with.
     */
    fun to211(b: ByteArray): ByteArray {
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun i32() = (u16() shl 16) or u16()
        val out = ByteArrayOutputStream()
        fun w8(v: Int) = out.write(v)
        fun w16(v: Int) { out.write(v shr 8); out.write(v) }
        fun w32(v: Int) { w16(v ushr 16); w16(v and 0xFFFF) }
        while (p < b.size) {
            when (val o = u8()) {
                0 -> break
                1 -> { val n = u16(); w8(1); w16(n); repeat(n * 3) { w16(u16()) } }
                2, 6, 7 -> { w8(o); w16(u16()) }
                3 -> { val n = u8(); w8(3); w8(n); repeat(n) { w8(u8()) } }
                4 -> w8(4)
                5, 8, 9, 10, 11 -> { w8(o); w8(u8()) }
                12 -> { val n = u8(); w8(12); w8(n); repeat(n * 2) { w16(u16()) } }
                13 -> { w8(14); w32(i32()) }
                14 -> { val n = u16(); repeat(n) { p += 2 + 6 } }
                15 -> { w8(16); w16(u16()); w16(u16()) }
                16 -> p++
                17 -> { val n = u8(); w8(17); w8(n); repeat(n) { w8(u8()) } }
                18 -> { while (b[p].toInt() != 0) p++; p++ }
                19 -> {}
                else -> error("animation setting $o isn't known")
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * An animation bundle (the editor's own format, see [CacheEdits.exportBundle]) of live animations [ids], with
     * their frames, skeletal keyframes and skeletons, ready for [CacheEdits.importBundle].
     */
    fun bundle(cat: LiveOsrs.Catalog, ids: Collection<Int>, out: File) {
        val seqs = LiveOsrs.groupFiles(cat.cacheId, 2, 12)
        val groups = HashSet<Int>(); val keyframes = HashSet<Int>(); val skels = HashSet<Int>()
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            for (id in ids.distinct().sorted()) {
                val raw = seqs[id] ?: error("animation $id isn't in the live cache")
                val conv = to211(raw)
                val s = SeqDef.decode(id, conv)
                require(s.readOk) { "animation $id didn't convert" }
                put("seq/$id.dat", conv)
                (s.frameIds.toList() + (s.chatFrameIds?.toList() ?: emptyList())).forEach { groups.add(it ushr 16) }
                if (s.skeletalModel != -1) keyframes.add(s.skeletalModel)
            }
            for (g in groups) for ((f, b) in LiveOsrs.groupFiles(cat.cacheId, 0, g)) {
                put("frames/$g/$f.dat", b)
                if (b.size >= 2) skels.add(((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255))
            }
            for (k in keyframes) {
                val b = LiveOsrs.groupFiles(cat.cacheId, 0, k ushr 16)[k and 0xFFFF] ?: error("keyframes $k aren't in the live cache")
                put("keyframes/${k ushr 16}/${k and 0xFFFF}.dat", b)
                if (b.size >= 3) skels.add(((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255))
            }
            for (s in skels) put("skeletons/$s.dat", LiveOsrs.group(cat.cacheId, 1, s))
            put("bundle.txt", ("# Uzi's Map Editor animation bundle (live OSRS)\n" + ids.joinToString("\n") { "seq $it" } + "\n").toByteArray())
        }
    }

    /** An item's animations downloaded and ready to import: its roles (live ids) and the bundle holding them. */
    class Prepared(val roles: Map<String, Int>, val zip: File)

    /** Finds and downloads an item's own animations (no cache writes; safe off the window's thread), or null. */
    fun prepare(cat: LiveOsrs.Catalog, itemName: String, listed: IntArray?): Prepared? {
        val roles = forItem(itemName, listed)
        if (roles.isEmpty()) return null
        val zip = File.createTempFile("live-anims", ".zip").apply { deleteOnExit() }
        bundle(cat, roles.values, zip)
        return Prepared(roles, zip)
    }

    /** Imports prepared animations into the exported cache; the item's weapon animations with this cache's ids. */
    fun apply(p: Prepared): Map<String, Int> {
        try {
            val live = p.roles.values.distinct().sorted()
            val r = CacheEdits.importBundle(p.zip, null)
            val newId = live.zip(r.newSeqIds).toMap()
            return p.roles.mapValues { (_, v) -> newId[v] ?: v }
        } finally { p.zip.delete() }
    }
}
