package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.WorldObject

/*
 * Loc and NPC handlers of the raid's nexus and rooms (spec §3.5, §3.6, §5.9; plugin-scanned). Each one finds the
 * player's raid and hands over to it; outside a raid they do nothing.
 */

private fun raidOf(player: Player): TfaRaid? = TfaRaids.raidOf(player)?.takeIf { it.memberOf(player) != null }

/** The paired room whose static map square holds [obj]. */
private fun roomAt(raid: TfaRaid, obj: WorldObject): TfaRoomId? {
    val s = raid.instance.toStatic(obj.location) ?: return null
    return TfaRoomId.entries.firstOrNull { it.isPaired && it.staticRegion == s.regionId }
}

/** Section doors 63088-63107: Enter / Quick-enter (§3.5 step 3). */
class SectionDoorAction : ObjectAction {
    override fun handleObjectAction(player: Player?, `object`: WorldObject?, name: String?, optionId: Int, option: String?) {
        player ?: return
        val obj = `object` ?: return
        val raid = raidOf(player) ?: return
        val (section, side) = RaidGeometry.doorOf(obj.id) ?: return
        raid.nexus.enterDoor(player, section, side, quick = option.equals("Quick-enter", ignoreCase = true))
    }

    override fun getObjects(): Array<Any> {
        val out = ArrayList<Any>()
        for (section in 1..3) for (side in Side.entries) {
            val ids = RaidGeometry.doorIds(section, side)
            out += ids.enter
            out += ids.slightly
            out += ids.opened
        }
        return out.toTypedArray()
    }
}

/** Archive transporters 63087 / 62610 (Moia) / 63134 (Dreadnoth): Use / Quick-use (§2.4 room flow, §3.5 step 4). */
class TransporterAction : ObjectAction {
    override fun handleObjectAction(player: Player?, `object`: WorldObject?, name: String?, optionId: Int, option: String?) {
        player ?: return
        val obj = `object` ?: return
        val raid = raidOf(player) ?: return
        val room = roomAt(raid, obj) ?: return
        raid.useTransporter(player, room)
    }

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Loc.TRANSPORTER, TfaIds.Loc.TRANSPORTER_MOIA, TfaIds.Loc.TRANSPORTER_DREADNOTH)
}

/** Passageway 62765 "Exit" in every paired room: back to the nexus arrival tile (§3.5 step 5). */
class PassagewayAction : ObjectAction {
    override fun handleObjectAction(player: Player?, `object`: WorldObject?, name: String?, optionId: Int, option: String?) {
        player ?: return
        val obj = `object` ?: return
        val raid = raidOf(player) ?: return
        val room = roomAt(raid, obj) ?: return
        raid.usePassageway(player, room)
    }

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Loc.PASSAGEWAY)
}

/** The Ocellus "Archive Door" 63111: Enter / Quick-enter to the corridor (§3.6). */
class OcellusDoorAction : ObjectAction {
    override fun handleObjectAction(player: Player?, `object`: WorldObject?, name: String?, optionId: Int, option: String?) {
        player ?: return
        val raid = raidOf(player) ?: return
        raid.enterOcellus(player)
    }

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Loc.OCELLUS_DOOR_OPEN)
}

/** Alliance Navigator 16700: Follow / Quick-follow (§3.5 step 2). */
class NavigatorPlugin : NPCPlugin() {
    override fun handle() {
        bind("Follow") { player, npc -> raidOf(player)?.nexus?.follow(player, npc, quick = false) }
        bind("Quick-follow") { player, npc -> raidOf(player)?.nexus?.follow(player, npc, quick = true) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.NAVIGATOR)
}

/**
 * The Quartermaster (§3.12): 16761 Resupply / Talk-to after room 3; 16762 (inactive) Talk-to before it
 * ("I'm still setting up." [HOUSE]).
 */
class QuartermasterPlugin : NPCPlugin() {
    override fun handle() {
        bind("Resupply") { player, _ -> resupply(player) }
        bind("Talk-to") { player, npc -> talk(player, npc) }
    }

    private fun resupply(player: Player) {
        val raid = raidOf(player) ?: return
        val member = raid.memberOf(player) ?: return
        raid.supplies.open(member)
    }

    private fun talk(player: Player, npc: NPC) {
        val raid = raidOf(player) ?: return
        if (npc.id == TfaIds.Npc.QUARTERMASTER_INACTIVE || !raid.supplies.granted) {
            player.dialogue(npc.id) { npc("Quartermaster", "I'm still setting up. Come back once the third foe has fallen.") }
            return
        }
        player.dialogue(npc.id) {
            npc("Quartermaster", "Help yourself to what little supplies we can offer to aid you in the battles to come.")
            options("Resupply?") {
                "Yes." { resupply(player) }
                "No." {}
            }
        }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.QUARTERMASTER, TfaIds.Npc.QUARTERMASTER_INACTIVE)
}

/**
 * The in-raid allies (§3.5 step 7; Transcript: The Fractured Archive (miniquest), "During the raid" [DOC]):
 * Armacus 16696, Eva 16697 (camp, after the Guardian) and Ivy 16698 (NEXUS_FINAL brief). Options the wiki marks
 * `{{tmissing}}` get a one-line in-character reply [HOUSE].
 */
class RaidAlliesPlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc ->
            when (npc.id) {
                TfaIds.Npc.ARMACUS_TALK -> RaidAllies.armacus(player, npc)
                TfaIds.Npc.EVA_TALK -> RaidAllies.eva(player, npc)
                TfaIds.Npc.IVY -> RaidAllies.ivy(player, npc)
            }
        }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.ARMACUS_TALK, TfaIds.Npc.EVA_TALK, TfaIds.Npc.IVY)
}
