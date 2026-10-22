package com.zenyte.game.content.skills.construction.servant;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.content.skills.construction.ObjectInteraction;
import com.zenyte.game.content.skills.construction.RoomReference;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.masks.Animation;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.WorldObject;

/**
 * Dining room bell-pulls: ring for the servant.
 */
public final class BellPull implements ObjectInteraction {

    /* Player and bell-pull animations (crude for the rope bell-pull, posh for the others). */
    private static final Animation PULL_CRUDE = new Animation(3667);
    private static final Animation PULL_POSH = new Animation(3668);
    private static final Animation LOC_CRUDE = new Animation(3575);
    private static final Animation LOC_POSH = new Animation(3574);

    @Override
    public void handleObjectAction(final Player player, final Construction construction, final RoomReference reference, final WorldObject object, final int optionId, final String option) {
        if (!option.equalsIgnoreCase("Ring")) {
            return;
        }
        final boolean crude = object.getId() == 13307;
        player.setAnimation(crude ? PULL_CRUDE : PULL_POSH);
        World.sendObjectAnimation(object, crude ? LOC_CRUDE : LOC_POSH);
        if (!construction.isOwner(player)) {
            player.sendMessage("Only the owner of the house can call the servant.");
            return;
        }
        construction.getServantManager().call(player);
    }

    @Override
    public Object[] getObjects() {
        return new Object[] { 13307, 13308, 13309 };
    }
}
