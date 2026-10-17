package utils

import cache.CachePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Builds a double-clickable Windows app: "Uzis Map Editor\Uzis Map Editor.exe" with the icon,
 * a loading splash, only the parts of Java it needs (jlink), and optionally a map.
 *
 *   Uzis Map Editor\
 *     Uzis Map Editor.exe
 *     app\        editor jars, JavaFX, splash, version.txt, update-url.txt   (replaced by updates)
 *     runtime\    trimmed Java
 *     map\        cache\, xteas.json ...                                      (never replaced by app updates)
 */
class AppImage(private val log: (String) -> Unit) {
    companion object {
        const val APP_NAME = "Uzis Map Editor"
        private val UNUSED_JFX = listOf("javafx-web", "javafx-media", "javafx.web", "javafx.media")
        private fun jfxModules() = ModuleLayer.boot().modules().map { it.name }
            .filter { it.startsWith("javafx.") && it != "javafx.web" && it != "javafx.media" }.sorted()
        /** What the editor needs opened on Java 21 (Guice); the jar's manifest has them, but an app's main jar doesn't. */
        private val REQUIRED_FLAGS = listOf("--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
        private val RUNTIME_MODULES = listOf(
            "java.se", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.crypto.ec", "jdk.crypto.mscapi",
            "jdk.zipfs", "jdk.charsets", "jdk.jsobject", "jdk.xml.dom", "jdk.localedata"
        )
    }

    private fun tool(name: String): File? {
        fun inJdk(home: File) = listOf(File(home, "bin/$name.exe"), File(home, "bin/$name")).firstOrNull { it.isFile }
        inJdk(File(System.getProperty("java.home")))?.let { return it }
        // the one-file editor runs on a Java without jpackage/jlink: use a full JDK 21, downloaded once
        val jdk = downloadedJdk() ?: return null
        return inJdk(jdk)
    }

    /** A full Temurin JDK 21 in the user folder (downloaded the first time it's needed), or null if that failed. */
    private fun downloadedJdk(): File? {
        val dir = File(System.getProperty("user.home"), ".uzis-map-editor/jdk-21")
        fun home() = dir.listFiles()?.firstOrNull { File(it, "bin").isDirectory && File(it, "jmods").isDirectory }
        home()?.let { return it }
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val os = if (windows) "windows" else if (System.getProperty("os.name").lowercase().contains("mac")) "mac" else "linux"
        val arch = if (System.getProperty("os.arch").contains("aarch64")) "aarch64" else "x64"
        val url = "https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse?project=jdk"
        return try {
            log("This Java can't build the app; downloading a full Java 21 (once, about 200 MB)...")
            dir.deleteRecursively(); dir.mkdirs()
            val archive = File(dir, if (windows) "jdk.zip" else "jdk.tar.gz")
            java.net.URI(url).toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            log("Unpacking Java...")
            if (windows) {
                java.util.zip.ZipFile(archive).use { z ->
                    for (e in z.entries()) {
                        val f = File(dir, e.name)
                        if (!f.canonicalPath.startsWith(dir.canonicalPath)) continue
                        if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } } }
                    }
                }
            } else if (!run(listOf("tar", "-xzf", archive.path, "-C", dir.path))) return null
            archive.delete()
            home()?.also { log("Java 21 ready: ${it.path}") } ?: run { log("The Java download didn't unpack right."); null }
        } catch (e: Exception) {
            log("Couldn't download Java 21: ${e.message}")
            null
        }
    }

    private fun run(cmd: List<String>): Boolean {
        log("> " + cmd.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }.take(300))
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            out.lines().filter { it.isNotBlank() }.takeLast(12).forEach { log("  $it") }
            p.waitFor() == 0
        } catch (e: Exception) {
            log("  failed: ${e.message}")
            false
        }
    }

    /** Windows .ico from the PNG icons in the resources (PNG-in-ICO, fine on Windows 7+). */
    private fun writeIco(target: File) {
        val sizes = listOf(16, 32, 64, 256)
        val pngs = sizes.mapNotNull { s -> AppImage::class.java.getResourceAsStream("/icon-$s.png")?.use { Pair(s, it.readBytes()) } }
        val header = ByteBuffer.allocate(6 + 16 * pngs.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(pngs.size.toShort())
        var offset = 6 + 16 * pngs.size
        for ((s, data) in pngs) {
            header.put((if (s >= 256) 0 else s).toByte()).put((if (s >= 256) 0 else s).toByte())
            header.put(0).put(0).putShort(1).putShort(32).putInt(data.size).putInt(offset)
            offset += data.size
        }
        FileOutputStream(target).use { out ->
            out.write(header.array())
            pngs.forEach { out.write(it.second) }
        }
    }

    /** All class/resource folders on the classpath merged into one jar, with a Class-Path for the libraries. */
    private fun writeMainJar(target: File, classDirs: List<File>, libNames: List<String>) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "AppKt"
            mainAttributes[Attributes.Name.CLASS_PATH] = libNames.joinToString(" ") { "lib/$it" }
        }
        val seen = HashSet<String>()
        JarOutputStream(FileOutputStream(target), manifest).use { jar ->
            for (dir in classDirs) {
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val name = f.relativeTo(dir).invariantSeparatorsPath
                    if (name.equals("META-INF/MANIFEST.MF", true) || !seen.add(name)) return@forEach
                    jar.putNextEntry(JarEntry(name))
                    f.inputStream().use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }
    }

    /**
     * The older ".bat" downloads fetch this app folder and run app\launch.cmd with their own Java.
     * Give them a launch.cmd for the new layout, and a better update script (progress + log) they copy in.
     */
    private fun writeLegacyLauncher(input: File) {
        val jfxModules = jfxModules()
        val flags = (REQUIRED_FLAGS + ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.startsWith("--add-opens") || it.startsWith("--add-exports") }
            .map { it.replace(" ", "=") }).distinct()
        val cmd = buildString {
            appendln("@echo off")
            appendln("cd /d \"%~dp0..\"")
            appendln("if exist \"app\\update.ps1\" copy /y \"app\\update.ps1\" \"update.ps1\" >nul")
            appendln("set \"JAVA=java\"")
            appendln("if exist \"java\\bin\\java.exe\" set \"JAVA=%CD%\\java\\bin\\java.exe\"")
            appendln("echo Starting Uzi's Map Editor...")
            append("start \"\" \"%JAVA%w\"".replace("%JAVA%w", "%JAVA:java.exe=javaw.exe%"))
            append(" -Dume.appdir=\"%CD%\\app\" -Dume.noupdate=true -Dume.autolaunch=true")
            // run from the 1.0 jar, JavaFX is inside it (on the classpath), not separate modules
            if (jfxModules.isNotEmpty()) append(" --module-path app\\modules --add-modules ${jfxModules.joinToString(",")}")
            flags.forEach { append(" $it") }
            appendln(" -cp app\\uzis-map-editor.jar AppKt")
        }
        File(input, "launch.cmd").writeText(cmd.replace("\n", "\r\n"))
        File(input, "update.ps1").writeText(Packager.UPDATER.trimIndent().replace("\n", "\r\n"))
    }

    /**
     * Build the app into [work]. Returns the app folder ("Uzis Map Editor"), or null on failure.
     * [map]: folder whose main_file_cache files become map\cache (null = no map).
     */
    fun build(work: File, stamp: String, updateBase: String?, map: File?): File? {
        val jpackage = tool("jpackage")
        val jlink = tool("jlink")
        if (jpackage == null || jlink == null) {
            log("This Java has no jpackage/jlink. Run the editor with a full JDK 21 (Temurin JDK).")
            return null
        }
        work.deleteRecursively()
        val input = File(work, "input").apply { mkdirs() }
        File(input, "lib").mkdirs()
        File(input, "modules").mkdirs()

        // ---- what the running editor is made of ----
        val sep = File.pathSeparator
        val modulePath = (System.getProperty("jdk.module.path") ?: "").split(sep).filter { it.isNotBlank() }.map { File(it) }.filter { it.exists() }
        val moduleSet = modulePath.map { it.absolutePath }.toSet()
        val classPath = (System.getProperty("java.class.path") ?: "").split(sep).filter { it.isNotBlank() }
            .map { File(it) }.filter { it.exists() && it.absolutePath !in moduleSet }
        val classDirs = classPath.filter { it.isDirectory }
        // JavaFX goes in once (as modules); the classpath copies are empty placeholders
        val libs = classPath.filter { it.isFile && it.name.endsWith(".jar") && !it.name.startsWith("javafx-") }
        libs.forEach { it.copyTo(File(input, "lib/${it.name}"), true) }
        // the editor doesn't use JavaFX Web or Media: leave them out (saves ~70 MB)
        modulePath.filter { f -> UNUSED_JFX.none { f.name.contains(it) } }
            .forEach { f -> if (f.isFile) f.copyTo(File(input, "modules/${f.name}"), true) else f.copyRecursively(File(input, "modules/${f.name}"), true) }
        writeMainJar(File(input, "uzis-map-editor.jar"), classDirs, libs.map { it.name })
        log("Editor: ${classDirs.size} class folders, ${libs.size} libraries, ${modulePath.size} JavaFX modules")

        AppImage::class.java.getResourceAsStream("/splash.png")?.use { s -> File(input, "splash.png").outputStream().use { s.copyTo(it) } }
        File(input, "version.txt").writeText(stamp)
        if (updateBase != null) File(input, "update-url.txt").writeText(updateBase)
        val ico = File(work, "icon.ico").also { writeIco(it) }
        writeLegacyLauncher(input)

        // ---- trimmed Java ----
        log("Making a small Java runtime...")
        val runtime = File(work, "runtime")
        val jlinkBase = listOf(jlink.path, "--add-modules", RUNTIME_MODULES.joinToString(","),
            "--strip-debug", "--no-header-files", "--no-man-pages", "--output", runtime.path)
        if (!run(jlinkBase + "--compress=zip-6")) {
            runtime.deleteRecursively()
            if (!run(jlinkBase + "--compress=2")) return null
        }

        // ---- the exe ----
        val jfxModules = jfxModules()
        val flags = (REQUIRED_FLAGS + ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.startsWith("--add-opens") || it.startsWith("--add-exports") || it.startsWith("-Xmx") }
            .map { it.replace(" ", "=") }).distinct()
        // an empty --add-modules= stops Java from starting at all ("Failed to launch JVM"): run from the 1.0 jar,
        // JavaFX is inside the jar (on the classpath) and there are no separate JavaFX modules
        val javaOptions = listOf("-Dume.appdir=\$APPDIR", "-Dume.autolaunch=true") +
            (if (jfxModules.isEmpty()) emptyList() else listOf("--module-path=\$APPDIR/modules", "--add-modules=" + jfxModules.joinToString(","))) +
            flags
        val out = File(work, "out").apply { mkdirs() }
        val cmd = mutableListOf(jpackage.path, "--type", "app-image", "--name", APP_NAME,
            "--input", input.path, "--main-jar", "uzis-map-editor.jar", "--main-class", "AppKt",
            "--runtime-image", runtime.path, "--icon", ico.path, "--dest", out.path,
            "--app-version", "1.0.0", "--vendor", "Uzi Cohen", "--description", "Uzi's Map Editor")
        javaOptions.forEach { cmd.add("--java-options"); cmd.add(it) }
        log("Building the app (exe + icon)...")
        if (!run(cmd)) return null
        val image = File(out, APP_NAME)

        // ---- map ----
        if (map != null) {
            val mapCache = File(image, "map/cache").apply { mkdirs() }
            map.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.forEach { it.copyTo(File(mapCache, it.name), true) }
            listOf("xteas.json", "gadscape-bookmarks.json").forEach { n ->
                val f = File(GadTools.editorDir, n)
                if (f.exists()) f.copyTo(File(image, "map/$n"), true)
            }
            File(image, "map/map-version.txt").writeText(stamp)
            val dat = File(map, "main_file_cache.dat2")
            log("Included the map from ${map.path} (saved ${java.text.SimpleDateFormat("dd MMM HH:mm").format(java.util.Date(dat.lastModified()))}, %.0f MB)".format(dat.length() / 1048576.0))
        }
        val mb = image.walkTopDown().filter { it.isFile }.map { it.length() }.sum() / 1048576.0
        log("App ready: ${image.path} (%.0f MB)".format(mb))
        return image
    }
}
