package com.gadscape.raids.tfa.supplies

import com.gadscape.game.content.omnishop.OmniShop
import com.gadscape.game.content.omnishop.OmniShopStockState
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRaid
import com.gadscape.raids.tfa.raid.TfaRaidMember
import com.gadscape.raids.tfa.raid.TfaRoomId
import org.slf4j.LoggerFactory

/**
 * The Quartermaster's side of one raid (spec §3.12, WU-17):
 * - [grantCredits] once, when room 3 (the section-2 room) completes: [SupplyCredits] per member present, written to
 *   varbit 16381; the failure shop is chosen per member at the same time;
 * - [open]: the member's own shop (17150 or 17151) with their own stock (`tfa.json supplies.perPlayerStock`);
 * - [reset]: credits back to 0 when the member leaves or the raid ends (16381 is a persistent var).
 * `supplies.useOmnishop = false` (or `-Dgadscape.omnishop.enabled=false`) uses the classic option-menu shop with the
 * same rules.
 */
class RaidSupplies(val raid: TfaRaid) {

    private val log = LoggerFactory.getLogger(RaidSupplies::class.java)

    private val stock = HashMap<String, OmniShopStockState>()
    private var shared: OmniShopStockState? = null
    private var sharedFailure: OmniShopStockState? = null

    var granted = false
        private set

    /** Grants the Supply Credits (§3.12 formula) to every member inside; once per raid. */
    fun grantCredits() {
        if (granted) return
        granted = true
        val cfg = raid.config.raid.supplies
        val failurePct = raid.pointsConfig.failureShopPct
        val size = raid.raidSize.coerceAtLeast(1)
        val firstRooms = listOf(TfaRoomId.GUARDIAN) + listOfNotNull(raid.route.room(1), raid.route.room(2))
        for (m in raid.members) {
            if (!m.inRaid || m.creditsGranted) continue
            val points = raid.ledger.banked(m.username)
            val deaths = m.deathsIn(firstRooms)
            val credits = SupplyCredits.credits(cfg, points, size, deaths)
            m.creditsGranted = true
            m.failureShop = SupplyCredits.usesFailureShop(cfg, failurePct, points, size)
            m.player.varManager.sendBit(TfaIds.Varbit.SUPPLY_POINTS, credits)
            m.player.sendMessage("The Quartermaster has arrived in the camp. You have been granted $credits Supply Credits.")
            log.info("Fractured Archive raid {}: {} gets {} credits (points {}, size {}, deaths {}, failure shop {})",
                raid.id, m.username, credits, points, size, deaths, m.failureShop)
        }
    }

    /** Opens Alliance Supplies for [member] (Quartermaster "Resupply"). */
    fun open(member: TfaRaidMember) {
        val player = member.player
        if (!granted) {
            player.sendMessage("The Quartermaster is still setting up.")
            return
        }
        val definition = AllianceSuppliesShop.definition(member.failureShop)
        val state = stateOf(member, definition)
        val useOmni = raid.config.raid.supplies.useOmnishop && OmniShop.enabled
        if (useOmni) OmniShop.open(player, definition, state) else OmniShop.openClassic(player, definition, state)
    }

    private fun stateOf(member: TfaRaidMember, definition: com.gadscape.game.content.omnishop.OmniShopDefinition): OmniShopStockState {
        if (raid.config.raid.supplies.perPlayerStock) {
            return stock.getOrPut(member.username + (if (member.failureShop) ":f" else "")) { OmniShopStockState(definition.initialStock()) }
        }
        return if (member.failureShop) sharedFailure ?: OmniShopStockState(definition.initialStock()).also { sharedFailure = it }
        else shared ?: OmniShopStockState(definition.initialStock()).also { shared = it }
    }

    /** Credits back to 0 (leaving the raid, raid end). */
    fun reset(member: TfaRaidMember) {
        val player = member.player
        if (player.isFinished) return
        try {
            if (player.varManager.getBitValue(TfaIds.Varbit.SUPPLY_POINTS) != 0) player.varManager.sendBit(TfaIds.Varbit.SUPPLY_POINTS, 0)
            OmniShop.close(player)
        } catch (e: Exception) {
            log.warn("Fractured Archive: resetting supplies failed for {}", player.username, e)
        }
    }
}
