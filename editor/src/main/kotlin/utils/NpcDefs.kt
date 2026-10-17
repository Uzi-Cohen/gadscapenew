package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * NPC definitions (config archive 9), read without losing anything: every opcode is kept as raw bytes,
 * so writing one back only changes what was edited (the right-click options). Checked against all
 * 12,141 NPCs of the 211 cache.
 */
class NpcDef(val id: Int, val raw: ByteArray) {
    var name = "null"
    var models = IntArray(0)
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
    /** (opcode, start, end) of every entry in [raw], terminator excluded. */
    internal val parts = ArrayList<Triple<Int, Int, Int>>()
    var readOk = true

    val label: String get() = "$id  $name" + if (combat > 0) "  (level $combat)" else ""
    override fun toString() = label

    /** The definition with [newOptions] in place of the old options; everything else byte-for-byte the same. */
    fun encode(newOptions: Array<String?>): ByteArray {
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
    fun decode(id: Int, b: ByteArray): NpcDef {
        val d = NpcDef(id, b)
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
        try {
            while (p < b.size) {
                val start = p
                val op = u8()
                if (op == 0) break
                when (op) {
                    1 -> { val n = u8(); d.models = IntArray(n) { u16() } }
                    2 -> d.name = str()
                    12 -> d.size = u8()
                    13 -> d.standAnim = u16()
                    14 -> d.walkAnim = u16()
                    15, 16, 18 -> u16()
                    17 -> repeat(4) { u16() }
                    in 30..34 -> { val s = str(); d.options[op - 30] = if (s.equals("Hidden", true)) null else s }
                    40 -> { val n = u8(); val f = ShortArray(n); val t = ShortArray(n); for (i in 0 until n) { f[i] = u16().toShort(); t[i] = u16().toShort() }; d.recolorFrom = f; d.recolorTo = t }
                    41 -> { val n = u8(); val f = ShortArray(n); val t = ShortArray(n); for (i in 0 until n) { f[i] = u16().toShort(); t[i] = u16().toShort() }; d.retextureFrom = f; d.retextureTo = t }
                    60 -> { val n = u8(); repeat(n) { u16() } }
                    in 74..79 -> u16()
                    93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147 -> {}
                    95 -> d.combat = u16()
                    97 -> d.widthScale = u16()
                    98 -> d.heightScale = u16()
                    103, 114, 116, 124, 126, 146 -> u16()
                    100, 101 -> u8()
                    102 -> {
                        val bits = u8()
                        var n = 0
                        while ((bits shr n) != 0) n++
                        for (i in 0 until n) if (bits and (1 shl i) != 0) {
                            p += if (b[p].toInt() and 0x80 != 0) 4 else 2
                            p += if ((b[p].toInt() and 0xFF) < 128) 1 else 2
                        }
                    }
                    106, 118 -> { u16(); u16(); if (op == 118) u16(); val n = u8(); repeat(n + 1) { u16() } }
                    115, 117 -> repeat(4) { u16() }
                    249 -> {
                        val n = u8()
                        repeat(n) { val isString = u8() == 1; p += 3; if (isString) str() else p += 4 }
                    }
                    else -> { d.readOk = false; return d }
                }
                d.parts.add(Triple(op, start, p))
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
            val archive = lib.index(2).archive(9) ?: return out
            for (id in archive.fileIds()) {
                val data = lib.data(2, 9, id) ?: continue
                out[id] = decode(id, data)
            }
        } finally { lib.close() }
        return out
    }

    /** Write new options for one NPC into the exported cache (deploy sends it). */
    fun saveOptions(def: NpcDef, options: Array<String?>): NpcDef {
        if (!def.readOk) throw IllegalStateException("NPC ${def.id} uses opcodes this editor doesn't know, so it isn't changed.")
        val bytes = def.encode(options)
        val check = decode(def.id, bytes)
        if (!check.readOk || check.name != def.name || !check.options.contentEquals(options.map { it?.takeIf { s -> !s.equals("Hidden", true) } }.toTypedArray()))
            throw IllegalStateException("The new definition didn't read back the same, nothing was written.")
        val dir = GadTools.ensureExportCache()
        val lib = CacheLibrary(dir.path)
        try {
            lib.put(2, 9, def.id, bytes)
            lib.index(2).update()
        } finally { lib.close() }
        replaced(check)
        return check
    }
}
