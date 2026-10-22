package com.gadscape.raids.tfa.rooms.dagonhai

import com.gadscape.raids.tfa.encounter.Element
import com.gadscape.raids.tfa.encounter.TfaEncounter
import com.gadscape.raids.tfa.encounter.TfaEncounterContext
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.gadscape.raids.tfa.rooms.dagonhai.Timeline.Tag
import com.zenyte.game.content.skills.prayer.Prayer
import com.zenyte.game.util.Direction
import com.zenyte.game.world.World
import com.zenyte.game.world.entity.ForceTalk
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.masks.Animation
import com.zenyte.game.world.entity.masks.Graphics
import com.zenyte.game.world.entity.masks.Hit
import com.zenyte.game.world.entity.masks.HitType
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.npc.combatdefs.AttackType
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.action.combat.CombatUtilities
import org.slf4j.LoggerFactory

/**
 * Room 4, the Dagon'hai trio (spec §4.6, WU-25): region 16215, section 3 west.
 *
 * - [build] spawns the cauldron (SW 4064,5598) and the three Dagon'hai at their spawns (Elder (4069,5599), staff monk
 *   (4062,5602), book monk (4062,5596)) with the shared pool for the party scale.
 * - [onArenaEntry] runs the seq prefetch 10011 (and the music once the fight is on).
 * - [start] (first transporter use) starts the fight script ([DagonhaiFight]), the cauldron HUD and the music.
 * - [tick] (spec §2.5): a depletion from last tick's hits becomes a break, then the timeline (landing hits, tome
 *   impacts, hazards), then the script's decisions, then the imps' targets, the HUD and the pool headbars.
 * - [isComplete]: the two 7-HP survivors are dead and their death animations have played.
 * - [onComplete] (once), [onWipe], [teardown]: everything the room created is cancelled and removed.
 *
 * All coordinates here are static; [translate] / [toStatic] go through the raid's `TfaInstance`.
 */
class DagonhaiRoom(override val context: TfaEncounterContext) : TfaEncounter, DagonhaiHost {

    override val room: TfaRoomId get() = TfaRoomId.DAGONHAI

    /** The raid's snapshot of `dagonhai.json`. */
    val cfg: DagonhaiConfig = context.config[DagonhaiConfig]

    /**
     * The shared pool per break for this party (spec §2.6: 640 / 913 / 1187 / 1460 / 1734 / 2007 / 2281). Computed at
     * build from the scale the raid would use then, and again at [start], when the raid fixes the scale.
     */
    var poolMax: Int = cfg.scaledPool(context.scaling, context.scale)
        private set

    override val contributionPool: Int get() = Bridge.contributionPool(context)

    override val phases: List<String> = PHASES

    internal val timeline = Timeline()
    internal val fight = DagonhaiFight(cfg, timeline, context.random, this, poolMax)

    private val trio = LinkedHashMap<Role, DagonhaiNpc>()
    private var cauldron: Cauldron? = null
    private val imps = ArrayList<Imp>()
    private val rifts = HashMap<Int, DemonicRift>()
    private var nextRift = 0
    private val fighterMap = LinkedHashMap<Player, PlayerFighter>()
    private val hud = Bridge.RitualHud(DagonhaiIds.CAULDRON, if (cfg.hud.recolourExtraBar) cfg.hud.extraBarColours else null)
    private var hudState = intArrayOf(cfg.stability, cfg.stability, 0, cfg.ritualQuarters)
    private var shownPool = -1
    private var poolBarTick = 0
    private var trioShield = ShieldState.SHIELDED

    private var built = false
    private var started = false
    private var completed = false
    private var tornDown = false

    // ================================================================================== TfaEncounter lifecycle

    override fun build() {
        if (built) return
        built = true
        cauldron = Cauldron(this, context, translate(cfg.arena.cauldron)).also {
            it.spawn()
            it.setStability(cfg.stability)
        }
        for (role in Role.entries) {
            val npc = DagonhaiNpc(this, context, role, translate(cfg.arena.spawn(role)), Direction.WEST)
            npc.spawn()
            npc.setHitpoints(poolMax)
            npc.setFaceLocation(translate(DagonhaiArena.CAULDRON_CENTRE))
            trio[role] = npc
        }
        log.debug("Dagon'hai room built (scale {}, pool {})", context.scale, poolMax)
    }

    override fun onArenaEntry(player: Player) {
        Bridge.prefetch(context, player)
        if (started && !completed) Bridge.roomMusic(context, player)
    }

    override fun start() {
        if (started || tornDown) return
        if (!built) build()
        started = true
        val scaled = cfg.scaledPool(context.scaling, context.scale)
        if (scaled != poolMax && fight.rescale(scaled)) {
            poolMax = scaled
            for (npc in trio.values) if (!npc.isFinished) npc.setHitpoints(scaled)
        }
        for (p in context.playersInArena()) Bridge.roomMusic(context, p)
        refreshFighters()
        fight.start()
        syncHud()
    }

    override fun tick() {
        if (!started || tornDown || completed) return
        timeline.step()
        refreshFighters()
        fight.preTick()
        timeline.runDue()
        fight.tick()
        impsTick()
        syncHud()
        poolBarKeepalive()
    }

    override fun onPlayerDeath(player: Player) {
        val f = fighterMap[player] ?: return
        fight.onFighterLost(f)
    }

    override fun isComplete(): Boolean = started && !tornDown && fight.complete

    override fun onComplete() {
        if (completed) return
        completed = true
        timeline.cancelAll()
        fight.stop()
        despawnEverything()
        hud.closeAll()
        log.info("Dagon'hai room complete at tick {} (scale {}, breaks {})", timeline.now, context.scale, fight.breaks)
    }

    override fun onWipe() {
        teardown()
    }

    override fun teardown() {
        if (tornDown) return
        tornDown = true
        timeline.cancelAll()
        fight.stop()
        despawnEverything()
        hud.closeAll()
        fighterMap.clear()
    }

    private fun despawnEverything() {
        for (npc in trio.values) {
            if (!npc.isFinished) {
                Bridge.chargeBar(npc, 0)
                npc.finish()
            }
        }
        trio.clear()
        cauldron?.let { if (!it.isFinished) it.finish() }
        cauldron = null
        despawnAdds()
    }

    // ========================================================================================== debug hooks

    override fun debugSkipToPhase(phase: String): Boolean {
        if (tornDown || completed) return false
        if (!built) build()
        if (!started) start()
        return when (phase.lowercase()) {
            PHASE_SHIELDED -> when (fight.phase) {
                DagonhaiFight.Phase.INTRO -> fight.debugEndIntro()
                DagonhaiFight.Phase.PROTECTED -> fight.debugEndProtection()
                DagonhaiFight.Phase.SHIELDED -> true
                else -> false
            }
            PHASE_PROTECTED -> { ensureShielded(); fight.debugBreak(toExplosion = false) }
            PHASE_EMPOWERED -> { ensureShielded(); fight.debugRitual(cfg.ritualQuarters) }
            PHASE_EXPLOSION -> { ensureShielded(); fight.debugBreak(toExplosion = true) }
            PHASE_DEATH -> {
                if (fight.phase != DagonhaiFight.Phase.FINALE) return false
                trio.values.filter { it.shield == ShieldState.UNSHIELDED && !it.killed }.forEach { it.setHitpoints(0) }
                true
            }
            else -> false
        }
    }

    private fun ensureShielded() {
        if (fight.phase == DagonhaiFight.Phase.INTRO) fight.debugEndIntro()
        if (fight.phase == DagonhaiFight.Phase.PROTECTED) fight.debugEndProtection()
    }

    override fun debugSetHpPercent(percent: Int): Boolean = fight.debugSetPoolPercent(percent)

    override fun debugState(): List<String> = buildList {
        add("Dagon'hai room: built $built, started $started, completed $completed, torn down $tornDown, scale ${context.scale}")
        addAll(fight.describe())
        add("  npcs: ${trio.values.joinToString { "${it.role.key} ${it.id} hp ${it.hitpoints}${if (it.killed) " dead" else ""}" }}, " +
            "cauldron ${cauldron?.hitpoints ?: "-"}, imps ${impsAlive()}/${imps.size}, rifts ${rifts.size}, pending ${timeline.pending()}")
        add("  fighters: ${fighterMap.values.joinToString { "${it.player.username}@${it.tile}${if (it.alive) "" else " (out)"}" }}")
    }

    // ===================================================================================== engine callbacks

    /** True while hits on the shielded trio count (not protected, not mid-break). */
    internal fun poolOpen(): Boolean = fight.poolOpen() && trioShield == ShieldState.SHIELDED

    /** True while the trio may not be attacked: a break is pending or the protection holds. */
    internal fun poolBlocked(): Boolean = started && (fight.breakPending || trioShield == ShieldState.PROTECTED)

    /** A hit was processed on one of the trio (spec "Shared pool"): sync the pool to the other two. */
    internal fun onTrioHit(npc: DagonhaiNpc, hit: Hit) {
        if (!started) {
            // Hit before the trigger (the transporter starts the fight): start it now, through the raid when there is one.
            if (!Bridge.requestStart(context, hit.source as? Player)) start()
            return
        }
        if (npc.shield != ShieldState.SHIELDED || npc.isDead) return
        fight.onPoolHit(npc.hitpoints)
    }

    internal fun onPoolDepleted(npc: DagonhaiNpc) {
        if (!started) return
        fight.onPoolDepleted()
        log.debug("Dagon'hai pool depleted by a hit on the {} (break {} pending)", npc.role.key, fight.breaks + 1)
    }

    internal fun onFinalDeath(npc: DagonhaiNpc) = fight.onFinalDeath(npc.role)

    internal fun onSpellLanded(npc: DagonhaiNpc, element: Element?, splash: Boolean) {
        if (npc.shield == ShieldState.UNSHIELDED) return
        fight.onSpellLanded(npc.role, element, splash)
    }

    internal fun strengthMultiplier(player: Player): Double {
        val f = fighterMap[player] ?: return 1.0
        return fight.consumeStrength(f)
    }

    internal fun onImpDeath(imp: Imp, killer: Player?) {
        val f = killer?.let { fighterMap[it] }
        fight.onImpDeath(f)
        timeline.schedule(cfg.protect.impDespawn, Tag.OTHER) {
            if (!imp.isFinished) imp.finish()
            imps.remove(imp)
        }
    }

    /**
     * NPC accuracy roll against [target] with the raid's random (Zenyte's `CombatUtilities.getHitAccuracy` formula),
     * then a uniform 0..[max]. Godmode players take nothing.
     */
    internal fun rollNpcHit(npc: NPC, target: Player, max: Int, style: AttackType): Int {
        if (Bridge.isGodmode(target) || max <= 0) return 0
        val accuracy = CombatUtilities.getHitAccuracy(npc, target, style)
        if (accuracy < context.random.nextDouble()) return 0
        return CombatUtilities.clampMaxHit(npc, target, context.random.nextInt(max + 1))
    }

    // ============================================================================================ internals

    internal fun translate(tile: StaticTile): Location = context.instance.translate(tile)

    internal fun toStatic(location: Location): StaticTile =
        context.instance.toStatic(location) ?: StaticTile(-1, -1, location.plane)

    /** True while [player] is a living member inside this arena. */
    internal fun isFighting(player: Player): Boolean =
        !player.isDead && !player.isFinished && player in context.members && DagonhaiArena.onFloor(toStatic(player.location))

    private fun refreshFighters() {
        val inArena = context.playersInArena()
        for (p in inArena) fighterMap.getOrPut(p) { PlayerFighter(p, this) }
        val gone = fighterMap.keys.filter { it !in inArena || it.isFinished }
        for (p in gone) {
            val f = fighterMap.remove(p) ?: continue
            fight.onFighterLost(f)
        }
    }

    private fun syncHud() {
        hud.sync(Bridge.hudViewers(context), hudState[0], hudState[1], hudState[2], hudState[3])
    }

    /** Re-sends the pool bar (86 fades 10 t after an update; 89 is held, re-sent for late observers). */
    private fun poolBarKeepalive() {
        if (trioShield == ShieldState.UNSHIELDED || timeline.now - poolBarTick < Bridge.KEEPALIVE_TICKS) return
        showPoolBars(fight.pool, poolMax)
    }

    private fun showPoolBars(pool: Int, max: Int) {
        poolBarTick = timeline.now
        for (npc in trio.values) {
            if (npc.isFinished || npc.killed || npc.shield == ShieldState.UNSHIELDED) continue
            Bridge.poolBar(npc, cfg.poolHeadbar, pool, max)
        }
    }

    /** Active imps whose target died or left go for a random player in the arena. */
    private fun impsTick() {
        val alive = fighterMap.values.filter { it.alive }
        for (imp in imps) {
            if (imp.dying || imp.isDead || imp.isFinished) continue
            val target = imp.combat.target as? Player
            if (target != null && isFighting(target)) continue
            if (alive.isEmpty()) continue
            imp.setTarget(alive[context.random.nextInt(alive.size)].player)
        }
    }

    // ======================================================================================== DagonhaiHost

    override fun fighters(): List<Fighter> = fighterMap.values.toList()

    private fun npc(role: Role): DagonhaiNpc? = trio[role]?.takeIf { !it.isFinished }

    override fun trioTile(role: Role): StaticTile? = npc(role)?.let { toStatic(it.location) }

    override fun trioAnimate(role: Role, seq: Int) {
        npc(role)?.setAnimation(Animation(seq))
    }

    override fun trioGraphic(role: Role, spot: Int) {
        npc(role)?.setGraphics(if (spot < 0) Graphics.RESET else Graphics(spot))
    }

    override fun trioSay(role: Role, text: String) {
        npc(role)?.setForceTalk(ForceTalk(text))
    }

    override fun trioFace(role: Role, target: Fighter) {
        val p = (target as? PlayerFighter)?.player ?: return
        npc(role)?.setFaceEntity(p)
    }

    override fun trioFaceTile(role: Role, tile: StaticTile) {
        val n = npc(role) ?: return
        n.setFaceEntity(null)
        n.setFaceLocation(translate(tile))
    }

    override fun trioWalk(role: Role, tile: StaticTile) {
        val n = npc(role) ?: return
        n.setFaceEntity(null)
        n.resetWalkSteps()
        val to = translate(tile)
        if (!n.calcFollow(to, -1, true, true, false)) n.addWalkSteps(to.x, to.y, -1, true)
    }

    override fun trioMoving(role: Role): Boolean = npc(role)?.hasWalkSteps() == true

    override fun trioStop(role: Role) {
        npc(role)?.resetWalkSteps()
    }

    override fun trioPlace(role: Role, tile: StaticTile) {
        val n = npc(role) ?: return
        n.resetWalkSteps()
        n.setLocation(translate(tile))
    }

    override fun trioPool(pool: Int, max: Int) {
        // While protected (pool 0) the NPCs keep full HP internally: nothing can hit them and isDead() stays false.
        val hp = if (pool > 0) pool else max
        for (npc in trio.values) {
            if (npc.isFinished || npc.killed || npc.shield == ShieldState.UNSHIELDED) continue
            if (npc.hitpoints != hp) npc.setHitpoints(hp)
        }
        if (pool != shownPool) {
            shownPool = pool
            showPoolBars(pool, max)
        }
    }

    override fun trioShield(state: ShieldState) {
        trioShield = state
        for (npc in trio.values) if (npc.shield != ShieldState.UNSHIELDED) npc.shield = state
        if (state == ShieldState.UNSHIELDED) {
            for (npc in trio.values) if (!npc.isFinished) Bridge.removePoolBar(npc, cfg.poolHeadbar)
        }
    }

    override fun trioEmpowered(empowered: Boolean) {
        for (npc in trio.values) npc.empowered = empowered && npc.shield != ShieldState.UNSHIELDED
    }

    override fun chargeBar(role: Role, ticks: Int) {
        npc(role)?.let { Bridge.chargeBar(it, ticks) }
    }

    override fun trioUnshield(role: Role, hp: Int) {
        val n = npc(role) ?: return
        Bridge.chargeBar(n, 0)
        Bridge.removePoolBar(n, cfg.poolHeadbar)
        n.unshield(hp)
    }

    override fun trioKill(role: Role) {
        val n = npc(role) ?: return
        Bridge.chargeBar(n, 0)
        Bridge.removePoolBar(n, cfg.poolHeadbar)
        n.markKilled()
    }

    override fun trioDespawn(role: Role) {
        val n = trio[role] ?: return
        if (!n.isFinished) n.finish()
    }

    override fun trioAttack(role: Role, target: Fighter, style: Style, max: Int) {
        val n = npc(role) ?: return
        if (n.killed) return
        val p = (target as? PlayerFighter)?.player ?: return
        if (!isFighting(p)) return
        val engineStyle = if (style == Style.MAGIC) AttackType.MAGIC else AttackType.RANGED
        val damage = rollNpcHit(n, p, max, engineStyle)
        val type = if (style == Style.MAGIC) HitType.MAGIC else HitType.RANGED
        // Lands this tick; the protection prayer is checked as it lands (Player.appendHitEntry, NPC prayer multiplier).
        CombatUtilities.delayHit(n, -1, p, Hit(n, damage, type))
    }

    override fun cauldronAnimate(seq: Int) {
        cauldron?.takeIf { !it.isFinished }?.setAnimation(Animation(seq))
    }

    override fun cauldronGraphic(spot: Int) {
        cauldron?.takeIf { !it.isFinished }?.setGraphics(Graphics(spot))
    }

    override fun cauldronExplodeModel() {
        cauldron?.takeIf { !it.isFinished }?.explodeModel()
    }

    override fun cauldronDespawn() {
        cauldron?.let { if (!it.isFinished) it.finish() }
    }

    override fun ritualHud(stability: Int, stabilityMax: Int, ritual: Int, ritualMax: Int) {
        hudState = intArrayOf(stability, stabilityMax, ritual, ritualMax)
        cauldron?.takeIf { !it.isFinished }?.setStability(stability)
        syncHud()
    }

    override fun bookAnimate(seq: Int) {
        Bridge.animateBook(translate(BOOK_TILE), seq)
    }

    override fun damage(target: Fighter, amount: Int, burn: Boolean) {
        val p = (target as? PlayerFighter)?.player ?: return
        if (amount <= 0 || !isFighting(p) || Bridge.isGodmode(p)) return
        p.applyHit(Hit(amount, if (burn) HitType.BURN else HitType.TYPELESS))
    }

    override fun heal(target: Fighter, hp: Int, prayer: Int) {
        val p = (target as? PlayerFighter)?.player ?: return
        if (!isFighting(p)) return
        if (hp > 0) p.heal(hp)
        if (prayer > 0) p.prayerManager.restorePrayerPoints(prayer)
    }

    override fun playerGraphic(target: Fighter, spot: Int, height: Int) {
        val p = (target as? PlayerFighter)?.player ?: return
        if (p.isFinished) return
        p.setGraphics(Graphics(spot, 0, height))
    }

    override fun graphic(spot: Int, tile: StaticTile, height: Int, delayCycles: Int) {
        Bridge.telegraph(context, spot, tile, height, delayCycles)
    }

    override fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, target: Fighter) {
        val source: NPC = (if (from == null) cauldron else npc(from)) ?: return
        val p = (target as? PlayerFighter)?.player ?: return
        World.sendProjectile(source, p, Bridge.projectile(spot, spec))
    }

    override fun projectile(from: Role?, spot: Int, spec: DagonhaiConfig.ProjectileSpec, tile: StaticTile) {
        val source: NPC = (if (from == null) cauldron else npc(from)) ?: return
        World.sendProjectile(source, translate(tile), Bridge.projectile(spot, spec))
    }

    override fun spawnRift(tile: StaticTile): Int {
        val handle = nextRift++
        val rift = DemonicRift(this, context, translate(tile))
        rift.spawn()
        rifts[handle] = rift
        return handle
    }

    override fun despawnRift(handle: Int) {
        val rift = rifts.remove(handle) ?: return
        if (!rift.isFinished) rift.finish()
    }

    override fun spawnImp(tile: StaticTile, target: Fighter?) {
        val imp = Imp(this, context, translate(tile))
        imp.spawn()
        imps += imp
        val p = (target as? PlayerFighter)?.player
        if (p != null && isFighting(p)) imp.setTarget(p)
    }

    override fun impsAlive(): Int = imps.count { !it.dying && !it.isDead && !it.isFinished }

    override fun despawnAdds() {
        imps.forEach { if (!it.isFinished) it.finish() }
        imps.clear()
        rifts.values.forEach { if (!it.isFinished) it.finish() }
        rifts.clear()
    }

    /** A player in the arena, wrapped once so the script can keep a focus target, buffs and burns. */
    internal class PlayerFighter(val player: Player, private val room: DagonhaiRoom) : Fighter {
        override val tile: StaticTile get() = room.toStatic(player.location)
        override val alive: Boolean get() = room.isFighting(player)
        override fun praying(protect: Protect): Boolean = player.prayerManager.isActive(
            when (protect) {
                Protect.MELEE -> Prayer.PROTECT_FROM_MELEE
                Protect.MISSILES -> Prayer.PROTECT_FROM_MISSILES
                Protect.MAGIC -> Prayer.PROTECT_FROM_MAGIC
            },
        )

        override fun toString(): String = "PlayerFighter(${player.username})"
    }

    // ===================================================================================== room tools (::tfa)

    /** `::tfa dagonhai <action>` (see [DagonhaiDebug]); returns a message. */
    internal fun debugAction(action: String, args: List<String>): String {
        if (!started) return "The fight has not started."
        if (completed || tornDown) return "The room is over."
        return when (action) {
            "pool" -> {
                val pct = args.firstOrNull()?.toIntOrNull() ?: return "Usage: ::tfa dagonhai pool <0-100>"
                if (fight.debugSetPoolPercent(pct)) "Pool set to $pct%." else "The pool is closed now (${fight.phase})."
            }
            "channel" -> {
                val element = args.firstNotNullOfOrNull { a -> Element.entries.firstOrNull { it.name.equals(a, true) } }
                val role = args.firstNotNullOfOrNull { Role.byKey(it) }
                if (fight.debugChannel(role, element)) "Channel forced." else "Cannot channel now (${fight.phase})."
            }
            "tome" -> {
                val god = args.firstNotNullOfOrNull { God.byKey(it) }
                val desk = args.firstNotNullOfOrNull { a -> com.gadscape.raids.tfa.raid.Compass.entries.firstOrNull { it.name.equals(a, true) } }
                val role = args.firstNotNullOfOrNull { Role.byKey(it) }?.takeIf { it.isMonk }
                if (fight.debugTome(god, desk, role)) "Tome forced." else "No monk can throw now (${fight.phase})."
            }
            "break" -> if (fight.debugBreak(toExplosion = false)) "The shield breaks next tick." else "Cannot break now (${fight.phase})."
            "explode" -> if (fight.debugBreak(toExplosion = true)) "The cauldron explodes next tick." else "Cannot explode now (${fight.phase})."
            "ritual" -> {
                val q = args.firstOrNull()?.toIntOrNull() ?: return "Usage: ::tfa dagonhai ritual <0-${cfg.ritualQuarters}>"
                if (fight.debugRitual(q)) "Ritual set to $q." else "Cannot set the ritual now (${fight.phase})."
            }
            "unprotect" -> if (fight.debugEndProtection()) "Protection ended." else "Not protected."
            "imps" -> {
                imps.filter { !it.dying && !it.isFinished }.forEach { it.setHitpoints(0) }
                "Imps killed."
            }
            else -> "Unknown action \"$action\"."
        }
    }

    companion object {
        const val PHASE_SHIELDED = "shielded"
        const val PHASE_PROTECTED = "protected"
        const val PHASE_EMPOWERED = "empowered"
        const val PHASE_EXPLOSION = "explosion"
        const val PHASE_DEATH = "death"

        val PHASES: List<String> = listOf(PHASE_SHIELDED, PHASE_PROTECTED, PHASE_EMPOWERED, PHASE_EXPLOSION, PHASE_DEATH)

        /** Book of Life and Death 62552, SW (4049,5597) [CACHE r16215_locs]. */
        val BOOK_TILE = StaticTile(4049, 5597)

        private val log = LoggerFactory.getLogger(DagonhaiRoom::class.java)
    }
}
