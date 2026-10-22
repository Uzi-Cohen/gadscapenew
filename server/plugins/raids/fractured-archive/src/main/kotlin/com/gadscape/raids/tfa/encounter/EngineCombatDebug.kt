package com.gadscape.raids.tfa.encounter

import com.gadscape.game.world.Lines
import com.gadscape.game.world.entity.player.status.PlayerBleed
import com.gadscape.game.world.entity.player.status.PlayerBurn
import com.gadscape.game.world.entity.player.status.PlayerStatuses
import com.gadscape.game.world.entity.player.status.PrayerDrainOnAttack
import com.gadscape.game.world.entity.player.status.VirusOutbreak
import com.gadscape.game.world.entity.player.status.VirusSettings
import com.gadscape.game.world.entity.player.status.Weary
import com.gadscape.raids.tfa.debug.TfaCommands
import com.zenyte.game.task.WorldTask
import com.zenyte.game.task.WorldTasksManager
import com.zenyte.game.world.entity.Entity
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.CharacterLoop
import java.util.Random

/**
 * `::tfa combat ...` (engine-combat, WU-04/05/07): puts a raid boss's traits on any NPC and applies the player
 * statuses to yourself, so the engine hooks can be checked in game without a raid.
 *
 * - `::tfa combat traits <preset|off>`: on your last target, else the nearest NPC within 8 tiles. Presets are the
 *   spec §4 values ([PRESETS]).
 * - `::tfa combat burn [stacks]`, `weary [ticks]`, `bleed`, `virus`, `drain [points]`, `clear`.
 * - `::tfa combat ray <x> <y>`: the Bresenham line from you to (x, y) and whether the map clips a projectile.
 */
object EngineCombatDebug {

    private val boss = CombatTraits.BOSS_DEFAULT
    private val add = CombatTraits.ADD_DEFAULT

    /** Spec §4 combat profiles (DOC/CACHE values; [HOUSE] where the spec says so). */
    val PRESETS: Map<String, CombatTraits> = linkedMapOf(
        "guardian" to boss.copy(weakness = Element.FIRE, weaknessPct = 80, rangedDef = RangedDefence(75, 120, 50),
            freezeResistPct = 100, immuneAncients = true, magicDrainInverted = true, flying = true),
        "guardian_grounded" to boss.copy(weakness = Element.FIRE, weaknessPct = 40, rangedDef = RangedDefence(50, 115, 50),
            freezeResistPct = 100, immuneAncients = true, magicDrainInverted = true),
        "bork" to boss.copy(weakness = Element.EARTH, weaknessPct = 40, rangedDef = RangedDefence(80, 50, 5),
            flatArmour = 12, magicResist = 2, freezeResistPct = 100),
        "bork_dazed" to boss.copy(weakness = Element.EARTH, weaknessPct = 40, rangedDef = RangedDefence(80, 50, 5),
            freezeResistPct = 100, guaranteedMelee = true),
        "smorks" to boss.copy(defenceFloorPct = null, weakness = Element.EARTH, weaknessPct = 85,
            rangedDef = RangedDefence(80, 150, 80), flatArmour = 2, freezeResistPct = 100),
        "dreadnoth" to boss.copy(defenceFloorPct = null, weakness = Element.FIRE, weaknessPct = 85, immuneVenom = false,
            rangedDef = RangedDefence(100, 80, 80), freezeResistPct = 40, demonbanePct = 25),
        "jelly" to add.copy(weakness = Element.FIRE, weaknessPct = 60, rangedDef = RangedDefence(-45, 18, -50),
            freezeResistPct = 20, demonbanePct = 100),
        "xarth" to boss.copy(weakness = Element.WATER, weaknessPct = 85, rangedDef = RangedDefence(125, 230, 155),
            freezeResistPct = 100, demonbanePct = 80),
        "corpse" to add.copy(weakness = Element.WATER, weaknessPct = 40, rangedDef = RangedDefence(-20, 80, 80)),
        "ocellus" to boss.copy(rangedDef = RangedDefence(50, 135, 50), demonbanePct = 80),
    )

    @JvmStatic
    fun register() {
        TfaCommands.register("combat", "combat <traits|burn|weary|bleed|virus|drain|clear|ray> [args]",
            "engine-combat checks: boss traits on any NPC, player statuses on yourself, LOS rays") { player, args ->
            execute(player, args)
        }
    }

    private fun execute(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "traits" -> traits(player, args.getOrNull(1)?.lowercase())
            "burn" -> {
                val added = PlayerBurn.apply(player, args.getOrNull(1)?.toIntOrNull() ?: 1)
                player.sendMessage("Burn: +$added, ${PlayerBurn.stacks(player)} stacks, ${PlayerBurn.remainingDamage(player)} damage to come.")
            }
            "weary" -> {
                Weary.apply(player, args.getOrNull(1)?.toIntOrNull() ?: Weary.DEFAULT_TICKS)
                player.sendMessage("Weary for ${Weary.remainingTicks(player)} ticks.")
            }
            "bleed" -> {
                PlayerBleed.apply(player)
                player.sendMessage("Bleeding (Vardorvis rules, 15 t; walk to bleed every tick).")
            }
            "virus" -> virus(player)
            "drain" -> player.sendMessage("Prayer drained: ${PrayerDrainOnAttack.drain(player, null, args.getOrNull(1)?.toIntOrNull() ?: 2)}")
            "clear" -> {
                PlayerStatuses.clear(player)
                player.sendMessage("Burn, weary, bleed and virus cleared.")
            }
            "ray" -> ray(player, args)
            else -> player.sendMessage("::tfa combat traits <${PRESETS.keys.joinToString("|")}|off> | burn [n] | weary [t] | bleed | virus | drain [n] | clear | ray <x> <y>")
        }
    }

    private fun traits(player: Player, preset: String?) {
        val npc = (player.lastTarget as? NPC)?.takeIf { !it.isFinished && it.location.withinDistance(player.location, 15) }
            ?: CharacterLoop.find(player.location, 8, NPC::class.java) { !it.isFinished && !it.isDead }
                .minByOrNull { it.location.getTileDistance(player.location) }
        if (npc == null) {
            player.sendMessage("No NPC nearby.")
            return
        }
        if (preset == null || preset == "off") {
            npc.combatTraits = null
            player.sendMessage("${npc.definitions.name} (${npc.id}): traits removed.")
            return
        }
        val traits = PRESETS[preset]
        if (traits == null) {
            player.sendMessage("Presets: ${PRESETS.keys.joinToString()}")
            return
        }
        npc.combatTraits = traits.toEngine()
        player.sendMessage("${npc.definitions.name} (${npc.id}) now has the $preset traits: $traits")
    }

    private fun virus(player: Player) {
        val outbreak = VirusOutbreak.ofEntities(VirusSettings(), Random())
        outbreak.infect(player)
        var ticks = 0
        WorldTasksManager.schedule(object : WorldTask {
            override fun run() {
                if (++ticks > 40 || outbreak.carriers.isEmpty()) {
                    outbreak.clearAll()
                    stop()
                    return
                }
                outbreak.tick(CharacterLoop.find(player.location, 3, Entity::class.java) { true })
            }
        }, 0, 0)
        player.sendMessage("Debug virus: you are infected for 20 t; it spreads to anyone next to a carrier for 40 t.")
    }

    private fun ray(player: Player, args: List<String>) {
        val x = args.getOrNull(1)?.toIntOrNull()
        val y = args.getOrNull(2)?.toIntOrNull()
        if (x == null || y == null) {
            player.sendMessage("::tfa combat ray <x> <y>")
            return
        }
        val line = Lines.bresenham(player.x, player.y, x, y).joinToString(" ") { "(${Lines.tileX(it)},${Lines.tileY(it)})" }
        val clipped = Lines.isClipped(player.location, Location(x, y, player.plane))
        player.sendMessage("Ray: $line")
        player.sendMessage("Projectile clipped: $clipped")
    }
}
