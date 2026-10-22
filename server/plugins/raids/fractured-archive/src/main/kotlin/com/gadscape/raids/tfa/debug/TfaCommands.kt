package com.gadscape.raids.tfa.debug

import com.gadscape.raids.tfa.TfaServices
import com.gadscape.raids.tfa.config.Severity
import com.gadscape.raids.tfa.config.TfaConfig
import com.gadscape.raids.tfa.encounter.TfaEncounters
import com.gadscape.raids.tfa.ids.RemapTable
import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.content.achievementdiary.Diary
import com.zenyte.game.world.entity.player.GameCommands
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.privilege.PlayerPrivilege
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** One `::tfa` sub-command handler. [args] are the words after the sub-command name. */
fun interface TfaSubcommand {
    fun execute(player: Player, args: List<String>)
}

/**
 * The `::tfa` debug command (WU-08) and `::tfareload`.
 *
 * Access: developers on any world, everyone on a development world (`WORLD_PROFILE.isDevelopment()`).
 *
 * Built in: `help`, `dump [config|ids|raid|all]`, `godmode`, `scale <n|off>`, `hp <pct>`, `skipto <phase>`,
 * `reload`. Reserved slots that later units implement with [bind]:
 *
 * | Slot                | Owner        | Meaning                                                           |
 * |---------------------|--------------|-------------------------------------------------------------------|
 * | [Slot.PARTY]        | WU-11        | `::tfa party [args]`: create / fill a test party led by you       |
 * | [Slot.START]        | WU-12        | `::tfa start`: start a raid for your party now (no door checks)   |
 * | [Slot.SKIPTO_ROOM]  | WU-12, WU-13 | `::tfa skipto <room>`: move your raid to a room, earlier ones done |
 * | [Slot.SKIPTO_VAULT] | WU-29        | `::tfa skipto vault`: roll rewards and go to the vault            |
 *
 * `skipto <phase>` and `hp <pct>` go to the active encounter's `debugSkipToPhase` / `debugSetHpPercent`.
 * Other units may add their own sub-commands with [register] (e.g. `::tfa supplies`).
 */
object TfaCommands {

    private val log = LoggerFactory.getLogger(TfaCommands::class.java)

    /** Journal lines shown in game; the full dump always goes to the server log. */
    private const val JOURNAL_LINES = 160

    /** Reserved sub-commands implemented by later units. */
    enum class Slot(val usage: String, val owner: String, val help: String) {
        PARTY("party [args]", "WU-11 party system", "create or fill a test party led by you"),
        START("start", "WU-12 raid state machine", "start a raid for your party now, skipping the Archive Door checks"),
        SKIPTO_ROOM("skipto <room>", "WU-12/WU-13 raid and nexus flow", "move your raid to a room, marking earlier rooms done"),
        SKIPTO_VAULT("skipto vault", "WU-29 vault and loot", "roll rewards and move your raid to the vault"),
    }

    private class Entry(val name: String, val usage: String, val help: String, val handler: TfaSubcommand)

    private val slots = ConcurrentHashMap<Slot, TfaSubcommand>()
    private val extra = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var installed = false

    /** Implements a reserved [slot]. */
    @JvmStatic
    fun bind(slot: Slot, handler: TfaSubcommand) {
        slots[slot] = handler
    }

    @JvmStatic
    fun isBound(slot: Slot): Boolean = slots.containsKey(slot)

    /** Adds a new sub-command `::tfa <name> ...` (names of built-ins and slots are refused). */
    @JvmStatic
    fun register(name: String, usage: String, help: String, handler: TfaSubcommand) {
        val key = name.lowercase()
        require(key !in BUILT_IN && Slot.entries.none { it.name.lowercase() == key || it.usage.startsWith("$key ") || it.usage == key }) {
            "::tfa $key is reserved"
        }
        extra[key] = Entry(key, usage, help, handler)
    }

    private val BUILT_IN = setOf("help", "dump", "godmode", "scale", "hp", "skipto", "reload")

    /** Registers `::tfa` and `::tfareload` with the game's command table (idempotent). */
    @JvmStatic
    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            GameCommands.Command(PlayerPrivilege.DEVELOPER, "tfa", "Fractured Archive debug tools: ::tfa help") { p, args ->
                execute(p, args.toList())
            }.orAllowIf { TfaDebug.isDevelopmentWorld() }
            GameCommands.Command(PlayerPrivilege.DEVELOPER, "tfareload", "Reloads the Fractured Archive config files.") { p, _ ->
                reload(p)
            }.orAllowIf { TfaDebug.isDevelopmentWorld() }
            installed = true
        }
    }

    /** Dispatches `::tfa <args>`. */
    @JvmStatic
    fun execute(player: Player, args: List<String>) {
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        val rest = args.drop(1)
        try {
            when (sub) {
                "help" -> help(player)
                "dump" -> dump(player, rest.firstOrNull()?.lowercase() ?: "all")
                "reload" -> reload(player)
                "godmode", "god" -> {
                    val on = TfaDebug.toggleGodmode(player)
                    player.sendMessage("Fractured Archive godmode ${if (on) "on: your HP and prayer are restored every tick" else "off"}.")
                }
                "scale" -> scale(player, rest)
                "hp" -> hp(player, rest)
                "skipto" -> skipTo(player, rest)
                "party" -> slot(player, Slot.PARTY, rest)
                "start" -> slot(player, Slot.START, rest)
                else -> {
                    val entry = extra[sub]
                    if (entry != null) entry.handler.execute(player, rest)
                    else player.sendMessage("Unknown ::tfa command \"$sub\". Try ::tfa help.")
                }
            }
        } catch (e: Exception) {
            log.error("::tfa {} failed for {}", args.joinToString(" "), player.username, e)
            player.sendMessage("::tfa $sub failed: ${e.message ?: e.javaClass.simpleName} (see the server log).")
        }
    }

    private fun slot(player: Player, slot: Slot, args: List<String>) {
        val handler = slots[slot]
        if (handler == null) {
            player.sendMessage("::tfa ${slot.usage} is not implemented yet (${slot.owner}).")
            return
        }
        handler.execute(player, args)
    }

    private fun help(player: Player) {
        val lines = ArrayList<String>()
        lines += "::tfa dump [config|ids|raid|all] - config house values, issues, ids, services"
        lines += "::tfa reload (or ::tfareload) - re-read data/raids/tfa/*.json"
        lines += "::tfa godmode - toggle HP/prayer top-up for you"
        lines += "::tfa scale <2-8|off> - force the party scale of encounters you start"
        lines += "::tfa hp <0-100> - set the active boss to that % of its scaled HP"
        lines += "::tfa skipto <phase> - jump the active encounter to a phase"
        for (slot in Slot.entries) {
            lines += "::tfa ${slot.usage} - ${slot.help}${if (isBound(slot)) "" else " (not implemented: ${slot.owner})"}"
        }
        extra.values.sortedBy { it.name }.forEach { lines += "::tfa ${it.usage} - ${it.help}" }
        Diary.sendJournal(player, "::tfa commands", lines)
    }

    private fun reload(player: Player) {
        val snap = TfaConfig.reload()
        player.sendMessage(snap.summary() + ".")
        val issues = snap.issues()
        issues.take(5).forEach { player.sendMessage((if (it.severity == Severity.ERROR) "<col=ff0000>" else "<col=ff9040>") + it) }
        if (issues.size > 5) player.sendMessage("... ${issues.size - 5} more problems: ::tfa dump config")
        player.sendMessage("Raids already running keep the config they started with.")
    }

    private fun scale(player: Player, args: List<String>) {
        val arg = args.firstOrNull()
        if (arg == null) {
            player.sendMessage("Forced scale: ${TfaDebug.forcedScale(player) ?: "off"}. Usage: ::tfa scale <2-8|off>")
            return
        }
        if (arg.equals("off", ignoreCase = true)) {
            TfaDebug.forceScale(player, null)
            player.sendMessage("Forced scale off: encounters scale by the members inside the raid.")
            return
        }
        val scaling = TfaConfig.current.scaling
        val n = arg.toIntOrNull()
        if (n == null || n !in scaling.minScale..scaling.maxScale) {
            player.sendMessage("Usage: ::tfa scale <${scaling.minScale}-${scaling.maxScale}|off>")
            return
        }
        TfaDebug.forceScale(player, n)
        player.sendMessage("Encounters started by your raid will scale for $n players (from the next encounter start).")
    }

    private fun hp(player: Player, args: List<String>) {
        val pct = args.firstOrNull()?.toIntOrNull()
        if (pct == null || pct !in 0..100) {
            player.sendMessage("Usage: ::tfa hp <0-100>")
            return
        }
        val encounter = TfaServices.raids.encounterOf(player)
        when {
            encounter == null -> player.sendMessage(noEncounterMessage())
            encounter.debugSetHpPercent(pct) -> player.sendMessage("${encounter.room.displayName}: boss set to $pct% HP.")
            else -> player.sendMessage("${encounter.room.displayName} does not support ::tfa hp yet.")
        }
    }

    private fun skipTo(player: Player, args: List<String>) {
        val target = args.joinToString(" ").trim()
        if (target.isEmpty()) {
            player.sendMessage("Usage: ::tfa skipto <room|phase|vault>. Rooms: ${TfaRoomId.ROUTE_ORDER.joinToString { it.configKey }}.")
            return
        }
        if (target.equals("vault", ignoreCase = true)) {
            slot(player, Slot.SKIPTO_VAULT, emptyList())
            return
        }
        val room = TfaRoomId.byName(target)
        if (room != null) {
            slot(player, Slot.SKIPTO_ROOM, listOf(room.name) + args.drop(1))
            return
        }
        val encounter = TfaServices.raids.encounterOf(player)
        if (encounter == null) {
            player.sendMessage("\"$target\" is not a room or \"vault\", and ${noEncounterMessage().replaceFirstChar { it.lowercase() }}")
            return
        }
        val phase = encounter.phases.firstOrNull { it.equals(target, ignoreCase = true) }
        when {
            phase == null -> player.sendMessage("${encounter.room.displayName} phases: ${encounter.phases.joinToString().ifEmpty { "none" }}.")
            encounter.debugSkipToPhase(phase) -> player.sendMessage("${encounter.room.displayName}: skipped to $phase.")
            else -> player.sendMessage("${encounter.room.displayName} does not support ::tfa skipto <phase> yet.")
        }
    }

    private fun noEncounterMessage(): String =
        if (TfaServices.raids === com.gadscape.raids.tfa.TfaRaidDirectory.NONE) "There is no raid system yet (WU-12)."
        else "You are not in an active Fractured Archive encounter."

    private fun dump(player: Player, what: String) {
        val lines = dumpLines(player, what)
        log.info("::tfa dump {} by {}:\n{}", what, player.username, lines.joinToString("\n"))
        val shown = if (lines.size > JOURNAL_LINES) {
            lines.take(JOURNAL_LINES - 1) + "... ${lines.size - JOURNAL_LINES + 1} more lines in the server log"
        } else lines
        Diary.sendJournal(player, "Fractured Archive dump", shown)
    }

    /** The `::tfa dump` text (also used by tests and tools). */
    @JvmStatic
    fun dumpLines(player: Player?, what: String = "all"): List<String> {
        val all = what == "all"
        val lines = ArrayList<String>()
        if (all || what == "config") {
            val snap = TfaConfig.current
            lines += snap.summary()
            val issues = snap.issues()
            if (issues.isNotEmpty()) {
                lines += "Problems:"
                issues.forEach { lines += "  $it" }
            }
            val unread = snap.unreadOverrideFiles()
            if (unread.isNotEmpty()) lines += "Override files no section reads: ${unread.joinToString()}"
            val house = snap.houseEntries()
            lines += "Keys still holding house values (${house.size}):"
            house.forEach { lines += "  $it" }
        }
        if (all || what == "ids") {
            val ids = TfaIds.all()
            val remap = RemapTable.active
            lines += "Ids: ${ids.size} named, ${ids.count { it.remapped }} remapped by ${remap.source}" +
                if (remap.problems.isEmpty()) "" else " (${remap.problems.size} remap problems)"
            remap.problems.forEach { lines += "  remap.tsv: $it" }
            ids.filter { it.remapped }.forEach { lines += "  $it" }
        }
        if (all || what == "raid") {
            lines += "Encounters registered: ${TfaEncounters.registered().joinToString().ifEmpty { "none" }}"
            lines += "Encounters missing: ${TfaEncounters.missing().joinToString().ifEmpty { "none" }}"
            lines += "Debug slots: " + Slot.entries.joinToString { "${it.name.lowercase()}=${if (isBound(it)) "bound" else "unbound"}" }
            TfaServices.describe().forEach { lines += "Service $it" }
            lines += "Active raids: ${TfaServices.raids.activeRaidCount()}"
            if (player != null) {
                lines += TfaServices.raids.describe(player)
                TfaServices.raids.encounterOf(player)?.let { enc ->
                    lines += "Active encounter: ${enc.room.displayName} (scale ${enc.context.scale}, ${enc.context.encounterTicks} ticks)"
                    lines += enc.debugState()
                }
                lines += "You: godmode ${if (TfaDebug.isGodmode(player)) "on" else "off"}, forced scale ${TfaDebug.forcedScale(player) ?: "off"}"
            }
        }
        if (lines.isEmpty()) lines += "Usage: ::tfa dump [config|ids|raid|all]"
        return lines
    }
}
