package com.zenyte.game.content.skills.prayer;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.ItemOnNPCAction;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.NpcId;
import com.zenyte.game.world.entity.npc.actions.NPCPlugin;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.plugins.dialogue.ItemChat;

/**
 * Zeal (The Fractured Archive, 20 Oct 2026): the Horn of Bran, taken to King Arthur in Camelot, unlocks Zeal, which
 * replaces Piety in the prayer book (varbit 16578 prayer_zeal_unlocked). OSRS plays a cutscene with King Arthur; here
 * the presentation is a dialogue with him, started by talking to him with the horn or using the horn on him.
 */
public final class HornOfBran {

    private HornOfBran() {
    }

    /** The King Arthur npcs of Camelot castle (3531 is the spawned one). */
    private static final Object[] KING_ARTHURS = {NpcId.KING_ARTHUR};

    private static boolean carriesHorn(final Player player) {
        return player.getInventory().containsItem(PrayerItemIds.HORN_OF_BRAN, 1);
    }

    static void present(final Player player, final NPC arthur) {
        if (player.getPrayerManager().isUnlocked(Prayer.ZEAL)) {
            player.getDialogueManager().start(new Dialogue(player, arthur) {
                @Override
                public void buildDialogue() {
                    npc("Your devotion is already known to the knights of the Round Table. May Zeal serve you well.");
                }
            });
            return;
        }
        player.getDialogueManager().start(new Dialogue(player, arthur) {
            @Override
            public void buildDialogue() {
                player("Your Majesty, I recovered this horn from the Fractured Archive. The inscription says it belonged to someone called Bran.");
                npc("The Horn of Bran! One of the Thirteen Treasures of Britain, lost for longer than Camelot has stood.");
                npc("Its return is a deed worthy of the Round Table. Let it rest here, and take the blessing it carries: a prayer of greater zeal than Piety itself.");
                options("Give the Horn of Bran to King Arthur?", "Yes, present the horn.", "No, keep it for now.")
                        .onOptionOne(() -> {
                            finish();
                            unlock(player);
                        });
            }
        });
    }

    private static void unlock(final Player player) {
        if (player.getPrayerManager().isUnlocked(Prayer.ZEAL)) {
            return;
        }
        if (!player.getInventory().deleteItem(new Item(PrayerItemIds.HORN_OF_BRAN, 1)).isFailure()) {
            player.getPrayerManager().unlock(Prayer.ZEAL);
            player.getDialogueManager().start(new ItemChat(player, new Item(PrayerItemIds.HORN_OF_BRAN),
                    "You present the Horn of Bran to King Arthur and learn a new prayer: <col=FF0040>Zeal</col>. It replaces Piety in your prayer book."));
        }
    }

    /** "Inspect" on the horn. */
    public static final class HornItem extends ItemPlugin {
        @Override
        public void handle() {
            bind("Inspect", (player, item, slotId) -> {
                if (player.getPrayerManager().isUnlocked(Prayer.ZEAL)) {
                    player.sendMessage("Based on text inscribed upon it, it presumably belonged to someone called Bran. You have already unlocked Zeal.");
                    return;
                }
                player.sendMessage("Based on text inscribed upon it, it presumably belonged to someone called Bran. King Arthur in Camelot may know more about it.");
            });
        }

        @Override
        public int[] getItems() {
            return new int[] {PrayerItemIds.HORN_OF_BRAN};
        }
    }

    /** Talking to King Arthur with the horn in the inventory presents it. */
    public static final class KingArthur extends NPCPlugin implements ItemOnNPCAction {
        @Override
        public void handle() {
            bind("Talk-to", (player, npc) -> {
                if (carriesHorn(player) && !player.getPrayerManager().isUnlocked(Prayer.ZEAL)) {
                    present(player, npc);
                    return;
                }
                player.getDialogueManager().start(new Dialogue(player, npc) {
                    @Override
                    public void buildDialogue() {
                        npc("Welcome to the court of King Arthur. Camelot's doors are open to any brave adventurer.");
                    }
                });
            });
        }

        @Override
        public int[] getNPCs() {
            return new int[] {NpcId.KING_ARTHUR};
        }

        @Override
        public void handleItemOnNPCAction(final Player player, final Item item, final int slot, final NPC npc) {
            present(player, npc);
        }

        @Override
        public Object[] getItems() {
            return new Object[] {PrayerItemIds.HORN_OF_BRAN};
        }

        @Override
        public Object[] getObjects() {
            return KING_ARTHURS;
        }
    }
}
