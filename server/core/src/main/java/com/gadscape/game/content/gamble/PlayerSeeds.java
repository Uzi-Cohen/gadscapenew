package com.gadscape.game.content.gamble;

import com.gadscape.game.content.gamble.fair.ProvablyFair;

/**
 * Per-player provably-fair state, exactly like Roat Pkz's "green lock" panel:
 * client seed (player can edit), server seed (only its SHA-256 is shown until revealed), and the game number (nonce).
 *
 * Revealing the server seed rotates to a fresh one and resets the nonce, so the revealed seed can be checked against
 * every game played with it (on Roat's verifier pages, copied in ../provably-fair/html).
 *
 * Persist clientSeed/serverSeed/nonce in the player save so the nonce never repeats for the same seed pair.
 */
public final class PlayerSeeds {
   private String clientSeed;
   private String serverSeed;
   private int nonce;

   public PlayerSeeds() {
      this(ProvablyFair.randomSeed(), ProvablyFair.randomSeed(), 0);
   }

   public PlayerSeeds(String clientSeed, String serverSeed, int nonce) {
      this.clientSeed = clientSeed;
      this.serverSeed = serverSeed;
      this.nonce = nonce;
   }

   public String clientSeed() {
      return clientSeed;
   }

   /** Used by the games to compute outcomes. Never send this to the player before reveal(). */
   public String serverSeed() {
      return serverSeed;
   }

   public String serverSeedHash() {
      return ProvablyFair.sha256Hex(serverSeed);
   }

   public int nonce() {
      return nonce;
   }

   /** Returns the nonce to use for this game and advances it. */
   public int nextNonce() {
      return nonce++;
   }

   public boolean setClientSeed(String seed) {
      if (!ProvablyFair.isSeedValid(seed)) {
         return false;
      }
      clientSeed = seed;
      return true;
   }

   /** Reveal the current server seed (so the player can verify past games) and start a new one. */
   public String reveal() {
      String old = serverSeed;
      serverSeed = ProvablyFair.randomSeed();
      nonce = 0;
      return old;
   }
}
