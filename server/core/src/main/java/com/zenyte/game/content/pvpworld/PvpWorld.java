package com.zenyte.game.content.pvpworld;

import com.gadscape.game.world.PlayerEvent;
import com.gadscape.game.world.WorldHooks;
import com.google.common.eventbus.Subscribe;
import com.zenyte.game.GameConstants;
import com.zenyte.game.model.ui.testinterfaces.advancedsettings.SettingVariables;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Location;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.region.RegionArea;
import com.zenyte.game.world.region.area.plugins.DeathPlugin;
import com.zenyte.game.world.region.area.plugins.EntityAttackPlugin;
import com.zenyte.plugins.events.ServerLaunchEvent;
import com.zenyte.utils.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * PvP world mode (PVP-29). OFF by default: nothing changes unless the world is configured as a PvP world.
 * <p>
 * Switch it on with {@code types: [MEMBERS, PVP]} (or {@code HIGH_RISK}) for the world in worlds.yml, or with
 * {@code -Dgadscape.world.pvp=true} / {@code -Dgadscape.world.highrisk=true}. On a PvP world (OSRS rules, simplified):
 * <ul>
 *     <li>Players outside safe zones (the banks/hubs listed in {@link #SAFE_ZONES}) and outside any area with its own
 *     combat rules can attack each other; the "Attack" option is shown. Entering/leaving a safe zone is announced.</li>
 *     <li>Combat range is +/-15 combat levels, +15 on top of the wilderness level inside the wilderness.</li>
 *     <li>Attacking someone who has not attacked you skulls you (respecting the skull-prevention setting).</li>
 *     <li>Items lost to a player killer drop for that killer like in the wilderness.</li>
 *     <li>High-risk worlds: Protect Item does not protect an item on death.</li>
 * </ul>
 * Areas that manage PvP themselves (wilderness, Clan Wars, duel arena, minigames/instances with their own death or
 * attack rules) are left alone.
 */
public final class PvpWorld {
    private static final Logger log = LoggerFactory.getLogger(PvpWorld.class);
    /** Temporary attribute: this player's attackable state is currently set by the PvP world mode. */
    private static final String MANAGED_ATTR = "pvp world managed";
    /** OSRS PvP worlds: +/-15 combat levels outside the wilderness. */
    public static final int LEVEL_RANGE = 15;

    /** Safe zones, {swX, swY, neX, neY} inclusive, all planes. The owner can extend this list. */
    private static final int[][] SAFE_ZONES = {
            {3074, 3480, 3106, 3518},   // Edgeville bank and GadScape home square
            {3140, 3465, 3190, 3515},   // Grand Exchange
            {3180, 3432, 3194, 3447},   // Varrock west bank
            {3250, 3418, 3258, 3424},   // Varrock east bank
            {2943, 3365, 2949, 3374},   // Falador west bank
            {3009, 3353, 3019, 3359},   // Falador east bank
            {3200, 3200, 3226, 3237},   // Lumbridge castle (respawn point)
            {3088, 3240, 3098, 3247},   // Draynor bank
            {3265, 3160, 3273, 3174},   // Al Kharid bank
            {2806, 3438, 2812, 3446},   // Catherby bank
            {2721, 3487, 2730, 3494},   // Seers' Village bank
            {2612, 3330, 2621, 3335},   // East Ardougne north bank
            {2649, 3280, 2658, 3287},   // East Ardougne south bank
            {2609, 3088, 2616, 3097},   // Yanille bank
            {2435, 3081, 2446, 3098},   // Castle Wars lobby
            {3328, 7168, 3391, 7231},   // new-player registration square
    };

    private static Boolean enabled;
    private static Boolean highRisk;

    private PvpWorld() {
    }

    public static boolean isEnabled() {
        if (enabled == null) {
            enabled = Boolean.getBoolean("gadscape.world.pvp") || isHighRisk() || hasType("PVP");
        }
        return enabled;
    }

    public static boolean isHighRisk() {
        if (highRisk == null) {
            highRisk = Boolean.getBoolean("gadscape.world.highrisk") || hasType("HIGH_RISK");
        }
        return highRisk;
    }

    private static boolean hasType(final String type) {
        try {
            if (GameConstants.WORLD_PROFILE == null) return false;
            final List<String> types = GameConstants.WORLD_PROFILE.getTypes();
            if (types == null) return false;
            for (final String t : types) {
                if (t != null && t.trim().toUpperCase(Locale.ROOT).equals(type)) return true;
            }
        } catch (final Exception e) {
            log.warn("Could not read world types", e);
        }
        return false;
    }

    public static boolean inSafeZone(final Location tile) {
        final int x = tile.getX();
        final int y = tile.getY();
        for (final int[] zone : SAFE_ZONES) {
            if (x >= zone[0] && x <= zone[2] && y >= zone[1] && y <= zone[3]) return true;
        }
        return false;
    }

    /** True when the PvP world mode governs this player (open world, not inside an area with its own rules). */
    private static boolean governs(final RegionArea area) {
        return area == null || !(area instanceof EntityAttackPlugin) && !(area instanceof DeathPlugin) && !area.isDynamicArea();
    }

    /** Whether a PvP death at this tile drops the loot for the killer (open-world PvP tile on a PvP world). */
    public static boolean isPvpTile(final Player victim) {
        return isEnabled() && victim.getTemporaryAttributes().containsKey(MANAGED_ATTR) && victim.isCanPvp();
    }

    @Subscribe
    public static void onServerLaunch(final ServerLaunchEvent event) {
        if (!isEnabled()) {
            return;
        }
        log.info("PvP world mode is ON (high risk: {}).", isHighRisk());
        final WorldHooks hooks = event.getWorldThread().getHooks();
        hooks.register(PlayerEvent.PostProcess.class, post -> process(post.getPlayer()));
    }

    /** Per tick: open-world players outside safe zones are attackable. */
    private static void process(final Player player) {
        if (player == null || player.isNulled() || player.isFinished()) return;
        final RegionArea area = player.getArea();
        final boolean managed = player.getTemporaryAttributes().containsKey(MANAGED_ATTR);
        if (!governs(area)) {
            if (managed) {
                // Entered an area with its own rules: give control back. Areas that set the state on entry already
                // did; plain areas get the safe state.
                player.getTemporaryAttributes().remove(MANAGED_ATTR);
                if (!(area instanceof EntityAttackPlugin)) {
                    player.setCanPvp(false);
                }
            }
            return;
        }
        final boolean wanted = !inSafeZone(player.getLocation());
        if (!managed) {
            player.getTemporaryAttributes().put(MANAGED_ATTR, Boolean.TRUE);
            player.setCanPvp(wanted);
            player.sendMessage(wanted ? "<col=ef1020>This is a PvP world: other players can attack you here.</col>"
                    : "You are in a safe zone.");
            return;
        }
        if (player.isCanPvp() != wanted) {
            player.setCanPvp(wanted);
            player.sendMessage(wanted ? "<col=ef1020>You have left the safe zone.</col>" : "You have entered a safe zone.");
        }
    }

    /** Called before the wilderness takes over, so its entry logic (overlay, attackable) always runs. */
    public static void release(final Player player) {
        if (player.getTemporaryAttributes().remove(MANAGED_ATTR) != null) {
            player.setCanPvp(false);
        }
    }

    /**
     * Combat-level and skull rules for an open-world attack on a PvP world. Returns false (with the reason) if the
     * attack is not allowed. Always true when the mode is off or an area handles the attack.
     */
    public static boolean canAttack(final Player player, final Player target) {
        if (!isEnabled() || !governs(player.getArea()) || !player.getTemporaryAttributes().containsKey(MANAGED_ATTR)) {
            return true;
        }
        if (!player.isCanPvp() || !target.isCanPvp()) {
            player.sendMessage("You can't attack someone in a safe zone.");
            return false;
        }
        if (Math.abs(player.getSkills().getCombatLevel() - target.getSkills().getCombatLevel()) > LEVEL_RANGE) {
            player.sendMessage("The difference between your Combat level and the Combat level of "
                    + target.getPlayerInformation().getDisplayname() + " is too great.");
            return false;
        }
        final long now = WorldThread.getCurrentCycle();
        final boolean wouldSkull = player.getAttackedByPlayers().getLong(target.getUsername()) < now
                && target.getAttackedByPlayers().getLong(player.getUsername()) < now;
        if (wouldSkull) {
            if (!player.getVariables().isSkulled()
                    && player.getVarManager().getBitValue(SettingVariables.PK_SKULL_PREVENTION_VARBIT_ID) == 1) {
                player.sendMessage("You cannot attack this target as it would result in you getting skulled.");
                return false;
            }
            player.getVariables().setSkull(true);
            target.getAttackedByPlayers().put(player.getUsername(), TimeUnit.MINUTES.toTicks(20) + now);
        }
        return true;
    }

    /** Extra combat levels allowed inside the wilderness on a PvP world (OSRS: wilderness level + 15). */
    public static int wildernessLevelBonus() {
        return isEnabled() ? LEVEL_RANGE : 0;
    }

    /** High-risk worlds: Protect Item does not keep an extra item. */
    public static boolean protectItemDisabled() {
        return isHighRisk();
    }

    /** For the wilderness: true when the given player is currently governed by the PvP world mode. */
    public static boolean isManaged(final Player player) {
        return player.getTemporaryAttributes().containsKey(MANAGED_ATTR);
    }
}
