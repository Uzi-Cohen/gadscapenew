package com.gadscape.raids.tfa.config

/**
 * Declares one typed config section: which [file] it lives in (under `data/raids/tfa/` and the bundled
 * `src/main/resources/tfa/`), optionally which top-level [root] key of that file it owns, and how to [read] it.
 *
 * Declare specs as singletons and register them once:
 * ```
 * data class GuardianConfig(val enragePct: Int, ...) {
 *     companion object SPEC : TfaConfigSpec<GuardianConfig>("guardian.json") {
 *         override fun read(r: ConfigReader) = GuardianConfig(enragePct = r.int("enragePct", 30, Evidence.COMM), ...)
 *     }
 * }
 * TfaConfig.register(GuardianConfig)               // at server launch (any order)
 * val cfg = raidSnapshot[GuardianConfig]           // typed value from the raid's config snapshot
 * ```
 *
 * Contract:
 * - [read] must give every key a default equal to the spec's house value, and the bundled
 *   `resources/tfa/<file>` must hold exactly those values (see `TfaConfigTestKit.assertBundledMatchesDefaults`).
 * - [read] must not throw on bad data (the [ConfigReader] already reports and falls back); it may build
 *   derived values. If it throws anyway, the snapshot reports it and uses the code defaults.
 * - A file may hold several specs with distinct [root] keys (e.g. `tfa.json`); a spec without a root owns the
 *   whole file.
 * - Marking keys verified: a file may contain `"$verified": {"enragePct": "wiki Archive_Guardian rev 1540000"}`
 *   (or a list of keys). Verified keys, and keys under a verified parent, stop being listed as house values.
 */
abstract class TfaConfigSpec<T : Any>(
    /** File name, e.g. `guardian.json`. */
    val file: String,
    /** Top-level key this spec owns, or null for the whole file. */
    val root: String? = null,
) {
    init {
        require(file.endsWith(".json") && '/' !in file && '\\' !in file) { "config file must be a plain *.json name: $file" }
    }

    /** Reads the section. Called once per config snapshot. */
    abstract fun read(r: ConfigReader): T

    /** `file` or `file#root`, for logs. */
    val description: String get() = if (root == null) file else "$file#$root"

    override fun toString(): String = "TfaConfigSpec($description)"
}
