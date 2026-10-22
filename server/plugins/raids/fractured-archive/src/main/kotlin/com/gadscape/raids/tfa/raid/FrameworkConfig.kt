package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.config.ConfigReader
import com.gadscape.raids.tfa.config.Evidence.CACHE
import com.gadscape.raids.tfa.config.Evidence.DOC
import com.gadscape.raids.tfa.config.Evidence.HOUSE
import com.gadscape.raids.tfa.config.Evidence.INF
import com.gadscape.raids.tfa.config.TfaConfigSpec

/**
 * `framework.json`: the raid-framework keys of WU-12..WU-18 that `tfa.json` (WU-08's [com.gadscape.raids.tfa.config.RaidConfig])
 * does not have. Every [HOUSE] / [INF] value is listed by `::tfa dump config` until verified.
 */
data class FrameworkConfig(
    val spectate: Spectate,
    val death: Death,
    val nexus: Nexus,
    val raid: Raid,
) {

    /** §3.13 / U15. */
    data class Spectate(
        /** "That raid has reached the maximum number of spectators." [DOC] */
        val maxSpectators: Int,
        /** "Spectate specific party" needs the viewer on a participant's friends list [DOC]. */
        val specificNeedsFriend: Boolean,
        /** Inventory id used to mirror the viewed player's inventory into 978:0 (U15 [INF]; 1031 is unused while Practice is off). */
        val mirrorInventory: Int,
        /** Ticks a ping highlight stays on the viewed player's slot ("for 4 s" [HOUSE] ≈ 7 t). */
        val pingTicks: Int,
        /** Clientscript run on the viewed player for a ping, or -1 for the chat-message fallback (U15: signature unknown). */
        val pingScript: Int,
        /** Dead members' camera follows the first living member when they die ([HOUSE]; else the room centre). */
        val deadFollowMember: Boolean,
    )

    /** §3.8. */
    data class Death(
        /** Item loss of a wipe: `server_default` = GadScape's normal death rules; `gravestone` = always a gravestone (live). */
        val wipeItemLoss: WipeItemLoss,
        /** Fee rule of script 1797 [CACHE]: below this KC... */
        val feeKcThreshold: Int,
        /** ...a death in the zone pays this % of the normal fee [CACHE scale(1,4,fee)]. */
        val feeReducedPct: Int,
        /** Ticks from the death animation to the spectate state (ToB: 3). */
        val spectateDelayTicks: Int,
    )

    enum class WipeItemLoss { SERVER_DEFAULT, GRAVESTONE }

    /** §3.5 flow details not in `tfa.json nexus`. */
    data class Nexus(
        /** Ticks the Navigator walks towards the doors before despawning. */
        val navigatorWalkTicks: Int,
        /** Spawn the in-raid Talk-to allies Armacus 16696 / Eva 16697 in the camp [DOC transcript, INF placement]. */
        val talkToAllies: Boolean,
        val armacusTile: StaticTile,
        val evaTile: StaticTile,
        /** Spawn the no-op camp allies 16724 / 16725 too [CACHE]. */
        val campAllies: Boolean,
        val campArmacusTile: StaticTile,
        val campEvaTile: StaticTile,
        /** Spawn the in-raid Merit scroll 62774 at `tfa.json tiles.campMeritScroll` [INF]. */
        val campMeritScroll: Boolean,
    )

    /** §2.3 / §2.5. */
    data class Raid(
        /** Hard cap of concurrent raids (map space: one 5 x 7-region block each). */
        val maxConcurrentRaids: Int,
        /** Tiles searched around a landing tile (transporter, antechamber, camp swap) when it is blocked. */
        val landingSearchRadius: Int,
    )

    companion object SPEC : TfaConfigSpec<FrameworkConfig>("framework.json") {
        override fun read(r: ConfigReader): FrameworkConfig = FrameworkConfig(
            spectate = r.obj("spectate") {
                Spectate(
                    maxSpectators = int("maxSpectators", 100, DOC, min = 0, max = 1000),
                    specificNeedsFriend = bool("specificNeedsFriend", true, DOC),
                    mirrorInventory = int("mirrorInventory", 1031, INF, min = 0, max = 65535),
                    pingTicks = int("pingTicks", 7, HOUSE, min = 1, max = 100),
                    pingScript = int("pingScript", -1, HOUSE, min = -1),
                    deadFollowMember = bool("deadFollowMember", true, HOUSE),
                )
            },
            death = r.obj("death") {
                Death(
                    wipeItemLoss = enum("wipeItemLoss", WipeItemLoss.SERVER_DEFAULT, HOUSE),
                    feeKcThreshold = int("feeKcThreshold", 5, CACHE, min = 0),
                    feeReducedPct = int("feeReducedPct", 25, CACHE, min = 0, max = 100),
                    spectateDelayTicks = int("spectateDelayTicks", 3, HOUSE, min = 1, max = 10),
                )
            },
            nexus = r.obj("nexus") {
                Nexus(
                    navigatorWalkTicks = int("navigatorWalkTicks", 6, HOUSE, min = 0, max = 50),
                    talkToAllies = bool("talkToAllies", true, INF),
                    armacusTile = tile("armacusTile", StaticTile(4122, 5583, 0), HOUSE),
                    evaTile = tile("evaTile", StaticTile(4124, 5579, 0), HOUSE),
                    campAllies = bool("campAllies", false, INF),
                    campArmacusTile = tile("campArmacusTile", StaticTile(4133, 5590, 0), HOUSE),
                    campEvaTile = tile("campEvaTile", StaticTile(4121, 5590, 0), HOUSE),
                    campMeritScroll = bool("campMeritScroll", true, INF),
                )
            },
            raid = r.obj("raid") {
                Raid(
                    maxConcurrentRaids = int("maxConcurrentRaids", 64, HOUSE, min = 1, max = 500),
                    landingSearchRadius = int("landingSearchRadius", 3, HOUSE, min = 0, max = 10),
                )
            },
        )
    }
}
