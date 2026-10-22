package com.zenyte.game.world.entity.player.teleportsystem;

import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.plugins.dialogue.ItemChat;

import java.util.Objects;

/**
 * Old teleport unlock scrolls (12100-12105). They unlocked destinations of the legacy teleport menu (interface 1700),
 * which never worked (its client scripts are missing in every cache). Every destination of the GadScape teleport menu
 * (interface 1601) is available to everyone, so reading one only explains that; the scroll is kept (it can be dropped).
 *
 * @author Kris | 29/03/2019 18:32
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class TeleportScrollPlugin extends ItemPlugin {

    @Override
    public void handle() {
        bind("Read", (player, item, slotId) -> {
            final TeleportScroll scroll = Objects.requireNonNull(TeleportScroll.map.get(item.getId()));
            final String name = scroll.getName().replaceAll(" teleport scroll", "");
            player.getDialogueManager().start(new ItemChat(player, item, "The words on the parchment have faded. You don't need it: "
                    + name + " is already in the teleport menu (magic tab teleports or the Teleportation nexus)."));
        });
    }

    @Override
    public int[] getItems() {
        return TeleportScroll.map.keySet().toIntArray();
    }
}
