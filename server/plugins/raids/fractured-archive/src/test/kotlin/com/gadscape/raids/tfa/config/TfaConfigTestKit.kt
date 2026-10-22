package com.gadscape.raids.tfa.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Helpers for config tests. Encounter units use [assertBundledMatchesDefaults] for their own spec:
 * ```
 * @Test fun bundledGuardianJsonMatchesDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(GuardianConfig)
 * ```
 */
object TfaConfigTestKit {

    /** A snapshot of [specs] built from [overrides] (written to a temp data dir) over [bundled] (null = classpath). */
    fun load(
        specs: List<TfaConfigSpec<*>>,
        overrides: Map<String, String> = emptyMap(),
        bundled: Map<String, String>? = null,
    ): TfaConfigSnapshot {
        val dir: Path = createTempDirectory("tfa-config-test")
        overrides.forEach { (name, text) -> dir.resolve(name).writeText(text) }
        try {
            return TfaConfig.load(dir, bundled?.let { map -> { name: String -> map[name] } } ?: TfaConfig::classpathBundled, specs)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    /**
     * The bundled `resources/tfa/<file>` must parse cleanly, contain no unknown keys and hold exactly the code
     * defaults of [spec] (the defaults are the spec's house values; the file is their shipped copy).
     */
    fun assertBundledMatchesDefaults(spec: TfaConfigSpec<*>) {
        val snap = load(listOf(spec), overrides = emptyMap(), bundled = null)
        val value = snap[spec]
        val issues = snap.issuesOf(spec)
        assertTrue(issues.isEmpty(), "bundled ${spec.file} has problems:\n" + issues.joinToString("\n"))
        val missing = snap.entriesOf(spec).filter { it.source == ValueSource.DEFAULT }
        assertTrue(missing.isEmpty(), "bundled ${spec.file} lacks keys (code default used):\n" + missing.joinToString("\n"))
        val differ = snap.entriesOf(spec).filter { it.tuned }
        if (differ.isNotEmpty()) fail("bundled ${spec.file} differs from the code defaults:\n" + differ.joinToString("\n"))
        // Reading with no files at all must give the same value.
        val defaultsOnly = load(listOf(spec), bundled = emptyMap())[spec]
        assertEquals(defaultsOnly, value, "code defaults and bundled ${spec.file} build different values")
    }
}
