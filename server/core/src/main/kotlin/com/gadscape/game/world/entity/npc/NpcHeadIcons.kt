package com.gadscape.game.world.entity.npc

/**
 * Head icons of an NPC in up to [SLOTS] slots (E13). The 241 NPC info block HEADICON_CUSTOMISATION carries
 * eight slots of (sprite group, index); the client draws every filled slot above the NPC, slot 0 lowest.
 * Zenyte's `NPC.setOverhead` only drives slot 0; an NPC that implements [MultiHeadIconNpc] gets all eight
 * from this holder (slot 0 falls back to `NPC.getOverhead()` while it is empty here).
 *
 * Thread: world thread only (the info update reads it on the world thread).
 *
 * Example (Ocellus with Deflect Melee and Deflect Mage, spec §4.8 / WU-06):
 * ```
 * headIcons.set(0, NpcHeadIcons.GROUP_PRAYER, NpcHeadIcons.DEFLECT_MELEE)
 * headIcons.set(1, NpcHeadIcons.GROUP_PRAYER, NpcHeadIcons.DEFLECT_MAGE)
 * ```
 */
class NpcHeadIcons {

    private val groups = IntArray(SLOTS) { -1 }
    private val indices = IntArray(SLOTS) { -1 }

    /** Bumped on every change; lets the info update skip unchanged NPCs cheaply. */
    var version: Int = 0
        private set

    /** Shows sprite [index] of sprite group [group] in [slot] (0..7). */
    fun set(slot: Int, group: Int, index: Int) {
        require(slot in 0 until SLOTS) { "head icon slot $slot, expected 0..${SLOTS - 1}" }
        require(group >= 0 && index in 0..0xFFFF) { "head icon $group/$index" }
        if (groups[slot] == group && indices[slot] == index) return
        groups[slot] = group
        indices[slot] = index
        version++
    }

    /** Empties [slot]. */
    fun clear(slot: Int) {
        require(slot in 0 until SLOTS) { "head icon slot $slot, expected 0..${SLOTS - 1}" }
        if (groups[slot] == -1 && indices[slot] == -1) return
        groups[slot] = -1
        indices[slot] = -1
        version++
    }

    /** Empties every slot. */
    fun clearAll() {
        var changed = false
        for (slot in 0 until SLOTS) {
            if (groups[slot] != -1 || indices[slot] != -1) changed = true
            groups[slot] = -1
            indices[slot] = -1
        }
        if (changed) version++
    }

    /** Replaces every slot: slot i shows [prayerIndices][i] of [GROUP_PRAYER]; the other slots are emptied. */
    fun setPrayers(vararg prayerIndices: Int) {
        require(prayerIndices.size <= SLOTS) { "${prayerIndices.size} head icons, at most $SLOTS" }
        clearAll()
        prayerIndices.forEachIndexed { slot, index -> set(slot, GROUP_PRAYER, index) }
    }

    /** The sprite group in [slot], or -1 when the slot is empty. */
    fun group(slot: Int): Int = groups[slot]

    /** The sprite index in [slot], or -1 when the slot is empty. */
    fun index(slot: Int): Int = indices[slot]

    fun isEmpty(slot: Int): Boolean = groups[slot] < 0 || indices[slot] < 0

    override fun toString(): String =
        (0 until SLOTS).filterNot(::isEmpty).joinToString(prefix = "NpcHeadIcons(", postfix = ")") { "$it=${groups[it]}/${indices[it]}" }

    companion object {
        const val SLOTS = 8

        /**
         * Sprite group 440 `headicons_prayer` (the NPC overhead prayers). Indexes [CACHE 2746, spec §5.7 / E13]:
         * 0 Protect from Melee, 1 Protect from Missiles, 2 Protect from Magic (as Zenyte's NpcOverhead),
         * 11 Soul Split, 12 Deflect Melee, 13 Deflect Missiles, 14 Deflect Magic.
         */
        const val GROUP_PRAYER = 440
        const val PROTECT_MELEE = 0
        const val PROTECT_MISSILES = 1
        const val PROTECT_MAGIC = 2
        const val SOUL_SPLIT = 11
        const val DEFLECT_MELEE = 12
        const val DEFLECT_MISSILES = 13
        const val DEFLECT_MAGIC = 14
    }
}

/** An NPC whose head icons come from [headIcons] (all eight slots) instead of only `NPC.setOverhead` (slot 0). */
interface MultiHeadIconNpc {
    val headIcons: NpcHeadIcons
}
