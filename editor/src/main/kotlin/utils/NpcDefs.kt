package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * NPC definitions (config archive 9), read without losing anything: every opcode is kept as raw bytes,
 * so writing one back only changes what was edited (the right-click options). Reads both formats: rev 211 (models
 * in opcodes 1 and 60, 2-byte ids) and build 241 (61 and 62, 4-byte ids; 1 and 60 carry no data there, so an NPC
 * with them shows no model in the 241 game). Checked against all 12,141 NPCs of the 211 cache and every NPC of the
 * stock 241 caches and GadScape's 241 cache.
 */
class NpcDef(val id: Int, val raw: ByteArray) {
    var name = "null"
    var models = IntArray(0)
    /** Chathead models (211 opcode 60, 241 opcode 62). */
    var chatModels = IntArray(0)
    /** The format [raw] is in. */
    var rev = ConfigRev.R211
    var standAnim = -1
    var walkAnim = -1
    var size = 1
    var combat = -1
    var widthScale = 128
    var heightScale = 128
    var recolorFrom: ShortArray? = null
    var recolorTo: ShortArray? = null
    var retextureFrom: ShortArray? = null
    var retextureTo: ShortArray? = null
    /** Right-click options, slots 1-5 (null = empty). */
    val options = arrayOfNulls<String>(5)
    /** A follower (a pet): 211 opcode 111, 241 opcode 122 (both tables have 122 and 123; no 211 cache uses them). */
    var follower = false
    /** Other players' followers have their options below "Walk here": 211 opcode 111 too, 241 opcode 123. */
    var followerOpsLow = false
    /** (opcode, start, end) of every entry in [raw], terminator excluded. */
    internal val parts = ArrayList<Triple<Int, Int, Int>>()
    var readOk = true

    val label: String get() = "$id  $name" + if (combat > 0) "  (level $combat)" else ""
    override fun toString() = label

    /** The definition with [newOptions] in place of the old options; everything else byte-for-byte the same. */
    fun encode(newOptions: Array<String?>): ByteArray {
        // nothing changed: the bytes as they were (options that were split up or "Hidden" stay that way)
        if (readOk && newOptions.map { it?.takeIf { s -> !s.equals("Hidden", true) } } == options.toList()) return raw.copyOf()
        val out = ByteArrayOutputStream()
        var wrote = false
        fun writeOptions() {
            if (wrote) return
            wrote = true
            for (i in 0 until 5) {
                val s = newOptions[i] ?: continue
                out.write(30 + i)
                out.write(s.toByteArray(Charsets.ISO_8859_1))
                out.write(0)
            }
        }
        for ((op, s, e) in parts) {
            // options go where the first one was (an NPC without options gets them at the end)
            if (op in 30..34) { writeOptions(); continue }
            out.write(raw, s, e - s)
        }
        writeOptions()
        out.write(0)
        return out.toByteArray()
    }
}

object NpcDefs {
    /**
     * Skips the data of NPC opcode [op] (already read) in the format [rev]; throws on an opcode that format doesn't
     * have. The tables are the 211 client's and the build-241 client's.
     */
    fun skip(i: CfgIn, op: Int, id: Int, rev: Int) {
        val r241 = ConfigRev.is241(rev)
        when (op) {
            2, 30, 31, 32, 33, 34 -> i.skipStr()
            12, 100, 101 -> i.skip(1)
            13, 14, 15, 16, 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 114, 116, 124, 126, 146 -> i.skip(2)
            17, 115, 117 -> i.skip(8)
            40, 41 -> i.skip(4 * i.u8())
            // flags (111: "follower" + "follower options low" in 211; render priority in 241, where those are 122 and 123)
            93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> {}
            102 -> {
                val bits = i.u8()
                var n = 0
                while ((bits shr n) != 0) n++
                for (k in 0 until n) if (bits and (1 shl k) != 0) { i.skipBigSmart(); i.skipSmart() }
            }
            106, 118 -> { i.skip(4); if (op == 118) i.skip(2); i.skip(2 * (i.u8() + 1)) }
            249 -> CfgParams.read(i, rev)
            251 -> { i.skip(2); i.skipStr() }
            252 -> { i.skip(13); i.skipStr() }
            253 -> { i.skip(15); i.skipStr() }
            else -> when {
                !r241 && (op == 1 || op == 60) -> i.skip(2 * i.u8())
                r241 && (op == 61 || op == 62) -> i.skip(4 * i.u8())
                r241 && op == 148 -> i.skip(4)
                r241 && (op == 149 || op == 151) -> i.skip(1)
                r241 && op == 150 -> i.skip(6)
                r241 && op == 152 -> { i.skip(6); i.skip(2 * i.u8()) }
                else -> throw IllegalStateException("NPC $id: setting $op isn't a ${ConfigRev.name(rev)} NPC setting")
            }
        }
    }

    fun skip211(i: CfgIn, op: Int, id: Int) = skip(i, op, id, ConfigRev.R211)

    /**
     * Reads an NPC. [rev] is the format it should be in (the cache it came from); one that only reads in the other
     * format (a 211 NPC written into a 241 cache) is read that way.
     */
    fun decode(id: Int, b: ByteArray, rev: Int = ConfigRev.current()): NpcDef {
        val first = decodeAs(id, b, rev)
        if (first.readOk) return first
        val second = decodeAs(id, b, ConfigRev.other(rev))
        return if (second.readOk) second else first
    }

    /**
     * Reads an NPC in exactly the format [rev]. An option "Hidden" clears its slot in rev 211; the build 241 client
     * skips it (the slot keeps what it had), and so does this.
     */
    fun decodeAs(id: Int, b: ByteArray, rev: Int): NpcDef {
        val d = NpcDef(id, b)
        val r241 = ConfigRev.is241(rev)
        d.rev = if (r241) ConfigRev.R241 else ConfigRev.R211
        val i = CfgIn(b)
        try {
            while (i.p < b.size) {
                val start = i.p
                val op = i.u8()
                if (op == 0) break
                when {
                    op == 1 && !r241 -> { val n = i.u8(); d.models = IntArray(n) { i.u16() } }
                    op == 61 && r241 -> { val n = i.u8(); d.models = IntArray(n) { i.i32() } }
                    op == 60 && !r241 -> { val n = i.u8(); d.chatModels = IntArray(n) { i.u16() } }
                    op == 62 && r241 -> { val n = i.u8(); d.chatModels = IntArray(n) { i.i32() } }
                    op == 2 -> d.name = i.str()
                    op == 12 -> d.size = i.u8()
                    op == 13 -> d.standAnim = i.u16()
                    op == 14 -> d.walkAnim = i.u16()
                    op in 30..34 -> { val s = i.str(); if (!s.equals("Hidden", true)) d.options[op - 30] = s else if (!r241) d.options[op - 30] = null }
                    op == 111 && !r241 -> { d.follower = true; d.followerOpsLow = true }
                    op == 122 -> d.follower = true
                    op == 123 -> d.followerOpsLow = true
                    op == 40 -> { val n = i.u8(); val f = ShortArray(n); val t = ShortArray(n); for (k in 0 until n) { f[k] = i.u16().toShort(); t[k] = i.u16().toShort() }; d.recolorFrom = f; d.recolorTo = t }
                    op == 41 -> { val n = i.u8(); val f = ShortArray(n); val t = ShortArray(n); for (k in 0 until n) { f[k] = i.u16().toShort(); t[k] = i.u16().toShort() }; d.retextureFrom = f; d.retextureTo = t }
                    op == 95 -> d.combat = i.u16()
                    op == 97 -> d.widthScale = i.u16()
                    op == 98 -> d.heightScale = i.u16()
                    else -> skip(i, op, id, rev)
                }
                d.parts.add(Triple(op, start, i.p))
            }
        } catch (e: Exception) {
            d.readOk = false
        }
        return d
    }

    @Volatile private var loaded: Map<Int, NpcDef>? = null
    @Volatile private var loading = false
    /** Bumped when a definition changes, with the ids that changed (the 3D view rebuilds those models). */
    @Volatile var generation = 0
        private set
    private val changed = java.util.Collections.synchronizedSet(HashSet<Int>())

    fun cachedOrNull(): Map<Int, NpcDef>? = loaded
    fun all(): Map<Int, NpcDef> = loaded ?: synchronized(this) { loaded ?: load().also { loaded = it } }
    fun loadInBackground() {
        if (loaded != null || loading) return
        loading = true
        Thread({ try { all() } catch (e: Exception) { println("NPC definitions: $e") } finally { loading = false } }, "npc-defs").apply { isDaemon = true }.start()
    }
    fun changedIds(): List<Int> = synchronized(changed) { changed.toList().also { changed.clear() } }
    private fun replaced(def: NpcDef) {
        loaded = HashMap(loaded ?: emptyMap()).apply { put(def.id, def) }
        changed.add(def.id); generation++
    }

    /** Where NPC edits are read from and written to: the exported cache once it exists, else the map's cache. */
    fun cacheDir(): File {
        val out = GadTools.exportCacheDir
        return if (out.listFiles()?.any { it.name.startsWith("main_file_cache") } == true) out else File(cache.CachePaths.cacheDir)
    }

    fun load(): Map<Int, NpcDef> {
        val out = HashMap<Int, NpcDef>()
        val lib = CacheLibrary(cacheDir().path)
        try {
            val rev = ConfigRev.of(lib)
            val archive = lib.index(2).archive(9) ?: return out
            for (id in archive.fileIds()) {
                val data = lib.data(2, 9, id) ?: continue
                out[id] = decode(id, data, rev)
            }
        } finally { lib.close() }
        return out
    }

    /** Write new options for one NPC into the exported cache (deploy sends it), in that cache's format. */
    fun saveOptions(def: NpcDef, options: Array<String?>): NpcDef {
        if (!def.readOk) throw IllegalStateException("NPC ${def.id} uses opcodes this editor doesn't know, so it isn't changed.")
        val dir = GadTools.ensureExportCache()
        val lib = CacheLibrary(dir.path)
        try {
            val target = ConfigRev.of(lib)
            var bytes = def.encode(options)
            if (target != def.rev) {
                // read from a 211 cache, written into a 241 one: model ids move to their 4-byte opcodes
                if (!ConfigRev.is241(target)) throw IllegalStateException("NPC ${def.id} is a build 241 NPC; it can't be written into a rev 211 cache.")
                bytes = ConfigConv.npc211to241(def.id, bytes)
            }
            val check = decodeAs(def.id, bytes, target)
            if (!check.readOk || check.name != def.name || !check.models.contentEquals(def.models) ||
                check.follower != def.follower || check.followerOpsLow != def.followerOpsLow ||
                !check.options.contentEquals(options.map { it?.takeIf { s -> !s.equals("Hidden", true) } }.toTypedArray()))
                throw IllegalStateException("The new definition didn't read back the same, nothing was written.")
            lib.put(2, 9, def.id, bytes)
            lib.index(2).update()
            replaced(check)
            return check
        } finally { lib.close() }
    }
}
