package com.zenyte.game.content.killstreak;

import com.gadscape.game.model.item.ItemValueExtKt;
import com.zenyte.game.content.skills.prayer.Prayer;
import com.zenyte.game.item.Item;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Anti-farm / anti-rag gate for every PvP reward: blood money and killstreaks (Killstreaks), the PvP hiscores
 * (WildernessKills/Deaths) and Bounty Hunter points/emblems.
 * <p>
 * A kill only counts when the two players are different people (no shared IP or client UUID), the victim risked
 * something worth taking (anti-rag) and the victim is not a brand-new account, and the killer has not been rewarded
 * for the same victim (name or IP) in the last 45 minutes (KillstreakLog). The thresholds are constants so the owner
 * can tune them; set one to 0 to switch that rule off.
 */
public final class PvpKillValidator {
    private static final Logger log = LoggerFactory.getLogger(PvpKillValidator.class);

    /** Minimum value (coins) the victim must lose on death for the kill to count. */
    public static final long MIN_VICTIM_RISK = 100_000L;
    /** Minimum time played by the victim, in game ticks (6000 ticks = 1 hour). */
    public static final int MIN_VICTIM_PLAYTIME_TICKS = 6_000;

    public enum Result {
        OK(null),
        SELF("You can't be rewarded for killing yourself."),
        SAME_IP("You don't receive rewards for killing players on your own network."),
        SAME_DEVICE("You don't receive rewards for killing players on your own computer."),
        RECENT_KILL("You recently killed %s and therefore did not receive any rewards for that kill."),
        LOW_RISK("%s wasn't risking enough for this kill to count towards any rewards."),
        NEW_ACCOUNT("%s's account is too new for this kill to count towards any rewards.");

        private final String message;

        Result(final String message) {
            this.message = message;
        }

        public String message(final Player victim) {
            return message == null ? null : String.format(message, victim.getName());
        }
    }

    private PvpKillValidator() {
    }

    public static Result check(final Player attacker, final Player victim) {
        if (attacker == null || victim == null || attacker == victim
                || attacker.getUsername().equalsIgnoreCase(victim.getUsername())) {
            return Result.SELF;
        }
        final String attackerIp = attacker.getIP();
        if (attackerIp != null && attackerIp.equalsIgnoreCase(victim.getIP())) {
            return Result.SAME_IP;
        }
        if (shareUuid(attacker, victim)) {
            return Result.SAME_DEVICE;
        }
        if (attacker.getKillstreakLog().entryExists(victim)) {
            return Result.RECENT_KILL;
        }
        if (MIN_VICTIM_PLAYTIME_TICKS > 0 && victim.getVariables().getPlayTime() < MIN_VICTIM_PLAYTIME_TICKS) {
            return Result.NEW_ACCOUNT;
        }
        if (MIN_VICTIM_RISK > 0 && riskOf(victim) < MIN_VICTIM_RISK) {
            return Result.LOW_RISK;
        }
        return Result.OK;
    }

    /** Same check, and tells the killer why the kill does not count. */
    public static boolean validate(final Player attacker, final Player victim, final String rewardName) {
        final Result result = check(attacker, victim);
        if (result == Result.OK) {
            return true;
        }
        report(attacker, victim, result, rewardName);
        return false;
    }

    /** Tells the killer why a kill (already checked with {@link #check}) gives no reward, and logs it. */
    public static void report(final Player attacker, final Player victim, final Result result, final String rewardName) {
        if (result == null || result == Result.OK) {
            return;
        }
        final String message = result.message(victim);
        if (message != null && attacker != null) {
            attacker.sendMessage("<col=ef1020>" + message + "</col>");
        }
        log.info("PvP reward '{}' denied: {} killed {} -> {}", rewardName,
                attacker == null ? "null" : attacker.getUsername(), victim == null ? "null" : victim.getUsername(), result);
    }

    private static boolean shareUuid(final Player a, final Player b) {
        try {
            final byte[] current = b.getPlayerInformation().getUUID();
            if (a.getUUIDS() == null || b.getUUIDS() == null) {
                return false;
            }
            for (final byte[] mine : a.getUUIDS()) {
                if (mine == null || isBlank(mine)) continue;
                if (current != null && Arrays.equals(mine, current)) return true;
                for (final byte[] theirs : b.getUUIDS()) {
                    if (theirs != null && Arrays.equals(mine, theirs)) return true;
                }
            }
        } catch (final Exception e) {
            log.warn("UUID comparison failed", e);
        }
        return false;
    }

    private static boolean isBlank(final byte[] bytes) {
        for (final byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    /**
     * Approximate value the player loses on a PvP death right now: everything carried (inventory, equipment, looting
     * bag) minus the 3 most valuable items when unskulled, +1 with Protect Item (OSRS kept-items rule). Item values
     * are the server's GE/sell prices (ItemDefinitions.getSellPrice).
     */
    public static long riskOf(final Player player) {
        final List<Long> unitValues = new ArrayList<>();
        long total = 0L;
        for (final Item item : player.getInventory().getContainer().getItems().values()) {
            total += add(unitValues, item);
        }
        for (final Item item : player.getEquipment().getContainer().getItems().values()) {
            total += add(unitValues, item);
        }
        if (player.getInventory().containsAnyOf(ItemId.LOOTING_BAG, ItemId.LOOTING_BAG_22586)) {
            for (final Item item : player.getLootingBag().getContainer().getItems().values()) {
                if (item == null) continue;
                total += (long) ItemValueExtKt.getItemValue(item.getId()) * item.getAmount();
            }
        }
        int kept = player.getVariables().isSkulled() ? 0 : 3;
        if (player.getPrayerManager().isActive(Prayer.PROTECT_ITEM)) {
            kept++;
        }
        unitValues.sort(Collections.reverseOrder());
        for (int i = 0; i < kept && i < unitValues.size(); i++) {
            total -= unitValues.get(i);
        }
        return Math.max(0L, total);
    }

    /** Adds one entry per unit (capped, stacks are never all kept) and returns the stack value. */
    private static long add(final List<Long> unitValues, final Item item) {
        if (item == null) return 0L;
        final long unit = ItemValueExtKt.getItemValue(item.getId());
        final int units = Math.min(item.getAmount(), 4);
        for (int i = 0; i < units; i++) {
            unitValues.add(unit);
        }
        return unit * item.getAmount();
    }
}
