package com.zenyte.game.world.entity.player.action.combat.moon;

import com.zenyte.game.task.WorldTask;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Eclipse Moon set effect (OSRS wiki "Eclipse atlatl"): a burn deals 10 damage over 40 ticks, 1 damage every 4 ticks.
 * Every burn runs on its own; a target can have up to 5 at once (a new one needs a free stack). Unapplied damage is lost
 * when the target dies. The Eclipse special consumes the remaining damage of every burn.
 */
public final class EclipseBurn {

    private EclipseBurn() {
    }

    public static final int MAX_STACKS = 5;
    public static final int DAMAGE = 10;
    public static final int INTERVAL = 4;
    private static final String KEY = "eclipse_moon_burns";

    private static final class Burn {
        private int remaining = DAMAGE;
        private int ticks = INTERVAL;
    }

    private static final class Burns implements WorldTask {
        private final Entity target;
        private final List<Burn> burns = new ArrayList<>(MAX_STACKS);
        private Player source;
        private boolean running;

        private Burns(final Entity target) {
            this.target = target;
        }

        @Override
        public void run() {
            if (target.isFinished() || target.isDead() || burns.isEmpty()) {
                burns.clear();
                running = false;
                target.getTemporaryAttributes().remove(KEY);
                stop();
                return;
            }
            final Iterator<Burn> iterator = burns.iterator();
            while (iterator.hasNext()) {
                final Burn burn = iterator.next();
                if (--burn.ticks > 0) {
                    continue;
                }
                burn.ticks = INTERVAL;
                burn.remaining--;
                target.applyHit(new Hit(source, 1, HitType.BURN));
                if (burn.remaining <= 0) {
                    iterator.remove();
                }
            }
        }
    }

    private static Burns burns(final Entity target, final boolean create) {
        final Object current = target.getTemporaryAttributes().get(KEY);
        if (current instanceof Burns) {
            return (Burns) current;
        }
        if (!create) {
            return null;
        }
        final Burns burns = new Burns(target);
        target.getTemporaryAttributes().put(KEY, burns);
        return burns;
    }

    /** Adds a burn when the target has a free stack; returns whether it did. */
    public static boolean apply(final Player source, final Entity target) {
        if (target.isDead() || target.isFinished()) {
            return false;
        }
        final Burns burns = burns(target, true);
        if (burns.burns.size() >= MAX_STACKS) {
            return false;
        }
        burns.source = source;
        burns.burns.add(new Burn());
        if (!burns.running) {
            burns.running = true;
            WorldTasksManager.schedule(burns, 0, 0);
        }
        return true;
    }

    /** The burn damage the target has left to take. */
    public static int remaining(final Entity target) {
        final Burns burns = burns(target, false);
        if (burns == null) {
            return 0;
        }
        int total = 0;
        for (final Burn burn : burns.burns) {
            total += burn.remaining;
        }
        return total;
    }

    /** Removes every burn and returns the damage they had left (the Eclipse special). */
    public static int consume(final Entity target) {
        final int remaining = remaining(target);
        final Burns burns = burns(target, false);
        if (burns != null) {
            burns.burns.clear();
        }
        return remaining;
    }
}
