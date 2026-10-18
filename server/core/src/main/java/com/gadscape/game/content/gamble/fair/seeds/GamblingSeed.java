/*
 * Provably Fair Gaming Utilities for RSPS
 * Copyright (c) Roat Pkz - https://roatpkz.com
 *
 * Source & License: https://github.com/roatpkz/rsps-provably-fair
 *
 * This code is open source and may be reused or audited freely under the MIT License.
 */

package com.gadscape.game.content.gamble.fair.seeds;

import com.gadscape.game.content.gamble.fair.ProvablyFair;

public abstract class GamblingSeed {

    public GamblingSeed(String presetSeed) {
        if(presetSeed == null || presetSeed.isEmpty()) {
            presetSeed = ProvablyFair.randomSeed();
        }
        setSeed(presetSeed);
    }

    private String seed;
    public String getSeed() { return seed; }

    public void setSeed(String newSeed) {
        this.seed = newSeed;
    }

}
