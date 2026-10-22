package com.zenyte.game.content.skills.prayer;

import com.zenyte.game.model.item.pluginextensions.ItemPlugin;
import com.zenyte.game.model.ui.testinterfaces.PrayerTab;
import com.zenyte.game.world.WorldThread;
import com.zenyte.game.world.entity.Entity;
import com.zenyte.game.world.entity.masks.Hit;
import com.zenyte.game.world.entity.masks.HitType;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.combatdefs.NPCCombatDefinitions;
import com.zenyte.game.world.entity.npc.combatdefs.StatDefinitions;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.SkillConstants;

/**
 * Ruinous Powers, the 24-prayer Zarosian book of Deadman: Annihilation and the Trailblazer Reloaded relic (interface 541
 * with varbit 14826 prayerbook = 1; active prayers in varp 3780, quick prayers in varp 3781, per-prayer unlocks in varp
 * 3785). The book is switched with "Change Prayerbook" on the Ruinous powers item (28775). Prayers that mirror a
 * standard prayer (Dampen Melee/Ranged/Magic, Protect Item, Rejuvenation, Vindication, Wrath) reuse that prayer's code
 * through {@link Prayer#getRuinousEquivalent()} and {@link PrayerManager#isActive(Prayer)}; the effects of their own are
 * here. Values from the OSRS wiki (Ruinous Powers, Metabolise, Ruinous Grace pages).
 */
public final class RuinousPowers {

    private RuinousPowers() {
    }

    /** Temporary attribute: the world cycle Metabolise was switched on. */
    public static final String METABOLISE_START = "ruinous_metabolise_start";
    /** "Metabolise must be active for at least 15 seconds in order to take effect." */
    private static final int METABOLISE_WARMUP_TICKS = 25;
    /** Temporary attribute guarding Cruor's Vow against several hits of one attack each counting as a kill. */
    private static final String CRUORS_VOW_LAST_KILL = "ruinous_cruors_vow_kill";

    /**
     * Per-prayer unlock varbits of the book (varp 3785 bits 0-4 and 6-9, and 14977). The client greys out a prayer whose
     * unlock varbit is 0; every Ruinous prayer is available by Prayer level alone here, so login sends them all as 1.
     */
    public static final int[] UNLOCK_VARBITS = {14852, 14853, 14854, 14855, 14856, 14858, 14859, 14860, 14861, 14977};

    /** Handles a prayer-tab button while the Ruinous Powers book is active; false when the standard book should. */
    public static boolean handleButton(final Player player, final int componentId) {
        final PrayerManager manager = player.getPrayerManager();
        if (manager == null || !manager.isRuinousBook()) {
            return false;
        }
        final Prayer prayer = Prayer.ruinous(componentId - PrayerTab.FIRST_PRAYER_COMPONENT);
        if (prayer != null) {
            manager.togglePrayer(prayer);
        }
        return true;
    }

    /** Switches between the standard book and Ruinous Powers. */
    public static void toggleBook(final Player player) {
        final PrayerManager manager = player.getPrayerManager();
        if (manager == null) {
            return;
        }
        if (player.isLocked() || player.isDead()) {
            return;
        }
        final boolean toRuinous = !manager.isRuinousBook();
        manager.setBook(toRuinous ? Prayer.Book.RUINOUS : Prayer.Book.STANDARD);
        player.sendMessage(toRuinous ? "You feel the ruinous powers of Zaros take hold of your prayers." : "You return to the standard prayer book.");
    }

    /**
     * The attack-side effects of the Ruinous prayers, called from {@link Player#handleOutgoingHit} before each of the
     * player's combat hits lands.
     */
    public static void onOutgoingHit(final Player player, final Entity target, final Hit hit) {
        final PrayerManager manager = player.getPrayerManager();
        if (manager == null || !manager.isRuinousBook() || target == null || hit == null) {
            return;
        }
        final HitType type = hit.getHitType();
        final boolean attack = type == HitType.MELEE || type == HitType.RANGED || type == HitType.MAGIC;
        if (!attack) {
            return;
        }
        final int damage = Math.max(0, Math.min(hit.getDamage(), target.getHitpoints()));
        // Purge: "Reduces the player's and opponent's prayer points by 33% of damage dealt".
        if (damage > 0 && manager.isActive(Prayer.RUINOUS_PURGE)) {
            final int drain = damage / 3;
            if (drain > 0) {
                if (target instanceof Player && ((Player) target).getPrayerManager() != null) {
                    ((Player) target).getPrayerManager().drainPrayerPoints(drain);
                }
                manager.drainPrayerPoints(drain);
            }
        }
        // Umbra's Vow: "Attacks drain 5% of the opponent's Defence, up to 15% of their base level".
        if (manager.isActive(Prayer.RUINOUS_UMBRAS_VOW)) {
            drainCapped(target, SkillConstants.DEFENCE);
        }
        // Glacies' Vow: "Attacks drain 5% of a monster's Magic, up to 15% of their base level".
        if (target instanceof NPC && manager.isActive(Prayer.RUINOUS_GLACIES_VOW)) {
            drainCapped(target, SkillConstants.MAGIC);
        }
        // Cruor's Vow: "Heals 3 Hitpoints and drains 3 Prayer points upon killing a target".
        if (damage > 0 && target.getHitpoints() - hit.getDamage() <= 0 && manager.isActive(Prayer.RUINOUS_CRUORS_VOW)) {
            final Object last = player.getTemporaryAttributes().get(CRUORS_VOW_LAST_KILL);
            if (last != target) {
                player.getTemporaryAttributes().put(CRUORS_VOW_LAST_KILL, target);
                player.heal(3);
                manager.drainPrayerPoints(3);
            }
        }
    }

    /** Drains 5% of the skill, never taking it below 85% of the target's base level. */
    private static void drainCapped(final Entity target, final int skill) {
        final int base;
        final int current;
        if (target instanceof Player) {
            final Player p = (Player) target;
            base = p.getSkills().getLevelForXp(skill);
            current = p.getSkills().getLevel(skill);
        } else if (target instanceof NPC) {
            final NPC npc = (NPC) target;
            final NPCCombatDefinitions baseDefinitions = npc.getBaseCombatDefinitions();
            final NPCCombatDefinitions definitions = npc.getCombatDefinitions();
            if (baseDefinitions == null || definitions == null) {
                return;
            }
            base = npcLevel(baseDefinitions.getStatDefinitions(), skill);
            current = npcLevel(definitions.getStatDefinitions(), skill);
        } else {
            return;
        }
        final int floor = base - (int) (base * 0.15);
        final int amount = Math.min(Math.max(1, (int) (current * 0.05)), current - floor);
        if (amount > 0) {
            target.drainSkill(skill, amount);
        }
    }

    private static int npcLevel(final StatDefinitions stats, final int skill) {
        if (stats == null) {
            return 0;
        }
        return skill == SkillConstants.DEFENCE ? stats.getDefenceLevel() : skill == SkillConstants.MAGIC ? stats.getMagicLevel() : 0;
    }

    /** Rebuke: "Increases recoil damage by 10%". */
    public static int rebuke(final Player player, final int reflected) {
        final PrayerManager manager = player.getPrayerManager();
        if (manager == null || !manager.isActive(Prayer.RUINOUS_REBUKE)) {
            return reflected;
        }
        return (int) Math.floor(reflected * 1.1);
    }

    /** Ruinous Grace: "Prayer points are drained in place of run energy whilst running" (so long as any are left). */
    public static boolean ruinousGrace(final Player player) {
        final PrayerManager manager = player.getPrayerManager();
        return manager != null && manager.isActive(Prayer.RUINOUS_GRACE) && manager.getPrayerPoints() > 0;
    }

    /**
     * Metabolise: once active for 15 seconds, the attack delay eating adds is one tick shorter (a 3-tick food 2, a
     * karambwan 1, a karambwan combo 5 -> 3).
     */
    public static int adjustFoodDelay(final Player player, final int delay) {
        final PrayerManager manager = player.getPrayerManager();
        if (delay <= 0 || manager == null || !manager.isActive(Prayer.RUINOUS_METABOLISE)) {
            return delay;
        }
        final Object start = player.getTemporaryAttributes().get(METABOLISE_START);
        if (!(start instanceof Number) || WorldThread.getCurrentCycle() - ((Number) start).longValue() < METABOLISE_WARMUP_TICKS) {
            return delay;
        }
        return delay - 1;
    }

    /** "Change Prayerbook" on the Ruinous powers item, in the inventory or worn (it goes in the shield slot). */
    public static final class BookItem extends ItemPlugin {
        @Override
        public void handle() {
            bind("Change Prayerbook", (player, item, slotId) -> toggleBook(player));
        }

        @Override
        public int[] getItems() {
            return new int[] {PrayerItemIds.RUINOUS_POWERS};
        }
    }
}
