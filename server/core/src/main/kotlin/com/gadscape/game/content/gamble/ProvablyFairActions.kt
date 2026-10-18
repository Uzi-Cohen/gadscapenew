package com.gadscape.game.content.gamble

import com.gadscape.game.content.gamble.ui.ProvablyFairPanel
import com.zenyte.game.world.entity.player.Player

/** The green-lock panel's buttons: edit the client seed, reveal/rotate the server seed, verify the last game. */
object ProvablyFairActions {

    /**
     * One game's panel: [id] is its container (Roat's 51550 / 51500 / 30930), [dim] the shade behind it,
     * [busy] true while a game that uses the current seeds is still running, [lastGame] describes the last game.
     */
    class Panel(
        val id: Int,
        val dim: Int,
        val nonceName: String,
        val view: (Player) -> GambleView,
        val busy: (Player) -> Boolean,
        val lastGame: (Player) -> String?,
    ) {
        fun show(player: Player, show: Boolean) {
            val v = view(player)
            v.hide(id, !show)
            v.hide(dim, !show)
            if (show) refresh(player)
        }

        fun refresh(player: Player) {
            val seeds = Gamble.seeds(player)
            val v = view(player)
            v.text(id + ProvablyFairPanel.CLIENT_SEED, "<col=ffbe71>Client Seed:@gre@ " + seeds.clientSeed() + " <col=ffbe71>(edit)")
            v.text(id + ProvablyFairPanel.SERVER_SEED, "<col=ffbe71>Server Seed:@red@ Click to reveal or shuffle")
            v.text(id + ProvablyFairPanel.SERVER_HASH, "<col=ffbe71>Server Seed Hashed:@red@ " + shortHash(seeds.serverSeedHash()))
            v.text(id + ProvablyFairPanel.NONCE, "<col=ffbe71>$nonceName (nonce):@cya@ " + (seeds.nonce() + 1))
        }

        /** Handles a click inside the panel; false if [roatId] isn't one of its buttons. */
        fun click(player: Player, roatId: Int): Boolean {
            when (roatId - id) {
                ProvablyFairPanel.CLOSE -> show(player, false)
                ProvablyFairPanel.CLIENT_SEED -> editClientSeed(player)
                ProvablyFairPanel.SERVER_SEED -> reveal(player)
                ProvablyFairPanel.SERVER_HASH -> player.sendMessage("Server seed hash: " + Gamble.seeds(player).serverSeedHash())
                ProvablyFairPanel.NONCE -> player.sendMessage("Your next game is number ${Gamble.seeds(player).nonce() + 1} with these seeds.")
                ProvablyFairPanel.VERIFY -> verify(player)
                ProvablyFairPanel.VIDEO, ProvablyFairPanel.SOURCE, ProvablyFairPanel.SITE -> explain(player)
                else -> return false
            }
            return true
        }

        private fun editClientSeed(player: Player) {
            if (busy(player)) {
                player.sendMessage("Finish your current game before changing your seed.")
                return
            }
            player.sendInputString("Enter a new client seed (letters and numbers):") { seed ->
                if (busy(player)) return@sendInputString
                val seeds = Gamble.seeds(player)
                if (!seeds.setClientSeed(seed.trim())) {
                    player.sendMessage("That seed isn't valid. Use letters and numbers only.")
                    return@sendInputString
                }
                Gamble.save(player, seeds)
                player.sendMessage("Your client seed is now: ${seeds.clientSeed()}")
                refresh(player)
            }
        }

        private fun reveal(player: Player) {
            if (busy(player)) {
                player.sendMessage("Finish your current game before revealing your server seed.")
                return
            }
            val seeds = Gamble.seeds(player)
            val games = seeds.nonce()
            val hash = seeds.serverSeedHash()
            val old = seeds.reveal()
            Gamble.save(player, seeds)
            player.sendMessage("<col=00a000>Revealed server seed:</col> $old")
            player.sendMessage("Its SHA-256 is $hash, the hash you were shown. It was used for games 0 to ${games - 1} with client seed ${seeds.clientSeed()}.")
            player.sendMessage("New server seed hash: ${seeds.serverSeedHash()} (game number reset).")
            refresh(player)
        }

        private fun verify(player: Player) {
            val seeds = Gamble.seeds(player)
            val last = lastGame(player)
            if (last == null) {
                player.sendMessage("You haven't played a game this session yet.")
            } else {
                player.sendMessage(last)
            }
            player.sendMessage("Client seed: ${seeds.clientSeed()}, server seed hash: ${seeds.serverSeedHash()}.")
            player.sendMessage("Reveal your server seed to check any game played with it: each result comes from SHA-256(clientSeed:serverSeed:nonce).")
        }

        private fun explain(player: Player) {
            player.sendMessage("<col=00a000>How provably fair works:</col> before you play, the server picks a secret server seed and shows you its SHA-256 hash.")
            player.sendMessage("Every result is made from SHA-256(clientSeed:serverSeed:nonce), where you choose the client seed and the nonce counts your games.")
            player.sendMessage("Reveal the server seed at any time: its hash matches the one you saw, so it couldn't have been changed, and you can recompute every result.")
        }
    }

    private fun shortHash(hash: String) = if (hash.length > 24) hash.substring(0, 24) + "..." else hash
}
