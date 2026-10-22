package com.gadscape.game.content.wilderness

import com.google.common.eventbus.Subscribe
import com.gadscape.game.content.wilderness.event.hot_zone.WildernessHotZoneEvent
import com.gadscape.game.world.PlayerEvent
import com.gadscape.game.world.entity.player.pvpDeaths
import com.gadscape.game.world.entity.player.pvpKillStreak
import com.gadscape.game.world.entity.player.pvpKills
import com.gadscape.game.world.hook
import com.zenyte.game.content.killstreak.Killstreaks
import com.zenyte.game.content.well.WellPerk
import com.zenyte.game.content.xamphur.XamphurBoost
import com.zenyte.game.item.Item
import com.zenyte.game.util.Utils
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.player.MessageType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.MemberRank
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import com.zenyte.plugins.events.ServerLaunchEvent
import kotlin.math.min

@Suppress("unused")
object WildernessKillStreakModule {

    @JvmStatic
    @Subscribe
    fun onServerLaunchEvent(event: ServerLaunchEvent) {
        Killstreaks.hotZone = java.util.function.Predicate { WildernessHotZoneEvent.inHotZone(it) }
        event.worldThread.hook<PlayerEvent.Died> {
            val killer = ((it.killer as? Player))?:return@hook
            Killstreaks.handlePlayerKill(killer, it.player)
        }
    }

    // The private copy of handlePlayerKill/getTotalBloodmoneyForKill/... that lived here was dead code (the hook
    // above calls Killstreaks.handlePlayerKill); removed so the anti-farm rules live in one place
    // (Killstreaks + PvpKillValidator).
}
//
//class BloodMoneyReward(
//    val privilege: MemberRank,
//    val myKillstreak: Int,
//    val shutdownKillstreak: Int,
//    val wildernessLevel: Int,
//    val totalAmount: Int,
//    val totalAmountWithBooster: Int,
//    val totalAmountWithXamphur: Int,
//    val totalAmountWithXamphurAndBooster: Int,
//    val shutdownBonus: Int
//
//)
//fun main() {
//    val rewards = mutableListOf<BloodMoneyReward>()
//    val shutdownKdr = 0..15 step 5
//    listOf(MemberRank.NONE, MemberRank.PREMIUM, MemberRank.UBER).forEach { privilege ->
//        // 0..50
//        val streakRanges = 0..25 step 5
//        streakRanges.forEach { yourStreak ->
//            shutdownKdr.forEach { shutdownKillstreak ->
//                val wildernessLevels = 0..60 step 10
//                wildernessLevels.forEach { wildernessLevel ->
//                    val reward = bloodMoneyReward(privilege, yourStreak, wildernessLevel, shutdownKillstreak)
//
//                    rewards.add(reward)
//                }
//            }
//        }
//    }
//    printAsciiTable(rewards)
//
////    printAsciiTable(listOf(bloodMoneyReward(MemberRank.UBER, 10, 50, 10)))
//}
//
//private fun bloodMoneyReward(
//    privilege: MemberRank,
//    yourStreak: Int,
//    wildernessLevel: Int,
//    shutdownKillstreak: Int,
//): BloodMoneyReward {
//    val rankBoost = Killstreaks.getRankBoost(privilege)
//    // 0..30
//    val killstreakBoost = Killstreaks.getKillstreakBoost(yourStreak)
//    // (0..80)
//    val totalBoost = 1.2 * (rankBoost + killstreakBoost).toDouble()
//
//    val min = 10
//    val wildernessLevelExtra = (wildernessLevel / 10) * 3
//    val baseAmount = min + wildernessLevelExtra
//    val bonusAmount = (baseAmount) / (100.0) * (totalBoost)
//
//    val shutdownBonus = Killstreaks.getBloodmoneyForShutdown(shutdownKillstreak)
//    val totalAmount = shutdownBonus + (baseAmount + bonusAmount).coerceAtMost(50.0)
//
//    val totalAmountWithBooster = totalAmount * 1.25
//    val totalAmountWithXamphur = (totalAmount * 2)
//    val totalAmountWithXamphurAndBooster = (totalAmount * 2) * 1.25
//
//    val reward = BloodMoneyReward(
//        privilege,
//        yourStreak,
//        shutdownKillstreak,
//        wildernessLevel,
//        totalAmount.toInt(),
//        totalAmountWithBooster.toInt(),
//        totalAmountWithXamphur.toInt(),
//        totalAmountWithXamphurAndBooster.toInt(),
//        shutdownBonus
//    )
//    return reward
//}
//
//fun printAsciiTable(rewards: List<BloodMoneyReward>) {
//    val header = listOf(
//        "Privilege", "My Killstreak", "Shutdown Killstreak", "Wilderness Level",
//        "Total Amount", "Total w/ Booster", "Total w/ Xamphur", "Total w/ Xamphur + Booster"
//    )
//
//    val separator = "-".repeat(header.joinToString(" | ").length)
//
//    println(separator)
//    println(header.joinToString(" | "))
//    println(separator)
//
//    rewards.forEach { reward ->
//        val row = listOf(
//            reward.privilege.toString().padEnd(header[0].length),
//            "${reward.myKillstreak.toString().padEnd(2)} (my kdr)".padEnd(header[1].length),
//            "${reward.shutdownKillstreak.toString().padEnd(2)} (other kdr +${reward.shutdownBonus})".padEnd(header[2].length),
//            "${reward.wildernessLevel.toString().padEnd(2)} (wildy lvl)".padEnd(header[3].length),
//            "${reward.totalAmount} (base)".padEnd(header[4].length),
//            "${reward.totalAmountWithBooster} (+ booster)".padEnd(header[5].length),
//            "${reward.totalAmountWithXamphur} (+ xamphur)".padEnd(header[6].length),
//            "${reward.totalAmountWithXamphurAndBooster} (+ xamphur + booster)".padEnd(header[7].length)
//        )
//        println(row.joinToString(" | "))
//    }
//    println(separator)
//}
