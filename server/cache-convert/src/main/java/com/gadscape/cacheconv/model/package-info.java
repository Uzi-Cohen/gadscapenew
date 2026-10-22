/**
 * Models area (archive 7): {@link com.gadscape.cacheconv.model.ModelsStep} layers the user's own models onto BASE and
 * moves the ones that clash; {@link com.gadscape.cacheconv.model.ModelRefs} is the helper the config and UI steps
 * call to rewrite model references; {@link com.gadscape.cacheconv.model.ModelRefCheckStep} checks the result.
 * {@link com.gadscape.cacheconv.model.ModelsMain} is a standalone dry run and
 * {@link com.gadscape.cacheconv.model.ModelSelfCheck} the self-check on the real caches.
 *
 * <h2>Model id width in build 241 (checked with the real 241 client, injected-client 1.13.3)</h2>
 * Hand-made entries carrying model id 100000 were decoded with the client's own decoder classes and the decoded
 * fields read back:
 * <table>
 *   <tr><th>structure</th><th>241 field</th><th>width</th><th>211 field (dropped by 241)</th></tr>
 *   <tr><td>obj config (10)</td><td>44 inventory, 45/48 wear 0 (+offset), 46/49 wear 1, 47/50 wear 2, 51-54 heads</td><td>4 bytes</td><td>1, 23-26, 78, 79, 90-93 (no payload in 241)</td></tr>
 *   <tr><td>npc config (9)</td><td>61 models, 62 chathead models</td><td>4 bytes</td><td>1, 60 (read, value dropped)</td></tr>
 *   <tr><td>loc config (6)</td><td>6 models + shape, 7 models</td><td>4 bytes</td><td>1, 5 (value dropped)</td></tr>
 *   <tr><td>spotanim config (13)</td><td>3 model</td><td>4 bytes</td><td>1 (value dropped)</td></tr>
 *   <tr><td>identkit config (3)</td><td>5 models, 70-74 chathead models</td><td>4 bytes</td><td>2 (breaks the 241 decoder), 60-64; 65-69 / 75-79 throw (5 slots)</td></tr>
 *   <tr><td>IF3 component type 6</td><td>model at offset 19</td><td>4 bytes</td><td>2 bytes in 211</td></tr>
 *   <tr><td>IF1 component type 6</td><td>model, active model</td><td>4 bytes</td><td>2 bytes in 211</td></tr>
 *   <tr><td>IF_SETMODEL (server packet, rsprot IfSetModelV2)</td><td>model</td><td>4 bytes</td><td></td></tr>
 *   <tr><td>clientscripts, enum/param/struct/dbrow values typed 'm'</td><td>int</td><td>4 bytes</td><td></td></tr>
 * </table>
 * No reference structure limits model ids to 2 bytes. The limit is the transport: the 241 client requests a group
 * with opcode + 3 bytes {@code archive << 16 | group} (class vy, buffer method xw.bo writes a 24-bit value), reads
 * the reply header as archive u8 + group u16 (xw.cc, xw.cw), and keys pending requests by
 * {@code (archive << 16) + group}; rsprot's JS5 decoder on the server reads g1 archive + g2 group. A model above
 * 65535 can therefore never be downloaded: model 100000 would be requested as archive 8 (sprites) group 34464. The
 * models range is thus {@code [highest BASE model + 1, 65535]} instead of the spec's 100000-199999.
 *
 * <h2>Numbers on the real inputs (BASE 2746, USER gadcache 211, VAN211)</h2>
 * BASE 62,714 models (max 62,775); USER 56,379 (max 65,535): 47,594 vanilla, 8,774 user-added, 11 user-changed.
 * 7,306 user models equal BASE's at the same id (5,430 by bytes, 1,876 by geometry); 75 are written at their own id
 * (free in BASE); 2 user-edited vanilla models that BASE never changed (37907 gem stall, 38000 hooded figure) are
 * written in place; 1,402 clash with a different BASE model: 77 map onto an identical BASE model at another id, 1,325
 * move into 64,206-65,530 (packed at the top of 62,776-65,535). 1,421 ids stay free below 65,536 (62,776-64,205
 * minus 9 user ids), which is all the room left for new stock models and further custom models.
 */
package com.gadscape.cacheconv.model;
