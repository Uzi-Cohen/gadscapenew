package com.zenyte.game.world.entity.player.privilege;

import com.zenyte.utils.Ordinal;

/**
 * @author Tommeh | 5-4-2019 | 16:15
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
@Ordinal
public enum MemberRank implements IPrivilege {

    NONE("None", 0, 0, Crown.NONE, "000000", -1, -1),
    PREMIUM("Donator", 1, 0.01D, Crown.PREMIUM, "007e18", 200, 12),  //2 min
    EXPANSION("Super Donator", 2, 0.02D, Crown.EXPANSION, "F8E239", 100, 10),  //1 min
    EXTREME("Elite Donator", 3, 0.04D, Crown.EXTREME, "FFFFFF", 75, 8),  // 45 seconds
    RESPECTED("Extreme Donator", 4, 0.06D, Crown.RESPECTED, "d80717", 50, 6),  // 30 seconds
    LEGENDARY("Respected Donator", 5, 0.08D, Crown.LEGENDARY, "2188FF", 25, 4),  //15 seconds
    MYTHICAL("Mythical Donator", 6, 0.1D, Crown.MYTHICAL, "999999", 8, 2),  //8 seconds
    UBER("Platinum Donator", 7, 0.12D, Crown.UBER, "222222", 0, 0),
    /**
     * Amascut was a special rank added by Will that is only obtainable
     * for 2 members `speckle` and `gim`.
     */
    AMASCUT("Amascut", 7, 0.12D, Crown.AMASCUT, "df782f", 0, 0),
    ;

    public static final MemberRank[] values = values();
    private final String displayName;
    private final int id;
    private final Crown crown;
    private final double dropRate;
    private final String yellColor;
    private final int yellDelay;
    private final int togglesChance;

    MemberRank(String displayName, int id, double dropRate, Crown crown, String yellColor, int yellDelay, final int togglesChance) {
        this.displayName = displayName;
        this.id = id;
        this.dropRate = dropRate;
        this.crown = crown;
        this.yellColor = yellColor;
        this.yellDelay = yellDelay;
        this.togglesChance = togglesChance;
    }

    public static MemberRank fromId(int memberRank) {
        return MemberRank.values[memberRank];
    }

    public boolean equalToOrGreaterThan(final MemberRank member) {
        return getId() >= member.getId();
    }

    /**
     * The donator tier name players see; the constant names are kept because saves and
     * perk checks refer to them.
     */
    @Override
    public String toString() {
        return displayName;
    }

    public int getId() {
        return id;
    }

    public String getYellColor() {
        return yellColor;
    }

    public int getYellDelay() {
        return yellDelay;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public Crown crown() {
        return crown;
    }

    public double getDR() {
        return dropRate;
    }

    public int getTogglesChance() {
        return togglesChance;
    }

}
