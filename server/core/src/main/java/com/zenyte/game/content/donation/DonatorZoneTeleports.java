package com.zenyte.game.content.donation;

import com.google.common.eventbus.Subscribe;
import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.GameCommands;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.privilege.MemberRank;
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege;
import com.zenyte.plugins.events.ServerLaunchEvent;

public enum DonatorZoneTeleports {

    // the islands keep their commands; each belongs to the rank in the same place of the old ladder
    UDI(MemberRank.DIVINE, create(new Location(3393, 7758, 0))),
    LDI(MemberRank.ROYAL, create(new Location(3390, 7575, 0))),
    RDI(MemberRank.LEGENDARY, create(new Location(3404, 8003, 0))),
    DI(MemberRank.DONATOR, create(new Location(3359, 8170, 2))),
    DIE(MemberRank.SUPER, create(new Location(3359, 8299, 2)));

    private final Location location;
    private final MemberRank rank;
    private final Teleport teleport;
    DonatorZoneTeleports(MemberRank rank, Teleport teleport) {
        this.location = teleport.getDestination();
        this.rank = rank;
        this.teleport = teleport;
        new GameCommands.Command(PlayerPrivilege.PLAYER, name().toLowerCase(), rank + " Zone", (p, args) -> {
            if (p.isLocked()) {
                return;
            }
            if(p.getMemberRank().equalToOrGreaterThan(rank) || p.isStaff())
            teleport.teleport(p);
            else {
                p.sendMessage("You must be a " + rank + " or higher to use this teleport.");
            }
        });
    }

    public Location getLocation() {
        return location;
    }

    public void teleport(final Player player) {
        teleport.teleport(player);
    }

    /** The best donator zone [rank] may use (::dzone), or null for non-donators. */
    public static DonatorZoneTeleports forRank(final MemberRank rank) {
        DonatorZoneTeleports best = null;
        for (final DonatorZoneTeleports zone : values()) {
            if (rank.equalToOrGreaterThan(zone.rank) && (best == null || zone.rank.getId() > best.rank.getId())) {
                best = zone;
            }
        }
        return rank.isDonator() ? best : null;
    }

    @Subscribe
    public static void on(ServerLaunchEvent event) {
    }

    private static Teleport create(Location location) {
        return new Teleport() {
            @Override
            public TeleportType getType() {
                return TeleportType.GADSCAPE_PORTAL_TELEPORT;
            }

            @Override
            public Location getDestination() {
                return location;
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
                return 3;
            }

            @Override
            public Item[] getRunes() {
                return null;
            }

            @Override
            public int getWildernessLevel() {
                return 20;
            }

            @Override
            public boolean isCombatRestricted() {
                return false;
            }
        };
    }
}
