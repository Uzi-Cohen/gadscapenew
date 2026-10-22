package com.zenyte.game.world.entity.player.action.combat;

/**
 * GadScape-only weapon item ids (user content of the GadScape cache) that the weapon code needs and that have no
 * constant in {@link com.gadscape.game.item.CustomItemId}.
 * <p>
 * Several of these sit on ids that the 2746 base cache also uses (see plan/gadconv-spec.md, "Clashes"), so the cache
 * converter may move them: when it does, rewrite these constants from its idmap.json and nothing else changes.
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
    public static final int ASCENSION_CROSSBOWS_GADSCAPE = 34721;
    /** GadScape's recoloured Obligator (live 34723 is the Archivist's folio). */
    public static final int OBLIGATOR_RECOLOUR = 34723;
    /** "Gad obligator" (live 34726 is the Zorya's tome placeholder). */
    public static final int GAD_OBLIGATOR = 34726;

    /** GadScape's Obligator copies: same mechanics as the Obligator, but they have no charges (always charged). */
    public static boolean isGadScapeObligator(final int id) {
        return id == OBLIGATOR_RECOLOUR || id == GAD_OBLIGATOR;
    }
}
