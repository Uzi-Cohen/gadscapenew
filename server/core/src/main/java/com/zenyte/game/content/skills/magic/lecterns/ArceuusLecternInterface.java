package com.zenyte.game.content.skills.magic.lecterns;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.Interface;
import com.zenyte.game.world.entity.player.Player;
import mgi.types.config.enums.EnumDefinitions;
import mgi.types.config.enums.IntEnum;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;

/**
 * The teletab lectern of build 241 (interface 403 teletabs_craft_if), for both lecterns.
 * <p>
 * 211 had two screens: the Arceuus lectern (403, 11 tablets) and the regular (home) lectern (79, which 241 reuses for
 * another interface). 241 draws one screen for every lectern: script 3591 labels tab_1..tab_21 (403:19..39) from the
 * enum in varp 4074 (teletab_enum, key = tab - 1, value = the tablet obj), shows tab {@code n} selected when varbit
 * 10600 (tablet_to_make) is {@code n}, and the confirm button (403:14) makes the selected tablet. The tablet is found
 * by its obj, so the server follows whatever the enum lists; a tablet GadScape does not make (241's Kourend, Civitas and
 * boat tablets) is refused.
 */
public class ArceuusLecternInterface extends Interface {
    private static final int QUANTITY_VARP = 2224;
    private static final int SELECTED_TABLET_TYPE = 10600;
    private static final int TABLET_SPELLBOOK_VARBIT = 10599;
    /** 241 teletab_enum: the enum the lectern screen lists. */
    private static final int TELETAB_ENUM_VARP = 4074;
    /** 241 enum of the Arceuus lectern's tablets (Arceuus Library .. Ape Atoll, the 211 tab order). */
    static final int ARCEUUS_TABLETS_ENUM = 2628;
    /** 241 enum of the gilded lectern: every standard-spellbook tablet (GadScape's home lectern makes all of them). */
    static final int REGULAR_TABLETS_ENUM = 5271;
    private static final int FIRST_TAB = 19;
    private static final int TABS = 21;

    @Override
    protected void attach() {
        // Build 241 teletabs_craft_if (403): 211 ids 4-8 / 13 / 14-24 are 5-9 / 14 / 19-29 (tab_1..tab_11), 241 has
        // 21 tabs.
        put(5, "Make one");
        put(6, "Make five");
        put(7, "Make ten");
        put(8, "Make x");
        put(9, "Make all");
        for (int tab = 1; tab <= TABS; tab++) {
            put(FIRST_TAB + tab - 1, "Tab " + tab);
        }
        put(14, "Create");
    }

    @Override
    public void open(final Player player) {
        open(player, ARCEUUS_TABLETS_ENUM, 3);
    }

    /** Opens the lectern screen listing the tablets of {@code tabletsEnum}. */
    static void open(final Player player, final int tabletsEnum, final int spellbook) {
        player.getVarManager().sendBit(TABLET_SPELLBOOK_VARBIT, spellbook);
        player.getVarManager().sendVar(TELETAB_ENUM_VARP, tabletsEnum);
        player.getVarManager().sendBit(SELECTED_TABLET_TYPE, 0);
        if (player.getVarManager().getValue(QUANTITY_VARP) < 1) {
            player.getVarManager().sendVar(QUANTITY_VARP, 1);
        }
        player.getInterfaceHandler().sendInterface(GameInterface.ARCEUUS_LECTERN);
    }

    @Override
    protected void build() {
        bind("Make one", player -> player.getVarManager().sendVar(QUANTITY_VARP, 1));
        bind("Make five", player -> player.getVarManager().sendVar(QUANTITY_VARP, 5));
        bind("Make ten", player -> player.getVarManager().sendVar(QUANTITY_VARP, 10));
        bind("Make x", player -> player.sendInputInt("How many would you like to make?", value -> player.getVarManager().sendVar(QUANTITY_VARP, Math.max(0, Math.min(28, value)))));
        bind("Make all", player -> player.getVarManager().sendVar(QUANTITY_VARP, 28));
        for (int tab = 1; tab <= TABS; tab++) {
            final int selected = tab;
            bind("Tab " + tab, (player, slotId, itemId, option) -> select(player, selected));
        }
        bind("Create", (player) -> {
            final int tab = player.getVarManager().getBitValue(SELECTED_TABLET_TYPE);
            final LecternTablet tablet = tabletOf(player, tab);
            if (tablet == null) {
                if (tab > 0) {
                    player.sendMessage("You can't make that tablet here.");
                }
                return;
            }
            player.getInterfaceHandler().closeInterface(getInterface());
            player.getActionManager().setAction(new TabletCreation(tablet, player.getVarManager().getValue(QUANTITY_VARP)));
        });
    }

    private static void select(@NotNull final Player player, final int tab) {
        if (tabletObj(player, tab) == -1) {
            return;
        }
        player.getVarManager().sendBit(SELECTED_TABLET_TYPE, tab);
    }

    /** The tablet obj the open lectern lists on {@code tab} (1-based), or -1. */
    private static int tabletObj(@NotNull final Player player, final int tab) {
        if (tab < 1) {
            return -1;
        }
        final IntEnum tablets = EnumDefinitions.getIntEnumOrEmpty(player.getVarManager().getValue(TELETAB_ENUM_VARP));
        final OptionalInt obj = tablets.getValue(tab - 1);
        return obj.isPresent() ? obj.getAsInt() : -1;
    }

    private static LecternTablet tabletOf(@NotNull final Player player, final int tab) {
        final int obj = tabletObj(player, tab);
        if (obj == -1) {
            return null;
        }
        for (final ArceuusTablet tablet : ArceuusTablet.values()) {
            if (tablet.getTab().getId() == obj) {
                return tablet;
            }
        }
        for (final RegularTablet tablet : RegularTablet.values()) {
            if (tablet.getTab().getId() == obj) {
                return tablet;
            }
        }
        return null;
    }

    @Override
    public GameInterface getInterface() {
        return GameInterface.ARCEUUS_LECTERN;
    }
}
