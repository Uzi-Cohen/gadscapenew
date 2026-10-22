/**
 * UI area: GadScape's own interfaces (archive 3) and clientscripts (archive 12) on build 241 (gadconv spec, policy 8).
 * Vanilla interfaces and scripts stay BASE's; the three-way merge of the user-modified vanilla ones is a separate area
 * (it can share {@link com.gadscape.cacheconv.ui.UiRemap} for the custom ids those reference).
 * <p>
 * Steps, in pipeline order:
 * <ol>
 *   <li>{@link com.gadscape.cacheconv.ui.UiRelocateStep} ("ui-relocate", early, right after check-ranges and before
 *       any step that allocates var ids or rewrites script/interface references): custom ids BASE already uses move to
 *       reserved ranges (scripts 47000-47999; interfaces 5200-5299, which Kind.INTERFACE lacks in core); var ids the
 *       custom UI uses undefined inside a reserved range (varp 9998) are reserved;</li>
 *   <li>{@link com.gadscape.cacheconv.ui.UiConvertStep} ("ui-convert", after the steps that relocate sprites, models,
 *       seqs and configs, and after the UI merge): rewrites every reference in the 211 bytes, converts with the ported
 *       CsAll rules (convert.ScriptConv / InterfaceConv) and writes the custom scripts, the vanilla 211 scripts that
 *       had to be ported, the signature adapters (for create_graphic 98, scrollbar_horizontal 6156, 2064 and 4218; ids allocated at the top of the reserved script range, idmap.json _new)
 *       and the custom interfaces; USER sprites/fonts the custom UI needs that no earlier step added are copied;</li>
 *   <li>{@link com.gadscape.cacheconv.ui.UiFixesStep} ("ui-fixes", after ui-convert): QA fixes of GadScape's UI as data
 *       ({@code ui/fixes.tsv}), script recipes located by anchors, and new scripts the server calls.</li>
 *   <li>{@link com.gadscape.cacheconv.ui.UiCheckStep} ("ui-check", read-only, at the end): read-back, gosub argument
 *       counts, hook targets and assets of everything ui-convert wrote.</li>
 * </ol>
 * Reference typing comes from {@link com.gadscape.cacheconv.ui.CsSim}, a stack simulator over the 211 command table
 * generated from RuneStar cs2 (resources ui/cs2-commands-211*.tsv); {@link com.gadscape.cacheconv.ui.IfRefs} walks
 * components; {@link com.gadscape.cacheconv.ui.UiRemap} holds the id mapping rules (shared with the UI merge);
 * {@link com.gadscape.cacheconv.ui.UiScope} says what is converted (all custom UI, or a port-interface selection);
 * {@link com.gadscape.cacheconv.ui.UiAdapters} builds adapters. Data in resources/ui: script-signatures.tsv
 * (adapters, with argument maps), custom-fixups.tsv (known wrong interface ids), interface-remap-211-241.tsv (copy of
 * server/docs/rev241/interface-remap.tsv; a ui-merge/component-remap.tsv with the same columns takes precedence, and
 * its component241 column may be "IFACE:COMP"). Vanilla interfaces the table does not cover keep their component ids
 * when the component is identical or the same shape in 241.
 * {@link com.gadscape.cacheconv.ui.UiMain} runs the three steps standalone, {@link com.gadscape.cacheconv.ui.UiPort}
 * is {@code gadconv port-interface}, {@link com.gadscape.cacheconv.ui.UiSelfCheck} the synthetic and golden checks.
 */
package com.gadscape.cacheconv.ui;
