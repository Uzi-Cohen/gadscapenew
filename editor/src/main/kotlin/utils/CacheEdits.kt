package utils

import com.displee.cache.CacheLibrary
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writing models and animations into the exported cache (the copy that Deploy sends to the server). Every write
 * first saves a copy of the cache's index files and data, so "Undo last" puts it back exactly.
 */
object CacheEdits {
    private val backupRoot = File(System.getProperty("user.home"), ".uzis-map-editor/cache-backups")

    /** Copy of the exported cache before a change; only the newest few are kept. */
    fun backup(what: String): File {
        val src = GadTools.ensureExportCache()
        val dir = File(backupRoot, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date()) + "_" + what.replace(Regex("[^A-Za-z0-9_-]"), "_"))
        dir.mkdirs()
        src.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.forEach { it.copyTo(File(dir, it.name), true) }
        val all = backupRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (all.size > 4) all.take(all.size - 4).forEach { it.deleteRecursively() }
        return dir
    }

    fun lastBackup(): File? = backupRoot.listFiles()?.filter { it.isDirectory }?.maxBy { it.name }

    /** Put the exported cache back as it was before the last model/animation change. */
    fun undoLast(): String {
        val b = lastBackup() ?: return "There's nothing to undo."
        val dst = GadTools.ensureExportCache()
        // written over in place: on Windows a cache file that's open can't be deleted, so copyTo(overwrite) fails
        b.listFiles()?.forEach { src -> java.io.FileOutputStream(File(dst, src.name)).use { o -> src.inputStream().use { it.copyTo(o) } } }
        val name = b.name
        b.deleteRecursively()
        return "Put the cache back as it was before: ${name.substringAfter('_').substringAfter('_')} (${name.take(19).replace('_', ' ')})."
    }

    fun <T> withExportCache(block: (CacheLibrary) -> T): T {
        val lib = CacheLibrary(GadTools.ensureExportCache().path)
        try { return block(lib) } finally { lib.close() }
    }

    // ---------------- models (index 7) ----------------

    fun nextFreeModelId(lib: CacheLibrary): Int = ModelIds(lib).next()

    /**
     * Hands out new model ids: from where the first run of 50 free ids starts (after the game's own models), never
     * above 65535, because items, objects and NPCs store model ids in 2 bytes. (A cache with a model at 65535 would
     * otherwise give 65536, which the item saves as 0.)
     */
    class ModelIds(lib: CacheLibrary) {
        private val used = lib.index(7).archiveIds().toHashSet()
        private var next: Int = run {
            var start = -1; var run = 0; var found = -1
            for (i in 0..65535) {
                if (i in used) run = 0 else { if (run == 0) start = i; run++; if (run >= 50) { found = start; break } }
            }
            if (found >= 0) found else (0..65535).firstOrNull { it !in used } ?: 65536
        }
        fun next(): Int {
            while (next in used) next++
            require(next <= 65535) { "There are no free model ids left (model ids only go up to 65535)." }
            used.add(next)
            return next++
        }
    }

    /** Before writing animations: puts a padded animations archive back to its real size (see [BigArchive]). */
    fun repairAnimationsIfNeeded(lib: CacheLibrary): String {
        if (!BigArchive.animationsNeedRepair(lib)) return ""
        val n = BigArchive.repairAnimations(lib) ?: error("the animations in this cache can't be read, nothing was written")
        return "Took the padding off $n animation(s) so the editor can save them (the game reads them the same). "
    }

    /** Writes a model; [id] null = the next free id. Returns the id used. */
    fun putModel(data: ByteArray, id: Int?): Int {
        backup("model-" + (id ?: "new"))
        return withExportCache { lib ->
            val use = id ?: nextFreeModelId(lib)
            lib.put(7, use, 0, data)
            lib.index(7).update()
            use
        }
    }

    // ---------------- animation bundles ----------------

    /**
     * An animation with everything it needs, as a zip: the sequence, its frame files and skeletons.
     * Import it into another cache (or this one) with [importBundle].
     */
    fun exportBundle(src: CacheLibrary, seqIds: List<Int>, out: File): String {
        val anims = AnimCache(src)
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(name: String, b: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(b); zip.closeEntry() }
            val groups = HashSet<Int>(); val skels = HashSet<Int>()
            val manifest = StringBuilder("# Uzi's Map Editor animation bundle\n")
            for (id in seqIds.distinct()) {
                val raw = anims.seqData(id) ?: continue
                val seq = SeqDef.decode(id, raw)
                put("seq/$id.dat", raw)
                manifest.append("seq ").append(id).append('\n')
                (seq.frameIds.toList() + (seq.chatFrameIds?.toList() ?: emptyList())).forEach { groups.add(it ushr 16) }
                // a skeletal animation's keyframe set: index 0 too, skeleton id after a version byte
                if (seq.skeletalModel != -1) {
                    val g = seq.skeletalModel ushr 16; val f = seq.skeletalModel and 0xFFFF
                    val b = src.data(0, g, f)
                    if (b != null && b.size >= 3) { put("keyframes/$g/$f.dat", b); skels.add(((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)) }
                    src.index(0).unCache()
                }
            }
            for (g in groups) {
                val arch = src.index(0).archive(g) ?: continue
                for (f in arch.fileIds()) {
                    val b = src.data(0, g, f) ?: continue
                    put("frames/$g/$f.dat", b)
                    if (b.size >= 2) skels.add(((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255))
                }
                src.index(0).unCache()
            }
            for (s in skels) src.data(1, s, 0)?.let { put("skeletons/$s.dat", it) }
            put("bundle.txt", manifest.toString().toByteArray())
            return "Saved ${seqIds.distinct().size} animation(s), ${groups.size} frame set(s) and ${skels.size} skeleton(s)."
        }
    }

    class ImportResult(val message: String, val newSeqIds: List<Int>)

    /**
     * Adds the animations in a bundle to the exported cache. Frame sets and skeletons that are already there, byte
     * for byte, are reused; the rest get new ids. [replaceSeq] (only for a bundle with one animation) puts it over
     * that animation id instead of adding a new one.
     */
    fun importBundle(zipFile: File, replaceSeq: Int?): ImportResult {
        val seqs = HashMap<Int, ByteArray>(); val frames = HashMap<Int, HashMap<Int, ByteArray>>(); val skels = HashMap<Int, ByteArray>()
        val keyframes = HashMap<Int, HashMap<Int, ByteArray>>()
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                val parts = e.name.trim('/').split('/')
                when {
                    parts.size == 2 && parts[0] == "seq" -> parts[1].removeSuffix(".dat").toIntOrNull()?.let { seqs[it] = bytes }
                    parts.size == 3 && parts[0] == "frames" -> {
                        val g = parts[1].toIntOrNull(); val f = parts[2].removeSuffix(".dat").toIntOrNull()
                        if (g != null && f != null) frames.getOrPut(g) { HashMap() }[f] = bytes
                    }
                    parts.size == 2 && parts[0] == "skeletons" -> parts[1].removeSuffix(".dat").toIntOrNull()?.let { skels[it] = bytes }
                    parts.size == 3 && parts[0] == "keyframes" -> {
                        val g = parts[1].toIntOrNull(); val f = parts[2].removeSuffix(".dat").toIntOrNull()
                        if (g != null && f != null) keyframes.getOrPut(g) { HashMap() }[f] = bytes
                    }
                }
            }
        }
        require(seqs.isNotEmpty()) { "That zip has no animations in it (it should have a seq folder)." }
        require(replaceSeq == null || seqs.size == 1) { "Replacing only works with a bundle of one animation." }
        backup("animations")
        return withExportCache { lib ->
            val notes = ArrayList<String>()
            repairAnimationsIfNeeded(lib).let { if (it.isNotEmpty()) notes.add(it.trim()) }
            // skeletons
            val skelMap = HashMap<Int, Int>()
            var nextSkel = (lib.index(1).archiveIds().max() ?: -1) + 1
            for ((id, b) in skels) {
                val existing = lib.data(1, id, 0)
                if (existing != null && existing.contentEquals(b)) { skelMap[id] = id; continue }
                lib.put(1, nextSkel, 0, b); skelMap[id] = nextSkel; nextSkel++
            }
            // frame sets
            val groupMap = HashMap<Int, Int>()
            var nextGroup = (lib.index(0).archiveIds().max() ?: -1) + 1
            for ((g, files) in frames) {
                val patched = files.mapValues { (_, b) ->
                    val s = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
                    val to = skelMap[s] ?: s
                    if (to == s) b else b.clone().also { c -> c[0] = (to shr 8).toByte(); c[1] = to.toByte() }
                }
                val arch = lib.index(0).archive(g)
                val same = arch != null && arch.fileIds().toSet() == patched.keys && patched.all { (f, b) -> lib.data(0, g, f)?.contentEquals(b) == true }
                if (same) { groupMap[g] = g; continue }
                for ((f, b) in patched) lib.put(0, nextGroup, f, b)
                groupMap[g] = nextGroup; nextGroup++
            }
            // keyframe sets (skeletal animations): one file each; a new group unless it's already here byte for byte
            val keyMap = HashMap<Int, Int>() // old (group << 16 | file) -> new
            for ((g, files) in keyframes) for ((f, b) in files) {
                val s = ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)
                val to = skelMap[s] ?: s
                val patched = if (to == s) b else b.clone().also { c -> c[1] = (to shr 8).toByte(); c[2] = to.toByte() }
                if (lib.data(0, g, f)?.contentEquals(patched) == true) { keyMap[(g shl 16) or f] = (g shl 16) or f; continue }
                lib.put(0, nextGroup, 0, patched); keyMap[(g shl 16) or f] = nextGroup shl 16; nextGroup++
            }
            // sequences
            val added = ArrayList<Int>()
            var nextSeq = (lib.index(2).archive(12)?.fileIds()?.max() ?: -1) + 1
            for ((id, raw) in seqs.toSortedMap()) {
                val seq = SeqDef.decode(id, raw)
                if (!seq.readOk) { notes.add("Animation $id has settings this editor doesn't know; it was copied as it is."); }
                fun remap(f: Int) = ((groupMap[f ushr 16] ?: (f ushr 16)) shl 16) or (f and 0xFFFF)
                val bytes = if (seq.readOk && (seq.frameIds.isNotEmpty() || seq.skeletalModel != -1))
                    seq.encode(seq.frameIds.map(::remap).toIntArray(), seq.delays, seq.chatFrameIds?.map(::remap)?.toIntArray(),
                        keyMap[seq.skeletalModel] ?: seq.skeletalModel) else raw
                val target = replaceSeq ?: nextSeq++
                lib.put(2, 12, target, bytes)
                added.add(target)
            }
            lib.index(0).update(); lib.index(1).update(); lib.index(2).update()
            val newGroups = groupMap.count { it.key != it.value } + keyMap.count { it.key != it.value }
            ImportResult("Imported ${added.size} animation(s) as id ${added.joinToString(", ")}" +
                    (if (newGroups > 0) ", with $newGroups new frame set(s)" else "") + ". " + notes.joinToString(" "), added)
        }
    }

    /** Saves new frames/delays for one animation into the exported cache. */
    fun saveSeq(seq: SeqDef, frames: IntArray, delays: IntArray): ByteArray {
        val bytes = seq.encode(frames, delays)
        val check = SeqDef.decode(seq.id, bytes)
        require(check.readOk && check.frameIds.contentEquals(frames) && check.delays.contentEquals(delays)) { "It didn't read back the same, nothing was written." }
        backup("anim-${seq.id}")
        withExportCache { lib -> repairAnimationsIfNeeded(lib); lib.put(2, 12, seq.id, bytes); lib.index(2).update() }
        return bytes
    }

    fun readAll(input: java.io.InputStream): ByteArray { val o = ByteArrayOutputStream(); input.copyTo(o); return o.toByteArray() }
}
