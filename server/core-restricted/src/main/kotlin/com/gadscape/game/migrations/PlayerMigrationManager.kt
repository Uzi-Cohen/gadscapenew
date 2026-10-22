package com.gadscape.game.migrations

import com.google.common.eventbus.Subscribe
import com.gadscape.game.world.entity.player.migrationVersion
import com.zenyte.game.world.entity.player.Player
import com.zenyte.plugins.events.LoginEvent
import com.zenyte.plugins.events.PostInitializationEvent
import com.zenyte.plugins.events.ServerLaunchEvent
import com.zenyte.utils.ClassInitializer
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool
import java.util.function.Consumer

/**
 * A system used to migrate certain variables on various different triggers like login
 * @author John J. Woloszyk / Kryeus
 */
object PlayerMigrationManager {
    private val log = LoggerFactory.getLogger(PlayerMigrationManager::class.java)
    private val migrations = mutableMapOf<Int, GameMigration>()
    private lateinit var sortedMigrations : SortedMap<Int, GameMigration>

    @Subscribe
    @JvmStatic
    fun onServerLaunch(event: ServerLaunchEvent) {
        scanMigrations()
    }

    @Subscribe
    @JvmStatic
    fun onPlayerLogin(event: LoginEvent) {
        performPlayerLoginMigrations(event.player)
        performRepeatableMigrations(event.player, GameMigration.Phase.LOGIN)
    }

    /** The save has been loaded into the player and nothing has been sent yet: [GameMigration.Phase.LOAD] migrations. */
    @Subscribe
    @JvmStatic
    fun onPlayerLoaded(event: PostInitializationEvent) {
        performRepeatableMigrations(event.player, GameMigration.Phase.LOAD)
    }

    private fun scanMigrations() {
        val scanner = ClassGraph()
        scanner.ignoreMethodVisibility()
        scanner.enableAnnotationInfo()
        scanner.enableMethodInfo()
        scanner.enableClassInfo()
        scanner.enableExternalClasses()
        scanner.acceptPackages("com.gadscape.game.migrations.impl")

        log.debug("Scanning for migrations in classpath.")
        scanner.scan().use { result ->
            val callables =
                ObjectArrayList<Callable<Void?>>(1000)
            val lock = Any()
            result.allClasses
                .forEach(Consumer { clazz: ClassInfo ->
                    callables.add(
                        Callable<Void?> {
                            if (clazz.hasAnnotation(ActiveMigration::class.java)) {
                                log.trace("Loaded migration: " + clazz.simpleName)
                                ClassInitializer.initialize(clazz.loadClass())
                                val obj = clazz.loadClass()
                                synchronized(lock) {
                                    val migration = obj.getDeclaredConstructor().newInstance() as GameMigration
                                    migrations.put(migration.id(), migration)
                                }
                            }
                            null
                        })
                })
            ForkJoinPool.commonPool().invokeAll(callables)
            log.info(
                "Loaded a total of {} migrations from the classpath",
                migrations.size
            )
        }
        sortedMigrations = migrations.toSortedMap()
    }

    @JvmStatic
    private fun performPlayerLoginMigrations(player: Player) {
        if (!::sortedMigrations.isInitialized) return
        for((id, migration) in sortedMigrations) {
            if (migration.repeatable() || migration.phase() != GameMigration.Phase.LOGIN) continue
            if(player.migrationVersion < id) {
                log.debug("Performing player migration #$id for ${player.username}")
                player.sendDeveloperMessage("Running migration: " + migration.javaClass.simpleName)
                migration.run(player)
                player.migrationVersion = id
            }
        }
    }

    /**
     * Repeatable migrations of [phase], in id order. Each keeps its own per-account record, so running it again (a
     * second login, or the load event that the login sequence posts twice) does nothing.
     */
    @JvmStatic
    private fun performRepeatableMigrations(player: Player, phase: GameMigration.Phase) {
        if (!::sortedMigrations.isInitialized) return
        for ((id, migration) in sortedMigrations) {
            if (!migration.repeatable() || migration.phase() != phase) continue
            try {
                migration.run(player)
            } catch (e: Exception) {
                log.error("Migration #$id (${migration.javaClass.simpleName}) failed for ${player.username}", e)
            }
        }
    }
}