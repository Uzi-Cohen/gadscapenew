package com.zenyte.game.world.entity.player.privilege;

import com.google.gson.annotations.SerializedName;
import com.zenyte.utils.Ordinal;

/**
 * GadScape's donator ranks: Roat Pkz's six ranks at Roat's prices (the website sells the same table), each name with
 * "Gad" in it. A rank comes from a DONATOR_RANK claim or from the lifetime total donated reaching its price
 * ({@link #forTotalCents}), and never goes down. The numbers are Roat's donator benefits table; see
 * com.gadscape.game.content.donator.DonatorRanks for where each one is used.
 * <p>
 * Saves keep the rank by its serialized name. The ranks before these (Donator $10, Super $100, Elite $200,
 * Extreme $500, Respected $1000, Mythical, Platinum $5000, saved as PREMIUM..UBER) load as the new rank that the
 * same money buys now (the alternate names below), so nobody goes down.
 * <p>
 * Crowns: the gem icons in mod_icons, Crown keeps their old names (see Crown).
 *
 * @author Tommeh | 5-4-2019 | 16:15
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
@Ordinal
public enum MemberRank implements IPrivilege {

    // name, id, price (cents), drop rate %, crown, yell colour, yell delay (ticks), toggles chance (1 in n), donator points
    // with the rank, CoX purple chance +%, presets, ::spec/::hp cooldown (s), ::unskull cooldown (s), Slayer points +%,
    // cancel task cost, block task cost, Bounty Hunter skips per 30 min, Bounty Hunter new target after (s)
    NONE("None",                              0,      0,  0, Crown.NONE,      "000000", -1, -1,    0,  0,  4,  -1,  -1,  0, 30, 100,  3, 30),
    @SerializedName(value = "GAD_DONATOR", alternate = {"PREMIUM"})
    DONATOR("Gad Donator",                    1,    995, 15, Crown.PREMIUM,   "007e18", 200, 12,  30, 10,  8,  90, 180,  5, 27,  90,  7, 30),
    @SerializedName(value = "SUPER_GAD_DONATOR")
    SUPER("Super Gad Donator",                2,   2995, 25, Crown.EXPANSION, "f8e239", 100, 10, 100, 20, 15,  60, 120, 10, 24,  80, 10, 25),
    @SerializedName(value = "EXTREME_GAD_DONATOR", alternate = {"EXPANSION"})
    EXTREME("Extreme Gad Donator",            3,   8995, 30, Crown.EXTREME,   "ffffff",  75,  8, 400, 30, 20,  45,  90, 15, 21,  70, 20, 15),
    @SerializedName(value = "LEGENDARY_GAD_DONATOR", alternate = {"EXTREME"})
    LEGENDARY("Legendary Gad Donator",        4,  18995, 35, Crown.RESPECTED, "d80717",  50,  6, 800, 40, 25,  30,  60, 25, 18,  60, 30,  5),
    @SerializedName(value = "ROYAL_GAD_DONATOR", alternate = {"RESPECTED"})
    ROYAL("Royal Gad Donator",                5,  49995, 40, Crown.LEGENDARY, "2188ff",  25,  4, 2000, 50, 40, 15,  40, 50, 15,  50, 40,  1),
    @SerializedName(value = "DIVINE_GAD_DONATOR", alternate = {"LEGENDARY", "MYTHICAL", "UBER"})
    DIVINE("Divine Gad Donator",              6,  99995, 45, Crown.UBER,      "b65cff",   0,  0, 5000, 60, 60,  9,  20, 75, 12,  40, 50,  1),
    /**
     * Amascut was a special rank added by Will that is only obtainable
     * for 2 members `speckle` and `gim`. It has the Divine perks with its own name and crown.
     */
    AMASCUT("Amascut",                        6,      0, 45, Crown.AMASCUT,   "df782f",   0,  0,    0, 60, 60,  9,  20, 75, 12,  40, 50,  1),
    ;

    public static final MemberRank[] values = values();
    /** The six ranks in order, cheapest first. */
    public static final MemberRank[] DONATOR_RANKS = {DONATOR, SUPER, EXTREME, LEGENDARY, ROYAL, DIVINE};

    private final String displayName;
    private final int id;
    private final int priceCents;
    private final int dropRatePercent;
    private final Crown crown;
    private final String yellColor;
    private final int yellDelay;
    private final int togglesChance;
    private final int freeDonatorPoints;
    private final int coxPurplePercent;
    private final int presets;
    private final int specCooldownSeconds;
    private final int unskullCooldownSeconds;
    private final int slayerPointsPercent;
    private final int cancelTaskCost;
    private final int blockTaskCost;
    private final int bountyTargetSkips;
    private final int bountyTargetCooldownSeconds;

    MemberRank(String displayName, int id, int priceCents, int dropRatePercent, Crown crown, String yellColor, int yellDelay,
               int togglesChance, int freeDonatorPoints, int coxPurplePercent, int presets, int specCooldownSeconds,
               int unskullCooldownSeconds, int slayerPointsPercent, int cancelTaskCost, int blockTaskCost,
               int bountyTargetSkips, int bountyTargetCooldownSeconds) {
        this.displayName = displayName;
        this.id = id;
        this.priceCents = priceCents;
        this.dropRatePercent = dropRatePercent;
        this.crown = crown;
        this.yellColor = yellColor;
        this.yellDelay = yellDelay;
        this.togglesChance = togglesChance;
        this.freeDonatorPoints = freeDonatorPoints;
        this.coxPurplePercent = coxPurplePercent;
        this.presets = presets;
        this.specCooldownSeconds = specCooldownSeconds;
        this.unskullCooldownSeconds = unskullCooldownSeconds;
        this.slayerPointsPercent = slayerPointsPercent;
        this.cancelTaskCost = cancelTaskCost;
        this.blockTaskCost = blockTaskCost;
        this.bountyTargetSkips = bountyTargetSkips;
        this.bountyTargetCooldownSeconds = bountyTargetCooldownSeconds;
    }

    public static MemberRank fromId(int memberRank) {
        return MemberRank.values[memberRank];
    }

    /** Rank number 1..6 (as the website's DONATOR_RANK reward sends it), or null. */
    public static MemberRank forNumber(final int number) {
        return number >= 1 && number <= DONATOR_RANKS.length ? DONATOR_RANKS[number - 1] : null;
    }

    /** The highest rank whose price [cents] reaches, or NONE. */
    public static MemberRank forTotalCents(final long cents) {
        MemberRank rank = NONE;
        for (final MemberRank r : DONATOR_RANKS) {
            if (cents >= r.priceCents) {
                rank = r;
            }
        }
        return rank;
    }

    public boolean equalToOrGreaterThan(final MemberRank member) {
        return getId() >= member.getId();
    }

    /** The next rank up, or null at the top. */
    public MemberRank next() {
        return id < DONATOR_RANKS.length ? DONATOR_RANKS[id] : null;
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

    public int getPriceCents() {
        return priceCents;
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

    /** Extra drop rate as a fraction (0.15 = 15%). */
    public double getDR() {
        return dropRatePercent / 100.0D;
    }

    public int getDropRatePercent() {
        return dropRatePercent;
    }

    public int getTogglesChance() {
        return togglesChance;
    }

    /** Donator points that come with claiming this rank. */
    public int getFreeDonatorPoints() {
        return freeDonatorPoints;
    }

    /** Extra chance (percent) of a purple in the Chambers of Xeric chest. */
    public int getCoxPurplePercent() {
        return coxPurplePercent;
    }

    /** Total presets (Roat's custom kits). */
    public int getPresets() {
        return presets;
    }

    /** ::spec and ::hp cooldown, -1 = no access. */
    public int getSpecCooldownSeconds() {
        return specCooldownSeconds;
    }

    /** ::unskull cooldown, -1 = no access. */
    public int getUnskullCooldownSeconds() {
        return unskullCooldownSeconds;
    }

    /** Extra Slayer points for a finished task, percent. */
    public int getSlayerPointsPercent() {
        return slayerPointsPercent;
    }

    public int getCancelTaskCost() {
        return cancelTaskCost;
    }

    public int getBlockTaskCost() {
        return blockTaskCost;
    }

    /** Bounty Hunter target skips per 30 minutes. */
    public int getBountyTargetSkips() {
        return bountyTargetSkips;
    }

    /** Seconds until the next Bounty Hunter target after a skip. */
    public int getBountyTargetCooldownSeconds() {
        return bountyTargetCooldownSeconds;
    }

    public boolean isDonator() {
        return id > 0;
    }

    /** ::bank in safe places (Super and up). */
    public boolean hasBankCommand() {
        return equalToOrGreaterThan(SUPER);
    }

    /** Private boss instances without the fee (Extreme and up). */
    public boolean hasFreeInstances() {
        return equalToOrGreaterThan(EXTREME);
    }

    /** ::spec, ::hp and ::unskull (Legendary and up). */
    public boolean hasRestoreCommands() {
        return equalToOrGreaterThan(LEGENDARY);
    }

    /** No time limit in the AFK area (Royal and up). */
    public boolean hasUnlimitedAfk() {
        return equalToOrGreaterThan(ROYAL);
    }

    /** ::settele and ::tele 1-5 (Divine). */
    public boolean hasSavedTeleports() {
        return equalToOrGreaterThan(DIVINE);
    }

}
