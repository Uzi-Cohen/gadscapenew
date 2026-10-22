package com.gadscape.raids.tfa.rooms.xarth

import com.gadscape.raids.tfa.config.TfaConfigTestKit
import com.gadscape.raids.tfa.raid.StaticTile
import java.util.Random

/** The bundled `xarth.json` (= the code defaults, checked by [XarthConfigTest]). */
fun xarthConfig(): XarthConfig = TfaConfigTestKit.load(listOf(XarthConfig))[XarthConfig]

/** A player for the boss script. */
class XFighter(var at: StaticTile, val name: String = "p") : Fighter {
    var isAlive = true
    override val tile: StaticTile get() = at
    override val alive: Boolean get() = isAlive
    override fun toString() = "XFighter($name@$at)"
}

/** A recording [XarthHost]: every call is logged as `"<tick> <what> <args>"`; boss HP is a plain counter. */
class XHost(var max: Int = 2600) : XarthHost {
    lateinit var timeline: Timeline
    var hp = max
    val fighters = ArrayList<XFighter>()
    val log = ArrayList<String>()
    val attacks = ArrayList<Triple<Int, Fighter, AttackStyle>>()
    val damage = ArrayList<Triple<Int, Fighter, Int>>()
    val burns = ArrayList<Triple<Int, Fighter, Int>>()
    val collapsed = ArrayList<Pillar>()
    val flames = LinkedHashSet<StaticTile>()
    val corpses = ArrayList<Pile>()
    var corpsesAlive = 0
    var despawned = false
    var accurate = true

    private val now: Int get() = timeline.now

    private fun rec(s: String) {
        log += "$now $s"
    }

    /** Ticks at which [entry] (e.g. `"anim 14991"`) was logged. */
    fun ticksOf(entry: String): List<Int> = log.filter { it.substringAfter(' ') == entry }.map { it.substringBefore(' ').toInt() }

    fun has(tick: Int, entry: String): Boolean = "$tick $entry" in log

    override val bossHp: Int get() = hp
    override val bossMaxHp: Int get() = max

    override fun bossAnimate(seq: Int) = rec("anim $seq")
    override fun bossGraphic(spot: Int) = rec("spot $spot")
    override fun bossFace(target: Fighter) = rec("face ${(target as XFighter).name}")
    override fun bossFaceTile(tile: StaticTile) = rec("faceTile $tile")

    override fun bossHeal(amount: Int): Int {
        val healed = minOf(amount, max - hp).coerceAtLeast(0)
        hp += healed
        rec("heal $healed")
        return healed
    }

    override fun bossTargetable(targetable: Boolean) = rec("targetable $targetable")

    override fun bossDespawn() {
        despawned = true
        rec("despawn")
    }

    override fun fighters(): List<Fighter> = fighters.toList()

    override fun attack(target: Fighter, style: AttackStyle, max: Int): Boolean {
        attacks += Triple(now, target, style)
        rec("attack ${(target as XFighter).name} $style $max")
        return accurate
    }

    override fun damage(target: Fighter, amount: Int): Int {
        damage += Triple(now, target, amount)
        rec("damage ${(target as XFighter).name} $amount")
        return amount
    }

    override fun burn(target: Fighter, stacks: Int) {
        burns += Triple(now, target, stacks)
        rec("burn ${(target as XFighter).name} $stacks")
    }

    override fun bind(target: Fighter, ticks: Int) = rec("bind ${(target as XFighter).name} $ticks")
    override fun holdBind(target: Fighter) {}
    override fun release(target: Fighter) = rec("release ${(target as XFighter).name}")
    override fun playerGraphic(target: Fighter, spot: Int) = rec("pspot ${(target as XFighter).name} $spot")
    override fun tileGraphic(spot: Int, tile: StaticTile) = rec("tspot $spot $tile")
    override fun projectile(kind: Proj, target: Fighter) = rec("proj $kind ${(target as XFighter).name}")
    override fun projectile(kind: Proj, tile: StaticTile) = rec("proj $kind $tile")
    override fun losBlocked(target: Fighter): Boolean = false

    override fun collapsePillar(pillar: Pillar) {
        collapsed += pillar
        rec("collapse $pillar")
    }

    override fun pileFire(pile: Pile, burning: Boolean) = rec("pile $pile $burning")

    override fun spawnCorpse(pile: Pile) {
        corpses += pile
        corpsesAlive++
        rec("corpse $pile")
    }

    override fun corpsesAlive(): Int = corpsesAlive

    override fun killCorpses() {
        corpsesAlive = 0
        rec("killCorpses")
    }

    override fun flameSpawn(tile: StaticTile) {
        flames += tile
        rec("flame+ $tile")
    }

    override fun flameRemove(tile: StaticTile) {
        flames -= tile
        rec("flame- $tile")
    }
}

/** A brain over [XHost] with its own timeline; [run] advances whole room ticks (timeline, decisions, hazards). */
class XRig(val cfg: XarthConfig = xarthConfig(), var scale: Int = 2, seed: Long = 7L) {
    val timeline = Timeline()
    val host = XHost(2600).also { it.timeline = timeline }
    val brain = XarthBrain(cfg, timeline, Random(seed), host) { scale }

    fun add(name: String, tile: StaticTile): XFighter = XFighter(tile, name).also { host.fighters += it }

    fun tick() {
        timeline.advance()
        brain.tick()
        brain.tickHazards()
    }

    fun run(ticks: Int) = repeat(ticks) { tick() }

    /** Runs until [cond] holds (at most [limit] ticks); returns the tick it held at. */
    fun runUntil(limit: Int = 200, cond: () -> Boolean): Int {
        repeat(limit) {
            tick()
            if (cond()) return timeline.now
        }
        error("condition not reached in $limit ticks (now ${timeline.now}, state ${brain.state})")
    }

    /** Sets his HP to [pct]% of max and checks thresholds, as a processed hit would. */
    fun hpTo(pct: Int) {
        host.hp = host.max * pct / 100
        brain.checkThresholds()
    }

    companion object {
        /** West of him, nothing in between: always exposed. */
        val WEST_OPEN = StaticTile(4185, 5535, 0)

        /** North-east, behind pillar B (4193..4194, 5540..5541) on the ray from his centre. */
        val BEHIND_B = StaticTile(4195, 5543, 0)

        /** Orthogonally adjacent to his 3x3 (west side). */
        val MELEE_WEST = StaticTile(4189, 5535, 0)
    }
}
