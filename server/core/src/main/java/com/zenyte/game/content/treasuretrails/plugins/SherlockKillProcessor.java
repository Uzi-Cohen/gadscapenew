package com.zenyte.game.content.treasuretrails.plugins;

import com.zenyte.game.content.treasuretrails.clues.SherlockTask;
import com.zenyte.game.world.entity.npc.NPC;
import com.zenyte.game.world.entity.npc.drop.matrix.DropProcessor;
import com.zenyte.game.world.entity.player.Player;
import com.zenyte.utils.IntArray;
import org.apache.commons.lang3.ArrayUtils;

/**
 * Progresses Sherlock's kill skill challenges that have no other hook (kills are reported through the drop processors).
 */
public class SherlockKillProcessor extends DropProcessor {
    private static final int[] LIZARDMAN_SHAMANS = IntArray.of(6766, 6767, 7573, 7574, 7744, 7745, 8565);
    private static final int[] FIYR_SHADES = IntArray.of(1286);

    @Override
    public void attach() {
    }

    @Override
    public void onDeath(final NPC npc, final Player killer) {
        if (killer == null) {
            return;
        }
        final int id = npc.getId();
        if (ArrayUtils.contains(LIZARDMAN_SHAMANS, id)) {
            SherlockTask.KILL_LIZARDMAN_SHAMAN.progress(killer);
        } else if (ArrayUtils.contains(FIYR_SHADES, id)) {
            SherlockTask.KILL_FIYR_SHADE_INSIDE_CATACOMBS.progress(killer);
        }
    }

    @Override
    public int[] ids() {
        return ArrayUtils.addAll(LIZARDMAN_SHAMANS, FIYR_SHADES);
    }
}
