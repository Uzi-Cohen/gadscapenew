package com.zenyte.game.content.donation;

import com.zenyte.game.content.skills.magic.spells.teleports.Teleport;
import com.zenyte.game.content.skills.magic.spells.teleports.TeleportType;
import com.zenyte.game.item.Item;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.privilege.MemberRank;

public enum HomeTeleport {
    HOME(new Location(3211, 3423), "Regular Home", MemberRank.SUPER),
    MAGE_BANK(new Location(2539, 4716), "Mage's Bank", MemberRank.SUPER),
    FEROX_ENCLAVE(new Location(3150, 3636), "Ferox Enclave", MemberRank.SUPER),
    DI(DonatorZoneTeleports.DI.getLocation(), "Gad Donator Island", MemberRank.SUPER),
    DIE(DonatorZoneTeleports.DIE.getLocation(), "Super Gad Donator Island", MemberRank.SUPER),
    RDI(DonatorZoneTeleports.RDI.getLocation(), "Legendary Gad Donator Island", MemberRank.LEGENDARY),
    LDI(DonatorZoneTeleports.LDI.getLocation(), "Royal Gad Donator Island", MemberRank.ROYAL),
    UDI(DonatorZoneTeleports.UDI.getLocation(), "Divine Gad Donator Island", MemberRank.DIVINE),
    ;

    HomeTeleport(Location location, String name, MemberRank required) {
        this.location = location;
        this.name = name;
        this.required = required;
    }

    public Location getLocation() {
        return location;
    }

    public String getName() {
        return name;
    }

    public MemberRank getRequired() {
        return required;
    }
    public static HomeTeleport get(Player player) {
        return VALUES[((Number)player.getAttributeOrDefault("HOME_TELEPORT", 0)).intValue()];
    }

    public Teleport getTeleport() {
        return new Teleport() {
            @Override
            public TeleportType getType() {
                return TeleportType.HOME_TELEPORT;
            }

            @Override
            public Location getDestination() {
                return getLocation();
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
                return new Item[0];
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

    public static HomeTeleport[] VALUES = values();

    private Location location;
    private String name;
    private MemberRank required;


}
