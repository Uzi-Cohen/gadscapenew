package com.gadscape.game.content.gamble.ui

import com.gadscape.game.content.gamble.ui.RoatUi.Companion.ROOT

/**
 * Roat Pkz's mines window (their 51100, MinesTableInterface) and side panel (51300, MinesTableSideInterface),
 * ported call for call. Component ids are Roat's; [AUTO] + id is the auto-tab copy of a component Roat shows in
 * both tabs, [STATE] + id the pressed/selected look of a button.
 */
object MinesUi {

    const val MAIN_ID = 5122
    const val SIDE_ID = 5123

    const val AUTO = 100_000
    const val STATE = 200_000

    const val TILES = 25

    // main window
    const val BACKGROUND = 51101
    const val CLOSE = 51102
    const val TITLE = 51103
    const val RULES_BUTTON = 51104
    const val LIGHT = 51106
    const val SESSION = 51107
    const val AUTO_LABEL = 51115
    const val DIM = 51108
    const val WIN_PANEL = 51109
    const val WIN_TITLE = 51110
    const val WIN_MULTIPLIER = 51111
    const val WIN_AMOUNT = 51112
    const val WIN_CLOSE = 51113
    const val PROVABLY_FAIR = 51114
    const val STRIP = 51120
    const val STRIP_SLOTS = 7

    /** Tile i: button [TILE] + 2i, its hover label + 1. */
    const val TILE = 51190
    /** Tile i look once revealed: [TILE_STATE] + i (sprites "button 3".."button 6", "button 2" while loading). */
    const val TILE_STATE = 51190_00
    /** Tile i picked for autoplay: [AUTO_PICK] + i ("button_auto 1"), and [AUTO_PLAYING] + i while autoplay runs. */
    const val AUTO_PICK = 52100
    const val AUTO_PLAYING = 52150

    const val RULES_DIM = 51249
    const val RULES = 51250
    const val RULES_CLOSE = 51252
    const val RATES = 51260
    const val FAIR = 51500

    // side panel
    const val AUTO_TAB = 51298
    const val MANUAL = 51299
    const val MODE = 51296
    const val BALANCE = 51301
    const val CURRENCY = 51302
    const val HALF = 51306
    const val DOUBLE = 51307
    const val BET_AMOUNT = 51308
    const val MINES_LABEL = 51310
    const val MINES_PICKER = 51311
    const val MINES_PICKER_TEXT = 51311_1
    const val PROFIT_LABEL = 51312
    const val PROFIT = 51314
    const val BET = 51315
    const val RANDOM_TILE = 51316
    const val COUNTS = 51317
    const val GEMS_LEFT = 51318
    const val MINES_LEFT = 51319
    const val CASHOUT = 51320
    const val AUTO_MINES_PICKER = 51326
    const val AUTO_MINES_PICKER_TEXT = 51326_1
    const val BETS_MINUS = 51622
    const val BETS_PLUS = 51623
    const val BETS_INF = 51624
    const val BETS = 51625
    const val SUCCESS_STOP = 51629
    const val SUCCESS_CASHOUT = 51630
    const val WIN_RESET = 51633
    const val WIN_INCREASE = 51634
    const val WIN_PERCENT = 51635
    const val LOSS_RESET = 51638
    const val LOSS_INCREASE = 51639
    const val LOSS_PERCENT = 51640
    const val STOP_PROFIT = 51644
    const val STOP_LOSS = 51647
    const val AUTOBET = 51649

    /** Tile look: 1 gem, 2 mine that went off, 3 gem not picked, 4 mine not hit, 5 loading. */
    fun tileSprite(state: Int) = RoatSprites.id(
        when (state) {
            1 -> "MinesInterface/button 3"
            2 -> "MinesInterface/button 4"
            3 -> "MinesInterface/button 5"
            4 -> "MinesInterface/button 6"
            else -> "MinesInterface/button 2"
        }
    )

    val main: RoatUi by lazy { buildMain() }
    val side: RoatUi by lazy { buildSide() }

    private fun buildMain() = RoatUi(MAIN_ID, 512, 334, side = false).apply {
        val sx = 5
        val sy = 2
        image(BACKGROUND, ROOT, sx, sy, "MinesInterface/background 0")
        button(LIGHT, ROOT, 16, sy + 7, "MinesInterface/light", 0, 1, "Transparent Background")
        text(TITLE, ROOT, sx + 135 - 20, sy + 10, "GadScape Mines", 16750623, true, true, 2, roatWidth = 350)
        clickText(RULES_BUTTON, ROOT, 40, sy + 10, "<img=264:1> Rules", "Select", 2, 12698049, false, true, 60, 16)
        button(PROVABLY_FAIR, ROOT, sx + 454, sy + 7, "BlackjackInterface/green_lock", 0, 1, "Provably Fair")

        container(STRIP, ROOT, sx + 42, sy + 39, 418, 17)
        for (a in 0 until STRIP_SLOTS) {
            image(STRIP + 1 + a * 2, STRIP, a * 61, 0, "MinesInterface/top_part 0")
            text(STRIP + 2 + a * 2, STRIP, 26 + a * 61, 3, "", 118328, true, true, 0)
        }

        var x = 46
        var y = 67
        for (a in 0 until TILES) {
            button(TILE + a * 2, ROOT, sx + x, sy + y, "MinesInterface/button", 0, 1, "Select")
            text(TILE + a * 2 + 1, ROOT, sx + x + 36, sy + y + 15, "", 9342336, true, true, 2, hide = true)
            image(TILE_STATE + a, ROOT, sx + x, sy + y, "MinesInterface/button 2", hide = true)
            image(AUTO_PICK + a, ROOT, sx + x, sy + y, "MinesInterface/button_auto 1", hide = true)
            image(AUTO_PLAYING + a, ROOT, sx + x, sy + y, "MinesInterface/auto overlay", hide = true)
            if ((a + 1) % 5 == 0) {
                x = 46
                y += 47
            } else {
                x += 84
            }
        }

        shade(DIM, ROOT, sx, sy, 501, 330, 0x000000, 179, hide = true)
        image(WIN_PANEL, ROOT, sx + 130, sy + 91, "MinesInterface/winning 0", hide = true)
        button(CLOSE, ROOT, sx + 474, sy + 7, "PriceCheckerInterface/close", 1, 2, "Close")
        text(SESSION, ROOT, sx + 250, sy + 310, "", 16750623, true, true, 0)
        text(AUTO_LABEL, ROOT, sx + 250, sy + 310, "", 16750623, true, true, 0, hide = true)
        text(WIN_TITLE, ROOT, sx + 130 + 120, sy + 121, "YOU WIN!", 65280, true, true, 2, hide = true)
        text(WIN_MULTIPLIER, ROOT, sx + 130 + 120, sy + 164, "", 65280, true, true, 2, hide = true)
        text(WIN_AMOUNT, ROOT, sx + 130 + 120, sy + 192, "", 65280, true, true, 2, hide = true)
        button(WIN_CLOSE, ROOT, sx + 130 + 120 + 75, sy + 192 - 72, "MinesInterface/close", 0, 1, "Close", hide = true)

        shade(RULES_DIM, ROOT, sx, sy, 501, 330, 0x000000, 179, hide = true)
        container(RULES, ROOT, 0, 0, 512, 334, hide = true)
        image(51251, RULES, sx + 72, sy + 26, "MinesInterface/background 2")
        button(RULES_CLOSE, RULES, sx + 403, sy + 33, "PriceCheckerInterface/close", 1, 2, "Close")
        text(51253, RULES, sx + 246, sy + 37, "<img=264> Mines Rules", 16750623, true, true, 2)
        text(51254, RULES, sx + 246, sy + 68 - 4, "Reveal mines to increase your payout multiplier.", 16754236, true, true, 1)
        text(51255, RULES, sx + 246, sy + 83 - 4, "Cash out anytime to secure winnings at the multiplier.", 16754236, true, true, 1)
        text(51256, RULES, sx + 246, sy + 98 - 4, "If a bomb is revealed, the game ends, and you lose.", 16754236, true, true, 1)
        text(51257, RULES, sx + 246, sy + 113 - 4, "Adding bombs increases potential multipliers on reveal.", 16754236, true, true, 1)
        image(51258, RULES, sx + 174, sy + 140 - 6, "BlackjackInterface/side/line_top 1")
        clickText(RATES, RULES, sx + 135, sy + 143, "<img=287> Click Here to View Mine Rates! <img=287>", "Select", 2, 12698049, false, true, 230, 16)
        image(51259, RULES, sx + 176, sy + 166, "BlackjackInterface/side/line_top 1")
        text(51262, RULES, sx + 246, sy + 175, "<img=126:1> The Max Win For This Game:", 16760433, true, true, 2)
        text(51263, RULES, sx + 246, sy + 193, "@gre@250,000x<col=bd823c> your bet, paid up to the max stack.", 12419644, true, true, 0)
        image(51267, RULES, sx + 174, sy + 228, "BlackjackInterface/side/line_top 1")
        text(51265, RULES, sx + 246, sy + 234, "<col=ffbe71>The return to player (RTP) on this game is @gre@98%.", 16760433, true, true, 0)
        image(51268, RULES, sx + 174, sy + 250, "BlackjackInterface/side/line_top 1")
        text(51266, RULES, sx + 246, sy + 256, "<img=209> @yel@Gamble Responsibly and Enjoy! <img=209>", 16750623, true, true, 0)

        ProvablyFairPanel.add(this, FAIR, sx, sy, "game", "mine grid")
    }

    private fun buildSide() = RoatUi(SIDE_ID, 190, 261, side = true).apply {
        container(MANUAL, ROOT, 0, 0, 190, 261)
        container(AUTO_TAB, ROOT, 0, 6, 174, 250, hide = true, scrollHeight = 510)
        image(51297, ROOT, 3, 0, "DiceInterface/side/background 2")
        clickText(MODE, ROOT, 48, 3, "@cu1@mode: @cu2@manual", "Switch between manual & auto", 0, 15063096, true, true, 90, 14)

        betRows(this, MANUAL, 0, 0)
        image(51309, MANUAL, 17, 99, "MinesInterface/side/line_bottom 0")
        text(MINES_LABEL, MANUAL, 9, 105, "Mines:", 16777215, false, true, 0)
        text(PROFIT_LABEL, MANUAL, 9, 144, "Total Profit (@gre@1.00x@whi@):", 16777215, false, true, 0)
        image(51313, MANUAL, 8, 158, "MinesInterface/background 3")
        text(PROFIT, MANUAL, 95, 161, "", 65280, true, true, 2)
        image(COUNTS, MANUAL, 8, 118, "MinesInterface/background 4", hide = true)
        text(GEMS_LEFT, MANUAL, 51, 121, "", 65280, true, true, 2, hide = true)
        text(MINES_LEFT, MANUAL, 141, 121, "", 65280, true, true, 2, hide = true)
        button(BET, MANUAL, 7, 216, "MinesInterface/side/bet", 0, 1, "Bet!")
        image(STATE + BET, MANUAL, 7, 216, "MinesInterface/side/bet 2", hide = true)
        button(CASHOUT, MANUAL, 7, 216, "MinesInterface/side/cashout", 0, 1, "Cashout!", hide = true)
        image(STATE + CASHOUT, MANUAL, 7, 216, "MinesInterface/side/cashout 2", hide = true)
        button(RANDOM_TILE, MANUAL, 7, 183, "MinesInterface/side/randomtile", 0, 1, "Pick a Random Tile!")
        image(STATE + RANDOM_TILE, MANUAL, 7, 183, "MinesInterface/side/randomtile 2")
        minesPicker(this, MANUAL, MINES_PICKER, MINES_PICKER_TEXT, 118)

        betRows(this, AUTO_TAB, AUTO, 6)
        text(51620, AUTO_TAB, 9, 93, "Number of Bets:", 16777215, false, true, 0)
        image(51621, AUTO_TAB, 8, 106, "DiceInterface/side/number_of_bets 0")
        button(BETS_MINUS, AUTO_TAB, 99, 106, "DiceInterface/side/number_of_bets", 1, 2, "-10 Bets")
        button(BETS_PLUS, AUTO_TAB, 128, 106, "DiceInterface/side/number_of_bets", 3, 4, "+10 Bets")
        button(BETS_INF, AUTO_TAB, 157, 106, "DiceInterface/side/number_of_bets", 5, 6, "INF Bets")
        clickText(BETS, AUTO_TAB, 8, 112, "  10", "Change Number of Bets", 2, 13156279, false, true, 86, 20)
        image(AUTO + 51309, AUTO_TAB, 17, 145, "MinesInterface/side/line_bottom 0")
        text(51626, AUTO_TAB, 9, 157, "Mines:", 16777215, false, true, 0)
        minesPicker(this, AUTO_TAB, AUTO_MINES_PICKER, AUTO_MINES_PICKER_TEXT, 170)
        text(51627, AUTO_TAB, 9, 198, "On Success:", 16777215, false, true, 0)
        image(51628, AUTO_TAB, 8, 211, "MinesInterface/side/background 0")
        button(SUCCESS_STOP, AUTO_TAB, 10, 213, "MinesInterface/side/stop_auto", 0, 1, "Stop")
        image(STATE + SUCCESS_STOP, AUTO_TAB, 10, 213, "MinesInterface/side/stop_auto 2", hide = true)
        button(SUCCESS_CASHOUT, AUTO_TAB, 96, 213, "MinesInterface/side/cashout_auto", 0, 1, "Cashout")
        image(STATE + SUCCESS_CASHOUT, AUTO_TAB, 96, 213, "MinesInterface/side/cashout_auto 2")
        val dy = 84
        onWinOrLoss(this, 51631, "On Win:", 163 + dy, WIN_RESET, WIN_INCREASE, WIN_PERCENT, "Change on Win Percentage")
        onWinOrLoss(this, 51636, "On Loss:", 211 + dy, LOSS_RESET, LOSS_INCREASE, LOSS_PERCENT, "Change on Loss Percentage")
        image(51641, AUTO_TAB, 17, 264 + dy, "DiceInterface/side/line_bottom 0")
        text(51642, AUTO_TAB, 9, 274 + dy, "Stop on Profit:", 16777215, false, true, 0)
        image(51643, AUTO_TAB, 8, 287 + dy, "DiceInterface/side/background 4")
        clickText(STOP_PROFIT, AUTO_TAB, 8, 293 + dy, "  0", "Change Stop on Profit", 2, 65280, false, true, 106, 20)
        text(51645, AUTO_TAB, 9, 321 + dy, "Stop on Loss:", 16777215, false, true, 0)
        image(51646, AUTO_TAB, 8, 334 + dy, "DiceInterface/side/background 4")
        clickText(STOP_LOSS, AUTO_TAB, 8, 340 + dy, "  0", "Change Stop on Loss", 2, 16711680, false, true, 106, 20)
        image(51648, AUTO_TAB, 17, 371 + dy, "DiceInterface/side/line_bottom 0")
        button(AUTOBET, AUTO_TAB, 8, 380 + dy, "DiceInterface/side/start_autobet", 0, 1, "Start or Stop Autobet")
        image(STATE + AUTOBET, AUTO_TAB, 8, 380 + dy, "DiceInterface/side/stop_autobet 0", hide = true)
        scrollbar(AUTO_TAB * 10 + 9, ROOT, 174, 6, 250, AUTO_TAB)
    }

    private fun betRows(ui: RoatUi, parent: Int, offset: Int, dy: Int) = with(ui) {
        text(offset + BALANCE, parent, 95, 16 - dy, "", 16750623, true, true, 2)
        clickText(offset + CURRENCY, parent, 50, 32 - dy, "(change currency)", "Select", 0, 15063096, false, true, 90, 14)
        image(offset + 51303, parent, 41, 47 - dy, "MinesInterface/side/line_top 0")
        text(offset + 51304, parent, 9, 53 - dy, "Bet Amount:", 16777215, false, true, 0)
        image(offset + 51305, parent, 8, 66 - dy, "MinesInterface/side/background_bet 4")
        button(offset + HALF, parent, 128, 66 - dy, "MinesInterface/side/background_bet", 0, 1, "1/2 Bet Size")
        button(offset + DOUBLE, parent, 157, 66 - dy, "MinesInterface/side/background_bet", 2, 3, "2x Bet Size")
        clickText(offset + BET_AMOUNT, parent, 8, 72 - dy, "", "Change Bet Size", 2, 16776960, false, true, 106, 20)
    }

    /** Roat's mines DropDownMenu (1-24): the box shows the count; click to type one, or step it. */
    private fun minesPicker(ui: RoatUi, parent: Int, id: Int, textId: Int, y: Int) = with(ui) {
        image(id, parent, 8, y, "MinesInterface/background 4")
        clickText(textId, parent, 8, y + 3, "@yel@3", "Choose", 2, 65280, true, true, 175, 16)
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
