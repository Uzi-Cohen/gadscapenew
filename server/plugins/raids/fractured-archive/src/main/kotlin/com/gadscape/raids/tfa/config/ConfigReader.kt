package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.raid.StaticTile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal

/**
 * Typed, validating access to one JSON object of a config file, used inside [TfaConfigSpec.read].
 *
 * Each call names a key, its **default** (the value the spec ships), its [Evidence] tag and optional bounds:
 * ```
 * override fun read(r: ConfigReader) = GuardianConfig(
 *     enragePct = r.int("enragePct", 30, Evidence.COMM, min = 1, max = 99),
 *     lift = r.obj("lift") { Lift(int("mainStepPct", 35), int("maxGroundedTicks", 24, min = 1)) },
 * )
 * ```
 * Resolution per key: the override file (`data/raids/tfa/<file>`) wins, then the bundled default file, then the
 * code default. A value that has the wrong type or fails its bounds is reported (file, key, reason, and what was
 * used instead) and the next source is used; loading never fails. Keys that no read consumed are reported as
 * unknown (catches typos). Keys starting with `$` or `_` are reserved for metadata and comments.
 *
 * Every key read outside list elements becomes a [ConfigEntry], which is what `::tfa dump` and the load log
 * list as "still holding a house value".
 */
class ConfigReader internal constructor(
    private val session: ReadSession,
    /** Dotted path of this object from the file root; empty at the root. */
    val path: String,
    private val layers: List<Layer>,
    private val element: ElementScope?,
) {

    internal class Layer(val source: ValueSource, val label: String, val obj: JsonObject?) {
        val consumed = HashSet<String>()
    }

    /** Collects errors while reading one element of an [objList]; a failed element rejects the whole list. */
    internal class ElementScope {
        val errors = ArrayList<String>()
    }

    private val children = ArrayList<ConfigReader>()

    /** The config file being read, e.g. `guardian.json`. */
    val file: String get() = session.file

    fun keyPath(key: String): String = if (path.isEmpty()) key else "$path.$key"

    // ---------------------------------------------------------------------------------------------- scalars

    fun int(
        key: String, default: Int, evidence: Evidence = Evidence.HOUSE,
        min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE, check: ((Int) -> String?)? = null,
    ): Int = read(key, default, evidence, { el, _ -> JsonValues.int(el) }) { v -> bounds(v, min, max) ?: check?.invoke(v) }

    /** An integer that may be JSON `null` (e.g. an optional combat-level override). */
    fun intOrNull(
        key: String, default: Int?, evidence: Evidence = Evidence.HOUSE,
        min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE,
    ): Int? = read(key, default, evidence, { el, _ -> if (el.isJsonNull) null else JsonValues.int(el) }) { v ->
        if (v == null) null else bounds(v, min, max)
    }

    fun double(
        key: String, default: Double, evidence: Evidence = Evidence.HOUSE,
        min: Double = -Double.MAX_VALUE, max: Double = Double.MAX_VALUE, check: ((Double) -> String?)? = null,
    ): Double = read(key, default, evidence, { el, _ -> JsonValues.double(el) }) { v ->
        (if (v < min || v > max) "must be between $min and $max, got $v" else null) ?: check?.invoke(v)
    }

    fun bool(key: String, default: Boolean, evidence: Evidence = Evidence.HOUSE): Boolean =
        read(key, default, evidence, { el, _ -> JsonValues.bool(el) }, null)

    fun string(
        key: String, default: String, evidence: Evidence = Evidence.HOUSE,
        allowed: Collection<String>? = null, check: ((String) -> String?)? = null,
    ): String = read(key, default, evidence, { el, _ -> JsonValues.string(el) }) { v ->
        (if (allowed != null && v !in allowed) "must be one of ${allowed.joinToString { "\"$it\"" }}, got \"$v\"" else null)
            ?: check?.invoke(v)
    }

    /**
     * An enum written as its lower-case name (`"half_up"` for `HALF_UP`; case and `-`/`_` are ignored).
     */
    fun <E : Enum<E>> enum(key: String, default: E, evidence: Evidence = Evidence.HOUSE): E {
        val constants = default.declaringJavaClass.enumConstants
        return read(key, default, evidence, { el, _ ->
            val text = JsonValues.string(el)
            val norm = text.trim().lowercase().replace('-', '_')
            constants.firstOrNull { it.name.lowercase() == norm }
                ?: throw BadConfigValue("must be one of ${constants.joinToString { "\"${it.name.lowercase()}\"" }}, got \"$text\"")
        }, null)
    }

    // ------------------------------------------------------------------------------------------------ lists

    fun intList(
        key: String, default: List<Int>, evidence: Evidence = Evidence.HOUSE,
        size: IntRange? = null, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE,
        check: ((List<Int>) -> String?)? = null,
    ): List<Int> = read(key, default, evidence, { el, _ -> JsonValues.array(el).mapIndexed { i, e -> JsonValues.at(i) { JsonValues.int(e) } } }) { v ->
        sizeCheck(v.size, size) ?: v.withIndex().firstNotNullOfOrNull { (i, x) -> bounds(x, min, max)?.let { "element $i $it" } }
            ?: check?.invoke(v)
    }

    fun doubleList(
        key: String, default: List<Double>, evidence: Evidence = Evidence.HOUSE,
        size: IntRange? = null, min: Double = -Double.MAX_VALUE, max: Double = Double.MAX_VALUE,
    ): List<Double> = read(key, default, evidence, { el, _ -> JsonValues.array(el).mapIndexed { i, e -> JsonValues.at(i) { JsonValues.double(e) } } }) { v ->
        sizeCheck(v.size, size) ?: v.withIndex().firstNotNullOfOrNull { (i, x) ->
            if (x < min || x > max) "element $i must be between $min and $max, got $x" else null
        }
    }

    fun stringList(
        key: String, default: List<String>, evidence: Evidence = Evidence.HOUSE,
        size: IntRange? = null, allowed: Collection<String>? = null,
    ): List<String> = read(key, default, evidence, { el, _ -> JsonValues.array(el).mapIndexed { i, e -> JsonValues.at(i) { JsonValues.string(e) } } }) { v ->
        sizeCheck(v.size, size) ?: if (allowed != null) v.firstOrNull { it !in allowed }?.let { "\"$it\" is not one of ${allowed.joinToString { a -> "\"$a\"" }}" } else null
    }

    /** A `[low, high]` pair, e.g. a damage roll `[35, 45]`; `low <= high` is enforced. */
    fun intRange(
        key: String, default: IntRange, evidence: Evidence = Evidence.HOUSE,
        min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE,
    ): IntRange = read(key, default, evidence, { el, _ ->
        val arr = JsonValues.array(el)
        if (arr.size() != 2) throw BadConfigValue("expected [low, high], got ${JsonValues.describe(el)}")
        val lo = JsonValues.at(0) { JsonValues.int(arr[0]) }
        val hi = JsonValues.at(1) { JsonValues.int(arr[1]) }
        if (lo > hi) throw BadConfigValue("low $lo is above high $hi")
        lo..hi
    }) { v -> bounds(v.first, min, max) ?: bounds(v.last, min, max) }

    /** A static tile written `[x, y, plane]` (plane 0-3). */
    fun tile(key: String, default: StaticTile, evidence: Evidence = Evidence.HOUSE): StaticTile =
        read(key, default, evidence, { el, _ -> JsonValues.tile(el) }, null)

    fun tileList(key: String, default: List<StaticTile>, evidence: Evidence = Evidence.HOUSE, size: IntRange? = null): List<StaticTile> =
        read(key, default, evidence, { el, _ -> JsonValues.array(el).mapIndexed { i, e -> JsonValues.at(i) { JsonValues.tile(e) } } }) { v ->
            sizeCheck(v.size, size)
        }

    // ------------------------------------------------------------------------------------------------- maps

    /** An object whose keys are data (e.g. `"combatLevelOverride": {"moia": 1064}`). Read as one value. */
    fun intMap(
        key: String, default: Map<String, Int>, evidence: Evidence = Evidence.HOUSE,
        min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE,
    ): Map<String, Int> = read(key, default, evidence, { el, _ ->
        JsonValues.obj(el).entrySet().associate { (k, v) -> k to JsonValues.at(k) { JsonValues.int(v) } }
    }) { v -> v.entries.firstNotNullOfOrNull { (k, x) -> bounds(x, min, max)?.let { "\"$k\" $it" } } }

    /** An object of integer lists keyed by data (e.g. `"overrides": {"xarth_corpse_hp": [20, ...]}`). */
    fun intListMap(
        key: String, default: Map<String, List<Int>>, evidence: Evidence = Evidence.HOUSE,
        listSize: IntRange? = null, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE,
    ): Map<String, List<Int>> = read(key, default, evidence, { el, _ ->
        JsonValues.obj(el).entrySet().associate { (k, v) ->
            k to JsonValues.at(k) { JsonValues.array(v).mapIndexed { i, e -> JsonValues.at(i) { JsonValues.int(e) } } }
        }
    }) { v ->
        v.entries.firstNotNullOfOrNull { (k, list) ->
            sizeCheck(list.size, listSize)?.let { "\"$k\" $it" }
                ?: list.withIndex().firstNotNullOfOrNull { (i, x) -> bounds(x, min, max)?.let { "\"$k\" element $i $it" } }
        }
    }

    // ---------------------------------------------------------------------------------------------- objects

    /**
     * A nested object. Each key inside is resolved on its own (an override may set only some of them).
     * A non-object value is reported and ignored.
     */
    fun <T> obj(key: String, read: ConfigReader.() -> T): T {
        val full = keyPath(key)
        val childLayers = layers.map { layer ->
            val src = layer.obj
            val el = if (src != null && src.has(key)) {
                layer.consumed += key
                src.get(key)
            } else null
            val child = when {
                el == null || el.isJsonNull -> null
                el.isJsonObject -> el.asJsonObject
                else -> {
                    report(full, "expected an object, got ${JsonValues.describe(el)} (in ${layer.label}); ignoring it")
                    null
                }
            }
            Layer(layer.source, layer.label, child)
        }
        val reader = ConfigReader(session, full, childLayers, element)
        children += reader
        return reader.read()
    }

    /**
     * A list of objects (e.g. Bork's tempo table). The list is one value: every element must read cleanly
     * (no missing or bad keys), otherwise the whole list from that file is rejected and the next source is used.
     */
    fun <T> objList(
        key: String, default: List<T>, evidence: Evidence = Evidence.HOUSE,
        size: IntRange? = null, read: ConfigReader.() -> T,
    ): List<T> = read(key, default, evidence, { el, layer ->
        val full = keyPath(key)
        JsonValues.array(el).mapIndexed { i, e ->
            if (!e.isJsonObject) throw BadConfigValue("element $i: expected an object, got ${JsonValues.describe(e)}")
            val scope = ElementScope()
            val reader = ConfigReader(session, "$full[$i]", listOf(Layer(layer.source, layer.label, e.asJsonObject)), scope)
            val value = reader.read()
            reader.finish()
            if (scope.errors.isNotEmpty()) throw BadConfigValue("element $i: ${scope.errors.joinToString("; ")}")
            value
        }
    }) { v -> sizeCheck(v.size, size) }

    /**
     * Reports a cross-field problem (e.g. `minScale > maxScale`) against [key] when [ok] is false. The values stay
     * as read; the spec decides whether to repair them. Returns [ok].
     */
    fun check(key: String, ok: Boolean, message: () -> String): Boolean {
        if (!ok) report(keyPath(key), message())
        return ok
    }

    // ------------------------------------------------------------------------------------------- internals

    private fun <T> read(
        key: String, default: T, evidence: Evidence,
        convert: (JsonElement, Layer) -> T, check: ((T) -> String?)?,
    ): T {
        val full = keyPath(key)
        if (element != null) return readElement(key, full, default, convert, check)
        // The key is known in every file that has it, even the ones a higher layer overrides.
        for (layer in layers) if (layer.obj?.has(key) == true) layer.consumed += key
        var problem: String? = null
        for (layer in layers) {
            val src = layer.obj ?: continue
            if (!src.has(key)) continue
            val error = try {
                val value = convert(src.get(key), layer)
                val failed = check?.invoke(value)
                if (failed == null) {
                    if (problem != null) {
                        session.issue(full, Severity.ERROR, "$problem; using the ${layer.source.label} ${ConfigEntry.render(value)}")
                    }
                    session.record(full, value, default, evidence, layer.source)
                    return value
                }
                failed
            } catch (e: BadConfigValue) {
                e.message
            }
            problem = (if (problem == null) "" else "$problem; ") + "$error (in ${layer.label})"
        }
        if (problem != null) {
            session.issue(full, Severity.ERROR, "$problem; using the code default ${ConfigEntry.render(default)}")
        }
        session.record(full, default, default, evidence, ValueSource.DEFAULT)
        return default
    }

    private fun <T> readElement(
        key: String, full: String, default: T,
        convert: (JsonElement, Layer) -> T, check: ((T) -> String?)?,
    ): T {
        val scope = element!!
        val layer = layers.single()
        val src = layer.obj
        if (src == null || !src.has(key)) {
            scope.errors += "$full is missing"
            return default
        }
        layer.consumed += key
        return try {
            val value = convert(src.get(key), layer)
            val failed = check?.invoke(value)
            if (failed != null) {
                scope.errors += "$full $failed"
                default
            } else value
        } catch (e: BadConfigValue) {
            scope.errors += "$full ${e.message}"
            default
        }
    }

    private fun report(fullKey: String, message: String) {
        if (element != null) element.errors += "$fullKey: $message" else session.issue(fullKey, Severity.ERROR, message)
    }

    /** Reports keys that no read consumed, in this object and every nested one. */
    internal fun finish() {
        for (layer in layers) {
            val src = layer.obj ?: continue
            for (k in src.keySet()) {
                if (k in layer.consumed || k.startsWith("$") || k.startsWith("_")) continue
                session.issue(keyPath(k), Severity.WARNING, "unknown key in ${layer.label} (ignored)")
            }
        }
        children.forEach { it.finish() }
    }

    private fun bounds(v: Int, min: Int, max: Int): String? =
        if (v < min || v > max) "must be between $min and $max, got $v" else null

    private fun sizeCheck(size: Int, range: IntRange?): String? =
        if (range != null && size !in range) {
            if (range.first == range.last) "must have ${range.first} elements, got $size" else "must have ${range.first}-${range.last} elements, got $size"
        } else null
}

/** Collects the entries and issues of one spec read. */
internal class ReadSession(val file: String, private val verified: Map<String, String>) {
    val entries = ArrayList<ConfigEntry>()
    val issues = ArrayList<ConfigIssue>()

    fun record(key: String, value: Any?, default: Any?, evidence: Evidence, source: ValueSource) {
        entries += ConfigEntry(file, key, value, default, evidence, source, verifiedNote(key))
    }

    fun issue(key: String?, severity: Severity, message: String) {
        issues += ConfigIssue(file, key, severity, message)
    }

    /** A key is verified when `$verified` names it or one of its parent objects. */
    private fun verifiedNote(key: String): String? =
        verified[key] ?: verified.entries.firstOrNull { (k, _) -> key.startsWith("$k.") || key.startsWith("$k[") }?.value
}

/** JSON → Kotlin conversions that throw [BadConfigValue] with a readable reason. */
internal object JsonValues {

    fun describe(el: JsonElement): String {
        val text = el.toString()
        return if (text.length > 40) text.take(37) + "..." else text
    }

    private fun primitive(el: JsonElement, what: String): JsonPrimitive {
        if (!el.isJsonPrimitive) throw BadConfigValue("expected $what, got ${describe(el)}")
        return el.asJsonPrimitive
    }

    fun int(el: JsonElement): Int {
        val p = primitive(el, "an integer")
        if (!p.isNumber) throw BadConfigValue("expected an integer, got ${describe(el)}")
        val number: BigDecimal = try {
            p.asBigDecimal
        } catch (e: NumberFormatException) {
            throw BadConfigValue("expected an integer, got ${describe(el)}")
        }
        if (number.signum() != 0 && number.stripTrailingZeros().scale() > 0) {
            throw BadConfigValue("expected an integer, got $number")
        }
        return try {
            number.intValueExact()
        } catch (e: ArithmeticException) {
            throw BadConfigValue("$number does not fit in an integer")
        }
    }

    fun double(el: JsonElement): Double {
        val p = primitive(el, "a number")
        if (!p.isNumber) throw BadConfigValue("expected a number, got ${describe(el)}")
        val value = try {
            p.asDouble
        } catch (e: NumberFormatException) {
            throw BadConfigValue("expected a number, got ${describe(el)}")
        }
        if (!value.isFinite()) throw BadConfigValue("expected a finite number, got ${describe(el)}")
        return value
    }

    fun bool(el: JsonElement): Boolean {
        val p = primitive(el, "true or false")
        if (!p.isBoolean) throw BadConfigValue("expected true or false, got ${describe(el)}")
        return p.asBoolean
    }

    fun string(el: JsonElement): String {
        val p = primitive(el, "a string")
        if (!p.isString) throw BadConfigValue("expected a string, got ${describe(el)}")
        return p.asString
    }

    fun array(el: JsonElement): JsonArray {
        if (!el.isJsonArray) throw BadConfigValue("expected an array, got ${describe(el)}")
        return el.asJsonArray
    }

    fun obj(el: JsonElement): JsonObject {
        if (!el.isJsonObject) throw BadConfigValue("expected an object, got ${describe(el)}")
        return el.asJsonObject
    }

    fun tile(el: JsonElement): StaticTile {
        val arr = array(el)
        if (arr.size() != 3) throw BadConfigValue("expected a tile [x, y, plane], got ${describe(el)}")
        val x = at(0) { int(arr[0]) }
        val y = at(1) { int(arr[1]) }
        val plane = at(2) { int(arr[2]) }
        if (x < 0 || y < 0 || plane !in 0..3) throw BadConfigValue("tile [$x, $y, $plane] is out of range (plane 0-3)")
        return StaticTile(x, y, plane)
    }

    /** Prefixes a conversion failure with the element index or map key. */
    inline fun <T> at(where: Any, convert: () -> T): T = try {
        convert()
    } catch (e: BadConfigValue) {
        throw BadConfigValue(if (where is Int) "element $where: ${e.message}" else "\"$where\": ${e.message}")
    }
}
