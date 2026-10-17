package utils

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs at start-up in the packaged app (while the splash is showing):
 *  - a newer editor on the downloads page -> download it, restart into it
 *  - a newer map -> replace map\cache (your own edits in map\cache-out are kept)
 * Anything that goes wrong just starts the version you have.
 */
object Updater {
    private val appDir: File? = System.getProperty("ume.appdir")?.let { File(it) }?.takeIf { it.isDirectory }

    /** The folder with the exe (app\ and map\ are inside it); null when running from source. */
    val installDir: File? get() = appDir?.parentFile

    // JavaFX loading window (no AWT: starting AWT stops JavaFX's folder picker from opening on Windows)
    private var splashStage: javafx.stage.Stage? = null
    private var splashLabel: javafx.scene.control.Label? = null

    /** Show the loading window. Call on the JavaFX thread. */
    fun showSplash() {
        val img = Updater::class.java.getResourceAsStream("/splash.png")?.use { javafx.scene.image.Image(it) } ?: return
        val label = javafx.scene.control.Label("Starting...").apply {
            style = "-fx-text-fill: #ff981f; -fx-font-family: Verdana; -fx-font-weight: bold; -fx-font-size: 13px;" +
                    "-fx-effect: dropshadow(one-pass-box, black, 0, 0, 1, 1);"
        }
        val pane = javafx.scene.layout.StackPane(javafx.scene.image.ImageView(img), label).apply {
            javafx.scene.layout.StackPane.setAlignment(label, javafx.geometry.Pos.BOTTOM_LEFT)
            javafx.scene.layout.StackPane.setMargin(label, javafx.geometry.Insets(0.0, 0.0, 22.0, 24.0))
        }
        val stage = javafx.stage.Stage(javafx.stage.StageStyle.UNDECORATED)
        stage.scene = javafx.scene.Scene(pane)
        Updater::class.java.getResource("/icon-64.png")?.let { stage.icons.add(javafx.scene.image.Image(it.toExternalForm())) }
        stage.title = "Uzi's Map Editor"
        stage.centerOnScreen()
        stage.show()
        splashStage = stage
        splashLabel = label
    }

    fun splashText(text: String) {
        println(text)
        val l = splashLabel ?: return
        if (javafx.application.Platform.isFxApplicationThread()) l.text = text
        else javafx.application.Platform.runLater { l.text = text }
    }

    fun closeSplash() {
        val st = splashStage ?: return
        splashStage = null
        if (javafx.application.Platform.isFxApplicationThread()) st.close()
        else javafx.application.Platform.runLater { st.close() }
    }

    private fun fetchText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000; c.instanceFollowRedirects = true
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText().trim() else null
    } catch (e: Exception) { null }

    private fun download(url: String, to: File, label: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) false else {
            val total = c.contentLengthLong
            var done = 0L
            var lastShown = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastShown && (pct < 0 || pct % 5 == 0)) {
                            lastShown = pct
                            splashText(if (pct >= 0) "$label $pct%" else "$label ${done / 1048576} MB")
                        }
                    }
                }
            }
            true
        }
    } catch (e: Exception) { false }

    private fun unzip(zip: File, dest: File) {
        java.util.zip.ZipFile(zip).use { z ->
            for (e in z.entries()) {
                val f = File(dest, e.name)
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile.mkdirs()
                    z.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
                }
            }
        }
    }

    /** Returns true if the app is restarting into a new version (caller should exit). */
    val logDir: File get() = File(System.getProperty("user.home"), ".uzis-map-editor").apply { mkdirs() }

    fun checkAndUpdate(): Boolean {
        if (System.getProperty("ume.noupdate") == "true") return false
        val dir = appDir ?: return false
        println("Update check: app folder ${dir.path}")
        val install = dir.parentFile
        val urlFile = File(dir, "update-url.txt")
        if (!urlFile.isFile) return false
        // accept both ".../releases" and older ".../releases/latest/download"
        val base = urlFile.readText().trim().removeSuffix("/").removeSuffix("/latest/download")
        splashText("Checking for updates...")

        // ---- map ----
        try {
            val mapDir = File(install, "map")
            val remoteMap = fetchText("$base/download/map/map-version.txt")
            val localMap = File(mapDir, "map-version.txt").takeIf { it.isFile }?.readText()?.trim()
            if (remoteMap != null && remoteMap != localMap) {
                val tmp = File.createTempFile("ume-map", ".zip")
                if (download("$base/download/map/map.zip", tmp, "Downloading the new map...")) {
                    splashText("Installing the new map...")
                    val cache = File(mapDir, "cache")
                    val staging = File(mapDir, "cache.new").apply { deleteRecursively(); mkdirs() }
                    unzip(tmp, staging)
                    val dat = staging.walkTopDown().firstOrNull { it.name == "main_file_cache.dat2" }
                    if (dat != null) {
                        cache.deleteRecursively()
                        dat.parentFile.copyRecursively(cache, true)
                        staging.walkTopDown().firstOrNull { it.name == "xteas.json" }?.copyTo(File(mapDir, "xteas.json"), true)
                        File(mapDir, "map-version.txt").writeText(remoteMap)
                    }
                    staging.deleteRecursively()
                }
                tmp.delete()
            }
        } catch (e: Exception) {
            println("Map update skipped: ${e.message}")
        }

        // ---- editor ----
        val remote = fetchText("$base/latest/download/version.txt")
        val local = File(dir, "version.txt").takeIf { it.isFile }?.readText()?.trim()
        println("Update check: this is $local, newest is ${remote ?: "unknown (offline?)"}")
        if (remote == null || remote == local) return false
        val exe = ProcessHandle.current().info().command().orElse(null)?.let { File(it) }
            ?.takeIf { it.name.endsWith(".exe", true) && !it.name.startsWith("java", true) }
            ?: File(install, "${AppImage.APP_NAME}.exe")
        val zip = File.createTempFile("ume-app", ".zip")
        if (!download("$base/latest/download/app.zip", zip, "Downloading update $remote...")) {
            println("Update download failed, starting $local")
            return false
        }
        // a build that needs a newer Java than the one in runtime\ would fail to start ("Failed to launch JVM"):
        // bring the Java it was built with too
        val needJava = fetchText("$base/latest/download/java-version.txt")?.trim()?.toIntOrNull()
        val haveJava = Runtime.version().feature()
        var runtimeZip: File? = null
        if (needJava != null && needJava > haveJava) {
            val rz = File.createTempFile("ume-runtime", ".zip")
            if (!download("$base/latest/download/runtime.zip", rz, "Downloading Java $needJava for update $remote...")) {
                println("Update $remote needs Java $needJava (this has $haveJava) and its Java couldn't be downloaded, starting $local")
                zip.delete(); rz.delete()
                return false
            }
            runtimeZip = rz
        }
        println("Downloaded ${zip.length()} bytes, restarting into $remote")
        splashText("Installing update $remote...")
        // the running app holds its own files, so a small script swaps them after we exit
        val script = File.createTempFile("ume-update", ".ps1")
        script.writeText("""
            param([int]${'$'}ProcId, [string]${'$'}Zip, [string]${'$'}AppDir, [string]${'$'}Exe, [string]${'$'}Log, [string]${'$'}RuntimeZip = '')
            Start-Transcript -Path ${'$'}Log -Force | Out-Null
            ${'$'}ProgressPreference = 'SilentlyContinue'
            try {
                "Waiting for the editor to close (pid ${'$'}ProcId)..."
                try { Wait-Process -Id ${'$'}ProcId -Timeout 60 -ErrorAction SilentlyContinue } catch {}
                Start-Sleep -Milliseconds 500
                ${'$'}tmp = Join-Path ${'$'}env:TEMP ('ume-new-' + [guid]::NewGuid())
                "Unpacking ${'$'}Zip"
                Expand-Archive ${'$'}Zip ${'$'}tmp -Force
                ${'$'}src = Join-Path ${'$'}tmp 'app'
                if (Test-Path (Join-Path ${'$'}src 'uzis-map-editor.jar')) {
                    if (${'$'}RuntimeZip -ne '') {
                        "Replacing Java"
                        ${'$'}rt = Join-Path (Split-Path ${'$'}AppDir -Parent) 'runtime'
                        ${'$'}rtmp = Join-Path ${'$'}env:TEMP ('ume-rt-' + [guid]::NewGuid())
                        Expand-Archive ${'$'}RuntimeZip ${'$'}rtmp -Force
                        ${'$'}rsrc = Join-Path ${'$'}rtmp 'runtime'
                        if (-not (Test-Path (Join-Path ${'$'}rsrc 'bin'))) { throw "the Java download is incomplete" }
                        if (Test-Path (${'$'}rt + '.old')) { Remove-Item (${'$'}rt + '.old') -Recurse -Force }
                        Rename-Item ${'$'}rt (${'$'}rt + '.old')
                        Copy-Item ${'$'}rsrc ${'$'}rt -Recurse -Force
                        Remove-Item (${'$'}rt + '.old') -Recurse -Force -ErrorAction SilentlyContinue
                        Remove-Item ${'$'}rtmp -Recurse -Force -ErrorAction SilentlyContinue
                    }
                    "Replacing ${'$'}AppDir"
                    ${'$'}backup = ${'$'}AppDir + '.old'
                    if (Test-Path ${'$'}backup) { Remove-Item ${'$'}backup -Recurse -Force }
                    Copy-Item ${'$'}AppDir ${'$'}backup -Recurse -Force
                    Get-ChildItem ${'$'}AppDir | Remove-Item -Recurse -Force
                    Copy-Item (Join-Path ${'$'}src '*') ${'$'}AppDir -Recurse -Force
                    Remove-Item ${'$'}backup -Recurse -Force -ErrorAction SilentlyContinue
                    "Update installed."
                } else { "The download doesn't look like an editor update, keeping the current one." }
                Remove-Item ${'$'}tmp -Recurse -Force -ErrorAction SilentlyContinue
            } catch {
                "Update failed: ${'$'}(${'$'}_.Exception.Message)"
                ${'$'}rt = Join-Path (Split-Path ${'$'}AppDir -Parent) 'runtime'
                if ((Test-Path (${'$'}rt + '.old')) -and -not (Test-Path (Join-Path ${'$'}rt 'bin'))) {
                    "Putting the previous Java back"
                    if (Test-Path ${'$'}rt) { Remove-Item ${'$'}rt -Recurse -Force -ErrorAction SilentlyContinue }
                    Rename-Item (${'$'}rt + '.old') ${'$'}rt
                }
                ${'$'}backup = ${'$'}AppDir + '.old'
                if ((Test-Path ${'$'}backup) -and -not (Test-Path (Join-Path ${'$'}AppDir 'uzis-map-editor.jar'))) {
                    "Putting the previous version back"
                    Get-ChildItem ${'$'}AppDir | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
                    Copy-Item (Join-Path ${'$'}backup '*') ${'$'}AppDir -Recurse -Force
                }
            } finally {
                Remove-Item ${'$'}Zip -Force -ErrorAction SilentlyContinue
                if (${'$'}RuntimeZip -ne '') { Remove-Item ${'$'}RuntimeZip -Force -ErrorAction SilentlyContinue }
                "Starting ${'$'}Exe"
                Start-Process ${'$'}Exe
                Stop-Transcript | Out-Null
            }
        """.trimIndent())
        ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", script.path, "-ProcId", ProcessHandle.current().pid().toString(),
            "-Zip", zip.path, "-AppDir", dir.path, "-Exe", exe.path, "-Log", File(logDir, "update.log").path, "-RuntimeZip", runtimeZip?.path ?: "").start()
        return true
    }
}
