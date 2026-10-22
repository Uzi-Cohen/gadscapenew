package com.gadscape.game.rev241;

import com.gadscape.game.rev241.IdMapTable.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * GadScape's own 211-era ids, resolved to where the build-241 cache holds that content.
 * <p>
 * The 241 cache (cacheV) is openrs2 2746 plus GadScape's content, built by gadconv ({@code server/cache-convert}).
 * Where a GadScape id collided with a different 2746 entry, gadconv moved GadScape's entry to a reserved range and
 * recorded the move in {@code idmap.json} (for example seq 15110 -> 40198, obj 34723 -> 60002, varp 3811 -> 9011,
 * loc 35000 -> 64501, interface 866 -> 5200). On cacheV the old number is a 2746 id that means something else.
 * <p>
 * Server code that means GadScape's content keeps its 211 number and goes through this class:
 * {@code new Animation(Gad211.seq(15110))}, {@code sendVar(Gad211.varp(3811), v)}. Code that means the 2746 entry at
 * that number (Raids 4, stock 241 content written against 2746) uses the number directly. An id the map does not
 * move resolves to itself, so wrapping an id that never moved is harmless.
 * <p>
 * The table is {@code idmap.json} of the cache the server runs on, bundled as the resource
 * {@value #RESOURCE} (a byte-for-byte copy of gadconv's {@code cacheV.idmap.json}; replace that file when the cache
 * is rebuilt). System property {@value #PROPERTY}: unset or {@code bundled} = the resource, a path = that file,
 * {@code none} = identity (for running on a cache that holds GadScape's content at its 211 ids: the 211 cache or the
 * older cacheU). The table loads on first use and never changes afterwards.
 */
public final class Gad211 {

    private static final Logger log = LoggerFactory.getLogger(Gad211.class);

    public static final String PROPERTY = "gadscape.idmap";
    public static final String RESOURCE = "/gadscape/rev241/idmap.json";

    private static volatile IdMapTable table;

    private Gad211() {
    }

    public static int seq(final int id) {
        return table().map(Kind.SEQ, id);
    }

    public static int spot(final int id) {
        return table().map(Kind.SPOT, id);
    }

    public static int obj(final int id) {
        return table().map(Kind.OBJ, id);
    }

    public static int npc(final int id) {
        return table().map(Kind.NPC, id);
    }

    public static int loc(final int id) {
        return table().map(Kind.LOC, id);
    }

    public static int varp(final int id) {
        return table().map(Kind.VARP, id);
    }

    public static int varbit(final int id) {
        return table().map(Kind.VARBIT, id);
    }

    public static int varc(final int id) {
        return table().map(Kind.VARC, id);
    }

    public static int script(final int id) {
        return table().map(Kind.SCRIPT, id);
    }

    public static int iface(final int id) {
        return table().map(Kind.INTERFACE, id);
    }

    public static int enumId(final int id) {
        return table().map(Kind.ENUM, id);
    }

    public static int struct(final int id) {
        return table().map(Kind.STRUCT, id);
    }

    public static int param(final int id) {
        return table().map(Kind.PARAM, id);
    }

    public static int sprite(final int id) {
        return table().map(Kind.SPRITE, id);
    }

    public static int model(final int id) {
        return table().map(Kind.MODEL, id);
    }

    public static int dbrow(final int id) {
        return table().map(Kind.DBROW, id);
    }

    /**
     * A GadScape obj that has no 211 id: the id gadconv added it at ({@code idmap.json} {@code _new.obj[key]}), or
     * {@code fallback} when the active table does not list it (identity table, or a cache built before it existed).
     */
    public static int newObj(final String key, final int fallback) {
        final int id = table().added(Kind.OBJ, key);
        return id == -1 ? fallback : id;
    }

    /** Maps every element of {@code ids} (a new array). */
    public static int[] seqs(final int... ids) {
        return mapAll(Kind.SEQ, ids);
    }

    public static int[] objs(final int... ids) {
        return mapAll(Kind.OBJ, ids);
    }

    public static int[] locs(final int... ids) {
        return mapAll(Kind.LOC, ids);
    }

    public static int[] mapAll(final Kind kind, final int... ids) {
        final IdMapTable t = table();
        final int[] out = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            out[i] = t.map(kind, ids[i]);
        }
        return out;
    }

    public static int map(final Kind kind, final int id) {
        return table().map(kind, id);
    }

    /** The active table (loaded on first use). */
    public static IdMapTable table() {
        IdMapTable t = table;
        if (t == null) {
            synchronized (Gad211.class) {
                t = table;
                if (t == null) {
                    t = load();
                    table = t;
                    log.info("GadScape 211 id map: {}", t);
                }
            }
        }
        return t;
    }

    /** Replaces the active table: tests and tools only, before anything resolved an id. */
    public static synchronized void install(final IdMapTable replacement) {
        table = replacement;
    }

    /**
     * Boot check, run when the server opens its cache: a 211 cache with an active table, or an {@code idmap.json}
     * next to the cache that differs from the active table, means GadScape ids resolve wrong. Only logs.
     */
    public static void checkAgainstCache(final String cacheDirectory, final int revision) {
        try {
            final IdMapTable active = table();
            if (revision < 241 && !active.isIdentity()) {
                log.error("The server runs a revision-{} cache but resolves GadScape ids through {}: on a cache that"
                        + " keeps GadScape's content at its 211 ids start with -D{}=none.", revision, active, PROPERTY);
            }
            if (cacheDirectory == null) {
                return;
            }
            final Path beside = Path.of(cacheDirectory).resolve("idmap.json");
            if (!Files.isRegularFile(beside)) {
                return;
            }
            final IdMapTable cacheTable;
            try (Reader reader = Files.newBufferedReader(beside, StandardCharsets.UTF_8)) {
                cacheTable = IdMapTable.parse(reader, beside.toString());
            }
            int differences = 0;
            final StringBuilder first = new StringBuilder();
            for (final Kind kind : Kind.values()) {
                final var mine = active.entries(kind);
                final var theirs = cacheTable.entries(kind);
                if (mine.equals(theirs)) {
                    continue;
                }
                for (final var e : theirs.int2IntEntrySet()) {
                    if (!mine.containsKey(e.getIntKey()) || mine.get(e.getIntKey()) != e.getIntValue()) {
                        if (differences++ < 8) {
                            first.append(' ').append(kind.key).append(' ').append(e.getIntKey()).append("->").append(e.getIntValue());
                        }
                    }
                }
                for (final var e : mine.int2IntEntrySet()) {
                    if (!theirs.containsKey(e.getIntKey())) {
                        if (differences++ < 8) {
                            first.append(' ').append(kind.key).append(' ').append(e.getIntKey()).append(" (not moved by the cache)");
                        }
                    }
                }
            }
            if (differences > 0) {
                log.error("{} differs from the server's id map {} in {} entr{} (e.g.{}): copy the cache's idmap.json to"
                        + " cache/src/main/resources{} and rebuild, or start with -D{}={}.", beside, active.source(),
                        differences, differences == 1 ? "y" : "ies", first, RESOURCE, PROPERTY, beside);
            }
        } catch (final Exception e) {
            log.error("Could not check the GadScape id map against the cache at " + cacheDirectory, e);
        }
    }

    private static IdMapTable load() {
        final String spec = System.getProperty(PROPERTY, "bundled").trim();
        final String lower = spec.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("off") || lower.equals("identity") || lower.equals("false")) {
            return IdMapTable.IDENTITY;
        }
        if (lower.isEmpty() || lower.equals("bundled") || lower.equals("true")) {
            final InputStream in = Gad211.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                // without the table every GadScape id that moved would silently mean 2746 content: refuse to run
                throw new IllegalStateException("Resource " + RESOURCE + " is missing from the classpath; set -D"
                        + PROPERTY + "=<idmap.json> or -D" + PROPERTY + "=none");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return IdMapTable.parse(reader, "classpath:" + RESOURCE);
            } catch (final Exception e) {
                throw new IllegalStateException("Cannot read " + RESOURCE, e);
            }
        }
        final Path path = Path.of(spec);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return IdMapTable.parse(reader, path.toString());
        } catch (final Exception e) {
            throw new IllegalStateException("Cannot read the id map " + path + " (-D" + PROPERTY + ")", e);
        }
    }
}
