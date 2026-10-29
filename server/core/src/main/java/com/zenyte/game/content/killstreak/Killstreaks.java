package com.zenyte.game.content.killstreak;

import com.gadscape.game.world.entity.player.PlayerAttributesKt;
import com.zenyte.game.content.well.WellPerk;
import com.zenyte.game.content.xamphur.XamphurBoost;
import com.zenyte.game.item.Item;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.MessageType;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.privilege.MemberRank;
import com.zenyte.game.world.region.area.wilderness.WildernessArea;
import org.apache.commons.lang3.StringUtils;

public class Killstreaks {
    /** Blood tokens (the old Blood money), GadScape's currency. */
    public static final int BLOODMONEY_ITEM_ID = 13_307;

    /** Tokens for a kill on a victim carrying nothing. */
    public static final int BASE_TOKENS_MIN = 100;
    /** Tokens for a kill on a victim carrying {@link #FULL_REWARD_RISK} or more. */
    public static final int BASE_TOKENS_MAX = 500;
    /** Victim's carried value (blood tokens, premade-kit items left out) that gives the full base reward. */
    public static final long FULL_REWARD_RISK = 50_000L;
    /** Bonus per kill in the killer's current streak, percent. */
    public static final int STREAK_BONUS_PERCENT_PER_KILL = 10;
    /** Most the streak bonus adds, percent. */
    public static final int STREAK_BONUS_PERCENT_MAX = 100;

    /**
     * Whether a victim died inside the active wilderness hot zone (double blood money). Set by core-restricted's
     * WildernessKillStreakModule at launch; the double used to exist only in that module's unused copy of this code.
     */
    public static volatile java.util.function.Predicate<Player> hotZone = player -> false;

    public static void handlePlayerKill(Player attacker, Player victim) {
        if(!WildernessArea.isWithinWilderness(victim.getLocation().getX(), victim.getLocation().getY()))
            return;
        // Anti-farm / anti-rag: same IP or client UUID, recent kill of the same victim, low risk, new account.
        // The validator only decides the killer's rewards. The victim's side (death count, streak reset, shutdown
        // announcement) always happens, except between one person's own accounts (SELF/SAME_IP/SAME_DEVICE), so a PKer
        // who always risks under the threshold still loses their streak and records the death (as Bounty Hunter does).
        final PvpKillValidator.Result result = PvpKillValidator.check(attacker, victim);
        final boolean sameOwner = result == PvpKillValidator.Result.SELF || result == PvpKillValidator.Result.SAME_IP
                || result == PvpKillValidator.Result.SAME_DEVICE;
        final boolean rewarded = result == PvpKillValidator.Result.OK;
        // Read before any reset: the shutdown bonus below and the announcement use the victim's streak.
        final int bloodmoneyBeforeBoosts = rewarded ? getTotalBloodmoneyForKill(attacker, victim) : 0;
        if (!sameOwner) {
            victim.getAttributes().put("WildernessDeaths", victim.getNumericAttribute("WildernessDeaths").intValue() + 1);
            PlayerAttributesKt.setPvpDeaths(victim, PlayerAttributesKt.getPvpDeaths(victim) + 1);
            refreshKDR(victim);
            announceShutdown(attacker, victim);
            resetKillstreak(victim, true);
        }
        if (!rewarded) {
            PvpKillValidator.report(attacker, victim, result, "blood tokens");
            return;
        }

        // PvP hiscores (HiscoresCategory.PVP) and the wilderness K/D varps only count validated kills (PVP-21).
        attacker.getAttributes().put("WildernessKills", attacker.getNumericAttribute("WildernessKills").intValue() + 1);
        refreshKDR(attacker);

        int totalBloodmoney = bloodmoneyBeforeBoosts;
        if(World.hasBoost(XamphurBoost.BONUS_BLOOD_MONEY))
            totalBloodmoney *= 2;
        if (attacker.getVariables().getBloodMoneyBoosterLeft() > 0) {
            attacker.getVariables().setBloodMoneyBoosterLeft(attacker.getVariables().getBloodMoneyBoosterLeft() - 1);
            totalBloodmoney *= 1.25;
        }
        if (hotZone.test(victim))
            totalBloodmoney *= 2;
        if(World.hasBoost(WellPerk.DOUBLE_BLOOD_MONEY))
            totalBloodmoney *= 2;
        int attackerCurrentStreak = PlayerAttributesKt.getPvpKillStreak(attacker);
        PlayerAttributesKt.setPvpKillStreak(attacker, attackerCurrentStreak + 1);

        attacker.sendMessage(
                "You have received <col=ff0000>" + totalBloodmoney + "</col> blood tokens. Your killstreak is now <col=ff0000>"
                        + (attackerCurrentStreak + 1) + "</col>.");
        attacker.getInventory().addOrDrop(new Item(BLOODMONEY_ITEM_ID, totalBloodmoney));

        PlayerAttributesKt.setPvpKills(attacker, PlayerAttributesKt.getPvpKills(attacker) + 1);

        attacker.getKillstreakLog().addEntry(victim);

        announceKillstreak(attacker, attackerCurrentStreak + 1);
    }

    private static void refreshKDR(final Player player) {
        player.getVarManager().sendVar(1102, player.getNumericAttribute("WildernessDeaths").intValue());
        player.getVarManager().sendVar(1103, player.getNumericAttribute("WildernessKills").intValue());
    }

    /**
     * Resets a players killstreak
     *
     * @param player - the player
     * @param announce - announce to the world
     */
    private static void resetKillstreak(Player player, boolean announce) {
        PlayerAttributesKt.setPvpKillStreak(player, 0);
    }

    /**
     * Globally announce killstreak to the whole world
     *
     * @param player - the player
     * @param newKillstreak
     */
    private static void announceKillstreak(Player player, int newKillstreak) {
        if (newKillstreak <= 0) return;

        if (newKillstreak % 5 == 0) {
            World.sendMessage(MessageType.FILTERABLE, "<img=9><col=ff0000> " + player.getName()
                    + " has a killstreak of " + newKillstreak + " and earns +" + getKillstreakBoost(newKillstreak)
                    + "% blood tokens per kill!</col>");
        }
    }

    private static void announceShutdown(Player attacker, Player victim) {
        int victimsKillstreak = PlayerAttributesKt.getPvpKillStreak(victim);
        if (victimsKillstreak >= 5) {
            World.sendMessage(MessageType.FILTERABLE,
                    "<img=9><col=ff0000> " + attacker.getName()
                            + " has shut down " + victim.getName() +
                            " with a killing spree of " + victimsKillstreak + "!</col>");
        }
    }

    /**
     * Blood tokens for a rewarded kill, before the world/booster multipliers: 100 tokens for a victim carrying
     * nothing, rising in a straight line to 500 at a carried value of 50,000 tokens or more (premade-kit items don't
     * count, PvpKillValidator.carriedTokens), plus 10% per kill in the killer's current streak, at most +100%.
     */
    private static int getTotalBloodmoneyForKill(Player attacker, Player victim) {
        final long risk = Math.min(Math.max(0L, PvpKillValidator.carriedTokens(victim)), FULL_REWARD_RISK);
        final long base = BASE_TOKENS_MIN + (BASE_TOKENS_MAX - BASE_TOKENS_MIN) * risk / FULL_REWARD_RISK;
        final int streakBonus = getKillstreakBoost(PlayerAttributesKt.getPvpKillStreak(attacker));
        return (int) (base * (100 + streakBonus) / 100);
    }

    /**
     * The killstreak bonus, percent: 10% per kill in the streak, at most 100%.
     *
     * @param killstreak - the killstreak
     */
    private static int getKillstreakBoost(int killstreak) {
        if (killstreak <= 0) return 0;
        return Math.min(killstreak * STREAK_BONUS_PERCENT_PER_KILL, STREAK_BONUS_PERCENT_MAX);
    }

    private static int getRankBoost(MemberRank rank) {
        switch (rank) {
            case DONATOR: return 5;
            case SUPER: return 10;
            case EXTREME: return 15;
            case LEGENDARY: return 20;
            case ROYAL: return 30;
            case AMASCUT:
            case DIVINE: {
                return 50;
            }
        }
        return 0;
    }

    private static String getChatIconForKillstreak(int killstreak) {
        if (killstreak >= 5 && killstreak <= 10) {
            return "<img=9>";
        }
        if (killstreak >= 11 && killstreak <= 15) {
            return "<img=10>";
        }
        if (killstreak >= 16 && killstreak <= 20) {
            return "<img=11>";
        }
        if (killstreak >= 21 && killstreak <= 25) {
            return "<img=12>";
        }
        if (killstreak >= 26) {
            return "<img=13>";
        }
        return "";
    }

}
