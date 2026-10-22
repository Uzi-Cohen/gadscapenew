package com.gadscape.raids.tfa.rooms.ezekiel

import java.util.Random

/**
 * The three finale swords (npc 16655), named from the player's view facing east toward Ezekiel (spec §4.2 Finale):
 * left = north of the pen, right = south, front = east (the point of the "V under Ezekiel").
 */
enum class FinaleSword(val key: String) {
    LEFT("left"), RIGHT("right"), FRONT("front");

    companion object {
        @JvmStatic
        fun byKey(key: String): FinaleSword? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * The three finale attacks of dbtable 140 `tfa_ezekiel_finale_attack` (rows 17048 left, 17049 right, 17050 front;
 * values stripped). Each is telegraphed by striking the *other two* shapes (wiki Strategies lightning-pattern tables,
 * `raids4/ezekiel/NOTES.md` §7.7 reading): "left + middle struck → safe right", "middle + right → safe left",
 * "left + right (flat) → only the far row is safe". The middle shape is the front sword.
 */
enum class FinaleAttack(val key: String, val struck: List<FinaleSword>) {
    /** 17048 `left`: struck middle + right; safe (4063,5536), (4062,5536). */
    LEFT("left", listOf(FinaleSword.FRONT, FinaleSword.RIGHT)),
    /** 17049 `right`: struck left + middle; safe (4063,5534), (4062,5534). */
    RIGHT("right", listOf(FinaleSword.LEFT, FinaleSword.FRONT)),
    /** 17050 `front`: struck left + right ("flat"); safe the far row x 4062. */
    FRONT("front", listOf(FinaleSword.LEFT, FinaleSword.RIGHT));

    companion object {
        @JvmStatic
        fun byKey(key: String): FinaleAttack? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/** Impact spot size on a struck pen tile [INF from the 4196/4197/4198 names]. */
enum class ImpactSize { LARGE, MEDIUM, SMALL }

/** Pure finale layout and patterns (spec §4.2 Finale [COMM tables + HOUSE timing]). */
object Finale {

    /** The pen: cell (1,1), x 4062-4064, y 5534-5536. */
    @JvmField
    val DEFAULT_PEN = EzekielArena.cell(1, 1)

    /** Ezekiel's finale SW: cell (2,1), the "central eastern section". */
    @JvmField
    val DEFAULT_BOSS_TILE = EzekielArena.cellSw(2, 1)

    /** SW tiles of the 7 fence cells, in fence-variant order (drop/idle 0N, N = 1..7) [HOUSE order]. */
    @JvmField
    val DEFAULT_FENCE_TILES: List<Tile> = listOf(
        EzekielArena.cellSw(0, 0), EzekielArena.cellSw(1, 0), EzekielArena.cellSw(2, 0),
        EzekielArena.cellSw(2, 2), EzekielArena.cellSw(1, 2), EzekielArena.cellSw(0, 2),
        EzekielArena.cellSw(0, 1),
    )

    /** Safe tiles per attack (wiki tables mapped to coordinates; "top" = x 4064, "left" = north). */
    @JvmField
    val DEFAULT_SAFE: Map<FinaleAttack, List<Tile>> = mapOf(
        FinaleAttack.LEFT to listOf(Tile(4063, 5536), Tile(4062, 5536)),
        FinaleAttack.RIGHT to listOf(Tile(4063, 5534), Tile(4062, 5534)),
        FinaleAttack.FRONT to listOf(Tile(4062, 5534), Tile(4062, 5535), Tile(4062, 5536)),
    )

    /** dbtable 139 combos [HOUSE]: c1 = L,R,F; c2 = R,L,F; c3 = F,L,R; c4 = L,F,R. */
    @JvmField
    val DEFAULT_COMBOS: List<List<FinaleAttack>> = listOf(
        listOf(FinaleAttack.LEFT, FinaleAttack.RIGHT, FinaleAttack.FRONT),
        listOf(FinaleAttack.RIGHT, FinaleAttack.LEFT, FinaleAttack.FRONT),
        listOf(FinaleAttack.FRONT, FinaleAttack.LEFT, FinaleAttack.RIGHT),
        listOf(FinaleAttack.LEFT, FinaleAttack.FRONT, FinaleAttack.RIGHT),
    )

    /** dbtable 138 rotations [HOUSE], 1-based combo numbers: r1 = 1,2,3,4; r2 = 2,4,1,3; r3 = 3,1,4,2. */
    @JvmField
    val DEFAULT_ROTATIONS: List<List<Int>> = listOf(listOf(1, 2, 3, 4), listOf(2, 4, 1, 3), listOf(3, 1, 4, 2))

    /** Every pen tile except the attack's safe tiles. */
    @JvmStatic
    fun struckTiles(pen: Rect, safe: Collection<Tile>): List<Tile> = pen.tiles().filter { it !in safe }

    /**
     * Impact size of a struck tile by its distance from Ezekiel's side of the pen: the row next to him (x = pen.maxX)
     * large, the middle row medium, the far row small [INF].
     */
    @JvmStatic
    fun impactSize(pen: Rect, t: Tile): ImpactSize = when (pen.maxX - t.x) {
        0 -> ImpactSize.LARGE
        1 -> ImpactSize.MEDIUM
        else -> ImpactSize.SMALL
    }

    /**
     * The volley order of one finale: a rotation picked at random (each entry a 1-based combo number), cycled for as
     * long as the finale lasts ("Pick a rotation at random when the finale starts and cycle it").
     */
    class Schedule(private val combos: List<List<FinaleAttack>>, rotations: List<List<Int>>, random: Random) {
        val rotationIndex: Int = random.nextInt(rotations.size)
        private val rotation: List<Int> = rotations[rotationIndex]
        var volleys: Int = 0
            private set

        /** The attacks of the next volley. */
        fun next(): List<FinaleAttack> {
            val combo = combos[(rotation[volleys % rotation.size] - 1).coerceIn(0, combos.size - 1)]
            volleys++
            return combo
        }
    }

    /**
     * Tiles to move a player onto when the fences drop: the pen tiles not yet taken, in a random order; once all are
     * taken, any pen tile.
     */
    @JvmStatic
    fun penTileFor(pen: Rect, taken: Set<Tile>, random: Random, allowed: (Tile) -> Boolean = { true }): Tile {
        val free = pen.tiles().filter { it !in taken && allowed(it) }
        val pool = free.ifEmpty { pen.tiles().filter(allowed).ifEmpty { pen.tiles() } }
        return pool[random.nextInt(pool.size)]
    }
}
