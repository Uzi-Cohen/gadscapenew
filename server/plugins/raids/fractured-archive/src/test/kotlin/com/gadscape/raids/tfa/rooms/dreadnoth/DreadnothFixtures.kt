package com.gadscape.raids.tfa.rooms.dreadnoth

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/** A player for the brain tests. */
class FakeFighter(
    val name: String,
    override var tile: StaticTile,
    override var alive: Boolean = true,
) : Fighter {
    val prayers = mutableSetOf<Protect>()
    override fun praying(protect: Protect): Boolean = protect in prayers
    override fun toString() = name
}

/** One recorded host call. */
data class Ev(val tick: Int, val kind: String, val target: Fighter? = null, val a: Int = 0, val b: Int = 0, val tile: StaticTile? = null, val style: AttackStyle? = null)

/**
 * A recording [DreadnothHost] that also simulates the boss's position (one step per [bossStep], clamped to the box),
 * HP (heals) and the arena's fighters.
 */
class FakeHost(var maxHp: Int, var sw: StaticTile = StaticTile(4189, 5661, 0)) : DreadnothHost {
    lateinit var timeline: Timeline
    val fighters = ArrayList<FakeFighter>()
    val events = ArrayList<Ev>()
    var hp: Int = maxHp
    var targetable = true
    var despawned = false

    private fun rec(kind: String, target: Fighter? = null, a: Int = 0, b: Int = 0, tile: StaticTile? = null, style: AttackStyle? = null) {
        events += Ev(timeline.now, kind, target, a, b, tile, style)
    }

    fun of(kind: String) = events.filter { it.kind == kind }

    override val bossSw: StaticTile get() = sw
    override val bossHp: Int get() = hp
    override val bossMaxHp: Int get() = maxHp

    override fun bossAnimate(seq: Int) = rec("anim", a = seq)
    override fun bossFace(target: Fighter?) {}
    override fun bossStep(dx: Int, dy: Int): Boolean {
        val next = sw.offset(dx, dy)
        if (!DreadnothArena.footprintInBox(next)) return false
        sw = next
        rec("step", a = dx, b = dy, tile = next)
        return true
    }
    override fun bossHalt() {}
    override fun bossLeap(from: StaticTile, to: StaticTile, startCycle: Int, endCycle: Int) {
        sw = to
        rec("leap", a = startCycle, b = endCycle, tile = to)
    }
    override fun bossTargetable(targetable: Boolean) {
        this.targetable = targetable
        rec("targetable", a = if (targetable) 1 else 0)
    }
    override fun bossHeal(amount: Int) {
        hp = minOf(maxHp, hp + amount)
        rec("heal", a = amount)
    }
    override fun bossDespawn() {
        despawned = true
        rec("despawn")
    }

    override fun fighters(): List<Fighter> = fighters.toList()
    override fun attack(target: Fighter, style: AttackStyle, max: Int) = rec("attack", target, a = max, style = style)
    override fun damage(target: Fighter, amount: Int): Int {
        rec("damage", target, a = amount)
        return amount
    }
    override fun graphic(spot: Int, tile: StaticTile) = rec("graphic", a = spot, tile = tile)
    override fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, target: Fighter) = rec("projectile", target, a = spot)
    override fun projectile(spot: Int, spec: DreadnothConfig.ProjectileSpec, tile: StaticTile) = rec("projectileTile", a = spot, tile = tile)
    override fun blood(tile: StaticTile) = rec("blood", tile = tile)
    override fun spawnJellies(count: Int) = rec("jellies", a = count)
    override fun spawnDeathWave(count: Int) = rec("deathWave", a = count)
    override fun onEnrage() = rec("enrage")
}

/** The brain, its timeline and a [FakeHost], ticked like the room does. */
class Harness(
    val cfg: DreadnothConfig = DEFAULT_CONFIG,
    maxHp: Int = 2100,
    scale: Int = 2,
    seed: Long = 7L,
) {
    val timeline = Timeline()
    val host = FakeHost(maxHp).also { it.timeline = timeline }
    val brain = DreadnothBrain(cfg, timeline, Random(seed), host, scale)

    fun add(name: String, x: Int, y: Int): FakeFighter = FakeFighter(name, StaticTile(x, y, 0)).also { host.fighters += it }

    fun tick(n: Int = 1) {
        repeat(n) {
            timeline.advance()
            brain.tick()
        }
    }

    /** Sets the HP to [pct]% of max (rounded down) and lets the brain see it, as a processed hit would. */
    fun hpTo(pct: Int) {
        host.hp = (host.maxHp.toLong() * pct / 100).toInt()
        brain.checkThresholds()
    }

    val now: Int get() = timeline.now

    companion object {
        /** The bundled `dreadnoth.json` (= the code defaults). */
        val DEFAULT_CONFIG: DreadnothConfig by lazy { TfaConfigTestKit.load(listOf(DreadnothConfig))[DreadnothConfig] }
    }
}
