package com.zenyte.game.content.skills.construction;

import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType;
import com.zenyte.game.content.skills.magic.spells.teleports.structures.HouseStructure;
import com.zenyte.game.content.skills.magic.spells.teleports.structures.HouseTabletStructure;
import com.zenyte.game.content.skills.magic.spells.teleports.structures.TeleportStructure;
import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;

/**
 * House teleports other than the spell: the house tablet (consumed) and the Construction / max cape "Tele to POH" (free).
 * They go through their own structures, since {@link TeleportType#HOUSE_TELEPORT}'s structure is the spell's.
 */
public enum HouseTeleport implements Teleport {
    TABLET(new HouseTabletStructure(), new Item[] { new Item(ItemId.TELEPORT_TO_HOUSE) }),
    CAPE(new HouseStructure(), new Item[0]);

    private final TeleportStructure structure;
    private final Item[] runes;

    HouseTeleport(final TeleportStructure structure, final Item[] runes) {
        this.structure = structure;
        this.runes = runes;
    }

    /** @param mode {@link HouseStructure#INSIDE}, {@link HouseStructure#OUTSIDE} or null for the house setting. */
    public void teleport(final Player player, final String mode) {
        if (mode != null) {
            player.getTemporaryAttributes().put(HouseStructure.MODE_ATTR, mode);
        } else {
            player.getTemporaryAttributes().remove(HouseStructure.MODE_ATTR);
        }
        structure.teleport(player, this);
    }

    @Override
    public void teleport(final Player player) {
        structure.teleport(player, this);
    }

    @Override
    public void teleport(final Player player, final boolean alwaysWarnForDeepWilderness) {
        structure.teleport(player, this);
    }

    @Override
    public TeleportType getType() {
        return TeleportType.HOUSE_TELEPORT;
    }

    @Override
    public Location getDestination() {
        return null;
    }

    @Override
    public int getLevel() {
        return 0;
    }

    @Override
    public double getExperience() {
        return 0;
    }

    @Override
    public int getRandomizationDistance() {
        return 0;
    }

    @Override
    public Item[] getRunes() {
        return runes;
    }

    @Override
    public int getWildernessLevel() {
        return WILDERNESS_LEVEL;
    }

    @Override
    public boolean isCombatRestricted() {
        return UNRESTRICTED;
    }
}
