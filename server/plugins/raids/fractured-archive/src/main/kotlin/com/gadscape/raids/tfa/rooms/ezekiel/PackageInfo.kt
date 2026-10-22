/*
 * Package com.gadscape.raids.tfa.rooms.ezekiel
 *
 * Owner: WU-21 Ezekiel (spec §4.2), room 2, section 1 west, static region 16214. Notes: raids4-impl/ENC-ezekiel.md.
 *
 * - EzekielModule: registers `ezekiel.json` (EzekielConfig), TfaEncounters.register(TfaRoomId.EZEKIEL), `::tfa ezekiel`.
 * - EzekielRoom: the TfaEncounter and the world side (EzekielHost) of EzekielFight, the pure state machine.
 * - EzekielNpcs: Ezekiel 16652, Animated Sword 16653, sword fence 16654, finale sword 16655, death sword 16656.
 * - Cleave / Star (EzekielGeometry), SwordPatterns, SwordFlight, Finale, TankOrder: pure rules, unit-tested.
 * - EzekielIds: every id used with its evidence; Bridge: the one adapter to other units' capabilities.
 */
package com.gadscape.raids.tfa.rooms.ezekiel
