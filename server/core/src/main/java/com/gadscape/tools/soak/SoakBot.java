package com.gadscape.tools.soak;

import com.gadscape.game.content.bountyhunter.BountyHunterController;
import com.gadscape.game.world.entity.player.FakePlayer;
import com.gadscape.network.rsprot.RsprotInfo;
import com.zenyte.game.content.bountyhunter.BountyHunter;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.region.GlobalAreaManager;
import com.zenyte.game.world.region.LocationMap;
import com.zenyte.plugins.events.LogoutEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A soak test bot: a FakePlayer that can never die, so it never drops anything (its gear is a copy of the
 * developer's; a death would put that copy on the floor for others to pick up).
 * <p>
 * Names start with "~", which no real account can have (TextUtils.isValidName: [a-z0-9_]), and the counter never
 * restarts while the server runs, so a bot never takes the name of a real player (World.namedPlayers).
 */
final class SoakBot extends FakePlayer {
    private static final Logger log = LoggerFactory.getLogger(SoakBot.class);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Whether the bot was registered with rsprot player info (so real clients see it). */
    private boolean infoAllocated;

    SoakBot() {
        super("~soak" + SEQUENCE.incrementAndGet());
    }

    /** Hitpoints never drop below 1: every hit path goes through here (removeHitpoints, specials, poison, ...). */
    @Override
    public boolean setHitpoints(final int hitpoints) {
        return super.setHitpoints(Math.max(1, hitpoints));
    }

    /** Safety net for code that kills directly: heal instead of dying, so DeathMechanics never drops the copied gear. */
    @Override
    public void sendDeath() {
        super.setHitpoints(getMaxHitpoints());
    }

    /** Registers the bot with rsprot player info so nearby real clients see it (bots have no client of their own). */
    void allocateInfo() {
        try {
            RsprotInfo.login(this);
            infoAllocated = true;
        } catch (final Exception e) {
            log.debug("soak bot {} has no rsprot player info", getUsername(), e);
        }
    }

    /**
     * Takes the bot out of the world like a logout, without the parts that would leak a bot into persistent or
     * external state (save, local/website hiscores, analytics: Player.finish posts LogoutEvent to all of those).
     * Bounty Hunter gets its logout handling so that no real player keeps a bot as a target.
     */
    void logoutBot() {
        if (isFinished()) {
            World.removePlayer(this);
            return;
        }
        final LogoutEvent event = new LogoutEvent(this);
        run("Bounty Hunter", () -> BountyHunterController.onPlayerLogout(event));
        run("legacy Bounty Hunter", () -> BountyHunter.onLogout(event));
        run("interfaces", () -> getInterfaceHandler().closeInterfaces());
        setFinished(true);
        run("chunk", () -> World.updateEntityChunk(this, true));
        run("location map", () -> LocationMap.remove(this));
        run("area", () -> GlobalAreaManager.update(this, false, true));
        if (infoAllocated) {
            run("rsprot info", () -> RsprotInfo.logout(this));
            infoAllocated = false;
        }
        // Same as World.unregisterPlayer: the player leaves the list on the next tick.
        final Player self = this;
        WorldTasksManager.schedule(() -> World.removePlayer(self), 1);
    }

    private void run(final String what, final Runnable action) {
        try {
            action.run();
        } catch (final Exception e) {
            log.warn("soak bot {}: {} logout step failed", getUsername(), what, e);
        }
    }
}
