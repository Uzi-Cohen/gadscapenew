package com.gadscape.raids.tfa

import java.nio.file.Path

/**
 * Where the Fractured Archive reads its server-side data.
 *
 * The data directory (`data/raids/tfa/` under the server working directory) is not in git. It holds the
 * optional config overrides (`<name>.json`, which win over the defaults bundled in this module under
 * `src/main/resources/tfa/`), the optional id remap (`remap.tsv`) and the records store (`records/`).
 *
 * Override the directory with `-Dgadscape.tfa.dir=<path>` (the app's Gradle tasks forward every `-Dgadscape.*`).
 */
object TfaPaths {

    /** System property that relocates [dataDir]. */
    const val DATA_DIR_PROPERTY = "gadscape.tfa.dir"

    /** Classpath folder of the bundled defaults (`src/main/resources/tfa/`). */
    const val BUNDLED_RESOURCE_DIR = "tfa"

    /** `data/raids/tfa/` relative to the server working directory, unless [DATA_DIR_PROPERTY] is set. */
    @JvmStatic
    val dataDir: Path
        get() = System.getProperty(DATA_DIR_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: Path.of("data", "raids", "tfa")

    /** The id remap written by the asset port (WU-01). Optional: identity when absent. */
    @JvmStatic
    val remapFile: Path get() = dataDir.resolve("remap.tsv")

    /** Global records store (WU-19). */
    @JvmStatic
    val recordsDir: Path get() = dataDir.resolve("records")
}
