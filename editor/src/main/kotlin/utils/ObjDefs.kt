package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream

/**
 * Object definitions (config archive 6), read without losing anything, like [NpcDefs]: only the name and the
 * right-click options are rewritten. Reads both formats: rev 211 (models in opcodes 1 and 5 with 2-byte ids; the
 * GadScape client reads the ambient sound "retain" byte of 78/79 only above object 47337) and build 241 (opcodes 6
 * and 7 with 4-byte ids, the retain byte on every object; 1 and 5 carry no data there). Checked against all 47,337
 * objects of the 211 cache and every object of the stock 241 caches and GadScape's 241 cache.
 */
class ObjDef(val id: Int, val raw: ByteArray) {
    var name = "null"
    val options = arrayOfNulls<String>(5)
    internal val parts = ArrayList<Triple<Int, Int, Int>>()
    /** The format [raw] is in. */
    var rev = ConfigRev.R211
    var readOk = true
    /** Read with the extra ambient sound byte (opcodes 78/79). */
    var wideSounds = false
    /** The first setting this editor doesn't know (when [readOk] is false), to tell the user. */
    var unknownOp = -1
    override fun toString() = "$id  $name"

    /**
     * For objects with settings the editor can't read (custom objects packed by other tools): keep every byte as it
     * is and add the new name and options at the end. The game reads settings in order, so the last ones win.
     * Rev 211 removes an option with "Hidden", so every empty slot gets one. Build 241 skips a "Hidden" option
     * instead of clearing the slot, so there an option can only be replaced: empty slots are left as they were, and
     * removing an option the editor shows for this object throws (nothing is written). An option set after the
     * setting the editor can't read isn't shown, and in 241 it stays.
     */
    fun encodeAppended(newName: String, newOptions: Array<String?>): ByteArray {
        val r241 = ConfigRev.is241(rev)
        val want = Array(5) { i -> newOptions[i]?.takeUnless { r241 && it.equals("Hidden", true) } }
        if (r241) {
            val removed = (0 until 5).filter { want[it] == null && options[it] != null }
            if (removed.isNotEmpty()) throw IllegalStateException("Object $id has settings this editor can't read, and in a build 241 cache " +
                    "an option can't be removed by adding \"Hidden\" after them (the game skips it), so option " +
                    removed.joinToString(", ") { "${it + 1} (${options[it]})" } + " would stay. Nothing was written; give it another option instead.")
        }
        val out = ByteArrayOutputStream()
        out.write(raw, 0, raw.size - 1) // everything except the final end marker
        fun str(op: Int, s: String) { out.write(op); out.write(s.toByteArray(Charsets.ISO_8859_1)); out.write(0) }
        str(2, newName)
        for (i in 0 until 5) {
            val s = want[i]
            if (s != null) str(30 + i, s) else if (!r241) str(30 + i, "Hidden")
        }
        out.write(0)
        return out.toByteArray()
    }

    fun encode(newName: String, newOptions: Array<String?>): ByteArray {
        // nothing changed: the bytes as they were (options that were split up or "Hidden" stay that way)
        if (readOk && newName == name && newOptions.map { it?.takeIf { s -> !s.equals("Hidden", true) } } == options.toList()) return raw.copyOf()
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
    /** The GadScape client reads the extra sound byte (opcodes 78/79) only for object ids above this. */
    const val WIDE_SOUNDS_ABOVE = 47337

    /**
     * The same settings with the ambient sound opcodes (78/79) in the width the client expects for [newId], for
     * moving an object to another id (one above 47337 is read with the extra byte, one below without).
     */
    fun resoundFor(def: ObjDef, bytes: ByteArray, newId: Int): ByteArray {
        // build 241 objects all have the extra byte
        if (ConfigRev.is241(def.rev)) return bytes
        val want = newId > WIDE_SOUNDS_ABOVE
        val d = decodeWith(def.id, bytes, def.wideSounds) ?: return bytes
        if (d.wideSounds == want || d.parts.none { it.first == 78 || it.first == 79 }) return bytes
        val out = ByteArrayOutputStream()
        for ((op, s, e) in d.parts) {
            if (op != 78 && op != 79) { out.write(bytes, s, e - s); continue }
            val fixed = if (op == 78) 4 else 6 // opcode + the settings before the extra byte
            out.write(bytes, s, fixed)
            if (want) out.write(0)
            val rest = s + fixed + (if (d.wideSounds) 1 else 0)
            out.write(bytes, rest, e - rest)
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * Skips the data of object opcode [op] (already read) in the format [rev]; [wideSounds]: 78/79 have the retain
     * byte (always in 241). Throws on an opcode that format doesn't have. The tables are the 211 client's and the
     * build-241 client's.
     */
    fun skip(i: CfgIn, op: Int, id: Int, rev: Int, wideSounds: Boolean = ConfigRev.is241(rev)) {
        val r241 = ConfigRev.is241(rev)
        when (op) {
            2, 30, 31, 32, 33, 34 -> i.skipStr()
            14, 15, 19, 28, 29, 39, 69, 75, 81, 91, 95 -> i.skip(1)
            17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 94 -> {}
            24, 61, 65, 66, 67, 68, 70, 71, 72, 82 -> i.skip(2)
            40, 41 -> i.skip(4 * i.u8())
            77, 92 -> { i.skip(4); if (op == 92) i.skip(2); i.skip(2 * (i.u8() + 1)) }
            78 -> i.skip(3 + (if (wideSounds) 1 else 0))
            79 -> { i.skip(5 + (if (wideSounds) 1 else 0)); i.skip(2 * i.u8()) }
            93 -> i.skip(6)
            249 -> CfgParams.read(i, rev)
            else -> when {
                !r241 && op == 1 -> i.skip(3 * i.u8())
                !r241 && op == 5 -> i.skip(2 * i.u8())
                !r241 && op == 60 -> i.skip(2)
                !r241 && op == 63 -> {}
                r241 && op == 42 -> i.skip(2)
                r241 && op == 6 -> i.skip(5 * i.u8())
                r241 && op == 7 -> i.skip(4 * i.u8())
                r241 && op == 96 -> i.skip(1)
                r241 && op == 100 -> { i.skip(2); i.skipStr() }
                r241 && op == 101 -> { i.skip(13); i.skipStr() }
                r241 && op == 102 -> { i.skip(15); i.skipStr() }
                else -> throw IllegalStateException("object $id: setting $op isn't a ${ConfigRev.name(rev)} object setting")
            }
        }
    }

    /**
     * Reads [b] in exactly the format [rev], or null. [wideSounds]: 78/79 carry the extra retain byte (GadScape 211
     * objects above 47337, every 241 object). An option "Hidden" clears its slot in rev 211; the build 241 client
     * skips it (the slot keeps what it had), and so does this.
     */
    internal fun decodeWith(id: Int, b: ByteArray, wideSounds: Boolean, rev: Int = ConfigRev.R211): ObjDef? {
        val d = ObjDef(id, b)
        val r241 = ConfigRev.is241(rev)
        d.rev = if (r241) ConfigRev.R241 else ConfigRev.R211
        val i = CfgIn(b)
        try {
            while (i.p < b.size) {
                val start = i.p
                val op = i.u8()
                // the game stops at the end marker; some server packers pad the definition with zeros after it
                if (op == 0) { d.wideSounds = wideSounds; return if (i.p == b.size || (i.p until b.size).all { b[it].toInt() == 0 }) d else null }
                when (op) {
                    2 -> d.name = i.str()
                    in 30..34 -> { val s = i.str(); if (!s.equals("Hidden", true)) d.options[op - 30] = s else if (!r241) d.options[op - 30] = null }
                    else -> skip(i, op, id, rev, wideSounds)
                }
                if (i.p > b.size) return null
                d.parts.add(Triple(op, start, i.p))
            }
        } catch (e: Exception) { return null }
        return if (i.p == b.size) d else null
    }

    /**
     * Reads an object. [rev] is the format it should be in (the cache it came from); one that only reads in the
     * other format (a 211 object written into a 241 cache) is read that way.
     */
    fun decode(id: Int, b: ByteArray, rev: Int = ConfigRev.current()): ObjDef {
        fun as211() = decodeWith(id, b, id > WIDE_SOUNDS_ABOVE) ?: decodeWith(id, b, id <= WIDE_SOUNDS_ABOVE)
        fun as241() = decodeWith(id, b, true, ConfigRev.R241)
        return (if (ConfigRev.is241(rev)) as241() ?: as211() else as211() ?: as241()) ?: ObjDef(id, b).also { d ->
            d.readOk = false
            d.rev = if (ConfigRev.is241(rev)) ConfigRev.R241 else ConfigRev.R211
            // still show what we can: the settings before the first unknown one, then a block added by encodeAppended
            readPrefix(b, d, ConfigRev.is241(d.rev) || id > WIDE_SOUNDS_ABOVE)
            readLoose(b, d)
        }
    }

    /**
     * Name and options from the settings before the first one the editor can't read (an unreadable object in format
     * [ObjDef.rev]); sets [ObjDef.unknownOp]. What comes after that setting can't be told apart from data.
     */
    private fun readPrefix(b: ByteArray, d: ObjDef, wideSounds: Boolean) {
        val r241 = ConfigRev.is241(d.rev)
        val i = CfgIn(b)
        try {
            while (i.p < b.size) {
                val op = i.u8()
                if (op == 0) return
                when (op) {
                    2 -> d.name = i.str()
                    in 30..34 -> { val s = i.str(); if (!s.equals("Hidden", true)) d.options[op - 30] = s else if (!r241) d.options[op - 30] = null }
                    else -> try { skip(i, op, d.id, d.rev, wideSounds) } catch (e: IllegalStateException) { d.unknownOp = op; return }
                }
            }
        } catch (e: Exception) { }
    }

    /**
     * If a name/options block was added at the end by [ObjDef.encodeAppended], read it back (display only): opcode 2
     * and then options in slot order (all five in rev 211, only the ones set in build 241), up to the end marker.
     */
    private fun readLoose(b: ByteArray, d: ObjDef) {
        for (start in b.size - 3 downTo 0) {
            if (b[start].toInt() != 2) continue
            var p = start
            val found = LinkedHashMap<Int, String>()
            var ok = true
            var last = -1
            while (p < b.size - 1) {
                val op = b[p].toInt() and 0xFF
                if (!(op == 2 && p == start) && !(op in 30..34 && op > last)) { ok = false; break }
                last = op
                p++
                val s0 = p
                while (p < b.size && (b[p].toInt() and 0xFF) >= 32) p++
                if (p >= b.size || b[p].toInt() != 0) { ok = false; break }
                found[op] = String(b, s0, p - s0, Charsets.ISO_8859_1); p++
            }
            if (ok && p == b.size - 1 && b[p].toInt() == 0 && found.containsKey(2)) {
                d.name = found.getValue(2)
                // later settings win; "Hidden" clears a slot in 211 and is skipped in 241
                for (i in 0 until 5) {
                    val s = found[30 + i] ?: continue
                    if (!s.equals("Hidden", true)) d.options[i] = s else if (!ConfigRev.is241(d.rev)) d.options[i] = null
                }
                return
            }
        }
    }

    fun read(id: Int): ObjDef? {
        val lib = CacheLibrary(NpcDefs.cacheDir().path)
        try { return lib.data(2, 6, id)?.let { decode(id, it, ConfigRev.of(lib)) } } finally { lib.close() }
    }

    /** New name / options for one object, written into the exported cache. Returns what was written. */
    fun save(def: ObjDef, name: String, options: Array<String?>): ObjDef {
        if (!def.readOk) {
            // custom object with settings we can't read: add the new name/options after everything else
            val bytes = def.encodeAppended(name, options)
            if (bytes.size < def.raw.size || bytes[bytes.size - 1].toInt() != 0) throw IllegalStateException("It didn't build right, nothing was written.")
            write(def.id, bytes, null, def.rev)
            return ObjDef(def.id, bytes).also { d -> d.readOk = false; d.rev = def.rev; d.name = name
                for (i in 0 until 5) d.options[i] = options[i]?.takeUnless { it.equals("Hidden", true) } }
        }
        val bytes = def.encode(name, options)
        val check = decode(def.id, bytes, def.rev)
        // "Hidden" leaves the slot empty: rev 211 clears it, build 241 skips it (the options are written once each)
        val expect = options.map { it?.takeUnless { s -> s.equals("Hidden", true) } }.toTypedArray()
        if (!check.readOk || check.rev != def.rev || check.name != name || !check.options.contentEquals(expect)) throw IllegalStateException("It didn't read back the same, nothing was written.")
        return write(def.id, bytes, check, def.rev) ?: check
    }

    /**
     * A copy of [def] under a new object id (so one placed object can behave differently from the rest). In a build
     * 241 cache the new id comes from GadScape's object range (65534 down to 64000, see [cache.IdKind.LOC]): the game
     * protocol carries object ids in 16 bits, so a higher id would reach the server as another object when clicked.
     */
    fun cloneAsNew(def: ObjDef, name: String, options: Array<String?>): ObjDef {
        val bytes = if (def.readOk) def.encode(name, options) else def.encodeAppended(name, options)
        val lib = CacheLibrary(GadTools.ensureExportCache().path)
        val newId = try { cache.IdAllocator(lib, cache.IdKind.LOC).next() } finally { lib.close() }
        // a 211 client reads opcodes 78/79 one byte wider above 47337: match the new id or the rest reads as garbage
        val out = if (def.readOk) resoundFor(def, bytes, newId) else bytes
        val read = if (def.readOk) decode(newId, out, def.rev) else null
        return write(newId, out, read, def.rev) ?: read ?: decode(newId, out, def.rev)
    }

    /**
     * Writes object [id] into the exported cache. [bytes] are in format [rev]; [read] is the object they hold (null
     * when they can't be read): when the exported cache is build 241 and the object was read as rev 211, it is
     * converted first (4-byte model ids, the sound retain byte). Returns the converted object, read back, when it was
     * converted. Bytes the editor can't read can't be converted, so they are only written into a cache of their format.
     */
    private fun write(id: Int, bytes: ByteArray, read: ObjDef?, rev: Int): ObjDef? {
        val lib = CacheLibrary(GadTools.ensureExportCache().path)
        try {
            val target = ConfigRev.of(lib)
            var out = bytes
            var back: ObjDef? = null
            if (read == null && ConfigRev.is241(rev) != ConfigRev.is241(target))
                throw IllegalStateException("Object $id has settings this editor can't read, so it can't be converted from the ${ConfigRev.name(rev)} " +
                        "format to the ${ConfigRev.name(target)} format of the exported cache. Nothing was written.")
            if (read != null && read.rev != target) {
                if (!ConfigRev.is241(target)) throw IllegalStateException("Object $id is a build 241 object; it can't be written into a rev 211 cache.")
                // the retain byte is there already when the 211 bytes had it (wide), else it is added
                out = ConfigConv.loc211to241(id, bytes, if (read.wideSounds) -1 else Int.MAX_VALUE)
                back = decodeWith(id, out, true, target) ?: throw IllegalStateException("Object $id didn't convert to the build 241 format, nothing was written.")
            }
            lib.put(2, 6, id, out)
            lib.index(2).update()
            return back
        } finally { lib.close() }
    }
}
