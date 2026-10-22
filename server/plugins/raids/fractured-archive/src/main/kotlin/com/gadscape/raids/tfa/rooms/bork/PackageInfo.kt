/*
 * Package com.gadscape.raids.tfa.rooms.bork
 *
 * Owner: WU-22 Bork and the Smorks (spec §4.3). Notes: raids4-impl/ENC-bork.md.
 *
 * | File | What |
 * |---|---|
 * | BorkSmorksModule | registration: TfaConfig.register(BorkSmorksConfig), TfaEncounters.register(TfaRoomId.BORK), ::tfa bork |
 * | BorkSmorksConfig | bork_smorks.json (spec §4.3 block + implementation keys) |
 * | BorkSmorksRoom | the TfaEncounter + the engine side of BorkHost |
 * | BorkSmorksBrain | the scripted fight (engine-free, unit tested) |
 * | BorkSmorksPorts | Fighter, BorkHost (brain <-> world seam) |
 * | Bork, Smorks | npcs 16640 / 16641 (TfaBossNpc), combat definitions, live traits |
 * | Tempo, CartShadow, OilField, WaveDust, BorkArena, Timeline | pure pieces (unit tested) |
 * | BorkIds | every cache id with its evidence |
 * | Bridge | the one adapter to other units' capabilities |
 * | BorkDebug | ::tfa bork: the room on the static map without the raid system |
 */
package com.gadscape.raids.tfa.rooms.bork
