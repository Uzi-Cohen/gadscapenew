package com.zenyte.plugins.object;

import com.gadscape.game.rev241.Gad211;
import com.zenyte.game.GameInterface;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.object.ObjectAction;
import com.zenyte.game.world.object.WorldObject;


public final class ClanRecruitmentBoard implements ObjectAction {

    @Override
    public void handleObjectAction(final Player player, final WorldObject object, final String name, final int optionId, final String option) {
        GameInterface.HISCORES.open(player);
    }

    @Override
    public Object[] getObjects() {
        // GadScape's "Hiscores" board (moved by the 241 cache, Gad211) and 2746's clan board at 41728 (one id on 211)
        return new Object[] {41728, Gad211.loc(41728)};
    }
}
