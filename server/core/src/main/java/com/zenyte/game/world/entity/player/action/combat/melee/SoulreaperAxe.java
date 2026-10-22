package com.zenyte.game.world.entity.player.action.combat.melee;

import com.google.common.eventbus.Subscribe;
import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.ArchiveWeapons;
import com.zenyte.game.world.entity.player.action.combat.GadScapeWeaponIds;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import com.zenyte.plugins.events.LoginEvent;

/**
 * Soulreaper axe soul stacks, as in OSRS since the Summer Sweep-Up of 22 Jul 2026: every attack with the axe adds a
 * stack (max 5), after that attack's damage is worked out. Each stack is +6% Strength level for the axe only, added to
 * prayer boosts. Stacks cost no hitpoints any more (and so give none back when they fade). With no attack for 50
 * ticks, one stack is lost, then another 50 ticks later. Stacks stay through weapon switches, teleports and logging out
 * (a stack is lost if the axe was banked before logging out). Behead (the special) uses them all up
 * ({@link SoulreaperAxeCombat}). The 241 client draws the stacks in the combat tab and on the special attack orb from
 * player varp 3784 (soulreaper_stacks).
 */
public final class SoulreaperAxe {
    public static final int AXE = 28338;
    public static final int AXE_ORNAMENT = 33335;
    public static final int MAX_STACKS = 5;
    private static final int DECAY_TICKS = 50;
    /** Saved with the character, so stacks survive logging out like on OSRS. */
    private static final String STACKS = "soulreaper stacks";
    private static final String LAST_ATTACK = "soulreaper last attack";
    private static final String DECAY_TASK = "soulreaper decay";

    private SoulreaperAxe() {
    }

    public static boolean isAxe(final int itemId) {
        return itemId == AXE || itemId == AXE_ORNAMENT || itemId == GadScapeWeaponIds.SOULREAPER_AXE_OR_33369;
    }

    public static int stacks(final Player player) {
        return Math.max(0, Math.min(MAX_STACKS, player.getNumericAttribute(STACKS).intValue()));
    }

    private static void setStacks(final Player player, final int stacks) {
        final int value = Math.max(0, Math.min(MAX_STACKS, stacks));
        player.addAttribute(STACKS, value);
        player.getVarManager().sendVar(ArchiveWeapons.SOULREAPER_STACKS_VARP, value);
    }

    /** What the stacks add to the Strength level multiplier (with prayers), while the axe is wielded. */
    public static double strengthBonus(final Player player) {
        return isAxe(player.getEquipment().getId(EquipmentSlot.WEAPON)) ? 0.06 * stacks(player) : 0;
    }

    public static void addStack(final Player player) {
        final int before = stacks(player);
        final int now = Math.min(MAX_STACKS, before + 1);
        setStacks(player, now);
        if (now != before) {
            player.sendFilteredMessage("Soul stacks: " + now + "/" + MAX_STACKS + " (+" + (now * 6) + "% Strength).");
        }
        player.getTemporaryAttributes().put(LAST_ATTACK, WorldThread.getCurrentCycle());
        startDecay(player);
    }

    /** Takes all the stacks (Behead) and returns how many there were. */
    public static int takeStacks(final Player player) {
        final int stacks = stacks(player);
        setStacks(player, 0);
        player.getTemporaryAttributes().put(LAST_ATTACK, WorldThread.getCurrentCycle());
        return stacks;
    }

    @Subscribe
    public static void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        int stacks = stacks(player);
        if (stacks <= 0) {
            return;
        }
        if (!carriesAxe(player)) {
            // OSRS (4 Sep 2024): a stack is lost when the axe was put in the bank before logging out
            stacks--;
        }
        setStacks(player, stacks);
        if (stacks > 0) {
            player.getTemporaryAttributes().put(LAST_ATTACK, WorldThread.getCurrentCycle());
            startDecay(player);
        }
    }

    private static boolean carriesAxe(final Player player) {
        return isAxe(player.getEquipment().getId(EquipmentSlot.WEAPON))
                || player.getInventory().containsAnyOf(AXE, AXE_ORNAMENT, GadScapeWeaponIds.SOULREAPER_AXE_OR_33369);
    }

    private static void startDecay(final Player player) {
        if (player.getTemporaryAttributes().containsKey(DECAY_TASK)) {
            return;
        }
        final WorldTask task = new WorldTask() {
            @Override
            public void run() {
                final int stacks = stacks(player);
                if (player.isFinished() || player.isNulled() || stacks <= 0) {
                    player.getTemporaryAttributes().remove(DECAY_TASK);
                    stop();
                    return;
                }
                final Object last = player.getTemporaryAttributes().get(LAST_ATTACK);
                final long now = WorldThread.getCurrentCycle();
                if (!(last instanceof Long) || now - (Long) last >= DECAY_TICKS) {
                    setStacks(player, stacks - 1);
                    player.getTemporaryAttributes().put(LAST_ATTACK, now);
                    player.sendFilteredMessage("A soul stack fades (" + (stacks - 1) + " left).");
                }
            }
        };
        player.getTemporaryAttributes().put(DECAY_TASK, task);
        WorldTasksManager.schedule(task, 1, 1);
    }
}
