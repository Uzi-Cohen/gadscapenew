package com.gadscape.raids.tfa.rooms.ocellus

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities

/**
 * Phase 1, Ocellus 16674 (spec §4.8 "Phase 1"; WU-27).
 *
 * Cycle: walk to the next quadrant anchor (SW → NW → NE → SE, repeat) with Protect from Melee up while walking
 * (icon cleared on arrival) → at the anchor channel 7 tendrils, one every 3 t, each living 4 t → slam (Chebyshev 2
 * of his 3x3, up to 35) → next anchor. Every 5 t he fires an auto at a random player, 50/50 ranged ("orange",
 * fire) or magic ("blue", water), max 30, blocked fully by the matching protection prayer.
 *
 * At 66% and 25% of the P1 pool [DOC; `p1.thresholds`]: teleport out / in at the centre, 4 rounds of 2 tendrils
 * clockwise from N with a slam after each pair, then charge and release a projectile at every player: meter ≥ 50
 * "Embrace... annihilation..." 80-90, else "Death... awaits..." 17-18 [DOC]; the meter resets and he walks on.
 * At 0 HP the room moves to [P1ToP2].
 *
 * Animations, all on 16674's skeleton 2742 (same as its stand 14804 / walk 14805) [CACHE, checked with
 * `raids4-impl/ocellus-tools/seqskel.py`; lengths from `raids4/ocellus/seq_ocellus.txt`]:
 * 14806 projectile start (3 t loop, walkmerge) / 14807 end (1 t, walkmerge); 14809 charge (4 t loop);
 * 14810 expend (3 t); 14811 explode = slam (2 t, impact sound at 0.83 t); 14812 / 14813 teleport out / in (2 t);
 * 14814 transformation (7 t, [P1ToP2]). Not used: 14808 `railgun`, whose keyframe set is the idle's
 * (keyframeset 65470464 = 14804's), i.e. a placeholder pose.
 * Graphics [CACHE spots]: ranged cast 4231 (2 t) → projectile 4232 → impact 4233; magic cast 4228 (2 t) →
 * projectile 4229 → impact 4230; channel 4260 (2 t, resent); slam 4261 on him + floor 4262-4265; teleport out
 * 4266 / in 4267; release projectile 4234.
 */
internal class OcellusP1(room: OcellusRoom) : PhaseController(room, OcellusPhase.P1) {

    private val cfg = room.cfg.p1
    private val boss: OcellusNpc get() = room.boss ?: error("P1 without a boss")
    private val tendrils = TendrilController(room)
    private val thresholds = ThresholdTracker(cfg.thresholds)
    private val anchors: List<Quadrant> = cfg.anchors.map(Quadrant::byName)

    private enum class Mode { WALKING, CHANNEL, CENTRE, IDLE }

    private var mode = Mode.IDLE
    private var anchorCursor = 0
    private var walkTarget: StaticTile? = null
    private var walkStartedAt = 0
    private var channelling = false
    /** No auto attacks before this tick (slams, teleports, the release). */
    private var quietUntil = 0

    val meter: Int get() = tendrils.meter

    override fun begin() {
        Bridge.meterShow(room.viewers(), 0, cfg.meterMax)
        tendrils.setMeter(0)
        walkTo(0)
        timeline.every(cfg.speed, cfg.firstAutoDelay, "$tag.auto") { autoAttack() }
    }

    override fun tick() {
        if (mode == Mode.WALKING) stepWalk()
    }

    override fun onArenaEntry(player: Player) {
        val npc = room.boss ?: return
        Bridge.hudShown(npc, listOf(player))
        Bridge.meterShow(listOf(player), tendrils.meter, cfg.meterMax)
    }

    override fun onPlayerDeath(player: Player) {
        tendrils.forget(player)
    }

    override fun onBossHp(before: Int, after: Int) {
        if (after <= 0) return
        val crossed = thresholds.crossed(before, after, boss.maxHitpoints)
        if (crossed.isNotEmpty() && mode != Mode.CENTRE) startCentre()
    }

    override fun onBossZeroHp(): Boolean {
        room.enter(P1ToP2(room))
        return true
    }

    override fun end() {
        super.end()
        tendrils.settle()
        channelling = false
        mode = Mode.IDLE
        room.boss?.let { setPfm(it, false); it.resetWalkSteps() }
        Bridge.meterHide(room.viewers())
    }

    // ---- walking ------------------------------------------------------------------------------------------------

    private fun walkTo(cursor: Int) {
        anchorCursor = ((cursor % anchors.size) + anchors.size) % anchors.size
        walkTarget = room.anchorTile(anchors[anchorCursor])
        walkStartedAt = timeline.now
        mode = Mode.WALKING
        channelling = false
        setPfm(boss, true)
    }

    private fun stepWalk() {
        val npc = room.boss ?: return
        val target = walkTarget ?: return
        val here = room.fx.static(npc.location) ?: return
        if (here.x == target.x && here.y == target.y) {
            arrive()
            return
        }
        if (npc.isFrozen) {
            // The Scorching bow bind holds him (p1.bindable, COMM: teams chained binds so he never reached a
            // quadrant); a bind pauses the stuck timer.
            npc.resetWalkSteps()
            walkStartedAt = timeline.now
            return
        }
        if (timeline.now - walkStartedAt > cfg.walkTimeout) {
            // Stuck: put him on the anchor; arrival is seen next tick.
            npc.resetWalkSteps()
            room.fx.teleport(npc, target)
            walkStartedAt = timeline.now
            return
        }
        if (!npc.hasWalkSteps()) {
            val l = room.fx.loc(target)
            npc.addWalkSteps(l.x, l.y, -1, true)
        }
    }

    private fun arrive() {
        val npc = boss
        mode = Mode.CHANNEL
        walkTarget = null
        npc.resetWalkSteps()
        setPfm(npc, false) // spec: "the icon clears on arrival"
        npc.setFaceLocation(room.fx.loc(room.geo.centre))
        after(cfg.arrivalPause, "cycle") { runAnchorSet() }
    }

    // ---- tendrils at an anchor ----------------------------------------------------------------------------------

    private fun runAnchorSet() {
        val sw = room.fx.static(boss.location) ?: return
        val lines = ArenaGeometry.CLOCKWISE.associateWith { room.geo.tendrilLine(sw.x, sw.y, boss.size, it) }
        val order = TendrilPlan.anchorOrder(lines, cfg.tendrilsPerAnchor) { room.random.nextInt(it) }
        startChannel()
        if (order.isEmpty()) {
            slam("cycle") { afterSlam() }
            return
        }
        order.forEachIndexed { i, dir ->
            val last = i == order.lastIndex
            after(i * cfg.tendrilInterval, "cycle") {
                tendrils.spawn(dir, lines.getValue(dir), "$tag.cycle") {
                    if (last) {
                        tendrils.settle()
                        slam("cycle") { afterSlam() }
                    }
                }
            }
        }
    }

    private fun afterSlam() {
        after(cfg.postSlamPause, "cycle") { walkTo(anchorCursor + 1) }
    }

    private fun startChannel() {
        channelling = true
        room.fx.anim(boss, TfaIds.Seq.OCELLUS_P1_CHARGE)
        room.fx.spotOn(boss, TfaIds.Spot.OCELLUS_P1_CHARGE)
        // 4260's anim 14822 lasts 2 t: resend it while he channels (one loop at a time).
        timeline.cancel("$tag.chargefx")
        timeline.every(cfg.chargeSpotEvery, cfg.chargeSpotEvery, "$tag.chargefx", { channelling && mode != Mode.WALKING }) {
            room.boss?.let { room.fx.spotOn(it, TfaIds.Spot.OCELLUS_P1_CHARGE) }
        }
    }

    // ---- slam ---------------------------------------------------------------------------------------------------

    /** 14811 + 4261 + floor 4262-4265; damage [p1.slamDelay] later to players within Chebyshev 2 of his 3x3. */
    private fun slam(sub: String, then: () -> Unit) {
        val npc = boss
        channelling = false
        quietUntil = timeline.now + cfg.slamDelay + 2
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_P1_SLAM)
        room.fx.spotOn(npc, TfaIds.Spot.OCELLUS_P1_SLAM)
        val sw = room.fx.static(npc.location) ?: return
        val cx = sw.x + npc.size / 2
        val cy = sw.y + npc.size / 2
        for (dir in listOf(Compass.N, Compass.E, Compass.S, Compass.W)) {
            val r = (cfg.slamFloorRotations[ArenaGeometry.key(dir)] ?: 0).coerceIn(0, 3)
            val t = room.geo.tile(cx + dir.dx * cfg.slamFloorOffset, cy + dir.dy * cfg.slamFloorOffset)
            room.fx.spotAt(TfaIds.Spot.OCELLUS_P1_SLAM_FLOOR[r], t)
        }
        after(cfg.slamDelay, sub) {
            val at = room.boss?.let { room.fx.static(it.location) } ?: sw
            for (p in room.targets()) {
                val t = room.tileOf(p) ?: continue
                if (ArenaGeometry.distanceToFootprint(t.x, t.y, at.x, at.y, npc.size) <= cfg.slamRadius) {
                    room.fx.hurt(p, room.random.nextInt(cfg.slamMax + 1), HitType.DEFAULT, npc)
                }
            }
            then()
        }
    }

    // ---- auto attack --------------------------------------------------------------------------------------------

    private fun autoAttack() {
        val npc = room.boss ?: return
        when (mode) {
            Mode.CENTRE, Mode.IDLE -> return
            Mode.CHANNEL -> if (!cfg.autosWhileChannelling) return
            Mode.WALKING -> {}
        }
        if (timeline.now < quietUntil) return
        val targets = room.targets()
        if (targets.isEmpty()) return
        val target = targets[room.random.nextInt(targets.size)]
        val magic = room.random.nextBoolean()
        npc.faceEntity(target)
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_P1_PROJ_START)
        room.fx.spotOn(npc, if (magic) TfaIds.Spot.OCELLUS_WATER_CAST else TfaIds.Spot.OCELLUS_FIRE_CAST)
        after(cfg.castTicks, "auto") {
            if (!room.isTarget(target)) return@after
            room.fx.anim(npc, TfaIds.Seq.OCELLUS_P1_PROJ_END)
            val spec = if (magic) room.cfg.projectiles.p1Magic else room.cfg.projectiles.p1Ranged
            val proj = if (magic) TfaIds.Spot.OCELLUS_WATER_PROJ else TfaIds.Spot.OCELLUS_FIRE_PROJ
            val ticks = room.fx.projectile(proj, npc, target, spec)
            timeline.after(ticks, "hit") { landAuto(npc, target, magic) }
            if (channelling) after(1, "cycle") { if (channelling) room.boss?.let { room.fx.anim(it, TfaIds.Seq.OCELLUS_P1_CHARGE) } }
        }
    }

    private fun landAuto(npc: OcellusNpc, target: Player, magic: Boolean) {
        if (!room.isTarget(target)) return
        room.fx.spotOn(target, if (magic) TfaIds.Spot.OCELLUS_WATER_IMPACT else TfaIds.Spot.OCELLUS_FIRE_IMPACT)
        val prayer = if (magic) Prayer.PROTECT_FROM_MAGIC else Prayer.PROTECT_FROM_MISSILES
        val damage = if (target.prayerManager.isActive(prayer)) 0 else {
            val accuracy = CombatUtilities.getHitAccuracy(npc, target, if (magic) AttackType.MAGIC else AttackType.RANGED)
            if (room.random.nextDouble() < accuracy) room.random.nextInt(cfg.autoMax + 1) else 0
        }
        room.fx.hurt(target, damage, if (magic) HitType.MAGIC else HitType.RANGED, npc)
    }

    // ---- centre sequence (66% / 25%) ----------------------------------------------------------------------------

    private fun startCentre() {
        if (!thresholds.hasPending()) return
        val pct = thresholds.poll()
        timeline.cancel("$tag.cycle")
        tendrils.settle()
        channelling = false
        mode = Mode.CENTRE
        walkTarget = null
        val npc = boss
        npc.resetWalkSteps()
        setPfm(npc, false)
        room.fx.anim(npc, TfaIds.Seq.OCELLUS_P1_TELEPORT_OUT)
        room.fx.spotOn(npc, TfaIds.Spot.OCELLUS_P1_TELEPORT_OUT)
        after(cfg.teleportTicks, "centre") {
            val n = room.boss ?: return@after
            room.fx.teleport(n, room.centreTile)
            room.fx.anim(n, TfaIds.Seq.OCELLUS_P1_TELEPORT_IN)
            room.fx.spotOn(n, TfaIds.Spot.OCELLUS_P1_TELEPORT_IN)
            after(cfg.teleportTicks, "centre") { centreRound(0) }
        }
        lastThreshold = pct
    }

    private var lastThreshold = 0

    private fun centreRound(round: Int) {
        val rounds = TendrilPlan.centreRounds()
        if (round >= rounds.size) {
            charge()
            return
        }
        val c = room.centreTile
        val (a, b) = rounds[round]
        startChannel()
        tendrils.spawn(a, room.geo.tendrilLine(c.x, c.y, boss.size, a), "$tag.centre")
        after(cfg.centrePairGap, "centre") {
            tendrils.spawn(b, room.geo.tendrilLine(c.x, c.y, boss.size, b), "$tag.centre") {
                slam("centre") { after(1, "centre") { centreRound(round + 1) } }
            }
        }
    }

    private fun charge() {
        tendrils.settle()
        val npc = boss
        val annihilation = tendrils.meter >= cfg.annihilationAt
        room.fx.shout(npc, if (annihilation) "Embrace... annihilation..." else "Death... awaits...")
        startChannel()
        after(cfg.chargeTicks, "centre") {
            channelling = false
            val n = room.boss ?: return@after
            room.fx.anim(n, TfaIds.Seq.OCELLUS_P1_EXPEND)
            val range = if (annihilation) cfg.annihilation else cfg.deathAwaits
            for (p in room.targets()) {
                val ticks = room.fx.projectile(TfaIds.Spot.OCELLUS_P1_RELEASE_PROJ, n, p, room.cfg.projectiles.p1Release)
                val dmg = range.first + room.random.nextInt(range.last - range.first + 1)
                timeline.after(ticks, "hit") { if (room.isTarget(p)) room.fx.hurt(p, dmg, HitType.DEFAULT, n) }
            }
            tendrils.setMeter(0)
            after(cfg.releaseTicks, "centre") { finishCentre() }
        }
    }

    private fun finishCentre() {
        if (thresholds.hasPending()) {
            startCentre()
            return
        }
        walkTo(anchorCursor + 1)
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private fun setPfm(npc: OcellusNpc, on: Boolean) {
        npc.protectFromMelee = on
        if (on) Bridge.setOverheads(npc, Bridge.PROTECT_FROM_MELEE) else Bridge.clearOverheads(npc)
    }

    override fun debugLines(): List<String> = listOf(
        "P1 mode=$mode anchor=${anchors.getOrNull(anchorCursor)} meter=${tendrils.meter}/${cfg.meterMax} " +
            "tendrils active=${tendrils.activeCount} set resolved=${tendrils.resolvedInSet} blocked=${tendrils.blockedInSet} " +
            "thresholds fired=${thresholds.fired} pending=${thresholds.pendingList()} last=$lastThreshold",
    )

    /** `::tfa ocellus centre`: run the centre sequence now (debug). */
    fun debugCentre() {
        thresholds.force()
        if (mode != Mode.CENTRE) startCentre()
    }
}

/**
 * HP thresholds of a phase (§2.5: "several thresholds crossed in one hit queue in order"; same test as
 * `Scaling.crossed`: from above `pct`% of the max to at or below it). Pure; unit tested.
 */
class ThresholdTracker(thresholds: List<Int>) {

    private val ordered = thresholds.sortedDescending()
    private val pending = ArrayDeque<Int>()
    val fired = LinkedHashSet<Int>()

    /** Records every threshold [before] → [after] crosses (of [max]); returns the newly crossed ones in order. */
    fun crossed(before: Int, after: Int, max: Int): List<Int> {
        val out = ArrayList<Int>()
        for (pct in ordered) {
            if (pct in fired) continue
            val mark = max * pct / 100.0
            if (before > mark && after <= mark) {
                fired += pct
                pending += pct
                out += pct
            }
        }
        return out
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun poll(): Int = pending.removeFirst()

    fun pendingList(): List<Int> = pending.toList()

    /** Debug: queue a run with no threshold (0). */
    fun force() {
        pending += 0
    }
}
