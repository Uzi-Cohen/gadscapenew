package com.gadscape.cacheconv.map;

import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Options;
import com.gadscape.cacheconv.map.Classifier.Action;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTest {
    /** A flat 211 terrain: every tile "height 0, end" (opcode 1 + 1 byte). */
    static byte[] flat(int height) {
        byte[] t = new byte[16384 * 3];
        for (int i = 0; i < 16384; i++) { t[i * 3] = 0; t[i * 3 + 1] = 1; t[i * 3 + 2] = (byte) height; }
        return t;
    }

    /** Same terrain with tile i set to an overlay (opcode 2: overlay id u16 follows) then height. */
    static byte[] withOverlay(byte[] flat211, int tile, int overlay) {
        int[] o = Terrain.offsets(flat211);
        byte[] ins = {0, 2, (byte) (overlay >> 8), (byte) overlay};
        byte[] r = new byte[flat211.length + 4];
        System.arraycopy(flat211, 0, r, 0, o[tile]);
        System.arraycopy(ins, 0, r, o[tile], 4);
        System.arraycopy(flat211, o[tile], r, o[tile] + 4, flat211.length - o[tile]);
        return r;
    }

    @Test void locCodecRoundTripAndCanonicalOrder() {
        List<LocFile.Loc> ls = List.of(new LocFile.Loc(70000, 5, 10 << 2), new LocFile.Loc(1, 4095, 22 << 2 | 3), new LocFile.Loc(1, 0, 0),
                new LocFile.Loc(32767, 7, 1), new LocFile.Loc(32768, 7, 2), new LocFile.Loc(65534, 0x3fff, 3));
        LocFile f = new LocFile(ls);
        byte[] b = f.encode();
        LocFile g = LocFile.parse(b);
        assertTrue(g.samePlacements(f));
        assertArrayEquals(b, g.encode(), "canonical encoding is stable");
        assertEquals(1, g.locs.get(0).id());
        assertEquals(0, g.locs.get(0).pos(), "positions ascending within an id");
        assertArrayEquals(new int[]{1, 32767, 32768, 65534, 70000}, g.ids());
    }

    @Test void locParserRejectsGarbageButAcceptsZeroPadding() {
        byte[] b = new LocFile(List.of(new LocFile.Loc(42, 100, 4))).encode();
        byte[] padded = Arrays.copyOf(b, b.length + 50);
        LocFile p = LocFile.parse(padded);
        assertEquals(50, p.padding);
        assertArrayEquals(b, p.encode());
        byte[] junk = Arrays.copyOf(b, b.length + 1);
        junk[b.length] = 7;
        assertThrows(IllegalArgumentException.class, () -> LocFile.parse(junk));
        assertNull(LocFile.tryParse(new byte[]{(byte) 0x80}));
        assertArrayEquals(new byte[]{0}, LocFile.EMPTY.encode());
    }

    @Test void locMultisetHelpers() {
        LocFile a = new LocFile(List.of(new LocFile.Loc(1, 1, 0), new LocFile.Loc(1, 1, 0), new LocFile.Loc(2, 3, 4)));
        LocFile b = new LocFile(List.of(new LocFile.Loc(1, 1, 0), new LocFile.Loc(2, 3, 4)));
        assertEquals(1, a.countNotIn(b));
        assertEquals(0, b.countNotIn(a));
        assertFalse(a.samePlacements(b));
        assertTrue(LocFile.contains(a, 2, 3, 4));
        LocFile m = a.mapIds(id -> id == 2 ? 64500 : id);
        assertTrue(LocFile.contains(m, 64500, 3, 4));
    }

    @Test void terrainConversionAndTileDiff() {
        byte[] t = flat(0);
        assertTrue(Terrain.valid211(t));
        byte[] t241 = Terrain.to241(t);
        assertTrue(Terrain.valid241(t241));
        assertFalse(Terrain.valid241(t));
        byte[] u = withOverlay(t, 100, 7);
        assertTrue(Terrain.valid211(u));
        assertEquals(1, Terrain.tilesDiffering(t, u));
        assertEquals(0, Terrain.tilesDiffering(t, t241), "the 241 flags byte is not a tile");
    }

    @Test void mergeTakesUserTilesAndBaseElsewhere() {
        byte[] start = flat(0), user = withOverlay(start, 10, 5);
        byte[] base241 = Terrain.to241(withOverlay(start, 20, 9));
        BitSet tm = new BitSet(16384), lm = new BitSet(16384);
        tm.set(10);
        lm.set(30);
        LocFile userLocs = new LocFile(List.of(new LocFile.Loc(500, 30, 4), new LocFile.Loc(600, 40, 0)));
        LocFile baseLocs = new LocFile(List.of(new LocFile.Loc(700, 30, 4), new LocFile.Loc(800, 40, 0), new LocFile.Loc(900, 10, 0)));
        SquareMerge.Result r = SquareMerge.merge(user, userLocs, base241, baseLocs, tm, lm);
        assertTrue(Terrain.valid241(r.terrain()));
        byte[] expected = Terrain.to241(withOverlay(withOverlay(start, 20, 9), 10, 5));
        assertArrayEquals(expected, r.terrain(), "user tile 10 and BASE tile 20 both present");
        // tile 30 (user placement change) and 10 (user terrain change) are the user's; tile 40 is BASE's
        assertTrue(LocFile.contains(r.locs(), 500, 30, 4));
        assertFalse(LocFile.contains(r.locs(), 700, 30, 4));
        assertTrue(LocFile.contains(r.locs(), 800, 40, 0));
        assertFalse(LocFile.contains(r.locs(), 600, 40, 0));
        assertFalse(LocFile.contains(r.locs(), 900, 10, 0), "BASE placement on a user terrain tile is dropped");
        assertEquals(2, r.baseDropped());
    }

    @Test void maskRunsRoundTrip() {
        BitSet b = new BitSet(16384);
        b.set(0); b.set(5, 9); b.set(16383);
        String s = AllowList.runs(b);
        assertEquals("0,5-8,16383", s);
        assertEquals(b, AllowList.parseRuns(s));
        assertEquals(new BitSet(), AllowList.parseRuns(AllowList.runs(new BitSet())));
    }

    @Test void classifierMasksFindChangedTiles() {
        byte[] s = flat(0), u = withOverlay(s, 4097, 3);
        LocFile sl = new LocFile(List.of(new LocFile.Loc(1, 64, 0))), ul = new LocFile(List.of(new LocFile.Loc(1, 64, 0), new LocFile.Loc(2, 65, 0)));
        BitSet[] m = Classifier.masks(u, ul, s, sl);
        assertNotNull(m);
        assertEquals(1, m[0].cardinality());
        assertTrue(m[0].get(4097));
        assertEquals(1, m[1].cardinality());
        assertTrue(m[1].get(65));
    }

    @Test void envNeighbourOrderIsRingsSouthWestFirst() {
        List<Integer> n = Env.neighbourOrder(12342);
        assertEquals(47 << 8 | 53, (int) n.get(0));
        assertEquals(new TreeSet<>(n.subList(0, 8)).size(), 8);
        assertEquals(46 << 8 | 52, (int) n.get(8));
        for (int r : n.subList(0, 8)) assertEquals(1, Math.max(Math.abs((r >> 8) - 48), Math.abs((r & 255) - 54)));
    }

    @Test void xteaHeaderSearch() {
        int[] key = {0x01234567, 0x89abcdef, 0x13579bdf, 0x2468ace0};
        byte[] enc = MapSelfCheck.encryptBlock(new byte[]{0, 0, 1, 0, 0x1f, (byte) 0x8b, 8, 0}, key);
        assertArrayEquals(new byte[]{0, 0, 1, 0, 0x1f, (byte) 0x8b, 8, 0}, KeySearch.decryptBlock(enc, 0, key));
        byte[] raw = new byte[24];
        raw[0] = 2;
        System.arraycopy(enc, 0, raw, 5, 8);
        assertTrue(KeySearch.headerMatches(raw, key));
        assertFalse(KeySearch.headerMatches(raw, new int[]{1, 1, 1, 1}));
    }

    @Test void instanceScanEvaluatesLiteralArithmetic() {
        assertEquals(440, InstanceScan.eval("3522 / 8"));
        assertEquals(328, InstanceScan.eval("2624 >> 3"));
        assertEquals(165, InstanceScan.eval(" 165 "));
        assertNull(InstanceScan.eval("copiedChunkX"));
        assertEquals(400, InstanceScan.eval("CHUNK_X", java.util.Map.of("CHUNK_X", 400)));
    }

    @Test void storedAllowListIsConsistent() throws Exception {
        AllowList a = AllowList.load();
        assertFalse(a.squares.isEmpty());
        for (var e : a.masks.entrySet()) {
            AllowList.Square s = a.squares.get(e.getKey());
            assertNotNull(s, "mask for a listed square " + e.getKey());
            assertEquals(Action.OVERLAY, s.action());
            assertTrue(s.merge());
        }
        for (AllowList.Square s : a.squares.values()) if (s.merge()) assertNotNull(a.masks.get(s.region()), "merged square has masks " + s.region());
        for (int r : a.restoreLocs.keySet()) {
            assertEquals(Action.RESTORE, a.squares.get(r).action());
            assertNotNull(LocFile.tryParse(a.restoreLocs.get(r)));
        }
        // the packer-made home and POH templates are written
        assertTrue(a.writes(12342));
        for (int r : new int[]{7513, 7514, 7769, 7770, 8025, 8026}) assertTrue(a.writes(r), "POH template " + r);
        assertTrue(a.loc225.get(47337));
        assertFalse(a.loc225.get(50001), "Christmas customs are not 225 imports");
    }

    // ------------------------------------------------------------------ real caches (only with -Dgadconv.root)

    static Options real() {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211), "needs -Dgadconv.root");
        return o;
    }

    @Test void dryRunWritesTheAllowListAndIsDeterministic(@TempDir Path tmp) throws Exception {
        Options o = real();
        String[] reports = new String[2];
        for (int i = 0; i < 2; i++) {
            o.dryRun = true;
            o.out = tmp.resolve("out" + i);
            o.work = tmp.resolve("work" + i);
            try (Ctx ctx = Ctx.open(o, true)) {
                ctx.report.echo = false;
                for (var s : List.of(new MapLocPlanStep(), new MapsStep())) { ctx.report.begin(s.name()); s.run(ctx); ctx.report.end(); }
                assertEquals(0, ctx.report.errors());
                var rj = ctx.report.toJson();
                rj.remove("inputs"); // holds the output/work paths, which differ between the two runs
                reports[i] = com.gadscape.cacheconv.core.Json.write(rj) + com.gadscape.cacheconv.core.Json.write(ctx.ids.toJson());
                AllowList a = AllowList.load();
                for (AllowList.Square s : a.squares.values()) {
                    if (!a.writes(s.region())) continue;
                    assertTrue(ctx.out.isPending(5, s.region()), "square written " + s.region());
                    assertTrue(Square241.isSquare(ctx.out, s.region()), "written square parses " + s.region());
                }
                // moved locs stay u16 (OPLOC / LOC_ADD_CHANGE / multiloc transforms)
                for (int to : ctx.ids.all(com.gadscape.cacheconv.core.Kind.LOC).values()) assertTrue(to <= 65534, "loc moved to " + to);
                assertTrue(ctx.ids.moved(com.gadscape.cacheconv.core.Kind.LOC, 35000), "TELE-08 portal 35000 moves");
            }
        }
        assertEquals(reports[0], reports[1]);
    }
}
