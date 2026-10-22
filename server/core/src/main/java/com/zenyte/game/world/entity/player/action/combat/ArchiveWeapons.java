package com.zenyte.game.world.entity.player.action.combat;

/**
 * Ids of The Fractured Archive (OSRS raid 4, 20 Oct 2026) weapons and the client data that drives them.
 * <p>
 * Every id here is a live (stock) id from the openrs2 2746 cache, which the GadScape 241 cache is built on, so these
 * never move. GadScape's own copies of these items (recolours, the old duplicates) are in {@link GadScapeWeaponIds}.
 */
public final class ArchiveWeapons {

    private ArchiveWeapons() {
    }

    /* Items (obj ids, 2746). */
    public static final int ASCENSION_SHARDS = 34669;
    public static final int ASCENSION_CROSSBOWS = 34681;
    public static final int ASCENSION_BOLTS = 34684;
    public static final int DIAMOND_ASCENSION_BOLTS = 34690;
    public static final int DIAMOND_ASCENSION_BOLTS_E = 34696;
    public static final int ONYX_ASCENSION_BOLTS = 34702;
    public static final int ONYX_ASCENSION_BOLTS_E = 34708;
    /** Right-hand model of the second crossbow, used by the fire animations (seq righthand obj). */
    public static final int ASCENSION_CROSSBOW_DUMMY = 34714;
    public static final int OBLIGATOR = 34715;
    public static final int OBLIGATOR_UNCHARGED = 34717;
    public static final int RONDACHE = 34719;
    public static final int ZORYAS_TOME = 34725;
    public static final int ZORYAS_TOME_UNCHARGED = 34727;

    /* Player animations (seq ids, 2746). */
    public static final int RONDACHE_SPECIAL_ANIM = 15108;
    public static final int CROSSBOWS_FIRE_LEFT = 15113;
    public static final int CROSSBOWS_FIRE_LEFT_PVN = 15114;
    public static final int CROSSBOWS_FIRE_RIGHT = 15115;
    public static final int CROSSBOWS_FIRE_RIGHT_PVN = 15116;
    public static final int OBLIGATOR_ATTACK = 15122;
    public static final int OBLIGATOR_MULTIHIT = 15123;
    public static final int OBLIGATOR_ATTACK_FEMALE = 15130;
    public static final int OBLIGATOR_MULTIHIT_FEMALE = 15131;
    public static final int ZORYAS_TOME_ATTACK = 15139;
    public static final int ZORYAS_TOME_SPECIAL = 15140;
    public static final int ZORYAS_TOME_ATTACK_FEMALE = 15146;
    public static final int ZORYAS_TOME_SPECIAL_FEMALE = 15147;

    /* Spot animations (2746). */
    public static final int RONDACHE_SPECIAL_GFX = 4422;
    public static final int OBLIGATOR_CHARGED_THRUST_GFX = 4423;
    public static final int OBLIGATOR_UNCHARGED_THRUST_GFX = 4424;
    public static final int OBLIGATOR_CHARGED_THRUST_GFX_FEMALE = 4425;
    public static final int OBLIGATOR_UNCHARGED_THRUST_GFX_FEMALE = 4426;
    public static final int ZORYAS_TOME_PROJECTILE = 4427;
    public static final int ZORYAS_TOME_SPECIAL_PROJECTILE = 4428;
    public static final int ZORYAS_TOME_IMPACT = 4429;
    public static final int ZORYAS_TOME_SPECIAL_IMPACT = 4430;
    public static final int ZORYAS_TOME_FOLLOW_UP_IMPACT = 4431;
    public static final int ZORYAS_TOME_SPECIAL_CAST = 4432;
    public static final int ZORYAS_TOME_CAST = 4433;
    public static final int ZORYAS_TOME_SPECIAL_CAST_FEMALE = 4434;
    public static final int ZORYAS_TOME_CAST_FEMALE = 4435;
    public static final int ASCENSION_BOLT_PROJECTILE = 4437;
    public static final int ONYX_ASCENSION_BOLT_PROJECTILE = 4438;
    public static final int DIAMOND_ASCENSION_BOLT_PROJECTILE = 4439;

    /* Client vars (2746). */
    /** varp 300 sa_energy, varp 301 sa_attack: 0 off, 1 main-hand (weapon) special, 2 off-hand (Rondache) special. */
    public static final int SA_ATTACK_VARP = 301;
    /** varbit rondache_charges (in varp 5925 tfa_rewards_1), 0-20; the client draws the Rondache bar from it. */
    public static final int RONDACHE_CHARGES_VARBIT = 16438;
    /** varp zoryas_tome_stacks: the empowered attacks left after Zorya's flow. */
    public static final int ZORYAS_TOME_STACKS_VARP = 5926;
    /** varbit charges_obligator_quantity. */
    public static final int OBLIGATOR_CHARGES_VARBIT = 16323;
    /** varbit charges_zoryas_tome_quantity. */
    public static final int ZORYAS_TOME_CHARGES_VARBIT = 16324;
    /** varp soulreaper_stacks (stock 241): the client draws the Soulreaper axe bar and orb from it. */
    public static final int SOULREAPER_STACKS_VARP = 3784;

    /* Combat-tab weapon categories (varbit 357 combat_weapon_category, dbtable combat_interface_weapon_category). */
    public static final int CATEGORY_EXTENDING_CHAIN_MACE = 36;
    public static final int CATEGORY_BOOK_SELFPOWERING = 37;

    public static boolean isObligator(final int id) {
        return id == OBLIGATOR || id == OBLIGATOR_UNCHARGED || GadScapeWeaponIds.isGadScapeObligator(id);
    }

    public static boolean isZoryasTome(final int id) {
        return id == ZORYAS_TOME || id == ZORYAS_TOME_UNCHARGED;
    }

    public static boolean isAscensionCrossbows(final int id) {
        return id == ASCENSION_CROSSBOWS || id == GadScapeWeaponIds.ASCENSION_CROSSBOWS_GADSCAPE;
    }
}
