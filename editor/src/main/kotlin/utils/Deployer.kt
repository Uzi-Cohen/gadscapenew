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
    /** The live server's cache folder: the deployRemoteCache setting, or found on the server ("auto", [findServerCache]). */
    private val remoteCache: String by lazy { resolveRemoteCache() }
    /** The live server's working folder, when [findServerCache] found it. */
    private var remoteServerDir: String? = null
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
        if (ok) remoteRevision()?.let { log("The server's cache ($remoteCache) is a ${revisionName(it)} cache.") }
        return ok
    }

    // ---------------- which cache folder, which format (build 241) ----------------

    private fun resolveRemoteCache(): String {
        val set = cfg.deployRemoteCache?.trim()?.trimEnd('/').orEmpty()
        if (set.isNotEmpty() && !set.equals(GadTools.AUTO, ignoreCase = true)) return set
        val found = findServerCache()
        if (found != null) { log("The server's cache folder: $found"); return found }
        log("Couldn't tell which cache folder the server uses; using ${GadTools.LEGACY_REMOTE_CACHE} (set deployRemoteCache in the Settings file to change it).")
        return GadTools.LEGACY_REMOTE_CACHE
    }

    /**
     * Finds the cache folder the live server reads: -Dgadscape.cache.dir of the running server process (the build 241
     * server is started with it), else of the service's start command or the script it runs, else the server's default,
     * cache/data/cache in its working folder. Null if none of that can be found.
     */
    private fun findServerCache(): String? {
        val probe = listOf(
            "echo '#SERVICE'; ${sudo}systemctl cat $service 2>/dev/null",
            "echo '#PROCS'; for p in \$(pgrep -f java); do echo \"PID \$p CWD \$(readlink /proc/\$p/cwd)\"; tr '\\0' ' ' < /proc/\$p/cmdline; echo; done",
            "echo '#END'"
        ).joinToString("; ")
        val (code, out) = capture(listOf("ssh") + sshOpts("-p") + listOf("$user@$host", probe), echo = false)
        if (code != 0) return null
        val (dir, serverDir) = serverCacheFrom(out)
        remoteServerDir = serverDir
        return dir
    }

    /** 241 or 211: the format of the cache in the server's cache folder (archive 24 = build 241), null if unknown. */
    private fun remoteRevision(): Int? {
        val f = "$remoteCache/main_file_cache.idx255"
        // idx255 entry 24 (bytes 144..149): a non-zero length means the cache has archive 24 (gamevals), i.e. build 241
        val cmd = "if ${sudo}test -f $f; then if [ \$(${sudo}stat -c %s $f) -ge 150 ] && [ \"\$(${sudo}od -An -tu1 -j144 -N3 $f | tr -d ' \\n')\" != \"000\" ]; " +
                "then echo GADSCAPE_REV=241; else echo GADSCAPE_REV=211; fi; else echo GADSCAPE_REV=none; fi"
        val (code, out) = capture(listOf("ssh") + sshOpts("-p") + listOf("$user@$host", cmd), echo = false)
        if (code != 0) return null
        return Regex("GADSCAPE_REV=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun revisionName(rev: Int) = if (rev >= cache.CacheIO.REV_241) "build 241" else "rev 211"

    /**
     * Before a cache goes up: the exported cache and the server's must be the same format (a rev 211 cache on the build
     * 241 server, or the other way round, breaks every client). False (with the reason logged) stops the deploy.
     */
    private fun sameCacheFormat(): Boolean {
        val local = cache.CacheIO.revisionOf(GadTools.exportCacheDir)
        if (local == null) { log("Can't read the exported cache in ${GadTools.exportCacheDir.path}. Nothing was sent."); return false }
        val remote = remoteRevision()
        if (remote == null) {
            log("Couldn't read the format of the server's cache in $remoteCache. Nothing was sent (check deployRemoteCache in the Settings file).")
            return false
        }
        if (local != remote) {
            log("Stopping: your cache is a ${revisionName(local)} cache but the server's ($remoteCache) is ${revisionName(remote)}. " +
                    "Nothing was sent. If the server runs from another folder, set deployRemoteCache in the Settings file.")
            return false
        }
        log("Cache format: ${revisionName(local)} here and on the server.")
        return true
    }

    fun deploy(): Boolean {
        if (!CacheEdits.fixBeforeShipping(log)) return false
        if (File(GadTools.exportCacheDir, "main_file_cache.dat2").isFile && !sameCacheFormat()) return false
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
            .maxByOrNull { it.second.length }
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
        val roots = listOfNotNull(serverDirGuess, NpcSpawns.serverDir, ServerSource.guess(),
            File("C:/GadScape")).filter { it.isDirectory }.distinct()
        val deep = roots.flatMap { r -> r.walkTopDown().maxDepth(3).filter { it.isDirectory && (File(it, "gradlew.bat").isFile || File(it, "gradlew").isFile) }.toList() }
        val allRoots = (roots + deep).distinct()
        fun resolve(e: String): File? = File(e).takeIf { it.isAbsolute && it.isFile }
            ?: allRoots.asSequence().map { File(it, e) }.firstOrNull { it.isFile }
            ?: allRoots.asSequence().map { File(it, "${shopsPath(it)}/$e") }.firstOrNull { it.isFile }
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
# the server only loads plugin classes listed in data/plugins.dat: add new shop / NPC spawn classes to it
if len(sys.argv) > 5 and os.path.isfile(sys.argv[4]):
    import struct
    bases = set(n.rsplit('.class', 1)[0] for n in names if n.endswith('.class'))
    new = [n.rsplit('.class', 1)[0] for n in repl if n.endswith('.class') and '$' not in n
           and n.rsplit('.class', 1)[0] not in bases and ('/plugins/shops/' in n or '/plugins/spawns/' in n)]
    d = open(sys.argv[4], 'rb').read()
    count = struct.unpack('>I', d[:4])[0]; p = 4; have = set()
    for i in range(count):
        l = d[p + 1]; have.add(d[p + 2:p + 2 + l].decode('utf-8')); p += 2 + l
    add = [c.replace('/', '.') for c in new if c.replace('/', '.') not in have]
    if add:
        body = d[4:p] + b''.join(bytes([2, len(c.encode())]) + c.encode() for c in add)
        open(sys.argv[5], 'wb').write(struct.pack('>I', count + len(add)) + body)
    print('PLUGINS_ADDED=%d %s' % (len(add), ' '.join(add)))
""".trimIndent()

    /**
     * The edited shop / NPC spawn / other source files, each with its path inside the server project (for servers
     * that build themselves on start). Any file under the server folder counts, not just the shops folder.
     */
    private fun editedSources(serverDir: File): List<Pair<File, String>> {
        val root = serverDir.canonicalFile
        return pendingShops().mapNotNull { e ->
            val f = (File(e).takeIf { it.isAbsolute } ?: File(root, e)).takeIf { it.isFile }
                ?: File(root, "${shopsPath(root)}/$e").takeIf { it.isFile } ?: return@mapNotNull null
            val c = f.canonicalFile
            if (!c.path.startsWith(root.path + File.separator)) return@mapNotNull null
            c to c.relativeTo(root).invariantSeparatorsPath
        }.distinctBy { it.second }
    }

    /**
     * One upload, one restart: the map (cache-out) and/or a new server jar.
     * Both are backed up on the server first; Rollback restores whatever was replaced.
     */
    fun deployBundle(includeMap: Boolean, shopClasses: File?, serverDir: File? = null): Boolean {
        val jar: File? = shopClasses
        if (includeMap && !CacheEdits.fixBeforeShipping(log)) return false
        if (includeMap && File(GadTools.exportCacheDir, "main_file_cache.dat2").isFile && !sameCacheFormat()) return false
        val cacheFiles = if (includeMap) GadTools.exportCacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.sortedBy { it.name } ?: emptyList() else emptyList()
        if (includeMap && cacheFiles.none { it.name == "main_file_cache.dat2" }) { log("No saved map in ${GadTools.exportCacheDir.path}. Save first."); return false }
        if (!includeMap && jar == null) { log("Nothing to deploy."); return false }
        val mode = if (jar != null) detectServer() ?: return false else null
        // the live jar is never replaced wholesale: only the edited shop classes inside it are swapped
        val remoteJar: String? = (mode as? ServerMode.Jar)?.path
        val sourceRoot = (mode as? ServerMode.Source)?.root
        // only the shop files you edited and saved in the shop editor; every other server file stays as it is live
        val shopFiles = if (sourceRoot != null && serverDir != null) editedSources(serverDir) else emptyList()
        if (sourceRoot != null && shopFiles.isEmpty() && remoteJar == null) {
            log("No edited shops to send (save a shop in Tools > Shops first).")
            if (!includeMap) return false
        }
        if (shopFiles.isNotEmpty()) log("Files to send: ${shopFiles.joinToString(", ") { it.second }}")
        if (remoteJar != null) log("The server runs $remoteJar: only the edited shop classes inside it will be swapped.")
        if (sourceRoot != null) log("The server builds itself from $sourceRoot: your ${shopFiles.size} shop files go into its source, then it restarts and rebuilds.")
        val remoteShops = sourceRoot?.takeIf { shopFiles.isNotEmpty() }
        // the server's plugin list (new shop / spawn classes must be added to it, or the server never loads them)
        val remotePlugins = remoteCache.let { rc -> (remoteServerDir?.trimEnd('/') ?: rc.removeSuffix("/").removeSuffix("/cache/data/cache")) + "/data/plugins.dat" }

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
        log("Uploading ${upload.size + shopFiles.size} file(s) (%.0f MB)...".format((upload + shopFiles.map { it.first }).sumOf { it.length() / 1048576.0 }))
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
                        "python3 patch_jar.py $remoteJar $staging/shops/classes $staging/server.jar.new $remotePlugins $staging/plugins.dat.new") != 0) {
                log("Couldn't build the patched server jar (needs python3 on the server). The live server was not touched.")
                ssh("rm -rf $staging"); return false
            }
        }
        // each file goes up under a numbered name (two edited files can share a name in different folders)
        shopFiles.forEachIndexed { i, (f, _) ->
            if (exec(listOf("scp") + sshOpts("-P") + listOf(f.absolutePath, "$user@$host:$staging/shops/$i.src")) != 0) {
                log("Upload of ${f.name} failed. The live server was not touched.")
                ssh("rm -rf $staging"); return false
            }
        }
        val steps = mutableListOf("set -e", "${sudo}mkdir -p $backup")
        if (includeMap) steps.add("${sudo}cp -p $remoteCache/main_file_cache.* $backup/")
        if (remoteJar != null) { steps.add("${sudo}cp -p $remoteJar $backup/server.jar"); steps.add("echo $remoteJar | ${sudo}tee $backup/jar-path.txt >/dev/null") }
        if (remoteJar != null) steps.add("if [ -f $staging/plugins.dat.new ]; then ${sudo}cp -p $remotePlugins $backup/plugins.dat && echo $remotePlugins | ${sudo}tee $backup/plugins-path.txt >/dev/null; fi")
        if (remoteShops != null) {
            // backup keeps each file at its path inside the server project; a new file is noted so Rollback removes it
            steps.add("${sudo}mkdir -p $backup/src")
            shopFiles.forEach { (_, rel) ->
                steps.add("if [ -f '$remoteShops/$rel' ]; then ${sudo}mkdir -p \"$backup/src/\$(dirname '$rel')\" && ${sudo}cp -p '$remoteShops/$rel' '$backup/src/$rel'; " +
                        "else echo '$rel' | ${sudo}tee -a $backup/src-new.txt >/dev/null; fi")
            }
            steps.add("echo $remoteShops | ${sudo}tee $backup/src-root.txt >/dev/null")
        }
        steps.add("${sudo}systemctl stop $service")
        if (remoteShops != null) shopFiles.forEachIndexed { i, (_, rel) ->
            steps.add("${sudo}mkdir -p \"$remoteShops/\$(dirname '$rel')\" && ${sudo}cp -f $staging/shops/$i.src '$remoteShops/$rel'")
        }
        if (includeMap) steps.add("${sudo}cp -f $staging/main_file_cache.* $remoteCache/")
        if (remoteJar != null) steps.add("${sudo}cp -f $staging/server.jar.new $remoteJar")
        if (remoteJar != null) steps.add("if [ -f $staging/plugins.dat.new ]; then ${sudo}cp -f $staging/plugins.dat.new $remotePlugins; fi")
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

    /**
     * Sends what the Items tab saved to the live server, with one restart:
     *  - item stats: only the edited entries are merged into the live data/items/ItemDefinitions.json (by id), so
     *    changes made on the server in the meantime stay;
     *  - other server data files (data/items/special-attacks.json) are copied as they are;
     *  - with [withCache], the exported cache too (new items, models, special energy).
     * Everything replaced is backed up first; Rollback restores it.
     */
    fun deployItemStats(server: File, withCache: Boolean = false): Boolean {
        val json = ItemStats.pendingJson(server)
        val others = ServerItems.pending().map { it to File(ServerItems.dataDir(server), it) }.filter { it.second.isFile }
        if (withCache && !CacheEdits.fixBeforeShipping(log)) return false
        if (withCache && File(GadTools.exportCacheDir, "main_file_cache.dat2").isFile && !sameCacheFormat()) return false
        val cacheFiles = if (withCache) GadTools.exportCacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("main_file_cache") }?.sortedBy { it.name } ?: emptyList() else emptyList()
        if (withCache && cacheFiles.none { it.name == "main_file_cache.dat2" }) { log("No exported cache in ${GadTools.exportCacheDir.path}. Save first."); return false }
        if (json == null && others.isEmpty() && cacheFiles.isEmpty()) { log("Nothing to send. Save stats in the Items tab first."); return false }
        val live = "$remoteData/items/ItemDefinitions.json"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val staging = "/tmp/gadscape-stats-$stamp"
        val backup = "${remoteCache}-backups/$stamp"
        val local = File(System.getProperty("java.io.tmpdir"), "ume-stats-$stamp").apply { mkdirs() }
        try {
            File(local, "patch.json").writeText(json ?: "[]")
            File(local, "merge.py").writeText("""
import json, sys
live, patch, out = sys.argv[1], sys.argv[2], sys.argv[3]
L = json.load(open(live, encoding='utf-8')); P = json.load(open(patch, encoding='utf-8'))
at = {e['id']: i for i, e in enumerate(L) if isinstance(e, dict) and 'id' in e}
for e in P:
    if e['id'] in at: L[at[e['id']]] = e
    else: L.append(e)
L.sort(key=lambda e: e.get('id', 0) if isinstance(e, dict) else 0)
json.dump(L, open(out, 'w', encoding='utf-8'), indent='\t', ensure_ascii=False)
json.load(open(out, encoding='utf-8'))
print('MERGED %d item(s)' % len(P))
""".trimIndent())
            if (json != null) log("Sending stats for item(s) ${ItemStats.pending().sorted().joinToString(", ")} to $live ...")
            if (others.isNotEmpty()) log("Sending ${others.joinToString(", ") { it.first }} ...")
            val check = "mkdir -p $staging/data && ${sudo}test -d $remoteData && echo found-data" +
                    (if (json != null) " && ${sudo}test -f $live && echo found-live-stats" else "") +
                    (if (withCache) " && ${sudo}test -f $remoteCache/main_file_cache.dat2 && echo found-live-cache" else "")
            if (ssh(check) != 0) { log("Couldn't find $remoteData${if (withCache) " or the live cache" else ""} on the server (deployRemoteData in the Settings file). Nothing was changed."); return false }
            fun up(files: List<String>, to: String) = exec(listOf("scp") + sshOpts("-P") + files + listOf("$user@$host:$to")) == 0
            var uploaded = up(listOf(File(local, "patch.json").absolutePath, File(local, "merge.py").absolutePath), "$staging/")
            for ((rel, f) in others) if (uploaded) uploaded = ssh("mkdir -p $staging/data/${rel.substringBeforeLast('/', "")}") == 0 && up(listOf(f.absolutePath), "$staging/data/$rel")
            if (uploaded && cacheFiles.isNotEmpty()) uploaded = up(cacheFiles.map { it.absolutePath }, "$staging/")
            if (!uploaded) { log("Upload failed. The live server was not touched."); ssh("rm -rf $staging"); return false }
            val steps = mutableListOf("set -e", "${sudo}mkdir -p $backup/data", "echo $remoteData | ${sudo}tee $backup/data-path.txt >/dev/null")
            if (json != null) steps.addAll(listOf("command -v python3 >/dev/null",
                "python3 $staging/merge.py $live $staging/patch.json $staging/ItemDefinitions.json",
                "${sudo}mkdir -p $backup/items", "${sudo}cp -p $live $backup/items/ItemDefinitions.json",
                "echo $live | ${sudo}tee $backup/items-path.txt >/dev/null"))
            for ((rel, _) in others) {
                steps.add("${sudo}mkdir -p $backup/data/${rel.substringBeforeLast('/', "")}")
                steps.add("if [ -f $remoteData/$rel ]; then ${sudo}cp -p $remoteData/$rel $backup/data/$rel; fi")
            }
            if (withCache) steps.add("${sudo}cp -p $remoteCache/main_file_cache.* $backup/")
            steps.add("${sudo}systemctl stop $service")
            if (json != null) steps.add("${sudo}cp -f $staging/ItemDefinitions.json $live")
            for ((rel, _) in others) {
                steps.add("${sudo}mkdir -p $remoteData/${rel.substringBeforeLast('/', "")}")
                steps.add("${sudo}cp -f $staging/data/$rel $remoteData/$rel")
            }
            if (withCache) steps.add("${sudo}cp -f $staging/main_file_cache.* $remoteCache/")
            steps.addAll(listOf("${sudo}systemctl start $service", "rm -rf $staging",
                "ls -1dt ${remoteCache}-backups/*/ 2>/dev/null | tail -n +6 | xargs -r ${sudo}rm -rf",
                "sleep 5", "${sudo}systemctl is-active $service"))
            val ok = ssh(steps.joinToString(" && ")) == 0
            log(if (ok) "Done. The server has the new item stats. The old files are saved in $backup (Rollback restores them)." else "Sending failed. If the server is down, run Rollback.")
            if (ok) { ItemStats.clearPending(); ServerItems.clearPending() }
            lastBackup = backup
            return ok
        } finally { local.deleteRecursively() }
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
            "if [ -f \$B/plugins.dat ]; then ${sudo}cp -f \$B/plugins.dat \$(cat \$B/plugins-path.txt); fi",
            "if [ -f \$B/items/ItemDefinitions.json ]; then ${sudo}cp -f \$B/items/ItemDefinitions.json \$(cat \$B/items-path.txt); fi",
            "if [ -d \$B/shops ]; then ${sudo}cp -f \$B/shops/*.kt \$(cat \$B/shops-path.txt)/; fi",
            "if [ -d \$B/data ]; then ${sudo}cp -rf \$B/data/. \$(cat \$B/data-path.txt)/; fi",
            "if [ -d \$B/src ]; then ${sudo}cp -rf \$B/src/. \$(cat \$B/src-root.txt)/; fi",
            "if [ -f \$B/src-new.txt ]; then R=\$(cat \$B/src-root.txt); while read -r f; do ${sudo}rm -f \"\$R/\$f\"; done < \$B/src-new.txt; fi",
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
        /**
         * Reads the output of [findServerCache]'s probe (the service unit after "#SERVICE", then "PID <pid> CWD <dir>"
         * and the command line of each java process after "#PROCS"): the server's cache folder (null if it can't be
         * told) and its working folder.
         */
        fun serverCacheFrom(out: String): Pair<String?, String?> {
            val serviceText = out.substringAfter("#SERVICE").substringBefore("#PROCS")
            val procs = out.substringAfter("#PROCS").substringBefore("#END")
            val workDir = serviceText.lines().lastOrNull { it.trim().startsWith("WorkingDirectory=") }?.substringAfter("=")?.trim()
            fun resolve(path: String, base: String?) = (if (path.startsWith("/") || base == null) path else "${base.trimEnd('/')}/$path").trimEnd('/')
            val prop = Regex("""-Dgadscape\.cache\.dir=("[^"]+"|'[^']+'|\S+)""")
            fun cacheDirIn(text: String) = prop.find(text)?.groupValues?.get(1)?.trim('"', '\'')
            // 1. the running game server: a java process started with -Dgadscape.cache.dir, else (as detectServer does)
            // the java process with the longest command line
            val javas = procs.lines().windowed(2, 1).map { it[0] to it[1] }
                .filter { it.first.startsWith("PID ") && !it.second.contains("pgrep") && !it.second.contains("GradleDaemon") && !it.second.contains("GradleWrapperMain") && !it.second.contains("kotlin-daemon") }
            val game = javas.filter { prop.containsMatchIn(it.second) }.maxByOrNull { it.second.length } ?: javas.maxByOrNull { it.second.length }
            val cwd = game?.first?.substringAfter("CWD ")?.trim()?.takeIf { it.isNotEmpty() }
            val serverDir = cwd ?: workDir
            game?.second?.let { cmd -> cacheDirIn(cmd)?.let { return resolve(it, cwd) to serverDir } }
            // 2. the service's start command (or its Environment= lines, e.g. JAVA_OPTS)
            cacheDirIn(serviceText)?.let { return resolve(it, workDir) to serverDir }
            // 3. the server's default: cache/data/cache in the folder it runs in
            val base = serverDir ?: return null to null
            return resolve("cache/data/cache", base) to serverDir
        }

        /** The shop editor calls this when a shop file is saved, so only edited shops get deployed. */
        fun markShopEdited(name: String) {
            val f = File(System.getProperty("user.home"), ".uzis-map-editor/shops-pending.txt")
            f.parentFile.mkdirs()
            val now = (if (f.isFile) f.readLines() else emptyList()).map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            now.add(name); f.writeText(now.joinToString("\n"))
        }
        /** The shops folder: com/gadscape/... (see [ServerSource.pkg]). */
        fun shopsPath(server: File?): String = ServerSource.pkgPath(server, "plugins/shops/src/main/kotlin", "plugins/shops")
        @Volatile var lastBackup: String? = null
        fun settingsFile(): File = GadTools.templatesPath
    }
}
