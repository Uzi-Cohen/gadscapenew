package com.zenyte.game.model.ui.testinterfaces;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.skills.prayer.Prayer;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;

/**
 * @author Kris | 16/04/2019 16:59
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public class PrayerTab extends Interface {
    /**
     * Build 241 prayer book (interface 541 of the 2746 cache): the prayer buttons are components 9..37, placed by the
     * prayer objects' param 1751. Index = component - 9, value = the prayer's slot (param 630 = varp 83 bit = enum 4956
     * index = {@link Prayer} ordinal of the standard prayer). The ranged and magic prayers are grouped by style, so the
     * order is not the slot order. An alternate prayer (Deadeye, Mystic Vigour, Zeal) uses its base prayer's button.
     */
    public static final int FIRST_PRAYER_COMPONENT = 9;
    private static final int[] SLOT_BY_COMPONENT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, // 9..26 Thick Skin .. Smite
            18, // 27 Sharp Eye
            20, // 28 Hawk Eye
            22, // 29 Eagle Eye / Deadeye
            19, // 30 Mystic Will
            21, // 31 Mystic Lore
            23, // 32 Mystic Might / Mystic Vigour
            24, // 33 Rigour
            25, // 34 Chivalry
            26, // 35 Piety / Zeal
            27, // 36 Augury
            28, // 37 Preserve
    };

    /**
     * The "Prayer Filters" panel (541:42): rows 0-4 are created by script 3156 in this order and transmit op 1
     * ("Change"); each toggles one varbit of varp 2972 that the 2746 prayer scripts read.
     */
    public static final int FILTERS_COMPONENT = 42;
    private static final com.zenyte.game.world.entity.player.Setting[] FILTERS = {
            com.zenyte.game.world.entity.player.Setting.PRAYER_FILTER_BLOCK_LOW_TIER,      // "Show lower tiers of tiered prayers"
            com.zenyte.game.world.entity.player.Setting.PRAYER_FILTER_ALLOW_COMBINED_TIER, // "Show tiered prayers even if multi-skill prayers are available"
            com.zenyte.game.world.entity.player.Setting.PRAYER_FILTER_BLOCK_HEALING,       // "Show Rapid Healing prayers"
            com.zenyte.game.world.entity.player.Setting.PRAYER_FILTER_BLOCK_LACK_LEVEL,    // "Show prayers you lack the Prayer level to activate"
            com.zenyte.game.world.entity.player.Setting.PRAYER_FILTER_BLOCK_LOCKED,        // "Show prayers you lack the requirements to activate"
    };

    /** The 541 button of a standard-book prayer (or of an alternate: its base prayer's button), or -1. */
    public static int componentOf(final Prayer prayer) {
        final int slot = prayer.getSlot();
        for (int i = 0; i < SLOT_BY_COMPONENT.length; i++) {
            if (SLOT_BY_COMPONENT[i] == slot) {
                return FIRST_PRAYER_COMPONENT + i;
            }
        }
        return -1;
    }

    /** The standard-book prayer of a 541 button, or null. */
    public static Prayer prayerOf(final int componentId) {
        final int index = componentId - FIRST_PRAYER_COMPONENT;
        if (index < 0 || index >= SLOT_BY_COMPONENT.length) {
            return null;
        }
        return Prayer.get(SLOT_BY_COMPONENT[index]);
    }

    @Override
    protected void attach() {

    }

    @Override
    protected DefaultClickHandler getDefaultHandler() {
        return (player, componentId, slotId, itemId, optionId) -> {
            if (componentId == FILTERS_COMPONENT) {
                if (slotId >= 0 && slotId < FILTERS.length) {
                    player.getSettings().toggleSetting(FILTERS[slotId]);
                }
                return;
            }
            if (com.zenyte.game.content.skills.prayer.RuinousPowers.handleButton(player, componentId)) {
                return;
            }
            final Prayer prayer = prayerOf(componentId);
            if (prayer != null) {
                player.getPrayerManager().togglePrayer(prayer);
            }
        };
    }

    @Override
    public void open(final Player player) {
        player.getInterfaceHandler().sendInterface(this);
        // the filter rows are dynamic children: their "Change" op is only transmitted with these events
        player.getPacketDispatcher().sendComponentSettings(getInterface(), FILTERS_COMPONENT, 0, FILTERS.length - 1,
                com.zenyte.game.util.AccessMask.CLICK_OP1);
    }

    @Override
    protected void build() {

    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.PRAYER_TAB_INTERFACE;
    }
}
