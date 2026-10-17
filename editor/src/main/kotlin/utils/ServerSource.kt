package utils

import models.Configuration
import java.io.File

/**
 * Where the server's source code is (the folder with gradlew + settings.gradle and NpcId.java inside).
 * Every window (NPCs, Teleports, Object options, Shops) uses this, so picking some other folder in one of
 * them (C:\GadScape, a data folder) can't break the others any more: a folder without the code is never used.
 */
object ServerSource {
    private const val KEY = "server-src-dir"
    private var cached: File? = null

    private fun skip(f: File) = f.invariantSeparatorsPath.let {
        it.contains("/build/") || it.contains("/.gradle/") || it.contains("/.git/") || it.contains("/node_modules/") ||
                it.contains("/cache") || it.contains("/excluded/")
    }

    private fun isGradleRoot(d: File) = (File(d, "settings.gradle.kts").isFile || File(d, "settings.gradle").isFile) &&
            (File(d, "gradlew.bat").isFile || File(d, "gradlew").isFile)

    /** True when [d] is a server project with NpcId in it. */
    fun isSource(d: File?): Boolean {
        if (d == null || !d.isDirectory || !isGradleRoot(d)) return false
        return d.walkTopDown().onEnter { !skip(it) }.any { it.isFile && (it.name == "NpcId.java" || it.name == "NpcId.kt") }
    }

    /** The server project in, under or above [d] (a few levels each way), or null. */
    fun near(d: File?): File? {
        if (d == null || !d.isDirectory) return null
        if (isSource(d)) return d
        d.walkTopDown().onEnter { !skip(it) }.maxDepth(4).filter { it.isDirectory && isGradleRoot(it) }.firstOrNull { isSource(it) }?.let { return it }
        return generateSequence(d.parentFile) { it.parentFile }.take(4).firstOrNull { isSource(it) }
    }

    private fun knownPlaces(): List<File> {
        val home = File(System.getProperty("user.home"))
        return listOf(
            File(home, "Downloads/RSPS-NEAR-REALITY-main/RSPS-NEAR-REALITY-main/near-reality-server-main"),
            File(home, "Downloads/RSPS-NEAR-REALITY-main"),
            File(home, "Downloads"), File(home, "Desktop"), File(home, "Documents"), File("C:/GadScape")
        )
    }

    /** The server source folder: the saved one if it really is one, otherwise found and saved. */
    fun dir(configuration: Configuration): File? {
        cached?.takeIf { it.isDirectory }?.let { return it }
        val saved = listOf(configuration.getProp(KEY), configuration.getProp("server-dir"))
            .filter { it.isNotBlank() }.map { File(it) }
        val found = saved.firstOrNull { isSource(it) }
            ?: saved.asSequence().mapNotNull { near(it) }.firstOrNull()
            ?: knownPlaces().asSequence().mapNotNull { near(it) }.firstOrNull()
        if (found != null) remember(configuration, found)
        return found
    }

    /** A folder picked in a window: use it if it has the code, else the code near it, else the known one. */
    fun fromPicked(configuration: Configuration, picked: File): Pair<File?, String?> {
        near(picked)?.let { remember(configuration, it); return Pair(it, if (it != picked) "Using the server code in ${it.path}." else null) }
        cached = null
        val known = dir(configuration)
        return Pair(known, if (known != null) "${picked.path} has no server code (no NpcId), so I used ${known.path}."
                           else "${picked.path} has no server code (no NpcId.java). Pick the server folder (the one with gradlew and plugins/).")
    }

    /**
     * The server's own package root: "gadscape" (com.gadscape...) for GadScape, "near_reality" for a Near-Reality
     * checkout that wasn't renamed.
     */
    fun pkg(server: File?): String {
        val base = server?.let { File(it, "plugins/shops/src/main/kotlin/com") }
        return if (base != null && File(base, "near_reality").isDirectory && !File(base, "gadscape").isDirectory) "near_reality" else "gadscape"
    }

    /** Where the shop scripts are, relative to the server folder. */
    fun shopsPath(server: File?): String = "plugins/shops/src/main/kotlin/com/${pkg(server)}/plugins/shops"

    private fun remember(configuration: Configuration, d: File) {
        cached = d
        configuration.saveProp(KEY, d.path)
        configuration.saveProp("server-dir", d.path)
    }
}
