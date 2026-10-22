package com.zenyte.plugins.itemonitem;

import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.model.item.ItemOnItemAction;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Action;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.plugins.dialogue.PlainChat;
import com.zenyte.plugins.dialogue.SkillDialogue;

/**
 * Chisel on Ascension shards (The Fractured Archive): one shard makes 10 Ascension bolts, 86 Fletching, 140 XP, one
 * shard every 2 ticks (OSRS wiki). A regular chisel only. Tipping the bolts with diamond / onyx bolt tips is in
 * {@link com.zenyte.game.content.skills.fletching.FletchingDefinitions.AmmunitionFletchingData}.
 */
public final class AscensionBoltFletching implements ItemOnItemAction {
    private static final int LEVEL = 86;
    private static final double XP = 140;
    private static final int BOLTS_PER_SHARD = 10;
    /** Live animation not known; the amethyst chiselling animation is used. */
    private static final Animation ANIMATION = new Animation(6295);

    @Override
    public void handleItemOnItemAction(final Player player, final Item from, final Item to, final int fromSlot, final int toSlot) {
        if (player.getSkills().getLevel(SkillConstants.FLETCHING) < LEVEL) {
            player.getDialogueManager().start(new PlainChat(player, "You need a Fletching level of " + LEVEL + " to make ascension bolts."));
            return;
        }
        final int shards = player.getInventory().getAmountOf(ArchiveWeapons.ASCENSION_SHARDS);
        if (shards <= 1) {
            player.getActionManager().setAction(new Chisel(1));
            return;
        }
        player.getDialogueManager().start(new SkillDialogue(player, "How many shards will you chisel?", new Item(ArchiveWeapons.ASCENSION_BOLTS, BOLTS_PER_SHARD)) {
            @Override
            public void run(final int slotId, final int amount) {
                player.getActionManager().setAction(new Chisel(amount));
            }
        });
    }

    @Override
    public int[] getItems() {
        return new int[] {ItemId.CHISEL, ArchiveWeapons.ASCENSION_SHARDS};
    }

    @Override
    public ItemPair[] getMatchingPairs() {
        return new ItemPair[] {ItemPair.of(ItemId.CHISEL, ArchiveWeapons.ASCENSION_SHARDS)};
    }

    private static final class Chisel extends Action {
        private final int amount;
        private int done;

        private Chisel(final int amount) {
            this.amount = amount;
        }

        private boolean check() {
            if (!player.getInventory().containsItem(ItemId.CHISEL, 1)) {
                player.sendMessage("You need a chisel to do this.");
                return false;
            }
            if (!player.getInventory().containsItem(ArchiveWeapons.ASCENSION_SHARDS, 1)) {
                return false;
            }
            if (!player.getInventory().hasFreeSlots() && !player.getInventory().containsItem(ArchiveWeapons.ASCENSION_BOLTS, 1)
                    && player.getInventory().getAmountOf(ArchiveWeapons.ASCENSION_SHARDS) > 1) {
                player.sendMessage("You need some more free inventory space to do this.");
                return false;
            }
            return done < amount;
        }

        @Override
        public boolean start() {
            return check();
        }

        @Override
        public boolean process() {
            return check();
        }

        @Override
        public int processWithDelay() {
            player.setAnimation(ANIMATION);
            player.getInventory().deleteItem(ArchiveWeapons.ASCENSION_SHARDS, 1);
            player.getInventory().addOrDrop(new Item(ArchiveWeapons.ASCENSION_BOLTS, BOLTS_PER_SHARD));
            player.getSkills().addXp(SkillConstants.FLETCHING, XP);
            player.sendFilteredMessage("You chisel the shard into " + BOLTS_PER_SHARD + " ascension bolts.");
            done++;
            return 1;
        }
    }
}
