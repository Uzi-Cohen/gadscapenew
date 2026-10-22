/**
 * UI merge area (gadconv spec, policy 8): GadScape's edits of vanilla interfaces and clientscripts re-applied onto
 * build 241 (BASE, openrs2 2746), and the 211 -> 241 component remap the server switches to.
 * <ul>
 *   <li>{@link com.gadscape.cacheconv.uimerge.UiMergeStep} ("ui-merge"): the pipeline step. Writes the merged groups
 *       stored in resources/ui-merge (manifest.tsv, interfaces/ID.if, scripts/ID.cs2), refusing entries whose BASE
 *       group changed, and maps GadScape's references through the id map. Run it after ui-relocate and the steps that
 *       relocate sprites, models, seqs, vars and configs; before ui-convert and the reference checks.</li>
 *   <li>{@link com.gadscape.cacheconv.uimerge.UiMergeGen}: regenerates that data from the caches and the hand-made
 *       decisions in {@link com.gadscape.cacheconv.uimerge.UiMergeSpecs} (interfaces) and
 *       {@link com.gadscape.cacheconv.uimerge.ScriptRecipes} (scripts), plus component-remap.tsv and NOTES.md.</li>
 *   <li>Engines: {@link com.gadscape.cacheconv.uimerge.IfComp} (IF3 field codec), {@link com.gadscape.cacheconv.uimerge.IfMerge}
 *       (field-level three-way merge), {@link com.gadscape.cacheconv.uimerge.CompRemap} (docs table + names + content
 *       alignment), {@link com.gadscape.cacheconv.uimerge.SpellAnchors} (218 by spell objs),
 *       {@link com.gadscape.cacheconv.uimerge.CsText} (script text form), {@link com.gadscape.cacheconv.uimerge.ScriptEdit}
 *       (anchored script edits), {@link com.gadscape.cacheconv.uimerge.UserCode} (GadScape code typed with the UI area's
 *       stack simulator), {@link com.gadscape.cacheconv.uimerge.RefKinds} (id kinds and their build-time mapping).</li>
 *   <li>{@link com.gadscape.cacheconv.uimerge.UiMergeMain}: gen / check / dry / write / show;
 *       {@link com.gadscape.cacheconv.uimerge.UiMergeSelfCheck}: synthetic and golden checks.
 *       Explore, ExploreCs and ExploreRefs are dev tools (side-by-side dumps, instruction diffs, reference search).</li>
 * </ul>
 */
package com.gadscape.cacheconv.uimerge;
