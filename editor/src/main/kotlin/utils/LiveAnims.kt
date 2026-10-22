package utils

import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A live Old School item's own animations, brought into this cache (build 241 or rev 211) with it.
 *
 * Which animations an item uses isn't in the item: newer items (build 241 on) list them (item setting 161), and for
 * the rest Jagex's own animation names (RuneLite's gameval list, e.g. SCYTHE_OF_VITUR_READY) carry the item's name.
 * Each name says what the animation is for (READY = standing, WALK_F = walking, ATTACK...), which is how they're
 * matched to the server's weapon animations. The animations are bundled in the live cache's own (build 241) layout,
 * with their frames, skeletons and skeletal keyframe sets (archive 22 in the live cache), and imported through
 * [CacheEdits.importBundle], which converts them to the target cache's layout ([SeqConv]) and puts keyframe sets
 * where that cache keeps them (archive 22 in 241, archive 0 in 211).
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
     * A newer (226+, build 241) animation definition written the way 211 reads it (see [SeqConv.to211]: skeletal
     * 13 -> 14, its range 15 -> 16, sounds back to 211 records; height offset, debug name and 19 left out).
     */
    fun to211(b: ByteArray): ByteArray = SeqConv.to211(-1, b).bytes

    /**
     * An animation bundle (the editor's own format, see [CacheEdits.exportBundle]) of live animations [ids], with
     * their frames, skeletal keyframes and skeletons, ready for [CacheEdits.importBundle]. The sequences stay in the
     * live cache's layout: bundle.txt names the live cache's own build ("format 241"), and the import converts them
     * for a 211 cache and refuses a build newer than 241 ([CacheEdits.requireKnownFormat]). Only caches of build 241
     * or older are bundled ([LiveOsrs.NEWEST_BUILD]); an older one is read and checked as 241 and says "format 241".
     */
    fun bundle(cat: LiveOsrs.Catalog, ids: Collection<Int>, out: File) {
        require(cat.build <= LiveOsrs.NEWEST_BUILD) {
            "the live cache is build ${cat.build}, newer than build ${LiveOsrs.NEWEST_BUILD} (GadScape's game), which can't read its animations"
        }
        val seqs = LiveOsrs.groupFiles(cat.cacheId, 2, 12)
        val groups = HashSet<Int>(); val keyframes = HashSet<Int>(); val skels = HashSet<Int>()
        ZipOutputStream(out.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            for (id in ids.distinct().sorted()) {
                val raw = seqs[id] ?: error("animation $id isn't in the live cache")
                val s = SeqDef.decode(id, raw, ConfigRev.R241)
                require(s.readOk) { "animation $id has settings newer than build 241, which this editor can't read" }
                put("seq/$id.dat", raw)
                (s.frameIds.toList() + (s.chatFrameIds?.toList() ?: emptyList())).forEach { groups.add(it ushr 16) }
                if (s.skeletalModel != -1) keyframes.add(s.skeletalModel)
            }
            for (g in groups) for ((f, b) in LiveOsrs.groupFiles(cat.cacheId, 0, g)) {
                put("frames/$g/$f.dat", b)
                if (b.size >= 2) skels.add(((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255))
            }
            // skeletal keyframe sets are in archive 22 of the live (226+) cache, not archive 0 with the frame sets
            for (k in keyframes) {
                val b = LiveOsrs.groupFiles(cat.cacheId, cache.CacheIO.KEYFRAMES, k ushr 16)[k and 0xFFFF]
                    ?: error("keyframes ${k ushr 16}:${k and 0xFFFF} aren't in the live cache's archive 22")
                put("keyframes/${k ushr 16}/${k and 0xFFFF}.dat", b)
                if (b.size >= 3) skels.add(((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255))
            }
            for (s in skels) put("skeletons/$s.dat", LiveOsrs.group(cat.cacheId, 1, s))
            // the live build, or 241 for an older cache (its sequences were read and checked as 241 ones above)
            val format = maxOf(cat.build, ConfigRev.R241)
            put("bundle.txt", ("# Uzi's Map Editor animation bundle (live OSRS cache ${cat.cacheId}, build ${cat.build})\nformat $format\n" +
                    ids.joinToString("\n") { "seq $it" } + "\n").toByteArray())
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

    /**
     * Imports prepared animations into the exported cache; the item's weapon animations with this cache's ids. They
     * are live content, so they keep their live ids where those are free (and an animation that is already here under
     * its own id is used as it is); see [CacheEdits.importInto].
     */
    fun apply(p: Prepared): Map<String, Int> {
        try {
            val live = p.roles.values.distinct().sorted()
            val r = CacheEdits.importBundle(p.zip, null, keepIds = true)
            val newId = live.zip(r.newSeqIds).toMap()
            return p.roles.mapValues { (_, v) -> newId[v] ?: v }
        } finally { p.zip.delete() }
    }
}
