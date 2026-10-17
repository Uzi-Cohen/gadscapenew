package utils

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** One NPC standing on the map, and where it came from in the server's spawn files. */
class NpcSpawn(var id: Int, var x: Int, var y: Int, var z: Int, var direction: String, var radius: Int) {
    /** File it lives in (null = not written anywhere yet). */
    var file: File? = null
    /** Index of its line in that file when it was read, -1 for new spawns. */
    var line = -1
    /** Anything after the numbers on its line that we don't understand, kept as it was. */
    var extra: String = ""

    data class State(val id: Int, val x: Int, val y: Int, val z: Int, val direction: String, val radius: Int)
    fun state() = State(id, x, y, z, direction, radius)
    fun set(s: State) { id = s.id; x = s.x; y = s.y; z = s.z; direction = s.direction; radius = s.radius }
    val regionId: Int get() = ((x shr 6) shl 8) or (y shr 6)
    override fun toString() = "$id at $x, $y, $z ($direction, walks $radius)"
}

/** Every spawn the editor knows about, plus what changed since they were read. */
object NpcSpawns {
    val all = CopyOnWriteArrayList<NpcSpawn>()
    /** Spawns that were in a file and got deleted (their lines are removed on save). */
    private val deleted = ArrayList<NpcSpawn>()
    private val changedFiles = HashSet<File>()
    @Volatile var dirty = false
        private set
    @Volatile var version = 0
        private set
    /** What happened when the spawn files were read, shown in the NPC panel. */
    @Volatile var info = "Spawn files not read yet."
    var format: SpawnFormat? = null
    var serverDir: File? = null

    private fun touched(s: NpcSpawn) {
        dirty = true; version++
        s.file?.let { changedFiles.add(it) }
    }

    /** Spawns on a tile (big NPCs cover more than one tile; the one whose corner is closest wins). */
    fun at(x: Int, y: Int, z: Int, size: (Int) -> Int): NpcSpawn? =
        all.filter { it.z == z && x >= it.x && y >= it.y && x < it.x + size(it.id) && y < it.y + size(it.id) }
            .minBy { (x - it.x) + (y - it.y) }

    fun add(s: NpcSpawn) { if (s.file == null) s.file = format?.fileFor(s, serverDir); all.add(s); deleted.remove(s); touched(s) }
    fun remove(s: NpcSpawn) {
        all.remove(s)
        // its line is dropped on save; the spawn itself counts as new if undo brings it back
        if (s.line >= 0) { deleted.add(NpcSpawn(s.id, s.x, s.y, s.z, s.direction, s.radius).also { it.file = s.file; it.line = s.line }); s.line = -1 }
        touched(s)
    }

    /** Undo / redo / edits all come through here: null state = the spawn isn't there. */
    fun apply(s: NpcSpawn, st: NpcSpawn.State?) {
        if (st == null) { if (all.contains(s)) remove(s) ; return }
        val oldFile = s.file
        s.set(st)
        if (!all.contains(s)) { all.add(s); deleted.remove(s) }
        // moved into another region: its line moves to that region's file
        val newFile = format?.fileFor(s, serverDir)
        if (newFile != null && oldFile != null && newFile != oldFile && format?.perRegion == true) {
            if (s.line >= 0) { deleted.add(NpcSpawn(s.id, s.x, s.y, s.z, s.direction, s.radius).also { it.file = oldFile; it.line = s.line }) }
            changedFiles.add(oldFile)
            s.file = newFile; s.line = -1
        }
        touched(s)
    }

    fun load(dir: File, fmt: SpawnFormat): String {
        all.clear(); deleted.clear(); changedFiles.clear(); dirty = false
        serverDir = dir; format = fmt
        var files = 0
        for (f in fmt.files(dir)) {
            val list = try { fmt.read(f) } catch (e: Exception) { println("Spawns: couldn't read ${f.path}: $e"); continue }
            list.forEach { it.file = f }
            all.addAll(list); files++
        }
        version++
        info = "${all.size} NPC spawns from $files files (${fmt.name})."
        if (fmt is KotlinSpawnFormat) {
            val skipped = fmt.unknownNames.values.sum()
            if (skipped > 0) info += " $skipped lines skipped, names not in NpcId: " +
                    fmt.unknownNames.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key} (${it.value})" }
            if (fmt.oddLines.isNotEmpty()) info += "\nLines in a shape the editor doesn't read yet:\n" + fmt.oddLines.joinToString("\n")
            println(info)
        }
        return info
    }

    /** Write every changed file. Unchanged lines stay exactly as they were. */
    fun save(): String {
        val fmt = format ?: return "No spawn files loaded."
        if (!dirty) return "No NPC changes to save."
        val files = HashSet(changedFiles)
        all.filter { it.line < 0 }.mapNotNullTo(files) { it.file }
        var n = 0
        val summary = ArrayList<String>()
        for (f in files) {
            val added = all.count { it.file == f && it.line < 0 }
            val removed = deleted.count { it.file == f }
            summary.add(f.name + (if (added > 0) " +$added" else "") + (if (removed > 0) " -$removed" else ""))
            val keep = all.filter { it.file == f }
            val gone = deleted.filter { it.file == f }.map { it.line }.toSet()
            fmt.write(f, keep, gone)
            // deployed like edited shops: the server compiles these source files and swaps just their classes
            Deployer.markShopEdited(f.absolutePath)
            n++
        }
        // the format updated every spawn's line number; what was deleted is gone from the files now
        deleted.removeAll { it.file in files }
        changedFiles.clear()
        dirty = false; version++
        return "Saved NPC spawns ($n file${if (n == 1) "" else "s"}: ${summary.joinToString(", ")})."
    }
}

/** How a server keeps its NPC spawns. */
interface SpawnFormat {
    val name: String
    /** True when each region has its own file (moving an NPC across a border moves its line). */
    val perRegion: Boolean
    fun files(serverDir: File): List<File>
    fun read(file: File): List<NpcSpawn>
    /** Rewrite [file]: lines in [removedLines] go, spawns read from it are updated in place, new ones are added. */
    fun write(file: File, spawns: List<NpcSpawn>, removedLines: Set<Int>)
    /** File a new spawn (or one moved into another region) belongs in. */
    fun fileFor(s: NpcSpawn, serverDir: File?): File?
}
