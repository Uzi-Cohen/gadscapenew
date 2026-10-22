package com.zenyte.plugins.renewednpc;

import com.zenyte.game.world.entity.npc.NpcId;
import com.zenyte.game.world.entity.npc.actions.NPCPlugin;

/**
 * Teleport wizard (GadScape Teleporter, NPC 16000). The legacy three-column menu (interface 1700) it used to open has no client scripts in any cache, so it
 * opens the GadScape teleport menu (interface 1601) instead, and "Teleport-previous" repeats the last 1601 teleport.
 *
 * @author Kris | 25/11/2018 16:11
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class ZenyteTeleporter extends NPCPlugin {
    @Override
    public void handle() {
        bind("Teleport", (player, npc) -> player.getTeleportsManager().attemptOpen());
        bind("Teleport-previous", (player, npc) -> {
            if (player.getTeleportsManager().getPreviousDestination() == null) {
                player.sendMessage("The wizard hasn't teleported you anywhere yet.");
                return;
            }
            player.getTeleportsManager().attemptTeleport(player.getTeleportsManager().getPreviousDestination());
        });
    }

    @Override
    public int[] getNPCs() {
        // 16000 is the GadScape Teleporter (cache/assets/types/home.toml: op1 Teleport, op4 Teleport-previous); 10000 is
        // the old wizard id, undefined in every current cache, kept for old spawn files.
        return new int[] {10000, NpcId.GADSCAPE_TELEPORTER};
    }
}
