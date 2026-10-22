package com.zenyte.game.content.skills.construction.combat;

import com.zenyte.game.util.Direction;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.npc.NPC;

/**
 * A dungeon guard or treasure room monster brought to life by challenge mode ({@link HouseCombat}).
 * <p>
 * The NPC ids are the real monsters (skeleton, hobgoblin, steel dragon...), which have full drop tables, so this class turns
 * every reward off: no drop (and no crystal key / voucher / drop processor rolls, which live in {@link NPC#drop}), no slayer
 * credit, combat achievements or kill count, no superior spawn, and no respawn. Guards also give no combat experience (OSRS
 * wiki "Skeleton guard": "will give no experience when killed"). When it dies the house puts the furniture back.
 */
public final class HouseMonsterNPC extends NPC {

    private final HouseCombat houseCombat;
    private final boolean guard;

    HouseMonsterNPC(final int id, final Location tile, final HouseCombat houseCombat, final boolean guard) {
        // The five-argument constructor marks the NPC as spawned: it never gets a respawn task.
        super(id, tile, Direction.SOUTH, 3, true);
        this.houseCombat = houseCombat;
        this.guard = guard;
        setForceAggressive(true);
    }

    /** OSRS: house guards and treasure room monsters drop nothing. */
    @Override
    protected void drop(final Location tile) {
    }

    /** Death bookkeeping only: a null source skips slayer credit, combat achievements and death-charge effects. */
    @Override
    protected void onDeath(final Entity source) {
        super.onDeath(null);
    }

    @Override
    protected void onFinish(final Entity source) {
        // forceKilled skips the drop and the slayer superior roll; a null source skips the kill-count notification.
        forceKilled = true;
        super.onFinish(null);
        houseCombat.onMonsterDied(this);
    }

    /** Never respawns (also covers the stuck-NPC despawn path, which calls this directly). */
    @Override
    public void setRespawnTask() {
        if (!isFinished()) {
            reset();
            finish();
        }
    }

    @Override
    public float getXpModifier(final Hit hit) {
        return guard ? 0.0F : super.getXpModifier(hit);
    }
}
