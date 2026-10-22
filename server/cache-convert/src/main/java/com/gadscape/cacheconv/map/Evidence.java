package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Xteas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Optional evidence for the square classification that is not one of the build inputs (it lives under
 * {@code WORK/maps/evidence/}; the build itself reads the stored classification, resource map/squares.tsv):
 * <ul>
 *   <li>{@code rev225/1943/x/cache} + {@code rev225/1943/keys.json}: the rev-225 cache GadScape's DataMigration ported
 *       (OpenRS2 1943, {@code /caches/runescape/1943/disk.zip} and {@code keys.json}); {@code found_225.json} keys
 *       {@link KeySearch} found.</li>
 *   <li>{@code keys211/keys_*.json}: the public map keys of every 210-212 build on OpenRS2 (1167 ... 1316), newest
 *       first; {@code keys211/found_van211.json}: keys {@link KeySearch} found in {@code valid_keys_all.json}
 *       (OpenRS2 {@code /keys/valid.json}).</li>
 *   <li>{@code found_user.json}: keys found for USER loc groups the user's xteas.json does not open.</li>
 * </ul>
 * Override with {@code --maps.rev225=DIR --maps.keys225=FILE --maps.van211Keys=F1,F2,...}.
 */
public final class Evidence implements AutoCloseable {
    public final JagCache rev225;
    public final List<Xteas> keys225;
    public final List<Xteas> van211Keys, userKeys;
    public final List<String> describe = new ArrayList<>();

    private Evidence(JagCache rev225, List<Xteas> keys225, List<Xteas> vanKeys, List<Xteas> userKeys) {
        this.rev225 = rev225; this.keys225 = keys225; this.van211Keys = vanKeys; this.userKeys = userKeys;
    }

    public static Path defaultDir(Ctx ctx) throws IOException { return ctx.work("maps").resolve("evidence"); }

    public static Evidence load(Ctx ctx) throws IOException {
        Path ev = defaultDir(ctx);
        Path c225 = Path.of(ctx.opt.get("maps.rev225", ev.resolve("rev225/1943/x/cache").toString()));
        Path k225 = Path.of(ctx.opt.get("maps.keys225", ev.resolve("rev225/1943/keys.json").toString()));
        JagCache r = Files.isRegularFile(c225.resolve("main_file_cache.dat2")) ? JagCache.open("REV225", c225) : null;
        List<Xteas> k = new ArrayList<>();
        if (Files.isRegularFile(k225)) {
            k.add(Xteas.load(k225));
            Path f = k225.resolveSibling("found_225.json");
            if (Files.isRegularFile(f)) k.add(Xteas.load(f));
        }
        List<Path> vkp = new ArrayList<>();
        String opt = ctx.opt.get("maps.van211Keys", null);
        if (opt != null) for (String s : opt.split(",")) vkp.add(Path.of(s.trim()));
        else {
            Path d = ev.resolve("keys211");
            if (Files.isDirectory(d)) {
                try (Stream<Path> s = Files.list(d)) {
                    // keys_<openrs2 id>.json newest first, then found_*.json
                    s.filter(p -> p.getFileName().toString().matches("keys_\\d+\\.json"))
                            .sorted(Comparator.comparingInt((Path p) -> Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""))).reversed())
                            .forEach(vkp::add);
                }
                if (Files.isRegularFile(d.resolve("found_van211.json"))) vkp.add(d.resolve("found_van211.json"));
            }
        }
        List<Xteas> vk = new ArrayList<>();
        for (Path p : vkp) vk.add(Xteas.load(p));
        List<Xteas> uk = new ArrayList<>();
        uk.add(ctx.xteas());
        if (Files.isRegularFile(ev.resolve("found_user.json"))) uk.add(Xteas.load(ev.resolve("found_user.json")));
        uk.addAll(k);        // groups DataMigration copied from 225 may still be encrypted with 225's key
        uk.addAll(vk);       // ... or vanilla groups with 211's
        vk.add(ctx.xteas()); // the user's own keys open a few vanilla groups too
        Evidence e = new Evidence(r, k, vk, uk);
        e.describe.add("rev225: " + (r == null ? "none" : c225 + (k.isEmpty() ? " (no keys!)" : " + " + k225 + " (" + k.get(0).size() + " keys)")));
        e.describe.add("van211 keys: " + (vkp.isEmpty() ? "none" : vkp.size() + " files " + vkp.get(0).getParent()));
        return e;
    }

    @Override public void close() { if (rev225 != null) rev225.close(); }
}
