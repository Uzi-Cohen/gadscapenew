package com.gadscape.raids.tfa.party

/**
 * The row strings the Grouping Board scripts split with proc 632 on `|` [CACHE scripts 10094 / 10095 / 10053 /
 * 10058]. Pure, unit-tested.
 */
object PartyRowCodec {

    /** Fields of one 977 row, script 10094: leader, m2..m8, count, preferred size, completions, mode text, age. */
    const val LIST_FIELDS = 13

    /** Fields of a 976 member / applicant row, scripts 10053 / 10058. */
    const val MEMBER_FIELDS = 11

    /** Text of enum 6051 [CACHE]. */
    fun modeText(mode: Int): String = if (mode == 1) "Practice" else "Normal"

    /**
     * One 977 row (script 10094). The leader is wrapped in `<col=FFFFFF>` when the viewer is in that party (as ToA
     * does); [preferredSize] 0 shows "-"; [preferredCompletions] 0 is sent as "0", which proc 10095 draws as "-".
     */
    fun listRow(
        names: List<String>,
        viewerInParty: Boolean,
        preferredSize: Int,
        preferredCompletions: Int,
        mode: Int,
        ageTicks: Long,
    ): String {
        require(names.isNotEmpty()) { "a listed party has a leader" }
        val fields = ArrayList<String>(LIST_FIELDS)
        val leader = clean(names[0])
        fields += if (viewerInParty) "<col=FFFFFF>$leader" else leader
        for (i in 1 until 8) fields += clean(names.getOrNull(i) ?: "")
        fields += names.size.coerceAtMost(8).toString()
        fields += if (preferredSize > 0) preferredSize.toString() else "-"
        fields += preferredCompletions.coerceAtLeast(0).toString()
        fields += modeText(mode)
        fields += ageTicks.coerceAtLeast(0).toString()
        return fields.joinToString("|")
    }

    /**
     * The whole 977 board: always [slots] (45) strings, "" for empty indexes, because the client lays the list out
     * only when index 44 arrives [CACHE script 10094]. Parties beyond [slots] are not listed.
     */
    fun <P> boardRows(parties: List<P>, slots: Int = 45, row: (P) -> String): List<String> =
        List(slots) { i -> parties.getOrNull(i)?.let(row) ?: "" }

    /** What 976 shows for one member or applicant. Levels are base levels. */
    data class MemberInfo(
        val name: String,
        val combat: Int,
        val attack: Int,
        val strength: Int,
        val ranged: Int,
        val magic: Int,
        val defence: Int,
        val hitpoints: Int,
        val prayer: Int,
        val completions: Int,
        val practiceCompletions: Int,
    )

    /** One 976 member / applicant row (scripts 10053 / 10058): the ToB `informationString` plus practice completions. */
    fun memberRow(info: MemberInfo): String = listOf(
        clean(info.name), info.combat, info.attack, info.strength, info.ranged, info.magic, info.defence,
        info.hitpoints, info.prayer, info.completions, info.practiceCompletions,
    ).joinToString("|")

    /** 975:5 text: the eight slots joined with `<br>`, "-" for empty slots. */
    fun overlayNames(names: List<String>): String =
        (0 until 8).joinToString("<br>") { names.getOrNull(it)?.let(::clean)?.takeIf { n -> n.isNotEmpty() } ?: "-" }

    /** Splits a row the way proc 632 does (for tests and the debug dump). */
    fun split(row: String): List<String> = row.split('|')

    /** Names never contain `|`, but a stray one would shift every column. */
    private fun clean(name: String): String = name.replace("|", "")
}
