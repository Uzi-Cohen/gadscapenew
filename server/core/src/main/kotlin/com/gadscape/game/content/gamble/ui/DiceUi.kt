package com.gadscape.game.content.gamble.ui

import com.gadscape.game.content.gamble.ui.RoatUi.Companion.ROOT

/**
 * Roat Pkz's dice window (their 51350, DicesTableInterface) and side panel (51450, DicesTableSideInterface),
 * ported call for call. Component ids are Roat's; [AUTO] + id is the copy of a manual-mode component that Roat
 * shows in both tabs, [STATE] + id is the pressed/selected look of a button.
 */
object DiceUi {

    const val MAIN_ID = 5120
    const val SIDE_ID = 5121

    const val AUTO = 100_000
    const val STATE = 200_000

    // main window
    const val CLOSE = 51352
    const val TITLE = 51353
    const val SLIDER = 51354
    const val RULES_BUTTON = 51355
    const val MULTIPLIER = 51356
    const val ROLL_OVER = 51357
    const val WIN_CHANCE = 51358
    const val LIGHT = 51362
    const val SESSION = 51363
    const val ARROW = 51364
    const val ARROW_TEXT = 51366
    const val BACKGROUND = 51351
    const val PROVABLY_FAIR = 51369
    const val HISTORY = 51370
    const val HISTORY_SLOTS = 8
    const val DIM = 51415
    const val RULES = 51416
    const val RULES_CLOSE = 51418
    const val FAIR = 51550
    const val FAIR_CLOSE = 51552
    const val FAIR_CLIENT_SEED = 51561
    const val FAIR_SERVER_SEED = 51562
    const val FAIR_SERVER_HASH = 51563
    const val FAIR_NONCE = 51564
    const val FAIR_VERIFY = 51565
    const val FAIR_VIDEO = 51566
    const val FAIR_SOURCE = 51567
    const val FAIR_SITE = 51568

    /** Slider parts (not Roat components: Roat draws the slider in code). */
    const val SLIDER_GREEN_LAYER = 51354_0
    const val SLIDER_GREEN = 51354_1
    const val SLIDER_KNOB = 51354_2
    const val SLIDER_SPOT = 51354_10 // + percentage
    const val SLIDER_X = 31
    const val SLIDER_Y = 183
    const val SLIDER_WIDTH = 450
    const val SLIDER_OFFSET = 25
    const val MIN_TARGET = 2
    const val MAX_TARGET = 98

    // side panel
    const val MANUAL = 51449
    const val AUTO_TAB = 51448
    const val MODE = 51460
    const val BALANCE = 51451
    const val CURRENCY = 51452
    const val HALF = 51456
    const val DOUBLE = 51457
    const val BET = 51458
    const val PROFIT_LABEL = 51462
    const val PROFIT = 51464
    const val ROLL = 51465
    const val BETS_MINUS = 51472
    const val BETS_PLUS = 51473
    const val BETS_INF = 51474
    const val BETS = 51475
    const val WIN_RESET = 51478
    const val WIN_INCREASE = 51479
    const val WIN_PERCENT = 51480
    const val LOSS_RESET = 51483
    const val LOSS_INCREASE = 51484
    const val LOSS_PERCENT = 51485
    const val STOP_PROFIT = 51488
    const val STOP_LOSS = 51491
    const val AUTOBET = 51492

    val main: RoatUi by lazy { buildMain() }
    val side: RoatUi by lazy { buildSide() }

    /** x of the slider knob's centre for a target (Roat's DiceSliderBar.setSliderPercentage). */
    fun sliderLocation(target: Int): Int {
        val step = (SLIDER_WIDTH - 2.0 * SLIDER_OFFSET) / (MAX_TARGET - MIN_TARGET)
        return SLIDER_OFFSET + ((target - MIN_TARGET) * step).toInt()
    }

    /** Left edge of the knob (Roat's getSliderDrawLocationX); the red part of the bar ends here. */
    fun knobX(target: Int): Int = (sliderLocation(target) - KNOB_WIDTH / 2).coerceAtMost(SLIDER_WIDTH - KNOB_WIDTH)

    private const val KNOB_WIDTH = 35

    private fun buildMain() = RoatUi(MAIN_ID, 512, 334, side = false).apply {
        val sx = 5
        val sy = 5
        image(BACKGROUND, ROOT, sx, sy, "DiceInterface/background 0")
        button(CLOSE, ROOT, sx + 474, sy + 7, "PriceCheckerInterface/close", 1, 2, "Close")
        button(PROVABLY_FAIR, ROOT, sx + 454, sy + 7, "BlackjackInterface/green_lock", 0, 1, "Provably Fair")
        container(ARROW, ROOT, sx + 200, sy + 156, 53, 28, hide = true)
        image(51365, ARROW, 0, 0, "DiceInterface/arrow 0")
        text(ARROW_TEXT, ARROW, 26, 3, "@gre@72.91", 65280, true, true, 2)
        text(TITLE, ROOT, sx + 135, sy + 10, "GadScape Dice", 16750623, true, true, 2, roatWidth = 310)
        clickText(RULES_BUTTON, ROOT, 40, sy + 10, "<img=264:1> Rules", "Select", 2, 12698049, false, true, 60, 16)
        button(LIGHT, ROOT, 16, sy + 7, "DiceInterface/light", 0, 1, "Transparent Background")

        // DiceSliderBar: green bar, then the part right of the knob shown over a red bar, then the knob
        image(SLIDER, ROOT, sx + 26, sy + 178, "DiceInterface/red_bar")
        val knob = knobX(50)
        container(SLIDER_GREEN_LAYER, ROOT, sx + 26 + knob, sy + 178, SLIDER_WIDTH, 26)
        image(SLIDER_GREEN, SLIDER_GREEN_LAYER, -knob, 0, "DiceInterface/green_bar")
        image(SLIDER_KNOB, ROOT, sx + 26 + knob, sy + 178, "DiceInterface/slider")
        for (target in MIN_TARGET..MAX_TARGET) {
            val left = if (target == MIN_TARGET) 0 else (sliderLocation(target - 1) + sliderLocation(target)) / 2
            val right = if (target == MAX_TARGET) SLIDER_WIDTH else (sliderLocation(target) + sliderLocation(target + 1)) / 2
            hotspot(SLIDER_SPOT + target, ROOT, sx + 26 + left, sy + 178, right - left, 26, "Slide")
        }

        text(MULTIPLIER, ROOT, sx + 38, sy + 270, "0.00000", 16300573, false, true, 2)
        text(ROLL_OVER, ROOT, sx + 38 + 154, sy + 270, "0.00000", 16300573, false, true, 2)
        text(WIN_CHANCE, ROOT, sx + 38 + 154 + 153, sy + 270, "0.00000", 16300573, false, true, 2)
        text(51359, ROOT, sx + 31, sy + 252, "Multiplier:", 16777215, false, true, 0)
        text(51360, ROOT, sx + 31 + 154, sy + 252, "Roll Over:", 16777215, false, true, 0)
        text(51361, ROOT, sx + 31 + 154 + 153, sy + 252, "Win Chance:", 16777215, false, true, 0)
        text(SESSION, ROOT, sx + 250, sy + 305, "", 16750623, true, true, 0)

        container(HISTORY, ROOT, sx + 42, sy + 39, 418, 17)
        for (a in 0 until HISTORY_SLOTS) {
            image(HISTORY + 1 + a * 2, HISTORY, a * 61, 0, "DiceInterface/win_tab", hide = true)
            text(HISTORY + 2 + a * 2, HISTORY, 26 + a * 61, 3, "", 65280, true, true, 0)
        }

        shade(DIM, ROOT, sx, sy, 501, 330, 0x000000, 179, hide = true)
        container(RULES, ROOT, 0, 0, 512, 334, hide = true)
        image(51417, RULES, sx + 72, sy + 26 + 40, "DiceInterface/background 2")
        button(RULES_CLOSE, RULES, sx + 403, sy + 33 + 40, "PriceCheckerInterface/close", 1, 2, "Close")
        text(51419, RULES, sx + 246, sy + 37 + 40, "<img=323:0> Dice Rules", 16750623, true, true, 2)
        text(51420, RULES, sx + 248, sy + 68 - 4 + 40, "Choose how much you want to bet on the roll.", 16754236, true, true, 1)
        text(51421, RULES, sx + 248, sy + 83 - 4 + 40, "Adjust the win chance by selecting a target number.", 16754236, true, true, 1)
        text(51422, RULES, sx + 248, sy + 98 - 4 + 40, "A random number between 0 and 100 will be generated.", 16754236, true, true, 1)
        text(51423, RULES, sx + 248, sy + 113 - 4 + 40, " If the roll lands in your range, you win, otherwise, you lose.", 16754236, true, true, 1)
        image(51424, RULES, sx + 174, sy + 228 - 95 + 40, "BlackjackInterface/side/line_top 1")
        text(51425, RULES, sx + 246, sy + 234 - 95 + 40, "<col=ffbe71>The return to player (RTP) on this game is @gre@99%.", 16760433, true, true, 0)
        image(51426, RULES, sx + 174, sy + 250 - 95 + 40, "BlackjackInterface/side/line_top 1")
        text(51427, RULES, sx + 246, sy + 256 - 95 + 40, "<img=209> @yel@Gamble Responsibly and Enjoy! <img=209>", 16750623, true, true, 0)

        ProvablyFairPanel.add(this, FAIR, sx, sy, "dice roll", "dice roll")
    }

    private fun buildSide() = RoatUi(SIDE_ID, 190, 261, side = true).apply {
        container(MANUAL, ROOT, 0, 0, 190, 261)
        container(AUTO_TAB, ROOT, 0, 6, 174, 250, hide = true, scrollHeight = 426)
        image(51461, ROOT, 3, 0, "DiceInterface/side/background 2")
        clickText(MODE, ROOT, 48, 3, "@cu1@mode: @cu2@manual", "Switch between manual & auto", 0, 15063096, true, true, 90, 14)

        betRows(this, MANUAL, 0, 6)
        image(51459, MANUAL, 17, 109, "DiceInterface/side/line_bottom 0")
        text(PROFIT_LABEL, MANUAL, 9, 122, "Profit on Win (@gre@40.43x@whi@):", 16777215, false, true, 0)
        image(51463, MANUAL, 8, 136, "DiceInterface/side/background 3")
        text(PROFIT, MANUAL, 95, 139, "", 65280, true, true, 2)
        image(51466, MANUAL, 17, 169, "DiceInterface/side/line_bottom 0")
        button(ROLL, MANUAL, 7, 181, "DiceInterface/side/roll_Button", 0, 1, "Roll Dice!")
        image(STATE + ROLL, MANUAL, 7, 181, "DiceInterface/side/roll_Button 2", hide = true)

        betRows(this, AUTO_TAB, AUTO, 0)
        text(51470, AUTO_TAB, 9, 101, "Number of Bets:", 16777215, false, true, 0)
        image(51471, AUTO_TAB, 8, 114, "DiceInterface/side/number_of_bets 0")
        button(BETS_MINUS, AUTO_TAB, 99, 114, "DiceInterface/side/number_of_bets", 1, 2, "-10 Bets")
        button(BETS_PLUS, AUTO_TAB, 128, 114, "DiceInterface/side/number_of_bets", 3, 4, "+10 Bets")
        button(BETS_INF, AUTO_TAB, 157, 114, "DiceInterface/side/number_of_bets", 5, 6, "INF Bets")
        clickText(BETS, AUTO_TAB, 8, 120, "  10", "Change Number of Bets", 2, 13156279, false, true, 86, 20)
        image(51493, AUTO_TAB, 17, 153, "DiceInterface/side/line_bottom 0")
        onWinOrLoss(this, 51476, "On Win:", 163, WIN_RESET, WIN_INCREASE, WIN_PERCENT, "Change on Win Percentage")
        onWinOrLoss(this, 51481, "On Loss:", 211, LOSS_RESET, LOSS_INCREASE, LOSS_PERCENT, "Change on Loss Percentage")
        image(51494, AUTO_TAB, 17, 264, "DiceInterface/side/line_bottom 0")
        text(51486, AUTO_TAB, 9, 274, "Stop on Profit:", 16777215, false, true, 0)
        image(51487, AUTO_TAB, 8, 287, "DiceInterface/side/background 4")
        clickText(STOP_PROFIT, AUTO_TAB, 8, 293, "  0", "Change Stop on Profit", 2, 65280, false, true, 106, 20)
        text(51489, AUTO_TAB, 9, 321, "Stop on Loss:", 16777215, false, true, 0)
        image(51490, AUTO_TAB, 8, 334, "DiceInterface/side/background 4")
        clickText(STOP_LOSS, AUTO_TAB, 8, 340, "  0", "Change Stop on Loss", 2, 16711680, false, true, 106, 20)
        image(51495, AUTO_TAB, 17, 371, "DiceInterface/side/line_bottom 0")
        button(AUTOBET, AUTO_TAB, 8, 380, "DiceInterface/side/start_autobet", 0, 1, "Start or Stop Autobet")
        image(STATE + AUTOBET, AUTO_TAB, 8, 380, "DiceInterface/side/stop_autobet 0", hide = true)
        scrollbar(AUTO_TAB * 10 + 9, ROOT, 174, 6, 250, AUTO_TAB)
    }

    /** Balance, currency and bet size rows; Roat shows the same components in both tabs (auto at 6px higher). */
    private fun betRows(ui: RoatUi, parent: Int, offset: Int, dy: Int) = with(ui) {
        text(offset + BALANCE, parent, 95, 17 - dy, "", 16750623, true, true, 2)
        clickText(offset + CURRENCY, parent, 50, 33 - dy, "(change currency)", "Select", 0, 15063096, false, true, 90, 14)
        image(offset + 51453, parent, 41, 51 - dy, "DiceInterface/side/line_top 0")
        text(offset + 51454, parent, 9, 60 - dy, "Bet Amount:", 16777215, false, true, 0)
        image(offset + 51455, parent, 8, 73 - dy, "DiceInterface/side/background_bet 4")
        button(offset + HALF, parent, 128, 73 - dy, "DiceInterface/side/background_bet", 0, 1, "1/2 Bet Size")
        button(offset + DOUBLE, parent, 157, 73 - dy, "DiceInterface/side/background_bet", 2, 3, "2x Bet Size")
        clickText(offset + BET, parent, 8, 79 - dy, "", "Change Bet Size", 2, 16776960, false, true, 106, 20)
    }

    private fun onWinOrLoss(ui: RoatUi, label: Int, name: String, y: Int, reset: Int, increase: Int, percent: Int, tooltip: String) = with(ui) {
        text(label, AUTO_TAB, 9, y, name, 16777215, false, true, 0)
        image(label + 1, AUTO_TAB, 8, y + 13, "DiceInterface/side/background 1")
        button(reset, AUTO_TAB, 10, y + 15, "DiceInterface/side/reset", 0, 1, "Reset")
        image(STATE + reset, AUTO_TAB, 10, y + 15, "DiceInterface/side/reset 2")
        button(increase, AUTO_TAB, 56, y + 15, "DiceInterface/side/increase_by", 0, 1, "Increase By")
        image(STATE + increase, AUTO_TAB, 56, y + 15, "DiceInterface/side/increase_by 2", hide = true)
        clickText(percent, AUTO_TAB, 144, y + 22, "0%", tooltip, 0, 16234781, true, true, 30, 20)
    }
}
