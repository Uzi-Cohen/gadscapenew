package utils

import java.io.File

/**
 * Favourite and recently used objects, remembered between runs in
 * %USERPROFILE%\.uzis-map-editor\ (favorites.txt, recent.txt).
 */
object Favorites {
    private val dir = File(System.getProperty("user.home"), ".uzis-map-editor")
    private val favFile = File(dir, "favorites.txt")
    private val recentFile = File(dir, "recent.txt")
    private const val RECENT_MAX = 40

    private val favs = LinkedHashSet<Int>(load(favFile))
    private val recent = ArrayList<Int>(load(recentFile))
    private val listeners = ArrayList<() -> Unit>()

    private fun load(f: File): List<Int> = try {
        if (f.exists()) f.readLines().mapNotNull { it.trim().toIntOrNull() } else emptyList()
    } catch (e: Exception) { emptyList() }

    private fun save(f: File, ids: Collection<Int>) {
        try { dir.mkdirs(); f.writeText(ids.joinToString("\n")) } catch (e: Exception) {}
    }

    private fun changed() {
        val run = { listeners.toList().forEach { it() } }
        if (javafx.application.Platform.isFxApplicationThread()) run() else javafx.application.Platform.runLater(run)
    }

    fun onChange(l: () -> Unit) { listeners.add(l) }

    fun isFavorite(id: Int) = favs.contains(id)
    fun favorites(): Set<Int> = favs
    fun recent(): List<Int> = recent

    /** Star or unstar an object. Returns true if it is now a favourite. */
    fun toggle(id: Int): Boolean {
        val now = if (favs.remove(id)) false else { favs.add(id); true }
        save(favFile, favs)
        changed()
        return now
    }

    /** Remember an object you just used (held, placed, copied). */
    fun used(id: Int) {
        if (recent.firstOrNull() == id) return
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > RECENT_MAX) recent.removeAt(recent.size - 1)
        save(recentFile, recent)
        changed()
    }
}
