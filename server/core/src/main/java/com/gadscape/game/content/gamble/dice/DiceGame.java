package com.gadscape.game.content.gamble.dice;

import com.gadscape.game.content.gamble.PlayerSeeds;
import com.gadscape.game.content.gamble.fair.ProvablyFairDice;

/**
 * Roat Pkz Dice: pick a "roll over" target, a number 0.00-100.00 is rolled with their provably-fair RNG
 * (SHA-256(clientSeed:serverSeed:nonce) -> first 4 bytes mod 10001 -> /100). Win if roll >= target.
 * Multiplier = 99 / (100 - target)  (99% RTP, the figure in their in-game rules panel and wiki).
 */
public final class DiceGame {
   public static final double RTP = 0.99;
   public static final double MIN_TARGET = 2.0, MAX_TARGET = 98.0;

   private DiceGame() {
   }

   public static double multiplier(double target) {
      return RTP * 100.0 / (100.0 - target);
   }

   /** Win chance in percent as Roat displays it (100 - target). */
   public static double winChance(double target) {
      return 100.0 - target;
   }

   public static double clampTarget(double target) {
      return Math.max(MIN_TARGET, Math.min(MAX_TARGET, target));
   }

   public static final class Result {
      public final int nonce;
      public final double roll, target, multiplier;
      public final boolean win;
      public final long bet, payout;

      Result(int nonce, double roll, double target, long bet) {
         this.nonce = nonce;
         this.roll = roll;
         this.target = target;
         this.multiplier = multiplier(target);
         this.win = roll >= target;
         this.bet = bet;
         this.payout = win ? (long) Math.floor(bet * multiplier) : 0;
      }

      public long profit() {
         return payout - bet;
      }
   }

   /** Pure roll. The caller removes the bet first and adds result.payout afterwards. */
   public static Result roll(PlayerSeeds seeds, long bet, double target) {
      target = clampTarget(target);
      int nonce = seeds.nextNonce();
      double roll = ProvablyFairDice.rollPercentage(seeds.clientSeed(), seeds.serverSeed(), nonce);
      return new Result(nonce, roll, target, bet);
   }
}
