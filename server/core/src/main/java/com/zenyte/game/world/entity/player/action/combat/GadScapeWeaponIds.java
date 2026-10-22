package com.zenyte.game.world.entity.player.action.combat;

import com.gadscape.game.rev241.Gad211;

/**
 * GadScape-only weapon item ids (user content of the GadScape cache) that the weapon code needs and that have no
 * constant in {@link com.gadscape.game.item.CustomItemId}.
 * <p>
 * Several of these sit on ids that the 2746 base cache also uses (see plan/gadconv-spec.md, "Clashes"); the ones the
 * cache converter moved resolve through {@link com.gadscape.game.rev241.Gad211} (its idmap.json), so they follow the
 * cache without code changes. They are therefore not compile-time constants (no {@code case} labels).
 */
public final class GadScapeWeaponIds {

    private GadScapeWeaponIds() {
    }

    /** "Abyssal whip (or)" copies (live 33338 is the Oathplate slayer helmet). */
    public static final int ABYSSAL_WHIP_OR_33338 = 33338;
    public static final int ABYSSAL_WHIP_OR_33346 = 33346;
    public static final int ABYSSAL_WHIP_OR_33372 = 33372;
    public static final int ABYSSAL_WHIP_OR_33380 = 33380;
    /** "Voidwaker (Deadman)" (two copies). */
    public static final int VOIDWAKER_DEADMAN = 33339;
    public static final int VOIDWAKER_DEADMAN_33373 = 33373;
    /** "Lite whip" (two copies). */
    public static final int LITE_WHIP_33258 = 33258;
    public static final int LITE_WHIP_33363 = 33363;
    /** Second "Soulreaper axe (o)" (live 33369 is a league scroll cert). */
    public static final int SOULREAPER_AXE_OR_33369 = 33369;

    /** GadScape's copy of the Ascension crossbows (live 34721 is the Rondache placeholder). */
    public static final int ASCENSION_CROSSBOWS_GADSCAPE = Gad211.obj(34721);
    /** GadScape's recoloured Obligator (live 34723 is the Archivist's folio). */
    public static final int OBLIGATOR_RECOLOUR = Gad211.obj(34723);
    /** "Gad obligator" (live 34726 is the Zorya's tome placeholder). */
    public static final int GAD_OBLIGATOR = Gad211.obj(34726);

    /** GadScape "Blood Moon chestplate" copies (live 33340 is the Radiant slayer helmet); used by combat.moon.MoonSets. */
    public static final int BLOOD_MOON_CHESTPLATE_33340 = 33340;
    public static final int BLOOD_MOON_CHESTPLATE_33374 = 33374;
    /** GadScape "Eclipse atlatl" copies (live 33342 is the Demonic quill); used by combat.moon.MoonSets. */
    public static final int ECLIPSE_ATLATL_33342 = 33342;
    public static final int ECLIPSE_ATLATL_33376 = 33376;

    /** GadScape's Obligator copies: same mechanics as the Obligator, but they have no charges (always charged). */
    public static boolean isGadScapeObligator(final int id) {
        return id == OBLIGATOR_RECOLOUR || id == GAD_OBLIGATOR;
    }
}
