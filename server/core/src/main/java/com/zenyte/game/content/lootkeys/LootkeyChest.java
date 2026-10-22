package com.zenyte.game.content.lootkeys;

import com.gadscape.game.content.commands.DeveloperCommands;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.WorldObject;

/**
 * Loot chest at Ferox Enclave (loc 43486, multiloc on varbit 13651 -> 43484/43485, op "Loot").
 * Anyone holding loot keys can open them here, including keys taken from other players by someone who never bought
 * Skully's enchantment (OSRS).
 */
public class LootkeyChest implements ObjectAction {

    @Override
    public void handleObjectAction(Player player, WorldObject object, String name, int optionId, String option) {
        if (!DeveloperCommands.INSTANCE.getEnabledLootKeys()) {
            player.getDialogueManager().start(new Dialogue(player) {
                @Override
                public void buildDialogue() {
                    plain("Loot keys are currently disabled.");
                }
            });
            return;
        }
        if ("Loot".equals(option)) {
            LootChest.open(player);
        }
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 43484, 43485, 43486 };
    }
}
