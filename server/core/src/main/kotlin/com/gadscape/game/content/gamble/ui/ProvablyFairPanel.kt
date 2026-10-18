package com.gadscape.game.content.gamble.ui

/**
 * Roat Pkz's "Provably Fair Gaming" panel (the green lock), which their dice, mines and blackjack windows each
 * add as a hidden sub-interface: [id] is the container (51550 dice, 51500 mines, 30930 blackjack) and the parts
 * are [id] + 1..19 like theirs.
 */
object ProvablyFairPanel {

    const val CLOSE = 2
    const val CLIENT_SEED = 11
    const val SERVER_SEED = 12
    const val SERVER_HASH = 13
    const val NONCE = 14
    const val VERIFY = 15
    const val VIDEO = 16
    const val SOURCE = 17
    const val SITE = 18

    /**
     * @param what "dice roll" / "game" / "hand": what each wager is ("verify each [what] is genuine")
     * @param locked "dice roll" / "deck": what the seed hash locks ("locks the [locked] pre-play")
     */
    fun add(ui: RoatUi, id: Int, sx: Int, sy: Int, what: String, locked: String, nonceName: String = "Game Number") = with(ui) {
        val altered = if (locked == "deck") "shuffle" else locked
        container(id, RoatUi.ROOT, 0, 0, 512, 334, hide = true)
        image(id + 1, id, sx + 72, sy + 26 - 8, "BlackjackInterface/background 5")
        button(id + CLOSE, id, sx + 403, sy + 33 - 8, "PriceCheckerInterface/close", 1, 2, "Close")
        text(id + 3, id, sx + 252, sy + 37 - 8, "<img=331:-1> @gre@Provably Fair Gaming <img=331:-1>", 16750623, true, true, 2)
        text(id + 4, id, sx + 252, sy + 68 - 4 - 8, "Every wager is secured by cryptographic proof,", 16754236, true, true, 1)
        text(id + 5, id, sx + 252, sy + 83 - 4 - 8, "letting you verify each $what is genuine and intact.", 16754236, true, true, 1)
        text(id + 6, id, sx + 252, sy + 98 - 4 - 8, "A public server-seed hash locks the $locked pre-play,", 16754236, true, true, 1)
        text(id + 7, id, sx + 252, sy + 113 - 4 - 8, "so no one-including us-can alter the $altered later.", 16754236, true, true, 1)
        text(id + 8, id, sx + 252, sy + 128 - 4 - 8, "This removes any chance of rigging or hidden bias,", 16754236, true, true, 1)
        text(id + 9, id, sx + 252, sy + 143 - 4 - 8, "ensuring every win or loss is pure, provable luck.", 16754236, true, true, 1)
        image(id + 10, id, sx + 176, sy + 163 - 8, "BlackjackInterface/side/line_top 1")
        clickText(id + CLIENT_SEED, id, sx + 82, sy + 171 - 8, "<col=ffbe71>Client Seed:@gre@ XXXXXXXXXXXX <col=ffbe71>(edit)@whi@", "Edit", 1, 12698049, true, true, 338, 16)
        clickText(id + SERVER_SEED, id, sx + 82, sy + 187 - 8, "<col=ffbe71>Server Seed:@red@ Click to reveal or shuffle@whi@", "Reveal", 1, 12698049, true, true, 338, 16)
        clickText(id + SERVER_HASH, id, sx + 82, sy + 203 - 8, "<col=ffbe71>Server Seed Hashed:@red@ XXXXXXXXXXXX@whi@", "Copy", 1, 12698049, true, true, 338, 16)
        clickText(id + NONCE, id, sx + 82, sy + 219 - 8, "<col=ffbe71>$nonceName (nonce):@cya@ 1@whi@", "Select", 1, 12698049, true, true, 338, 16)
        button(id + VERIFY, id, sx + 82 + 65, sy + 240 - 8, "BlackjackInterface/verify", 0, 1, "Verify")
        image(id + 19, id, sx + 176, sy + 281 - 8, "BlackjackInterface/side/line_top 1")
        clickText(id + VIDEO, id, sx + 82 + 3, sy + 292 - 8, "<col=da7d0f>How it works@whi@", "Select", 2, 12698049, false, true, 80, 16)
        clickText(id + SOURCE, id, sx + 82 + 95, sy + 292 - 8, "<col=da7d0f>Source Code@whi@", "Select", 2, 12698049, false, true, 103, 16)
        clickText(id + SITE, id, sx + 82 + 210, sy + 292 - 8, "<col=da7d0f>Verify a Game@whi@", "Select", 2, 12698049, false, true, 131, 16)
    }
}
