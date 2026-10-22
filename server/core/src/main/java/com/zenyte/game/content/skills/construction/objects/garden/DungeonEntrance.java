package com.zenyte.game.content.skills.construction.objects.garden;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.content.skills.construction.dialogue.DungeonEnterD;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.ObjectId;
import com.zenyte.game.world.object.WorldObject;
import com.zenyte.plugins.dialogue.RoomRemovingD;

/**
 * @author Kris | 22. nov 2017 : 6:29.20
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>}
 */
public final class DungeonEntrance implements ObjectInteraction {

    @Override
    public Object[] getObjects() {
        return new Object[] { ObjectId.DUNGEON_ENTRANCE };
    }

    @Override
    public void handleObjectAction(Player player, Construction construction, RoomReference reference, WorldObject object, int optionId, String option) {
        if (optionId == 1) {
            for (RoomReference room : construction.getReferences()) {
                if (reference.getX() == room.getX() && reference.getY() == room.getY() && room.getPlane() == 0) {
                    player.setLocation(new Location(player.getX(), player.getY(), 0));
                    return;
                }
            }
            player.getDialogueManager().start(new DungeonEnterD(player, reference, object));
        } else if (optionId == 4) {
            // "Remove-room": the owner, in building mode (the controller checks this too).
            if (!construction.isOwner(player) || !construction.isBuildingMode()) {
                player.sendMessage(construction.isOwner(player) ? "You can only do that in building mode." : "You can only do that in your own house.");
                return;
            }
            final int roomX = player.getLocation().getChunkX() - construction.getChunkX() + construction.getYardOffset();
            final int roomY = player.getLocation().getChunkY() - construction.getChunkY() + construction.getYardOffset();
            final RoomReference ref = construction.getReference(roomX, roomY, 0);
            if (ref != null)
                player.getDialogueManager().start(new RoomRemovingD(player, construction, ref));
            else
                player.sendMessage("You haven't built anything under here!");
        }
    }
}
