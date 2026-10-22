/*
 * Package com.gadscape.raids.tfa.death
 *
 * Owner: WU-15 Deaths, wipes, logout (spec §3.8).
 * Planned files (spec §2.1): TfaDeathHandler.kt, WipeHandler.kt, DeathFeeZone.kt (copy of proc 10182).
 * Honours TfaDebug.isGodmode. Reads tfa.json logout / teleportOut / tiles.lobbyGravestone; uses TfaInstance.toStatic.
 *
 * Created empty by WU-08 (module skeleton). Read raids4-impl/ARCHITECTURE.md for the extension points; do not
 * edit the WU-08 core files (TfaModule, ids/, config/, encounter/{TfaEncounter,TfaNpc,TfaBossNpc,Scaling,CombatTraits},
 * raid/{TfaRoomId,TfaInstance,StaticTile}, debug/, TfaServices) except through those extension points.
 */
package com.gadscape.raids.tfa.death
