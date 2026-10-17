package controllers

import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import utils.ItemFull
import utils.ServerItemStats
import utils.ServerItems

/**
 * The fields for what an item does in game (the server's item stats): weight, slot, bonuses, requirements, weapon
 * speed, combat options, animations and special attack. Used by the Items tab and the new item maker.
 * [onPlay] is called with an animation id when one of the Play buttons is pressed.
 */
class ServerStatsForm(private val specialNames: () -> Map<String, List<Int>>, private val onPlay: (Int?) -> Unit) {
    private fun num(w: Int) = TextField().apply { prefColumnCount = w }
    val weight = num(6)
    val slot = ComboBox(FXCollections.observableArrayList(ItemFull.SLOTS.entries.map { it.value }))
    val type = ComboBox(FXCollections.observableArrayList(ServerItems.EQUIPMENT_TYPES))
    val bonus = Array(14) { num(4) }
    val reqSkill = Array(3) { ComboBox(FXCollections.observableArrayList(listOf("-") + ServerItems.SKILLS)) }
    val reqLevel = Array(3) { num(4) }
    val isWeapon = CheckBox("Weapon")
    val twoHanded = CheckBox("Two-handed")
    val speed = num(3)
    val category = ComboBox(FXCollections.observableArrayList(ServerItems.WEAPON_CATEGORIES.mapIndexed { i, n -> "$i $n" }))
    val dist = num(3); val longDist = num(3)
    val special = ComboBox<String>()
    val energy = num(3)
    private val anim = Array(ANIM_KEYS.size) { num(5) }

    private val all: List<Node> get() = listOf<Node>(weight, slot, type, isWeapon, twoHanded, speed, category, dist, longDist, special, energy) + bonus + reqSkill + reqLevel + anim

    /** The animation id typed for [key] ("stand", "walk", "accurate"...), or null. */
    fun anim(key: String): Int? = anim[ANIM_KEYS.indexOf(key)].text.trim().toIntOrNull()

    /** The chosen special attack's name, or null for none. */
    val specialName: String? get() = special.value?.takeIf { it != NONE }

    /** Stats -> fields; null disables them. */
    fun fill(st: ServerItemStats?) {
        for (n in all) n.isDisable = st == null
        if (st == null) return
        weight.text = st.weight.toString()
        slot.selectionModel.select(ItemFull.SLOTS.keys.indexOf(st.slot).coerceAtLeast(0))
        type.selectionModel.select(ServerItems.EQUIPMENT_TYPES.indexOf(st.equipmentType).coerceAtLeast(0))
        for (i in 0 until 14) bonus[i].text = st.bonuses[i].toString()
        val reqs = st.requirements.entries.toList()
        for (i in 0 until 3) { val r = reqs.getOrNull(i); reqSkill[i].selectionModel.select(if (r == null) 0 else r.key + 1); reqLevel[i].text = r?.value?.toString() ?: "" }
        val w = st.weapon
        isWeapon.isSelected = w != null
        val shown = w ?: ServerItemStats.Weapon()
        twoHanded.isSelected = shown.twoHanded; speed.text = shown.attackSpeed.toString()
        category.selectionModel.select(shown.category.coerceIn(0, ServerItems.WEAPON_CATEGORIES.size - 1))
        dist.text = shown.normalDistance.toString(); longDist.text = shown.longDistance.toString()
        val anims = shown.animations()
        for ((i, k) in ANIM_KEYS.withIndex()) anim[i].text = anims[k].toString()
        special.items.setAll(listOf(NONE) + specialNames().keys)
        special.selectionModel.select(st.special?.let { special.items.indexOf(it) }?.takeIf { it >= 0 } ?: 0)
        energy.text = st.specialEnergy?.toString() ?: ""
    }

    /** Fields -> a copy of [base] with them; null when [base] is null. */
    fun read(base: ServerItemStats?): ServerItemStats? {
        val st = base?.copy() ?: return null
        st.weight = weight.text.trim().toDoubleOrNull() ?: st.weight
        st.slot = ItemFull.SLOTS.keys.elementAtOrElse(slot.selectionModel.selectedIndex) { -1 }
        st.equipmentType = type.value ?: "DEFAULT"
        for (i in 0 until 14) st.bonuses[i] = bonus[i].text.trim().toIntOrNull() ?: 0
        st.requirements.clear()
        for (i in 0 until 3) { val sk = reqSkill[i].selectionModel.selectedIndex - 1; val lv = reqLevel[i].text.trim().toIntOrNull()
            if (sk >= 0 && lv != null && lv in 1..99) st.requirements[sk] = lv }
        st.weapon = if (!isWeapon.isSelected) null else ServerItemStats.Weapon().apply {
            twoHanded = this@ServerStatsForm.twoHanded.isSelected; attackSpeed = (speed.text.trim().toIntOrNull() ?: 4).coerceIn(1, 20)
            category = this@ServerStatsForm.category.selectionModel.selectedIndex.coerceAtLeast(0)
            normalDistance = dist.text.trim().toIntOrNull() ?: 0; longDistance = longDist.text.trim().toIntOrNull() ?: 0
            fun a(k: String, d: Int) = anim(k) ?: d
            stand = a("stand", stand); walk = a("walk", walk); run = a("run", run); standTurn = a("standTurn", standTurn)
            turn90 = a("turn90", turn90); turn180 = a("turn180", turn180); turn270 = a("turn270", turn270); block = a("block", block)
            accurate = a("accurate", accurate); aggressive = a("aggressive", aggressive); controlled = a("controlled", controlled); defensive = a("defensive", defensive)
        }
        st.special = specialName
        st.specialEnergy = energy.text.trim().toIntOrNull()?.coerceIn(0, 100)
        return st
    }

    /**
     * What's wrong with the fields, in plain words, or null when they're fine. Empty number fields count as 0 / none,
     * so only text that isn't a number is a problem.
     */
    fun problem(): String? {
        if (weight.text.isNotBlank() && weight.text.trim().toDoubleOrNull() == null) return "Weight should be a number of kg, like 0.45."
        for (i in 0 until 14) if (bonus[i].text.isNotBlank() && bonus[i].text.trim().toIntOrNull() == null) return "${ServerItems.BONUS_NAMES[i]} should be a whole number."
        for (i in 0 until 3) {
            if (reqSkill[i].selectionModel.selectedIndex <= 0) continue
            val lv = reqLevel[i].text.trim().toIntOrNull()
            if (lv == null || lv !in 1..99) return "The level needed for ${reqSkill[i].value} should be 1 to 99."
        }
        if (isWeapon.isSelected) {
            val s = speed.text.trim().toIntOrNull()
            if (s == null || s !in 1..20) return "Attack speed should be 1 to 20 ticks (4 = whip, 5 = scythe, 6 = slow)."
            for ((i, f) in anim.withIndex()) if (f.text.isNotBlank() && f.text.trim().toIntOrNull() == null) return "The ${ANIM_LABELS[i].toLowerCase()} animation should be an animation id."
        }
        if (specialName != null && energy.text.isNotBlank() && energy.text.trim().toIntOrNull()?.takeIf { it in 0..100 } == null) return "Special energy should be 0 to 100 (%)."
        return null
    }

    /** The form, laid out; [buttons] go at the bottom. */
    fun node(info: Node?, vararg buttons: Node): VBox {
        fun fixed(text: String) = Label(text).apply { minWidth = Region.USE_PREF_SIZE }
        fun row(label: String, vararg nodes: Node) = HBox(5.0, Label(label).apply { minWidth = 92.0 }, *nodes).apply { alignment = Pos.CENTER_LEFT }
        // attack bonuses in the first column, defence in the second, the rest in the third
        val bonusGrid = GridPane().apply { hgap = 6.0; vgap = 4.0 }
        for (i in 0 until 14) { val col = (if (i < 5) 0 else if (i < 10) 1 else 2) * 2; val r = if (i < 10) i % 5 else i - 10
            bonusGrid.add(fixed(ServerItems.BONUS_NAMES[i]), col, r); bonusGrid.add(bonus[i], col + 1, r) }
        val animGrid = GridPane().apply { hgap = 6.0; vgap = 4.0 }
        for (i in ANIM_KEYS.indices) { val col = (i % 2) * 3; val r = i / 2
            animGrid.add(fixed(ANIM_LABELS[i]), col, r); animGrid.add(anim[i], col + 1, r)
            animGrid.add(Button("Play").apply { setOnAction { onPlay(anim[i].text.trim().toIntOrNull()) } }, col + 2, r) }
        return VBox(7.0, *listOfNotNull(info).toTypedArray(),
            row("Weight kg", weight),
            row("Slot", slot, fixed("Covers"), type),
            Label("Equipment bonuses"), bonusGrid,
            row("Needs", reqSkill[0], reqLevel[0]),
            row("", reqSkill[1], reqLevel[1]),
            row("", reqSkill[2], reqLevel[2]),
            HBox(10.0, isWeapon, twoHanded, fixed("Attack speed (ticks)"), speed).apply { alignment = Pos.CENTER_LEFT },
            row("Combat options", category),
            row("Attack range", dist, fixed("long range"), longDist),
            Label("Animations while holding it (Play shows it on a player with this item)"), animGrid,
            row("Special attack", special),
            row("Energy %", energy),
            *(if (buttons.isEmpty()) emptyArray() else arrayOf<Node>(javafx.scene.layout.FlowPane(6.0, 6.0, *buttons))))
    }

    companion object {
        const val NONE = "(none)"
        val ANIM_KEYS = listOf("stand", "walk", "run", "standTurn", "turn90", "turn180", "turn270", "block", "accurate", "aggressive", "controlled", "defensive")
        val ANIM_LABELS = listOf("Stand", "Walk", "Run", "Turn on spot", "Turn 90", "Turn 180", "Turn 270", "Block", "Accurate", "Aggressive", "Controlled", "Defensive")
    }
}
