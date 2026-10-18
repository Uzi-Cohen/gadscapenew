package com.gadscape.game.content.gamble.ui

import com.gadscape.game.content.gamble.ui.RoatUi.Companion.ROOT

/**
 * Roat Pkz's blackjack table (their 30800, BlackJackTableInterface) and side panel (33600,
 * BlackJackTableSideInterface), ported call for call. Component ids are Roat's; [STATE] + id is the greyed-out
 * look of a side button.
 */
object BlackjackUi {

    const val MAIN_ID = 5124
    const val SIDE_ID = 5125

    const val STATE = 200_000

    // main window
    const val BACKGROUND = 30801
    const val CLOSE = 30802
    const val TITLE = 30803
    const val STATS_WINS = 30808
    const val STATS_LOSSES = 30809
    const val STATS_PROFIT = 30810
    const val STATS_EXTRA = 30818
    const val ARROW_RIGHT = 30813
    const val ARROW_LEFT = 30814
    const val LIGHT = 30815
    const val RULES_BUTTON = 30816
    const val PROVABLY_FAIR = 30819

    /** Hand total bubbles (background, glow, text) for the first hand, the split hand and the dealer. */
    const val HAND1_BG = 30820
    const val HAND1_GLOW = 30821
    const val HAND1_TOTAL = 30822
    const val HAND2_BG = 30823
    const val HAND2_GLOW = 30824
    const val HAND2_TOTAL = 30825
    const val DEALER_BG = 30826
    const val DEALER_GLOW = 30827
    const val DEALER_TOTAL = 30828

    /** Card slot i: [CARD] + i, and its win/loss/push frame [CARD_GLOW] + i. */
    const val CARD = 30830
    const val CARD_GLOW = 30860
    const val CARD_SLOTS = 30
    const val CARD_WIDTH = 87

    const val DIM = 30900
    const val RULES = 30920
    const val RULES_CLOSE = 30902
    const val FAIR = 30930

    // side panel
    const val BALANCE = 33601
    const val CURRENCY = 33602
    const val HALF = 33606
    const val DOUBLE_BET = 33607
    const val BET_AMOUNT = 33608
    const val HIT = 33610
    const val STAND = 33611
    const val SPLIT = 33612
    const val DOUBLE = 33613
    const val BET = 33614
    const val INSURANCE_YES = 33621
    const val INSURANCE_NO = 33622

    /** Side panel parts shown while betting/playing (33601-33614) and while insurance is offered (33620-33632). */
    val PLAY_PARTS = listOf(33601, 33602, 33603, 33604, 33605, HALF, DOUBLE_BET, BET_AMOUNT, 33609, HIT, STAND, SPLIT, DOUBLE, BET)
    val INSURANCE_PARTS = (33620..33632).toList()

    /** Card sprite: 0 back, 1-52 a card (Roat's card ids), 53/54 their spare backs. */
    fun cardSprite(card: Int) = RoatSprites.id("BlackjackInterface/cards/card/$card")

    /** Card frame: 0 green (win), 1 red (loss), 2 orange (push). */
    fun glowSprite(colour: Int) = RoatSprites.id(
        when (colour) {
            0 -> "BlackjackInterface/cards/green"
            1 -> "BlackjackInterface/cards/red"
            else -> "BlackjackInterface/cards/orange"
        }
    )

    /** Total bubble glow: 0-2 = Roat's glow 0..2. */
    fun bubbleGlowSprite(glow: Int) = RoatSprites.id("BlackjackInterface/glow $glow")

    val main: RoatUi by lazy { buildMain() }
    val side: RoatUi by lazy { buildSide() }

    private fun buildMain() = RoatUi(MAIN_ID, 512, 334, side = false).apply {
        val sx = 5
        val sy = 5
        image(BACKGROUND, ROOT, sx, sy, "BlackjackInterface/background 0")
        button(LIGHT, ROOT, 16, sy + 7, "BlackjackInterface/light", 0, 1, "Transparent Background")
        button(CLOSE, ROOT, sx + 474, sy + 7, "PriceCheckerInterface/close", 1, 2, "Close")
        button(PROVABLY_FAIR, ROOT, sx + 454, sy + 7, "BlackjackInterface/green_lock", 0, 1, "Provably Fair")
        text(TITLE, ROOT, sx + 80 - 50 - 7, sy + 10, "GadScape Blackjack", 16750623, true, true, 2, roatWidth = 424)
        image(30804, ROOT, sx + 412, sy + 27, "BlackjackInterface/cards/card/0")
        image(30805, ROOT, sx + 406, sy + 27, "BlackjackInterface/cards/card/0")
        image(30806, ROOT, sx + 400, sy + 27, "BlackjackInterface/cards/card/0")
        text(30807, ROOT, sx + 10, sy + 39, "Your session stats:", 16777215, false, true, 0)
        text(STATS_WINS, ROOT, sx + 10, sy + 40 + 13, "@whi@- Wins: @gre@0", 16777215, false, true, 0)
        text(STATS_LOSSES, ROOT, sx + 10, sy + 40 + 26, "@whi@- Losses: @red@0", 16777215, false, true, 0)
        text(STATS_PROFIT, ROOT, sx + 10, sy + 40 + 39, "@whi@- Profit: @gre@0", 16777215, false, true, 0)
        text(STATS_EXTRA, ROOT, sx + 10, sy + 40 + 52 + 1, "", 16777215, false, true, 0)
        text(30811, ROOT, sx + 250, sy + 163, "DEALER MUST DRAW TO 16 AND STAND ON ALL 17'S", 12698049, true, true, 2)
        text(30812, ROOT, sx + 250, sy + 163 + 16, "INSURANCE PAYS 2-1   |   BJ PAYS 3-2", 12698049, true, true, 2)
        clickText(RULES_BUTTON, ROOT, 13, 309, "<img=264> Rules", "Select", 2, 12698049, false, true, 60, 16)

        // total bubbles at the spots clearInterfaceForNextGame() moves them to
        image(HAND1_BG, ROOT, 211, 291, "BlackjackInterface/number_background 0")
        image(HAND1_GLOW, ROOT, 208, 288, "BlackjackInterface/glow 0", hide = true)
        text(HAND1_TOTAL, ROOT, 254, 297, "Place bets", 16777215, true, true, 2)
        image(HAND2_BG, ROOT, 211, 291, "BlackjackInterface/number_background 0", hide = true)
        image(HAND2_GLOW, ROOT, 208, 288, "BlackjackInterface/glow 0", hide = true)
        text(HAND2_TOTAL, ROOT, 254, 297, "0", 16777215, true, true, 2, hide = true)
        image(DEALER_BG, ROOT, sx + 206, sy + 286 - 158, "BlackjackInterface/number_background 0", hide = true)
        image(DEALER_GLOW, ROOT, sx + 203, sy + 283 - 158, "BlackjackInterface/glow 0", hide = true)
        text(DEALER_TOTAL, ROOT, sx + 249, sy + 292 - 158, "", 16777215, true, true, 2)

        for (i in 0 until CARD_SLOTS) {
            image(CARD + i, ROOT, sx, sy, "BlackjackInterface/cards/card/0", hide = true)
            image(CARD_GLOW + i, ROOT, sx, sy, "BlackjackInterface/cards/green", hide = true)
        }
        image(ARROW_RIGHT, ROOT, sx + 158, sy + 224, "BlackjackInterface/cards/arrow_right", hide = true)
        image(ARROW_LEFT, ROOT, sx + 302, sy + 224, "BlackjackInterface/cards/arrow_left", hide = true)

        shade(DIM, ROOT, sx, sy, 501, 325, 0x000000, 196, hide = true)
        container(RULES, ROOT, 0, 0, 512, 334, hide = true)
        image(30901, RULES, sx + 72, sy + 26, "BlackjackInterface/background 2")
        button(RULES_CLOSE, RULES, sx + 403, sy + 33, "PriceCheckerInterface/close", 1, 2, "Close")
        text(30903, RULES, sx + 246, sy + 37, "<img=264> Blackjack Rules", 16750623, true, true, 2)
        text(30904, RULES, sx + 246, sy + 68, "In blackjack, players attempt to reach a score of 21", 16754236, true, true, 1)
        text(30905, RULES, sx + 246, sy + 83, "without exceeding it-before the dealer hits 17.", 16754236, true, true, 1)
        text(30906, RULES, sx + 246, sy + 98, "You can win if you don't bust and your total is higher", 16754236, true, true, 1)
        text(30907, RULES, sx + 246, sy + 113, "than the dealer cards. Face Cards = 10, Ace = 1/11.", 16754236, true, true, 1)
        image(30908, RULES, sx + 176, sy + 140, "BlackjackInterface/side/line_top 1")
        text(30909, RULES, sx + 246, sy + 150, "<col=ffbe71>Blackjack pays out @gre@3:2.  <col=ffbe71>|  Insurance pays out @gre@2:1.", 16760433, true, true, 0)
        text(30910, RULES, sx + 246, sy + 163, "<col=bd823c>Dealer draws on @gre@16 <col=bd823c>or less and stands on any @gre@17.", 12419644, true, true, 0)
        text(30911, RULES, sx + 246, sy + 176, "Player can double after a split.", 16760433, true, true, 0)
        text(30912, RULES, sx + 246, sy + 189, "Player can double any first two cards.", 12419644, true, true, 0)
        text(30913, RULES, sx + 246, sy + 202, "Player can only hit split aces one time.", 16760433, true, true, 0)
        text(30914, RULES, sx + 246, sy + 215, "@gre@8x <col=bd823c>card decks are used. | Deck is shuffled every game.", 12419644, true, true, 0)
        text(30915, RULES, sx + 246, sy + 228, "<col=ffbe71>The house edge is roughly @gre@0.48%.", 16760433, true, true, 0)
        text(30917, RULES, sx + 246, sy + 254, "<img=209> @yel@Gamble Responsibly and Enjoy! <img=209>", 16750623, true, true, 0)

        ProvablyFairPanel.add(this, FAIR, sx, sy, "hand", "deck", nonceName = "Hand Number")
    }

    private fun buildSide() = RoatUi(SIDE_ID, 190, 261, side = true).apply {
        text(BALANCE, ROOT, 95, 6, "", 16750623, true, true, 2)
        clickText(CURRENCY, ROOT, 50, 22, "(change currency)", "Select", 0, 15063096, false, true, 90, 14)
        image(33603, ROOT, 41, 38, "BlackjackInterface/side/line_top 0")
        text(33604, ROOT, 9, 45, "Bet Amount:", 16777215, false, true, 0)
        image(33605, ROOT, 8, 58, "BlackjackInterface/side/background_bet 4")
        button(HALF, ROOT, 128, 58, "BlackjackInterface/side/background_bet", 0, 1, "1/2 Bet Size")
        button(DOUBLE_BET, ROOT, 157, 58, "BlackjackInterface/side/background_bet", 2, 3, "2x Bet Size")
        clickText(BET_AMOUNT, ROOT, 8, 64, "", "Change Bet Size", 2, 16776960, false, true, 106, 20)
        image(33609, ROOT, 17, 91, "BlackjackInterface/side/line_bottom 0")
        sideButton(this, HIT, 7, 99, "hit", "Hit!")
        sideButton(this, STAND, 7, 137, "stand", "Stand!")
        sideButton(this, SPLIT, 7, 175, "split", "Split!")
        sideButton(this, DOUBLE, 98, 175, "double", "Double!")
        sideButton(this, BET, 7, 213, "bet", "Bet!")
        text(33620, ROOT, 95, 30, "Insurance?", 16750623, true, true, 2, hide = true)
        button(INSURANCE_YES, ROOT, 7, 60, "BlackjackInterface/side/insurance", 0, 1, "Accept Insurance", hide = true)
        button(INSURANCE_NO, ROOT, 98, 60, "BlackjackInterface/side/insurance", 0, 1, "Decline Insurance", hide = true)
        text(33623, ROOT, 49, 69, "@gre@Yes", 16750623, true, true, 2, hide = true)
        text(33624, ROOT, 140, 69, "@red@No", 16750623, true, true, 2, hide = true)
        image(33625, ROOT, 41, 110, "BlackjackInterface/side/line_top 0", hide = true)
        text(33626, ROOT, 95, 125, "Insurance is a side bet that costs", 14579712, true, true, 0, hide = true)
        text(33627, ROOT, 95, 138, "50% of your initial bet.", 14579712, true, true, 0, hide = true)
        image(33628, ROOT, 41, 162, "BlackjackInterface/side/line_top 0", hide = true)
        text(33629, ROOT, 95, 177, "If the dealer has a blackjack", 14579712, true, true, 0, hide = true)
        text(33630, ROOT, 95, 190, "you'll receive your initial bet", 14579712, true, true, 0, hide = true)
        text(33631, ROOT, 95, 203, "size back, essentially breaking", 14579712, true, true, 0, hide = true)
        text(33632, ROOT, 95, 216, "even on the hand.", 14579712, true, true, 0, hide = true)
    }

    /** A side button with its greyed-out look ("hit 2" etc.) on top while it can't be used. */
    private fun sideButton(ui: RoatUi, id: Int, x: Int, y: Int, name: String, tooltip: String) = with(ui) {
        button(id, ROOT, x, y, "BlackjackInterface/side/$name", 0, 1, tooltip)
        image(STATE + id, ROOT, x, y, "BlackjackInterface/side/$name 2", hide = true)
    }
}
