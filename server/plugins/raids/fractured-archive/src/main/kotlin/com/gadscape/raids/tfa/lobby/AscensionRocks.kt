package com.gadscape.raids.tfa.lobby

import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.content.skills.mining.MiningDefinitions.PickaxeDefinitions
import com.gadscape.game.content.skills.mining.PickAxeDefinition
import com.zenyte.game.item.Item
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.actions.NPCPlugin
import com.zenyte.game.world.entity.player.Action
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.SkillConstants
import com.zenyte.game.world.`object`.ObjectAction
import com.zenyte.game.world.`object`.WorldObject
import com.zenyte.plugins.dialogue.PlainChat
import org.slf4j.LoggerFactory
import java.util.Random

/**
 * Ascension rocks in the lobby (spec §3.2) [DOC wiki Ascension rocks; CACHE locs and seqs].
 *
 * Locs [CACHE]: active 63266-63268 (op Mine; idle anims 15071 / 15073 / 15075), inactive 63269-63271 (no ops; idle
 * 15070 / 15072 / 15074); variant k of one is variant k of the other. The cache map places ten inactive rocks
 * (`lobby.json rocks.tiles`). Every `cycleTicks` (295) the three active rocks sink (loc anim `crystal_ascensionN_lower`
 * 15067-15069, 61 cycles = 3 ticks, then the inactive loc) and three other rocks rise (active loc + `_raise`
 * 15064-15066). The seqs are on the rock skeleton 2685 of the loc models [CACHE SkelCheck].
 *
 * Mining is [AscensionMiningAction]; the numbers are in [AscensionMining].
 */
object AscensionRocks {

    private val log = LoggerFactory.getLogger(AscensionRocks::class.java)

    internal val random = Random()

    private class Rock(val index: Int, val tile: Location, val variant: Int, val type: Int, val rotation: Int) {
        var active = false
        /** Bumped whenever the rock changes state, so a running mining action notices. */
        var generation = 0
    }

    private val rocks = ArrayList<Rock>()

    /** Ticks of the current cycle: start and end (LobbyClock time). */
    var cycleStart = 0L
        private set
    var cycleEnd = 0L
        private set
    var cycleId = 0
        private set

    private const val ROTATE_TAG = "rocks:rotate"

    /** Finds the placed rocks and starts the rotation (first world tick after launch). */
    @JvmStatic
    fun init() {
        val cfg = TfaConfig.current[LobbyConfig].rocks
        rocks.clear()
        val inactive = TfaIds.Loc.ASCENSION_ROCKS_INACTIVE
        val active = TfaIds.Loc.ASCENSION_ROCKS_ACTIVE
        for (tile in cfg.tiles) {
            val loc = Location(tile.x, tile.y, tile.plane)
            val obj = World.getObjectWithType(loc, 10) ?: continue
            val variant = inactive.indexOf(obj.id).takeIf { it >= 0 } ?: active.indexOf(obj.id).takeIf { it >= 0 } ?: continue
            rocks += Rock(rocks.size, loc, variant, obj.type, obj.rotation)
        }
        if (rocks.isEmpty()) {
            log.warn("Fractured Archive lobby: no Ascension rocks found at the configured tiles; mining is off")
            return
        }
        rocks.forEach { rock ->
            val id = World.getObjectWithType(rock.tile, 10)?.id ?: return@forEach
            if (id in active) setInactive(rock, animate = false)
        }
        rotate()
        log.info("Fractured Archive lobby: {} Ascension rocks, {} active, rotating every {} ticks", rocks.size, cfg.activeCount, cfg.cycleTicks)
    }

    /** Sinks the active rocks and raises the next set; re-schedules itself. */
    private fun rotate() {
        val cfg = TfaConfig.current[LobbyConfig].rocks
        val current = rocks.filter { it.active }.map { it.index }.toSet()
        val next = AscensionMining.nextActive(rocks.size, current, cfg.activeCount, random)
        for (rock in rocks) if (rock.active) setInactive(rock, animate = true)
        for (index in next) setActive(rocks[index])
        cycleStart = LobbyClock.queue.now
        cycleEnd = cycleStart + cfg.cycleTicks
        cycleId++
        LobbyClock.queue.cancel(ROTATE_TAG)
        LobbyClock.queue.schedule(cfg.cycleTicks, ROTATE_TAG) { rotate() }
    }

    private fun setActive(rock: Rock) {
        rock.active = true
        rock.generation++
        val obj = WorldObject(TfaIds.Loc.ASCENSION_ROCKS_ACTIVE[rock.variant], rock.type, rock.rotation, rock.tile)
        World.spawnObject(obj)
        // crystal_ascension0N_raise [CACHE seq 15064-15066]
        World.sendObjectAnimation(obj, Animation(TfaIds.Seq.ROCK_RAISE[rock.variant]))
    }

    private fun setInactive(rock: Rock, animate: Boolean) {
        rock.active = false
        rock.generation++
        val generation = rock.generation
        val swap = {
            if (!rock.active && rock.generation == generation) {
                World.spawnObject(WorldObject(TfaIds.Loc.ASCENSION_ROCKS_INACTIVE[rock.variant], rock.type, rock.rotation, rock.tile))
            }
        }
        if (!animate) return swap()
        val activeObj = WorldObject(TfaIds.Loc.ASCENSION_ROCKS_ACTIVE[rock.variant], rock.type, rock.rotation, rock.tile)
        // crystal_ascension0N_lower [CACHE seq 15067-15069], 61 cycles: swap after `animTicks` (3)
        World.sendObjectAnimation(activeObj, Animation(TfaIds.Seq.ROCK_LOWER[rock.variant]))
        LobbyClock.queue.schedule(TfaConfig.current[LobbyConfig].rocks.animTicks, "rock:${rock.index}") { swap() }
    }

    /** The rock index at [tile] if it is active now, with its generation; null otherwise. */
    internal fun activeAt(tile: Location): Pair<Int, Int>? {
        val rock = rocks.firstOrNull { it.tile.x == tile.x && it.tile.y == tile.y && it.tile.plane == tile.plane } ?: return null
        return if (rock.active) rock.index to rock.generation else null
    }

    internal fun stillActive(index: Int, generation: Int): Boolean =
        rocks.getOrNull(index)?.let { it.active && it.generation == generation } ?: false

    internal fun tileOf(index: Int): Location? = rocks.getOrNull(index)?.tile

    @JvmStatic
    fun onLeaveLobby(player: Player) {
        if (player.actionManager.action is AscensionMiningAction) player.actionManager.forceStop()
        Sprites.forget(player)
    }

    fun describe(): List<String> = listOf(
        "Ascension rocks: ${rocks.size}, active ${rocks.filter { it.active }.joinToString { "#${it.index}" }}, " +
            "cycle $cycleId ends in ${cycleEnd - LobbyClock.queue.now} ticks",
    )
}

/** "Mine" on the active rocks. Plugin-scanned. */
class AscensionRockAction : ObjectAction {
    override fun handleObjectAction(player: Player, `object`: WorldObject, name: String, optionId: Int, option: String) {
        if (!option.equals("Mine", ignoreCase = true)) return
        val (index, generation) = AscensionRocks.activeAt(`object`.position) ?: return
        player.actionManager.setAction(AscensionMiningAction(index, generation, `object`))
    }

    override fun getObjects(): Array<Any> = TfaIds.Loc.ASCENSION_ROCKS_ACTIVE.toList().toTypedArray<Any>()
}

/**
 * Mining one active Ascension rock: a roll every `rollIntervalTicks` (fixed, so tick manipulation does nothing [DOC]),
 * success → 1 shard and 15 xp (or a rare uncut gem pre-roll). Mining enhancers are not consulted [DOC]. The player's
 * pickaxe animation is the engine's stock one for that pickaxe. Stops when the rock sinks.
 */
class AscensionMiningAction(
    private val rockIndex: Int,
    private val generation: Int,
    private val rock: WorldObject,
) : Action() {

    private var tool: PickAxeDefinition? = null
    private var ticks = 0

    override fun start(): Boolean {
        val cfg = TfaConfig.current[LobbyConfig].rocks
        if (player.skills.getLevel(SkillConstants.MINING) < cfg.levelRequired) {
            player.dialogueManager.start(PlainChat(player, "You need a Mining level of ${cfg.levelRequired} to mine this rock."))
            return false
        }
        val result = PickaxeDefinitions.get(player, true)
        if (result.isEmpty) {
            player.dialogueManager.start(PlainChat(player, "You need a pickaxe to mine this rock. You do not have a pickaxe which you have the Mining level to use."))
            return false
        }
        tool = result.get().definition
        if (!hasSpace()) {
            player.sendMessage("Your inventory is too full to hold any more ascension shards.")
            return false
        }
        player.sendMessage("You swing your pick at the rock.")
        player.faceObject(rock)
        Sprites.onMiningStarted(player, rockIndex, generation)
        delay(cfg.rollIntervalTicks)
        return true
    }

    override fun process(): Boolean {
        if (!AscensionRocks.stillActive(rockIndex, generation)) {
            player.setAnimation(Animation.STOP)
            return false
        }
        if (!hasSpace()) {
            player.sendMessage("Your inventory is too full to hold any more ascension shards.")
            return false
        }
        if (ticks++ % 4 == 0) tool?.anim?.let { player.setAnimation(it) }
        return true
    }

    override fun processWithDelay(): Int {
        val cfg = TfaConfig.current[LobbyConfig].rocks
        val chance = AscensionMining.successChance(player.skills.getLevel(SkillConstants.MINING), tool?.level ?: 1, cfg)
        if (AscensionRocks.random.nextDouble() < chance) {
            when (AscensionMining.rollReward(AscensionRocks.random, cfg)) {
                AscensionMining.Reward.GEM -> {
                    val gems = TfaIds.StockObj.UNCUT_GEMS
                    val gem = gems[AscensionRocks.random.nextInt(gems.size)]
                    player.inventory.addOrDrop(Item(gem))
                    player.sendMessage("You just found an uncut gem!")
                }
                AscensionMining.Reward.SHARD -> {
                    player.inventory.addOrDrop(Item(TfaIds.Obj.ASCENSION_SHARDS))
                    player.skills.addXp(SkillConstants.MINING, cfg.xpPerShard.toDouble())
                    player.sendFilteredMessage("You manage to mine an ascension shard.")
                }
            }
        }
        Sprites.onRoll(player)
        return cfg.rollIntervalTicks
    }

    override fun stop() {
        player.setAnimation(Animation.STOP)
    }

    private fun hasSpace(): Boolean =
        player.inventory.containsItem(TfaIds.Obj.ASCENSION_SHARDS, 1) || player.inventory.hasFreeSlots()
}

/**
 * The sprite npc 16691 (stand 15062, walk 15061) [CACHE; DOC wiki Sprite]: one per depletion cycle per miner, at a
 * random time; Look-at gives 1-2 shards and 50-89 xp with "The sprite shatters, giving you some ascension shards.",
 * playing `tfa_gathering_sprite_explode` 15063 (60 cycles = 2 ticks, same skeleton 2234 as its stand and walk
 * [CACHE SkelCheck]); unlooked it disappears after 30 s (50 ticks). Mining resumes after the Look-at [DOC: it does
 * not interrupt mining].
 */
object Sprites {

    private class Plan(val cycleId: Int, val tick: Long, var spawned: Boolean = false)

    private val plans = HashMap<Player, Plan>()
    private val owners = HashMap<NPC, Player>()
    private val mining = HashMap<Player, Pair<Int, Int>>()

    internal fun onMiningStarted(player: Player, rockIndex: Int, generation: Int) {
        mining[player] = rockIndex to generation
        plan(player)
    }

    private fun plan(player: Player): Plan {
        val existing = plans[player]
        if (existing != null && existing.cycleId == AscensionRocks.cycleId) return existing
        val now = LobbyClock.queue.now
        val tick = AscensionMining.spriteTick(now, AscensionRocks.cycleEnd.coerceAtLeast(now + 1), AscensionRocks.random)
        return Plan(AscensionRocks.cycleId, tick).also { plans[player] = it }
    }

    internal fun onRoll(player: Player) {
        val cfg = TfaConfig.current[LobbyConfig].sprite
        if (!cfg.enabled) return
        val plan = plan(player)
        if (plan.spawned || LobbyClock.queue.now < plan.tick) return
        plan.spawned = true
        spawn(player, cfg)
    }

    private fun spawn(player: Player, cfg: LobbyConfig.Sprite) {
        val origin = player.location
        val candidates = ArrayList<Location>()
        for (dx in -cfg.spawnRadius..cfg.spawnRadius) for (dy in -cfg.spawnRadius..cfg.spawnRadius) {
            if (dx == 0 && dy == 0) continue
            if (World.isFloorFree(origin.plane, origin.x + dx, origin.y + dy)) candidates += Location(origin.x + dx, origin.y + dy, origin.plane)
        }
        if (candidates.isEmpty()) return
        val tile = candidates[AscensionRocks.random.nextInt(candidates.size)]
        val npc = NPC(TfaIds.Npc.SPRITE, tile, Direction.SOUTH, 0)
        npc.spawn()
        owners[npc] = player
        LobbyClock.queue.schedule(cfg.despawnTicks, tag(npc)) { despawn(npc) }
    }

    internal fun lookAt(player: Player, npc: NPC) {
        val owner = owners[npc]
        if (owner == null || npc.isFinished) return
        if (owner != player) {
            player.sendMessage("The sprite doesn't seem interested in you.")
            return
        }
        owners.remove(npc)
        LobbyClock.queue.cancel(tag(npc))
        val cfg = TfaConfig.current[LobbyConfig].sprite
        val shards = cfg.shards.first + AscensionRocks.random.nextInt(cfg.shards.last - cfg.shards.first + 1)
        val xp = cfg.xp.first + AscensionRocks.random.nextInt(cfg.xp.last - cfg.xp.first + 1)
        npc.setAnimation(Animation(TfaIds.Seq.SPRITE_EXPLODE)) // tfa_gathering_sprite_explode, 2 ticks
        player.inventory.addOrDrop(Item(TfaIds.Obj.ASCENSION_SHARDS, shards))
        player.skills.addXp(SkillConstants.MINING, xp.toDouble())
        player.sendMessage("The sprite shatters, giving you some ascension shards.")
        LobbyClock.queue.schedule(2, tag(npc)) { if (!npc.isFinished) npc.finish() }
        resumeMining(player)
    }

    private fun resumeMining(player: Player) {
        val (index, generation) = mining[player] ?: return
        if (!AscensionRocks.stillActive(index, generation)) return
        val tile = AscensionRocks.tileOf(index) ?: return
        val obj = World.getObjectWithType(tile, 10) ?: return
        if (player.location.getTileDistance(tile) > 1) return
        player.actionManager.setAction(AscensionMiningAction(index, generation, obj))
    }

    private fun despawn(npc: NPC) {
        owners.remove(npc)
        if (!npc.isFinished) npc.finish()
    }

    internal fun forget(player: Player) {
        plans.remove(player)
        mining.remove(player)
        owners.filterValues { it == player }.keys.toList().forEach(::despawn)
    }

    private fun tag(npc: NPC) = "sprite:${System.identityHashCode(npc)}"
}

/** "Look-at" on the sprite 16691. Plugin-scanned. */
class SpritePlugin : NPCPlugin() {
    override fun handle() {
        bind("Look-at") { player, npc -> Sprites.lookAt(player, npc) }
    }

    override fun getNPCs(): IntArray = intArrayOf(TfaIds.Npc.SPRITE)
}
