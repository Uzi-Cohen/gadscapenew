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
        return listOf(File("C:/GadScape"), File(home, "GadScape"), File(home, "Downloads"), File(home, "Desktop"), File(home, "Documents"))
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

    /** The server folder without a saved setting: the one found earlier this run, or one in the usual places. */
    fun guess(): File? = cached?.takeIf { it.isDirectory } ?: knownPlaces().asSequence().mapNotNull { near(it) }.firstOrNull()?.also { cached = it }

    /** A folder picked in a window: use it if it has the code, else the code near it, else the known one. */
    fun fromPicked(configuration: Configuration, picked: File): Pair<File?, String?> {
        near(picked)?.let { remember(configuration, it); return Pair(it, if (it != picked) "Using the server code in ${it.path}." else null) }
        cached = null
        val known = dir(configuration)
        return Pair(known, if (known != null) "${picked.path} has no server code (no NpcId), so I used ${known.path}."
                           else "${picked.path} has no server code (no NpcId.java). Pick your server folder (the one with gradlew and settings.gradle.kts).")
    }

    /**
     * The package the server's own code uses (com.<pkg>): "gadscape". Found from the shops folder, so files the editor
     * writes match the code they go into; an older copy of the source with one other package there gets that one.
     */
    fun pkg(server: File?): String {
        val com = server?.let { File(it, "plugins/shops/src/main/kotlin/com") } ?: return "gadscape"
        if (File(com, "gadscape").isDirectory) return "gadscape"
        return com.listFiles()?.filter { it.isDirectory }?.map { it.name }?.singleOrNull() ?: "gadscape"
    }

    /** [base]/com/<pkg>/[sub] for this server, e.g. pkgPath(s, "plugins/shops/src/main/kotlin", "plugins/shops"). */
    fun pkgPath(server: File?, base: String, sub: String): String = "$base/com/${pkg(server)}/$sub"

    private fun remember(configuration: Configuration, d: File) {
        cached = d
        configuration.saveProp(KEY, d.path)
        configuration.saveProp("server-dir", d.path)
    }
}
