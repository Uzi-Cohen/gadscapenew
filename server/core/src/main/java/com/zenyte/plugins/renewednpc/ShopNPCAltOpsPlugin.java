package com.zenyte.plugins.renewednpc;

import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.NpcId;
import com.zenyte.game.world.entity.npc.actions.NPCPlugin;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

/**
 * Shop keepers whose shop is not behind a plain "Trade" op ({@link ShopNPCPlugin} only binds "Trade"): Gabooty
 * (Trade-Co-op / Trade-Drinks), Razmire Keelgan (Trade-General-Store / Trade-Builders-Store), Ezekial Lovecraft (Shop),
 * Fadli (Buy), and the two keepers that only have Talk-to, as in OSRS (Vanligga Gastfrihet, the Dead Man's Chest bartender).
 */
public final class ShopNPCAltOpsPlugin extends NPCPlugin {

    @Override
    public void handle() {
        bind("Trade-Co-op", (player, npc) -> player.openShop("Gabooty's Tai Bwo Wannai Cooperative"));
        bind("Trade-Drinks", (player, npc) -> player.openShop("Gabooty's Tai Bwo Wannai Drinky Store"));
        bind("Trade-General-Store", (player, npc) -> player.openShop("Razmire General Store"));
        bind("Trade-Builders-Store", (player, npc) -> player.openShop("Razmire Builders Merchants"));
        bind("Shop", (player, npc) -> player.openShop("Lovecraft's Tackle"));
        bind("Buy", (player, npc) -> player.openShop("Shop of Distaste"));
    }

    @Override
    public int[] getNPCs() {
        return new int[] {NpcId.GABOOTY, NpcId.GABOOTY_6425, NpcId.RAZMIRE_KEELGAN, NpcId.EZEKIAL_LOVECRAFT, NpcId.FADLI};
    }

    /** Keepers with only Talk-to: the shop is offered in their dialogue. */
    public static final class TalkToShopKeepers extends NPCPlugin {
        @Override
        public void handle() {
            bind("Talk-to", (player, npc) -> offerShop(player, npc,
                    npc.getId() == NpcId.VANLIGGA_GASTFRIHET ? "Contraband yak produce" : "Dead Man's Chest"));
        }

        private static void offerShop(final Player player, final NPC npc, final String shop) {
            player.getDialogueManager().start(new Dialogue(player, npc) {
                @Override
                public void buildDialogue() {
                    npc("Hello there! Can I interest you in anything?");
                    options(TITLE, new DialogueOption("Let's see what you have.", () -> player.openShop(shop)),
                            new DialogueOption("No thanks."));
                }
            });
        }

        @Override
        public int[] getNPCs() {
            return new int[] {NpcId.VANLIGGA_GASTFRIHET, NpcId.BARTENDER_1314};
        }
    }
}
