package com.gadscape.game.content.gamble;

/**
 * Roat Pkz autoplay settings (dice + mines side panels, "Auto" tab):
 * Number of Bets (-10 / +10 / INF), On Win: Reset | Increase by %, On Loss: Reset | Increase by %,
 * Stop on Profit, Stop on Loss. Mines additionally has On Success: Stop | Cashout (see MinesGame).
 */
public final class AutoBet {
   public long baseBet;
   /** -1 = infinite (the INF button). */
   public int numberOfBets = 10;
   public boolean increaseOnWin;
   public double onWinPercent;
   public boolean increaseOnLoss;
   public double onLossPercent;
   /** 0 = disabled. */
   public long stopOnProfit;
   /** 0 = disabled. Positive number = stop once the session is down this much. */
   public long stopOnLoss;

   private long currentBet;
   private long profit;
   private int played;
   private boolean running;

   public void start(long baseBet) {
      this.baseBet = baseBet;
      currentBet = baseBet;
      profit = 0;
      played = 0;
      running = true;
   }

   public void stop() {
      running = false;
   }

   public boolean running() {
      return running;
   }

   public long currentBet() {
      return currentBet;
   }

   public long profit() {
      return profit;
   }

   public int played() {
      return played;
   }

   /** Feed the result of one bet; returns whether autoplay should continue. */
   public boolean record(boolean won, long betProfit) {
      played++;
      profit += betProfit;
      if (won) {
         currentBet = increaseOnWin ? Math.max(1, Math.round(currentBet * (1 + onWinPercent / 100.0))) : baseBet;
      } else {
         currentBet = increaseOnLoss ? Math.max(1, Math.round(currentBet * (1 + onLossPercent / 100.0))) : baseBet;
      }
      if (numberOfBets >= 0 && played >= numberOfBets) running = false;
      if (stopOnProfit > 0 && profit >= stopOnProfit) running = false;
      if (stopOnLoss > 0 && -profit >= stopOnLoss) running = false;
      return running;
   }
}
