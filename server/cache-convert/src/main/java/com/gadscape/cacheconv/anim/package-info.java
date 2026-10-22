/**
 * Animation area of gadconv: sequences (config 12), frame sets (archive 0), framemaps (archive 1) and skeletal keyframes
 * (archive 22).
 * <ul>
 *   <li>{@link com.gadscape.cacheconv.anim.AnimStep} ("anim"): layers the user's animations on BASE without overwriting
 *       a BASE group or record. Seqs the user never changed stay BASE's; user seqs that are the same animation as BASE's
 *       (skeletal on the same framemap, or the same frames) take BASE's record; the rest are ported, at their id or
 *       relocated to the reserved seq range when BASE uses the id. The frame sets, framemaps and 211 keyframes the ported
 *       seqs need are copied into the reserved ranges (keyframes into archive 22) with every reference rewritten.
 *       Must run before any step that rewrites seq references (npc/obj/loc/spotanim configs, enums, params, structs,
 *       interfaces, clientscripts): they map seq ids through {@code ctx.ids.map(Kind.SEQ, id)}.</li>
 *   <li>{@link com.gadscape.cacheconv.anim.AnimRefCheck} ("anim-refcheck"): proves every seq frame/keyframe/framemap ref
 *       of the output resolves, BASE's animation data is intact, every ported user seq plays the same frames as in USER,
 *       and the BOSS-X1 regression lists (549 skeletal seqs, 277 NPCs' stand/walk) resolve. Run it after the config
 *       steps so NPC/spotanim records of the final output are checked too.</li>
 *   <li>{@link com.gadscape.cacheconv.anim.AnimMain}: standalone dry run / scratch run / check-only of an output;
 *       {@link com.gadscape.cacheconv.anim.AnimSelfCheck}: codec self-checks.</li>
 * </ul>
 * Options: {@code --anim.portRemovedVanilla=false} (do not port vanilla 211 seqs BASE no longer has),
 * {@code --anim.takeBase=ids} (user seqs at BASE ids that should use BASE's record although they differ, e.g. the
 * classic Djinn seqs 11008-11020 that BASE has as skeletal), {@code --anim.forcePort=ids} (testing aid: port these user
 * seqs even when BASE has the same animation).
 */
package com.gadscape.cacheconv.anim;
