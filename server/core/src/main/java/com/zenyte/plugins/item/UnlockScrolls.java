package com.zenyte.plugins.item;

import com.zenyte.game.item.Item;
import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.plugins.dialogue.ItemChat;
import com.zenyte.plugins.dialogue.PlainChat;

/**
 * "Read" on the unlock scrolls the cache has without a handler (sets-prayers area, SKSP-18):
 * <ul>
 *     <li>Target teleport scroll (12846): unlocks Teleport to Bounty Target (85 Magic). The unlock is stored as the
 *     {@link #TARGET_TELEPORT_UNLOCKED} attribute and shown to the client with varbit 2010 (sent on login); the spell
 *     itself is Bounty Hunter content ({@code BountyTargetStructure}, which can check {@link #knowsTargetTeleport}).</li>
 *     <li>Leagues / Deadman cosmetic scrolls (teleport animation scrolls, Trailblazer reloaded relic scrolls,
 *     Armageddon scrolls): their magic only works on a League/Deadman world, so reading them says so and keeps them.</li>
 * </ul>
 */
public final class UnlockScrolls extends ItemPlugin {

    public static final String TARGET_TELEPORT_UNLOCKED = "target_teleport_unlocked";
    /** varbit 2010 bounty_target_teleport: the spellbook shows Teleport to Bounty Target as learnt while it is 1. */
    public static final int TARGET_TELEPORT_VARBIT = 2010;
    private static final int TARGET_TELEPORT_SCROLL = 12846;
    private static final Animation READ_ANIM = new Animation(7403);

    /** League teleport animation scrolls, Trailblazer reloaded scrolls and Deadman Armageddon scrolls (2746 ids). */
    private static final int[] SEASONAL_SCROLLS = {24460, 25087, 26500, 27416, 28693, 28696, 28699, 28705, 29622, 29625};

    @Override
    public void handle() {
        bind("Read", (player, item, slotId) -> {
            if (item.getId() == TARGET_TELEPORT_SCROLL) {
                readTargetTeleport(player, item, slotId);
                return;
            }
            player.getDialogueManager().start(new PlainChat(player, "The scroll's magic is bound to the Leagues and Deadman worlds. It does nothing here."));
        });
    }

    private static void readTargetTeleport(final Player player, final Item item, final int slotId) {
        if (knowsTargetTeleport(player)) {
            player.getDialogueManager().start(new PlainChat(player, "You already know how to cast Teleport to Bounty Target."));
            return;
        }
        if (player.getSkills().getLevelForXp(SkillConstants.MAGIC) < 85) {
            player.getDialogueManager().start(new PlainChat(player, "You need a <col=000080>Magic</col> level of 85 to learn this spell."));
            return;
        }
        player.getDialogueManager().start(new Dialogue(player) {
            @Override
            public void buildDialogue() {
                item(item, "The scroll describes how to teleport to your Bounty Hunter target. Would you like to learn the spell?");
                options("This will consume the scroll.", "Learn the spell.", "Cancel").onOptionOne(() -> {
                    if (player.getInventory().getItem(slotId) != item) {
                        return;
                    }
                    player.lock(3);
                    player.setAnimation(READ_ANIM);
                    player.getInventory().deleteItem(slotId, item);
                    player.addAttribute(TARGET_TELEPORT_UNLOCKED, 1);
                    player.getVarManager().sendBit(TARGET_TELEPORT_VARBIT, 1);
                    player.getDialogueManager().start(new ItemChat(player, item, "You learn the Teleport to Bounty Target spell."));
                });
            }
        });
    }

    /** Whether the player has read the Target teleport scroll. */
    public static boolean knowsTargetTeleport(final Player player) {
        return player.getNumericAttribute(TARGET_TELEPORT_UNLOCKED).intValue() == 1;
    }

    @com.google.common.eventbus.Subscribe
    public static void onLogin(final com.zenyte.plugins.events.LoginEvent event) {
        final Player player = event.getPlayer();
        if (knowsTargetTeleport(player)) {
            player.getVarManager().sendBit(TARGET_TELEPORT_VARBIT, 1);
        }
    }

    @Override
    public int[] getItems() {
        final int[] items = new int[SEASONAL_SCROLLS.length + 1];
        System.arraycopy(SEASONAL_SCROLLS, 0, items, 0, SEASONAL_SCROLLS.length);
        items[SEASONAL_SCROLLS.length] = TARGET_TELEPORT_SCROLL;
        return items;
    }
}
