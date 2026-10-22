package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.plugins.dialogue.ItemChat;
import com.zenyte.plugins.dialogue.PlainChat;

/**
 * Teleport anchoring scroll (OSRS wiki): reading it consumes it and makes the player permanently immune to the
 * teleport attacks of elder chaos druids and abyssal demons (not the Abyssal Sire, Chaos Elemental or Tele Block).
 */
public final class TeleportAnchoringScroll extends ItemPlugin {

    private static final String ATTRIBUTE = "teleport_anchoring";
    private static final Animation READ_ANIM = new Animation(7403);

    /** Whether the player read the scroll (elder chaos druid and abyssal demon teleports do nothing). */
    public static boolean isAnchored(final Player player) {
        return player.getNumericAttribute(ATTRIBUTE).intValue() == 1;
    }

    @Override
    public void handle() {
        bind("Read", (player, item, slotId) -> {
            if (isAnchored(player)) {
                player.getDialogueManager().start(new PlainChat(player, "You can make out some faded words on the ancient parchment, but there's nothing more for you to learn."));
                return;
            }
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    item(item, "You can make out some faded words on the ancient parchment. Would you like to absorb their power?");
                    options("This will consume the scroll.", "Read the scroll.", "Cancel").onOptionOne(() -> read(player, item, slotId));
                }
            });
        });
    }

    private static void read(final Player player, final Item item, final int slotId) {
        if (player.getInventory().getItem(slotId) != item || isAnchored(player)) {
            return;
        }
        player.lock(3);
        player.setAnimation(READ_ANIM);
        player.getInventory().deleteItem(slotId, item);
        player.addAttribute(ATTRIBUTE, 1);
        player.getDialogueManager().start(new ItemChat(player, item, "You study the scroll and become immune to the Abyssal demon and Elder Chaos druid teleportation attacks."));
    }

    @Override
    public int[] getItems() {
        return new int[] {ItemId.TELEPORT_ANCHORING_SCROLL};
    }
}
