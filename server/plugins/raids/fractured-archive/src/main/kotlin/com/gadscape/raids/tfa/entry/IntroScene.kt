package com.gadscape.raids.tfa.entry

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.lobby.LobbyClock
import com.gadscape.raids.tfa.lobby.LobbyConfig
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.Dialogue
import com.zenyte.game.world.entity.player.dialogue.Expression
import org.slf4j.LoggerFactory

/**
 * The first-entry scene (spec §3.1 [C+]) [DOC wiki Transcript:The_Fractured_Archive_(miniquest), "Entering the
 * archive"]: Ivy Sophista, Armacus Selles and Eva Cashien brief the player at the entrance, using the no-op cutscene
 * NPCs Ivy 16729, Armacus 16727 (stand 13999) and Eva 16728 (stand 13993) [CACHE; their stands come from the npc
 * configs, no other animation is played].
 *
 * Live has a known bug where the scene can leave mobile players stuck [DOC Out Today]; this one never does:
 * - it is one dialogue, offered with "Skip the briefing." first;
 * - finishing or skipping crawls the player through to the lobby;
 * - walking away, logging out or any error just removes the three NPCs and leaves the player at the entrance with
 *   miniquest = 1, and the scene is offered again next time;
 * - the NPCs are removed after `lobby.json intro.maxTicks` whatever happens.
 */
object IntroScene {

    private val log = LoggerFactory.getLogger(IntroScene::class.java)

    /** Player attribute: the scene was finished or skipped once. */
    const val SEEN_ATTRIBUTE = "tfa_intro_seen"

    private const val IVY = "Ivy Sophista"
    private const val ARMACUS = "Armacus Selles"
    private const val EVA = "Eva Cashien"

    private val active = HashMap<Player, Scene>()

    private class Scene(val npcs: List<NPC>) {
        var completed = false
        var closed = false
    }

    @JvmStatic
    fun shouldPlay(player: Player): Boolean =
        TfaConfig.current.raid.introScene.enabled && !player.getBooleanAttribute(SEEN_ATTRIBUTE) &&
            Miniquest.stage(player) < Miniquest.ENTERED_LOBBY

    /** Plays the scene; [onFinished] runs (crawling through) only when it was finished or skipped. */
    @JvmStatic
    fun play(player: Player, onFinished: () -> Unit) {
        if (active.containsKey(player)) return
        val cfg = TfaConfig.current[LobbyConfig].intro
        val npcs = try {
            listOf(
                spawn(TfaIds.Npc.IVY_CUTSCENE, cfg.ivy, player),
                spawn(TfaIds.Npc.ARMACUS_CUTSCENE, cfg.armacus, player),
                spawn(TfaIds.Npc.EVA_CUTSCENE, cfg.eva, player),
            )
        } catch (e: Exception) {
            log.error("Fractured Archive intro: could not spawn the scene for {}", player.username, e)
            player.putBooleanAttribute(SEEN_ATTRIBUTE, true)
            onFinished()
            return
        }
        val scene = Scene(npcs)
        active[player] = scene
        LobbyClock.queue.schedule(cfg.maxTicks, tag(player)) { cleanup(player, scene) }
        val dialogue = Dialogue.create(player, TfaIds.Npc.IVY_CUTSCENE) { d -> build(d, player, scene) }
        dialogue.setOnCloseRunnable {
            cleanup(player, scene)
            if (scene.completed) {
                player.putBooleanAttribute(SEEN_ATTRIBUTE, true)
                onFinished()
            }
        }
        player.dialogueManager.start(dialogue)
    }

    /** Removes the scene NPCs of [player] (logout, timeout, end of dialogue). */
    @JvmStatic
    fun abort(player: Player) {
        active[player]?.let { cleanup(player, it) }
    }

    private fun cleanup(player: Player, scene: Scene) {
        if (scene.closed) return
        scene.closed = true
        active.remove(player)
        LobbyClock.queue.cancel(tag(player))
        scene.npcs.forEach { if (!it.isFinished) it.finish() }
    }

    private fun tag(player: Player) = "intro:${player.username}"

    private fun spawn(id: Int, tile: StaticTile, player: Player): NPC {
        val npc = NPC(id, Location(tile.x, tile.y, tile.plane), Direction.SOUTH, 0)
        npc.spawn()
        npc.setFaceEntity(player)
        return npc
    }

    private fun build(d: Dialogue, player: Player, scene: Scene) {
        val name = player.name
        fun ivy(text: String) = d.npcWithId(TfaIds.Npc.IVY_CUTSCENE, IVY, text, Expression.DEFAULT)
        fun armacus(text: String) = d.npcWithId(TfaIds.Npc.ARMACUS_CUTSCENE, ARMACUS, text, Expression.DEFAULT)
        fun eva(text: String) = d.npcWithId(TfaIds.Npc.EVA_CUTSCENE, EVA, text, Expression.DEFAULT)

        // Key 1: the skip offer; key 2 onwards: the transcript.
        d.options("The Alliance wishes to brief you.",
            Dialogue.DialogueOption("Listen to the briefing.", d.key(2)),
            Dialogue.DialogueOption("Skip the briefing.") {
                scene.completed = true
                player.dialogueManager.finish()
            })
        ivy("Hello agai-")
        armacus("Greetings, adventurer! Have you come to join the ranks of the Alliance?")
        ivy("Armacus, this is $name, the adventurer I told you about.")
        armacus("Aha! Well met, young $name.")
        eva("I have heard good things from my uncle. It is good to meet you, $name.")
        d.player("Well met, and likewise. I received your message and came as soon as I could.")
        ivy("Thank you. This is Eva Cashien and Armacus Selles. They are leading the Temple Knights and the Guardians of Armadyl who have mobilised to defend the Archive.")
        if (!Miniquest.hasReadMessage(player)) {
            d.player("The Archive?")
            ivy("You didn't receive my message? Concerning...")
            ivy("It seems the Stone of Jas wasn't the only thing Guthix hid down here. This Archive was built to house various other powerful artefacts, to stop them falling into the wrong hands.")
            ivy("However, thanks to Lucien, it is now at risk. His followers are preparing to attack the Archive. Not only that, but it seems various other groups are also mobilising their forces.")
            d.player("I see. What's the current situation? Have your soldiers entered the Archive already?")
        } else {
            d.player("What's the current situation? Have your soldiers entered the Archive already?")
        }
        eva("No, they are still on their way. We came ahead of time to scout out the situation.")
        d.player("And what do you make of it?")
        armacus("It's quite the pickle we're in, I'm afraid! Our enemies approach from all sides. We have identified six vulnerable chambers altogether.")
        eva("What's more, the central chamber we intended to use to establish fortifications appears to be guarded by some sort of hostile creature.")
        ivy("It is likely one of Guthix's creations, left behind to defend the Archive from intruders.")
        d.player("Could we leverage it to help us?")
        ivy("I'm afraid not... It might hold off some of the intruders, but it won't manage all of them. And without getting in there to establish defences of our own, we won't stand a much better of a chance.")
        d.player("I see... That's unfortunate.")
        armacus("Indeed! But not all is bad news. Several of our enemies have clashed on their way to the Archive, and it's bought us some time.")
        armacus("If a brave young adventurer were to appear and clear the way for us to set up our defences, whilst we gathered our troops...")
        armacus("But wait! We have one right here!")
        d.plain("Armacus laughs.")
        eva("Armacus' jesting aside, we're hoping you might be able to help us establish a foothold.")
        d.player("I see. And do we know anything specific about what artefacts are hidden here?")
        ivy("It seems to be all manner of things. Most of them likely aren't too valuable to our foes, but the Archive has an inner vault.")
        ivy("We're not sure exactly what's inside, but we can't take any risks. We need to assume that the contents could have the potential to be on par with the Stone of Jas itself.")
        d.player("And our foes? What do we know about them?")
        eva("Lucien's forces are being led by someone called Moia. Our records have little information on her, but if Lucien has trusted her with this task, we must assume she is very dangerous.")
        armacus("However, luckily for us, there does seem to have been some splintering within Lucien's ranks. The Dagon'hai no longer appear to be working with him. Instead, they seem intent on raiding the Archive for themselves.")
        ivy("There is also a goblin warband that has somehow found out about this place. I fear we may have other foes as well that have not yet revealed themselves.")
        d.player("Sounds like we'd best not waste any time then.")
        eva("Thank you, $name. Once you've cleared the central chamber, we'll split up and tackle our enemies one at a time.")
        ivy("We'll be leaning heavily on you once again, $name. Our forces will do what they can to lift your burden, but our enemies are powerful, and you have more experience than most.")
        d.player("Thank you, I'll-")
        armacus("Indeed! They are powerful. But! You need not face them alone. I have no doubt that an enterprising young adventurer such as yourself has many capable and jolly allies.")
        armacus("Now is the time to unite, and leverage the strength of our co-operation against the weakness of our foes' greed.")
        d.player("I'll see what I can do.")
        eva("We must go and ready our forces. We will see you inside.")
        ivy("Good luck, $name.").executeAction { scene.completed = true }
        d.plain("All three of them leave, with Armacus bowing before he goes.")
    }
}
