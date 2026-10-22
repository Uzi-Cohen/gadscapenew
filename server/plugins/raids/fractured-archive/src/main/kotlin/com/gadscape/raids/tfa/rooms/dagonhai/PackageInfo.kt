/*
 * Package com.gadscape.raids.tfa.rooms.dagonhai
 *
 * Owner: WU-25 Dagon'hai trio (spec §4.6). Notes: raids4-impl/ENC-dagonhai.md.
 *
 * Files (the spec §2.1 plan, merged where one class covers several planned ones):
 * - DagonhaiModule: registers DagonhaiConfig (dagonhai.json), TfaEncounters.register(TfaRoomId.DAGONHAI), ::tfa dagonhai.
 * - DagonhaiRoom: the TfaEncounter and the engine side of DagonhaiHost (NPCs, hits, projectiles, graphics, HUD).
 * - DagonhaiFight: the engine-free fight script (Elder autos, ElementalChannel, TomeThrow, GodEffects application,
 *   breaks, Zamorak protection with DemonicRift / Imp, empowered state, explosion and finale).
 * - DagonhaiNpc (Elder and monks, shared pool, spell hook), Cauldron, Imp + DemonicRift, DagonhaiCombatDefs.
 * - GodEffects (pure effect geometry), DagonhaiArena (static geometry, lanes), DagonhaiConfig, DagonhaiIds,
 *   DagonhaiPorts (Role, God, Fighter, DagonhaiHost), Timeline, Bridge (the one adapter to other units), DagonhaiDebug.
 *
 * The shared pool is applied in DagonhaiNpc.processHit (TfaNpc records the capped damage for contribution).
 */
package com.gadscape.raids.tfa.rooms.dagonhai
