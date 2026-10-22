package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Self-checks of the maps area on the real caches (unit-test style; prints PASS/FAIL per check, exit code 1 on any
 * failure): loc file codec round trips on every square of BASE and USER, key search finds known keys, the tail probe
 * agrees with decrypted groups, terrain conversion, and the ported user squares against the hand-built cacheU.
 * <pre>dev/run.sh map.MapSelfCheck</pre>
 */
public final class MapSelfCheck {
    private int fails;
    private final List<String> lines = new ArrayList<>();

    void check(String what, boolean ok, String detail) {
        String l = (ok ? "PASS " : "FAIL ") + what + (detail == null ? "" : ": " + detail);
        lines.add(l);
        System.out.println(l);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        MapSelfCheck s = new MapSelfCheck();
        s.synthetic();
        try (Ctx ctx = Ctx.open(Options.parse(a, 0), false); Evidence ev = Evidence.load(ctx)) {
            s.real(ctx, ev);
        }
        System.out.println(s.fails == 0 ? "ALL PASS" : s.fails + " FAILED");
        if (s.fails > 0) System.exit(1);
    }

    /** Checks that need no cache. */
    void synthetic() {
        // loc codec: ids that need the extended smart, positions with equal entries, every type/rotation
        List<LocFile.Loc> ls = new ArrayList<>();
        ls.add(new LocFile.Loc(0, 0, 0));
        ls.add(new LocFile.Loc(5, 0x3fff, 22 << 2 | 3));
        ls.add(new LocFile.Loc(5, 0x3fff, 10 << 2));
        ls.add(new LocFile.Loc(40000, 64, 1));
        ls.add(new LocFile.Loc(70000, 1 << 12 | 5, 2));
        ls.add(new LocFile.Loc(131071, 2, 3));
        LocFile f = new LocFile(ls);
        byte[] b = f.encode();
        LocFile g = LocFile.parse(b);
        check("loc codec round trip (synthetic, ids up to 131071)", g.samePlacements(f) && Arrays.equals(g.encode(), b), null);
        LocFile m = f.mapIds(id -> id == 5 ? 70001 : id);
        check("loc id mapping keeps positions", m.size() == f.size() && LocFile.parse(m.encode()).ids()[m.ids().length - 1] == 131071
                && LocFile.contains(LocFile.parse(m.encode()), 70001, 0x3fff, 22 << 2 | 3), null);
        check("empty loc file is one zero byte", Arrays.equals(LocFile.EMPTY.encode(), new byte[]{0}), null);
        boolean threw = false;
        try { LocFile.parse(new byte[]{1, 2}); } catch (IllegalArgumentException e) { threw = true; }
        check("truncated loc file is rejected", threw, null);
        // terrain: a flat 211 square (every tile: height opcode) converts to 241 with one more byte
        byte[] t = new byte[16384 * 3];
        for (int i = 0; i < 16384; i++) { t[i * 3] = 0; t[i * 3 + 1] = 1; t[i * 3 + 2] = 0; }
        check("terrain 211 valid", Terrain.valid211(t), null);
        byte[] t2 = Terrain.to241(t);
        check("terrain 241 = 211 + flags byte", t2.length == t.length + 1 && t2[t.length] == 0 && Terrain.valid241(t2) && !Terrain.valid211(t2), null);
        // xtea: encrypt a block with a known key, decrypt with KeySearch
        int[] key = {0x01234567, 0x89abcdef, 0x13579bdf, 0x2468ace0};
        byte[] plain = {0, 0, 0, 100, 0x1f, (byte) 0x8b, 8, 0};
        byte[] enc = encryptBlock(plain, key);
        check("xtea block decrypt", Arrays.equals(KeySearch.decryptBlock(enc, 0, key), plain), null);
        byte[] fake = new byte[20];
        fake[0] = 2; fake[4] = 5;
        System.arraycopy(enc, 0, fake, 5, 8);
        check("key search header test", KeySearch.headerMatches(fake, key) && !KeySearch.headerMatches(fake, new int[]{1, 2, 3, 4}), null);
        // environment neighbour choice
        List<Integer> no = Env.neighbourOrder(12342);
        check("env neighbour order: ring 1 first, south-west corner first", no.get(0) == (47 << 8 | 53) && no.get(7) == (49 << 8 | 55) && no.get(8) == (46 << 8 | 52), null);
    }

    /**
     * Golden check against the hand-built cacheU, where the old PortMaps/Overlay tools wrote the user's squares: every
     * square the build writes unmerged from the user's copy (custom new, replaced, restored as the user had it) must
     * convert to exactly cacheU's files 0 and 1 (terrain + flags byte, loc bytes).
     */
    void golden(Ctx ctx) throws Exception {
        if (ctx.ref() == null) { check("golden vs cacheU", true, "skipped: no cacheU"); return; }
        AllowList al = AllowList.load();
        NamedMaps u = new NamedMaps(ctx.user(), List.of(ctx.xteas()));
        int same = 0, differ = 0, absent = 0;
        List<Integer> bad = new ArrayList<>();
        for (AllowList.Square s : al.squares.values()) {
            if (!al.writes(s.region()) || s.merge() || al.restoreLocs.containsKey(s.region())) continue;
            int r = s.region();
            byte[] t = u.terrain(r);
            NamedMaps.Locs l = u.locs(r);
            var g = ctx.ref().group(Arch.MAPS, r);
            if (g == null) { absent++; continue; }
            byte[] locs = l.ok() ? l.file().encode() : LocFile.EMPTY.encode();
            boolean eq = Arrays.equals(Terrain.to241(t), g.dataRef(0)) && Arrays.equals(locs, g.dataRef(1));
            if (eq) same++; else { differ++; if (bad.size() < 10) bad.add(r); }
        }
        ctx.user().release(Arch.MAPS);
        check("unmerged written squares equal cacheU's files 0-1 (old PortMaps/Overlay output)", differ == 0,
                same + " equal, " + differ + " differ " + bad + ", " + absent + " not in cacheU");
    }

    static byte[] encryptBlock(byte[] p, int[] k) {
        int v0 = com.gadscape.cacheconv.core.Bytes.i32(p, 0), v1 = com.gadscape.cacheconv.core.Bytes.i32(p, 4), sum = 0, delta = 0x9E3779B9;
        for (int i = 0; i < 32; i++) {
            v0 += (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + k[sum & 3]);
            sum += delta;
            v1 += (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + k[(sum >>> 11) & 3]);
        }
        byte[] o = new byte[8];
        com.gadscape.cacheconv.core.Bytes.w32(o, 0, v0);
        com.gadscape.cacheconv.core.Bytes.w32(o, 4, v1);
        return o;
    }

    void real(Ctx ctx, Evidence ev) throws Exception {
        // every BASE square's loc file round-trips through the codec
        int n = 0, bad = 0, firstBad = -1;
        for (int r : Square241.squares(ctx.base)) {
            byte[] b = Square241.locBytes(ctx.base, r);
            n++;
            if (!Arrays.equals(LocFile.parse(b).encode(), b)) { bad++; if (firstBad < 0) firstBad = r; }
            if (n % 300 == 0) ctx.base.release(Arch.MAPS);
        }
        ctx.base.release(Arch.MAPS);
        check("BASE loc files re-encode byte-identically", bad == 0, n + " squares, " + bad + " differ" + (bad > 0 ? " (first " + firstBad + ")" : ""));

        NamedMaps u = new NamedMaps(ctx.user(), ev.userKeys);
        n = 0; bad = 0; firstBad = -1;
        int unreadable = 0;
        for (int r : u.squares()) {
            NamedMaps.Locs l = u.locs(r);
            if (!l.ok()) { if (l.status().equals("unreadable")) unreadable++; continue; }
            n++;
            if (!Arrays.equals(l.file().encode(), Arrays.copyOf(l.bytes(), l.bytes().length - l.file().padding))) { bad++; if (firstBad < 0) firstBad = r; }
        }
        ctx.user().release(Arch.MAPS);
        check("USER loc files re-encode byte-identically", bad == 0, n + " squares, " + bad + " differ" + (bad > 0 ? " (first " + firstBad + ")" : "") + ", " + unreadable + " unreadable");

        // key search finds a known key: VAN211 squares the key files open
        NamedMaps v = new NamedMaps(ctx.van211(), ev.van211Keys);
        int tried = 0, found = 0, tailSame = 0, tailDiff = 0, tailUnknown = 0;
        for (int r : v.squares()) {
            NamedMaps.Locs l = v.locs(r);
            if (!l.ok() || !l.encrypted()) continue;
            List<int[]> ks = v.keysFor(r);
            byte[] raw = ((JagCache) ctx.van211()).rawContainer(Arch.MAPS, l.group());
            boolean any = false;
            for (int[] k : ks) any |= KeySearch.headerMatches(raw, k);
            tried++;
            if (any) found++;
            switch (TailProbe.compare(raw, l.bytes())) {
                case SAME -> tailSame++;
                case DIFF -> tailDiff++;
                default -> tailUnknown++;
            }
            if (tried >= 400) break;
        }
        ctx.van211().release(Arch.MAPS);
        check("key header test accepts the right key", tried > 0 && found == tried, found + "/" + tried + " decrypted VAN211 loc groups");
        golden(ctx);
        check("tail probe never contradicts a decrypted group", tailDiff == 0, "same " + tailSame + ", unknown " + tailUnknown + ", diff " + tailDiff);
    }
}
