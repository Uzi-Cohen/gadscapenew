package models

import java.io.File
import java.util.Properties

/**
 * Editor settings (last cache folder etc).
 * Read from ./config.properties first (portable packages set it there), then from
 * %USERPROFILE%\.uzis-map-editor\config.properties so the setting survives moving or
 * re-downloading the editor. Saves go to the user folder, and to ./config.properties if it exists.
 */
class Configuration {
    // the packaged app keeps its settings next to its exe (so it opens its own map, not another copy's)
    private val packaged: File? = utils.Updater.installDir
    private val localFile = if (packaged != null) File(packaged, "config.properties") else File("config.properties")
    private val userFile = if (packaged != null) File(packaged, "config.properties")
        else File(System.getProperty("user.home"), ".uzis-map-editor/config.properties")
    private val properties = Properties()

    fun saveProp(key: String, value: String) {
        properties.setProperty(key, value)
        write(userFile)
        if (localFile.exists()) write(localFile)
    }

    fun getProp(key: String): String {
        return properties.getProperty(key).orEmpty()
    }

    private fun write(f: File) {
        try {
            f.parentFile?.mkdirs()
            f.writer().use { properties.store(it, "Uzi's Map Editor") }
        } catch (e: Exception) {
            println("Could not save settings to ${f.path}: ${e.message}")
        }
    }

    private fun load(f: File): Properties {
        val p = Properties()
        if (f.exists()) try { f.inputStream().use { p.load(it) } } catch (e: Exception) {}
        return p
    }

    init {
        // the local file points a fresh portable package at its bundled map; after that, the map you last
        // opened (saved in your user folder) wins, so your own choice sticks even if the local file is rewritten
        load(localFile).forEach { (k, v) -> if (v.toString().isNotBlank()) properties[k] = v }
        load(userFile).forEach { (k, v) ->
            val keep = v.toString().isNotBlank() && (k != "last-cache-dir" || File(v.toString(), "cache").isDirectory)
            if (keep) properties[k] = v
        }
        // first run with nothing remembered: use the usual GadScape editor folder if it's there
        // packaged app: your GadScape map if this PC has one, otherwise the map that came with it
        if (properties.getProperty("last-cache-dir").isNullOrBlank() && packaged != null) {
            val gadscape = File("C:/GadScape/Editor")
            val bundled = File(packaged, "map")
            when {
                File(gadscape, "cache").isDirectory -> properties.setProperty("last-cache-dir", gadscape.path)
                File(bundled, "cache").isDirectory -> properties.setProperty("last-cache-dir", bundled.path)
            }
        }
        if (properties.getProperty("last-cache-dir").isNullOrBlank()) {
            val guess = File("C:/GadScape/Editor")
            if (File(guess, "cache").isDirectory) properties.setProperty("last-cache-dir", guess.path)
        }
    }
}
