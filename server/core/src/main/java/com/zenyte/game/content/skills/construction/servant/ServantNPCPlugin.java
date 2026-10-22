package com.zenyte.game.content.skills.construction.servant;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.NpcId;
import com.zenyte.game.world.entity.npc.actions.NPCPlugin;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.dialogue.Dialogue;

/**
 * Talking to servants: the hired one in the house (its jobs), the ones at the Servants' Guild and the Chief servant (hiring
 * and firing).
 */
public final class ServantNPCPlugin extends NPCPlugin {

    @Override
    public void handle() {
        bind("Talk-to", (player, npc) -> {
            if (npc instanceof ServantNPC) {
                final Construction house = ((ServantNPC) npc).getHouse();
                if (house.getPlayer() != player) {
                    player.getDialogueManager().start(new Dialogue(player, npc) {
                        @Override
                        public void buildDialogue() {
                            npc("Welcome to my employer's house. Please make yourself at home.");
                        }
                    });
                    return;
                }
                player.getDialogueManager().start(new ServantManager.ServantDialogue(player, (ServantNPC) npc, house.getServantManager()));
                return;
            }
            ServantManager.sendGuildVarbit(player);
            if (npc.getId() == NpcId.CHIEF_SERVANT) {
                player.getDialogueManager().start(new ChiefServantDialogue(player, npc));
                return;
            }
            final ServantType type = ServantType.forNpc(player.getTransmogrifiedId(npc.getDefinitions(), npc.getId()));
            player.getDialogueManager().start(new HireDialogue(player, npc, type != null ? type : ServantType.forNpc(npc.getId())));
        });
    }

    @Override
    public int[] getNPCs() {
        return new int[] { NpcId.RICK, NpcId.MAID, NpcId.COOK, NpcId.BUTLER, NpcId.DEMON_BUTLER, NpcId.CHIEF_SERVANT, 222, 224, 226, 228, 230 };
    }

    private static final class HireDialogue extends Dialogue {
        private final ServantType type;

        HireDialogue(final Player player, final NPC npc, final ServantType type) {
            super(player, npc);
            this.type = type;
        }

        @Override
        public void buildDialogue() {
            if (type == null) {
                npc("Please speak to the Chief servant about hiring.");
                return;
            }
            final ServantManager manager = player.getConstruction().getServantManager();
            if (manager.getType() == type) {
                npc("I already work for you. You'll find me in your house.");
                return;
            }
            npc("I'm " + type.getName().toLowerCase() + ". I can carry " + type.getCapacity() + " items per trip" + (type.canUseSawmill() ? " and take logs to the sawmill" : "")
                    + ". My fee is " + type.getWage() + " coins for every " + ServantType.TRIPS_PER_WAGE + " trips.");
            options("Hire " + type.getName().toLowerCase() + " for " + type.getWage() + " coins?",
                    new DialogueOption("Yes, you're hired.", () -> {
                        final String reason = manager.canHire(type);
                        if (reason != null) {
                            setKey(10);
                            return;
                        }
                        manager.hire(type);
                    }),
                    new DialogueOption("No, thank you."));
            final String reason = manager.canHire(type);
            npc(10, reason == null ? "Very good." : reason);
        }
    }

    private static final class ChiefServantDialogue extends Dialogue {
        ChiefServantDialogue(final Player player, final NPC npc) {
            super(player, npc);
        }

        @Override
        public void buildDialogue() {
            final ServantManager manager = player.getConstruction().getServantManager();
            npc("Welcome to the Servants' Guild. All of our servants are well trained; talk to any of them to hire them.");
            final ServantType hired = manager.getType();
            final DialogueOption[] hireOptions = new DialogueOption[ServantType.VALUES.length];
            for (int i = 0; i < ServantType.VALUES.length; i++) {
                final ServantType type = ServantType.VALUES[i];
                hireOptions[i] = new DialogueOption(type.getName() + " (level " + type.getLevel() + ", " + type.getWage() + " coins)", () -> manager.hire(type));
            }
            if (hired != null) {
                options(TITLE, new DialogueOption("I'd like to fire " + hired.getName().toLowerCase() + ".", manager::fire), new DialogueOption("Nothing, thank you."));
            } else {
                options(TITLE, new DialogueOption("I'd like to hire a servant.", () -> setKey(5)), new DialogueOption("Nothing, thank you."));
                options(5, "Hire which servant?", hireOptions);
            }
        }
    }
}
