package utils

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Pushes cache-out to the live server over SSH (Windows' built-in ssh/scp).
 * Needs key login: BatchMode is on, so a password prompt fails instead of hanging.
 */
class Deployer(private val log: (String) -> Unit) {

    private val cfg = GadTools.templates()
    private val host = cfg.deployHost!!
    private val port = (cfg.deployPort ?: 22).toString()
    private val user = cfg.deployUser!!
    private val remoteCache = cfg.deployRemoteCache!!.trimEnd('/')
    private val service = cfg.deployService!!
    private val sudo = if (user == "root") "" else "sudo -n "
    private val remoteData = cfg.deployRemoteData!!.trimEnd('/')

    private fun sshOpts(portFlag: String): List<String> {
        val opts = mutableListOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ConnectTimeout=15", portFlag, port)
        val key = cfg.deployKey?.trim().orEmpty()
        if (key.isNotEmpty()) { opts.add("-i"); opts.add(key) }
        return opts
    }

    private fun exec(cmd: List<String>): Int {
        log("> " + cmd.joinToString(" "))
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { log("  $it") }
            p.waitFor()
        } catch (e: Exception) {
            log("  failed to run: ${e.message}")
            -1
        }
    }

    private fun ssh(remote: String): Int = exec(listOf("ssh") + sshOpts("-p") + listOf("$user@$host", remote))

    fun test(): Boolean {
        log("Testing $user@$host:$port ...")
        val ok = ssh("echo connected && ${sudo}systemctl is-active $service; ls -la $remoteCache | head -30") == 0
        log(if (ok) "Connection OK. Check that the listing above shows main_file_cache.dat2." else
            "Connection failed. Key login is needed (see the setup steps).")
        return ok
    }

    fun deploy(): Boolean {
        val local = GadTools.exportCacheDir
        val files = local.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.sortedBy { it.name }
        if (files.isNullOrEmpty() || files.none { it.name == "main_file_cache.dat2" }) {
            log("Nothing to deploy: ${local.path} has no cache. Export first.")
            return false
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val staging = "/tmp/gadscape-deploy-$stamp"
        val backup = "${remoteCache}-backups/$stamp"
        val mb = files.sumByDouble { it.length() / 1048576.0 }
        log("Deploying ${files.size} files (%.0f MB) to $host".format(mb))

        if (ssh("mkdir -p $staging && ${sudo}test -f $remoteCache/main_file_cache.dat2 && echo found-live-cache") != 0) {
            log("Could not reach the server or $remoteCache/main_file_cache.dat2 does not exist. Stopping.")
            return false
        }

        log("Uploading (the big .dat2 takes a while)...")
        val scp = listOf("scp") + sshOpts("-P") + files.map { it.absolutePath } + listOf("$user@$host:$staging/")
        if (exec(scp) != 0) {
            log("Upload failed. The live server was not touched.")
            ssh("rm -rf $staging")
            return false
        }

        log("Swapping cache and restarting $service...")
        val swap = listOf(
            "set -e",
            "${sudo}mkdir -p $backup",
            "${sudo}cp -p $remoteCache/main_file_cache.* $backup/",
            "${sudo}systemctl stop $service",
            "${sudo}cp -f $staging/main_file_cache.* $remoteCache/",
            "${sudo}systemctl start $service",
            "rm -rf $staging",
            // keep the 5 newest backups
            "ls -1dt ${remoteCache}-backups/*/ 2>/dev/null | tail -n +6 | xargs -r ${sudo}rm -rf",
            "sleep 3",
            "${sudo}systemctl is-active $service"
        ).joinToString(" && ")
        val ok = ssh(swap) == 0
        if (ok) {
            log("Done. Live cache replaced; previous one saved in $backup")
        } else {
            log("Swap step failed. If the server is down, run Rollback (it restores $backup).")
        }
        lastBackup = backup
        return ok
    }

    // ---------------- server jar (shops and other server code) ----------------

    private fun capture(cmd: List<String>, dir: File? = null, env: Map<String, String> = emptyMap(), echo: Boolean = true): Pair<Int, String> {
        log("> " + cmd.joinToString(" "))
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (dir != null) pb.directory(dir)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = StringBuilder()
            p.inputStream.bufferedReader().forEachLine { out.appendln(it); if (echo) log("  $it") }
            Pair(p.waitFor(), out.toString())
        } catch (e: Exception) {
            log("  failed to run: ${e.message}")
            Pair(-1, "")
        }
    }

    /** A JDK that can compile (the server needs javac), preferring JAVA_HOME. */
    private fun jdkHome(): String? = listOfNotNull(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
        .firstOrNull { File(it, "bin/javac.exe").isFile || File(it, "bin/javac").isFile }

    /** Build the server on this PC (gradlew :app:shadowJar) and return the jar. */
    fun buildServerJar(serverDir: File): File? {
        val gradlew = File(serverDir, if (File(serverDir, "gradlew.bat").isFile) "gradlew.bat" else "gradlew")
        if (!gradlew.isFile) { log("No gradlew in ${serverDir.path}. Pick the server folder in Tools > Shops."); return null }
        val jdk = jdkHome() ?: run { log("No JDK found to build the server. Set JAVA_HOME to your JDK 21."); return null }
        log("Building the server (this takes a few minutes the first time)...")
        val started = System.currentTimeMillis()
        val cmd = if (gradlew.name.endsWith(".bat")) listOf("cmd", "/c", gradlew.name, ":app:shadowJar", "--console=plain")
                  else listOf("sh", gradlew.name, ":app:shadowJar", "--console=plain")
        val (code, _) = capture(cmd, serverDir, mapOf("JAVA_HOME" to jdk))
        if (code != 0) { log("Server build failed (see above). Nothing was uploaded."); return null }
        val jar = File(serverDir, "app/build/libs").listFiles { f -> f.extension == "jar" }
            ?.filter { it.lastModified() >= started - 60_000 }
            ?.sortedWith(compareByDescending<File> { it.name.contains("all") }.thenByDescending { it.length() })
            ?.firstOrNull()
        if (jar == null) { log("Build finished but no new jar in app/build/libs."); return null }
        log("Built ${jar.name} (%.0f MB)".format(jar.length() / 1048576.0))
        return jar
    }

    /** How the live server runs: from a jar we can replace, or from source it builds itself. */
    sealed class ServerMode {
        data class Jar(val path: String) : ServerMode()
        data class Source(val root: String) : ServerMode()
    }

    /**
     * Work out how the server is started: the deployJar setting, else the service's start command,
     * else the script it runs (start.sh), else the java process that is running right now.
     */
    fun detectServer(): ServerMode? {
        cfg.deployJar?.trim()?.takeIf { it.isNotEmpty() }?.let { return ServerMode.Jar(it) }
        val probe = listOf(
            "echo '#SERVICE'; ${sudo}systemctl cat $service",
            "echo '#PROCS'; for p in \$(pgrep -f java); do echo \"PID \$p CWD \$(readlink /proc/\$p/cwd)\"; tr '\\0' ' ' < /proc/\$p/cmdline; echo; done"
        ).joinToString("; ")
        val (code, out) = capture(listOf("ssh") + sshOpts("-p") + listOf("$user@$host", probe), echo = false)
        if (code != 0) { log("Couldn't look at the server."); return null }
        val service = out.substringAfter("#SERVICE").substringBefore("#PROCS")
        val procs = out.substringAfter("#PROCS")
        val exec = service.lines().lastOrNull { it.trim().startsWith("ExecStart=") }?.substringAfter("=")?.trim() ?: ""
        val workDir = service.lines().lastOrNull { it.trim().startsWith("WorkingDirectory=") }?.substringAfter("=")?.trim()
        fun resolve(path: String, base: String?) = if (path.startsWith("/") || base == null) path else "${base.trimEnd('/')}/$path"
        fun jarIn(cmd: String): String? {
            val t = cmd.trim().split(Regex("\\s+"))
            val i = t.indexOf("-jar")
            return if (i >= 0) t.getOrNull(i + 1) else null
        }
        log("Service starts: $exec")

        // 1. the service runs java -jar directly
        jarIn(exec)?.let { return ServerMode.Jar(resolve(it, workDir)) }

        // 2. the service runs a script: read it
        val script = exec.split(Regex("\\s+")).firstOrNull { it.endsWith(".sh") || it.startsWith("/") && !it.endsWith("java") }
        var scriptText = ""
        if (script != null) {
            val (c2, text) = capture(listOf("ssh") + sshOpts("-p") + listOf("$user@$host", "${sudo}cat ${resolve(script, workDir)}"), echo = false)
            if (c2 == 0) { scriptText = text; log("--- ${script} ---"); text.lines().take(60).forEach { log("  $it") }; log("---") }
        }
        val scriptDir = script?.let { resolve(it, workDir).substringBeforeLast('/') }
        val cdDir = Regex("""(?m)^\s*cd\s+"?([^"\s;&]+)""").findAll(scriptText).map { it.groupValues[1] }.lastOrNull()
            ?.replace("\$(dirname \"\$0\")", scriptDir ?: "")?.replace("\$(dirname \$0)", scriptDir ?: "")
        val base = cdDir?.let { resolve(it, scriptDir) } ?: workDir ?: scriptDir

        // 3. the running java process: the most reliable answer
        val gameProc = procs.lines().windowed(2, 1).map { it[0] to it[1] }
            .filter { it.first.startsWith("PID ") && !it.second.contains("pgrep") && !it.second.contains("GradleDaemon") && !it.second.contains("GradleWrapperMain") && !it.second.contains("kotlin-daemon") }
            .maxBy { it.second.length }
        if (gameProc != null) {
            val cwd = gameProc.first.substringAfter("CWD ").trim()
            val cmd = gameProc.second
            log("Running server process: ${cmd.take(300)}")
            jarIn(cmd)?.let { return ServerMode.Jar(resolve(it, cwd)) }
            // started by gradle (run task): the classpath points into the source tree's build folders
            Regex("""(/[^:\s]+?)/(?:app|plugins)/build/""").find(cmd)?.let { return ServerMode.Source(it.groupValues[1]) }
        }
        // 4. the script itself
        jarIn(scriptText.lines().firstOrNull { it.contains("-jar") } ?: "")?.let { j ->
            val vars = Regex("""(?m)^\s*(\w+)=["']?([^"'\s]+)""").findAll(scriptText).associate { it.groupValues[1] to it.groupValues[2] }
            var path = j.trim('"', '\'')
            vars.forEach { (k, v) -> path = path.replace("\${$k}", v).replace("\$$k", v) }
            if (!path.contains("$")) return ServerMode.Jar(resolve(path, base))
        }
        if (scriptText.contains("gradlew") && base != null) return ServerMode.Source(base)
        log("Couldn't tell how the server is started. Paste the lines above to fix it, or set deployJar in the Settings file.")
        return null
    }

    /**
     * Compile just the shops module on this PC and copy the classes of the edited shops into a folder
     * laid out like the jar (com/gadscape/plugins/shops/...). Returns that folder, or null.
     */
    fun buildShopClasses(serverDirGuess: File): File? {
        val pending = pendingShops()
        if (pending.isEmpty()) { log("No edited shop or NPC spawn files yet. Save a shop (Tools > Shops) or your NPC spawns first."); return null }
        // entries are full paths (newer), paths from the server folder, or just a shop file name (oldest)
        // older entries are relative to whichever folder was picked back then: try every folder the server could be in
        val roots = listOfNotNull(serverDirGuess, NpcSpawns.serverDir,
            File(System.getProperty("user.home"), "Downloads/RSPS-NEAR-REALITY-main/RSPS-NEAR-REALITY-main/near-reality-server-main"),
            File("C:/GadScape")).filter { it.isDirectory }.distinct()
        val deep = roots.flatMap { r -> r.walkTopDown().maxDepth(3).filter { it.isDirectory && (File(it, "gradlew.bat").isFile || File(it, "gradlew").isFile) }.toList() }
        val allRoots = (roots + deep).distinct()
        fun resolve(e: String): File? = File(e).takeIf { it.isAbsolute && it.isFile }
            ?: allRoots.asSequence().map { File(it, e) }.firstOrNull { it.isFile }
            ?: allRoots.asSequence().map { File(it, "${ServerSource.shopsPath(it)}/$e") }.firstOrNull { it.isFile }
        val missing = pending.filter { resolve(it) == null }
        // kept, not thrown away: they may turn up when the right folder is picked
        if (missing.isNotEmpty()) log("Can't find ${missing.size} edited file(s) on this PC: ${missing.joinToString(", ")} (looked in ${allRoots.joinToString { it.path }})")
        // store what was found with its full path from now on
        val found = pending.associateWith { resolve(it) }.filterValues { it != null }
        if (found.isNotEmpty()) { forgetPending(found.keys); found.values.forEach { markShopEdited(it!!.absolutePath) } }
        val sources = pending.mapNotNull { resolve(it) }.distinct()
        if (sources.isEmpty()) { log("None of the edited files are on this PC any more. Save the shop / NPC spawns again."); return null }
        // the server to compile is the gradle project the files live in, wherever that is
        val serverDir = generateSequence(sources.first().parentFile) { it.parentFile }
            .firstOrNull { (File(it, "gradlew.bat").isFile || File(it, "gradlew").isFile) && (File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile) }
            ?: run { log("Couldn't find the server's gradle project above ${sources.first().path}."); return null }
        log("Server project: ${serverDir.path}")
        log("Files to send: ${sources.joinToString(", ") { it.nameWithoutExtension }}")

        val gradlew = File(serverDir, if (File(serverDir, "gradlew.bat").isFile) "gradlew.bat" else "gradlew")
        val jdk = jdkHome() ?: run { log("No JDK found to compile the shops. Set JAVA_HOME to your JDK 21."); return null }
        // each file belongs to a gradle module (plugins/shops, core-model, ...): compile those modules only
        fun moduleOf(f: File) = f.relativeTo(serverDir).invariantSeparatorsPath.substringBefore("/src/main/")
        val modules = sources.map { moduleOf(it) }.distinct()
        log("Compiling ${modules.joinToString(", ")}...")
        val tasks = modules.map { ":" + it.replace('/', ':') + ":classes" }
        val cmd = (if (gradlew.name.endsWith(".bat")) listOf("cmd", "/c", gradlew.name) else listOf("sh", gradlew.name)) + tasks + "--console=plain"
        val compileStart = System.currentTimeMillis() - 2000
        val (code, _) = capture(cmd, serverDir, mapOf("JAVA_HOME" to jdk))
        if (code != 0) { log("It doesn't compile (see above). Nothing was sent."); return null }

        val stage = File(System.getProperty("java.io.tmpdir"), "ume-shops-${System.currentTimeMillis()}")
        var n = 0
        val copied = HashSet<String>()
        fun put(root: File, f: File) {
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (copied.add(rel)) { f.copyTo(File(stage, rel), true); n++ }
        }
        // a module compiles into kotlin/main AND java/main: look in both
        fun roots(module: String) = listOf("$module/build/classes/kotlin/main", "$module/build/classes/java/main")
            .map { File(serverDir, it) }.filter { it.isDirectory }
        val declRe = Regex("""(?m)^\s*(?:(?:public|private|protected|internal|final|abstract|open|sealed|static|data|enum|annotation|inner|value|strictfp)\s+)*(?:class|object|interface|enum|record|@interface)\s+(\w+)""")
        for (src in sources) {
            val text = src.readText()
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)?.replace('.', '/') ?: ""
            val names = (declRe.findAll(text).map { it.groupValues[1] } +
                    sequenceOf(src.nameWithoutExtension, src.nameWithoutExtension + "Kt")).toSet()
            var found = 0
            for (root in roots(moduleOf(src))) {
                for (c in names) File(root, pkg).listFiles { f -> f.name == "$c.class" || f.name.startsWith("$c$") }?.forEach { put(root, it); found++ }
            }
            if (found == 0) log("Warning: no compiled classes found for ${src.name}, it is skipped (the other files still go).")
        }
        // Java copies constants into the classes that use them; gradle recompiles those, so send everything it just rebuilt
        for (module in modules) for (root in roots(module)) root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && it.lastModified() >= compileStart }.forEach { put(root, it) }
        if (n == 0) { log("Nothing compiled to send."); return null }
        log("$n class file(s) ready.")
        return stage
    }

    /** Runs on the server: rebuild server.jar with only the given classes replaced. */
    private val patchScript = """
import zipfile, sys, os
jar, classes, out = sys.argv[1], sys.argv[2], sys.argv[3]
repl = {}
for root, _, files in os.walk(classes):
    for f in files:
        p = os.path.join(root, f)
        repl[os.path.relpath(p, classes).replace(os.sep, '/')] = p
# drop the old classes of each edited shop (including its inner/lambda classes)
prefixes = set()
for name in repl:
    base = name.rsplit('.class', 1)[0].split('$')[0]
    prefixes.add(base)
zin = zipfile.ZipFile(jar)
names = zin.namelist()
pkgs = set(n.rsplit('/', 1)[0] + '/' for n in repl)
missing = [p for p in pkgs if not any(x.startswith(p) for x in names)]
if missing:
    print('NOT_IN_JAR ' + ' '.join(missing)); sys.exit(3)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
dropped = 0
for item in zin.infolist():
    n = item.filename
    if n.endswith('.class') and (n.rsplit('.class', 1)[0].split('$')[0] in prefixes):
        dropped += 1
        continue
    zout.writestr(item, zin.read(n))
for name, p in sorted(repl.items()):
    zout.write(p, name)
zout.close()
zipfile.ZipFile(out).testzip()
print('PATCHED replaced=%d added=%d' % (dropped, len(repl)))
""".trimIndent()

    /** Upload the shop .kt files into the server's source tree (for servers that build themselves on start). */
    private fun shopSources(serverDir: File): List<File> =
        File(serverDir, ServerSource.shopsPath(serverDir)).listFiles { f -> f.extension == "kt" }?.toList() ?: emptyList()

    /**
     * One upload, one restart: the map (cache-out) and/or a new server jar.
     * Both are backed up on the server first; Rollback restores whatever was replaced.
     */
    fun deployBundle(includeMap: Boolean, shopClasses: File?, serverDir: File? = null): Boolean {
        val jar: File? = shopClasses
        val cacheFiles = if (includeMap) GadTools.exportCacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.sortedBy { it.name } ?: emptyList() else emptyList()
        if (includeMap && cacheFiles.none { it.name == "main_file_cache.dat2" }) { log("No saved map in ${GadTools.exportCacheDir.path}. Save first."); return false }
        if (!includeMap && jar == null) { log("Nothing to deploy."); return false }
        val mode = if (jar != null) detectServer() ?: return false else null
        // the live jar is never replaced wholesale: only the edited shop classes inside it are swapped
        val remoteJar: String? = (mode as? ServerMode.Jar)?.path
        val sourceRoot = (mode as? ServerMode.Source)?.root
        // only the shop files you edited and saved in the shop editor; every other server file stays as it is live
        val pending = pendingShops()
        val shopFiles = if (sourceRoot != null && serverDir != null) shopSources(serverDir).filter { it.name in pending } else emptyList()
        if (sourceRoot != null && shopFiles.isEmpty() && remoteJar == null) {
            log("No edited shops to send (save a shop in Tools > Shops first).")
            if (!includeMap) return false
        }
        if (shopFiles.isNotEmpty()) log("Shops to send: ${shopFiles.joinToString(", ") { it.name }}")
        if (remoteJar != null) log("The server runs $remoteJar: only the edited shop classes inside it will be swapped.")
        if (sourceRoot != null) log("The server builds itself from $sourceRoot: your ${shopFiles.size} shop files go into its source, then it restarts and rebuilds.")
        val remoteShops = sourceRoot?.takeIf { shopFiles.isNotEmpty() }?.let { "$it/${ServerSource.shopsPath(serverDir)}" }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val staging = "/tmp/gadscape-deploy-$stamp"
        val backup = "${remoteCache}-backups/$stamp"
        if (ssh("mkdir -p $staging/shops" + (if (remoteJar != null) " && ${sudo}test -f $remoteJar && echo found-live-jar" else "") +
                    (if (remoteShops != null) " && ${sudo}test -d $remoteShops && echo found-shop-sources" else "") +
                    (if (includeMap) " && ${sudo}test -f $remoteCache/main_file_cache.dat2 && echo found-live-cache" else "")) != 0) {
            log("Could not reach the server, or the live files are not where expected. Stopping.")
            return false
        }
        val upload = cacheFiles
        log("Uploading ${upload.size + shopFiles.size} file(s) (%.0f MB)...".format((upload + shopFiles).sumByDouble { it.length() / 1048576.0 }))
        if (upload.isNotEmpty() && exec(listOf("scp") + sshOpts("-P") + upload.map { it.absolutePath } + listOf("$user@$host:$staging/")) != 0) {
            log("Upload failed. The live server was not touched.")
            ssh("rm -rf $staging"); return false
        }
        if (remoteJar != null && shopClasses != null) {
            val script = File(shopClasses, "patch_jar.py").apply { writeText(patchScript) }
            val tops = shopClasses.listFiles { f -> f.isDirectory }?.map { it.absolutePath } ?: emptyList()
            if (exec(listOf("scp") + sshOpts("-P") + listOf("-r") + tops + listOf(script.absolutePath, "$user@$host:$staging/shops/")) != 0) {
                log("Upload of shop classes failed. The live server was not touched.")
                ssh("rm -rf $staging"); return false
            }
            // build the patched jar next to the live one and check it before anything is stopped
            if (ssh("cd $staging/shops && mkdir -p classes && for d in */; do [ \"\$d\" != \"classes/\" ] && mv \"\$d\" classes/; done; command -v python3 >/dev/null && " +
                        "python3 patch_jar.py $remoteJar $staging/shops/classes $staging/server.jar.new") != 0) {
                log("Couldn't build the patched server jar (needs python3 on the server). The live server was not touched.")
                ssh("rm -rf $staging"); return false
            }
        }
        if (shopFiles.isNotEmpty() && exec(listOf("scp") + sshOpts("-P") + shopFiles.map { it.absolutePath } + listOf("$user@$host:$staging/shops/")) != 0) {
            log("Upload of shop files failed. The live server was not touched.")
            ssh("rm -rf $staging"); return false
        }
        val steps = mutableListOf("set -e", "${sudo}mkdir -p $backup")
        if (includeMap) steps.add("${sudo}cp -p $remoteCache/main_file_cache.* $backup/")
        if (remoteJar != null) { steps.add("${sudo}cp -p $remoteJar $backup/server.jar"); steps.add("echo $remoteJar | ${sudo}tee $backup/jar-path.txt >/dev/null") }
        if (remoteShops != null) {
            steps.add("${sudo}mkdir -p $backup/shops")
            shopFiles.forEach { steps.add("if [ -f $remoteShops/${it.name} ]; then ${sudo}cp -p $remoteShops/${it.name} $backup/shops/; fi") }
            steps.add("echo $remoteShops | ${sudo}tee $backup/shops-path.txt >/dev/null")
        }
        steps.add("${sudo}systemctl stop $service")
        if (remoteShops != null) shopFiles.forEach { steps.add("${sudo}cp -f $staging/shops/${it.name} $remoteShops/") }
        if (includeMap) steps.add("${sudo}cp -f $staging/main_file_cache.* $remoteCache/")
        if (remoteJar != null) steps.add("${sudo}cp -f $staging/server.jar.new $remoteJar")
        steps.addAll(listOf("${sudo}systemctl start $service", "rm -rf $staging",
            "ls -1dt ${remoteCache}-backups/*/ 2>/dev/null | tail -n +6 | xargs -r ${sudo}rm -rf",
            "sleep ${if (remoteShops != null) 20 else 5}", "${sudo}systemctl is-active $service"))
        log("Swapping ${listOfNotNull(if (includeMap) "map" else null, if (remoteJar != null) "shops (inside server.jar)" else null, if (remoteShops != null) "shops" else null).joinToString(" + ")} and restarting $service...")
        if (remoteShops != null) log("(The server rebuilds itself while starting; it can take a minute or two before players can log in.)")
        val ok = ssh(steps.joinToString(" && ")) == 0
        log(if (ok) "Done. Previous version saved in $backup (Rollback restores it)." else "Swap failed. If the server is down, run Rollback.")
        if (ok && remoteJar != null && shopClasses != null) {
            val names = shopClasses.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.relativeTo(shopClasses).invariantSeparatorsPath }.take(6).toList()
            ssh("python3 -c 'import zipfile,sys; w=set(sys.argv[2:]); [print(i.filename, i.date_time) for i in zipfile.ZipFile(sys.argv[1]).infolist() if i.filename in w]' " +
                    remoteJar + " " + names.joinToString(" ") { "'$it'" })
        }
        if (ok && (shopFiles.isNotEmpty() || remoteJar != null)) clearPendingShops()
        lastBackup = backup
        return ok
    }

    // ---------------- server data (item stats, special attacks) ----------------

    /**
     * Sends the server data files saved in the editor (data/items/ItemDefinitions.json, special-attacks.json) to the
     * live server's data folder, and the exported cache too when [withCache]. The live files are backed up first and
     * the server is restarted once.
     */
    fun deployServerData(serverDir: File, withCache: Boolean): Boolean {
        val rels = ServerItems.pending().toList()
        val files = rels.map { it to File(ServerItems.dataDir(serverDir), it) }.filter { it.second.isFile }
        val cacheFiles = if (withCache) GadTools.exportCacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.sortedBy { it.name } ?: emptyList() else emptyList()
        if (withCache && cacheFiles.none { it.name == "main_file_cache.dat2" }) { log("No exported cache in ${GadTools.exportCacheDir.path}. Save first."); return false }
        if (files.isEmpty() && cacheFiles.isEmpty()) { log("No item stats waiting to be sent (save them in the Items tab first)."); return false }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val staging = "/tmp/gadscape-deploy-$stamp"
        val backup = "${remoteCache}-backups/$stamp"
        if (ssh("mkdir -p $staging/data && ${sudo}test -d $remoteData && echo found-data" +
                    (if (withCache) " && ${sudo}test -f $remoteCache/main_file_cache.dat2 && echo found-live-cache" else "")) != 0) {
            log("Could not reach the server, or $remoteData isn't there (set deployRemoteData in the Settings file). Stopping.")
            return false
        }
        log("Uploading ${files.size + cacheFiles.size} file(s)...")
        for ((rel, f) in files) {
            val dir = rel.substringBeforeLast('/', "")
            if (ssh("mkdir -p $staging/data/$dir") != 0 || exec(listOf("scp") + sshOpts("-P") + listOf(f.absolutePath, "$user@$host:$staging/data/$rel")) != 0) {
                log("Upload failed. The live server was not touched."); ssh("rm -rf $staging"); return false
            }
        }
        if (cacheFiles.isNotEmpty() && exec(listOf("scp") + sshOpts("-P") + cacheFiles.map { it.absolutePath } + listOf("$user@$host:$staging/")) != 0) {
            log("Upload failed. The live server was not touched."); ssh("rm -rf $staging"); return false
        }
        val steps = mutableListOf("set -e", "${sudo}mkdir -p $backup/data", "echo $remoteData | ${sudo}tee $backup/data-path.txt >/dev/null")
        for ((rel, _) in files) {
            val dir = rel.substringBeforeLast('/', "")
            steps.add("${sudo}mkdir -p $backup/data/$dir")
            steps.add("if [ -f $remoteData/$rel ]; then ${sudo}cp -p $remoteData/$rel $backup/data/$rel; fi")
        }
        if (withCache) steps.add("${sudo}cp -p $remoteCache/main_file_cache.* $backup/")
        steps.add("${sudo}systemctl stop $service")
        for ((rel, _) in files) {
            steps.add("${sudo}mkdir -p $remoteData/${rel.substringBeforeLast('/', "")}")
            steps.add("${sudo}cp -f $staging/data/$rel $remoteData/$rel")
        }
        if (withCache) steps.add("${sudo}cp -f $staging/main_file_cache.* $remoteCache/")
        steps.addAll(listOf("${sudo}systemctl start $service", "rm -rf $staging",
            "ls -1dt ${remoteCache}-backups/*/ 2>/dev/null | tail -n +6 | xargs -r ${sudo}rm -rf",
            "sleep 5", "${sudo}systemctl is-active $service"))
        log("Swapping ${rels.joinToString(", ")}${if (withCache) " + cache" else ""} and restarting $service...")
        val ok = ssh(steps.joinToString(" && ")) == 0
        log(if (ok) "Done. Previous version saved in $backup (Rollback restores it)." else "Swap failed. If the server is down, run Rollback.")
        if (ok) ServerItems.clearPending()
        lastBackup = backup
        return ok
    }

    /** Copies a file from the live server's data folder (e.g. items/ItemDefinitions.json) to [to]. */
    fun fetchServerData(rel: String, to: File): Boolean {
        to.parentFile.mkdirs()
        return exec(listOf("scp") + sshOpts("-P") + listOf("$user@$host:$remoteData/$rel", to.absolutePath)) == 0
    }

    fun rollback(): Boolean {
        val target = lastBackup
        val pick = target ?: "\$(ls -1dt ${remoteCache}-backups/*/ | head -n 1)"
        log("Rolling back to ${target ?: "newest backup on the server"}...")
        val cmd = listOf(
            "set -e",
            "B=$pick",
            "${sudo}systemctl stop $service",
            "if [ -f \$B/main_file_cache.dat2 ]; then ${sudo}cp -f \$B/main_file_cache.* $remoteCache/; fi",
            "if [ -f \$B/server.jar ]; then ${sudo}cp -f \$B/server.jar \$(cat \$B/jar-path.txt); fi",
            "if [ -d \$B/shops ]; then ${sudo}cp -f \$B/shops/*.kt \$(cat \$B/shops-path.txt)/; fi",
            "if [ -d \$B/data ]; then ${sudo}cp -rf \$B/data/. \$(cat \$B/data-path.txt)/; fi",
            "${sudo}systemctl start $service",
            "sleep 3",
            "${sudo}systemctl is-active $service",
            "echo restored from \$B"
        ).joinToString(" && ")
        return ssh(cmd) == 0
    }

    private fun pendingFile() = File(System.getProperty("user.home"), ".uzis-map-editor/shops-pending.txt")
    fun pendingShops(): Set<String> = pendingFile().takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    private fun clearPendingShops() { pendingFile().delete() }
    private fun forgetPending(gone: Set<String>) {
        val left = pendingShops() - gone
        if (left.isEmpty()) pendingFile().delete() else pendingFile().writeText(left.joinToString("\n"))
    }

    companion object {
        /** The shop editor calls this when a shop file is saved, so only edited shops get deployed. */
        fun markShopEdited(name: String) {
            val f = File(System.getProperty("user.home"), ".uzis-map-editor/shops-pending.txt")
            f.parentFile.mkdirs()
            val now = (if (f.isFile) f.readLines() else emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            now.add(name); f.writeText(now.joinToString("\n"))
        }
        @Volatile var lastBackup: String? = null
        fun settingsFile(): File = GadTools.templatesPath
    }
}
