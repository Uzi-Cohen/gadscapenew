package com.gadscape.raids.tfa.ids

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KProperty

/**
 * One id named in the spec: its [kind], where it lives in [TfaIds] ([group].[name]), the [live] 2746 id the spec
 * quotes, and the [resolved] id after `remap.tsv` (equal to [live] unless the asset port moved it).
 */
data class TfaId(
    val kind: IdKind,
    val group: String,
    val name: String,
    val live: Int,
    val resolved: Int,
) {
    val remapped: Boolean get() = live != resolved
    override fun toString(): String = "$group.$name ($kind ${if (remapped) "$live -> $resolved" else "$live"})"
}

/**
 * An ordered family of ids (e.g. the 8 vault chests, the 12 wave-dust spots), each resolved through the remap
 * on its own. Index access returns a primitive `Int` (no boxing).
 */
class IdList internal constructor(private val values: IntArray) : Iterable<Int> {
    val size: Int get() = values.size
    operator fun get(index: Int): Int = values[index]
    operator fun contains(id: Int): Boolean = indexOf(id) >= 0
    fun indexOf(id: Int): Int {
        for (i in values.indices) if (values[i] == id) return i
        return -1
    }
    fun toIntArray(): IntArray = values.copyOf()
    override fun iterator(): Iterator<Int> = values.iterator()
    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]")
}

/** Property delegate holding one resolved id; `getValue` returns a primitive `Int`. */
class IdValue internal constructor(@JvmField val value: Int) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Int = value
}

/** Property delegate holding a resolved [IdList]. */
class IdListValue internal constructor(@JvmField val value: IdList) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): IdList = value
}

/**
 * Base of the [TfaIds] groups. A group declares its ids with `val NAME by id(live)`; on first use of the group
 * each id is resolved through [RemapTable.active] and recorded in [TfaIdRegistry] under its property name.
 */
abstract class IdGroup(@JvmField val kind: IdKind) {

    /** Short group name used in logs and dumps, e.g. `Npc`. */
    val groupName: String get() = this::class.simpleName ?: kind.name

    protected fun id(live: Int) = IdProvider(kind, live)

    /** A family of ids in the given order. */
    protected fun ids(vararg live: Int) = IdListProvider(kind, live)

    /** A family of consecutive ids. */
    protected fun ids(live: IntRange) = IdListProvider(kind, live.toList().toIntArray())

    class IdProvider internal constructor(private val kind: IdKind, private val live: Int) {
        operator fun provideDelegate(thisRef: IdGroup, property: KProperty<*>): IdValue {
            val resolved = RemapTable.active.resolve(kind, live)
            TfaIdRegistry.add(TfaId(kind, thisRef.groupName, property.name, live, resolved))
            return IdValue(resolved)
        }
    }

    class IdListProvider internal constructor(private val kind: IdKind, private val live: IntArray) {
        operator fun provideDelegate(thisRef: IdGroup, property: KProperty<*>): IdListValue {
            val table = RemapTable.active
            val resolved = IntArray(live.size) { table.resolve(kind, live[it]) }
            for (i in live.indices) {
                TfaIdRegistry.add(TfaId(kind, thisRef.groupName, "${property.name}[$i]", live[i], resolved[i]))
            }
            return IdListValue(IdList(resolved))
        }
    }
}

/** Every id resolved so far, in declaration order. [TfaIds.all] initialises every group first. */
object TfaIdRegistry {
    private val ids = CopyOnWriteArrayList<TfaId>()

    internal fun add(id: TfaId) {
        ids += id
    }

    /** Snapshot of the ids resolved so far. */
    fun snapshot(): List<TfaId> = ids.toList()

    val isEmpty: Boolean get() = ids.isEmpty()
}
