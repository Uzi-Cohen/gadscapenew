package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.config.RaidConfig
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.entry.Miniquest
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.stats.StatsInterface979
import com.gadscape.raids.tfa.stats.TfaStats
import com.zenyte.game.GameInterface
import com.zenyte.game.item.Item
import com.zenyte.game.model.item.ItemOnNPCAction
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.Dialogue
import com.zenyte.game.world.entity.player.dialogue.dialogue
import org.slf4j.LoggerFactory

/**
 * Lobby NPCs (spec §3.2) [CACHE npc configs; DOC wiki positions and transcripts]:
 * General Montoya 16705 (4117,5550), Record Keeper 16692 (4136,5543, stand 15057), Wizard apprentice 16693
 * (4114,5539), Banker 16704 (4135,5555, stand 15055). Their stand animations come from the npc configs; nothing else
 * is played. Druids and decoration NPCs have no published positions and are not spawned.
 */
object LobbyNpcs {

    private val log = LoggerFactory.getLogger(LobbyNpcs::class.java)

    private val spawned = ArrayList<NPC>()

    fun npcId(role: LobbyNpcRole): Int = when (role) {
        LobbyNpcRole.GENERAL_MONTOYA -> TfaIds.Npc.GENERAL_MONTOYA
        LobbyNpcRole.RECORD_KEEPER -> TfaIds.Npc.RECORD_KEEPER
        LobbyNpcRole.WIZARD_APPRENTICE -> TfaIds.Npc.WIZARD_APPRENTICE
        LobbyNpcRole.BANKER -> TfaIds.Npc.BANKER
    }

    fun direction(c: Compass): Direction = when (c) {
        Compass.N -> Direction.NORTH
        Compass.NE -> Direction.NORTH_EAST
        Compass.E -> Direction.EAST
        Compass.SE -> Direction.SOUTH_EAST
        Compass.S -> Direction.SOUTH
        Compass.SW -> Direction.SOUTH_WEST
        Compass.W -> Direction.WEST
        Compass.NW -> Direction.NORTH_WEST
    }

    /** Spawns the configured NPCs (skipping any already standing there from another spawn source). */
    @JvmStatic
    fun spawnAll(cfg: LobbyConfig) {
        for (spawn in cfg.npcs) {
            try {
                val id = npcId(spawn.role)
                val tile = Location(spawn.tile.x, spawn.tile.y, spawn.tile.plane)
                if (World.getNPCs().any { it != null && it.id == id && !it.isFinished && it.location.withinDistance(tile, 3) }) continue
                val npc = NPC(id, tile, direction(spawn.facing), 0)
                npc.spawn()
                spawned += npc
            } catch (e: Exception) {
                log.error("Fractured Archive lobby: could not spawn {}", spawn, e)
            }
        }
    }
}

/** Talk-to on General Montoya 16705 [DOC wiki Transcript:General_Montoya]. Plugin-scanned. */
class GeneralMontoyaPlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc -> LobbyDialogues.montoya(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.GENERAL_MONTOYA)
}

/** Record Keeper 16692 [DOC wiki Transcript:Record_Keeper]. Plugin-scanned. */
class RecordKeeperPlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc -> LobbyDialogues.recordKeeper(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.RECORD_KEEPER)
}

/** Horn of Bran on the Record Keeper (spec §3.2 [HOUSE]). Plugin-scanned. */
class RecordKeeperHornAction : ItemOnNPCAction {
    override fun handleItemOnNPCAction(player: Player, item: Item, slot: Int, npc: NPC) {
        LobbyDialogues.recordKeeperHorn(player, npc)
    }

    override fun getItems(): Array<Any> = arrayOf(TfaIds.Obj.HORN_OF_BRAN)

    override fun getObjects(): Array<Any> = arrayOf(TfaIds.Npc.RECORD_KEEPER)
}

/** Wizard apprentice 16693 [DOC wiki Transcript:Wizard_apprentice]; the first talk sets varbit 16427. Plugin-scanned. */
class WizardApprenticePlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc -> LobbyDialogues.wizardApprentice(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.WIZARD_APPRENTICE)
}

/** Banker 16704: Talk-to / Bank / Collect [CACHE ops; DOC wiki Transcript:Banker_(The_Fractured_Archive)]. Plugin-scanned. */
class LobbyBankerPlugin : NPCPlugin() {
    override fun handle() {
        bind("Talk-to") { player, npc -> LobbyDialogues.banker(player, npc) }
        bind("Bank") { player, _ -> GameInterface.BANK.open(player) }
        bind("Collect") { player, _ -> GameInterface.GRAND_EXCHANGE_COLLECTION_BOX.open(player) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.BANKER)
}

/** The lobby dialogues. Text from the wiki transcripts [DOC] unless marked [HOUSE]. */
object LobbyDialogues {

    private const val MONTOYA = "General Montoya"
    private const val KEEPER = "Record Keeper"
    private const val APPRENTICE = "Wizard apprentice"
    private const val BANKER = "Banker"

    /** Player attribute: the Record Keeper's first dialogue was seen. */
    const val RECORD_KEEPER_MET = "tfa_record_keeper_met"

    // ------------------------------------------------------------------------------------------- General Montoya

    fun montoya(player: Player, target: NPC) {
        player.dialogue(target) {
            player("Hello!")
            npc(MONTOYA, "Good day!")
            player("What do you do here?")
            npc(MONTOYA, "I organise people according to the Alliance's defence plans. That means bringing newcomers quickly up to speed, and communicating any changes to the plan across the troops.")
            montoyaOptions(this, player, target)
        }
    }

    private fun montoyaOptions(d: Dialogue, player: Player, target: NPC) {
        d.options(Dialogue.TITLE,
            Dialogue.DialogueOption("Tell me more about the Archive.") { montoyaArchive(player, target) },
            Dialogue.DialogueOption("Who are our enemies?") { montoyaEnemies(player, target) },
            Dialogue.DialogueOption("Where is everybody else?") { montoyaEveryone(player, target) },
            Dialogue.DialogueOption("Thank you.") {
                player.dialogue(target) {
                    player("Thank you.")
                    npc(MONTOYA, "It was my pleasure, ${player.name}. Good luck in there.")
                }
            })
    }

    private fun montoyaArchive(player: Player, target: NPC) = player.dialogue(target) {
        val pronoun = if (player.appearance.isMale) "his" else "her"
        player("Tell me more about the Archive.")
        npc(MONTOYA, "You can see the Archive's layout on the board beside me. The central chamber, which we're calling the Nexus, is currently occupied by a hostile automaton. We plan to take that chamber and establish our defence there.")
        player("And by 'we' you mean me?")
        npc(MONTOYA, "Indeed. Before he left, Armacus informed me you would clear the way whilst he and the others mobilised the rest of the troops.")
        player("He did, did he?")
        npc(MONTOYA, "Yes! He seemed confident you would help. 'I can see it in $pronoun eyes. The flame of justice!'")
        npc(MONTOYA, "He is a bit eccentric, but he is a fine leader, and a good judge of character.")
        player("At least one of those is true. What comes after the Nexus?")
        npc(MONTOYA, "There are six peripheral chambers we expect to come under attack, all of which can be reached from the Nexus. As soon as the Nexus is secured, we will begin deploying troops to the furthest chambers.")
        npc(MONTOYA, "They will hold the line whilst we work on clearing the foremost ones. We are relying on you to take one side whilst we take the other.")
        player("So that makes... the Nexus plus three chambers you want taking care of?")
        npc(MONTOYA, "Yes. It is no small ask, but we do not think we can manage any more ourselves. I would suggest you seek allies of your own to help you.")
        player("I'll see what I can do.")
        montoyaOptions(this, player, target)
    }

    private fun montoyaEnemies(player: Player, target: NPC) = player.dialogue(target) {
        player("Who are our enemies?")
        npc(MONTOYA, "We have followers of Lucien, Zamorak and Bandos to deal with. Evidence also suggests there are other foes yet to fully reveal themselves. For example, there have been reports of a lone knight.")
        player("A lone knight?")
        npc(MONTOYA, "Yes. The scouting report is spotty. Only one of our people made it back alive, but they were heavily wounded. Apparently this knight took out a whole squadron by himself.")
        player("That's concerning... What about the others?")
        npc(MONTOYA, "The Bandosian forces are mostly goblins. You wouldn't think they'd pose much of a threat, but they've managed to build some sort of siege engine. If that weren't enough, they're being lead by a huge ork.")
        npc(MONTOYA, "As for the forces of Lucien and Zamorak, we spotted a clash between the two factions in some of the deeper tunnels. An army of skeletons facing off against a group of Zamorakian mages.")
        player("The Dagon'hai?")
        npc(MONTOYA, "Interestingly, no, but we'll get to them. We managed to capture one of the mages and interrogate them. They refused to say much, but we gather they're trying to summon some sort of powerful demon.")
        player("And the skeletons?")
        npc(MONTOYA, "There seems to be an endless tide of them. A necromancer must be present. It's possible this is the work of Moia, the leader of Lucien's forces.")
        player("And let me guess, there's more?")
        npc(MONTOYA, "Quite... You mentioned the Dagon'hai. A group of them were spotted near the Stone of Jas' old location. They seemed to be fending off an unusually capable tormented demon.")
        player("In what way?")
        npc(MONTOYA, "Most tormented demons seem to struggle with the strength of their own flames. This one seemed to fully control them, lending it both greater power and durability.")
        player("Did it manage to get rid of the Dagon'hai?")
        npc(MONTOYA, "No, they disengaged and made for a tunnel we think leads to the Archive. The demon followed, but went down a different path.")
        player("That's quite the list we're up against.")
        npc(MONTOYA, "Yes... but we are not without allies either. The Alliance is rallying as we speak. Together we can do this.")
        montoyaOptions(this, player, target)
    }

    private fun montoyaEveryone(player: Player, target: NPC) = player.dialogue(target) {
        player("Where is everybody else?")
        npc(MONTOYA, "Armacus, Eva and Ivy have gone to rally the rest of the forces. We are only a small vanguard sent to assess the situation and prepare if necessary.")
        player("Let's hope they don't take too long.")
        npc(MONTOYA, "Fear not, they will soon return with help. Once they are here, we estimate we can defeat three sets of intruders. As for the rest... we will be relying on you and any allies you can gather yourself.")
        montoyaOptions(this, player, target)
    }

    // --------------------------------------------------------------------------------------------- Record Keeper

    fun recordKeeper(player: Player, target: NPC) {
        if (player.containsItem(TfaIds.Obj.HORN_OF_BRAN)) return recordKeeperHorn(player, target)
        if (offerArchiveKey(player, target)) return
        if (!player.getBooleanAttribute(RECORD_KEEPER_MET)) {
            player.putBooleanAttribute(RECORD_KEEPER_MET, true)
            player.dialogue(target) {
                player("Hello.")
                npc(KEEPER, "...")
                player("Hello?")
                npc(KEEPER, "Hmm? What what?")
                player("I hope I'm not interrupting.")
                npc(KEEPER, "Interrupting who?")
                player("You?")
                npc(KEEPER, "What about me?")
                player("Am I interrupting you?")
                npc(KEEPER, "I'm not sure... What is it you're interrupting me for?")
                player("Well, I was just going to ask what you're doing, but I'm starting to regret it.")
                npc(KEEPER, "Why didn't you say so, my boy!")
                keeperIntro(this)
                viewStats(this, player, target)
            }
            return
        }
        player.dialogue(target) {
            player("Hello.")
            npc(KEEPER, "The Aspirant approaches! What will the records tell of you, I wonder?")
            keeperOptions(this, player, target)
        }
    }

    private fun keeperIntro(d: Dialogue) {
        d.npc(KEEPER, "I... am... the Keeper of the Records!")
        d.player("...")
        d.npc(KEEPER, "...")
        d.player("You're a record keeper?")
        d.npc(KEEPER, "THE Record Keeper!")
        d.npc(KEEPER, "These sensitive records are under my precise curation!")
    }

    private fun viewStats(d: Dialogue, player: Player, target: NPC) {
        d.npc(KEEPER, "The secrets of the Archive, the progress of the Alliance, the very steps you took towards me today, all recorded in these... very... pages!")
        d.player("Can I have a loo-")
        d.npc(KEEPER, "No!")
        d.player("Sorry, I was just interested.")
        d.npc(KEEPER, "Hmm... I suppose you may view some of the records, should your curiosity need satisfying.")
        viewStatsQuestion(d, player, target)
    }

    private fun viewStatsQuestion(d: Dialogue, player: Player, target: NPC) {
        d.options("View Stats?",
            Dialogue.DialogueOption("Yes.") { StatsInterface979.open(player, inActiveRaid = false) },
            Dialogue.DialogueOption("No.") { player.dialogue(target) { keeperOptions(this, player, target) } })
    }

    private fun keeperOptions(d: Dialogue, player: Player, target: NPC) {
        d.options(Dialogue.TITLE,
            Dialogue.DialogueOption("What are you doing?") {
                player.dialogue(target) {
                    player("What are you doing?")
                    keeperIntro(this)
                    player("Can I have a loo-")
                    npc(KEEPER, "No!")
                    player("Sorry, I was just interested.")
                    npc(KEEPER, "Hmm... I suppose you may view some of the records, should your curiosity need satisfying.")
                    viewStatsQuestion(this, player, target)
                }
            },
            Dialogue.DialogueOption("How are things?") {
                player.dialogue(target) {
                    player("How are things?")
                    npc(KEEPER, "The Alliance is struggling to hold off the threats to the Archive. And the records are eager to chronicle your deeds in this matter, if you are so inclined.")
                    npc(KEEPER, "You may view some of the records, should your curiosity need satisfying.")
                    viewStatsQuestion(this, player, target)
                }
            },
            Dialogue.DialogueOption("Goodbye.") {
                player.dialogue(target) {
                    player("Goodbye.")
                    npc(KEEPER, "Fare-thee-well, Aspirant, we watch you with great interest.")
                }
            })
    }

    /**
     * The Horn of Bran (spec §3.2, §3.16) [HOUSE]: Jagex's hint is "perhaps the Record Keeper can help?" [DOC]; he sends
     * the player to Camelot (the Round Table scene that unlocks Zeal belongs to task #50).
     */
    fun recordKeeperHorn(player: Player, target: NPC) {
        player.dialogue(target) {
            npc(KEEPER, "Hmm? What what? Is that... a horn? Let me see that, Aspirant!")
            player("I found it in the Archive's vault. Do you know what it is?")
            npc(KEEPER, "The records speak of a horn carried by Bran, a knight who served a king of old. Such a relic belongs at the Round Table of Camelot, not in my pages!")
            npc(KEEPER, "Take it to Camelot, Aspirant, and see what its knights make of it.")
        }
    }

    /**
     * The Archive key 34658 (spec §3.17) [HOUSE]: with `tfa.json recordsKey.source = "record_keeper"`, the Record
     * Keeper hands it over once KC ≥ `recordsKey.kc`, while the records room is locked (varbit 16383 = 0) and the
     * player has no key. Returns true if he did (the dialogue is shown).
     */
    private fun offerArchiveKey(player: Player, target: NPC): Boolean {
        val key = TfaConfig.current.raid.recordsKey
        if (key.source != RaidConfig.KeySource.RECORD_KEEPER) return false
        if (TfaStats.killCount(player) < key.kc) return false
        if (player.varManager.getBitValue(TfaIds.Varbit.RECORDS_ROOM_UNLOCKED) == 1) return false
        if (player.containsItem(TfaIds.Obj.ARCHIVE_KEY) || player.bank.containsItem(Item(TfaIds.Obj.ARCHIVE_KEY))) return false
        player.dialogue(target) {
            npc(KEEPER, "The Aspirant returns! ${key.kc} completions of the Archive... the records have taken note of you.")
            npc(KEEPER, "Take this. It was found within the Archive, and I believe it opens a door the records do not describe.")
            item(Item(TfaIds.Obj.ARCHIVE_KEY), "The Record Keeper hands you a key-like object made of stone and vines.").executeAction {
                player.inventory.addOrDrop(Item(TfaIds.Obj.ARCHIVE_KEY))
            }
        }
        return true
    }

    // ------------------------------------------------------------------------------------------ Wizard apprentice

    fun wizardApprentice(player: Player, target: NPC) {
        val first = !Miniquest.hasMetWizardApprentice(player)
        player.dialogue(target) {
            if (first) {
                player("Hello.")
                npc(APPRENTICE, "Hello!")
                player("Who are you?")
                npc(APPRENTICE, "I am the record keeper's... yes 'the' record keeper's... apprentice.")
                player("The record keeper is a wizard?")
                npc(APPRENTICE, "No, I'm a wizard, and his apprentice separately.")
                player("Oh... I see. And what do you do here?")
                npc(APPRENTICE, "I record the Archive's effects on its surroundings. Namely, the changes in those big crystals behind you.")
                player("That sounds interesting. Can I help somehow?")
                npc(APPRENTICE, "Why yes you can! The latent magical energy of the Archive collects in these crystals, and they seem to react to impact in interesting ways.")
                player("So you want me to mine the rocks?")
                npc(APPRENTICE, "Yes! Exactly.")
                player("Oh...")
                npc(APPRENTICE, "Oh?")
                player("I thought the work would be more... scientific.")
            } else {
                player("Hello again.")
                npc(APPRENTICE, "Hello there. My research on these crystals is going well. Don't forget, you can always help by mining some for me.")
                player("I suppose I can, but I must admit, I thought the work would be more... scientific.")
            }
            npc(APPRENTICE, "Oh but it is! Their reactions are incredible! For example, hitting the rock will occasionally cause nearby ambient magic to collate into what I'm calling a sprite.")
            npc(APPRENTICE, "The sprite is not a liquid, gas or a solid. It is the magical state of the crystal rocks! How exciting is that?! A material with a magical state!")
            player("That sounds quite interesting actually.")
            npc(APPRENTICE, "Yes! And what is more, merely noticing the sprite will cause it to immediately change into its solid crystal form, gravitating towards the source of attention.")
            player("That's incredible!")
            npc(APPRENTICE, "Ahaha, now you see the value of the work. But that's not all! The root system of the Archive seems to be trying to keep the crystals bound. But if the crystals are broken down enough, the roots retreat.")
            player("Sentient roots?!")
            npc(APPRENTICE, "Well, that's what we're here to find out! Without the roots, the crystals are fully exposed for collection.")
            player("And should I bring you back what I mine?")
            npc(APPRENTICE, "No, no, I have no use for rocks. Keep them! They're densely magical, I'm sure you can find some use for them.")
            player("Okay, thanks. I think I'll give that a go.").executeAction { Miniquest.setWizardApprenticeMet(player) }
        }
    }

    // ---------------------------------------------------------------------------------------------------- Banker

    fun banker(player: Player, target: NPC) {
        player.dialogue(target) {
            npc(BANKER, "Oh, hello. How can I help?")
            options(Dialogue.TITLE,
                Dialogue.DialogueOption("I'd like to access my bank account, please.") { GameInterface.BANK.open(player) },
                Dialogue.DialogueOption("I'd like to collect items.") { GameInterface.GRAND_EXCHANGE_COLLECTION_BOX.open(player) },
                Dialogue.DialogueOption("What's your function here?") { bankerFunction(player, target) })
        }
    }

    private fun bankerFunction(player: Player, target: NPC) = player.dialogue(target) {
        player("What's your function here?")
        npc(BANKER, "I'm the Alliance's travelling liaison with the Bank of Gielinor. I make sure everybody has everything they need, wherever we go.")
        player("Must be nice getting to travel around for work.")
        npc(BANKER, "I'd enjoy it a lot more if our destinations weren't generally so dangerous, but yes, I like seeing the world.")
        player("What's the book for then?")
        npc(BANKER, "Once we're settled in, my job involves a lot of standing around. There's only so much you can appreciate a cavern, even one as impressive as this.")
        player("That makes sense. And what's the book about?")
        npc(BANKER, "It's a collection of poems believed to be from the Second Age.")
        player("Interesting. Could you read me one?")
        npc(BANKER, "Of course.")
        npc(BANKER, "...")
        npc(BANKER, "'Flowers wilt and grow. Pruned carefully as they go. Were it that this flower, could live a life its own.'")
        player("...")
        player("I'm not sure I understand.")
        npc(BANKER, "There's no real need to. It's just a poem.")
        player("So it has no meaning?")
        npc(BANKER, "It means something to me.")
        player("Okay, what does it mean to you?")
        npc(BANKER, "Hmm... I suppose it's a reflection on the way our surroundings shape us. Or maybe it's arguing that wilder gardens are more beautiful.")
        player("Maybe... I think I'll leave you to your reading now.")
        npc(BANKER, "Of course. Let me know if you need anything else.")
    }
}
