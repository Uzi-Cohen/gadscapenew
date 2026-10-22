package com.gadscape.raids.tfa.raid

import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options

/**
 * In-raid ally dialogues, from the wiki's "Transcript:The Fractured Archive (miniquest)", section "During the raid"
 * [DOC, `raids4/framework/wiki/Transcript_The_Fractured_Archive_miniquest_.wiki`]. A second Talk-to in the same raid
 * gives the transcript's short "Talking to him/her again" line. `{{tmissing}}` branches get one line [HOUSE].
 */
internal object RaidAllies {

    private const val ARMACUS = "Armacus Selles"
    private const val EVA = "Eva Cashien"
    private const val IVY = "Ivy Sophista"

    /** True the first time [player] talks to [who] in their current raid. */
    private fun firstTime(player: Player, who: String): Boolean {
        val raid = TfaRaids.raidOf(player) ?: return true
        val key = "tfa_talked_${who}_${raid.id}"
        if (player.temporaryAttributes[key] == true) return false
        player.temporaryAttributes[key] = true
        return true
    }

    fun armacus(player: Player, npc: NPC) {
        val n = player.name
        if (!firstTime(player, "armacus")) {
            player.dialogue(npc) { npc(ARMACUS, "I believe that is all the chatter we can afford, young $n. Our foes will not wait.") }
            return
        }
        player.dialogue(npc) {
            npc(ARMACUS, "Well met, $n.")
            player("Well met, Armacus.")
            options {
                dialogueOption("Thanks for helping the Alliance.") {
                    npc(ARMACUS, "Ha! It is I that should be thanking you, young $n. I understand you were the first to help rally this group, and it is a fine group indeed.")
                    player("Yes, that it is, and it will likely need to be, with what we're up against.")
                    npc(ARMACUS, "Yes... Our enemies are powerful. With the Staff and the Stone, Lucien approaches the power of the gods. Meanwhile, we have been left without ours.")
                    player("You would rather you had Armadyl at your side, then?")
                    npc(ARMACUS, "Do not mistake me, $n, Guthix's choice expelled as much evil as it did justice, but it was perhaps naive of him to think that it would rid the world of its maluses.")
                    npc(ARMACUS, "Our current predicament is evidence enough of that.")
                    player("Well, thank you for helping to defend the Archive in spite of your doubts.")
                    npc(ARMACUS, "Ha! I need not agree with my allies on every small matter. The important thing is that we've all chosen to work together on the things that are of importance.")
                    npc(ARMACUS, "The Alliance's goals foster the justice I seek. That is enough for me. Now, I believe that is all the chatter we can afford. Let us deal with the remaining villains here.")
                }
                dialogueOption("How are the defence efforts going?") {
                    npc(ARMACUS, "They hold, if by a single thread. But! It is a single thread more than our enemies possess.")
                    player("That's good news. I don't know how I feel about us hanging on by a single thread, but it's good to hear you in good spirits despite it.")
                    npc(ARMACUS, "Ha! It is important to remember, young $n, that whilst the fight for goodness and justice may feel desperate, the fight for evil is twice as much so.")
                    player("How is that?")
                    npc(ARMACUS, "Tell me, $n, despite all of its flaws, do you find the world to be a good place?")
                    options {
                        dialogueOption("Yes, I suppose I do.") {
                            npc(ARMACUS, "Then you understand why we fight. Now, I believe that is all the chatter we can afford. Let us beat back these villains once more!")
                        }
                        dialogueOption("I... Recently, I'm not sure I do.") {
                            npc(ARMACUS, "Ah... I am sorry to hear that, friend. It is true that evil's grip has been tight of late. Even the strongest resolves are being tested.")
                            npc(ARMACUS, "Perhaps we could instead say that the world contains pockets of enduring goodness?")
                            player("Yes, I suppose that is true.")
                            npc(ARMACUS, "And is the world not assailed, unendingly, by those who wish to see that change?")
                            player("Yes, all of the time.")
                            npc(ARMACUS, "And yet goodness remains. We often view our fight as one to keep the fragile flame of hope from being snuffed out.")
                            npc(ARMACUS, "But we have extinguished a million embers of evil. Ha! It is they who are on the back foot!")
                            player("I suppose I can see where you're coming from.")
                            npc(ARMACUS, "Good, good. Well, I believe that is all the chatter we can afford. Let us beat back these villains once more!")
                        }
                    }
                }
                dialogueOption("Do you need relief?") {
                    npc(ARMACUS, "Ha! I suppose I am a little winded. Our opponents are fierce, and our resources are few and spread thin. However, you need not worry about me.")
                    player("You're more useful to us healthy and rested than injured. Everybody is. We can find somebody to take your place.")
                    npc(ARMACUS, "Your concern warms my old heart, young $n. But no, I am afraid everybody else is in need of respite as much as I am, if not more. That is simply the nature of our circumstances.")
                    npc(ARMACUS, "Though our trials might not weigh quite so heavy if we were to leverage the power held within the Archive.")
                    player("We're here to protect the Archive from intruders, not to beat them to the punch.")
                    npc(ARMACUS, "I am aware, I am aware... However, I would argue our willingness to protect this place might mark us as worthy to wield its power, if only for its defence.")
                    npc(ARMACUS, "After all, should we fail, the power will end up in nefarious hands either way. But! Worry not. There is fight left in me yet. And whilst there is, such a future will not come to pass.")
                    player("Yes, you're looking better already. Perhaps we should put you on double duty!")
                    npc(ARMACUS, "Ha! A jape! At my expense, no less! Very well, I believe that is all the chatter we can afford. Let us return to battle once more!")
                }
                dialogueOption("Goodbye.") {
                    npc(ARMACUS, "Fight well, $n.")
                }
            }
        }
    }

    fun eva(player: Player, npc: NPC) {
        val n = player.name
        if (!firstTime(player, "eva")) {
            player.dialogue(npc) { npc(EVA, "Our time for discussion runs short, $n. Let us return to our duties.") }
            return
        }
        player.dialogue(npc) {
            npc(EVA, "$n.")
            player("Eva.")
            options {
                dialogueOption("How are the Temple Knights faring?") {
                    npc(EVA, "Their training is being put to the test, but they are performing admirably.")
                    player("The Alliance is lucky to have the Temple Knights' resources on its side.")
                    npc(EVA, "You are kind. The Alliance is lucky to have each other. As much as I wish it weren't so, the Temple Knights alone could not hold the Archive.")
                    player("You sound slightly disappointed.")
                    npc(EVA, "Not disappointed, but worried. We have mounted an impressive defence at short notice, but it is barely holding. Had this alliance not existed, the Archive would have been breached.")
                    npc(EVA, "Even if we succeed here, there is a lesson to be taken. The forces of evil are many and strong, and we were unprepared.")
                    player("They are many, but they are not united. We will win because we found allies, and because we worked together.")
                    npc(EVA, "You speak wisely, $n. Our time for discussion runs short, however. Let us return to our duties.")
                }
                dialogueOption("What do you make of the Archive?") {
                    npc(EVA, "It holds more than any of us understand. That is reason enough to keep it from Lucien. Our time for discussion runs short, however.")
                }
                dialogueOption("It's good to have the Temple Knights on our side.") {
                    npc(EVA, "And it is good to have you on ours, $n. Let us return to our duties.")
                }
                dialogueOption("Goodbye.") {
                    npc(EVA, "Stay safe, $n.")
                }
            }
        }
    }

    fun ivy(player: Player, npc: NPC) {
        val n = player.name
        if (!firstTime(player, "ivy")) {
            player.dialogue(npc) { npc(IVY, "Good luck in there, $n.") }
            return
        }
        player.dialogue(npc) {
            player("What's happening?")
            npc(IVY, "We're not sure. The defence has been a success. Only a small number of enemies remain for us to deal with. We were preparing to do just that when we heard a disturbance coming from within the inner vault.")
            player("How is that possible?")
            npc(IVY, "It shouldn't be. This is the only way in or out of the vault, and yet... someone... or something has managed to get in there all the same.")
            npc(IVY, "$n, I wish I weren't asking this of you, but you are one of the most capable warriors amongst us.")
            npc(IVY, "Please, enter the vault with your allies and report back with what you find. Defend it if necessary. Given there are still other enemies for us to deal with, I'm afraid it is unlikely we will be able to help.")
            player("Don't worry, Ivy, leave it with us.")
            npc(IVY, "Thank you. Good luck.")
        }
    }
}
