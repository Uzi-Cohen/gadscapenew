package com.zenyte.game.content.skills.construction.servant;

import com.zenyte.game.content.skills.construction.Construction;
import com.zenyte.game.util.Direction;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.npc.NPC;

/**
 * The hired servant inside the owner's house instance. It is a plain NPC of the house instance, so wiping the instance removes
 * it as well.
 */
public final class ServantNPC extends NPC {

    private final Construction house;

    public ServantNPC(final ServantType type, final Location tile, final Construction house) {
        super(type.getNpcId(), tile, Direction.SOUTH, 2);
        this.house = house;
    }

    public Construction getHouse() {
        return house;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }
}
