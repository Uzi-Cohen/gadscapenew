package com.zenyte.game;

import com.gadscape.game.util.HuffmanManager;
import com.zenyte.CacheManager;
import com.zenyte.game.util.ZenyteHuffman;
import mgi.tools.jagcached.cache.Cache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * @author Tommeh | 28 jul. 2018 | 13:03:30
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>}
 */
public final class GameLoader {

    public static void load() {
        load(ForkJoinPool.commonPool());
    }

    public static void load(ExecutorService pool) {
        Cache cache = CacheManager.loadCacheFiles("./cache/data/cache/", true);
        HuffmanManager.load(cache);
        ZenyteHuffman.load(cache);
        // rev 241: JS5 and the client packet decoders are served by rsprot (com.gadscape.network.rsprot).
        CacheManager.loadDefinitions(pool, false);
    }
}
