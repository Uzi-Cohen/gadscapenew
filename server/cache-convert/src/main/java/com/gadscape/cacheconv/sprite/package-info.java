/**
 * Sprites (archive 8) and fonts (archive 13): BASE first, the user's own and edited art layered on top (spec policies
 * 1-3; audit CRIT-13, B11, MAP-11, PVP-03).
 *
 * <h2>Steps, in pipeline order</h2>
 * <ol>
 *   <li>{@link com.gadscape.cacheconv.sprite.FontsStep} ({@code fonts}) then
 *       {@link com.gadscape.cacheconv.sprite.SpritesStep} ({@code sprites}): early, with the other steps that move
 *       ids (after {@code check-ranges} / {@code ui-relocate}), and before {@code configs}, the UI merge and
 *       {@code ui-convert}, which rewrite sprite references through the id map
 *       ({@link com.gadscape.cacheconv.sprite.SpriteRefs}).</li>
 *   <li>{@link com.gadscape.cacheconv.sprite.AssetFixesStep} ({@code asset-fixes}): redrawn sprite frames
 *       ({@code sprite/replace.tsv}) and the user-changed binary archive groups (title screen).</li>
 *   <li>{@link com.gadscape.cacheconv.sprite.MapsceneStep} ({@code mapscene}): after {@code configs} (it patches
 *       the user locs that step wrote, when it has to merge mapscene frames).</li>
 * </ol>
 *
 * <h2>Policy as implemented</h2>
 * <ul>
 *   <li>Vanilla sprites: BASE's. User-added at a free id: the user's. User-added at an id BASE uses: the same picture,
 *       a Jagex re-colour ({@link com.gadscape.cacheconv.sprite.SpriteSheet.FrameDiff#sameThing}) or a blank user
 *       placeholder resolve to BASE's sprite; anything else is GadScape art and moves to 20000-29999 under the name
 *       {@code gadscape_sprite_<211 id>}.</li>
 *   <li>User-edited vanilla sprites: frame-by-frame three-way merge
 *       ({@link com.gadscape.cacheconv.sprite.SpriteMerge}); frames keep their index, conflicts go to the user and are
 *       reported (spec rule 10). Merged sheets get a palette of exactly the colours their frames draw.</li>
 *   <li>Mapscene sheet: BASE's; GadScape frames would be appended after BASE's and only the user locs that use them
 *       re-pointed.</li>
 *   <li>Fonts: BASE's; user fonts (metrics + glyph sheet) at free ids are added; a font clash is an error (no font
 *       kind in the id map yet).</li>
 * </ul>
 *
 * <h2>Results on the real inputs</h2>
 * USER has 5,725 sprite groups: 4,677 vanilla (BASE's), 963 user-added, 85 user-edited vanilla. Of the user-added:
 * 791 byte-identical to BASE, 67 the same picture or a Jagex re-colour, 24 blank placeholders (all BASE's), 51 at free
 * ids (9000-9006, 10000-10044, added), and 30 real clashes moved to 20000-20029 (4806, 4815, 4830, 4839, 5245,
 * 5259-5265 = B11, 5273, 5499-5508, 5623-5625, 5628, 5634, 5636, 5638). Of the user-edited: 9 same picture as BASE,
 * 42 edits of sprites BASE did not change, 30 edits that win over a Jagex change (the GadScape logo 498, the re-lettered
 * spellbook teleport icons 27-105, 341-348, 410, 1752-1761), 3 three-way merges (423 chat icons: 41 USER frames, 29
 * BASE; 439 head icons: BASE's 35 frames with the user's revive icons 21-30 and frame 2; 2134: one user frame), and
 * the mapscene sheet, kept as BASE's: USER's 200-frame sheet is Jagex's (frames 0-190 re-coloured, 191-199 empty), so
 * no frame is GadScape's and nothing is merged. Fonts: all 16 USER fonts are vanilla (BASE has 21).
 * Configs and ui-convert follow the moves (B11: structs 12000-12005 param 10003 and script 32031's constant now name
 * 20005-20011).
 *
 * <h2>Running alone</h2>
 * {@code dev/run.sh sprite.SpritesMain [--sprites.with=configs] [--work DIR]} (a dry run; {@code --write --out DIR}
 * writes), {@code dev/run.sh sprite.SpriteSelfCheck} (codec round trip of every BASE/USER sprite and the step results
 * on the real caches); unit tests in {@code SpriteTest}. Each run writes {@code WORK/sprites-db/sprites.tsv}.
 * Options: {@code --sprites.sameThing=false}, {@code --mapscene.merge=auto|never}.
 */
package com.gadscape.cacheconv.sprite;
