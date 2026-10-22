package com.zenyte.plugins.object;

import com.gadscape.game.rev241.Gad211;
import com.google.common.eventbus.Subscribe;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.plugins.events.ServerLaunchEvent;

/**
 * Teleportation nexus: "Teleportation-menu" opens the GadScape teleport menu (interface 1601) on Training, and
 * "Previous-teleport" repeats the last menu teleport.
 * <p>
 * The cache places one at the old Edgeville home (3077,3496). Home teleport now lands in Varrock square
 * ({@link com.zenyte.game.content.donation.HomeTeleport#HOME} 3211,3423), so a second nexus is spawned there at launch.
 */
public class HomeNexusObject implements ObjectAction {

    /**
     * GadScape loc "Teleportation nexus" (TypeParser: clone of POH nexus 41415). In the 2746 base cache 50082 is a vanilla
     * "Wilderness Sign" placed in the Wilderness, so the cache converter relocates the nexus; the idmap must rewrite this.
     */
    public static final int TELEPORTATION_NEXUS = Gad211.loc(50082);

    /** South-east of the Varrock fountain, beside the home-teleport arrival tile; 2x2 floor free on the 211, 241 and 2746 maps. */
    private static final Location VARROCK_HOME_NEXUS = new Location(3218, 3422, 0);

    @Override
    public void handleObjectAction(Player player, WorldObject object, String name, int optionId, String option) {
        if (option.equalsIgnoreCase("Previous-teleport")) {
            if (player.getTeleportsManager().getPreviousDestination() == null) {
                player.sendMessage("You don't have a previous teleport.");
                return;
            }
            player.getTeleportsManager().attemptTeleport(player.getTeleportsManager().getPreviousDestination());
            return;
        }
        player.getTeleportsManager().attemptOpen("training teleports");
    }

    @Override
    public Object[] getObjects() {
        return new Object[] {TELEPORTATION_NEXUS};
    }

    @Subscribe
    public static void onServerLaunch(final ServerLaunchEvent event) {
        World.loadRegion(VARROCK_HOME_NEXUS.getRegionId());
        if (World.getObjectWithId(VARROCK_HOME_NEXUS, TELEPORTATION_NEXUS) == null) {
            World.spawnObject(new WorldObject(TELEPORTATION_NEXUS, 10, 0, VARROCK_HOME_NEXUS));
        }
    }
}
