/*
 * Package com.gadscape.raids.tfa.rooms.dreadnoth
 *
 * Owner: WU-24 Dreadnoth the Jellied (spec §4.5). Notes: raids4-impl/ENC-dreadnoth.md.
 *
 * DreadnothModule  registration (config spec, TfaEncounters factory for TfaRoomId.DREADNOTH, ::tfa dreadnoth)
 * DreadnothConfig  dreadnoth.json (spec §4.5 house defaults + implementation keys)
 * DreadnothRoom    the TfaEncounter (lifecycle, completion, debug hooks) and the engine side of DreadnothHost
 * DreadnothBrain   the boss script: chase / threshold slams / enrage jumps / death (engine-free, unit tested)
 * Dreadnoth        npc 16665 (TfaBossNpc), engine combat loop off
 * Jelly            npc 16666 (TfaNpc + CombatScript) and JellySwarm (batches, death wave, targets, deaths)
 * JellyPlanner     jelly counts, spawn tiles and death-wave hops (pure)
 * BloodField       blood tiles 62609 (pure; locs through Bridge)
 * DreadnothArena   static geometry of the 15x15 arena (pure)
 * Timeline         tick counter + cancellable action queue (spec §2.5)
 * DreadnothIds     every id the room uses, with evidence
 * DreadnothPorts   Fighter / DreadnothHost seams between the brain and the world
 * Bridge           the one adapter to other units' capabilities (HUD, prefetch, music, drains, hazards, telegraphs)
 * DreadnothDebug   ::tfa dreadnoth: the room on the static map without the raid system
 */
package com.gadscape.raids.tfa.rooms.dreadnoth
