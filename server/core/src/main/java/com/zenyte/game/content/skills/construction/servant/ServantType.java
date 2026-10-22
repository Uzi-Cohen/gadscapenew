package com.zenyte.game.content.skills.construction.servant;

import com.zenyte.game.world.entity.npc.NpcId;

/**
 * The servants of the Servants' Guild, East Ardougne (OSRS wiki "Servant").
 * <p>
 * The wage is paid every 8 trips and equals the hiring fee (Rick 63 x 8 = 500, Maid 125 x 8 = 1,000, ...).
 * Trip times: 60 / 30 / 17 / 12 / 7 seconds.
 */
public enum ServantType {
    RICK("Rick", NpcId.RICK, 1, 20, 500, 6, 100, false),
    MAID("Maid", NpcId.MAID, 3, 25, 1_000, 10, 50, false),
    COOK("Cook", NpcId.COOK, 5, 30, 3_000, 16, 28, true),
    BUTLER("Butler", NpcId.BUTLER, 6, 40, 5_000, 20, 20, true),
    DEMON_BUTLER("Demon butler", NpcId.DEMON_BUTLER, 8, 50, 10_000, 26, 12, true);

    public static final ServantType[] VALUES = values();

    /** Varbit that hides the hired servant among the ones wandering the guild (multi-NPCs 222/224/226/228/230). */
    public static final int GUILD_VARBIT = 2190;

    /** Number of trips paid for by one wage. */
    public static final int TRIPS_PER_WAGE = 8;

    private final String name;
    private final int npcId;
    private final int guildVarbitValue;
    private final int level;
    private final int wage;
    private final int capacity;
    private final int tripTicks;
    private final boolean sawmill;

    ServantType(final String name, final int npcId, final int guildVarbitValue, final int level, final int wage, final int capacity, final int tripTicks, final boolean sawmill) {
        this.name = name;
        this.npcId = npcId;
        this.guildVarbitValue = guildVarbitValue;
        this.level = level;
        this.wage = wage;
        this.capacity = capacity;
        this.tripTicks = tripTicks;
        this.sawmill = sawmill;
    }

    public static ServantType forNpc(final int npcId) {
        for (final ServantType type : VALUES) {
            if (type.npcId == npcId) {
                return type;
            }
        }
        return null;
    }

    public String getName() {
        return name;
    }

    public int getNpcId() {
        return npcId;
    }

    public int getGuildVarbitValue() {
        return guildVarbitValue;
    }

    public int getLevel() {
        return level;
    }

    /** Hiring fee, also the wage for every {@link #TRIPS_PER_WAGE} trips. */
    public int getWage() {
        return wage;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getTripTicks() {
        return tripTicks;
    }

    public boolean canUseSawmill() {
        return sawmill;
    }
}
