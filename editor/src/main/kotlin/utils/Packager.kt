package utils

import cache.CachePaths
import java.io.File
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a portable copy of the running editor plus a map:
 *
 *   UzisMapEditor-<date>/
 *     Start Uzi's Map Editor.bat
 *     app/lib, app/classes, app/modules   (the editor itself)
 *     map/cache + map/xteas.json + tool settings/bookmarks
 *     java/                                (optional, so it runs on PCs without Java 21)
 */
class Packager(private val log: (String) -> Unit) {

    enum class MapSource(val label: String) {
        EDITED("Edited map (cache-out, with your exported changes)"),
        ORIGINAL("Original map (the cache the editor opened)"),
        NONE("No map (editor only)")
    }

    /**
     * [updateUrl]: where the package checks for newer versions on every start (a GitHub
     * "releases/latest/download" address). null = no auto-update.
     */
    fun build(outRoot: File, source: MapSource, includeJava: Boolean, makeZip: Boolean,
              updateUrl: String? = null, stamp: String = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())): File? {
        val root = File(outRoot, "UzisMapEditor-$stamp")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        log("Building ${root.path}")

        // ---- the editor itself, taken from what is running right now ----
        val sep = File.pathSeparator
        val modulePath = (System.getProperty("jdk.module.path") ?: "").split(sep).filter { it.isNotBlank() }.map { File(it) }
        val moduleNames = modulePath.map { it.absolutePath }.toSet()
        val classPath = (System.getProperty("java.class.path") ?: "").split(sep).filter { it.isNotBlank() }
            .map { File(it) }.filter { it.exists() && it.absolutePath !in moduleNames }

        val libDir = File(root, "app/lib").apply { mkdirs() }
        val classesDir = File(root, "app/classes").apply { mkdirs() }
        val modDir = File(root, "app/modules").apply { mkdirs() }
        val cpEntries = mutableListOf("app\\lib\\*")
        classPath.forEachIndexed { i, f ->
            if (f.isDirectory) {
                val name = "$i-${f.parentFile?.name ?: "dir"}-${f.name}"
                f.copyRecursively(File(classesDir, name), true)
                cpEntries.add("app\\classes\\$name")
            } else {
                f.copyTo(File(libDir, f.name), true)
            }
        }
        modulePath.filter { it.exists() }.forEach { f ->
            if (f.isDirectory) f.copyRecursively(File(modDir, f.name), true) else f.copyTo(File(modDir, f.name), true)
        }
        log("Copied ${classPath.size} classpath entries and ${modulePath.size} JavaFX modules")

        val jfxModules = ModuleLayer.boot().modules().map { it.name }.filter { it.startsWith("javafx.") }.sorted()
        val flags = ManagementFactory.getRuntimeMXBean().inputArguments
            .filter { it.startsWith("--add-opens") || it.startsWith("--add-exports") || it.startsWith("-Xmx") || it.startsWith("-Xms") }
            .map { it.replace(" ", "=") }
            .distinct()

        // ---- the map ----
        val editorDir = GadTools.editorDir
        when (source) {
            MapSource.NONE -> log("No map included")
            else -> {
                val src = if (source == MapSource.EDITED) GadTools.ensureExportCache() else File(CachePaths.cacheDir).absoluteFile
                val mapCache = File(root, "map/cache").apply { mkdirs() }
                src.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.forEach {
                    it.copyTo(File(mapCache, it.name), true)
                }
                listOf("xteas.json", "gadscape-tools.json", "gadscape-bookmarks.json").forEach { n ->
                    val f = File(editorDir, n)
                    if (f.exists()) f.copyTo(File(root, "map/$n"), true)
                }
                log("Included map from ${src.path}")
            }
        }

        // ---- optional Java runtime ----
        if (includeJava) {
            val javaHome = File(System.getProperty("java.home"))
            log("Copying Java from ${javaHome.path} (this is the slow part)...")
            javaHome.copyRecursively(File(root, "java"), true)
        }

        // ---- launcher ----
        // app\launch.cmd holds the real start command, so an update (which replaces app\) can change it too
        File(root, "app/version.txt").writeText(stamp)
        val launch = buildString {
            appendln("@echo off")
            appendln("cd /d \"%~dp0..\"")
            appendln("set \"JAVA=java\"")
            appendln("if exist \"java\\bin\\java.exe\" set \"JAVA=%CD%\\java\\bin\\java.exe\"")
            appendln("if not exist \"java\\bin\\java.exe\" (")
            appendln("  where java >nul 2>nul || (echo Java 21 is not installed and this download has no Java folder. & echo Get the full download instead. & pause & exit /b 1)")
            appendln(")")
            appendln("rem open the last used map by itself (the cache window counts down, Browse to pick another)")
            appendln("set \"AUTO=-Dume.autolaunch=true\"")
            appendln("echo Starting Uzi's Map Editor... (this window can stay open)")
            append("\"%JAVA%\" %AUTO%")
            flags.forEach { append(" $it") }
            append(" --module-path \"app\\modules\" --add-modules ${jfxModules.joinToString(",")}")
            append(" -cp \"${cpEntries.joinToString(";")}\" AppKt")
            appendln()
            appendln("if errorlevel 1 (echo. & echo The editor stopped with an error, see above. & pause)")
        }
        File(root, "app/launch.cmd").writeText(launch.replace("\n", "\r\n"))

        if (updateUrl != null) {
            File(root, "update-url.txt").writeText(updateUrl)
            File(root, "update.ps1").writeText(UPDATER.trimIndent().replace("\n", "\r\n"))
        }

        val bat = buildString {
            appendln("@echo off")
            appendln("title Uzi's Map Editor")
            appendln("cd /d \"%~dp0\"")
            appendln("if exist \"update.ps1\" powershell -NoProfile -ExecutionPolicy Bypass -File \"%~dp0update.ps1\"")
            appendln("rem point the cache chooser at the bundled map")
            appendln("set \"MAPDIR=%~dp0map\"")
            appendln("set \"MAPDIR=%MAPDIR:\\=/%\"")
            appendln("rem first start only: point at the bundled map; after that the editor remembers your own choice")
            appendln("if not exist \"%~dp0config.properties\" if exist \"%~dp0map\\cache\" (echo last-cache-dir=%MAPDIR%)> \"%~dp0config.properties\"")
            appendln("if not exist \"%~dp0app\\launch.cmd\" (echo The app folder is missing. Download the full package again. & pause & exit /b 1)")
            appendln("call \"%~dp0app\\launch.cmd\"")
        }
        File(root, "Start Uzi's Map Editor.bat").writeText(bat.replace("\n", "\r\n"))

        File(root, "README.txt").writeText(("""
            Uzi's Map Editor (portable)

            1. Double-click "Start Uzi's Map Editor.bat".
            2. It opens straight into the map that came with it (the map folder).
            ${if (includeJava) "Java is included, nothing else to install." else "Needs Java 21 installed (Temurin/Adoptium)."}

            Edits export into map\cache-out. Deploy settings are in map\gadscape-tools.json.
        """.trimIndent()).replace("\n", "\r\n"))

        if (!makeZip) {
            log("Done: ${root.path}")
            return root
        }
        val zip = File(outRoot, root.name + ".zip")
        log("Zipping to ${zip.name}...")
        zipDir(root, zip)
        log("Done: ${zip.path} (%.0f MB)".format(zip.length() / 1048576.0))
        return zip
    }

    companion object {
        /** Runs before every start: fetch a newer app\ from the update address if there is one. */
        const val UPDATER = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            ${'$'}root = ${'$'}PSScriptRoot
            ${'$'}logDir = Join-Path ${'$'}env:USERPROFILE '.uzis-map-editor'
            New-Item -ItemType Directory -Force ${'$'}logDir | Out-Null
            Start-Transcript -Path (Join-Path ${'$'}logDir 'update.log') -Force | Out-Null
            try {
                ${'$'}base = (Get-Content (Join-Path ${'$'}root 'update-url.txt') -Raw).Trim()
                ${'$'}local = ''
                ${'$'}vf = Join-Path ${'$'}root 'app\version.txt'
                if (Test-Path ${'$'}vf) { ${'$'}local = (Get-Content ${'$'}vf -Raw).Trim() }
                [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
                ${'$'}remote = (Invoke-WebRequest "${'$'}base/version.txt" -UseBasicParsing -TimeoutSec 10).Content
                if (${'$'}remote -is [byte[]]) { ${'$'}remote = [Text.Encoding]::UTF8.GetString(${'$'}remote) }
                ${'$'}remote = ${'$'}remote.Trim()
                if (-not ${'$'}remote -or ${'$'}remote -eq ${'$'}local) { exit 0 }
                Write-Host "Updating Uzi's Map Editor: ${'$'}local -> ${'$'}remote (downloading, please wait)..."
                ${'$'}tmp = Join-Path ${'$'}env:TEMP ('ume-update-' + [guid]::NewGuid())
                New-Item -ItemType Directory ${'$'}tmp | Out-Null
                Invoke-WebRequest "${'$'}base/app.zip" -OutFile (Join-Path ${'$'}tmp 'app.zip') -UseBasicParsing
                Expand-Archive (Join-Path ${'$'}tmp 'app.zip') ${'$'}tmp -Force
                Write-Host "Downloaded, installing..."
                if (-not (Test-Path (Join-Path ${'$'}tmp 'app\launch.cmd'))) { throw 'download looks broken' }
                ${'$'}old = Join-Path ${'$'}root 'app.old'
                if (Test-Path ${'$'}old) { Remove-Item ${'$'}old -Recurse -Force }
                Rename-Item (Join-Path ${'$'}root 'app') 'app.old'
                Move-Item (Join-Path ${'$'}tmp 'app') (Join-Path ${'$'}root 'app')
                Remove-Item ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue
                Remove-Item ${'$'}tmp -Recurse -Force -ErrorAction SilentlyContinue
                Write-Host "Updated to ${'$'}remote."
                Copy-Item (Join-Path ${'$'}root 'app\update.ps1') (Join-Path ${'$'}root 'update.ps1') -Force -ErrorAction SilentlyContinue
            } catch {
                Write-Host "Update check skipped: ${'$'}(${'$'}_.Exception.Message)"
                if ((-not (Test-Path (Join-Path ${'$'}root 'app'))) -and (Test-Path (Join-Path ${'$'}root 'app.old'))) {
                    Rename-Item (Join-Path ${'$'}root 'app.old') 'app'
                }
            }
            Stop-Transcript | Out-Null
        """
    }

    /** Full path to gh.exe, so publishing works even if the editor started before GitHub CLI was installed. */
    private val ghPath: String by lazy {
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles")?.let { "$it\\GitHub CLI\\gh.exe" },
            System.getenv("ProgramFiles(x86)")?.let { "$it\\GitHub CLI\\gh.exe" },
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\GitHub CLI\\gh.exe" },
            System.getenv("USERPROFILE")?.let { "$it\\scoop\\shims\\gh.exe" }
        ) + (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "gh.exe").path }
        candidates.firstOrNull { File(it).isFile } ?: "gh"
    }

    private fun run(cmd0: List<String>, dir: File? = null): Pair<Int, String> {
        val cmd = if (cmd0.firstOrNull() == "gh") listOf(ghPath) + cmd0.drop(1) else cmd0
        log("> " + cmd0.joinToString(" "))
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).apply { if (dir != null) directory(dir) }.start()
            val out = p.inputStream.bufferedReader().readText()
            out.lines().filter { it.isNotBlank() }.takeLast(15).forEach { log("  $it") }
            Pair(p.waitFor(), out)
        } catch (e: Exception) {
            log("  failed to run: ${e.message}")
            Pair(-1, "")
        }
    }

    /**
     * Publish the running editor as an update: a public "<you>/uzis-map-editor-releases" GitHub repo
     * gets a release with app.zip + version.txt (and optionally the full download with Java).
     * Everyone's portable copy picks it up the next time they start it. Your source stays private.
     */
    fun publish(workDir: File, includeFull: Boolean, notes: String, includeMap: Boolean = true): Boolean {
        val login = run(listOf("gh", "api", "user", "--jq", ".login")).let { if (it.first == 0) it.second.trim() else "" }
        if (login.isEmpty()) {
            log(if (ghPath == "gh") "GitHub CLI not found. Install it: winget install --id GitHub.cli -e" else "GitHub CLI isn't logged in. Run: gh auth login --web")
            return false
        }
        val repo = "$login/uzis-map-editor-releases"
        if (run(listOf("gh", "repo", "view", repo, "--json", "name")).first != 0) {
            log("Creating the public downloads repo $repo (only builds go here, never your source)...")
            if (run(listOf("gh", "repo", "create", repo, "--public", "--add-readme",
                    "--description", "Downloads and auto-updates for Uzi's Map Editor")).first != 0) return false
        }
        val base = "https://github.com/$repo/releases"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
        workDir.mkdirs()

        // your saved map (cache-out); the full download always carries it
        val sendMap = includeMap || includeFull
        val mapSrc = if (sendMap) GadTools.ensureExportCache() else null

        val image = AppImage(log).build(File(workDir, "image"), stamp, base, if (includeFull) mapSrc else null) ?: return false

        // ---- map: its own "map" release, replaced in place, so everyone can always find the newest one ----
        if (mapSrc != null) {
            log("Packing your saved map...")
            val mapFolder = File(workDir, "map").apply { mkdirs() }
            val cacheDir = File(mapFolder, "cache").apply { mkdirs() }
            mapSrc.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.forEach { it.copyTo(File(cacheDir, it.name), true) }
            File(GadTools.editorDir, "xteas.json").takeIf { it.isFile }?.copyTo(File(mapFolder, "xteas.json"), true)
            val mapZip = File(workDir, "map.zip")
            zipDir(mapFolder, mapZip)
            val mapVersion = File(workDir, "map-version.txt").apply { writeText(stamp) }
            if (run(listOf("gh", "release", "view", "map", "--repo", repo)).first != 0) {
                if (run(listOf("gh", "release", "create", "map", "--repo", repo, "--title", "Map",
                        "--notes", "The newest map. The editor downloads it by itself.", "--latest=false")).first != 0) return false
            }
            if (run(listOf("gh", "release", "upload", "map", mapZip.path, mapVersion.path, "--clobber", "--repo", repo)).first != 0) return false
            log("Map uploaded (%.0f MB).".format(mapZip.length() / 1048576.0))
        }

        // ---- editor ----
        val appZip = File(workDir, "app.zip")
        log("Zipping the update...")
        zipDir(File(image, "app"), appZip)
        val version = File(workDir, "version.txt").apply { writeText(stamp) }
        // the Java it runs on: an older copy's updater fetches runtime.zip when its own Java is older than this
        val javaVersion = File(workDir, "java-version.txt").apply { writeText(Runtime.version().feature().toString()) }
        val runtimeZip = File(workDir, "runtime.zip")
        log("Zipping Java (for copies that still have an older one)...")
        zipDir(File(image, "runtime"), runtimeZip)
        val assets = mutableListOf(appZip.path, version.path, javaVersion.path, runtimeZip.path)
        if (includeFull) {
            val full = File(workDir, "UzisMapEditor-full.zip")
            log("Zipping the full download...")
            zipDir(image, full)
            log("Full download: %.0f MB".format(full.length() / 1048576.0))
            assets.add(full.path)
        }
        val ok = run(listOf("gh", "release", "create", "v$stamp") + assets + listOf(
            "--repo", repo, "--latest", "--title", "Uzi's Map Editor $stamp", "--notes", notes.ifBlank { "Update $stamp" })).first == 0
        if (ok) {
            log("Published v$stamp. Everyone gets it the next time they open the editor.")
            if (includeFull) log("Download link for friends: $base/latest/download/UzisMapEditor-full.zip")
        }
        return ok
    }

    private fun zipDir(dir: File, zip: File) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            var n = 0
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putNextEntry(ZipEntry(dir.name + "/" + f.relativeTo(dir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
                if (++n % 500 == 0) log("  $n files zipped...")
            }
        }
    }
}
