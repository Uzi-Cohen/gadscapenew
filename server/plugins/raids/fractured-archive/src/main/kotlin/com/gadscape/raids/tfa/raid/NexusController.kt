package com.gadscape.raids.tfa.raid

import com.gadscape.raids.tfa.ids.TfaIds
import com.zenyte.game.util.Direction
import com.zenyte.game.world.entity.Location
import com.zenyte.game.world.entity.npc.NPC
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.dialogue.dialogue
import com.zenyte.game.world.entity.player.dialogue.options
import org.slf4j.LoggerFactory

/**
 * The nexus (static region 16471) of one raid: variants, section doors, Navigator, allies, Quartermaster, Ivy and the
 * Ocellus door (spec §2.4, §3.5, §3.6; WU-13).
 *
 * **Variants.** The nexus is copied from cache plane `nexus.variantPlanes[0]` (1: pre-Guardian, with the records room)
 * to instance plane 0 when the raid is created. `campRecopyDelayTicks` (9) after the Guardian dies, the Alliance-camp
 * variant (`variantPlanes[1]` = 3) is copied to instance plane [TfaBlockInstance.CAMP_PLANE] (2), the nexus region's
 * plane offset becomes 2 (so every static nexus tile translates onto the camp), and everyone in the nexus moves to the
 * same tile of the camp (or the closest walkable one) with a map rebuild. The camp is copied only then, so the client
 * never draws it as a ceiling over the pre-Guardian floor.
 *
 * **Section doors** (§3.5): spawned closed (63094) on build; the Navigator's "Yes" opens both doors of the next
 * section (the "slightly" id plays 15020 `entrance_guthixian01_doors01_open`, 76 cycles ≈ 3 t [CACHE seq_cfg], then the
 * "slightly_opened" id with 15021 idle); the first Enter commits the side: the other door plays 63095 (15022 close,
 * 3 t) then becomes 63094. Doors are type 10, 5 wide, west x 4113 rot 3, east x 4141 rot 1 [CACHE].
 */
class NexusController(val raid: TfaRaid) {

    private val log = LoggerFactory.getLogger(NexusController::class.java)

    /** Navigator 16700 waiting in the nexus, and the section its "Yes" opens (4 = the inner vault). */
    var navigator: NPC? = null
        private set
    private var navigatorSection = 0
    private var navigatorEarliest = Int.MAX_VALUE
    private var navigatorLatest = Int.MAX_VALUE
    private var pendingSection = 0

    /** Sections whose doors are open (Navigator "Yes"). */
    private val openSections = HashSet<Int>()

    private val spawned = ArrayList<NPC>()
    private var quartermaster: NPC? = null
    private var camp = false
    private var vaultCopied = false
    private var ocellusOpen = false

    val isCamp: Boolean get() = camp

    private val doorSeqTicks = 3 // 15020 / 15022: 76 client cycles [CACHE seq_cfg]

    // ========================================================================================================= build

    /** Raid created: copy the pre-Guardian variant and close every door. */
    fun build() {
        val nexus = TfaIds.Region.NEXUS
        raid.instance.copyRegionPlane(nexus, raid.config.raid.nexus.variantPlanes[0], 0)
        spawnClosedDoors()
        spawnOcellusDoorClosed()
    }

    private fun spawnClosedDoors() {
        if (!raid.config.raid.nexus.doorsLockedUntilNavigator) return
        for (section in 1..3) for (side in Side.entries) {
            val spot = RaidGeometry.door(section, side)
            RaidWorld.spawnLoc(TfaIds.Loc.SECTION_DOOR_CLOSED, raid.translate(spot.tile), spot.rotation)
        }
    }

    private fun spawnOcellusDoorClosed() {
        RaidWorld.spawnLoc(TfaIds.Loc.OCELLUS_DOOR_CLOSED, raid.translate(RaidGeometry.OCELLUS_DOOR), 0)
    }

    // ===================================================================================================== camp swap

    /** Guardian death + 9 t: the Alliance camp (spec §2.4). */
    fun swapToCamp() {
        if (camp) return
        camp = true
        val nexus = TfaIds.Region.NEXUS
        raid.instance.copyRegionPlane(nexus, raid.config.raid.nexus.variantPlanes[1], TfaBlockInstance.CAMP_PLANE)
        raid.instance.mapping.setPlaneOffset(nexus, TfaBlockInstance.CAMP_PLANE)
        spawnClosedDoors()
        spawnOcellusDoorClosed()
        // Everyone standing in the nexus moves onto the same tile of the camp.
        for (p in raid.area.players.toList()) {
            val s = raid.instance.toStatic(p.location) ?: continue
            if (s !in RaidGeometry.NEXUS || p.plane == TfaBlockInstance.CAMP_PLANE) continue
            val target = Location(p.x, p.y, TfaBlockInstance.CAMP_PLANE)
            RaidWorld.move(p, RaidWorld.landing(target, raid.framework.raid.landingSearchRadius))
        }
        raid.rebuildMaps()
        spawnCampNpcs()
        log.info("Fractured Archive raid {}: nexus swapped to the Alliance camp", raid.id)
    }

    private fun spawnCampNpcs() {
        val fw = raid.framework.nexus
        val tiles = raid.config.raid.tiles
        quartermaster = spawnNpc(TfaIds.Npc.QUARTERMASTER_INACTIVE, tiles.quartermaster, Direction.SOUTH)
        if (fw.talkToAllies) {
            spawnNpc(TfaIds.Npc.ARMACUS_TALK, fw.armacusTile, Direction.SOUTH)
            spawnNpc(TfaIds.Npc.EVA_TALK, fw.evaTile, Direction.SOUTH)
        }
        if (fw.campAllies) {
            spawnNpc(TfaIds.Npc.ARMACUS_CAMP, fw.campArmacusTile, Direction.NORTH)
            spawnNpc(TfaIds.Npc.EVA_CAMP, fw.campEvaTile, Direction.NORTH)
        }
        if (fw.campMeritScroll) {
            // In-raid scoreboard 62774 (3x2, op Read; not placed in any map) [CACHE, INF placement]; WU-19 handles Read.
            RaidWorld.spawnLoc(TfaIds.Loc.MERIT_SCROLL_CAMP, raid.translate(tiles.campMeritScroll), 0)
        }
    }

    private fun spawnNpc(id: Int, tile: StaticTile, facing: Direction): NPC? = try {
        val npc = NPC(id, raid.translate(tile), facing, 0, true)
        npc.spawn()
        spawned += npc
        npc
    } catch (e: Exception) {
        log.error("Fractured Archive raid {}: spawning npc {} failed", raid.id, id, e)
        null
    }

    /** Room 3 done: the Quartermaster 16761 replaces 16762 (§3.5 step 6). */
    fun activateQuartermaster() {
        val old = quartermaster
        if (old != null && old.id == TfaIds.Npc.QUARTERMASTER) return
        old?.let {
            spawned.remove(it)
            if (!it.isFinished) it.finish()
        }
        quartermaster = spawnNpc(TfaIds.Npc.QUARTERMASTER, raid.config.raid.tiles.quartermaster, Direction.SOUTH)
    }

    // ===================================================================================================== Navigator

    /**
     * After a completion: the Navigator 16700 appears at `tiles.navigatorSpawn` once the party is back in the nexus,
     * or `nexus.navigatorDelayTicks` (10) after the completion at the latest (§3.5 step 2). [section] is the section
     * its "Yes" opens (1-3), or 4 for the inner vault.
     */
    fun scheduleNavigator(section: Int) {
        pendingSection = section
        val now = raid.ticks
        val cfg = raid.config.raid
        // Not before the camp exists (Guardian) or the room exit moved everyone (paired rooms).
        navigatorEarliest = now + if (section == 1) cfg.nexus.campRecopyDelayTicks + 1 else cfg.roomExitDelayTicks + 1
        navigatorLatest = maxOf(navigatorEarliest, now + cfg.nexus.navigatorDelayTicks)
        if (section == 3) {
            raid.scheduler.schedule(0, "nexus:quartermaster") { activateQuartermaster() }
        }
    }

    fun tick() {
        if (pendingSection == 0 || raid.phase == RaidPhase.ENDED) return
        val now = raid.ticks
        if (now < navigatorEarliest) return
        val inside = raid.membersInside().filter { !it.dead }
        val allBack = inside.isNotEmpty() && inside.all { it.zone == Zone.NEXUS }
        if (allBack || now >= navigatorLatest) {
            spawnNavigator(pendingSection)
            pendingSection = 0
        }
    }

    private fun spawnNavigator(section: Int) {
        navigator?.let { if (!it.isFinished) it.finish() }
        val npc = spawnNpc(TfaIds.Npc.NAVIGATOR, raid.config.raid.tiles.navigatorSpawn, Direction.SOUTH) ?: return
        navigator = npc
        navigatorSection = section
    }

    /** The Navigator's lines [DOC transcript]; after section 1 the first line is reused [HOUSE]. */
    private fun navigatorLine(section: Int, name: String): String = when (section) {
        1, 2 -> "Good work, $name. Our forces have held so far, but the next foes await."
        3 -> "Thank you, $name. We're almost there. There are only two sets of intruders left."
        else -> "I am sorry to be the bearer of bad news, $name. There has been a disturbance in the inner vault. " +
            "Ivy has requested your presence immediately."
    }

    /** "Follow" (dialogue) / "Quick-follow" (straight to Yes) on the Navigator; any member (§3.5 step 2). */
    fun follow(player: Player, npc: NPC, quick: Boolean) {
        val member = raid.memberOf(player) ?: return
        if (member.dead || npc !== navigator) return
        val section = navigatorSection
        if (quick) {
            onNavigatorYes(section)
            return
        }
        player.dialogue(TfaIds.Npc.NAVIGATOR) {
            npc("Alliance Navigator", navigatorLine(section, player.name))
            options("Are you ready to proceed?") {
                "Yes." { onNavigatorYes(section) }
                "No." {}
            }
        }
    }

    private fun onNavigatorYes(section: Int) {
        val npc = navigator ?: return
        if (section != navigatorSection || npc.isFinished) return
        navigator = null
        navigatorSection = 0
        val target = if (section in 1..3) {
            val y = RaidGeometry.door(section, Side.WEST).tile.y + 2
            raid.translate(StaticTile(4127, y, 0))
        } else raid.translate(RaidGeometry.OCELLUS_DOOR.offset(3, -1))
        npc.addWalkSteps(target.x, target.y, -1, false)
        raid.scheduler.schedule(raid.framework.nexus.navigatorWalkTicks, "nexus:navigator") {
            spawned.remove(npc)
            if (!npc.isFinished) npc.finish()
        }
        if (section in 1..3) openSection(section) else enterFinal()
    }

    // ================================================================================================ section doors

    /** Opens both doors of [section] (§3.5 step 2). */
    fun openSection(section: Int) {
        if (!openSections.add(section)) return
        raid.setPhase(RaidPhase.SECTION_OPEN)
        for (side in Side.entries) {
            val spot = RaidGeometry.door(section, side)
            val ids = RaidGeometry.doorIds(section, side)
            val tile = raid.translate(spot.tile)
            RaidWorld.spawnLoc(ids.slightly, tile, spot.rotation) // anim 15020 open (loc config)
            raid.scheduler.schedule(doorSeqTicks, "door:$section:${side.name}") {
                if (raid.route.side(section) == null || raid.route.side(section) == side) {
                    RaidWorld.spawnLoc(ids.opened, tile, spot.rotation) // anim 15021 open idle
                }
            }
        }
        raid.broadcast("The doors to the next section of the Archive open.")
    }

    fun isOpen(section: Int): Boolean = section in openSections

    /**
     * Enter / Quick-enter on a section door (§3.5 step 3). The first Enter commits the side (with a confirmation once
     * per section unless [quick]); later Enters on the committed door take the member to its antechamber.
     */
    fun enterDoor(player: Player, section: Int, side: Side, quick: Boolean) {
        val member = raid.memberOf(player) ?: return
        if (member.dead) return
        val open = isOpen(section) ||
            (!raid.config.raid.nexus.doorsLockedUntilNavigator && raid.route.nextSection() == section)
        if (!open) {
            player.sendMessage("The door is firmly shut.")
            return
        }
        val committed = raid.route.side(section)
        if (committed != null && committed != side) {
            player.sendMessage("Your party has committed to the other path.")
            return
        }
        if (committed == null) {
            val ask = !quick && raid.config.raid.nexus.confirmSideOnce && section !in member.confirmedSections
            if (ask) {
                player.dialogue {
                    options("Your party will be committed to this path.") {
                        "Proceed." {
                            member.confirmedSections += section
                            enterDoor(player, section, side, true)
                        }
                        "Cancel." {}
                    }
                }
                return
            }
            commit(section, side)
        }
        val room = TfaRoomId.of(section, side)
        raid.moveToAntechamber(member, room)
    }

    /**
     * Commits [section] to [side]: path varbit, the other door closes, the room is copied and built ([build] = false
     * for a section `::tfa skipto` passes over).
     */
    fun commit(section: Int, side: Side, build: Boolean = true) {
        if (!raid.route.commit(section, side)) return
        val room = TfaRoomId.of(section, side)
        // The other door: 63095 (15022 close, 3 t) then 63094 [CACHE].
        val other = if (side == Side.WEST) Side.EAST else Side.WEST
        val spot = RaidGeometry.door(section, other)
        val tile = raid.translate(spot.tile)
        raid.scheduler.cancel("door:$section:${other.name}")
        RaidWorld.spawnLoc(TfaIds.Loc.SECTION_DOOR_CLOSING, tile, spot.rotation)
        raid.scheduler.schedule(doorSeqTicks, "door:$section:${other.name}:closed") {
            RaidWorld.spawnLoc(TfaIds.Loc.SECTION_DOOR_CLOSED, tile, spot.rotation)
        }
        if (build) {
            raid.instance.copyRegion(room.staticRegion)
            raid.rebuildMaps()
            raid.buildRoom(room)
        }
        raid.hud.markDirty()
        log.info("Fractured Archive raid {}: section {} committed to {} ({})", raid.id, section, side, room)
    }

    // ============================================================================================ inner vault (§3.6)

    /** Section 3 done and the Navigator's "Yes": NEXUS_FINAL — Ivy, the Ocellus region, the Archive Door opens. */
    fun enterFinal() {
        if (ocellusOpen) return
        ocellusOpen = true
        raid.setPhase(RaidPhase.NEXUS_FINAL)
        raid.instance.copyRegion(TfaIds.Region.OCELLUS)
        raid.rebuildMaps()
        raid.buildRoom(TfaRoomId.OCELLUS)
        // 63111 "Archive Door" (Enter / Quick-enter) carries anim 15020 open, so spawning it plays the opening [CACHE].
        RaidWorld.spawnLoc(TfaIds.Loc.OCELLUS_DOOR_OPEN, raid.translate(RaidGeometry.OCELLUS_DOOR), 0)
        spawnNpc(TfaIds.Npc.IVY, raid.config.raid.tiles.ivy, Direction.SOUTH)
        raid.broadcast("The Archive Door to the inner vault opens.")
    }

    val isOcellusOpen: Boolean get() = ocellusOpen

    /** Ocellus is dead: copy the vault (16474, all planes; players stand on plane 2). */
    fun copyVault() {
        if (vaultCopied) return
        vaultCopied = true
        raid.instance.copyRegion(TfaIds.Region.VAULT)
        raid.rebuildMaps()
    }

    /** Despawns the NPCs the nexus spawned (release). */
    fun cleanup() {
        for (npc in spawned) if (!npc.isFinished) npc.finish()
        spawned.clear()
        navigator = null
        quartermaster = null
    }
}
