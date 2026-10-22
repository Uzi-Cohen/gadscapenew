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

    fun lastBackup(): File? = backupRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }

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
     * Hands out new model ids (see [cache.IdKind.MODEL]): in a build 241 cache the highest free id up to 65534, from
     * the top down (configs store 4-byte model ids there, but the 241 client fetches models with 16-bit group ids;
     * the 241 base's own models end near 62050-62775 and the next OSRS caches add theirs right after); in a rev 211
     * cache from where the first run of 50 free ids starts, never above 65534 (2-byte model ids).
     */
    class ModelIds(lib: CacheLibrary, alsoTaken: Collection<Int> = emptyList()) {
        private val ids = cache.IdAllocator(lib, cache.IdKind.MODEL, alsoTaken)
        fun next(): Int = ids.next()
        /**
         * Marks [id] as used: a model written at an id of its own choosing after this was made (e.g. one kept at its
         * live id, see [ItemCopy.copyInto]). The allocator only knows the ids the cache had when it was made.
         */
        fun take(id: Int) = ids.take(id)
    }

    /**
     * Before writing animations: puts a padded animations archive back to its real size (see [BigArchive]), moves
     * frame sets the game can't play to numbers it can (see [fixFrameSetNumbers]), in a build 241 cache copies
     * skeletal keyframe sets the game can't find (only in archive 0) to archive 22 (see [Keyframes.moveStranded]), and
     * gives skeletal animations without a length the one their keyframes hold (see [Keyframes.fixLengths]).
     */
    fun repairAnimationsIfNeeded(lib: CacheLibrary): String {
        var note = ""
        if (BigArchive.animationsNeedRepair(lib)) {
            val n = BigArchive.repairAnimations(lib) ?: error("the animations in this cache can't be read, nothing was written")
            note = "Took the padding off $n animation(s) so the editor can save them (the game reads them the same). "
        }
        return note + fixFrameSetNumbers(lib) + Keyframes.moveStranded(lib) + Keyframes.fixLengths(lib)
    }

    // ---------------- frame sets (index 0) ----------------

    /**
     * The highest frame set the game can play. It keeps a frame as (set << 16) | frame in an int and gets the set back
     * with a signed shift, so set 32768 and up comes out negative: the client crashes as soon as such an animation plays.
     */
    const val MAX_FRAME_SET = 32767

    /**
     * Hands out new frame set numbers (archive 0 groups, see [cache.IdKind.FRAME_SET]): from GadScape's reserved range
     * 30000-32767, never above [MAX_FRAME_SET], never a number the cache already uses. (The first version took "the
     * first run of 50 free numbers" of the 211 cache, 4478 and up, which build 241's own frame sets use: CRIT-18.)
     */
    class FrameSetIds(lib: CacheLibrary) {
        private val ids = cache.IdAllocator(lib, cache.IdKind.FRAME_SET)
        fun next(): Int = ids.next().also { check(it <= MAX_FRAME_SET) }
        /** Marks [id] as used (one kept from the source, see [importBundle]'s keepIds). */
        fun take(id: Int) = ids.take(id)
        /** True if [id] is neither in the cache nor handed out / taken here. */
        fun isFree(id: Int) = ids.isFree(id)
    }

    /**
     * Hands out new skeletal keyframe set numbers (see [cache.IdKind.KEYFRAME_SET]): archive 22 groups in a build 241
     * cache, archive 0 groups in a rev 211 cache, from GadScape's reserved range 20000-29999.
     */
    class KeyframeSetIds(lib: CacheLibrary) {
        private val ids = cache.IdAllocator(lib, cache.IdKind.KEYFRAME_SET)
        fun next(): Int = ids.next().also { check(it <= MAX_FRAME_SET) }
        /** Marks [id] as used (one kept from the source, see [importBundle]'s keepIds). */
        fun take(id: Int) = ids.take(id)
        /** True if [id] is neither in the cache nor handed out / taken here. */
        fun isFree(id: Int) = ids.isFree(id)
    }

    /**
     * The groups in GadScape's reserved [range] of one kind ([cache.IdKind]) in [archive] of [lib], by content: an
     * import that would make a new copy of a skeleton, frame set or keyframe set first looks here for the copy an
     * earlier import made of the same bytes, and uses that one (see [importInto]). Without it, every import of the
     * same content made another copy, and the frame set and skeleton ranges hold only 2768 ids each.
     *
     * Only groups with the same file ids are read (the reference table lists them), each once per import, and the
     * ones read here are let go again; groups this import plans to write ([add]) count too.
     */
    private class ReservedCopies(private val lib: CacheLibrary, private val archive: Int, private val range: IntRange) {
        private val hashes = HashMap<Int, Int>()
        private val planned = LinkedHashMap<Int, Map<Int, ByteArray>>()

        private fun hash(files: Map<Int, ByteArray>): Int =
            files.entries.sortedBy { it.key }.fold(files.size) { h, (f, b) -> 31 * (31 * h + f) + b.contentHashCode() }

        private fun same(a: Map<Int, ByteArray>, b: Map<Int, ByteArray>) = a.keys == b.keys && a.all { (f, d) -> b[f]?.contentEquals(d) == true }

        /** The files of group [g], read without keeping them in memory (unless they were loaded before). */
        private fun read(g: Int): Map<Int, ByteArray>? {
            val index = lib.index(archive)
            val a = index.archive(g, direct = true) ?: return null
            val loaded = a.read || a.new || a.flagged()
            return try {
                val out = HashMap<Int, ByteArray>()
                for (f in a.fileIds()) out[f] = lib.data(archive, g, f) ?: return null
                out
            } catch (e: Exception) { null } finally { if (!loaded) a.restore() }
        }

        /** A group in the range (or planned by this import) holding exactly [files], or null. */
        fun find(files: Map<Int, ByteArray>): Int? {
            if (files.isEmpty()) return null
            planned.entries.firstOrNull { same(it.value, files) }?.let { return it.key }
            if (!lib.exists(archive)) return null
            val index = lib.index(archive)
            val want = hash(files)
            for (g in index.archiveIds().sorted()) {
                if (g !in range || g in planned) continue
                val a = index.archive(g, direct = true) ?: continue
                if (a.fileIds().size != files.size || !a.fileIds().all { it in files }) continue
                val h = hashes.getOrPut(g) { read(g)?.let { hash(it) } ?: 0 }
                if (h == want && read(g)?.let { same(it, files) } == true) return g
            }
            return null
        }

        /** A group this import will write at [g]. */
        fun add(g: Int, files: Map<Int, ByteArray>) { planned[g] = files }
    }

    /** True if the cache has frame sets numbered above [MAX_FRAME_SET] (left by imports before this was fixed). */
    fun hasUnplayableFrameSets(lib: CacheLibrary): Boolean = lib.index(0).archiveIds().any { it > MAX_FRAME_SET }

    /**
     * Moves frame sets numbered above [MAX_FRAME_SET] to free numbers the game can play, and points every animation
     * that uses them at the new numbers. Returns a note, or "" if there was nothing to move.
     */
    fun fixFrameSetNumbers(lib: CacheLibrary): String {
        val high = lib.index(0).archiveIds().filter { it > MAX_FRAME_SET }.sorted()
        if (high.isEmpty()) return ""
        // skeletal keyframe sets are archive 0 groups only in a 211 cache (archive 22 in 241)
        val keyframesInFrames = cache.CacheIO.keyframeArchive(lib) == cache.CacheIO.FRAMES
        fun setsOf(s: SeqDef) = s.frameIds.map { it ushr 16 } + (s.chatFrameIds?.map { it ushr 16 } ?: emptyList()) +
                (if (s.skeletalModel != -1 && keyframesInFrames) listOf(s.skeletalModel ushr 16) else emptyList())
        // the animations that use them; a set used by one this editor can't rewrite stays where it is
        val users = ArrayList<SeqDef>(); val stuck = HashSet<Int>()
        val rev = cache.CacheIO.revision(lib)
        for (id in lib.index(2).archive(12)?.fileIds() ?: IntArray(0)) {
            val raw = lib.data(2, 12, id) ?: continue
            val s = SeqDef.decode(id, raw, rev)
            val sets = setsOf(s).filter { it > MAX_FRAME_SET }
            if (sets.isEmpty()) continue
            if (s.readOk) users.add(s) else stuck.addAll(sets)
        }
        val ids = FrameSetIds(lib)
        val moved = HashMap<Int, Int>()
        for (g in high) {
            if (g in stuck) continue
            val fileIds = lib.index(0).archive(g)?.fileIds() ?: continue
            val files = fileIds.associateWith { f -> lib.data(0, g, f) }
            if (files.values.any { it == null }) { stuck.add(g); continue }
            val to = ids.next()
            for ((f, b) in files) lib.put(0, to, f, b!!)
            moved[g] = to
        }
        fun remap(f: Int) = moved[f ushr 16]?.let { (it shl 16) or (f and 0xFFFF) } ?: f
        var rewritten = 0
        for (s in users) {
            if (setsOf(s).none { it in moved }) continue
            lib.put(2, 12, s.id, s.encode(s.frameIds.map(::remap).toIntArray(), s.delays,
                s.chatFrameIds?.map(::remap)?.toIntArray(), if (s.skeletalModel == -1 || !keyframesInFrames) s.skeletalModel else remap(s.skeletalModel)))
            rewritten++
        }
        for (g in moved.keys) lib.remove(0, g)
        lib.index(0).update(); lib.index(2).update()
        return "Moved ${moved.size} frame set(s) the game couldn't play (numbered above $MAX_FRAME_SET; they crashed the client) " +
                "to ${moved.values.sorted().joinToString(", ")}, and updated the $rewritten animation(s) using them. " +
                (if (stuck.isNotEmpty()) "Left ${stuck.sorted().joinToString(", ")} where they are: an animation using them couldn't be read. " else "")
    }

    /**
     * Before the exported cache goes to the server: fixes frame sets the game can't play (see [fixFrameSetNumbers]),
     * skeletal keyframe sets it can't find (see [Keyframes.moveStranded]) and skeletal animations without a length
     * (see [Keyframes.fixLengths]; the ones it can't give a length are listed, they don't stop the deploy), with a
     * backup first. Returns false (and logs why) if they're there and couldn't be fixed.
     */
    fun fixBeforeShipping(log: (String) -> Unit): Boolean = try {
        // no exported cache yet: nothing to fix (and making one here would hide "export first")
        val exported = File(GadTools.exportCacheDir, "main_file_cache.dat2").isFile
        if (exported && withExportCache { hasUnplayableFrameSets(it) }) {
            backup("frame-sets")
            log(withExportCache { fixFrameSetNumbers(it) })
        }
        if (exported && withExportCache { Keyframes.stranded(it).isNotEmpty() }) {
            backup("keyframe-sets")
            log(withExportCache { Keyframes.moveStranded(it) })
        }
        // only after the keyframe sets are where the game looks: a length alone doesn't make an animation play
        val lengthless = if (exported) withExportCache { Keyframes.lengthless(it) } else emptyMap()
        if (lengthless.values.any { it != null }) backup("animation-lengths")
        if (lengthless.isNotEmpty()) log(withExportCache { Keyframes.fixLengths(it) }.trim())
        true
    } catch (e: Exception) {
        log("Stopping: the cache has animation frame sets the game can't play (or keyframe sets it can't find, or skeletal " +
                "animations without a length) and they couldn't be fixed (${e.message}). Nothing was sent.")
        false
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

    /** The line in a bundle's bundle.txt that says which layout its sequences use ("format 241"; none = 211). */
    private const val FORMAT_LINE = "format "

    /**
     * Stops an import of animations in a newer layout than build 241, the newest this editor reads and GadScape's game
     * runs (a live bundle names the live game's build, see [LiveAnims.bundle]): they'd be copied as if they were 241.
     */
    fun requireKnownFormat(from: Int) = require(from <= ConfigRev.R241) {
        "These animations are in build $from's layout, newer than build ${ConfigRev.R241} (the newest this editor reads and " +
                "GadScape's game runs), so they can't be imported. Nothing was written."
    }

    /** The sequence layout of a bundle (see [exportBundle]) from its bundle.txt; old bundles have none and are 211. */
    fun bundleFormat(manifest: String?): Int =
        manifest?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith(FORMAT_LINE) }
            ?.removePrefix(FORMAT_LINE)?.trim()?.toIntOrNull() ?: ConfigRev.R211

    /**
     * An animation with everything it needs, as a zip: the sequence (in [src]'s own layout, which bundle.txt names:
     * "format 241" or "format 211"), its frame files, keyframe sets and skeletons.
     * Import it into another cache (or this one, of either format) with [importBundle].
     */
    fun exportBundle(src: CacheLibrary, seqIds: List<Int>, out: File): String {
        val anims = AnimCache(src)
        ZipOutputStream(out.outputStream()).use { zip ->
            fun put(name: String, b: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(b); zip.closeEntry() }
            val groups = HashSet<Int>(); val skels = HashSet<Int>()
            val manifest = StringBuilder("# Uzi's Map Editor animation bundle\n").append(FORMAT_LINE).append(anims.rev).append('\n')
            var missingKeys = 0
            for (id in seqIds.distinct()) {
                val raw = anims.seqData(id) ?: continue
                val seq = SeqDef.decode(id, raw, anims.rev)
                put("seq/$id.dat", raw)
                manifest.append("seq ").append(id).append('\n')
                (seq.frameIds.toList() + (seq.chatFrameIds?.toList() ?: emptyList())).forEach { groups.add(it ushr 16) }
                // a skeletal animation's keyframe set (archive 22 in 241, archive 0 in 211): skeleton id after a version byte
                if (seq.skeletalModel != -1) {
                    val g = seq.skeletalModel ushr 16; val f = seq.skeletalModel and 0xFFFF
                    val b = anims.keyframeData(g, f)
                    if (b != null && b.size >= 3) { put("keyframes/$g/$f.dat", b); Keyframes.skeletonOf(b)?.let { skels.add(it) } } else missingKeys++
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
            return "Saved ${seqIds.distinct().size} animation(s), ${groups.size} frame set(s) and ${skels.size} skeleton(s)." +
                    (if (missingKeys > 0) " $missingKeys skeletal animation(s) had no keyframes in the cache; they won't play where they're imported." else "")
        }
    }

    class ImportResult(val message: String, val newSeqIds: List<Int>)

    /** One file [importInto] writes, once it has planned them all. */
    private class Put(val archive: Int, val group: Int, val file: Int, val data: ByteArray)

    /**
     * Adds the animations in a bundle to the exported cache (after a backup), see [importInto]. [replaceSeq] (only for
     * a bundle with one animation) puts it over that animation id instead of adding a new one. [keepIds]: the bundle
     * is live / stock OSRS content, which keeps its own ids where they are free.
     */
    fun importBundle(zipFile: File, replaceSeq: Int?, keepIds: Boolean = false): ImportResult {
        val seqs = HashMap<Int, ByteArray>(); val frames = HashMap<Int, HashMap<Int, ByteArray>>(); val skels = HashMap<Int, ByteArray>()
        val keyframes = HashMap<Int, HashMap<Int, ByteArray>>()
        var manifest: String? = null
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                val parts = e.name.trim('/').split('/')
                when {
                    parts.size == 1 && parts[0] == "bundle.txt" -> manifest = String(bytes, Charsets.UTF_8)
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
        val from = bundleFormat(manifest)
        requireKnownFormat(from)
        backup("animations")
        return withExportCache { lib -> importInto(lib, seqs, frames, skels, keyframes, from, replaceSeq, keepIds) }
    }

    /**
     * Adds animations ([seqs], in layout [from]) with their frame sets, skeletons and keyframe sets to [lib]. Nothing
     * that is in [lib] is written over (except the animation [replaceSeq], when given): frame sets, keyframe sets,
     * skeletons and animations that are already there byte for byte are reused, the rest get ids of their own.
     * Sequences are converted to [lib]'s layout when they are in the other one (see [SeqConv]), and keyframe sets go
     * where [lib] keeps them (archive 22 in build 241, archive 0 in rev 211).
     *
     * Ids follow the converter's policy (plan/gadconv-spec.md): with [keepIds] (live or stock OSRS content) each piece
     * keeps its own id where that id is free in [lib] (frame sets and keyframe sets up to [MAX_FRAME_SET]; keyframe
     * sets only in a build 241 cache, where they have their own archive); everything else, and everything from
     * another server's cache ([keepIds] false), gets ids from GadScape's reserved ranges ([cache.IdKind]: animations
     * 40000+, frame sets 30000+, keyframe sets 20000+, skeletons 30000+). A base game frame set or keyframe set is
     * never replaced. Before a new copy is made in a reserved range, the copy an earlier import made there of the
     * same bytes is looked for and used ([ReservedCopies]); a skeleton reused that way makes the frame sets patched
     * to it match their earlier copies too. With [keepIds], an animation whose own id holds another one here also
     * reuses an earlier import's copy of it (the same bytes in the animation range).
     *
     * A sequence this editor can't read (a setting newer than its layout) is copied as it is, so it keeps the frame
     * and keyframe set numbers it had: the import stops, with nothing of the bundle written, when any of the bundle's
     * frame or keyframe sets would get a new number here.
     */
    fun importInto(lib: CacheLibrary, seqs: Map<Int, ByteArray>, frames: Map<Int, Map<Int, ByteArray>>, skels: Map<Int, ByteArray>,
                   keyframes: Map<Int, Map<Int, ByteArray>>, from: Int, replaceSeq: Int?, keepIds: Boolean): ImportResult {
        requireKnownFormat(from)
        val notes = ArrayList<String>()
        val rev = cache.CacheIO.revision(lib)
        val keyArchive = Keyframes.archive(lib)
        require(lib.exists(keyArchive)) { "The exported cache has no keyframe archive ($keyArchive), nothing was written." }
        // every sequence in this cache's layout first, so a bad one stops the import before anything is written
        val native = java.util.TreeMap<Int, ByteArray>()
        val lost = java.util.TreeSet<String>()
        for ((id, raw) in seqs) {
            val r = try { SeqConv.convert(id, raw, from, rev) } catch (e: Exception) {
                error("Animation $id couldn't be converted from the bundle's ${ConfigRev.name(from)} layout to this cache's " +
                        "${ConfigRev.name(rev)} one (${e.message}); nothing was written.")
            }
            native[id] = r.bytes; lost.addAll(r.dropped)
        }
        if (from != rev) notes.add("Converted the animation(s) from ${ConfigRev.name(from)} to ${ConfigRev.name(rev)}" +
                (if (lost.isNotEmpty()) " (left out: ${lost.joinToString(", ")})" else "") + ".")
        val repaired = repairAnimationsIfNeeded(lib).trim()
        if (repaired.isNotEmpty()) notes.add(repaired)
        // the bundle's frame sets, keyframe sets and skeletons are planned first and written only once nothing stops
        // the import (an animation that can't be read, below); a number handed out here counts as taken from then on
        val puts = ArrayList<Put>()
        // skeletons
        val skelMap = HashMap<Int, Int>()
        // new ones from GadScape's reserved skeleton range (cache.IdKind.FRAMEMAP), never the base game's ids
        val skelIds = cache.IdAllocator(lib, cache.IdKind.FRAMEMAP)
        // the copies earlier imports made in the reserved ranges, reused before a new one is made
        val skelCopies = ReservedCopies(lib, cache.CacheIO.FRAMEMAPS, cache.IdKind.FRAMEMAP.rangeFor(lib))
        var kept = 0; var reusedCopies = 0; var newSets = 0
        for ((id, b) in skels) {
            val existing = lib.data(1, id, 0)
            if (existing != null && existing.contentEquals(b)) { skelMap[id] = id; continue }
            // free: not in the cache, and not given to another skeleton of this bundle
            val keep = keepIds && id <= 0xFFFF && skelIds.isFree(id)
            val copy = if (keep) null else skelCopies.find(mapOf(0 to b))
            if (copy != null) { skelMap[id] = copy; reusedCopies++; continue }
            val to = if (keep) id.also { skelIds.take(it); kept++ } else skelIds.next()
            puts.add(Put(1, to, 0, b)); skelMap[id] = to; skelCopies.add(to, mapOf(0 to b))
        }
        // frame sets
        val groupMap = HashMap<Int, Int>()
        val setIds = FrameSetIds(lib)
        val frameCopies = ReservedCopies(lib, cache.CacheIO.FRAMES, cache.IdKind.FRAME_SET.rangeFor(lib))
        for ((g, files) in frames) {
            val patched = files.mapValues { (_, b) ->
                val s = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
                val to = skelMap[s] ?: s
                if (to == s) b else b.clone().also { c -> c[0] = (to shr 8).toByte(); c[1] = to.toByte() }
            }
            val arch = lib.index(0).archive(g)
            val same = arch != null && arch.fileIds().toSet() == patched.keys && patched.all { (f, b) -> lib.data(0, g, f)?.contentEquals(b) == true }
            if (same) { groupMap[g] = g; continue }
            // free: not in the cache, and not given to another frame set of this bundle
            val keep = keepIds && g <= MAX_FRAME_SET && setIds.isFree(g)
            // an earlier import's copy (its skeleton reused above, so the patched frames match byte for byte)
            val copy = if (keep) null else frameCopies.find(patched)
            if (copy != null) { groupMap[g] = copy; reusedCopies++; continue }
            val to = if (keep) g.also { setIds.take(it); kept++ } else setIds.next().also { newSets++ }
            for ((f, b) in patched) puts.add(Put(0, to, f, b))
            groupMap[g] = to; frameCopies.add(to, patched)
        }
        // keyframe sets (skeletal animations): one file each, in archive 22 (241) or 0 (211); a new group unless
        // it's already there byte for byte
        val keyMap = HashMap<Int, Int>() // old (group << 16 | file) -> new
        val keyIds = KeyframeSetIds(lib)
        val keyCopies = ReservedCopies(lib, keyArchive, cache.IdKind.KEYFRAME_SET.rangeFor(lib))
        // in a 211 cache keyframe sets are archive 0 groups too: the frame sets planned above are taken
        if (keyArchive == cache.CacheIO.FRAMES) groupMap.values.forEach { keyIds.take(it) }
        // keyframe sets kept under their own ids (a group may hold more than one file)
        val keptKeyGroups = HashSet<Int>()
        for ((g, files) in keyframes) for ((f, b) in files) {
            val s = Keyframes.skeletonOf(b) ?: continue
            val to = skelMap[s] ?: s
            val patched = if (to == s) b else b.clone().also { c -> c[1] = (to shr 8).toByte(); c[2] = to.toByte() }
            if (Keyframes.has(lib, keyArchive, g, f) && lib.data(keyArchive, g, f)?.contentEquals(patched) == true) {
                keyMap[(g shl 16) or f] = (g shl 16) or f; continue
            }
            // in a 211 cache keyframe sets share archive 0 with the frame sets, so only 241 keeps their ids
            val keep = keepIds && keyArchive == cache.CacheIO.KEYFRAMES && g <= MAX_FRAME_SET &&
                    (g in keptKeyGroups || keyIds.isFree(g))
            if (keep) {
                if (keptKeyGroups.add(g)) { keyIds.take(g); kept++ }
                puts.add(Put(keyArchive, g, f, patched)); keyMap[(g shl 16) or f] = (g shl 16) or f; continue
            }
            // an earlier import's copy of the same keyframes (new copies are always file 0 of a group of their own)
            val copy = keyCopies.find(mapOf(0 to patched))
            if (copy != null) { keyMap[(g shl 16) or f] = copy shl 16; reusedCopies++; continue }
            val set = keyIds.next(); newSets++
            puts.add(Put(keyArchive, set, 0, patched)); keyMap[(g shl 16) or f] = set shl 16; keyCopies.add(set, mapOf(0 to patched))
        }
        // A sequence this editor can't read can only be copied as it is, with the frame set and keyframe set numbers
        // it had in the bundle: if any of those move here, it plays other frames (often the game's own) or nothing.
        // Which sets it uses can't be read either, so any set of the bundle that moves stops it, before anything of
        // the bundle is written. (A bundle in the other layout never gets here: SeqConv refuses what it can't read.)
        val unreadable = native.filter { (id, b) -> !SeqDef.decode(id, b, rev).readOk }.keys
        if (unreadable.isNotEmpty()) {
            val movedSets = groupMap.filter { it.key != it.value }.keys.sorted()
            val movedKeys = keyMap.filter { it.key != it.value }.keys.sorted().map { "${it ushr 16}:${it and 0xFFFF}" }
            if (movedSets.isNotEmpty() || movedKeys.isNotEmpty()) error(
                "Animation(s) ${unreadable.joinToString(", ")} have settings this editor can't read, so they can only be copied " +
                        "as they are, with the frame and keyframe set numbers they had. Here " +
                        listOfNotNull(movedSets.takeIf { it.isNotEmpty() }?.let { "frame set(s) ${Keyframes.idList(it)}" },
                            movedKeys.takeIf { it.isNotEmpty() }?.let { "keyframe set(s) ${it.take(12).joinToString(", ")}" +
                                    (if (it.size > 12) " and ${it.size - 12} more" else "") }).joinToString(" and ") +
                        " would need new numbers (they're taken, or new content gets GadScape's own numbers), so they'd play " +
                        "the wrong frames. Nothing of the bundle was written." +
                        (if (repaired.isNotEmpty()) " (This cache's own repairs were made first: $repaired)" else ""))
        }
        for (p in puts) lib.put(p.archive, p.group, p.file, p.data)
        // sequences
        val added = ArrayList<Int>()
        // new animations from GadScape's reserved range (cache.IdKind.SEQ, 40000-49999)
        val seqIds = cache.IdAllocator(lib, cache.IdKind.SEQ)
        val reused = ArrayList<Int>()
        // live / stock content whose own id holds another animation here: the copy an earlier import made of it (the
        // same bytes in GadScape's animation range), so importing the same item again doesn't use up another id
        val seqRange = cache.IdKind.SEQ.rangeFor(lib)
        val reusedCopy = ArrayList<String>()
        fun earlierCopy(b: ByteArray): Int? = lib.index(2).archive(12)?.fileIds()?.sorted()
            ?.firstOrNull { it in seqRange && lib.data(2, 12, it)?.contentEquals(b) == true }
        for ((id, raw) in native) {
            val seq = SeqDef.decode(id, raw, rev)
            if (!seq.readOk) notes.add("Animation $id has settings this editor doesn't know; it was copied as it is (its frame " +
                    "and keyframe sets kept their numbers).")
            fun remap(f: Int) = ((groupMap[f ushr 16] ?: (f ushr 16)) shl 16) or (f and 0xFFFF)
            val bytes = if (seq.readOk && (seq.frameIds.isNotEmpty() || seq.chatFrameIds != null || seq.skeletalModel != -1))
                seq.encode(seq.frameIds.map(::remap).toIntArray(), seq.delays, seq.chatFrameIds?.map(::remap)?.toIntArray(),
                    keyMap[seq.skeletalModel] ?: seq.skeletalModel) else raw
            if (seq.skeletalModel != -1 && seq.skeletalModel !in keyMap && !Keyframes.has(lib, keyArchive, seq.skeletalModel ushr 16, seq.skeletalModel and 0xFFFF))
                notes.add("Animation $id is skeletal but its keyframes weren't in the bundle or the cache; it won't play.")
            if (replaceSeq == null && keepIds) {
                // live / stock content: the same animation at its own id is reused, a free id is kept
                val here = lib.data(2, 12, id)
                if (here != null && here.contentEquals(bytes)) { reused.add(id); added.add(id); continue }
                if (here == null && (ConfigRev.is241(rev) || id <= 0xFFFF)) {
                    seqIds.take(id); lib.put(2, 12, id, bytes); added.add(id); continue
                }
                val copy = earlierCopy(bytes)
                if (copy != null) { reusedCopy.add("$id = $copy"); added.add(copy); continue }
            }
            val target = replaceSeq ?: seqIds.next()
            lib.put(2, 12, target, bytes)
            added.add(target)
        }
        lib.index(0).update(); lib.index(1).update(); lib.index(2).update()
        if (keyArchive != 0) lib.index(keyArchive).update()
        // imported skeletal animations without a length get the one their keyframes hold (the cache's own ones got it above)
        Keyframes.fixLengths(lib, added).trim().let { if (it.isNotEmpty()) notes.add(it) }
        if (reused.isNotEmpty()) notes.add("Already here (the same animation under its own id, so nothing was written): ${reused.joinToString(", ")}.")
        if (reusedCopy.isNotEmpty()) notes.add("Already here as an earlier import's copy (nothing was written): ${reusedCopy.joinToString(", ")}.")
        if (kept > 0) notes.add("$kept frame set(s), keyframe set(s) and skeleton(s) kept their own ids.")
        if (reusedCopies > 0) notes.add("$reusedCopies frame set(s), keyframe set(s) and skeleton(s) an earlier import copied here were used again.")
        return ImportResult("Imported ${added.size} animation(s) as id ${added.joinToString(", ")}" +
                (if (newSets > 0) ", with $newSets new frame set(s)" else "") + ". " + notes.joinToString(" "), added)
    }

    /**
     * Saves new frames/delays for one animation into the exported cache, in that cache's layout (converted when [seq]
     * was read from a cache of the other format). Returns the bytes written.
     */
    fun saveSeq(seq: SeqDef, frames: IntArray, delays: IntArray): ByteArray {
        val bytes = seq.encode(frames, delays)
        val check = SeqDef.decode(seq.id, bytes, seq.rev)
        require(check.readOk && check.frameIds.contentEquals(frames) && check.delays.contentEquals(delays)) { "It didn't read back the same, nothing was written." }
        backup("anim-${seq.id}")
        return withExportCache { lib ->
            val rev = cache.CacheIO.revision(lib)
            val out = SeqConv.convert(seq.id, bytes, seq.rev, rev).bytes
            val again = SeqDef.decode(seq.id, out, rev)
            require(again.readOk && again.frameIds.contentEquals(frames) && again.delays.contentEquals(delays)) { "It didn't convert to this cache's format, nothing was written." }
            repairAnimationsIfNeeded(lib); lib.put(2, 12, seq.id, out); lib.index(2).update()
            out
        }
    }

    fun readAll(input: java.io.InputStream): ByteArray { val o = ByteArrayOutputStream(); input.copyTo(o); return o.toByteArray() }
}
