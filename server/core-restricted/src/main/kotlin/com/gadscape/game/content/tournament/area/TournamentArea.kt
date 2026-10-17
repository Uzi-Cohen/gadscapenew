package com.gadscape.game.content.tournament.area

import com.gadscape.game.content.tournament.Tournament
import com.gadscape.game.content.tournament.TournamentState
import com.gadscape.game.content.tournament.moveToTournamentPortal
import com.gadscape.game.content.tournament.preset.TournamentPreset
import com.gadscape.game.content.tournament.sendTournamentMessage
import com.gadscape.game.plugin.experienceGainDisabled
import com.gadscape.game.plugin.prayersDisabled
import com.gadscape.game.plugin.spellsDisabled
import com.gadscape.game.plugin.tradingDisabled
import com.gadscape.game.util.formattedString
import com.zenyte.game.content.skills.magic.spells.lunar.NPCContact
import com.zenyte.game.content.skills.magic.spells.lunar.SpellbookSwap
import com.zenyte.game.world.region.DynamicArea
import com.zenyte.game.world.region.area.plugins.*
import com.zenyte.game.world.region.dynamicregion.AllocatedArea
import kotlin.time.Duration.Companion.seconds

abstract class TournamentArea(
    preset: TournamentPreset,
    allocatedArea: AllocatedArea,
    staticChunkX: Int,
    staticChunkY: Int
) :
    DynamicArea(allocatedArea, staticChunkX, staticChunkY),
    IDropPlugin,
    ExperiencePlugin by experienceGainDisabled(),
    SpellPlugin by spellsDisabled(
        NPCContact::class,
        SpellbookSwap::class
    ),
    LogoutRestrictionPlugin,
    PrayerPlugin by prayersDisabled(*preset.disabledPrayers),
    TempPlayerStatePlugin,
    TradePlugin by tradingDisabled(message = true),
    CycleProcessPlugin
{
    private var destroying = false

    abstract val tournament: Tournament

    override fun process() = Unit

    override fun postProcess() {
        if (destroying)
            return
        val state = tournament.state as? TournamentState.Finished ?:return
        val autoDestroyRegionTimer = state.autoDestroyRegionTimer
        if (autoDestroyRegionTimer.elapsed()) {
            players.toSet().forEach { it.moveToTournamentPortal() }
            destroying = true
            destroyRegion()
        } else {
            autoDestroyRegionTimer.every(10.seconds) {
                players.forEach { it.sendTournamentMessage("You'll be moved back home in ${autoDestroyRegionTimer.durationRemaining().formattedString}.") }
            }
        }
    }
}
