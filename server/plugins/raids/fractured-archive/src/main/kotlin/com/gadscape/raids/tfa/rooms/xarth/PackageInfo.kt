/*
 * Package com.gadscape.raids.tfa.rooms.xarth
 *
 * Owner: WU-26 Xarth (spec §4.7). Notes: raids4-impl/ENC-xarth.md.
 *
 * - XarthModule: registers xarth.json (XarthConfig), TfaEncounters.register(TfaRoomId.XARTH) and `::tfa xarth`.
 * - XarthRoom: the TfaEncounter and the world side of XarthHost (NPCs, locs, projectiles, hits, HUD).
 * - XarthBrain: the engine-free boss script (P1 autos, 80/65/50% blasts, P2 transition, autos, summons, heal blast,
 *   death) on a cancellable Timeline; XarthPorts: the brain/world seam.
 * - Xarth (npc 16684), XarthCorpse + CorpseSwarm (16687/16688), XarthPillar (16685/16686 + Rubble locs),
 *   FlameField (P2 flames 62713), XarthArena (static geometry, LOS, shield line), XarthIds (ids + evidence),
 *   Bridge (the one adapter to other units' capabilities), XarthDebug (static-map run).
 */
package com.gadscape.raids.tfa.rooms.xarth
