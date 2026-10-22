/**
 * The maps area: archive 5 (241 map squares: group = region id x << 8 | y; file 0 terrain, 1 locs, 2-4 environment),
 * spec rule 9 and audit MAP-1 ... MAP-6, MAP-10, TELE-08.
 *
 * <h2>Steps, in pipeline order</h2>
 * <ol>
 *   <li>{@link com.gadscape.cacheconv.map.MapLocPlanStep} ({@code maps-plan}): records in the id map the USER loc
 *       definitions that must move because BASE's own squares place their ids (MAP-6: the GadScape Portal and the
 *       other customs on 35000-35023, the Christmas customs over rev-225 objects 50000-50226, 55000, 55070-55071,
 *       60001, 60023-60024, ...), using {@link com.gadscape.cacheconv.config.LocWriter#plan}. Writes nothing. Must run
 *       before {@code configs} (which writes each moved loc at its new id and keeps BASE's at the old one) and before
 *       {@code maps}. Loc range: 64500-65534 (loc ids are u16 in 241's OPLOC and LOC_ADD_CHANGE packets and in
 *       multiloc transforms; the spec's 70000-79999 cannot hold a clickable loc), {@code --maps.locRange=LO-HI}.</li>
 *   <li>{@link com.gadscape.cacheconv.map.MapsStep} ({@code maps}): writes the squares of the stored allow-list onto
 *       BASE's archive 5: custom squares merged tile by tile with BASE ({@link com.gadscape.cacheconv.map.SquareMerge})
 *       or replacing it when no start is known, new custom squares and vanilla squares 241 removed with the
 *       environment of a copy source or the nearest BASE square ({@link com.gadscape.cacheconv.map.Env}), loc ids
 *       through the id map. {@code --maps.mode=overlay} replaces instead of merging.</li>
 * </ol>
 *
 * <h2>Classification (offline, stored as data)</h2>
 * {@link com.gadscape.cacheconv.map.Classifier} decides per square whether GadScape changed it by comparing the user's
 * square with the square it started from: rev 225's (GadScape's DataMigration ported every square with regionX &le; 99)
 * or vanilla 211's (decrypted with the public OpenRS2 keys; key-free {@link com.gadscape.cacheconv.map.TailProbe}
 * otherwise). Evidence that is not a build input (the rev-225 cache, the 211 keys; see
 * {@link com.gadscape.cacheconv.map.Evidence}) is only needed to regenerate the allow-list:
 * <pre>
 *   dev/run.sh map.MapsMain classify --maps.writeResources=src/main/resources/map
 * </pre>
 * which writes resources {@code map/squares.tsv}, {@code merge-masks.tsv}, {@code vanilla-placements.tsv},
 * {@code loc-225-imports.txt} and {@code restore/} ({@link com.gadscape.cacheconv.map.AllowList}). The build refuses a
 * stored decision for a user square whose bytes changed since (CRC), so a new user cache needs a new classification.
 *
 * <h2>Other entry points</h2>
 * <ul>
 *   <li>{@code dev/run.sh map.MapsMain dry-run | run --maps.out=DIR}: the two steps alone (report + id map in
 *       WORK/maps/dry-run or run/).</li>
 *   <li>{@code dev/run.sh map.MapsMain instances}: squares the server copies into instances
 *       ({@link com.gadscape.cacheconv.map.InstanceScan}) and whether the user's differ from BASE's.</li>
 *   <li>{@code dev/run.sh map.MapsMain square R ...}: what every cache holds for a square.</li>
 *   <li>{@code dev/run.sh map.MapsMain keysearch}: brute-force known-valid keys for unreadable loc groups.</li>
 *   <li>{@link com.gadscape.cacheconv.map.PortMap} ({@code gadconv port-map}): port squares from any 211-layout cache
 *       or raw (pre-209) square files into a copy of a 241 cache.</li>
 *   <li>{@code dev/run.sh map.MapSelfCheck}: codec round trips on every BASE/USER square, key search, tail probe,
 *       golden check of the unmerged squares against the hand-built cacheU. Unit tests: {@code MapTest}.</li>
 * </ul>
 */
package com.gadscape.cacheconv.map;
