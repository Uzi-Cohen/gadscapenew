package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Kris | 16/04/2019 16:59
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class PrayerTab extends Interface {
    /**
     * Build 241 prayerbook (541): component of each prayer slot -> the index PrayerManager#togglePrayer expects
     * (the 211 slot order, components 5..33 minus 5). In 241 the prayers are placed by
     * oc_param(enum_4956[prayer], param_1751): prayer1..prayer29 = components 9..37, and the ranged/magic
     * prayers (Hawk Eye, Eagle Eye, Mystic Will, Mystic Lore) were regrouped.
     */
    private static final int FIRST_PRAYER_COMPONENT = 9;
    private static final int[] SLOT_BY_COMPONENT_241 = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, // 9..27 thick skin .. sharp eye
            20, // 28 prayer20 Hawk Eye
            22, // 29 prayer21 Eagle Eye
            19, // 30 prayer22 Mystic Will
            21, // 31 prayer23 Mystic Lore
            23, // 32 prayer24 Mystic Might
            26, // 33 prayer25 Rigour
            24, // 34 prayer26 Chivalry
            25, // 35 prayer27 Piety
            27, // 36 prayer28 Augury
            28, // 37 prayer29 Preserve
    };

    @Override
    protected void attach() {

    }

    @Override
    protected DefaultClickHandler getDefaultHandler() {
        return (player, componentId, slotId, itemId, optionId) -> {
            final int index = componentId - FIRST_PRAYER_COMPONENT;
            if (index >= 0 && index < SLOT_BY_COMPONENT_241.length) {
                player.getPrayerManager().togglePrayer(SLOT_BY_COMPONENT_241[index], false);
            }
        };
    }

    @Override
    public void open(final Player player) {
        player.getInterfaceHandler().sendInterface(this);
    }

    @Override
    protected void build() {

    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.PRAYER_TAB_INTERFACE;
    }
}
