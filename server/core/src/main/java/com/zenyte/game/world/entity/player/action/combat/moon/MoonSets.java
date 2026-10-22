package com.zenyte.game.world.entity.player.action.combat.moon;

import com.google.common.eventbus.Subscribe;
import com.zenyte.game.item.ItemId;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.combat.AmmunitionDefinitions;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat;
import com.zenyte.game.world.entity.player.action.combat.PlayerCombat.IPlayerCombatAttackHook;
import com.zenyte.game.world.entity.player.container.impl.equipment.EquipmentSlot;
import com.zenyte.plugins.events.ServerLaunchEvent;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Perilous Moons (Varlamore) armour sets and weapons: item ids, set checks and the combat dispatch of the three Moon
 * weapons.
 * <ul>
 *     <li>Blood Moon + Dual macuahuitl: two hits per attack, Bloodrager, special Blood Infusion ({@link DualMacuahuitlCombat}).</li>
 *     <li>Eclipse Moon + Eclipse atlatl: strength-based ranged weapon, burn set effect, special Eclipse
 *     ({@link EclipseAtlatlCombat}, {@link EclipseBurn}).</li>
 *     <li>Blue Moon + Blue Moon spear: Frostweaver free melee attacks after binding spells, special Break Shackles
 *     ({@link BlueMoonSpear}).</li>
 * </ul>
 * Each set counts its normal, in-use ("Check"), (broken) and arena copies (OSRS: the set effects still work when a piece
 * is broken).
 */
public final class MoonSets {

    private MoonSets() {
    }

    /*
     * GadScape's own copies (user cache): ids the cache converter may move (idmap); rewrite them here if it does.
     */
    /** GadScape "Blood Moon chestplate" copies. */
    public static final int BLOOD_MOON_CHESTPLATE_GADSCAPE = 33340;
    public static final int BLOOD_MOON_CHESTPLATE_GADSCAPE_33374 = 33374;
    /** GadScape "Eclipse atlatl" copies. */
    public static final int ECLIPSE_ATLATL_GADSCAPE = 33342;
    public static final int ECLIPSE_ATLATL_GADSCAPE_33376 = 33376;

    private static final IntSet BLOOD_HELMS = set(ItemId.BLOOD_MOON_HELM, ItemId.BLOOD_MOON_HELM_29047, ItemId.BLOOD_MOON_HELM_29848, ItemId.BLOOD_MOON_HELM_BROKEN);
    private static final IntSet BLOOD_CHESTS = set(ItemId.BLOOD_MOON_CHESTPLATE, ItemId.BLOOD_MOON_CHESTPLATE_29043, ItemId.BLOOD_MOON_CHESTPLATE_29846, ItemId.BLOOD_MOON_CHESTPLATE_BROKEN,
            BLOOD_MOON_CHESTPLATE_GADSCAPE, BLOOD_MOON_CHESTPLATE_GADSCAPE_33374);
    private static final IntSet BLOOD_LEGS = set(ItemId.BLOOD_MOON_TASSETS, ItemId.BLOOD_MOON_TASSETS_29045, ItemId.BLOOD_MOON_TASSETS_29847, ItemId.BLOOD_MOON_TASSETS_BROKEN);

    private static final IntSet ECLIPSE_HELMS = set(ItemId.ECLIPSE_MOON_HELM, ItemId.ECLIPSE_MOON_HELM_29035, ItemId.ECLIPSE_MOON_HELM_29842, ItemId.ECLIPSE_MOON_HELM_BROKEN);
    private static final IntSet ECLIPSE_CHESTS = set(ItemId.ECLIPSE_MOON_CHESTPLATE, ItemId.ECLIPSE_MOON_CHESTPLATE_29031, ItemId.ECLIPSE_MOON_CHESTPLATE_29840, ItemId.ECLIPSE_MOON_CHESTPLATE_BROKEN);
    private static final IntSet ECLIPSE_LEGS = set(ItemId.ECLIPSE_MOON_TASSETS, ItemId.ECLIPSE_MOON_TASSETS_29033, ItemId.ECLIPSE_MOON_TASSETS_29841, ItemId.ECLIPSE_MOON_TASSETS_BROKEN);

    private static final IntSet BLUE_HELMS = set(ItemId.BLUE_MOON_HELM, ItemId.BLUE_MOON_HELM_29041, ItemId.BLUE_MOON_HELM_29845, ItemId.BLUE_MOON_HELM_BROKEN);
    private static final IntSet BLUE_CHESTS = set(ItemId.BLUE_MOON_CHESTPLATE, ItemId.BLUE_MOON_CHESTPLATE_29037, ItemId.BLUE_MOON_CHESTPLATE_29843, ItemId.BLUE_MOON_CHESTPLATE_BROKEN);
    private static final IntSet BLUE_LEGS = set(ItemId.BLUE_MOON_TASSETS, ItemId.BLUE_MOON_TASSETS_29039, ItemId.BLUE_MOON_TASSETS_29844, ItemId.BLUE_MOON_TASSETS_BROKEN);

    private static final IntSet DUAL_MACUAHUITLS = set(ItemId.DUAL_MACUAHUITL, ItemId.DUAL_MACUAHUITL_29850);
    private static final IntSet ECLIPSE_ATLATLS = set(ItemId.ECLIPSE_ATLATL, ItemId.ECLIPSE_ATLATL_29851, ECLIPSE_ATLATL_GADSCAPE, ECLIPSE_ATLATL_GADSCAPE_33376);
    private static final IntSet BLUE_MOON_SPEARS = set(ItemId.BLUE_MOON_SPEAR, ItemId.BLUE_MOON_SPEAR_29849);

    /** All Eclipse atlatl ids (the weapons atlatl darts can be thrown with). */
    public static final int[] ECLIPSE_ATLATL_IDS = ECLIPSE_ATLATLS.toIntArray();
    public static final int[] DUAL_MACUAHUITL_IDS = DUAL_MACUAHUITLS.toIntArray();
    public static final int[] BLUE_MOON_SPEAR_IDS = BLUE_MOON_SPEARS.toIntArray();

    private static IntSet set(final int... ids) {
        return new IntOpenHashSet(ids);
    }

    private static boolean wears(final Player player, final IntSet helms, final IntSet chests, final IntSet legs) {
        return helms.contains(player.getEquipment().getId(EquipmentSlot.HELMET))
                && chests.contains(player.getEquipment().getId(EquipmentSlot.PLATE))
                && legs.contains(player.getEquipment().getId(EquipmentSlot.LEGS));
    }

    /** Blood Moon helm, chestplate and tassets (the weapon is checked separately). */
    public static boolean hasFullBloodMoon(final Player player) {
        return wears(player, BLOOD_HELMS, BLOOD_CHESTS, BLOOD_LEGS);
    }

    public static boolean hasFullEclipseMoon(final Player player) {
        return wears(player, ECLIPSE_HELMS, ECLIPSE_CHESTS, ECLIPSE_LEGS);
    }

    public static boolean hasFullBlueMoon(final Player player) {
        return wears(player, BLUE_HELMS, BLUE_CHESTS, BLUE_LEGS);
    }

    public static boolean isDualMacuahuitl(final int id) {
        return DUAL_MACUAHUITLS.contains(id);
    }

    public static boolean isEclipseAtlatl(final int id) {
        return ECLIPSE_ATLATLS.contains(id);
    }

    public static boolean isBlueMoonSpear(final int id) {
        return BLUE_MOON_SPEARS.contains(id);
    }

    /**
     * Starts the Moon weapons' own combat: the generic dispatch in {@link PlayerCombat#attackEntity} would treat the
     * macuahuitl as a one-hit melee weapon and the atlatl ("atlatl" is no ranged weapon name) as a melee weapon.
     */
    private static final IPlayerCombatAttackHook ATTACK_HOOK = (player, entity, spell) -> {
        if (spell != null || player.getCombatDefinitions().getAutocastSpell() != null) {
            return IPlayerCombatAttackHook.Result.Pass;
        }
        final int weaponId = player.getEquipment().getId(EquipmentSlot.WEAPON);
        if (isDualMacuahuitl(weaponId)) {
            player.getActionManager().setAction(new DualMacuahuitlCombat(entity));
            return IPlayerCombatAttackHook.Result.Return;
        }
        if (isEclipseAtlatl(weaponId)) {
            player.getActionManager().setAction(new EclipseAtlatlCombat(entity,
                    AmmunitionDefinitions.getDefinitions(player.getEquipment().getId(EquipmentSlot.AMMUNITION))));
            return IPlayerCombatAttackHook.Result.Return;
        }
        if (isBlueMoonSpear(weaponId)) {
            player.getActionManager().setAction(new BlueMoonSpear.Combat(entity));
            return IPlayerCombatAttackHook.Result.Return;
        }
        return IPlayerCombatAttackHook.Result.Pass;
    };

    @Subscribe
    public static void onServerLaunch(final ServerLaunchEvent event) {
        PlayerCombat.registerAttackHook(ATTACK_HOOK);
    }
}
