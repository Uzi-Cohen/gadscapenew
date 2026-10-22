package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.TfaPaths
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Entry point of the Fractured Archive configuration (spec §2.6).
 *
 * - Defaults ship inside the module (`src/main/resources/tfa/<file>.json`); an operator file
 *   `data/raids/tfa/<file>.json` overrides them key by key (see [ConfigReader] for the rules).
 * - [current] is the live snapshot. A raid takes `TfaConfig.current` when it is created and keeps that
 *   [TfaConfigSnapshot] for its whole run, so `::tfareload` ([reload]) only affects raids created afterwards.
 * - Sections are typed [TfaConfigSpec]s. The raid-wide `tfa.json` ([RaidConfig]) and `scaling.json`
 *   ([ScalingConfig]) are registered here; every other unit registers its own spec with [register]
 *   (order-independent: registering after a snapshot exists builds the section into it immediately).
 * - Loading never throws for bad data. Problems become [ConfigIssue]s (logged, `::tfa dump`); the keys that still
 *   hold house values are logged per file and listed by `::tfa dump`.
 */
object TfaConfig {

    private val log = LoggerFactory.getLogger(TfaConfig::class.java)

    private val specs = LinkedHashSet<TfaConfigSpec<*>>()
    private val versions = AtomicInteger()

    @Volatile
    private var snapshot: TfaConfigSnapshot? = null

    init {
        specs += RaidConfig
        specs += ScalingConfig
    }

    /** The live snapshot, loaded (and logged) on first use. */
    @JvmStatic
    val current: TfaConfigSnapshot
        get() = snapshot ?: synchronized(this) {
            snapshot ?: load().also {
                snapshot = it
                logReport(it)
            }
        }

    /** True once the first snapshot exists. */
    @JvmStatic
    val isLoaded: Boolean get() = snapshot != null

    /** Registers [spec] (idempotent) and, if a snapshot exists, builds and reports it there. Returns [spec]. */
    @JvmStatic
    fun <T : Any> register(spec: TfaConfigSpec<T>): TfaConfigSpec<T> {
        val added = synchronized(this) { specs.add(spec) }
        val snap = snapshot
        if (added && snap != null) {
            snap[spec]
            snap.issuesOf(spec).forEach(::logIssue)
            logHouse(snap, snap.entriesOf(spec))
        }
        return spec
    }

    @JvmStatic
    fun registeredSpecs(): List<TfaConfigSpec<*>> = synchronized(this) { specs.toList() }

    /** Re-reads every file, swaps [current] and logs the report. Raids already running keep their snapshot. */
    @JvmStatic
    fun reload(): TfaConfigSnapshot {
        val fresh = load()
        snapshot = fresh
        logReport(fresh)
        return fresh
    }

    /**
     * Builds a new snapshot without installing it. Tests pass a temp [dataDir] and an in-memory [bundled] source.
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        dataDir: Path? = TfaPaths.dataDir,
        bundled: (String) -> String? = ::classpathBundled,
        specs: Collection<TfaConfigSpec<*>> = registeredSpecs(),
    ): TfaConfigSnapshot {
        val snap = TfaConfigSnapshot(versions.incrementAndGet(), Instant.now(), ConfigFiles(dataDir, bundled), specs.toList())
        for (spec in specs) snap[spec]
        return snap
    }

    /** Reads `tfa/<name>` from the module's resources. */
    @JvmStatic
    fun classpathBundled(name: String): String? =
        TfaConfig::class.java.classLoader.getResourceAsStream("${TfaPaths.BUNDLED_RESOURCE_DIR}/$name")
            ?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }

    private fun logReport(snap: TfaConfigSnapshot) {
        log.info(snap.summary())
        snap.issues().forEach(::logIssue)
        logHouse(snap, snap.entries())
    }

    private fun logIssue(issue: ConfigIssue) {
        if (issue.severity == Severity.ERROR) log.error("Fractured Archive config: {}", issue)
        else log.warn("Fractured Archive config: {}", issue)
    }

    private fun logHouse(snap: TfaConfigSnapshot, entries: List<ConfigEntry>) {
        entries.filter { it.holdsHouseValue }.groupBy { it.file }.forEach { (file, list) ->
            log.info("Fractured Archive config v{}: {} keys in {} still hold house values: {}",
                snap.version, list.size, file, list.joinToString { it.key + if (it.tuned) "*" else "" })
        }
    }
}

/**
 * An immutable view of the config files at one moment: override files are read when the snapshot is made, and
 * each [TfaConfigSpec] is built from that text once (on first [get]) and cached. Hold on to the snapshot a raid
 * started with instead of calling [TfaConfig.current] mid-raid.
 */
class TfaConfigSnapshot internal constructor(
    /** Increments with every load; shown by `::tfa dump`. */
    val version: Int,
    val loadedAt: Instant,
    private val files: ConfigFiles,
    /** The specs the snapshot was loaded with (others may be built later, on first [get]). */
    private val declared: List<TfaConfigSpec<*>>,
) {

    private class Built(val value: Any, val entries: List<ConfigEntry>, val issues: List<ConfigIssue>)

    private val built = LinkedHashMap<TfaConfigSpec<*>, Built>()

    /** The typed section for [spec], built from this snapshot's files on first use. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(spec: TfaConfigSpec<T>): T = builtOf(spec).value as T

    /** Raid-wide settings (`tfa.json`). */
    val raid: RaidConfig get() = this[RaidConfig]

    /** HP scaling (`scaling.json`). */
    val scaling: ScalingConfig get() = this[ScalingConfig]

    /** The specs built so far, in build order. */
    fun specs(): List<TfaConfigSpec<*>> = synchronized(built) { built.keys.toList() }

    /** Every key read by the built specs. */
    fun entries(): List<ConfigEntry> = synchronized(built) { built.values.flatMap { it.entries } }

    fun entriesOf(spec: TfaConfigSpec<*>): List<ConfigEntry> = builtOf(spec).entries

    /** Keys that still hold an unconfirmed (HOUSE/COMM/INF) value. */
    fun houseEntries(): List<ConfigEntry> = entries().filter { it.holdsHouseValue }

    /** File problems (parse errors, missing defaults, unclaimed top-level keys) and every spec's issues. */
    fun issues(): List<ConfigIssue> {
        val specs = specs()
        val out = ArrayList<ConfigIssue>()
        for (file in specs.map { it.file }.distinct()) out += fileIssues(file, specs.filter { it.file == file })
        synchronized(built) { built.values.forEach { out += it.issues } }
        return out
    }

    fun issuesOf(spec: TfaConfigSpec<*>): List<ConfigIssue> = fileIssues(spec.file, listOf(spec)) + builtOf(spec).issues

    /** Override files present in the data directory that no registered spec reads. */
    fun unreadOverrideFiles(): List<String> {
        val read = specs().map { it.file }.toSet()
        return files.overrideNames().filter { it !in read }.sorted()
    }

    /** One-line summary for logs and `::tfareload`. */
    fun summary(): String {
        val issues = issues()
        val fileNames = specs().map { it.file }.distinct()
        val overrides = fileNames.filter { files.override(it) != null }
        return "Fractured Archive config v$version: ${fileNames.size} files (${overrides.size} overridden in " +
            "${files.dataDir ?: "-"}${if (overrides.isEmpty()) "" else ": " + overrides.joinToString()}), " +
            "${issues.count { it.severity == Severity.ERROR }} errors, ${issues.count { it.severity == Severity.WARNING }} warnings, " +
            "${houseEntries().size} keys still hold house values"
    }

    private fun builtOf(spec: TfaConfigSpec<*>): Built = synchronized(built) {
        built[spec] ?: build(spec).also { built[spec] = it }
    }

    private fun build(spec: TfaConfigSpec<*>): Built {
        val override = files.override(spec.file)
        val bundled = files.bundled(spec.file)
        val verified = LinkedHashMap<String, String>()
        bundled?.verified?.let(verified::putAll)
        override?.verified?.let(verified::putAll)

        val session = ReadSession(spec.file, verified)
        val layers = ArrayList<ConfigReader.Layer>()
        for (parsed in listOfNotNull(override, bundled)) {
            val top = parsed.obj ?: continue
            val obj: JsonObject? = if (spec.root == null) top else rootObject(top, spec, parsed, session)
            layers += ConfigReader.Layer(parsed.source, parsed.label, obj)
        }
        if (spec.root == null) {
            // A whole-file spec does not report top-level keys that another registered section of the file owns.
            val claimed = (TfaConfig.registeredSpecs() + declared + specs()).filter { it.file == spec.file }.mapNotNull { it.root }
            layers.forEach { it.consumed += claimed }
        }
        val reader = ConfigReader(session, spec.root ?: "", layers, null)
        val value: Any = try {
            spec.read(reader).also { reader.finish() }
        } catch (e: Exception) {
            session.issue(null, Severity.ERROR, "reading ${spec.description} failed ($e); using the code defaults")
            val fallback = ReadSession(spec.file, verified)
            val result: Any = spec.read(ConfigReader(fallback, spec.root ?: "", emptyList(), null))
            session.entries.clear()
            session.entries += fallback.entries
            result
        }
        return Built(value, session.entries.toList(), session.issues.toList())
    }

    private fun rootObject(top: JsonObject, spec: TfaConfigSpec<*>, parsed: ParsedFile, session: ReadSession): JsonObject? {
        val el: JsonElement = top.get(spec.root) ?: return null
        if (el.isJsonObject) return el.asJsonObject
        if (!el.isJsonNull) {
            session.issue(spec.root, Severity.ERROR, "expected an object, got ${JsonValues.describe(el)} (in ${parsed.label}); ignoring it")
        }
        return null
    }

    private fun fileIssues(file: String, specsOfFile: List<TfaConfigSpec<*>>): List<ConfigIssue> {
        val out = ArrayList<ConfigIssue>()
        val override = files.override(file)
        val bundled = files.bundled(file)
        for (parsed in listOfNotNull(override, bundled)) {
            parsed.error?.let { out += ConfigIssue(file, null, Severity.ERROR, "${parsed.label}: $it; ignoring that file") }
            parsed.verifiedError?.let { out += ConfigIssue(file, "\$verified", Severity.WARNING, "${parsed.label}: $it") }
        }
        if (bundled == null) {
            out += ConfigIssue(file, null, Severity.WARNING, "no bundled defaults (resources/tfa/$file); using code defaults")
        }
        // With only root-scoped specs on this file, top-level keys that none of them owns are unknown.
        if (specsOfFile.isNotEmpty() && specsOfFile.all { it.root != null }) {
            val roots = specsOfFile.mapNotNull { it.root }.toSet()
            for (parsed in listOfNotNull(override, bundled)) {
                val top = parsed.obj ?: continue
                for (k in top.keySet()) {
                    if (k in roots || k.startsWith("$") || k.startsWith("_")) continue
                    if ((TfaConfig.registeredSpecs() + declared).any { it.file == file && it.root == k }) continue
                    out += ConfigIssue(file, k, Severity.WARNING, "unknown top-level key in ${parsed.label} (no registered section reads it)")
                }
            }
        }
        return out
    }
}

/** One parsed JSON file (or why it could not be parsed). */
internal class ParsedFile(
    val name: String,
    val source: ValueSource,
    val label: String,
    val obj: JsonObject?,
    val error: String?,
) {
    /** The `$verified` block: key path → note. */
    val verified: Map<String, String>
    val verifiedError: String?

    init {
        var problem: String? = null
        val map = LinkedHashMap<String, String>()
        val el = obj?.get("\$verified")
        when {
            el == null || el.isJsonNull -> {}
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) ->
                map[k] = if (v.isJsonPrimitive) v.asString else v.toString()
            }
            el.isJsonArray -> el.asJsonArray.forEach { item ->
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) map[item.asString] = "verified"
                else problem = "entries of a \$verified list must be key strings, got $item"
            }
            else -> problem = "\$verified must be an object {key: note} or a list of keys"
        }
        verified = map
        verifiedError = problem
    }

    companion object {
        fun parse(name: String, source: ValueSource, label: String, text: String): ParsedFile = try {
            val reader = JsonReader(StringReader(text))
            reader.strictness = Strictness.LENIENT // comments and stray unquoted words parse; bad values are typed later
            val root = JsonParser.parseReader(reader)
            when {
                reader.peek() != JsonToken.END_DOCUMENT -> ParsedFile(name, source, label, null, "unexpected content after the top-level object")
                !root.isJsonObject -> ParsedFile(name, source, label, null, "the top level must be a JSON object")
                else -> ParsedFile(name, source, label, root.asJsonObject, null)
            }
        } catch (e: JsonParseException) {
            ParsedFile(name, source, label, null, "malformed JSON: ${e.cause?.message ?: e.message}")
        } catch (e: Exception) {
            ParsedFile(name, source, label, null, "malformed JSON: ${e.message}")
        }
    }
}

/**
 * The raw files behind a snapshot. Override files are read eagerly (so a later edit cannot leak into a running
 * raid's snapshot); bundled files come from the module and never change at runtime.
 */
internal class ConfigFiles(val dataDir: Path?, private val bundledSource: (String) -> String?) {

    private val log = LoggerFactory.getLogger(ConfigFiles::class.java)

    private val overrideTexts: Map<String, String> = readOverrides()
    private val parsedOverrides = HashMap<String, ParsedFile?>()
    private val parsedBundled = HashMap<String, ParsedFile?>()

    fun overrideNames(): Set<String> = overrideTexts.keys

    fun override(name: String): ParsedFile? = synchronized(parsedOverrides) {
        parsedOverrides.getOrPut(name) {
            overrideTexts[name]?.let { ParsedFile.parse(name, ValueSource.OVERRIDE, "override ${dataDir?.resolve(name)}", it) }
        }
    }

    fun bundled(name: String): ParsedFile? = synchronized(parsedBundled) {
        parsedBundled.getOrPut(name) {
            bundledSource(name)?.let { ParsedFile.parse(name, ValueSource.BUNDLED, "bundled tfa/$name", it) }
        }
    }

    private fun readOverrides(): Map<String, String> {
        val dir = dataDir ?: return emptyMap()
        if (!Files.isDirectory(dir)) return emptyMap()
        return try {
            dir.listDirectoryEntries().filter { it.isRegularFile() && it.extension == "json" }.associate {
                it.name to Files.readString(it, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            log.error("Could not read Fractured Archive config overrides in {}", dir, e)
            emptyMap()
        }
    }
}
