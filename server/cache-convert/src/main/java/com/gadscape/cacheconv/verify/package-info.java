/**
 * Verify area of gadconv: checks a build-241 cache (an output being built, or any cache directory) and reports every
 * problem as a {@link com.gadscape.cacheconv.verify.Findings finding}. The walkers here are independent of the
 * converters they check.
 * <ul>
 *   <li>{@link com.gadscape.cacheconv.verify.Verifier}: the reusable entry point (any step or the integrator can run it
 *       on {@code ctx.out}); checks {@code refs}, {@code anims}, {@code interfaces}, {@code maps}, {@code scripts},
 *       {@code brand}, {@code oracle}, {@code strict}, {@code determinism}. With a baseline (BASE), problems BASE has
 *       too are counted as stock, not as errors.</li>
 *   <li>{@link com.gadscape.cacheconv.verify.VerifyStep} ("verify"): the pipeline step (run it last); report errors
 *       make build-cacheu exit 1. {@link com.gadscape.cacheconv.verify.VerifyMain}: {@code gadconv verify} and the
 *       standalone main.</li>
 *   <li>RefCheck: {@link com.gadscape.cacheconv.verify.CfgRefs} (241 config walker: obj/npc/loc/spotanim/idkit
 *       models and seqs, seq frames/keyframes/hand objs, transforms, params and typed values, enums, structs, dbrows,
 *       inventories, varbits, healthbars), {@link com.gadscape.cacheconv.verify.IfWalk} (IF3/IF1 components: sprites,
 *       models, seqs, fonts, parents, hook scripts and their arguments), map loc ids, frames -> framemaps,
 *       keyframes -> skeletal framemaps ({@link com.gadscape.cacheconv.verify.RefCheck}).</li>
 *   <li>Scripts: {@link com.gadscape.cacheconv.verify.CsStack} is a static stack analysis of 241 clientscripts (int,
 *       object and long stacks, typed arrays, hook descriptors, enum/param/db typed commands);
 *       {@link com.gadscape.cacheconv.verify.CsWorld} runs it over a whole cache, computing return counts bottom-up;
 *       {@link com.gadscape.cacheconv.verify.ScriptCheck} turns it into findings: gosub targets, arg counts at every
 *       call (caller pushes fewer = {@code gosub-underflow}, more to a void callee = {@code gosub-leftover}),
 *       signatures that differ from BASE at calls from stock scripts, hook setters, constant refs. The command table
 *       {@code verify/cs2-241-stack.tsv} was derived from the stock 241 cache by
 *       {@link com.gadscape.cacheconv.verify.CsLearn} (seeded with the 211 RuneStar table and
 *       {@code verify/cs2-241-manual.tsv}); every stock script of 2746 analyses without a violation.</li>
 *   <li>{@link com.gadscape.cacheconv.verify.ClientOracle}: the cdec oracles (real 241 client decoders for configs,
 *       models, components and scripts); {@link com.gadscape.cacheconv.verify.StrictChecker}: the server's
 *       {@code CacheVerifier} in a separate JVM (asserting it leaves the cache untouched);
 *       {@link com.gadscape.cacheconv.verify.BrandScan}: Near-Reality / Roat Pkz / OSNR / RuneSpawn strings;
 *       {@link com.gadscape.cacheconv.verify.Determinism}: group-by-group comparison of two outputs.</li>
 *   <li>Dev tools: {@link com.gadscape.cacheconv.verify.CsDump} (script listing with stack depths),
 *       {@link com.gadscape.cacheconv.verify.CsCheckBase} (stack analysis of a whole cache),
 *       {@link com.gadscape.cacheconv.verify.VerifySelfCheck} (self-checks; {@code --full} adds the BASE golden checks).</li>
 * </ul>
 */
package com.gadscape.cacheconv.verify;
