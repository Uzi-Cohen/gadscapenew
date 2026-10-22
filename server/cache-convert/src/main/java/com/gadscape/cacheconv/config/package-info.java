/**
 * The configs area: archive 2 (every config group except seq, which the anim area owns, and the interface side of
 * things, which the UI area owns), built as BASE plus the user's own content (spec policies 1-6, rules 10 and 11).
 *
 * <h2>Steps, in pipeline order</h2>
 * <ol>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigPlanStep} ({@code configs-plan}): decides every config relocation
 *       and records it in the id map, writing nothing. Run it early: after {@code models}, before the anim, UI and
 *       maps steps, so they rewrite their references (hand objs, clientscript operands and constants, IF3 var
 *       triggers, map files, the server) to the moved ids. Maps moves locs with
 *       {@link com.gadscape.cacheconv.config.LocWriter#plan} before {@code configs}.</li>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigStep} ({@code configs}): writes the groups. Run it after every
 *       step that moves ids configs refer to (models, anim seqs, UI sprites/interfaces, maps locs).</li>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigFixesStep} ({@code config-fixes}): the data-driven fixes of GadScape
 *       configs ({@code config/fixes.tsv}, opcode edits through {@link com.gadscape.cacheconv.config.ConfigEdit}), after
 *       {@code configs}; configs-plan claims the ids of its new entries and applies {@code config/relocate.tsv} (forced
 *       relocations).</li>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigOracle} ({@code configs-oracle}): every non-BASE entry through the
 *       server's strict 241 decoders.</li>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigRefCheck} ({@code configs-refcheck}): every reference of every
 *       non-BASE entry resolves; best run last, after all writers.</li>
 *   <li>{@link com.gadscape.cacheconv.config.ConfigExpect} ({@code configs-expect}): the audit's expectations and the
 *       relocation invariants on the real inputs.</li>
 * </ol>
 *
 * <h2>Policy as implemented</h2>
 * <ul>
 *   <li>Vanilla (USER == VAN211): BASE's entry. A vanilla entry BASE no longer has is ported at its id (server code
 *       and 211 content may still look it up).</li>
 *   <li>Same thing ({@link com.gadscape.cacheconv.config.CfgModel#same}): identical, decoded equal, or same name
 *       (case, punctuation, word order ignored) and models/links, compared before and after mapping, so Jagex content
 *       the user imported at its Jagex id resolves to BASE's entry.</li>
 *   <li>Protected (moved to a reserved range, references rewritten): ids BASE added over OLD241 (objs 34721-34723,
 *       34726; the 46 raid structs, whose 211 copies move); user enums/structs/params/varbits/varps/varcs/dbrows at
 *       ids BASE uses; enums the user re-typed or replaced (708); GadScape varps/varcs that have no USER definition
 *       but are BASE's (server varps, script varps 4204/4205/4507, varp 4702 under varbits 17007/17008, varcs 1151,
 *       1155-1159, 1500).</li>
 *   <li>Other entity clashes (obj, npc, loc, spotanim, inv, healthbar) and vanilla entities the user repurposed (clue
 *       scrolls and puzzle pieces turned into GadScape items): the USER entry stays at the id, listed in report.json
 *       and {@code WORK/configs/clashes.tsv}.</li>
 *   <li>Vanilla entries the user edited: BASE's entry plus the user's edit ({@link com.gadscape.cacheconv.config.Merge3},
 *       enums {@link com.gadscape.cacheconv.config.EnumMerge}: BASE's pairs plus the user's added/changed keys, USER
 *       wins conflicts; the user's removals are applied and reported, {@code --configs.enumRemovals=keep} for a strict
 *       union). Listed in {@code WORK/configs/merged.tsv}.</li>
 *   <li>Variables (rule 11): BASE's varc/varp entries stay; plain entries fill varcs below 2048 and varps below 65536
 *       (the 241 client sizes both tables from the file count).</li>
 * </ul>
 *
 * <h2>Options</h2>
 * {@code --configs.range.KIND=LO-HI}, {@code --configs.serverVarps=LIST|none},
 * {@code --configs.portRemovedVanilla=false}, {@code --configs.enumRemovals=keep}, {@code --configs.varcSlots=N},
 * {@code --configs.varpSlots=N}, {@code --configs.lossy=false}.
 *
 * <h2>Running alone</h2>
 * {@code dev/run.sh config.ConfigsMain [--configs.with=models,anim] [--write --out DIR]} (a dry run by default),
 * {@code dev/run.sh config.ConfigSelfCheck} (codec round trips and merge properties on the real caches); unit tests in
 * {@code ConfigTest}.
 */
package com.gadscape.cacheconv.config;
