package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Object definitions (config archive 6), read without losing anything, like [NpcDefs]: only the name and the
 * right-click options are rewritten. Checked against all 47,337 objects of the 211 cache.
 */
class ObjDef(val id: Int, val raw: ByteArray) {
    var name = "null"
    val options = arrayOfNulls<String>(5)
    internal val parts = ArrayList<Triple<Int, Int, Int>>()
    var readOk = true
    /** The first setting this editor doesn't know (when [readOk] is false), to tell the user. */
    var unknownOp = -1
    override fun toString() = "$id  $name"

    /**
     * For objects with settings the editor can't read (custom objects packed by other tools): keep every byte as it
     * is and add the new name and options at the end. The game reads settings in order, so the last ones win.
     */
    fun encodeAppended(newName: String, newOptions: Array<String?>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(raw, 0, raw.size - 1) // everything except the final end marker
        fun str(op: Int, s: String) { out.write(op); out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
        str(2, newName)
        for (i in 0 until 5) str(30 + i, newOptions[i] ?: "Hidden")
        out.write(0)
        return out.toByteArray()
    }

    fun encode(newName: String, newOptions: Array<String?>): ByteArray {
        val out = ByteArrayOutputStream()
        fun str(op: Int, s: String) { out.write(op); out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
        var wroteOpts = false; var wroteName = false
        fun opts() { if (wroteOpts) return; wroteOpts = true; for (i in 0 until 5) newOptions[i]?.let { str(30 + i, it) } }
        for ((op, s, e) in parts) {
            when {
                op == 2 -> { if (!wroteName) { str(2, newName); wroteName = true } }
                op in 30..34 -> opts()
                else -> out.write(raw, s, e - s)
            }
        }
        if (!wroteName && newName != "null") str(2, newName)
        opts()
        out.write(0)
        return out.toByteArray()
    }
}

object ObjDefs {
    /** [wideSounds]: caches from 220+ have an extra byte in the ambient sound opcodes (78/79). */
    private fun decodeWith(id: Int, b: ByteArray, wideSounds: Boolean): ObjDef? {
        val d = ObjDef(id, b)
        var p = 0
        fun u8() = b[p++].toInt() and 0xFF
        fun str(): String { val s = p; while (b[p].toInt() != 0) p++; val r = String(b, s, p - s, Charsets.ISO_8859_1); p++; return r }
        try {
            while (p < b.size) {
                val start = p
                val op = u8()
                if (op == 0) { return if (p == b.size) d else null }
                when (op) {
                    1 -> { val n = u8(); p += 3 * n }
                    2 -> d.name = str()
                    5 -> { val n = u8(); p += 2 * n }
                    14, 15, 19, 28, 29, 39, 69, 75, 81, 91 -> p += 1
                    17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 63 -> {}
                    24, 61, 60, 65, 66, 67, 68, 70, 71, 72, 82, 93, 94, 95 -> p += 2
                    in 30..34 -> { val s = str(); d.options[op - 30] = if (s.equals("Hidden", true)) null else s }
                    40, 41 -> { val n = u8(); p += 4 * n }
                    77, 92 -> { p += 4; if (op == 92) p += 2; val n = u8(); p += 2 * (n + 1) }
                    78 -> p += 3 + (if (wideSounds) 1 else 0)
                    79 -> { p += 5 + (if (wideSounds) 1 else 0); val n = u8(); p += 2 * n }
                    249 -> { val n = u8(); repeat(n) { val isString = u8() == 1; p += 3; if (isString) str() else p += 4 } }
                    else -> { d.unknownOp = op; return null }
                }
                if (p > b.size) return null
                d.parts.add(Triple(op, start, p))
            }
        } catch (e: Exception) { return null }
        return if (p == b.size) d else null
    }

    fun decode(id: Int, b: ByteArray): ObjDef =
        decodeWith(id, b, false) ?: decodeWith(id, b, true) ?: ObjDef(id, b).also { d ->
            d.readOk = false
            // still show what we can: name and options are plain text settings
            readLoose(b, d)
        }

    /** If a name/options block was added at the end by [ObjDef.encodeAppended], read it back (display only). */
    private fun readLoose(b: ByteArray, d: ObjDef) {
        for (start in b.size - 8 downTo 0) {
            if (b[start].toInt() != 2) continue
            var p = start
            val strs = ArrayList<String>()
            var ok = true
            for (op in intArrayOf(2, 30, 31, 32, 33, 34)) {
                if (p >= b.size || (b[p].toInt() and 0xFF) != op) { ok = false; break }
                p++
                val s0 = p
                while (p < b.size && b[p].toInt() != 0) p++
                if (p >= b.size) { ok = false; break }
                strs.add(String(b, s0, p - s0, Charsets.ISO_8859_1)); p++
            }
            if (ok && p == b.size - 1 && b[p].toInt() == 0) {
                d.name = strs[0]
                for (i in 0 until 5) d.options[i] = strs[i + 1].takeUnless { it.equals("Hidden", true) }
                return
            }
        }
    }

    fun read(id: Int): ObjDef? {
        val lib = CacheLibrary(NpcDefs.cacheDir().path)
        try { return lib.data(2, 6, id)?.let { decode(id, it) } } finally { lib.close() }
    }

    /** New name / options for one object, written into the exported cache. Returns what was written. */
    fun save(def: ObjDef, name: String, options: Array<String?>): ObjDef {
        if (!def.readOk) {
            // custom object with settings we can't read: add the new name/options after everything else
            val bytes = def.encodeAppended(name, options)
            if (bytes.size < def.raw.size || bytes[bytes.size - 1].toInt() != 0) throw IllegalStateException("It didn't build right, nothing was written.")
            write(def.id, bytes)
            return ObjDef(def.id, bytes).also { it.readOk = false; it.name = name; options.copyInto(it.options) }
        }
        val bytes = def.encode(name, options)
        val check = decode(def.id, bytes)
        if (!check.readOk || check.name != name || !check.options.contentEquals(options)) throw IllegalStateException("It didn't read back the same, nothing was written.")
        write(def.id, bytes)
        return check
    }

    /** A copy of [def] under a new object id (so one placed object can behave differently from the rest). */
    fun cloneAsNew(def: ObjDef, name: String, options: Array<String?>): ObjDef {
        val bytes = if (def.readOk) def.encode(name, options) else def.encodeAppended(name, options)
        val dir = GadTools.ensureExportCache()
        val lib = CacheLibrary(dir.path)
        val newId = try { (lib.index(2).archive(6)?.fileIds()?.max() ?: 0) + 1 } finally { lib.close() }
        write(newId, bytes)
        return decode(newId, bytes)
    }

    private fun write(id: Int, bytes: ByteArray) {
        val lib = CacheLibrary(GadTools.ensureExportCache().path)
        try { lib.put(2, 6, id, bytes); lib.index(2).update() } finally { lib.close() }
    }
}
