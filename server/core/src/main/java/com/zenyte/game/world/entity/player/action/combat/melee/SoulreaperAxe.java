package com.zenyte.game.world.entity.player.action.combat.melee;

import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;

/**
 * Soulreaper axe soul stacks, as in OSRS (2026): every attack with the axe adds a stack (max 5), after that attack's
 * damage is worked out. Each stack is +6% Strength level for the axe only, added to prayer boosts. With no attack for
 * 50 ticks, one stack is lost and heals 8 hitpoints, then another 50 ticks later. Stacks stay through weapon switches
 * and teleports. Behead (the special) uses them all up ({@link SoulreaperAxeCombat}).
 */
public final class SoulreaperAxe {
    public static final int AXE = 28338;
    public static final int AXE_ORNAMENT = 33335;
    public static final int MAX_STACKS = 5;
    private static final int DECAY_TICKS = 50;
    private static final int DECAY_HEAL = 8;
    private static final String STACKS = "soulreaper stacks";
    private static final String LAST_ATTACK = "soulreaper last attack";
    private static final String DECAY_TASK = "soulreaper decay";

    private SoulreaperAxe() {
    }

    public static boolean isAxe(final int itemId) {
        return itemId == AXE || itemId == AXE_ORNAMENT;
    }

    public static int stacks(final Player player) {
        final Object o = player.getTemporaryAttributes().get(STACKS);
        return o instanceof Integer ? (Integer) o : 0;
    }

    /** What the stacks add to the Strength level multiplier (with prayers), while the axe is wielded. */
    public static double strengthBonus(final Player player) {
        return isAxe(player.getEquipment().getId(EquipmentSlot.WEAPON)) ? 0.06 * stacks(player) : 0;
    }

    public static void addStack(final Player player) {
        player.getTemporaryAttributes().put(STACKS, Math.min(MAX_STACKS, stacks(player) + 1));
        player.getTemporaryAttributes().put(LAST_ATTACK, WorldThread.getCurrentCycle());
        startDecay(player);
    }

    /** Takes all the stacks (Behead) and returns how many there were. */
    public static int takeStacks(final Player player) {
        final int stacks = stacks(player);
        player.getTemporaryAttributes().remove(STACKS);
        player.getTemporaryAttributes().put(LAST_ATTACK, WorldThread.getCurrentCycle());
        return stacks;
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
                    player.getTemporaryAttributes().put(STACKS, stacks - 1);
                    player.getTemporaryAttributes().put(LAST_ATTACK, now);
                    player.heal(DECAY_HEAL);
                }
            }
        };
        player.getTemporaryAttributes().put(DECAY_TASK, task);
        WorldTasksManager.schedule(task, 1, 1);
    }
}
