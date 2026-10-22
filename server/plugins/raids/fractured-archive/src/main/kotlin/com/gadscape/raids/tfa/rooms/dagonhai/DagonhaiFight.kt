package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.rooms.dagonhai.Timeline.Tag
import java.util.Random

/**
 * The Dagon'hai trio's fight script (spec §4.6 state machine and attack table), engine-free: it drives the world only
 * through [DagonhaiHost] and keeps its own timeline ([Timeline], spec §2.5), so unit tests run it with a recording host
 * and a seeded [random].
 *
 * ```
 * IDLE ─start─▶ INTRO (3 t) ─▶ SHIELDED ─pool 0, breaks < 5─▶ PROTECTED (imps dead or 30 t) ─▶ SHIELDED ...
 *                                  │                    └─pool 0, 5th break─▶ FINALE (explosion, 7-HP survivors) ─▶ DONE
 *                                  └─ ritual 4/4: empowered (overlay: every NPC attacks every 5 t) until the explosion
 * ```
 *
 * - **Shared pool** ([onPoolHit], [onPoolDepleted]): the room applies a hit on any of the three to the pool (capped
 *   at the pool) and reports the new value; all three mirror it ([DagonhaiHost.trioPool]). A depletion is handled at
 *   the start of the next room tick ([preTick]), so the hit that broke the shield still counts for contribution.
 * - **Elder**: an auto every 6 t on a focus target kept for 4 attacks, 50/50 ranged (14593 + 4070, projectile 4071,
 *   impact 4072, max 27) or surge (14597 + 4103-4106, stock surge 1456-1465 by element, max 21-24); prayer checked
 *   when the hit lands; every other player within 1 tile of the target takes its own roll.
 * - **Channels**: one at a time, first at 15 t, then every 20-25 t, by the Elder or a monk at its desk: yell, loop
 *   14607/14612/14617 + element vfx, headbar 85 over 12 t. A matching standard spell landing on the caster interrupts
 *   it (14611/14616/14621); success plays 14609/14614/14619 + the end vfx and adds a ritual quarter.
 * - **Monks**: walk to a desk, search 4-8 t (14622/14630), "What have we here?" (14623/14631), then the god yell and the
 *   5-t throw (seq + hand spot, gusts on the lane tiles); the tome leaves at cycle 112 and flies 2 t. The first player
 *   on the lane (from the monk's end) takes the local effect; otherwise the cauldron's arena-wide effect fires and the
 *   ritual gains a quarter (`tome.addsProgress`).
 * - **Break** (pool 0): stability −200 (4164), ritual kept; breaks 1-4: Zamorak protection (dome 4165, trio
 *   invulnerable and idle), 3 rifts drop 2 imps each 2 t apart; ends when every imp is dead or after 30 t, pool full
 *   again. An imp's killer gets demonic strength (4162, glow 4067): their next damaging hit on a Dagon'hai × 2.
 * - **Empowered** (ritual full): cauldron 14678 + 4166, the book opens (14682); every Dagon'hai attacks every 5 t:
 *   Elder 14604 + 4077 with two 4078 surges of max 45, monks 14599/14601 + 4074/4075 (max 30); prayer only takes 25%.
 * - **Explosion** (5th break): cauldron model 62612 + 14679 + 4160/4161, stability 0; the book monk dies (14654 +
 *   4163); the Elder and the staff monk become 16644/16646 with 7 HP, stunned 3 / 6 t; the Elder then attacks again.
 * - **Finish**: each survivor's death plays 14652 / 14653; when both are done the room is complete.
 */
class DagonhaiFight(
    private val cfg: DagonhaiConfig,
    private val timeline: Timeline,
    private val random: Random,
    private val host: DagonhaiHost,
    poolMax: Int,
) {

    /** The shared pool for this party scale (spec §2.6); fixed when the fight starts ([rescale]). */
    var poolMax: Int = poolMax
        private set

    enum class Phase { IDLE, INTRO, SHIELDED, PROTECTED, FINALE, DONE }

    /** What one Dagon'hai is doing. */
    enum class Mode { IDLE, WALKING, SEARCHING, THROWING, CHANNELLING, RECOVERING, STUNNED, DEAD }

    /** The script's view of one of the three. */
    class TrioState internal constructor(val role: Role) {
        var mode: Mode = Mode.IDLE
            internal set

        /** Tick a search, recovery or stun ends. */
        var until: Int = 0
            internal set
        var desk: Compass? = null
            internal set
        var lastDesk: Compass? = null
            internal set
        var walkStart: Int = 0
            internal set
        var nextAttack: Int = Int.MAX_VALUE
            internal set
        var alive: Boolean = true
            internal set
        var unshielded: Boolean = false
            internal set
    }

    /** The channel in progress. */
    data class ActiveChannel(val role: Role, val element: Element, val start: Int, val end: Int)

    var phase: Phase = Phase.IDLE
        private set
    var empowered: Boolean = false
        private set
    var pool: Int = poolMax
        private set
    var breaks: Int = 0
        private set
    var stability: Int = cfg.stability
        private set
    var ritual: Int = 0
        private set
    var complete: Boolean = false
        private set
    var channel: ActiveChannel? = null
        private set

    val units: Map<Role, TrioState> = Role.entries.associateWith { TrioState(it) }

    /** True between a depletion and the next [preTick] (hits on the trio do nothing meanwhile). */
    var breakPending: Boolean = false
        private set

    /** The Elder's focus target and attacks left on it. */
    var focus: Fighter? = null
        private set
    private var focusLeft = 0

    var nextChannelTick: Int = Int.MAX_VALUE
        private set
    private var channelHandle: Timeline.Handle? = null

    /** Monks between "What have we here?" and the tome's release. */
    private val windups = LinkedHashSet<Role>()

    /** Tomes released and not landed yet. */
    var tomesInFlight: Int = 0
        private set

    // protection
    var protectEnd: Int = -1
        private set
    var impsTotal: Int = 0
        private set
    var impsDropped: Int = 0
        private set
    private val rifts = ArrayList<Int>()

    // demonic strength: fighter -> expiry tick
    private val strength = LinkedHashMap<Fighter, Int>()
    private val glowing = HashSet<Fighter>()

    // Zamorak burns per fighter, cancelled when the fighter is lost
    private val burns = HashMap<Fighter, MutableList<Timeline.Handle>>()

    private var survivors: List<Role> = emptyList()
    private var survivorsDone = 0

    // statistics for ::tfa dump and the tests
    var channelsCompleted = 0
        private set
    var channelsInterrupted = 0
        private set
    var tomesIntercepted = 0
        private set
    var tomesToCauldron = 0
        private set
    var explosionTick = -1
        private set

    private val now: Int get() = timeline.now

    // ============================================================================================= lifecycle

    /** Sets the pool for the scale the raid fixed at the start; only before [start]. */
    fun rescale(newMax: Int): Boolean {
        if (phase != Phase.IDLE || newMax <= 0) return false
        poolMax = newMax
        pool = newMax
        return true
    }

    /** The fight begins (first transporter use): INTRO, monks scatter to their first desks, HUD 100% / 0. */
    fun start() {
        if (phase != Phase.IDLE) return
        phase = Phase.INTRO
        pool = poolMax
        host.trioShield(ShieldState.SHIELDED)
        host.trioPool(pool, poolMax)
        hud()
        nextChannelTick = now + cfg.channel.first
        for (r in Role.entries) if (r.isMonk) goToDesk(unit(r), cfg.monk.firstDesks[r]?.takeIf { it in cfg.monk.desks })
        timeline.schedule(cfg.introTicks, Tag.TRIO) { beginShielded() }
    }

    private fun beginShielded() {
        if (phase != Phase.INTRO) return
        phase = Phase.SHIELDED
        unit(Role.ELDER).nextAttack = now + cfg.elder.firstAttack
    }

    /** Spec §2.5 step 2: a depletion reported during the last tick's hits becomes a break now. */
    fun preTick() {
        if (breakPending) {
            breakPending = false
            doBreak()
        }
    }

    /** One room tick, after [preTick] and the timeline's due actions. */
    fun tick() {
        for (u in units.values) if (u.mode == Mode.RECOVERING && now >= u.until) u.mode = Mode.IDLE
        when (phase) {
            Phase.IDLE, Phase.DONE -> return
            Phase.INTRO -> monksTick()
            Phase.SHIELDED -> if (empowered) empoweredTick() else {
                channelTick() // a caster that starts channelling does not also attack this tick
                elderTick()
                monksTick()
            }
            Phase.PROTECTED -> protectTick()
            Phase.FINALE -> finaleTick()
        }
        strength.entries.removeIf { it.value < now }
    }

    /** Stops the script (wipe, teardown): nothing runs any more. The room cancels the timeline. */
    fun stop() {
        phase = Phase.DONE
        windups.clear()
        channel = null
        strength.clear()
        burns.clear()
    }

    // ============================================================================================ shared pool

    /** A hit (damage or heal) was applied to a shielded Dagon'hai; [newPool] is its HP now. */
    fun onPoolHit(newPool: Int) {
        if (!poolOpen()) return
        pool = newPool.coerceIn(0, poolMax)
        if (pool > 0) host.trioPool(pool, poolMax)
    }

    /** A shielded Dagon'hai reached 0 HP: the pool is depleted. Idempotent until the break is handled. */
    fun onPoolDepleted() {
        if (!poolOpen()) return
        pool = 0
        breakPending = true
    }

    /** True while hits on the trio count against the pool (not protected, not mid-break, not after the explosion). */
    fun poolOpen(): Boolean = (phase == Phase.SHIELDED || phase == Phase.INTRO) && !breakPending

    private fun doBreak() {
        breaks++
        stability = maxOf(0, stability - cfg.stabilityPerBreak)
        if (cfg.ritualResetOnBreak) ritual = 0
        hud()
        interruptAll()
        host.cauldronGraphic(DagonhaiIds.SPOT_CAULDRON_SIZZLE)
        if (breaks >= cfg.breaks || stability <= 0) explode() else protect()
    }

    /** Cancels every wind-up, the channel and the monks' walks (tomes already released keep flying). */
    private fun interruptAll() {
        timeline.cancel(Tag.TRIO)
        windups.clear()
        channel?.let { c ->
            host.chargeBar(c.role, 0)
            host.trioGraphic(c.role, -1)
        }
        channel = null
        channelHandle = null
        for (u in units.values) {
            if (!u.alive) continue
            if (u.mode == Mode.WALKING || u.mode == Mode.SEARCHING || u.mode == Mode.THROWING || u.mode == Mode.CHANNELLING) {
                host.trioStop(u.role)
            }
            u.mode = Mode.IDLE
        }
    }

    // ================================================================================================== Elder

    private fun elderTick() {
        val u = unit(Role.ELDER)
        if (!u.alive || u.mode != Mode.IDLE || now < u.nextAttack) return
        elderAttack(u)
    }

    private fun elderAttack(u: TrioState) {
        val target = focusTarget() ?: run {
            u.nextAttack = now + 1
            return
        }
        u.nextAttack = now + cfg.elder.speed
        val from = host.trioTile(Role.ELDER) ?: cfg.arena.elder
        val d = DagonhaiArena.footprintDistance(from, DagonhaiArena.TRIO_SIZE, target.tile)
        host.trioFace(Role.ELDER, target)
        if (random.nextDouble() < cfg.elder.magicChance) {
            val element = Element.entries[random.nextInt(Element.entries.size)]
            val spec = cfg.projectiles.surge
            host.trioAnimate(Role.ELDER, DagonhaiIds.SEQ_ELDER_MAGIC)
            host.trioGraphic(Role.ELDER, DagonhaiIds.spotSurgeCast(element))
            host.projectile(Role.ELDER, DagonhaiIds.spotSurgeProj(element), spec, target)
            val max = cfg.elder.surgeMax[element] ?: 0
            timeline.schedule(spec.landingTicks(d), Tag.HIT) {
                landWithSplash(Role.ELDER, target, Style.MAGIC, max, DagonhaiIds.spotSurgeImpact(element), cfg.heights.surgeImpact)
            }
        } else {
            val spec = cfg.projectiles.elderRanged
            host.trioAnimate(Role.ELDER, DagonhaiIds.SEQ_ELDER_RANGED)
            host.trioGraphic(Role.ELDER, DagonhaiIds.SPOT_RANGED_CAST)
            host.projectile(Role.ELDER, DagonhaiIds.SPOT_RANGED_PROJ, spec, target)
            timeline.schedule(spec.landingTicks(d), Tag.HIT) {
                landWithSplash(Role.ELDER, target, Style.RANGED, cfg.elder.rangedMax, DagonhaiIds.SPOT_RANGED_IMPACT, cfg.heights.rangedImpact)
            }
        }
    }

    /** The Elder's hit on [target] and its splash on every other player within `elder.splash` tiles (own rolls). */
    private fun landWithSplash(role: Role, target: Fighter, style: Style, max: Int, impact: Int, height: Int) {
        if (!target.alive) return
        val centre = target.tile
        host.trioAttack(role, target, style, max)
        host.playerGraphic(target, impact, height)
        if (cfg.elder.splash <= 0) return
        for (f in host.fighters()) {
            if (f === target || !f.alive) continue
            if (DagonhaiArena.chebyshev(f.tile, centre) > cfg.elder.splash) continue
            host.trioAttack(role, f, style, max)
            host.playerGraphic(f, impact, height)
        }
    }

    /** The Elder's focus: kept for `focusAttacks` attacks or until it dies or leaves (COMM). */
    private fun focusTarget(): Fighter? {
        val alive = host.fighters().filter { it.alive }
        if (alive.isEmpty()) {
            focus = null
            return null
        }
        val current = focus
        if (current == null || !current.alive || current !in alive || focusLeft <= 0) {
            focus = alive[random.nextInt(alive.size)]
            focusLeft = cfg.elder.focusAttacks
        }
        focusLeft--
        return focus
    }

    // =============================================================================================== channels

    private fun channelTick() {
        if (channel != null || now < nextChannelTick) return
        val eligible = cfg.channel.casters.map { unit(it) }.filter { canChannel(it) }
        if (eligible.isEmpty()) {
            nextChannelTick = now + 1
            return
        }
        val caster = eligible[random.nextInt(eligible.size)]
        startChannel(caster, Element.entries[random.nextInt(Element.entries.size)])
    }

    private fun canChannel(u: TrioState): Boolean = u.alive && !u.unshielded && when (u.role) {
        Role.ELDER -> u.mode == Mode.IDLE
        else -> u.mode == Mode.SEARCHING || u.mode == Mode.IDLE
    }

    private fun startChannel(u: TrioState, element: Element) {
        val role = u.role
        if (u.mode == Mode.WALKING || u.mode == Mode.SEARCHING) host.trioStop(role)
        u.mode = Mode.CHANNELLING
        host.trioFaceTile(role, DagonhaiArena.CAULDRON_CENTRE)
        host.trioSay(role, channelLine(element))
        host.trioAnimate(role, DagonhaiIds.seqChannel(role))
        host.trioGraphic(role, DagonhaiIds.spotChannel(role, element, end = false))
        host.chargeBar(role, cfg.channel.length)
        channel = ActiveChannel(role, element, now, now + cfg.channel.length)
        channelHandle = timeline.schedule(cfg.channel.length, Tag.TRIO) { completeChannel() }
        val interval = cfg.channel.interval
        nextChannelTick = now + interval.first + random.nextInt(interval.last - interval.first + 1)
    }

    private fun completeChannel() {
        val c = channel ?: return
        channel = null
        channelHandle = null
        val u = unit(c.role)
        host.chargeBar(c.role, 0)
        host.trioAnimate(c.role, DagonhaiIds.seqChannelEnd(c.role))
        host.trioGraphic(c.role, DagonhaiIds.spotChannel(c.role, c.element, end = true))
        u.mode = Mode.RECOVERING
        u.until = now + CHANNEL_END_TICKS
        channelsCompleted++
        addRitual()
    }

    /**
     * A player's spell landed on [role] (E17 spell hook). A standard spell of the channel's element interrupts it
     * (splashes too, `channel.splashCounts`); any other spell does not. Returns true when it interrupted.
     */
    fun onSpellLanded(role: Role, element: Element?, splash: Boolean): Boolean {
        val c = channel ?: return false
        if (c.role != role || element != c.element || element == null) return false
        if (splash && !cfg.channel.splashCounts) return false
        if (phase != Phase.SHIELDED && phase != Phase.INTRO) return false
        channelHandle?.cancel()
        channelHandle = null
        channel = null
        val u = unit(role)
        host.chargeBar(role, 0)
        host.trioGraphic(role, -1)
        host.trioAnimate(role, DagonhaiIds.seqChannelInterrupted(role))
        u.mode = Mode.RECOVERING
        u.until = now + CHANNEL_INTERRUPT_TICKS
        channelsInterrupted++
        return true
    }

    // ================================================================================================= ritual

    private fun addRitual() {
        if (empowered || (phase != Phase.SHIELDED && phase != Phase.INTRO)) return
        ritual = minOf(cfg.ritualQuarters, ritual + 1)
        hud()
        if (ritual >= cfg.ritualQuarters) becomeEmpowered()
    }

    private fun hud() = host.ritualHud(stability, cfg.stability, ritual, cfg.ritualQuarters)

    // ================================================================================================== monks

    private fun monksTick() {
        for (r in Role.entries) if (r.isMonk) monkTick(unit(r))
    }

    private fun monkTick(u: TrioState) {
        if (!u.alive) return
        when (u.mode) {
            Mode.IDLE -> goToDesk(u, null)
            Mode.WALKING -> {
                val desk = u.desk ?: return goToDesk(u, null)
                val stand = stand(desk)
                val at = host.trioTile(u.role)
                when {
                    at == stand -> arrive(u, desk)
                    now - u.walkStart >= cfg.monk.walkTimeout -> {
                        host.trioPlace(u.role, stand)
                        arrive(u, desk)
                    }
                    !host.trioMoving(u.role) -> {
                        // The route ended next to the stand (2x2 route end) or was blocked by another npc: settle in
                        // place when one step away, else route again.
                        if (at != null && DagonhaiArena.chebyshev(at, stand) <= 1) {
                            host.trioPlace(u.role, stand)
                            arrive(u, desk)
                        } else {
                            host.trioWalk(u.role, stand)
                        }
                    }
                }
            }
            Mode.SEARCHING -> {
                if (now < u.until || phase != Phase.SHIELDED) return
                if (cfg.monk.oneTomeAtATime && (windups.isNotEmpty() || tomesInFlight > 0)) return
                beginThrow(u, God.entries[random.nextInt(God.entries.size)])
            }
            else -> {}
        }
    }

    private fun stand(desk: Compass): StaticTile = cfg.monk.stands[desk] ?: DagonhaiConfig.DEFAULT_STANDS.getValue(desk)

    /** Walks [u] to [preferred] or a random other desk (never the other monk's, never the last one). */
    private fun goToDesk(u: TrioState, preferred: Compass?) {
        val desk = preferred ?: pickDesk(u) ?: return
        u.desk = desk
        u.mode = Mode.WALKING
        u.walkStart = now
        host.trioWalk(u.role, stand(desk))
    }

    private fun pickDesk(u: TrioState): Compass? {
        val taken = units.values.filter { it !== u && it.role.isMonk && it.alive }.mapNotNull { it.desk }.toSet()
        val options = cfg.monk.desks.filter { it !in taken && it != u.lastDesk }
            .ifEmpty { cfg.monk.desks.filter { it !in taken } }
        return if (options.isEmpty()) null else options[random.nextInt(options.size)]
    }

    private fun arrive(u: TrioState, desk: Compass) {
        u.mode = Mode.SEARCHING
        u.lastDesk = desk
        val search = cfg.monk.searchTicks
        u.until = now + search.first + random.nextInt(search.last - search.first + 1)
        host.trioFaceTile(u.role, DagonhaiArena.DESK_FACE.getValue(desk))
        host.trioAnimate(u.role, DagonhaiIds.seqSearch(u.role))
    }

    /** "What have we here?" (search end), then the throw on the next tick. */
    private fun beginThrow(u: TrioState, god: God) {
        val desk = u.desk ?: return
        u.mode = Mode.THROWING
        windups += u.role
        host.trioAnimate(u.role, DagonhaiIds.seqSearchEnd(u.role))
        host.trioSay(u.role, SEARCH_LINE)
        timeline.schedule(SEARCH_END_TICKS, Tag.TRIO) { throwTome(u, god, desk) }
    }

    /** The 5-t throw: god yell, throw seq, hand spot, gusts on the lane; the tome leaves at `monk.releaseCycle`. */
    private fun throwTome(u: TrioState, god: God, desk: Compass) {
        val role = u.role
        host.trioFaceTile(role, DagonhaiArena.CAULDRON_CENTRE)
        host.trioSay(role, god.line)
        host.trioAnimate(role, DagonhaiIds.seqThrow(role, god))
        host.trioGraphic(role, DagonhaiIds.spotHand(role, god))
        val lane = DagonhaiArena.laneTiles(desk, stand(desk))
        for (tile in lane) host.graphic(DagonhaiIds.spotGust(god, desk), tile)
        val sendTick = (cfg.monk.throwRelease - 1).coerceAtLeast(0)
        timeline.schedule(sendTick, Tag.TRIO) { releaseTome(u, god, lane, sendTick) }
        timeline.schedule(THROW_TICKS, Tag.TRIO) {
            if (u.mode == Mode.THROWING) {
                u.mode = Mode.RECOVERING
                u.until = now
            }
        }
    }

    /** The tome leaves the monk's hand: the first player on the lane intercepts it, else it flies to the cauldron. */
    private fun releaseTome(u: TrioState, god: God, lane: List<StaticTile>, sendTick: Int) {
        windups -= u.role
        val fighters = host.fighters().filter { it.alive }
        val interceptor = lane.firstNotNullOfOrNull { t -> fighters.firstOrNull { it.tile == t } }
        val startDelay = (cfg.monk.releaseCycle - sendTick * DagonhaiIds.CYCLES_PER_TICK).coerceAtLeast(0)
        val flight = cfg.monk.flight * DagonhaiIds.CYCLES_PER_TICK
        val spec = cfg.projectiles.tome.copy(delay = startDelay, duration = flight, perTile = 0)
        val landing = (startDelay + flight + DagonhaiIds.CYCLES_PER_TICK - 1) / DagonhaiIds.CYCLES_PER_TICK
        tomesInFlight++
        if (interceptor != null) {
            host.projectile(u.role, DagonhaiIds.spotTomeProj(god), spec, interceptor)
            timeline.schedule(landing, Tag.HIT) {
                tomesInFlight--
                tomeIntercepted(god, interceptor)
            }
        } else {
            host.projectile(u.role, DagonhaiIds.spotTomeProj(god), spec, DagonhaiArena.CAULDRON_CENTRE)
            timeline.schedule(landing, Tag.HIT) {
                tomesInFlight--
                tomeReachesCauldron(god)
            }
        }
    }

    // ========================================================================================== tome effects

    private fun tomeIntercepted(god: God, blocker: Fighter) {
        if (phase == Phase.DONE || !blocker.alive) return
        tomesIntercepted++
        host.playerGraphic(blocker, DagonhaiIds.spotTomeImpact(god), cfg.heights.tomeImpact)
        when (god) {
            God.BANDOS -> {
                host.damage(blocker, random.nextInt(cfg.tome.bandosBlocker + 1))
                shockwave(blocker.tile, 1, blocker.tile, exclude = blocker)
            }
            God.ZAMORAK -> {
                host.damage(blocker, cfg.tome.zamBlocker)
                burn(blocker)
                for ((tile, fuse) in GodEffects.zamorakField(blocker.tile, cfg.tome.zamRadius, cfg.tome.zamFuseLayout, cfg.tome.zamFuses.size, random)) {
                    fireball(tile, fuse)
                }
            }
            God.SARADOMIN -> {
                host.heal(blocker, cfg.tome.saraHeal, 0)
                host.playerGraphic(blocker, DagonhaiIds.SPOT_SARA_HEAL_IMPACT, cfg.heights.healImpact)
                storm(blocker.tile)
            }
        }
    }

    private fun tomeReachesCauldron(god: God) {
        if (phase == Phase.DONE || phase == Phase.FINALE) return
        tomesToCauldron++
        host.graphic(DagonhaiIds.spotTomeImpact(god), DagonhaiArena.CAULDRON_CENTRE)
        host.cauldronAnimate(DagonhaiIds.seqCauldron(god))
        host.cauldronGraphic(DagonhaiIds.spotCauldron(god))
        when (god) {
            God.BANDOS -> shockwave(DagonhaiArena.CAULDRON_SW, DagonhaiArena.CAULDRON_SIZE, DagonhaiArena.CAULDRON_CENTRE, exclude = null)
            God.ZAMORAK -> {
                val spec = cfg.projectiles.zamFireball
                for (tile in DagonhaiArena.pickTiles(DagonhaiArena.HAZARD_TILES, cfg.tome.zamCauldronTiles, random)) {
                    host.projectile(null, DagonhaiIds.SPOT_ZAMORAK_FIREBALL_PROJ, spec, tile)
                    val land = (spec.flightCycles(DagonhaiArena.cauldronDistance(tile)) + DagonhaiIds.CYCLES_PER_TICK - 1) / DagonhaiIds.CYCLES_PER_TICK
                    timeline.schedule(land, Tag.HAZARD) { fireball(tile, random.nextInt(cfg.tome.zamFuses.size)) }
                }
            }
            God.SARADOMIN -> {
                for (f in host.fighters()) {
                    if (!f.alive) continue
                    host.heal(f, cfg.tome.saraCauldronHeal, 0)
                    host.playerGraphic(f, DagonhaiIds.SPOT_SARA_HEAL_IMPACT, cfg.heights.healImpact)
                }
                if (phase == Phase.SHIELDED || phase == Phase.INTRO) {
                    pool = minOf(poolMax, pool + poolMax * cfg.tome.saraPoolHealPct / 100)
                    host.trioPool(pool, poolMax)
                }
            }
        }
        if (cfg.tome.addsProgress) addRitual()
    }

    /** Bandos rings moving out from ([sw], [size]) one per tick (spots 4156-4159 on [centre]). */
    private fun shockwave(sw: StaticTile, size: Int, centre: StaticTile, exclude: Fighter?) {
        val rings = cfg.tome.bandosRings
        for (k in 1..rings) {
            timeline.schedule(k, Tag.HAZARD) {
                host.graphic(DagonhaiIds.spotBandosRing(k - 1), centre)
                for (f in GodEffects.ringVictims(sw, size, k, cfg.tome.bandosRingWidth, host.fighters(), exclude)) {
                    host.damage(f, cfg.tome.bandosRing)
                }
            }
        }
    }

    /** A Zamorak fireball on [tile]: fuse spot [fuse] (4145-4148), then the blast (4149) when it ends. */
    private fun fireball(tile: StaticTile, fuse: Int) {
        if (phase == Phase.DONE) return
        val index = fuse.coerceIn(0, cfg.tome.zamFuses.size - 1)
        host.graphic(DagonhaiIds.spotZamorakFuse(index), tile)
        timeline.schedule(cfg.tome.zamFuses[index], Tag.HAZARD) {
            host.graphic(DagonhaiIds.SPOT_ZAMORAK_BLAST, tile)
            for (f in host.fighters()) if (f.alive && f.tile == tile) host.damage(f, cfg.tome.zamBlast)
        }
    }

    /** The blocker's Zamorak burn: the `zamBurn` hitsplats, one every `zamBurnEvery` ticks (BURN hitsplat). */
    private fun burn(f: Fighter) {
        val list = burns.getOrPut(f) { ArrayList() }
        cfg.tome.zamBurn.forEachIndexed { i, amount ->
            list += timeline.schedule((i + 1) * cfg.tome.zamBurnEvery, Tag.HAZARD) {
                if (f.alive && amount > 0) host.damage(f, amount, burn = true)
            }
        }
    }

    /** The Saradomin storm: heal tiles (4064/4065 + cloud 4151 → 4069) and damage tiles (4063 + 4153 → 4068). */
    private fun storm(centre: StaticTile) {
        val (heal, damage) = GodEffects.stormTiles(centre, cfg.tome.saraRadius, cfg.tome.saraTiles, random)
        val strikes = cfg.tome.saraHealStrikes
        for (tile in heal) {
            val pick = random.nextInt(strikes.size)
            val strike = strikes[pick]
            host.graphic(if (pick == 0) DagonhaiIds.SPOT_SARA_HEAL_TELEGRAPH_5 else DagonhaiIds.SPOT_SARA_HEAL_TELEGRAPH_6, tile)
            host.graphic(DagonhaiIds.SPOT_STORMCLOUD_HEAL, tile)
            timeline.schedule(strike, Tag.HAZARD) {
                host.graphic(DagonhaiIds.SPOT_SARA_HEAL_LIGHTNING, tile)
                host.graphic(DagonhaiIds.SPOT_STORMCLOUD_HEAL_DESPAWN, tile)
                for (f in host.fighters()) if (f.alive && f.tile == tile) host.heal(f, cfg.tome.saraTileHeal, cfg.tome.saraTilePrayer)
            }
        }
        for (tile in damage) {
            host.graphic(DagonhaiIds.SPOT_SARA_DAMAGE_TELEGRAPH, tile)
            host.graphic(DagonhaiIds.SPOT_STORMCLOUD_DAMAGE, tile)
            timeline.schedule(cfg.tome.saraDamageStrike, Tag.HAZARD) {
                host.graphic(DagonhaiIds.SPOT_SARA_DAMAGE_LIGHTNING, tile)
                host.graphic(DagonhaiIds.SPOT_STORMCLOUD_DAMAGE_DESPAWN, tile)
                for (f in host.fighters()) if (f.alive && f.tile == tile) host.damage(f, cfg.tome.saraTileDmg)
            }
        }
    }

    // ============================================================================================= protection

    private fun protect() {
        phase = Phase.PROTECTED
        protectEnd = now + cfg.protect.maxTicks
        host.trioShield(ShieldState.PROTECTED)
        host.trioPool(0, poolMax)
        timeline.schedule(1, Tag.PROTECT) { dome() }
        val players = host.fighters().filter { it.alive }.map { it.tile }
        val candidates = DagonhaiArena.riftCandidates(players, cfg.protect.riftMinPlayerDistance)
            .ifEmpty { DagonhaiArena.HAZARD_TILES }
        val tiles = DagonhaiArena.pickTiles(candidates, cfg.protect.rifts, random, minApart = 2)
        impsTotal = tiles.size * cfg.protect.impsPerRift
        impsDropped = 0
        for (tile in tiles) {
            val handle = host.spawnRift(tile)
            rifts += handle
            for (j in 0 until cfg.protect.impsPerRift) {
                timeline.schedule(cfg.protect.firstImp + j * cfg.protect.impInterval, Tag.PROTECT) {
                    host.spawnImp(tile, randomAlive())
                    impsDropped++
                }
            }
            val close = cfg.protect.firstImp + (cfg.protect.impsPerRift - 1).coerceAtLeast(0) * cfg.protect.impInterval + 1
            timeline.schedule(close, Tag.PROTECT) {
                host.despawnRift(handle)
                rifts -= handle
            }
        }
    }

    /** The Zamorak dome 4165 on the cauldron, re-sent while the protection lasts (seq 9601 is 6 t). */
    private fun dome() {
        if (phase != Phase.PROTECTED) return
        host.cauldronGraphic(DagonhaiIds.SPOT_CAULDRON_DOME)
        timeline.schedule(cfg.protect.domeEvery, Tag.PROTECT) { dome() }
    }

    private fun protectTick() {
        if ((impsDropped >= impsTotal && host.impsAlive() == 0) || now >= protectEnd) endProtection()
    }

    private fun endProtection() {
        timeline.cancel(Tag.PROTECT)
        rifts.forEach { host.despawnRift(it) }
        rifts.clear()
        phase = Phase.SHIELDED
        pool = poolMax
        host.trioShield(ShieldState.SHIELDED)
        host.trioPool(pool, poolMax)
        nextChannelTick = now + cfg.channel.afterProtect
        for (u in units.values) {
            if (!u.alive) continue
            u.mode = Mode.IDLE
            u.nextAttack = now + if (empowered) cfg.empowered.firstDelay else cfg.elder.firstAttack
        }
    }

    /** An imp died; [killer] (if any) gains demonic strength (spot 4162, then the 4067 glow). */
    fun onImpDeath(killer: Fighter?) {
        if (killer == null || !killer.alive || phase == Phase.DONE) return
        strength[killer] = now + cfg.protect.strengthTicks
        host.playerGraphic(killer, DagonhaiIds.SPOT_DEMON_BUFF, cfg.heights.buff)
        if (glowing.add(killer)) timeline.schedule(BUFF_SPOT_TICKS, Tag.BUFF) { glow(killer) }
    }

    private fun glow(f: Fighter) {
        val expiry = strength[f]
        if (expiry == null || expiry < now || !f.alive) {
            glowing -= f
            return
        }
        host.playerGraphic(f, DagonhaiIds.SPOT_DEMONIC_GLOW, cfg.heights.buff)
        timeline.schedule(GLOW_TICKS, Tag.BUFF) { glow(f) }
    }

    /** True while [f] holds demonic strength. */
    fun hasStrength(f: Fighter): Boolean = (strength[f] ?: -1) >= now

    /**
     * The damage multiplier for [f]'s damaging hit on a Dagon'hai: `protect.strengthMult` once after an imp kill
     * (the buff is consumed), else 1.
     */
    fun consumeStrength(f: Fighter): Double {
        val expiry = strength.remove(f) ?: return 1.0
        glowing -= f
        return if (expiry >= now) cfg.protect.strengthMult else 1.0
    }

    // ============================================================================================== empowered

    private fun becomeEmpowered() {
        if (empowered) return
        empowered = true
        timeline.cancel(Tag.TRIO)
        windups.clear()
        channel?.let { c ->
            host.chargeBar(c.role, 0)
            host.trioGraphic(c.role, -1)
        }
        channel = null
        channelHandle = null
        host.trioEmpowered(true)
        host.cauldronAnimate(DagonhaiIds.SEQ_CAULDRON_EMPOWERED)
        host.cauldronGraphic(DagonhaiIds.SPOT_CAULDRON_UNLOCK)
        host.bookAnimate(DagonhaiIds.SEQ_BOOK_OPEN)
        for (u in units.values) {
            if (!u.alive) continue
            if (u.role.isMonk) host.trioStop(u.role)
            u.mode = Mode.IDLE
            u.nextAttack = now + cfg.empowered.firstDelay
        }
    }

    private fun empoweredTick() {
        for (u in units.values) {
            if (!u.alive || u.mode != Mode.IDLE || now < u.nextAttack) continue
            val target = if (u.role == Role.ELDER) focusTarget() else randomAlive()
            if (target == null) {
                u.nextAttack = now + 1
                continue
            }
            u.nextAttack = now + cfg.empowered.speed
            empoweredAttack(u.role, target)
        }
    }

    private fun empoweredAttack(role: Role, target: Fighter) {
        val from = host.trioTile(role) ?: cfg.arena.spawn(role)
        val d = DagonhaiArena.footprintDistance(from, DagonhaiArena.TRIO_SIZE, target.tile)
        host.trioFace(role, target)
        host.trioAnimate(role, DagonhaiIds.seqEmpowered(role))
        host.trioGraphic(role, DagonhaiIds.spotEmpoweredCast(role))
        if (role == Role.ELDER) {
            repeat(cfg.empowered.elderHits) { i ->
                val spec = cfg.projectiles.empoweredElder.let { it.copy(delay = it.delay + i * cfg.empowered.secondProjectileCycles) }
                host.projectile(Role.ELDER, DagonhaiIds.SPOT_EMPOWERED_ELDER_PROJ, spec, target)
                timeline.schedule(spec.landingTicks(d), Tag.HIT) {
                    if (target.alive) host.trioAttack(Role.ELDER, target, Style.MAGIC, cfg.empowered.elderMax)
                }
            }
        } else {
            val spec = cfg.projectiles.monkSurge
            val water = Element.WATER
            val delay = if (cfg.empowered.monkProjectile) {
                host.projectile(role, DagonhaiIds.spotSurgeProj(water), spec, target)
                spec.landingTicks(d)
            } else 1
            timeline.schedule(delay, Tag.HIT) {
                if (!target.alive) return@schedule
                host.trioAttack(role, target, Style.MAGIC, cfg.empowered.monkMax)
                if (cfg.empowered.monkProjectile) host.playerGraphic(target, DagonhaiIds.spotSurgeImpact(water), cfg.heights.surgeImpact)
            }
        }
    }

    // ============================================================================================ the finale

    private fun explode() {
        phase = Phase.FINALE
        explosionTick = now
        timeline.cancel(Tag.PROTECT)
        rifts.forEach { host.despawnRift(it) }
        rifts.clear()
        if (empowered) {
            empowered = false
            host.trioEmpowered(false)
        }
        stability = 0
        hud()
        host.cauldronExplodeModel()
        host.cauldronAnimate(DagonhaiIds.SEQ_CAULDRON_EXPLODE)
        host.cauldronGraphic(DagonhaiIds.SPOT_CAULDRON_EXPLODE)
        host.graphic(DagonhaiIds.SPOT_CAULDRON_DUST, DagonhaiArena.CAULDRON_CENTRE)
        timeline.schedule(cfg.explosion.cauldronTicks, Tag.FINALE) { host.cauldronDespawn() }
        val dies = cfg.explosion.dies
        val victim = unit(dies)
        victim.alive = false
        victim.mode = Mode.DEAD
        host.trioKill(dies)
        host.trioAnimate(dies, DagonhaiIds.seqDeath(dies))
        if (dies == Role.BOOK) host.trioGraphic(dies, DagonhaiIds.SPOT_BOOK_DEATH)
        timeline.schedule(cfg.explosion.deathTicks, Tag.FINALE) { host.trioDespawn(dies) }
        host.trioShield(ShieldState.UNSHIELDED)
        survivors = Role.entries.filter { it != dies }
        survivorsDone = 0
        for (r in survivors) {
            val u = unit(r)
            u.unshielded = true
            u.mode = Mode.STUNNED
            u.until = now + if (r == Role.ELDER) cfg.explosion.elderStun else cfg.explosion.monkStun
            host.trioUnshield(r, cfg.finalHp)
        }
        unit(Role.ELDER).nextAttack = now + cfg.explosion.elderStun
        focus = null
    }

    private fun finaleTick() {
        for (u in units.values) if (u.alive && u.mode == Mode.STUNNED && now >= u.until) u.mode = Mode.IDLE
        val elder = unit(Role.ELDER)
        if (cfg.finale.elderAttacks && elder.alive && elder.mode == Mode.IDLE && now >= elder.nextAttack) elderAttack(elder)
    }

    /** An unshielded survivor died: its death seq (14652 / 14653), removal after it; both done = complete. */
    fun onFinalDeath(role: Role) {
        val u = unit(role)
        if (phase != Phase.FINALE || !u.alive || !u.unshielded) return
        u.alive = false
        u.mode = Mode.DEAD
        host.trioAnimate(role, DagonhaiIds.seqDeath(role))
        timeline.schedule(cfg.finale.deathTicks, Tag.FINALE) {
            host.trioDespawn(role)
            survivorsDone++
            if (survivorsDone >= survivors.size && survivors.none { unit(it).alive }) {
                complete = true
                phase = Phase.DONE
            }
        }
    }

    // ================================================================================================ fighters

    /** A player died or left: no longer the Elder's focus, no buff, no more burn ticks. */
    fun onFighterLost(f: Fighter) {
        if (focus === f) {
            focus = null
            focusLeft = 0
        }
        strength.remove(f)
        glowing.remove(f)
        burns.remove(f)?.forEach { it.cancel() }
    }

    private fun randomAlive(): Fighter? {
        val alive = host.fighters().filter { it.alive }
        return if (alive.isEmpty()) null else alive[random.nextInt(alive.size)]
    }

    fun unit(role: Role): TrioState = units.getValue(role)

    // =================================================================================================== debug

    /** Ends the intro now (`::tfa skipto shielded`). */
    fun debugEndIntro(): Boolean {
        if (phase != Phase.INTRO) return false
        beginShielded()
        return true
    }

    /** `::tfa hp <pct>` / `::tfa dagonhai pool <pct>`: the pool at [percent]% (0 depletes it). */
    fun debugSetPoolPercent(percent: Int): Boolean {
        if (!poolOpen()) return false
        val value = (poolMax.toLong() * percent.coerceIn(0, 100) / 100).toInt()
        if (value <= 0) {
            onPoolDepleted()
            return true
        }
        pool = value
        host.trioPool(pool, poolMax)
        return true
    }

    /** Forces a channel by [role] (or a random eligible caster) with [element] (or a random one). */
    fun debugChannel(role: Role?, element: Element?): Boolean {
        if (phase != Phase.SHIELDED || empowered) return false
        channel?.let { c ->
            channelHandle?.cancel()
            host.chargeBar(c.role, 0)
            host.trioGraphic(c.role, -1)
            unit(c.role).mode = Mode.IDLE
            channel = null
        }
        val u = if (role != null) unit(role) else cfg.channel.casters.map { unit(it) }.firstOrNull { canChannel(it) } ?: return false
        if (!u.alive || u.unshielded) return false
        if (u.role.isMonk && (u.mode == Mode.THROWING)) return false
        startChannel(u, element ?: Element.entries[random.nextInt(Element.entries.size)])
        return true
    }

    /** Forces a tome now: [role]'s monk (or a free one) stands at [desk] (or its desk) and throws [god]. */
    fun debugTome(god: God?, desk: Compass?, role: Role?): Boolean {
        if (phase != Phase.SHIELDED || empowered) return false
        val u = (if (role != null) unit(role) else units.values.firstOrNull { it.role.isMonk && it.alive && it.mode != Mode.THROWING && it.mode != Mode.CHANNELLING })
            ?: return false
        if (!u.role.isMonk || !u.alive || u.mode == Mode.THROWING || u.mode == Mode.CHANNELLING) return false
        val d = desk ?: u.desk ?: cfg.monk.desks.first()
        host.trioStop(u.role)
        host.trioPlace(u.role, stand(d))
        u.desk = d
        u.lastDesk = d
        beginThrow(u, god ?: God.entries[random.nextInt(God.entries.size)])
        return true
    }

    /** Sets the ritual to [quarters] (the last quarter empowers the trio). */
    fun debugRitual(quarters: Int): Boolean {
        if (phase != Phase.SHIELDED && phase != Phase.INTRO) return false
        val q = quarters.coerceIn(0, cfg.ritualQuarters)
        if (q >= cfg.ritualQuarters) {
            ritual = cfg.ritualQuarters - 1
            addRitual()
        } else {
            ritual = q
            hud()
        }
        return true
    }

    /** Breaks the shield now; with [toExplosion] it is the fifth break. */
    fun debugBreak(toExplosion: Boolean): Boolean {
        if (phase == Phase.PROTECTED && !toExplosion) return false
        if (phase == Phase.PROTECTED) endProtection()
        if (!poolOpen()) return false
        if (toExplosion) breaks = maxOf(breaks, cfg.breaks - 1)
        onPoolDepleted()
        return true
    }

    /** Ends the protection now (as if every imp were dead). */
    fun debugEndProtection(): Boolean {
        if (phase != Phase.PROTECTED) return false
        endProtection()
        return true
    }

    /** Lines for `::tfa dump` / `::tfa dagonhai state`. */
    fun describe(): List<String> = buildList {
        add("Dagon'hai: phase $phase${if (empowered) " EMPOWERED" else ""}, tick $now, pool $pool/$poolMax, breaks $breaks/${cfg.breaks}, " +
            "stability $stability/${cfg.stability}, ritual $ritual/${cfg.ritualQuarters}${if (breakPending) ", break pending" else ""}")
        add("  channel ${channel?.let { "${it.role.key} ${it.element.name.lowercase()} ends ${it.end}" } ?: "-"}, next channel " +
            "${if (nextChannelTick == Int.MAX_VALUE) "-" else nextChannelTick}, done $channelsCompleted, interrupted $channelsInterrupted")
        add("  tomes: windups ${windups.joinToString { it.key }.ifEmpty { "-" }}, in flight $tomesInFlight, intercepted $tomesIntercepted, " +
            "to cauldron $tomesToCauldron")
        for (u in units.values) {
            add("  ${u.role.key}: ${u.mode}${if (!u.alive) " (dead)" else ""}${if (u.unshielded) " unshielded" else ""}, desk ${u.desk ?: "-"}, " +
                "until ${u.until}, next attack ${if (u.nextAttack == Int.MAX_VALUE) "-" else u.nextAttack}")
        }
        if (phase == Phase.PROTECTED) add("  protection ends by $protectEnd, imps dropped $impsDropped/$impsTotal, alive ${host.impsAlive()}")
        if (strength.isNotEmpty()) add("  demonic strength until ${strength.values.joinToString()}")
        if (complete) add("  complete")
    }

    companion object {
        /** "What have we here?" (COMM, spec tome throw). */
        const val SEARCH_LINE = "What have we here?"

        /** Search end seqs 14623 / 14631 are 30 c. */
        const val SEARCH_END_TICKS = 1

        /** Throw seqs 14624-14626 / 14632-14634 are 150 c. */
        const val THROW_TICKS = 5

        /** Channel end seqs 14609 / 14614 / 14619 are 30 c. */
        const val CHANNEL_END_TICKS = 1

        /** Channel interrupted seqs 14611 / 14616 / 14621 are 60 c. */
        const val CHANNEL_INTERRUPT_TICKS = 2

        /** Buff spot 4162 (seq 14677) is 122 c, then the glow. */
        const val BUFF_SPOT_TICKS = 4

        /** Glow 4067 (seq 14580) is 162 c: re-sent every 5 t. */
        const val GLOW_TICKS = 5

        /** The channel lines (COMM, spec "Channel lines"). */
        fun channelLine(element: Element): String = when (element) {
            Element.FIRE -> "I'll feed the flames!"
            Element.WATER -> "I'll guide the flow!"
            Element.AIR -> "I'll focus the winds!"
            Element.EARTH -> "I'll strengthen the bond!"
        }
    }
}
