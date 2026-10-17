package utils

/**
 * Place names for region ids (cities, dungeons, bosses, minigames, raids, areas), from RuneLite's region list.
 * Regions not in the list get "near X" (closest named place) or "under X" (dungeons sit 6400 tiles north of what's above them).
 */
object RegionNames {
    class Place(val name: String, val kind: String)

    private val names: Map<Int, Place> by lazy {
        val out = HashMap<Int, Place>()
        try {
            RegionNames::class.java.getResourceAsStream("/data/region-names.tsv")?.bufferedReader()?.forEachLine { l ->
                val p = l.split('\t')
                if (p.size >= 3) p[0].toIntOrNull()?.let { out[it] = Place(p[1], kindLabel(p[2])) }
            }
        } catch (e: Exception) { println("Region names: $e") }
        out
    }

    private fun kindLabel(k: String) = when (k) {
        "CITIES" -> "city"; "DUNGEONS" -> "dungeon"; "BOSSES" -> "boss"; "MINIGAMES" -> "minigame"; "RAIDS" -> "raid"; else -> "area"
    }

    fun exact(regionId: Int): Place? = names[regionId]

    /** Best description: the name, "under X", "near X", or "" when nothing is close. */
    fun describe(regionId: Int): String {
        names[regionId]?.let { return "${it.name} (${it.kind})" }
        val rx = regionId shr 8; val ry = regionId and 0xFF
        // underground: 6400 tiles = 100 regions north of the surface above it
        if (ry >= 100) {
            val above = (rx shl 8) or (ry - 100)
            names[above]?.let { return "under ${it.name}" }
        }
        var best: Place? = null; var bestD = Int.MAX_VALUE
        for (dx in -2..2) for (dy in -2..2) {
            if (dx == 0 && dy == 0) continue
            val p = names[((rx + dx) shl 8) or (ry + dy)] ?: continue
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = p }
        }
        return best?.let { "near ${it.name}" } ?: ""
    }
}
