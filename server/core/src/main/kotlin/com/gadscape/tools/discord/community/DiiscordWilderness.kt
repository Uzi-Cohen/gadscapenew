package com.gadscape.tools.discord.community

import com.gadscape.game.world.entity.player.killingBlowHit
import com.gadscape.tools.discord.DiscordServer
import com.gadscape.tools.discord.congratulate
import com.gadscape.tools.discord.discordImageUrl
import com.gadscape.tools.discord.itemInventoryImageUrl
import com.gadscape.tools.discord.pity
import com.zenyte.CacheManager
import com.zenyte.game.item.Item
import com.zenyte.game.item.ItemId
import com.zenyte.game.util.Utils
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.SpecialAttack
import com.zenyte.game.world.entity.player.privilege.GameMode
import com.zenyte.game.world.region.GlobalAreaManager
import com.zenyte.game.world.region.area.wilderness.WildernessArea
import dev.kord.common.Color
import dev.kord.core.behavior.channel.createEmbed
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import kotlinx.coroutines.launch
import mgi.tools.jagcached.cache.Cache
import mgi.types.config.enums.EnumDefinitions

fun DiscordCommunityBot.onKill(attacker: Player, victim: Player) {

    val attackerName = "**${attacker.name}**"
    val victimName = "**${victim.name}**"
    val lastHit = victim.killingBlowHit
    val killedWeapon = lastHit?.weapon as? Item
    val area = GlobalAreaManager.getArea(victim)
    scope.launch {

        broadcastChannel.run {
            createEmbed {
                title = "$attackerName killed $victimName"
                color = Color(136, 8, 8)
                author {
                    name = "Death"
                    icon = discordImageUrl("pk_death")
                }
                if (killedWeapon != null)
                    thumbnail { url = itemInventoryImageUrl(killedWeapon.id) }
                description = buildString {
                    if (lastHit != null) {
                        append(attackerName)
                        append(" hit a ${lastHit.damage} with ")
                        if (killedWeapon != null) {
                            val name = killedWeapon.name
                            append("${Utils.getAOrAn(name)} $name")
                            if (lastHit.isSpecial) {
                                val specialAttack = SpecialAttack.SPECIAL_ATTACKS[killedWeapon.id]
                                if (specialAttack != null) {
                                    append(" using its special attack **${specialAttack.specialAttackName}**")
                                }
                            }
                        }
                        append("!")
                    } else
                        append("Did you just die without getting hit $victimName?")
                    append(congratulate(attacker))
                    append(pity(victim))
                }
                val areaName = area?.name()
                if (areaName != null) {
                    footer {
                        if (area is WildernessArea)
                            icon = discordImageUrl("wilderness")
                        text = areaName
                    }
                }
            }
        }
    }
}

fun main() {
    CacheManager.loadCache(Cache.openCache("base/cache/data/cache", true))
    CacheManager.loadDefinitions()
    EnumDefinitions().load()

    DiscordCommunityBot.init(
        DiscordServer.Main,
        token = "CHANGE_ME"
    )
    val killer = mockPlayer("Test Killer", 0L)
    val victim = mockPlayer("Test Victim", 0L)

    val hit = spyk(Hit(killer, 70, HitType.MELEE, 0))
    hit.setSpecialAttack()
    hit.weapon = Item(ItemId.ARMADYL_GODSWORD)

    victim.killingBlowHit = hit

    mockkStatic(GlobalAreaManager::class)
    every { GlobalAreaManager.getArea(any()) } returns WildernessArea()

    DiscordCommunityBot.onKill(killer, victim)
}

private fun mockPlayer(username: String, discordUserId: Long): Player {
    val player = mockk<Player>()
    every { player.name } returns username
    every { player.position } returns Location(0, 0, 0)
    every { player.getNumericAttribute(any()) } returns 1
    every { player.notificationSettings.getKillcount(any()) } returns 0
    every { player.gameMode } returns GameMode.REGULAR
    every { player.combatXPRate } returns 250
    every { player.skillingXPRate } returns 80
    every { player.appearance.gender } returns "Man"
    every { player.skills.totalLevel } returns 420
    every { player.combatLevel } returns 69

    val attributes = mutableMapOf<String, Any?>()
    attributes["discordUserId"] = discordUserId
    every { player.attributes } returns attributes

    val tempAttributes = mutableMapOf<Any, Any?>()
    every { player.temporaryAttributes } returns tempAttributes
    return player
}
