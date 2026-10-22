package com.gadscape.raids.tfa.rooms.bork

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/** A player for the brain tests. */
class FakeFighter(override val name: String, override var tile: StaticTile, override var alive: Boolean = true) : Fighter {
    override fun toString() = name
}

/** One recorded host call. */
data class Ev(
    val tick: Int,
    val kind: String,
    val target: Fighter? = null,
    val a: Int = 0,
    val b: Int = 0,
    val tile: StaticTile? = null,
    val text: String? = null,
)

/**
 * A recording [BorkHost]: Bork's position ([sw], moved only when [chaseMoves]), both HP pools (cannonball damage to
 * Bork is applied), the arena's fighters.
 */
class FakeHost(
    var borkMax: Int = 1500,
    var smorksMax: Int = 600,
    var sw: StaticTile? = StaticTile(4194, 5597, 0),
) : BorkHost {
    lateinit var timeline: Timeline
    val fighters = ArrayList<FakeFighter>()
    val events = ArrayList<Ev>()
    var bork = borkMax
    var smorksHpNow = smorksMax
    var chaseMoves = false
    var dazedFlag = false
    var primary: Fighter? = null

    private fun rec(kind: String, target: Fighter? = null, a: Int = 0, b: Int = 0, tile: StaticTile? = null, text: String? = null) {
        events += Ev(if (::timeline.isInitialized) timeline.now else -1, kind, target, a, b, tile, text)
    }

    fun of(kind: String) = events.filter { it.kind == kind }
    fun clear() = events.clear()

    override val borkSw: StaticTile? get() = sw
    override val borkHp: Int get() = bork
    override val borkMaxHp: Int get() = borkMax
    override fun borkAnimate(seq: Int) = rec("borkAnim", a = seq)
    override fun borkSpot(spot: Int) = rec("borkSpot", a = spot)
    override fun borkSpotAtCentre(spot: Int) = rec("borkSpotCentre", a = spot)
    override fun borkSay(text: String) = rec("borkSay", text = text)
    override fun borkFace(target: Fighter?) {}
    override fun borkChase(target: Fighter) {
        rec("chase", target)
        val s = sw ?: return
        if (!chaseMoves) return
        val dx = Integer.signum(target.tile.x - (s.x + 1))
        val dy = Integer.signum(target.tile.y - (s.y + 1))
        val next = s.offset(dx, dy)
        if (BorkArena.footprintWalkable(next, BorkArena.BORK_SIZE)) sw = next
    }
    override fun borkHalt() {}
    override fun borkDazed(dazed: Boolean) {
        dazedFlag = dazed
        rec("dazed", a = if (dazed) 1 else 0)
    }
    override fun borkHit(target: Fighter, kind: BorkHit, max: Int, rollAccuracy: Boolean) =
        rec("borkHit", target, a = max, b = if (rollAccuracy) 1 else 0, text = kind.name)
    override fun borkDamage(amount: Int) {
        bork = maxOf(0, bork - amount)
        rec("borkDamage", a = amount)
    }
    override fun borkTargetable(targetable: Boolean) = rec("borkTargetable", a = if (targetable) 1 else 0)
    override fun borkDespawn() {
        sw = null
        rec("borkDespawn")
    }
    override fun primaryTarget(target: Fighter?) {
        primary = target
        rec("primary", target)
    }

    override val smorksHp: Int get() = smorksHpNow
    override val smorksMaxHp: Int get() = smorksMax
    override fun smorksAnimate(seq: Int) = rec("smorksAnim", a = seq)
    override fun smorksSpot(spot: Int) = rec("smorksSpot", a = spot)
    override fun smorksSpotAtCentre(spot: Int, delayCycles: Int) = rec("smorksSpotCentre", a = spot, b = delayCycles)
    override fun smorksSay(text: String) = rec("smorksSay", text = text)
    override fun smorksFace(target: Fighter?) {}
    override fun smorksProjectile(spot: Int, spec: BorkSmorksConfig.ProjectileSpec, target: Fighter): Int {
        rec("projectile", target, a = spot)
        return spec.flightTicks(BorkArena.chebyshev(StaticTile(4191, 5599, 0), target.tile))
    }
    override fun smorksLob(spot: Int, spec: BorkSmorksConfig.ArcSpec, tile: StaticTile, flightTicks: Int) =
        rec("lob", a = spot, b = flightTicks, tile = tile)
    override fun smorksVolleyHit(target: Fighter, style: VolleyStyle, max: Int) = rec("volleyHit", target, a = max, text = style.name)
    override fun smorksTargetable(targetable: Boolean) = rec("smorksTargetable", a = if (targetable) 1 else 0)
    override fun smorksDespawn() = rec("smorksDespawn")

    override fun fighters(): List<Fighter> = fighters.toList()
    override fun graphic(spot: Int, tile: StaticTile, delayCycles: Int) = rec("graphic", a = spot, b = delayCycles, tile = tile)
    override fun damage(target: Fighter, amount: Int) = rec("damage", target, a = amount)
    override fun burn(target: Fighter, stacks: Int) = rec("burn", target, a = stacks)
    override fun oilSpawn(tile: StaticTile, rotation: Int) = rec("oilSpawn", a = rotation, tile = tile)
    override fun oilFade(tile: StaticTile, rotation: Int) = rec("oilFade", a = rotation, tile = tile)
    override fun oilRemove(tile: StaticTile) = rec("oilRemove", tile = tile)
}

/** A brain over a [FakeHost] with the code defaults (optionally changed by [tune]). */
class Harness(
    tune: (BorkSmorksConfig) -> BorkSmorksConfig = { it },
    seed: Long = 7L,
    val host: FakeHost = FakeHost(),
) {
    val cfg: BorkSmorksConfig = tune(DEFAULT_CONFIG)
    val timeline = Timeline()
    val shadow = CartShadow(CartShadow.Mode.of(cfg.shadow.mode), Bridge::line)
    val brain: BorkSmorksBrain

    init {
        host.timeline = timeline
        brain = BorkSmorksBrain(cfg, timeline, Random(seed), host, shadow)
    }

    fun fighter(name: String, x: Int, y: Int): FakeFighter = FakeFighter(name, StaticTile(x, y, 0)).also { host.fighters += it }

    /** One server tick: the timeline, then the brain (the room's order). */
    fun tick() {
        timeline.advance()
        brain.tick()
    }

    fun run(ticks: Int) = repeat(ticks) { tick() }

    /** Runs until [condition] or [limit] ticks; returns the tick it became true. */
    fun runUntil(limit: Int = 500, condition: () -> Boolean): Int {
        repeat(limit) {
            tick()
            if (condition()) return timeline.now
        }
        error("condition not met in $limit ticks")
    }

    companion object {
        /** The code defaults (no files), which the bundled JSON must equal. */
        val DEFAULT_CONFIG: BorkSmorksConfig by lazy {
            TfaConfigTestKit.load(listOf(BorkSmorksConfig), bundled = emptyMap())[BorkSmorksConfig]
        }
    }
}
