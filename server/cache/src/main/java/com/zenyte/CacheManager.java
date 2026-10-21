package com.zenyte;

import mgi.tools.jagcached.CacheFormat;
import mgi.tools.jagcached.cache.Cache;
import mgi.types.Definitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

public final class CacheManager {

    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);

    public static boolean DEVELOPMENT_MODE = false;
    public static final String DEFAULT_CACHE_PATH = "cache/data/cache/";

    /**
     * System property naming a cache directory to load instead of GadScape's generated cache, for example the
     * vanilla build-241 cache: {@code -Dgadscape.cache.dir=/path/to/cache241}. The directory is opened read-only
     * and no cache packing or generation step runs (see {@link #isExternalCache()}).
     */
    public static final String CACHE_DIR_PROPERTY = "gadscape.cache.dir";

    /**
     * The directory from {@value #CACHE_DIR_PROPERTY}, or null when it is not set.
     */
    public static String externalCacheDir() {
        final String dir = System.getProperty(CACHE_DIR_PROPERTY);
        return dir == null || dir.isBlank() ? null : dir.trim();
    }

    /**
     * True when the server runs on an external cache ({@value #CACHE_DIR_PROPERTY} is set): GadScape's cache
     * build (TypeParser and the custom packers) and the XTEA key file are skipped in that mode.
     */
    public static boolean isExternalCache() {
        return externalCacheDir() != null;
    }

    /**
     * {@value #CACHE_DIR_PROPERTY} if set, else {@code defaultPath}.
     */
    public static String resolveCachePath(final String defaultPath) {
        final String external = externalCacheDir();
        return external != null ? external : defaultPath;
    }

    private static volatile int[] crc;
    private static volatile Cache cache;

    public static Cache getCache() {
        return cache;
    }

    public static void setCache(Cache cache) {
        CacheManager.cache = cache;
    }

    public static Cache loadDetached() {
        return loadDetached(false);
    }

    public static Cache loadDetached(boolean readOnly) {
        return loadDetached(resolveCachePath(DEFAULT_CACHE_PATH), readOnly);
    }

    public static Cache loadDetached(String path) {
        return loadDetached(path, false);
    }

    public static Cache loadDetached(String path, boolean readOnly) {
        Cache cache = loadCacheFiles(path, readOnly);
        loadDefinitions(ForkJoinPool.commonPool(), false);
        return cache;
    }

    /**
     * Opens the cache at {@code path} and makes it the server's cache. When {@value #CACHE_DIR_PROPERTY} is set,
     * that directory is opened instead (GameLoader passes GadScape's default "./cache/data/cache/").
     */
    public static Cache loadCacheFiles(String path, boolean readOnly) {
        path = resolveCachePath(path);
        if (!new java.io.File(path, "main_file_cache.dat2").isFile()) {
            throw new IllegalStateException("No cache at " + new java.io.File(path).getAbsolutePath()
                    + " (main_file_cache.dat2 missing)" + (isExternalCache() ? "; check -D" + CACHE_DIR_PROPERTY : ""));
        }
        Cache cache = Cache.openCache(path, readOnly);
        return loadCache(cache);
    }

    /**
     * Makes {@code cache} the server's cache and selects the definition format (211 or 241) it uses.
     */
    public static Cache loadCache(Cache cache) {
        crc = cache.getCrcs();
        final int revision = CacheFormat.detect(cache);
        CacheFormat.setRevision(revision);
        log.info("Using cache {} (format revision {}, {} archives{})", cache.getPath(), revision,
                cache.getArchiveCount(), isExternalCache() ? ", external: no cache generation" : "");
        return CacheManager.cache = cache;
    }

    public static void loadAllDefinitions() {
        loadDefinitions(ForkJoinPool.commonPool(), true);
    }

    public static void loadDefinitions() {
        loadDefinitions(ForkJoinPool.commonPool(), false);
    }

    public static void loadDefinitions(ExecutorService pool) {
        loadDefinitions(pool, false);
    }

    public static void loadDefinitions(ExecutorService pool, boolean allDefs) {
        List<Callable<Void>> callables = new ArrayList<>(Definitions.highPriorityDefinitions.length);
        for (final Class<?> clazz : Definitions.highPriorityDefinitions) {
            callables.add(() -> {
                Definitions.load(clazz).run();
                return null;
            });
        }
        if(allDefs) {
            for (final Class<?> clazz : Definitions.lowPriorityDefinitions) {
                callables.add(() -> {
                    Definitions.load(clazz).run();
                    return null;
                });
            }
        }
        try {
            pool.invokeAll(callables);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static int getCRC(int index) {
        return crc[index];
    }

    private CacheManager() {
        throw new UnsupportedOperationException();
    }

}
