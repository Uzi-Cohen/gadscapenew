package com.gadscape.raids.tfa.stats

import com.gadscape.raids.tfa.TfaPaths
import com.google.gson.GsonBuilder
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One records file (`<dir>/global.json`), read and written atomically. Never throws: a missing file starts empty, a
 * corrupt one is moved aside (`global.json.corrupt-<millis>`) and the store starts empty, and a directory that cannot
 * be written keeps the records in memory (logged once).
 */
class RecordsFile(private val dir: Path) {

    private val log = LoggerFactory.getLogger(RecordsFile::class.java)
    private var warnedUnwritable = false

    val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): GlobalRecords {
        val f = file
        if (!Files.isRegularFile(f)) return GlobalRecords()
        return try {
            val text = String(Files.readAllBytes(f), StandardCharsets.UTF_8)
            GSON.fromJson(text, GlobalRecords::class.java)?.also(::repair) ?: GlobalRecords()
        } catch (e: Exception) {
            log.error("Fractured Archive records: {} is unreadable; starting empty and keeping the old file aside", f, e)
            try {
                Files.move(f, f.resolveSibling("$FILE_NAME.corrupt-${System.currentTimeMillis()}"), StandardCopyOption.REPLACE_EXISTING)
            } catch (move: Exception) {
                log.warn("Fractured Archive records: could not move {} aside", f, move)
            }
            GlobalRecords()
        }
    }

    /** Writes [json] (from [encode]); returns false (and logs once) when the directory is not writable. */
    fun write(json: String): Boolean = try {
        Files.createDirectories(dir)
        val tmp = dir.resolve("$FILE_NAME.tmp")
        Files.write(tmp, json.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        true
    } catch (e: Exception) {
        if (!warnedUnwritable) {
            warnedUnwritable = true
            log.error("Fractured Archive records: cannot write {}; records stay in memory until it is writable", file, e)
        }
        false
    }

    companion object {
        const val FILE_NAME = "global.json"
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun encode(records: GlobalRecords): String = GSON.toJson(records)

        /** Gson leaves fields null when the JSON lacks them. */
        @Suppress("SENSELESS_COMPARISON")
        private fun repair(r: GlobalRecords) {
            if (r.allTime == null) r.allTime = StatsTable()
            if (r.today == null) r.today = StatsTable()
            if (r.todayDate == null) r.todayDate = ""
            for (t in listOf(r.allTime, r.today)) {
                if (t.counters == null) t.counters = HashMap()
                if (t.best == null) t.best = HashMap()
            }
        }
    }
}

/**
 * The server-wide records of the Alliance Log (spec §3.14): all-time totals and bests ("Global") and today's
 * ("Global Today"; GadScape has one world, so "World Today" shows the same), reset at 00:00 UTC. Stored in
 * `data/raids/tfa/records/global.json` ([TfaPaths.recordsDir]); flushed at most every `lobby.json
 * stats.saveIntervalTicks` on a background thread, and at shutdown.
 */
object TfaRecordsStore {

    private val log = LoggerFactory.getLogger(TfaRecordsStore::class.java)

    @Volatile
    var clock: Clock = Clock.systemUTC()

    private var file = RecordsFile(TfaPaths.recordsDir)
    private var loaded: GlobalRecords? = null
    private var dirty = false

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tfa-records-writer").apply { isDaemon = true }
    }

    /** The records, with "today" rolled to the current UTC date. */
    @JvmStatic
    val records: GlobalRecords
        get() {
            val r = loaded ?: file.load().also {
                loaded = it
                log.info("Fractured Archive records loaded from {}", file.file)
            }
            if (r.rollTo(today())) dirty = true
            return r
        }

    fun today(): String = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString()

    /** Applies [change] to the all-time and today tables. */
    @JvmStatic
    fun update(change: (StatsTable) -> Unit) {
        val r = records
        change(r.allTime)
        change(r.today)
        dirty = true
    }

    /** Writes the file in the background if something changed. */
    @JvmStatic
    fun flush() {
        if (!dirty) return
        dirty = false
        val json = RecordsFile.encode(records)
        val target = file
        writer.execute { if (!target.write(json)) synchronized(this) { dirty = true } }
    }

    /** Flushes and waits (server shutdown). */
    @JvmStatic
    fun flushNow() {
        flush()
        writer.submit {}.let {
            try {
                it.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                log.warn("Fractured Archive records: shutdown flush did not finish", e)
            }
        }
    }

    /** Tests and tools: use [dir] instead of the data dir (drops what is loaded). */
    @JvmStatic
    fun useDirectory(dir: Path) {
        file = RecordsFile(dir)
        loaded = null
        dirty = false
    }
}
