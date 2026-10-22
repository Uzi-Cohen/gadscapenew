package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.raid.StaticTile

/**
 * The evidence tag of a config key (spec §0.1). Keys tagged [HOUSE], [COMM] or [INF] are "unconfirmed": they are
 * listed as still holding a house value until a file marks them verified (see [TfaConfigSpec]).
 */
enum class Evidence(val unconfirmed: Boolean) {
    /** Jagex post or OSRS wiki. */
    DOC(false),

    /** Read from cache 2746 or its scripts. */
    CACHE(false),

    /** Community observation; keep configurable. */
    COMM(true),

    /** Our inference; keep configurable, verify later. */
    INF(true),

    /** Chosen by us because nothing public gives it. */
    HOUSE(true),
}

/** Where a config value came from. */
enum class ValueSource(val label: String) {
    /** The code default (the key was absent or invalid everywhere). */
    DEFAULT("code default"),

    /** The default file bundled in the module (`src/main/resources/tfa/<file>`). */
    BUNDLED("bundled default"),

    /** The operator's override file (`data/raids/tfa/<file>`). */
    OVERRIDE("override"),
}

/**
 * One key read by a [TfaConfigSpec]: its current [value], the spec [default] (the house value the spec ships),
 * its [evidence] tag and [source]. [verifiedBy] is the note from the file's `"$verified"` block, if any.
 */
data class ConfigEntry(
    val file: String,
    val key: String,
    val value: Any?,
    val default: Any?,
    val evidence: Evidence,
    val source: ValueSource,
    val verifiedBy: String?,
) {
    /** True while the key is unconfirmed (HOUSE/COMM/INF) and no file has marked it verified. */
    val holdsHouseValue: Boolean get() = evidence.unconfirmed && verifiedBy == null

    /** True when the value differs from the spec default (an operator tuned it). */
    val tuned: Boolean get() = value != default

    override fun toString(): String = buildString {
        append(file).append(' ').append(key).append(" = ").append(render(value))
        append(" [").append(evidence)
        if (tuned) append(", tuned from ").append(render(default))
        if (source != ValueSource.BUNDLED) append(", ").append(source.label)
        if (verifiedBy != null) append(", verified: ").append(verifiedBy)
        append(']')
    }

    companion object {
        /** Renders a config value the way the JSON files write it. */
        @JvmStatic
        fun render(value: Any?): String = when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Enum<*> -> "\"${value.name.lowercase()}\""
            is StaticTile -> "[${value.x}, ${value.y}, ${value.plane}]"
            is IntRange -> "[${value.first}, ${value.last}]"
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { "${render(it.key)}: ${render(it.value)}" }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { render(it) }
            else -> value.toString()
        }
    }
}

enum class Severity { ERROR, WARNING }

/** A problem found while loading config: a parse error, a bad value (with what was used instead) or an unknown key. */
data class ConfigIssue(
    val file: String,
    val key: String?,
    val severity: Severity,
    val message: String,
) {
    override fun toString(): String = "$severity $file${if (key != null) " $key" else ""}: $message"
}

/** Thrown by a converter or a check when a JSON value cannot be used. */
class BadConfigValue(message: String) : RuntimeException(message, null, false, false)
