package com.gadscape.game.world.entity.player.status

import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player

/**
 * Burn on players (spec §2.7 E10; Smorks oil, Xarth, the Dagon'hai Zamorak tome). GadScape only burnt NPCs before.
 *
 * - [MAX_STACKS] stacks, one damage beat every [INTERVAL] ticks dealing 1 per stack, [HITS_PER_STACK] hits per stack
 *   ([BurnStacks]).
 * - Hitsplat BURN 74 ([HitType.BURN]). The hit has no source, so protection prayers and the player's damage reducers
 *   do not touch it (wiki: "The Protect from Missiles prayer will not reduce burn damage").
 * - Buff bar: varbit [BUFF_BAR_VARBIT] `player_own_burn_damage_total` holds the damage still to come (0 when clear).
 * - Cleared on death ([PlayerStatuses]) and when the player logs out (the task stops with the player).
 *
 * Each burning player has one self-stopping [WorldTask]; it lives only while stacks remain.
 */
object PlayerBurn {

    const val MAX_STACKS = 5
    const val INTERVAL = 4
    const val HITS_PER_STACK = 10

    /** `player_own_burn_damage_total` (cache 2746 varbit 10964 = bits 6-17 of varp burn_damage2; spec §5.4 [INF]). */
    const val BUFF_BAR_VARBIT = 10964

    private const val KEY = "gadscape_player_burn"

    /**
     * Adds [stacks] burn stacks (default 1). Stacks beyond [MAX_STACKS] are discarded.
     *
     * @return the number of stacks actually added
     */
    @JvmStatic
    @JvmOverloads
    fun apply(player: Player, stacks: Int = 1): Int {
        if (stacks <= 0 || player.isDead || player.isFinished) return 0
        val task = taskOf(player, create = true)!!
        val added = task.burn.add(stacks)
        if (added > 0) {
            task.start()
            sendBuffBar(player, task.burn)
        }
        return added
    }

    /** Stacks currently burning on [player]. */
    @JvmStatic
    fun stacks(player: Player): Int = taskOf(player, create = false)?.burn?.stacks ?: 0

    /** Damage still to come from [player]'s burn. */
    @JvmStatic
    fun remainingDamage(player: Player): Int = taskOf(player, create = false)?.burn?.remainingDamage ?: 0

    @JvmStatic
    fun isBurning(player: Player): Boolean = stacks(player) > 0

    /** Removes every stack (death, leaving the raid, a cure). */
    @JvmStatic
    fun clear(player: Player) {
        val task = taskOf(player, create = false) ?: return
        task.burn.clear()
        sendBuffBar(player, task.burn)
    }

    private fun taskOf(player: Player, create: Boolean): BurnTask? {
        val current = player.temporaryAttributes[KEY]
        if (current is BurnTask) return current
        if (!create) return null
        return BurnTask(player).also { player.temporaryAttributes[KEY] = it }
    }

    private fun sendBuffBar(player: Player, burn: BurnStacks) {
        player.varManager.sendBit(BUFF_BAR_VARBIT, burn.remainingDamage.coerceIn(0, 4095))
    }

    private class BurnTask(private val player: Player) : WorldTask {
        val burn = BurnStacks(MAX_STACKS, INTERVAL, HITS_PER_STACK)
        private var running = false

        fun start() {
            if (running) return
            running = true
            WorldTasksManager.schedule(this, 0, 0)
        }

        override fun run() {
            if (player.isFinished || player.isDead || burn.isEmpty) {
                burn.clear()
                finish()
                return
            }
            val damage = burn.tick()
            if (damage > 0) {
                player.applyHit(Hit(damage, HitType.BURN))
                sendBuffBar(player, burn)
            }
            if (burn.isEmpty) finish()
        }

        private fun finish() {
            running = false
            player.temporaryAttributes.remove(KEY, this)
            if (!player.isFinished) sendBuffBar(player, burn)
            stop()
        }
    }
}
