package com.zenyte.plugins.object;

import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.WorldObject;

/**
 * "%SERVER_NAME% Portal" (loc 35000, cache/assets/types/home.toml). The legacy menu 1700 it opened has no client scripts,
 * so both ops now use the GadScape teleport menu (1601). Note: 35000 is a vanilla scenery id placed 166 times in
 * Prifddinas; the cache should drop that override (see content-work/teleports-shops/CACHE_NEEDS.md), after which this
 * plugin simply never fires. The working home portal is the Teleportation nexus (HomeNexusObject).
 *
 * @author Tommeh | 5-3-2019 | 19:02
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public class ZenytePortal implements ObjectAction {
    @Override
    public void handleObjectAction(Player player, WorldObject object, String name, int optionId, String option) {
        if (option.equals("Teleport")) {
            player.getTeleportsManager().attemptOpen();
        } else if (option.equals("Teleport-previous")) {
            if (player.getTeleportsManager().getPreviousDestination() == null) {
                player.sendMessage("You haven't teleported anywhere yet.");
                return;
            }
            player.getTeleportsManager().attemptTeleport(player.getTeleportsManager().getPreviousDestination());
        }
    }

    @Override
    public Object[] getObjects() {
        return new Object[] {35000};
    }
}
