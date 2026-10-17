package com.zenyte.game.content.flowerpoker;

import com.gadscape.game.content.gambling.provablyfair.ProvablyFair;
import com.gadscape.tools.logging.GameLogMessage;
import com.zenyte.game.world.World;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.game.world.entity.player.action.misc.MithrilSeedPlanting;
import com.zenyte.game.world.entity.player.container.Container;
import com.zenyte.game.world.object.WorldObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import kotlinx.datetime.Instant;

import java.util.ArrayList;

public class FlowerPokerSession {

    public final Player challenger;
    public final Player opponent;
    public final FlowerPokerAreas arena;
    public final Container stakedItemsChallenger;
    public final Container stakedItemsOpponent;
    public FlowerPokerStage stage;

    public FlowerPokerRank lastRankP;
    public FlowerPokerRank lastRankO;

    public ObjectArrayList<WorldObject> flowerObjects = new ObjectArrayList<>();
    public ArrayList<MithrilSeedPlanting.Flowers> challengerFlowers = new ArrayList<>();
    public ArrayList<MithrilSeedPlanting.Flowers> opponentFlowers = new ArrayList<>();
    public FlowerPokerPlanting planting;

    /** Provably fair: fresh per round (including restarts on black/white), revealed once the round resolves. */
    public String serverSeed;
    public int challengerDraws;
    public int opponentDraws;

    public FlowerPokerSession(Player challenger, Player opponent, FlowerPokerAreas arena, Container stakedItemsChallenger, Container stakedItemsOpponent) {
        this.challenger = challenger;
        this.opponent = opponent;
        this.arena = arena;
        this.stakedItemsChallenger = stakedItemsChallenger;
        this.stakedItemsOpponent = stakedItemsOpponent;
        this.stage = FlowerPokerStage.STARTING;
        startNewRound();
    }

    /** Call whenever a fresh round of planting begins (initial start, and every restart). */
    public void startNewRound() {
        this.serverSeed = ProvablyFair.randomSeed();
        this.challengerDraws = 0;
        this.opponentDraws = 0;
    }

    public boolean plantingFinish() {
        return challengerFlowers.size() >= 5 && opponentFlowers.size() >= 5;
    }

    public void removeFlowers() {
        for (WorldObject flowerObject : flowerObjects) {
            World.removeObject(flowerObject);
        }
        flowerObjects.clear();
    }

    public GameLogMessage.FlowerPokerSession toLogMessage(String winner) {
        return new GameLogMessage.FlowerPokerSession(
                Instant.Companion.now(),
                challenger.getUsername(),
                opponent.getUsername(),
                stakedItemsChallenger.getItems().clone(),
                stakedItemsOpponent.getItems().clone(),
                winner
        );
    }
}
