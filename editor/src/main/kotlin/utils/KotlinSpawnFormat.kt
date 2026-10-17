package utils

import java.io.File

/**
 * Spawns written as Kotlin lines in per-region files (plugins/spawns/regionNNxxx/.../RegionNNNNNSpawns.kt):
 *     NPC_NAME(x, y, z, DIRECTION, radius)
 * NPC names come from the server's NpcId constants. Lines the editor doesn't touch are written back as they were.
 */
class KotlinSpawnFormat(private val serverDir: File) : SpawnFormat {
    override val name = "Kotlin spawn files"
    override val perRegion = true

    /** NpcId constant name -> id, and the name to use for an id when writing a new line. */
    val idOf = HashMap<String, Int>()
    private val nameOf = HashMap<Int, String>()
    var constantsFile: File? = null
        private set
    /** Package of the NpcId the spawn files import. */
    var npcIdPackage: String? = null
        private set

    companion object {
        private val LINE = Regex("""^(\s*)(?:NpcId\.)?([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*(\d+)\s*)?(?:,\s*(?:Direction\.)?([A-Z][A-Z_]*)\s*)?(?:,\s*(\d+)\s*)?((?:,[^)]*)?)\)(.*)$""")
        private val CONST = Regex("""(?:const\s+val|static\s+final\s+int|val)\s+([A-Z][A-Z0-9_]*)\s*(?::\s*Int\s*)?=\s*(\d+)""")
        val DIRS = setOf("SOUTH", "SOUTH_WEST", "WEST", "NORTH_WEST", "NORTH", "NORTH_EAST", "EAST", "SOUTH_EAST")
        private fun skip(f: File) = f.invariantSeparatorsPath.let { it.contains("/build/") || it.contains("/.gradle/") || it.contains("/excluded/") || it.contains("/.git/") || it.contains("/node_modules/") }
        /** A line that calls something with x, y numbers: a spawn we may not have understood. */
        private val LOOKS_LIKE = Regex("""^\s*[\w.]+\s*\(\s*\d{3,5}\s*,\s*\d{3,5}""")
    }

    /** Spawn-looking lines that weren't read: name not in NpcId (count per name), or a shape we don't know (a few examples). */
    val unknownNames = HashMap<String, Int>()
    val oddLines = ArrayList<String>()

    private fun loadIds() {
        // every NpcId file (the server can have more than one, e.g. core and a custom one)
        val all = serverDir.walkTopDown().onEnter { !skip(it) }.filter { it.isFile && (it.name == "NpcId.java" || it.name == "NpcId.kt") }.toList()
        if (all.isEmpty()) return
        // use the NpcId the spawn files actually import: other copies (older revisions, tools) give the same
        // name a different id, which put the wrong NPC in game
        fun pkg(f: File) = f.useLines { ls -> ls.mapNotNull { Regex("""^\s*package\s+([\w.]+)""").find(it)?.groupValues?.get(1) }.firstOrNull() } ?: ""
        val imported = files(serverDir).take(400).flatMap { f ->
            f.useLines { ls -> ls.mapNotNull { Regex("""^\s*import\s+([\w.]+)\.NpcId(?:\.\*|\.\w+)?\s*$""").find(it)?.groupValues?.get(1) }.toList() }
        }.groupingBy { it }.eachCount()
        val wanted = imported.maxBy { it.value }?.key
        val found = all.filter { wanted != null && pkg(it) == wanted }.ifEmpty { all }
        constantsFile = found.first()
        npcIdPackage = wanted
        for (f in found) f.forEachLine { l ->
            CONST.find(l)?.let { m ->
                val id = m.groupValues[2].toInt()
                if (m.groupValues[1] in idOf) return@let
                idOf[m.groupValues[1]] = id
                // prefer the plain name over NAME_1234 style duplicates
                val n = m.groupValues[1]
                val cur = nameOf[id]
                if (cur == null || (cur.endsWith("_$id") && !n.endsWith("_$id"))) nameOf[id] = n
            }
        }
    }

    init { loadIds() }

    fun constantFor(id: Int): String? = nameOf[id]

    private var fileList: List<File>? = null

    /** Every spawn file: plugins/spawns/..., scripts/npc/spawns/... or anywhere else a *Spawns.kt / .kts lives. */
    override fun files(serverDir: File): List<File> = fileList ?: serverDir.walkTopDown().onEnter { !skip(it) }
        .filter { f -> f.isFile && (f.extension == "kt" || f.extension == "kts") &&
                (f.invariantSeparatorsPath.contains("/spawns/") || f.nameWithoutExtension.endsWith("Spawns")) }
        .toList().also { fileList = it }

    override fun read(file: File): List<NpcSpawn> {
        val out = ArrayList<NpcSpawn>()
        file.readLines().forEachIndexed { i, l ->
            val m = LINE.matchEntire(l)
            if (m == null) {
                if (LOOKS_LIKE.containsMatchIn(l) && oddLines.size < 8) oddLines.add(file.name + ": " + l.trim())
                return@forEachIndexed
            }
            val name = m.groupValues[2]
            if (name == "Region" || name == "NPCSpawnsScript") return@forEachIndexed
            val id = idOf[name] ?: run { unknownNames[name] = (unknownNames[name] ?: 0) + 1; return@forEachIndexed }
            val dir = m.groupValues[6].takeIf { it in DIRS } ?: "SOUTH"
            val s = NpcSpawn(id, m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toIntOrNull() ?: 0, dir,
                m.groupValues[7].toIntOrNull() ?: 0)
            s.line = i
            s.extra = m.groupValues[8]
            out.add(s)
        }
        return out
    }

    private fun lineFor(s: NpcSpawn, indent: String): String {
        val c = constantFor(s.id) ?: throw IllegalStateException("No NpcId constant for NPC ${s.id}, so it can't be written to a spawn file.")
        return "$indent$c(${s.x}, ${s.y}, ${s.z}, ${s.direction}, ${s.radius}${s.extra})"
    }

    override fun write(file: File, spawns: List<NpcSpawn>, removedLines: Set<Int>) {
        if (!file.isFile) createFile(file)
        val lines = file.readLines().toMutableList()
        val indent = lines.mapNotNull { LINE.matchEntire(it)?.groupValues?.get(1) }.firstOrNull() ?: "        "
        // spawns read from this file: rewrite their line only when it changed
        val byLine = spawns.filter { it.line >= 0 }.associateBy { it.line }
        for ((i, s) in byLine) {
            if (i !in lines.indices) continue
            val m = LINE.matchEntire(lines[i])
            val old = m?.let { NpcSpawn(idOf[it.groupValues[2]] ?: -1, it.groupValues[3].toInt(), it.groupValues[4].toInt(),
                it.groupValues[5].toIntOrNull() ?: 0, it.groupValues[6].takeIf { d -> d in DIRS } ?: "SOUTH", it.groupValues[7].toIntOrNull() ?: 0) }
            if (old != null && old.state() == s.state()) continue
            lines[i] = lineFor(s, m?.groupValues?.get(1) ?: indent) + (m?.groupValues?.get(9) ?: "")
        }
        // new ones go after the last spawn line
        val addedSpawns = spawns.filter { it.line < 0 }
        val added = addedSpawns.map { lineFor(it, indent) }
        val keep = lines.indices.filter { it !in removedLines }
        val result = ArrayList<String>()
        var lastSpawn = -1
        for (i in keep) { if (LINE.matchEntire(lines[i]) != null) lastSpawn = i }
        if (lastSpawn < 0) {
            // no spawns yet: just inside the init block (the second-to-last closing brace)
            val closers = keep.filter { lines[it].trim() == "}" }
            lastSpawn = when {
                closers.size >= 2 -> closers[closers.size - 2] - 1
                closers.size == 1 -> closers[0] - 1
                else -> keep.last()
            }
        }
        val insertAfter = keep.lastOrNull { it <= lastSpawn } ?: keep.firstOrNull() ?: -1
        val newIndex = HashMap<Int, Int>()
        if (insertAfter < 0) { addedSpawns.forEachIndexed { k, sp -> sp.line = k }; result.addAll(added) }
        for (i in keep) {
            newIndex[i] = result.size
            result.add(lines[i])
            if (i == insertAfter) addedSpawns.forEachIndexed { k, sp -> sp.line = result.size; result.add(added[k]) }
        }
        file.writeText(result.joinToString("\n") + "\n")
        // the spawns now know their new line numbers, so the next save edits the right lines
        for (sp in spawns) if (sp !in addedSpawns) sp.line = newIndex[sp.line] ?: -1
    }

    /** RegionNNNNNSpawns.kt in plugins/spawns/regionNNxxx. */
    override fun fileFor(s: NpcSpawn, serverDir: File?): File? {
        val dir = serverDir ?: return null
        val name = "Region${s.regionId}Spawns.kt"
        files(dir).firstOrNull { it.nameWithoutExtension == name.removeSuffix(".kt") }?.let { return it }
        // a new file next to the other files of that thousand (region12xxx for 12850)
        val sibling = files(dir).firstOrNull { it.invariantSeparatorsPath.contains("/region${s.regionId / 1000}xxx/") && it.name.startsWith("Region") }
            ?: files(dir).firstOrNull { it.name.startsWith("Region") }
            ?: return null
        return File(sibling.parentFile, name)
    }

    /** A new region file, made from another spawn file: same package, imports and layout, no spawns. */
    private fun createFile(file: File) {
        val template = file.parentFile.listFiles { f -> f.name.startsWith("Region") && f.name.endsWith("Spawns.kt") }?.firstOrNull()
            ?: files(serverDir).firstOrNull { it.name.startsWith("Region") }
            ?: throw IllegalStateException("No spawn file to copy the layout from.")
        val oldClass = template.nameWithoutExtension
        val newClass = file.nameWithoutExtension
        val text = template.readLines().filter { LINE.matchEntire(it) == null }.joinToString("\n") { it.replace(oldClass, newClass) }
        file.parentFile.mkdirs()
        file.writeText(text + "\n")
    }
}
