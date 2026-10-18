package com.gadscape.game.content.pk

import com.gadscape.game.world.entity.player.pkPoints
import com.gadscape.game.world.entity.player.pvpKillStreak
import com.gadscape.tools.PlayerUUID
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * PK points (PKP): earned for killing other players in the Wilderness, spent in the [PkStore].
 *
 * Called from [com.zenyte.game.content.killstreak.Killstreaks.handlePlayerKill] before the killstreaks are updated,
 * so the streaks read here are the ones the killer and victim had going into the fight. Blood money is still handed
 * out by Killstreaks; PKP comes on top of it.
 */
object PkPoints {

    /** PKP for any rewarded kill. */
    const val BASE_POINTS = 10

    /** PKP per kill of the victim's streak (shutting down a streaker), up to [MAX_VICTIM_STREAK_BONUS]. */
    const val POINTS_PER_VICTIM_STREAK = 2
    const val MAX_VICTIM_STREAK_BONUS = 20

    /** PKP per kill of the killer's own streak, up to [MAX_KILLER_STREAK_BONUS]. */
    const val POINTS_PER_KILLER_STREAK = 2
    const val MAX_KILLER_STREAK_BONUS = 20

    /** One PKP per this many Wilderness levels (of the victim's position), up to [MAX_WILDERNESS_BONUS]. */
    const val WILDERNESS_LEVELS_PER_POINT = 5
    const val MAX_WILDERNESS_BONUS = 10

    /** Killing the same victim again within this window gives no PKP. */
    val REPEAT_KILL_WINDOW_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)

    private class RecentKill(val victimUsername: String, val victimIp: String?, val time: Long)

    /** Killer username -> kills they were recently rewarded for. Not persisted: a restart clears it. */
    private val recentKills = ConcurrentHashMap<String, MutableList<RecentKill>>()

    /**
     * Awards PKP to [killer] for killing [victim], if the kill counts.
     *
     * @return the PKP awarded, 0 when the kill gave none.
     */
    @JvmStatic
    fun handlePlayerKill(killer: Player, victim: Player): Int {
        if (killer === victim || killer.username.equals(victim.username, ignoreCase = true))
            return 0
        if (!WildernessArea.isWithinWilderness(victim))
            return 0

        if (sameIp(killer, victim) || sameUuid(killer, victim)) {
            killer.sendMessage("You do not receive PK points for killing a player on the same connection or computer.")
            return 0
        }

        if (killedRecently(killer, victim)) {
            killer.sendMessage("You killed ${victim.name} too recently to receive PK points for them again.")
            return 0
        }

        val wildernessLevel = WildernessArea.getWildernessLevel(victim.location).orElse(0)
        val points = pointsForKill(killer.pvpKillStreak, victim.pvpKillStreak, wildernessLevel)

        killer.pkPoints = (killer.pkPoints.toLong() + points).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        recordKill(killer, victim)

        killer.sendMessage("You received <col=ff0000>$points</col> PK points (total <col=ff0000>${killer.pkPoints}</col>).")
        killer.sendDeveloperMessage(
            "PKP(base=$BASE_POINTS, victimStreak=${victim.pvpKillStreak}, killerStreak=${killer.pvpKillStreak}, " +
                    "wildy=$wildernessLevel) = $points"
        )
        return points
    }

    /**
     * The PKP formula: base + victim streak bonus + killer streak bonus + Wilderness level bonus, each bonus capped.
     */
    @JvmStatic
    fun pointsForKill(killerStreak: Int, victimStreak: Int, wildernessLevel: Int): Int {
        val victimBonus = min(victimStreak.coerceAtLeast(0) * POINTS_PER_VICTIM_STREAK, MAX_VICTIM_STREAK_BONUS)
        val killerBonus = min(killerStreak.coerceAtLeast(0) * POINTS_PER_KILLER_STREAK, MAX_KILLER_STREAK_BONUS)
        val wildernessBonus = min(wildernessLevel.coerceAtLeast(0) / WILDERNESS_LEVELS_PER_POINT, MAX_WILDERNESS_BONUS)
        return BASE_POINTS + victimBonus + killerBonus + wildernessBonus
    }

    private fun sameIp(a: Player, b: Player): Boolean {
        val ipA = a.ip ?: return false
        val ipB = b.ip ?: return false
        return ipA.isNotBlank() && ipA.equals(ipB, ignoreCase = true)
    }

    private fun sameUuid(a: Player, b: Player): Boolean {
        val uuidA = a.playerInformation?.uuid ?: return false
        val uuidB = b.playerInformation?.uuid ?: return false
        if (PlayerUUID.isEmpty(uuidA) || PlayerUUID.isEmpty(uuidB))
            return false
        return uuidA.contentEquals(uuidB)
    }

    private fun killedRecently(killer: Player, victim: Player): Boolean {
        val kills = recentKills[killer.username.lowercase()] ?: return false
        val cutoff = System.currentTimeMillis() - REPEAT_KILL_WINDOW_MILLIS
        synchronized(kills) {
            kills.removeIf { it.time < cutoff }
            val victimIp = victim.ip
            return kills.any {
                it.victimUsername.equals(victim.username, ignoreCase = true)
                        || (!victimIp.isNullOrBlank() && victimIp.equals(it.victimIp, ignoreCase = true))
            }
        }
    }

    private fun recordKill(killer: Player, victim: Player) {
        val kills = recentKills.computeIfAbsent(killer.username.lowercase()) { mutableListOf() }
        synchronized(kills) {
            kills.add(RecentKill(victim.username, victim.ip, System.currentTimeMillis()))
        }
    }
}
