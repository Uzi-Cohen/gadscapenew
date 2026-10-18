package com.gadscape.game.content.gamble.blackjack;

import com.gadscape.game.content.gamble.PlayerSeeds;
import java.util.ArrayList;
import java.util.List;
import com.gadscape.game.content.gamble.fair.ProvablyFairBlackjack;

/**
 * Roat Pkz Blackjack, rules from their table text + wiki:
 *  - 8 decks, a fresh shoe shuffled every game with their provably-fair shuffle (nonce = hand #)
 *  - Blackjack pays 3:2, insurance pays 2:1 (costs 50% of the bet), push returns the bet
 *  - Dealer draws to 16 and stands on ALL 17s (incl. soft 17); dealer peeks for blackjack
 *  - Double on any first two cards, double after split, split any two cards of equal value (one split, the table
 *    UI has two player hand slots), split aces receive exactly one card each
 *
 * Card ids are exactly the ids on Roat's verifier (blackjack.html) and the client's card sprites:
 *   1-13 spades, 14-26 clubs, 27-39 diamonds, 40-52 hearts; rank = (id-1)%13+1 (1=A, 11=J, 12=Q, 13=K); sprite 0 = back.
 *
 * Money flow: caller removes bet() before start(); before double/split/insurance remove costOfX(); after the game
 * is FINISHED add totalPayout().
 */
public final class BlackjackGame {
   public static final int DECKS = 8;
   public static final int MAX_HANDS = 2;

   public enum State { INSURANCE_OFFERED, PLAYER_TURN, FINISHED }

   public enum Outcome { NONE, WIN, BLACKJACK, PUSH, LOSE, BUST }

   public static final class Hand {
      public final List<Integer> cards = new ArrayList<>();
      public long bet;
      public boolean doubled, fromSplit, done;
      public Outcome outcome = Outcome.NONE;
      public long payout;

      public int total() {
         return BlackjackGame.total(cards);
      }

      public boolean soft() {
         return BlackjackGame.isSoft(cards);
      }

      boolean isNatural() {
         return !fromSplit && cards.size() == 2 && total() == 21;
      }
   }

   private final List<Integer> shoe;
   private int next;
   private final int nonce;
   private final String serverSeedHash;
   private final long baseBet;
   private final List<Hand> hands = new ArrayList<>();
   private final List<Integer> dealer = new ArrayList<>();
   private int active;
   private State state;
   private long insuranceBet;
   private long insurancePayout;

   private BlackjackGame(List<Integer> shoe, int nonce, String serverSeedHash, long bet) {
      this.shoe = shoe;
      this.nonce = nonce;
      this.serverSeedHash = serverSeedHash;
      this.baseBet = bet;
   }

   public static List<Integer> buildShoe() {
      List<Integer> deck = new ArrayList<>(DECKS * 52);
      for (int d = 0; d < DECKS; d++) {
         for (int rank = 1; rank <= 13; rank++) {
            deck.add(rank);       // spades
            deck.add(rank + 39);  // hearts
            deck.add(rank + 13);  // clubs
            deck.add(rank + 26);  // diamonds
         }
      }
      return deck;
   }

   public static List<Integer> shuffledShoe(String clientSeed, String serverSeed, int nonce) {
      List<Integer> deck = buildShoe();
      ProvablyFairBlackjack.shuffle(deck, clientSeed, serverSeed, nonce);
      return deck;
   }

   public static int rank(int card) {
      return (card - 1) % 13 + 1;
   }

   public static int value(int card) {
      int r = rank(card);
      return r == 1 ? 11 : Math.min(10, r);
   }

   public static String name(int card) {
      String[] r = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
      String suit = card <= 13 ? "♠" : card <= 26 ? "♣" : card <= 39 ? "♦" : "♥";
      return r[rank(card) - 1] + suit;
   }

   public static int total(List<Integer> cards) {
      int t = 0, aces = 0;
      for (int c : cards) {
         t += value(c);
         if (rank(c) == 1) aces++;
      }
      while (t > 21 && aces-- > 0) t -= 10;
      return t;
   }

   public static boolean isSoft(List<Integer> cards) {
      int hard = 0;
      boolean ace = false;
      for (int c : cards) {
         hard += rank(c) == 1 ? 1 : value(c);
         ace |= rank(c) == 1;
      }
      return ace && hard + 10 <= 21;
   }

   /** Deal a new game. Caller has already removed bet from the player. */
   public static BlackjackGame start(PlayerSeeds seeds, long bet) {
      int nonce = seeds.nextNonce();
      BlackjackGame g = new BlackjackGame(shuffledShoe(seeds.clientSeed(), seeds.serverSeed(), nonce), nonce, seeds.serverSeedHash(), bet);
      Hand h = new Hand();
      h.bet = bet;
      g.hands.add(h);
      h.cards.add(g.draw());
      g.dealer.add(g.draw());
      h.cards.add(g.draw());
      g.dealer.add(g.draw());
      if (rank(g.dealer.get(0)) == 1) {
         g.state = State.INSURANCE_OFFERED;
      } else {
         g.afterInsurance();
      }
      return g;
   }

   private int draw() {
      return shoe.get(next++);
   }

   public long costOfInsurance() {
      return baseBet / 2;
   }

   /** Answer the insurance question (only when dealer shows an ace). Caller removes costOfInsurance() if taking it. */
   public void insurance(boolean take) {
      if (state != State.INSURANCE_OFFERED) return;
      if (take) insuranceBet = costOfInsurance();
      afterInsurance();
   }

   private void afterInsurance() {
      boolean dealerBj = total(dealer) == 21;
      if (dealerBj) {
         insurancePayout = insuranceBet * 3; // 2:1 plus the stake
         settle();
         return;
      }
      if (hands.get(0).isNatural()) {
         settle();
         return;
      }
      state = State.PLAYER_TURN;
   }

   public Hand activeHand() {
      return state == State.PLAYER_TURN ? hands.get(active) : null;
   }

   public boolean canHit() {
      Hand h = activeHand();
      return h != null && !(h.fromSplit && rank(h.cards.get(0)) == 1);
   }

   public boolean canDouble() {
      Hand h = activeHand();
      return h != null && h.cards.size() == 2 && !(h.fromSplit && rank(h.cards.get(0)) == 1);
   }

   public boolean canSplit() {
      Hand h = activeHand();
      return h != null && hands.size() < MAX_HANDS && h.cards.size() == 2 && value(h.cards.get(0)) == value(h.cards.get(1));
   }

   public long costToDouble() {
      Hand h = activeHand();
      return h == null ? 0 : h.bet;
   }

   public long costToSplit() {
      return costToDouble();
   }

   public void hit() {
      if (!canHit()) return;
      Hand h = activeHand();
      h.cards.add(draw());
      if (h.total() >= 21) finishHand();
   }

   public void stand() {
      if (activeHand() != null) finishHand();
   }

   /** Caller removes costToDouble() first. */
   public void doubleDown() {
      if (!canDouble()) return;
      Hand h = activeHand();
      h.bet *= 2;
      h.doubled = true;
      h.cards.add(draw());
      finishHand();
   }

   /** Caller removes costToSplit() first. */
   public void split() {
      if (!canSplit()) return;
      Hand h = activeHand();
      Hand second = new Hand();
      second.bet = h.bet;
      second.fromSplit = h.fromSplit = true;
      second.cards.add(h.cards.remove(1));
      hands.add(active + 1, second);
      h.cards.add(draw());
      second.cards.add(draw());
      boolean aces = rank(h.cards.get(0)) == 1;
      if (aces) {
         h.done = second.done = true; // split aces: one card each, no further action
         active = hands.size() - 1;
         finishHand();
      } else if (h.total() == 21) {
         finishHand();
      }
   }

   private void finishHand() {
      hands.get(active).done = true;
      while (active < hands.size() && hands.get(active).done) active++;
      if (active >= hands.size()) {
         active = hands.size() - 1;
         dealerPlay();
      } else if (hands.get(active).total() == 21) {
         finishHand();
      }
   }

   private void dealerPlay() {
      boolean anyLive = hands.stream().anyMatch(h -> h.total() <= 21);
      if (anyLive) {
         while (total(dealer) < 17) dealer.add(draw()); // stands on all 17s
      }
      settle();
   }

   private void settle() {
      state = State.FINISHED;
      int d = total(dealer);
      boolean dealerBj = dealer.size() == 2 && d == 21;
      for (Hand h : hands) {
         int t = h.total();
         if (h.isNatural() && !dealerBj) {
            h.outcome = Outcome.BLACKJACK;
            h.payout = h.bet + h.bet * 3 / 2;
         } else if (t > 21) {
            h.outcome = Outcome.BUST;
         } else if (dealerBj) {
            h.outcome = h.isNatural() ? Outcome.PUSH : Outcome.LOSE;
            h.payout = h.isNatural() ? h.bet : 0;
         } else if (d > 21 || t > d) {
            h.outcome = Outcome.WIN;
            h.payout = h.bet * 2;
         } else if (t == d) {
            h.outcome = Outcome.PUSH;
            h.payout = h.bet;
         } else {
            h.outcome = Outcome.LOSE;
         }
      }
   }

   public long totalPayout() {
      long p = insurancePayout;
      for (Hand h : hands) p += h.payout;
      return p;
   }

   public long totalWagered() {
      long w = insuranceBet;
      for (Hand h : hands) w += h.bet;
      return w;
   }

   public State state() {
      return state;
   }

   public List<Hand> hands() {
      return hands;
   }

   public List<Integer> dealerCards() {
      return dealer;
   }

   /** Dealer cards the player may see: hole card hidden (sprite 0) until the game is finished. */
   public int dealerVisibleCard(int i) {
      return i == 1 && state != State.FINISHED ? 0 : dealer.get(i);
   }

   public int activeIndex() {
      return active;
   }

   public int nonce() {
      return nonce;
   }

   public String serverSeedHash() {
      return serverSeedHash;
   }
}
