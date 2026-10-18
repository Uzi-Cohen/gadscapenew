package com.gadscape.game.content.gamble.mines;

import com.gadscape.game.content.gamble.PlayerSeeds;
import java.util.BitSet;
import com.gadscape.game.content.gamble.fair.ProvablyFairMines;

/**
 * Roat Pkz Mines: 5x5 board, 1-24 mines, reveal gems to raise the multiplier, cash out any time, a mine loses the bet.
 *
 * Multiplier after k gems with m mines = 0.98^k * C(25,k) / C(25-m,k) (the 2% edge applies on every reveal),
 * rounded to 2 decimals, capped at 250,000x.
 * This reproduces every cell of Roat's published rate sheet (../mines_multipliers_official.csv) - checked in SelfTest.
 * Mine layout = their provably-fair ProvablyFairMines.generateLayout(clientSeed, serverSeed, nonce, mines).
 */
public final class MinesGame {
   public static final int TILES = 25;
   public static final double RTP = 0.98;
   public static final double MAX_MULTIPLIER = 250_000.0;

   public enum Reveal { GEM, MINE, ALREADY_REVEALED, NOT_RUNNING }

   private final long bet;
   private final int mines;
   private final int nonce;
   private final String serverSeedHash;
   private final BitSet layout;
   private final boolean[] revealed = new boolean[TILES];
   private int gems;
   private boolean running = true;
   private boolean lost;

   private MinesGame(long bet, int mines, int nonce, String serverSeedHash, BitSet layout) {
      this.bet = bet;
      this.mines = mines;
      this.nonce = nonce;
      this.serverSeedHash = serverSeedHash;
      this.layout = layout;
   }

   /** Start a round. The caller removes the bet from the player first. */
   public static MinesGame start(PlayerSeeds seeds, long bet, int mines) {
      if (mines < 1 || mines > 24) throw new IllegalArgumentException("mines must be 1-24");
      int nonce = seeds.nextNonce();
      BitSet layout = ProvablyFairMines.generateLayout(seeds.clientSeed(), seeds.serverSeed(), nonce, mines);
      return new MinesGame(bet, mines, nonce, seeds.serverSeedHash(), layout);
   }

   public static double multiplier(int mines, int gems) {
      if (gems == 0) return 1.0;
      double m = 1.0;
      // RTP divided by the chance of surviving each reveal; this exact arithmetic order reproduces every cell of
      // Roat's published sheet (incl. 5 mines/1 gem = 1.22, where the double is 1.2249999...).
      for (int i = 0; i < gems; i++) m *= RTP / ((double) (TILES - mines - i) / (TILES - i));
      return Math.min(MAX_MULTIPLIER, Math.round(m * 100.0) / 100.0);
   }

   public Reveal reveal(int tile) {
      if (!running) return Reveal.NOT_RUNNING;
      if (tile < 0 || tile >= TILES || revealed[tile]) return Reveal.ALREADY_REVEALED;
      revealed[tile] = true;
      if (layout.get(tile)) {
         running = false;
         lost = true;
         return Reveal.MINE;
      }
      gems++;
      if (gems == TILES - mines) running = false; // board cleared: auto cash-out
      return Reveal.GEM;
   }

   /** "Pick a Random Tile!" button. */
   public int randomUnrevealedTile(java.util.Random rng) {
      int left = 0;
      for (boolean r : revealed) if (!r) left++;
      int pick = rng.nextInt(left);
      for (int i = 0; i < TILES; i++) if (!revealed[i] && pick-- == 0) return i;
      return -1;
   }

   /** Ends the round and returns the payout (0 if a mine was hit or no gem revealed yet = bet back). */
   public long cashout() {
      running = false;
      if (lost) return 0;
      return (long) Math.floor(bet * currentMultiplier());
   }

   public double currentMultiplier() {
      return multiplier(mines, gems);
   }

   public double nextMultiplier() {
      return multiplier(mines, gems + 1);
   }

   public boolean running() {
      return running;
   }

   public boolean lost() {
      return lost;
   }

   public boolean isMine(int tile) {
      return layout.get(tile);
   }

   public boolean isRevealed(int tile) {
      return revealed[tile];
   }

   public int gems() {
      return gems;
   }

   public int mines() {
      return mines;
   }

   public long bet() {
      return bet;
   }

   public int nonce() {
      return nonce;
   }

   public String serverSeedHash() {
      return serverSeedHash;
   }

   /** Tile sprite state for RoatIds.Mines.TILE_* once the round is over (reveals the whole board, dimmed). */
   public int endSprite(int tile) {
      if (revealed[tile]) return layout.get(tile) ? 2 : 1;
      return layout.get(tile) ? 4 : 3;
   }
}
