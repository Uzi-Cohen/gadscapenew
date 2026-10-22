package com.gadscape.raids.tfa.config

import com.gadscape.raids.tfa.raid.Compass
import com.gadscape.raids.tfa.raid.StaticTile
import com.gadscape.raids.tfa.raid.TfaRoomId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TfaConfigTest {

    private val both = listOf(RaidConfig, ScalingConfig)

    // ------------------------------------------------------------------------------------------- bundled files

    @Test
    fun bundledTfaJsonMatchesTheCodeDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(RaidConfig)

    @Test
    fun bundledScalingJsonMatchesTheCodeDefaults() = TfaConfigTestKit.assertBundledMatchesDefaults(ScalingConfig)

    @Test
    fun bundledScalingEqualsTheSpecDefaultConstant() {
        val snap = TfaConfigTestKit.load(both)
        assertEquals(ScalingConfig.DEFAULT, snap.scaling)
        assertEquals(0, snap.issues().size, snap.issues().joinToString("\n"))
    }

    @Test
    fun noFilesAtAllStillGivesDefaultsAndOnlyMissingFileWarnings() {
        val snap = TfaConfigTestKit.load(both, bundled = emptyMap())
        assertEquals(ScalingConfig.DEFAULT, snap.scaling)
        assertEquals(RaidConfig.GateMode.ATTUNEMENT, snap.raid.entryGate.mode)
        assertEquals(StaticTile(4126, 5508, 0), snap.raid.tiles.lobbyArrival)
        assertTrue(snap.issues().all { it.severity == Severity.WARNING && it.message.startsWith("no bundled defaults") })
        assertTrue(snap.entries().all { it.source == ValueSource.DEFAULT })
    }

    @Test
    fun defaultValuesMatchTheSpec() {
        val raid = TfaConfigTestKit.load(both).raid
        assertEquals(72, raid.entryGate.requirements.thieving)
        assertEquals(RaidConfig.TeleportOutRule.LEAVE_RAID, raid.teleportOut)
        assertEquals(30, raid.watchdogMinutes)
        assertEquals(listOf(1, 3), raid.nexus.variantPlanes)
        assertEquals(83, raid.supplies.maxCredits)
        assertEquals(886, raid.completionJingle)
        assertEquals(StaticTile(4127, 5634, 1), raid.tiles.ocellusCorridor)
        val bork = assertNotNull(raid.rooms[TfaRoomId.BORK])
        assertEquals(StaticTile(4186, 5599, 0), bork.entry)
        assertEquals(Compass.E, bork.facing)
        assertEquals(6, raid.rooms.entries.size, "one transporter landing per paired room")
    }

    // --------------------------------------------------------------------------------------- house reporting

    @Test
    fun houseValuesAreListedAndDocValuesAreNot() {
        val snap = TfaConfigTestKit.load(both)
        val house = snap.houseEntries().map { "${it.file} ${it.key}" }.toSet()
        assertTrue("tfa.json entryGate.mode" in house)
        assertTrue("tfa.json tiles.nexusArrival" in house)
        assertTrue("scaling.json perPlayer" in house)
        assertTrue("scaling.json overrides" in house, "INF keys are unconfirmed too")
        assertFalse("scaling.json minScale" in house, "DOC keys are not house values")
        assertFalse("scaling.json combatLevelOverride" in house)
        assertFalse("tfa.json folio.chargesPerShard" in house)
    }

    @Test
    fun verifiedKeysAndParentsLeaveTheHouseList() {
        val override = """{ "${'$'}verified": { "perPlayer": "wiki: second scaled HP sighting" } }"""
        val tfa = """{ "${'$'}verified": ["tiles", "entryGate.mode"] }"""
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("scaling.json" to override, "tfa.json" to tfa))
        val house = snap.houseEntries().map { it.key }.toSet()
        assertFalse("perPlayer" in house)
        assertFalse("tiles.ivy" in house, "a verified parent verifies its children")
        assertFalse("entryGate.mode" in house)
        assertTrue("entryGate.requirements.thieving" in house)
        assertEquals("wiki: second scaled HP sighting", snap.entries().first { it.key == "perPlayer" }.verifiedBy)
        assertTrue(snap.issues().isEmpty(), snap.issues().joinToString("\n"))
    }

    @Test
    fun tunedValuesStayHouseValuesUntilVerified() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("tfa.json" to """{ "watchdogMinutes": 45 }"""))
        val entry = snap.houseEntries().first { it.key == "watchdogMinutes" }
        assertEquals(45, entry.value)
        assertEquals(30, entry.default)
        assertEquals(ValueSource.OVERRIDE, entry.source)
        assertTrue(entry.tuned)
        assertTrue("tuned from 30" in entry.toString())
        assertTrue(snap.issues().isEmpty(), snap.issues().joinToString("\n"))
    }

    // ---------------------------------------------------------------------------------- validation, fallback

    @Test
    fun malformedValueIsReportedWithItsKeyAndFallsBack() {
        // lenient parsing turns the typo 3O into the string "3O"
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("tfa.json" to """{ "watchdogMinutes": 3O, "releaseDelayTicks": 5 }"""))
        assertEquals(30, snap.raid.watchdogMinutes, "falls back to the bundled default")
        assertEquals(5, snap.raid.releaseDelayTicks, "the other override keys still apply")
        val issue = snap.issues().single() // no spurious "unknown key" for keys the override replaces
        assertEquals("tfa.json", issue.file)
        assertEquals("watchdogMinutes", issue.key)
        assertTrue("expected an integer" in issue.message, issue.message)
        assertTrue("using the bundled default 30" in issue.message, issue.message)
    }

    @Test
    fun outOfRangeAndWrongTypesFallBack() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf(
            "scaling.json" to """{ "perPlayer": -1, "rounding": "sideways", "minScale": 2.5 }""",
            "tfa.json" to """{ "entryGate": { "mode": 7, "requirements": { "magic": 120 } }, "tiles": { "ivy": [1, 2] } }""",
        ))
        assertEquals(ScalingConfig.DEFAULT, snap.scaling)
        assertEquals(RaidConfig.GateMode.ATTUNEMENT, snap.raid.entryGate.mode)
        assertEquals(67, snap.raid.entryGate.requirements.magic)
        assertEquals(StaticTile(4127, 5618, 0), snap.raid.tiles.ivy)
        val keys = snap.issues().filter { it.severity == Severity.ERROR }.map { it.key }.toSet()
        assertEquals(setOf("perPlayer", "rounding", "minScale", "entryGate.mode", "entryGate.requirements.magic", "tiles.ivy"), keys)
    }

    @Test
    fun badBundledAndBadOverrideFallBackToTheCodeDefault() {
        val snap = TfaConfigTestKit.load(listOf(ScalingConfig),
            overrides = mapOf("scaling.json" to """{ "perPlayer": "lots" }"""),
            bundled = mapOf("scaling.json" to """{ "perPlayer": true }"""))
        assertEquals(0.42727, snap.scaling.perPlayer)
        val issue = snap.issues().single { it.key == "perPlayer" }
        assertTrue("using the code default 0.42727" in issue.message, issue.message)
        assertEquals(ValueSource.DEFAULT, snap.entries().first { it.key == "perPlayer" }.source)
    }

    @Test
    fun malformedFileIsReportedAndIgnored() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("tfa.json" to """{ "watchdogMinutes": 10, """))
        assertEquals(30, snap.raid.watchdogMinutes)
        val issue = snap.issues().single { it.severity == Severity.ERROR }
        assertEquals("tfa.json", issue.file)
        assertTrue("malformed JSON" in issue.message, issue.message)
    }

    @Test
    fun topLevelMustBeAnObject() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("scaling.json" to "[1, 2, 3]"))
        assertEquals(ScalingConfig.DEFAULT, snap.scaling)
        assertTrue(snap.issues().any { "top level must be a JSON object" in it.message })
    }

    @Test
    fun unknownKeysAreWarned() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf(
            "tfa.json" to """{ "watchdogMinutez": 10, "hud": { "timerMod": "room" }, "_note": "comments are fine" }""",
        ))
        val warnings = snap.issues().filter { it.severity == Severity.WARNING }.map { it.key }.toSet()
        assertEquals(setOf("watchdogMinutez", "hud.timerMod"), warnings)
        assertEquals(RaidConfig.TimerMode.TOTAL, snap.raid.hud.timerMode)
    }

    @Test
    fun nestedOverridesAreKeyByKey() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf(
            "tfa.json" to """{ "entryGate": { "mode": "WAIVED", "requirements": { "thieving": 80 } },
                              "rooms": { "moia": { "facing": "n" } } }""",
        ))
        val gate = snap.raid.entryGate
        assertEquals(RaidConfig.GateMode.WAIVED, gate.mode, "enum names are case-insensitive")
        assertEquals(80, gate.requirements.thieving)
        assertEquals(67, gate.requirements.magic, "untouched keys keep the bundled value")
        val moia = snap.raid.rooms[TfaRoomId.MOIA]!!
        assertEquals(Compass.N, moia.facing)
        assertEquals(StaticTile(4066, 5663, 0), moia.entry)
    }

    @Test
    fun scalingTablesMustCoverEveryScale() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf(
            "scaling.json" to """{ "overrides": { "xarth_corpse_hp": [1, 2, 3] }, "combatLevelOverride": { "ocellus_p1": 1255 } }""",
        ))
        assertEquals(ScalingConfig.DEFAULT.overrides, snap.scaling.overrides)
        assertEquals(mapOf("ocellus_p1" to 1255), snap.scaling.combatLevelOverride, "a whole map is one value")
        assertTrue(snap.issues().single().message.contains("must have 7 elements"))
    }

    @Test
    fun minAboveMaxScaleIsRepaired() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("scaling.json" to """{ "minScale": 6, "maxScale": 3 }"""))
        assertEquals(2, snap.scaling.minScale)
        assertEquals(8, snap.scaling.maxScale)
        assertTrue(snap.issues().any { it.key == "maxScale" })
    }

    @Test
    fun overrideFilesNoSpecReadsAreListed() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("guardian.json" to "{}"))
        assertEquals(listOf("guardian.json"), snap.unreadOverrideFiles())
    }

    // ---------------------------------------------------------------------------------- object lists, roots

    private data class Tempo(val abovePct: Int, val speed: Int)

    private object TempoSpec : TfaConfigSpec<List<Tempo>>("tempo_test.json") {
        val DEFAULT = listOf(Tempo(66, 7), Tempo(33, 6), Tempo(0, 4))
        override fun read(r: ConfigReader): List<Tempo> =
            r.objList("tempo", DEFAULT, Evidence.HOUSE, size = 1..10) { Tempo(int("abovePct", 0, min = 0, max = 100), int("speed", 4, min = 1)) }
    }

    @Test
    fun objectListsAreAllOrNothing() {
        val good = TfaConfigTestKit.load(listOf(TempoSpec), bundled = emptyMap(),
            overrides = mapOf("tempo_test.json" to """{ "tempo": [ {"abovePct": 50, "speed": 5}, {"abovePct": 0, "speed": 3} ] }"""))
        assertEquals(listOf(Tempo(50, 5), Tempo(0, 3)), good[TempoSpec])

        val bad = TfaConfigTestKit.load(listOf(TempoSpec), bundled = emptyMap(),
            overrides = mapOf("tempo_test.json" to """{ "tempo": [ {"abovePct": 50, "speed": 5}, {"abovePct": 0} ] }"""))
        assertEquals(TempoSpec.DEFAULT, bad[TempoSpec])
        val issue = bad.issues().single { it.severity == Severity.ERROR }
        assertEquals("tempo", issue.key)
        assertTrue("tempo[1].speed is missing" in issue.message, issue.message)
    }

    private data class Extra(val enabled: Boolean)

    private object ExtraSection : TfaConfigSpec<Extra>("tfa.json", root = "spectateTest") {
        override fun read(r: ConfigReader) = Extra(r.bool("enabled", true))
    }

    @Test
    fun rootSectionsShareAFileWithoutUnknownKeyWarnings() {
        val snap = TfaConfigTestKit.load(both + ExtraSection,
            overrides = mapOf("tfa.json" to """{ "spectateTest": { "enabled": false }, "watchdogMinutes": 20 }"""))
        assertEquals(Extra(false), snap[ExtraSection])
        assertEquals(20, snap.raid.watchdogMinutes)
        assertTrue(snap.issues().isEmpty(), snap.issues().joinToString("\n"))
        assertTrue(snap.entries().any { it.key == "spectateTest.enabled" && it.source == ValueSource.OVERRIDE })
    }

    // ------------------------------------------------------------------------------------------- snapshots

    @Test
    fun snapshotsAreIndependent() {
        val a = TfaConfigTestKit.load(both, overrides = mapOf("tfa.json" to """{ "watchdogMinutes": 11 }"""))
        val b = TfaConfigTestKit.load(both, overrides = mapOf("tfa.json" to """{ "watchdogMinutes": 22 }"""))
        assertNotSame(a, b)
        assertTrue(b.version > a.version)
        assertEquals(11, a.raid.watchdogMinutes, "a running raid keeps the snapshot it started with")
        assertEquals(22, b.raid.watchdogMinutes)
    }

    @Test
    fun specsRegisteredAfterLoadReadTheSnapshotText() {
        val snap = TfaConfigTestKit.load(both, overrides = mapOf("tempo_test.json" to """{ "tempo": [ {"abovePct": 0, "speed": 2} ] }"""))
        // the temp dir is gone now; the snapshot still has the override text it read at load time
        assertEquals(listOf(Tempo(0, 2)), snap[TempoSpec])
    }
}
