package utils

import java.util.Properties

/** The editor's version and build number (written into version.properties when it's built). */
object BuildInfo {
    private val props = Properties().apply {
        try { BuildInfo::class.java.getResourceAsStream("/version.properties")?.use { load(it) } } catch (e: Exception) { }
    }
    val version: String = props.getProperty("version")?.takeIf { !it.startsWith("$") } ?: "1.0"
    val build: String = props.getProperty("build")?.takeIf { !it.startsWith("$") } ?: "dev"
    /** e.g. "1.0 (build 100)". */
    val label: String get() = "$version (build $build)"
}
