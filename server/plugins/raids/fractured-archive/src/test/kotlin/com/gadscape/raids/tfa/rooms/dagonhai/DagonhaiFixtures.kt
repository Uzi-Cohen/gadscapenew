package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/** A player for the fight script: a tile, alive or not, and its protection prayers. */
class FakeFighter(var tile0: StaticTile, val name: String = "p") : Fighter {
    var isAlive = true
    val prayers = HashSet<Protect>()
    override val tile: StaticTile get() = tile0
    override val alive: Boolean get() = isAlive
    override fun praying(protect: Protect): Boolean = protect in prayers
    override fun toString() = "FakeFighter($name@$tile0)"
}

/**
 * A recording [DagonhaiHost]: every call is logged as a string (`"anim ELDER 14593"`), attacks and damage are kept
 * in lists, walks arrive at once, imps are counted.
 */
class FakeHost(private val cfg: DagonhaiConfig) : DagonhaiHost {
    val fighters = ArrayList<FakeFighter>()
    val log = ArrayList<String>()
    val tiles = HashMap<Role, StaticTile>()
    val attacks = ArrayList<Triple<Role, Fighter, Style>>()
    val damage = ArrayList<Pair<Fighter, Int>>()
    val burns = ArrayList<Pair<Fighter, Int>>()
    val heals = ArrayList<Triple<Fighter, Int, Int>>()
    var pool = -1
    var poolMax = -1
    var shield: ShieldState? = null
    var empowered = false
    var hud = IntArray(4)
    var impsAlive = 0
    var impsSpawned = 0
    val impTargets = ArrayList<Fighter?>()
    val riftTiles = ArrayList<StaticTile>()
    val openRifts = HashSet<Int>()
    val despawned = ArrayList<Role>()
    val unshielded = HashMap<Role, Int>()
    val killed = ArrayList<Role>()
    var cauldronGone = false
    var explodeModel = false
    /** Walks complete at once (set false to keep monks walking). */
    var instantWalk = true

    init {
        for (r in Role.entries) tiles[r] = cfg.arena.spawn(r)
    }

    fun has(entry: String): Boolean = log.contains(entry)
    fun count(prefix: String): Int = log.count { it.startsWith(prefix) }

    override fun fighters(): List<Fighter> = fighters.toList()
    override fun trioTile(role: Role): StaticTile? = tiles[role]
    override fun trioAnimate(role: Role, seq: Int) { log += "anim $role $seq" }
    override fun trioGraphic(role: Role, spot: Int) { log += "gfx $role $spot" }
    override fun trioSay(role: Role, text: String) { log += "say $role $text" }
    override fun trioFace(role: Role, target: Fighter) { log += "face $role" }
    override fun trioFaceTile(role: Role, tile: StaticTile) { log += "faceTile $role $tile" }
    override fun trioWalk(role: Role, tile: StaticTile) {
        log += "walk $role $tile"
        if (instantWalk) tiles[role] = tile
    }
    override fun trioMoving(role: Role): Boolean = !instantWalk
    override fun trioStop(role: Role) { log += "stop $role" }
    override fun trioPlace(role: Role, tile: StaticTile) {
        log += "place $role $tile"
        tiles[role] = tile
    }
    override fun trioPool(pool: Int, max: Int) {
        this.pool = pool
        this.poolMax = max
    }
    override fun trioShield(state: ShieldState) {
        shield = state
        log += "shield $state"
    }
    override fun trioEmpowered(empowered: Boolean) {
        this.empowered = empowered
        log += "empowered $empowered"
    }
    override fun chargeBar(role: Role, ticks: Int) { log += "charge $role $ticks" }
    override fun trioUnshield(role: Role, hp: Int) {
        unshielded[role] = hp
        log += "unshield $role $hp"
    }
    override fun trioKill(role: Role) {
        killed += role
        log += "kill $role"
    }
    override fun trioDespawn(role: Role) {
        despawned += role
        log += "despawn $role"
    }
    override fun trioAttack(role: Role, target: Fighter, style: Style, max: Int) {
        attacks += Triple(role, target, style)
        log += "attack $role $style $max"
    }
    override fun cauldronAnimate(seq: Int) { log += "cauldronAnim $seq" }
    override fun cauldronGraphic(spot: Int) { log += "cauldronGfx $spot" }
    override fun cauldronExplodeModel() {
        explodeModel = true
        log += "cauldronModel"
    }
    override fun cauldronDespawn() {
        cauldronGone = true
        log += "cauldronDespawn"
    }
    override fun ritualHud(stability: Int, stabilityMax: Int, ritual: Int, ritualMax: Int) {
        hud = intArrayOf(stability, stabilityMax, ritual, ritualMax)
    }
    override fun bookAnimate(seq: Int) { log += "book $seq" }
    override fun damage(target: Fighter, amount: Int, burn: Boolean) {
        if (burn) burns += target to amount else damage += target to amount
    }
    override fun heal(target: Fighter, hp: Int, prayer: Int) { heals += Triple(target, hp, prayer) }
    override fun playerGraphic(target: Fighter, spot: Int, height: Int) { log += "pgfx $spot" }
    override fun graphic(spot: Int, tile: StaticTile, height: Int, delayCycles: Int) { log += "map $spot $tile" }
    override fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, target: Fighter) { log += "proj ${from ?: "CAULDRON"} $spot fighter" }
    override fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, tile: StaticTile) { log += "proj ${from ?: "CAULDRON"} $spot $tile" }
    override fun spawnRift(tile: StaticTile): Int {
        riftTiles += tile
        openRifts += riftTiles.size - 1
        return riftTiles.size - 1
    }
    override fun despawnRift(handle: Int) { openRifts -= handle }
    override fun spawnImp(tile: StaticTile, target: Fighter?) {
        impsAlive++
        impsSpawned++
        impTargets += target
    }
    override fun impsAlive(): Int = impsAlive
    override fun despawnAdds() {
        impsAlive = 0
        openRifts.clear()
    }
}

/** A fight with a recording host, a seeded random and the default config (optionally overridden). */
class FightFixture(seed: Long = 1, overrides: String? = null, val poolMax: Int = 640) {
    val cfg: DagonhaiConfig = TfaConfigTestKit.load(
        listOf(DagonhaiConfig),
        overrides = if (overrides == null) emptyMap() else mapOf("dagonhai.json" to overrides),
    )[DagonhaiConfig]
    val timeline = Timeline()
    val host = FakeHost(cfg)
    val fight = DagonhaiFight(cfg, timeline, Random(seed), host, poolMax)

    /** One room tick, in [DagonhaiRoom.tick]'s order. */
    fun tick() {
        timeline.step()
        fight.preTick()
        timeline.runDue()
        fight.tick()
    }

    fun run(ticks: Int) = repeat(ticks) { tick() }

    /** Ticks until [condition] holds (at most [max] ticks); returns the ticks it took. */
    fun runUntil(max: Int = 500, condition: () -> Boolean): Int {
        var n = 0
        while (!condition()) {
            check(n < max) { "condition not met in $max ticks (phase ${fight.phase})" }
            tick()
            n++
        }
        return n
    }

    fun player(x: Int, y: Int, name: String = "p${host.fighters.size}"): FakeFighter =
        FakeFighter(StaticTile(x, y), name).also { host.fighters += it }

    /** The room's part of a break: the pool reaches 0 on a hit. */
    fun depletePool() {
        fight.onPoolHit(0)
        fight.onPoolDepleted()
    }

    /** Kills every imp the protection dropped so far. */
    fun killImps(killer: Fighter? = null) {
        repeat(host.impsAlive) { fight.onImpDeath(killer) }
        host.impsAlive = 0
    }
}
