package com.gadscape.cacheconv.sprite;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;

/**
 * How other areas rewrite sprite references after {@link SpritesStep} ran (interface type-5 graphics and IF1
 * graphics, script constants typed graphic, enum/param/struct values typed graphic, obj/npc head icons,
 * healthbars, hitsplats). Only references held by USER content are rewritten: BASE content keeps BASE's ids, and the
 * USER sprites that stayed at their id (user-added at a free id, merged or user-edited vanilla sprites) need nothing.
 * <pre>
 *   int to = SpriteRefs.map(ctx.ids, spriteId);   // same as ctx.ids.map(Kind.SPRITE, id), -1 and other negatives kept
 * </pre>
 */
public final class SpriteRefs {
    private SpriteRefs() {}

    /** The id a USER sprite reference must use in the output (negative values, "no sprite", are kept). */
    public static int map(IdMap ids, int sprite) { return sprite < 0 ? sprite : ids.map(Kind.SPRITE, sprite); }

    public static int map(Ctx ctx, int sprite) { return map(ctx.ids, sprite); }

    /** Whether USER references to this sprite id must change. */
    public static boolean moved(IdMap ids, int sprite) { return sprite >= 0 && ids.moved(Kind.SPRITE, sprite); }
}
