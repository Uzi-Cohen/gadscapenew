package com.zenyte.game.content.skills.construction.objects.gamesroom;

import com.zenyte.game.world.entity.npc.actions.NPCPlugin;

/**
 * "Banish" on the games room's Jacky Jester and elemental balance.
 */
public final class GamesRoomNPCPlugin extends NPCPlugin {

    @Override
    public void handle() {
        bind("Banish", (player, npc) -> {
            player.sendMessage("You banish the " + npc.getDefinitions().getName().toLowerCase() + ".");
            npc.finish();
        });
    }

    @Override
    public int[] getNPCs() {
        return new int[] { GamesRoom.JESTER_NPC, GamesRoom.ELEMENTAL_BALANCE_NPC };
    }
}
