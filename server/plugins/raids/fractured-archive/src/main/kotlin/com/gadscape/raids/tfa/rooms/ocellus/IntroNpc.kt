package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * Talk-to / Proceed on the intro NPC 16671 and its multi forms 16672 "Hooded Figure" / 16673 "Ocellus" [CACHE]
 * (spec §4.8 INTRO):
 * - first Talk-to (varbit 16384 = 0): the Transcript:Ocellus "First completion" dialogue [DOC], then 16384 = 1;
 * - later Talk-to: one line, then the question;
 * - Proceed (met only): straight to "Are you ready for the final challenge?";
 * - Yes starts the fight; any raid member may answer [HOUSE].
 * Plugin-scanned (`NPCPlugin`).
 */
class OcellusIntroPlugin : NPCPlugin() {

    override fun handle() {
        bind("Talk-to") { player, npc -> OcellusIntro.talk(player, npc) }
        bind("Proceed") { player, npc -> OcellusIntro.proceed(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(
        TfaIds.Npc.OCELLUS_INTRO, TfaIds.Npc.OCELLUS_INTRO_NOT_MET, TfaIds.Npc.OCELLUS_INTRO_MET,
    )
}

internal object OcellusIntro {

    private fun roomOf(player: Player, npc: NPC): OcellusRoom? {
        val room = (npc as? OcellusIntroNpc)?.room ?: return null
        if (player !in room.context.members) {
            player.sendMessage("Ocellus pays you no attention.")
            return null
        }
        if (room.phase != OcellusPhase.INTRO) return null
        return room
    }

    fun talk(player: Player, npc: NPC) {
        val room = roomOf(player, npc) ?: return
        if (!Bridge.isOcellusMet(player)) firstMeeting(player, room) else metAgain(player, room)
    }

    fun proceed(player: Player, npc: NPC) {
        val room = roomOf(player, npc) ?: return
        if (!Bridge.isOcellusMet(player)) {
            firstMeeting(player, room)
            return
        }
        ask(player, room)
    }

    /** Transcript:Ocellus, "First completion" [DOC wiki, `raids4/ocellus/wiki/Transcript_Ocellus.wiki`]. */
    private fun firstMeeting(player: Player, room: OcellusRoom) {
        val figure = "Hooded Figure"
        player.dialogue(TfaIds.Npc.OCELLUS_INTRO_NOT_MET) {
            player("You... Who are you? And how did you get in here?")
            npc(figure, "Human... You stand before Ocellus... Chosen... of Guthix...")
            player("You serve Guthix?")
            npc(figure, "Yes... He charged me... to guard the source of his power... But his enemies approach... They are not worthy of it...")
            player("Well, then we're on the same side. I'm part of an alliance defending this place. The danger is mostly gone though. We held off the attackers.")
            npc(figure, "No... Your alliance... is false... Armadyl... Saradomin... They are no better... than the rest... They are not worthy... of this power...")
            player("We don't want it! We just want to keep this place safe!")
            npc(figure, "Wrong... Many try... but none can resist... You will try to take it... His blade...")
            npc(figure, "There is only one solution... Only Guthix himself is worthy... but he is long gone...")
            npc(figure, "To keep his power safe... I will take his place...")
            npc(figure, "The time has come... for ascension.").executeAction { Bridge.setOcellusMet(player) }
            options("Are you ready for the final challenge?") {
                "Yes." { room.requestStart(player) }
                "No." { finish() }
            }
        }
    }

    /** Met before (16673 "Ocellus"): one line [HOUSE], then the question. */
    private fun metAgain(player: Player, room: OcellusRoom) {
        player.dialogue(TfaIds.Npc.OCELLUS_INTRO_MET) {
            npc("Ocellus", "The time has come... for ascension.")
            options("Are you ready for the final challenge?") {
                "Yes." { room.requestStart(player) }
                "No." { finish() }
            }
        }
    }

    private fun ask(player: Player, room: OcellusRoom) {
        player.dialogue(TfaIds.Npc.OCELLUS_INTRO_MET) {
            options("Are you ready for the final challenge?") {
                "Yes." { room.requestStart(player) }
                "No." { finish() }
            }
        }
    }
}
