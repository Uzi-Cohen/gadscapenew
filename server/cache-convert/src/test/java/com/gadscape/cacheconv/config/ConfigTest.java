package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.config.CfgCodec.Entry;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests of the configs area on hand-made entries (no caches needed). */
class ConfigTest {
    static final int OBJ = Arch.CFG_OBJ, NPC = Arch.CFG_NPC, ENUM = Arch.CFG_ENUM, LOC = Arch.CFG_LOC;

    /** Param types: 1000 = obj, 1001 = struct, others untyped. */
    static final CfgCodec.ParamTypes TYPES = p -> p == 1000 ? 'o' : p == 1001 ? 'J' : -1;

    static CfgCodec.Mapper map(IdMap m) { return (k, v) -> m.map(k, v); }

    static IdMap moves(Object... kindFromTo) {
        IdMap m = new IdMap();
        for (int i = 0; i < kindFromTo.length; i += 3) m.record((Kind) kindFromTo[i], (Integer) kindFromTo[i + 1], (Integer) kindFromTo[i + 2], "test");
        return m;
    }

    /** obj: inventory model, name, cert link + template, count obj, params (obj-typed 1000, struct-typed 1001, plain 7). */
    static byte[] obj() {
        return new BufOut().u8(44).i32(5000).u8(2).str("Gad obligator").u8(97).u16(34727).u8(98).u16(799)
                .u8(100).u16(34726).u16(2)
                .u8(249).u8(3).u8(0).u24(1000).i32(34726).u8(0).u24(1001).i32(1216).u8(0).u24(7).i32(34726)
                .u8(0).done();
    }

    @Test void objRoundTripAndRefs() {
        byte[] b = obj();
        Entry e = CfgCodec.parse(OBJ, 34726, b, TYPES);
        assertArrayEquals(b, e.encode());
        assertEquals(List.of(5000), CfgCodec.refsOf(e, Kind.MODEL));
        assertEquals(List.of(34727, 799, 34726, 34726), CfgCodec.refsOf(e, Kind.OBJ), "cert link, template, count obj, obj-typed param");
        assertEquals(List.of(1216), CfgCodec.refsOf(e, Kind.STRUCT));
        assertEquals(List.of(1000, 1001, 7), CfgCodec.refsOf(e, Kind.PARAM));
        assertEquals("Gad obligator", CfgCodec.name(e));
    }

    @Test void mappingPatchesOnlyReferences() {
        byte[] b = obj();
        Entry e = CfgCodec.parse(OBJ, 34726, b, TYPES);
        assertSame(e, CfgCodec.map(e, (k, v) -> v), "identity: same instance");
        IdMap m = moves(Kind.OBJ, 34726, 60003, Kind.OBJ, 34727, 60004, Kind.MODEL, 5000, 64500, Kind.STRUCT, 1216, 13000, Kind.PARAM, 7, 9000);
        Entry r = CfgCodec.map(e, map(m));
        assertNotSame(e, r);
        Entry back = CfgCodec.parse(OBJ, 60003, r.encode(), p -> p == 1000 ? 'o' : p == 1001 ? 'J' : -1);
        assertEquals(List.of(64500), CfgCodec.refsOf(back, Kind.MODEL));
        assertEquals(List.of(60004, 799, 60003, 60003), CfgCodec.refsOf(back, Kind.OBJ), "untyped param 7's value 34726 is not an obj");
        assertEquals(List.of(13000), CfgCodec.refsOf(back, Kind.STRUCT));
        assertEquals(List.of(1000, 1001, 9000), CfgCodec.refsOf(back, Kind.PARAM), "param keys are mapped too");
        assertEquals(b.length, r.encode().length);
        CfgCodec.Item params = back.get("params");
        assertTrue(params.children.stream().anyMatch(c -> c.key.equals("p9000")), "child key follows the mapped param id");
    }

    @Test void u16FieldsRejectIdsThatDoNotFit() {
        Entry e = CfgCodec.parse(OBJ, 1, obj(), TYPES);
        IdMap m = moves(Kind.OBJ, 34727, 70000);
        assertThrows(IllegalStateException.class, () -> CfgCodec.map(e, map(m)));
    }

    @Test void npcTransformsSeqsAndHeadIconWidthChange() {
        // 13 stand seq, 102 head icon (sprite 100 as 2-byte big smart, index 0), 106 transforms varbit 15000, varp none, 1 + 1 npcs
        byte[] b = new BufOut().u8(13).u16(808).u8(102).u8(1).u16(100).u8(0)
                .u8(106).u16(15000).u16(65535).u8(1).u16(12000).u16(65535).u8(0).done();
        Entry e = CfgCodec.parse(NPC, 1, b, TYPES);
        assertArrayEquals(b, e.encode());
        IdMap m = moves(Kind.SEQ, 808, 40000, Kind.SPRITE, 100, 40000, Kind.VARBIT, 15000, 30000);
        Entry r = CfgCodec.map(e, map(m));
        byte[] out = r.encode();
        assertEquals(b.length + 2, out.length, "sprite 40000 needs the 4-byte big smart");
        Entry back = CfgCodec.parse(NPC, 1, out, TYPES);
        assertEquals(List.of(40000), CfgCodec.refsOf(back, Kind.SEQ));
        assertEquals(List.of(40000), CfgCodec.refsOf(back, Kind.SPRITE));
        assertEquals(List.of(30000), CfgCodec.refsOf(back, Kind.VARBIT));
        assertEquals(List.of(12000), CfgCodec.refsOf(back, Kind.NPC));
    }

    @Test void zeroPaddingIsAcceptedAndDropped() {
        byte[] b = Bytes.concat(new BufOut().u8(2).u16(28).u8(0).done(), new byte[40]);
        Entry e = CfgCodec.parse(Arch.CFG_INV, 1000, b, TYPES);
        assertEquals(40, e.padding);
        assertArrayEquals(new byte[]{2, 0, 28, 0}, e.encode());
        byte[] junk = new BufOut().u8(2).u16(28).u8(0).u8(7).done();
        assertThrows(IllegalStateException.class, () -> CfgCodec.parse(Arch.CFG_INV, 1000, junk, TYPES));
    }

    static byte[] enumIO(int[][] pairs) {
        BufOut o = new BufOut().u8(1).u8('i').u8(2).u8('o').u8(6).u16(pairs.length);
        for (int[] p : pairs) o.i32(p[0]).i32(p[1]);
        return o.u8(0).done();
    }

    static LinkedHashMap<Integer, Integer> ints(Entry e) {
        LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>();
        for (var x : EnumMerge.pairsOf(e).entrySet()) m.put(x.getKey(), Bytes.i32(x.getValue(), 0));
        return m;
    }

    @Test void enumKeyedMergeRule10() {
        // spec costs keyed by obj: VAN {1:25, 2:50}; USER adds 34721 (moved obj) and changes 2; BASE adds 3 and changes 2 too
        int[][] v = {{1, 25}, {2, 50}};
        int[][] u = {{1, 25}, {2, 60}, {34721, 100}};
        int[][] b = {{1, 25}, {2, 55}, {3, 10}};
        Entry ve = CfgCodec.parse(ENUM, 906, enumOI(v), TYPES), ue = CfgCodec.parse(ENUM, 906, enumOI(u), TYPES), be = CfgCodec.parse(ENUM, 906, enumOI(b), TYPES);
        IdMap m = moves(Kind.OBJ, 34721, 60000);
        EnumMerge.Result r = EnumMerge.merge(ve, ue, be, map(m));
        assertFalse(r.list);
        LinkedHashMap<Integer, Integer> got = ints(r.entry);
        assertEquals(25, got.get(1));
        assertEquals(60, got.get(2), "user value wins over BASE's");
        assertEquals(10, got.get(3), "BASE's addition survives");
        assertEquals(100, got.get(60000), "obj-typed key follows the moved obj");
        assertEquals(List.of("2"), r.conflicts);
    }

    /** obj-keyed int enum (906 layout: key type 'o', value type 'i'). */
    static byte[] enumOI(int[][] pairs) {
        BufOut o = new BufOut().u8(1).u8('o').u8(2).u8('i').u8(6).u16(pairs.length);
        for (int[] p : pairs) o.i32(p[0]).i32(p[1]);
        return o.u8(0).done();
    }

    @Test void enumListMergeAfterUserRemoval() {
        // list of objs: VAN [10,11,12,13]; USER removed 10 (shift) and appended 99; BASE appended 14
        Entry ve = CfgCodec.parse(ENUM, 2169, enumIO(new int[][]{{0, 10}, {1, 11}, {2, 12}, {3, 13}}), TYPES);
        Entry ue = CfgCodec.parse(ENUM, 2169, enumIO(new int[][]{{0, 11}, {1, 12}, {2, 13}, {3, 99}}), TYPES);
        Entry be = CfgCodec.parse(ENUM, 2169, enumIO(new int[][]{{0, 10}, {1, 11}, {2, 12}, {3, 13}, {4, 14}}), TYPES);
        EnumMerge.Result r = EnumMerge.merge(ve, ue, be, (k, x) -> k == Kind.OBJ && x == 99 ? 60099 : x);
        assertTrue(r.list);
        assertEquals(List.of(11, 12, 13, 60099, 14), List.copyOf(ints(r.entry).values()), "99 follows 13, as in the user's list");
        assertEquals(List.of(0, 1, 2, 3, 4), List.copyOf(ints(r.entry).keySet()));
        // idempotent: merging the user's edit into the result changes nothing
        EnumMerge.Result again = EnumMerge.merge(ve, ue, r.entry, (k, x) -> k == Kind.OBJ && x == 99 ? 60099 : x);
        assertEquals(ints(r.entry), ints(again.entry));
        // strict union (rule 10 read literally): the removed 10 stays
        EnumMerge.Result union = EnumMerge.merge(ve, ue, be, (k, x) -> x, false);
        assertEquals(List.of(10, 11, 12, 13, 99, 14), List.copyOf(ints(union.entry).values()));
        assertEquals(1, union.removalsNotApplied);
    }

    @Test void enumListInsertionsKeepTheirPlace() {
        // 2103-like: the user inserted 500 after 2 and 501 after 4; Jagex inserted 7 after 1 and appended 8
        Entry ve = CfgCodec.parse(ENUM, 2103, enumIO(new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}}), TYPES);
        Entry ue = CfgCodec.parse(ENUM, 2103, enumIO(new int[][]{{0, 1}, {1, 2}, {2, 500}, {3, 3}, {4, 4}, {5, 501}}), TYPES);
        Entry be = CfgCodec.parse(ENUM, 2103, enumIO(new int[][]{{0, 1}, {1, 7}, {2, 2}, {3, 3}, {4, 4}, {5, 8}}), TYPES);
        EnumMerge.Result r = EnumMerge.merge(ve, ue, be, (k, x) -> x);
        assertEquals(List.of(1, 7, 2, 500, 3, 4, 501, 8), List.copyOf(ints(r.entry).values()));
        assertEquals(2, r.added);
    }

    @Test void sameSizeIntTableMergesByKey() {
        // int -> int table edited in place (2286-like): by key, so BASE's other values keep their index
        BufOut v = new BufOut().u8(1).u8('i').u8(2).u8('i').u8(6).u16(3).i32(0).i32(10).i32(1).i32(20).i32(2).i32(30).u8(0);
        BufOut u = new BufOut().u8(1).u8('i').u8(2).u8('i').u8(6).u16(3).i32(0).i32(10).i32(1).i32(25).i32(2).i32(30).u8(0);
        BufOut b = new BufOut().u8(1).u8('i').u8(2).u8('i').u8(6).u16(3).i32(0).i32(11).i32(1).i32(20).i32(2).i32(30).u8(0);
        EnumMerge.Result r = EnumMerge.merge(CfgCodec.parse(ENUM, 2286, v.done(), TYPES), CfgCodec.parse(ENUM, 2286, u.done(), TYPES), CfgCodec.parse(ENUM, 2286, b.done(), TYPES), (k, x) -> x);
        assertFalse(r.list);
        assertEquals(List.of(11, 25, 30), List.copyOf(ints(r.entry).values()));
    }

    @Test void enumReplacedVsPruned() {
        // 708-like: 6 vanilla values, the user kept 1 and brought 3 new ones -> a different enum
        Entry v = CfgCodec.parse(ENUM, 708, enumIO(new int[][]{{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}}), TYPES);
        Entry replaced = CfgCodec.parse(ENUM, 708, enumIO(new int[][]{{0, 1}, {1, 900}, {2, 901}, {3, 902}}), TYPES);
        Entry pruned = CfgCodec.parse(ENUM, 708, enumIO(new int[][]{{0, 2}, {1, 5}}), TYPES);
        assertTrue(EnumMerge.replaced(v, replaced));
        assertFalse(EnumMerge.replaced(v, pruned), "values only removed (combat achievement lists): an edit");
        assertArrayEquals(new int[]{1, 3, 6, 4}, EnumMerge.valueDelta(v, replaced));
        // Jagex never changed the enum: the merge is the user's enum, in the user's order
        EnumMerge.Result r = EnumMerge.merge(v, pruned, v, (k, x) -> x);
        assertEquals(List.of(2, 5), List.copyOf(ints(r.entry).values()));
    }

    @Test void sameThingRules() {
        assertTrue(CfgModel.sameName("Helm of neitiznot (or)", "Helm of Neitiznot (or)"));
        assertTrue(CfgModel.sameName("Dizana's quiver (uncharged) (l)", "Dizana's quiver (l) (uncharged)"));
        assertFalse(CfgModel.sameName("Smoldering Demon", "Tormented Demon"));
        assertTrue(CfgModel.sameName(null, null));
        // an obj imported at its Jagex id: same name and inventory model 48053 before mapping; the models area moved
        // the user's copy of 48053 (USER bytes differ), so the mapped entry differs but the unmapped one is the same
        byte[] u = new BufOut().u8(44).i32(48053).u8(2).str("Helm of neitiznot (or)").u8(0).done();
        byte[] b = new BufOut().u8(44).i32(48053).u8(2).str("Helm of Neitiznot (or)").u8(3).str("A helm.").u8(0).done();
        Entry ue = CfgCodec.parse(OBJ, 28070, u, TYPES), be = CfgCodec.parse(OBJ, 28070, b, TYPES);
        Entry um = CfgCodec.map(ue, (k, x) -> k == Kind.MODEL && x == 48053 ? 64000 : x);
        assertFalse(CfgModel.same(OBJ, um, be).same());
        CfgModel.Same s = CfgModel.same(OBJ, ue, um, be);
        assertTrue(s.same(), s.why());
        assertTrue(s.why().contains("own ids"));
        // spotanims have no name: their op 2 is the seq
        Entry spot = CfgCodec.parse(Arch.CFG_SPOT, 1, new BufOut().u8(1).u16(500).u8(2).u16(808).u8(0).done(), TYPES);
        assertEquals(null, CfgCodec.name(spot));
        assertEquals(List.of(808), CfgCodec.refsOf(spot, Kind.SEQ));
    }

    @Test void varOperandsAndIdLists() {
        assertEquals(Kind.VARP, ConfigPlanStep.varOperand(1));
        assertEquals(Kind.VARBIT, ConfigPlanStep.varOperand(27));
        assertEquals(Kind.VARC, ConfigPlanStep.varOperand(43));
        assertEquals(Kind.VARC, ConfigPlanStep.varOperand(50));
        assertEquals(null, ConfigPlanStep.varOperand(47), "old varcstr (config 15) is another id space");
        assertEquals(List.of(3800, 3801, 3802, 4506), List.copyOf(ConfigPlanStep.parseIdList("3800-3802, 4506")));
        assertTrue(ConfigPlanStep.parseIdList("none").isEmpty());
    }

    @Test void enumAppendOnlyListKeepsUserIndexes() {
        Entry ve = CfgCodec.parse(ENUM, 1000, enumIO(new int[][]{{0, 10}, {1, 11}}), TYPES);
        Entry ue = CfgCodec.parse(ENUM, 1000, enumIO(new int[][]{{0, 10}, {1, 11}, {2, 90}, {3, 91}}), TYPES);
        Entry be = CfgCodec.parse(ENUM, 1000, enumIO(new int[][]{{0, 10}, {1, 11}, {2, 20}}), TYPES);
        EnumMerge.Result r = EnumMerge.merge(ve, ue, be, (k, x) -> x);
        assertFalse(r.list, "append-only: keyed, so the user's indexes stay");
        assertEquals(List.of(10, 11, 90, 91), List.copyOf(ints(r.entry).values()));
        assertEquals(List.of("2"), r.conflicts);
    }

    @Test void enumTypesIncompatible() {
        Entry u = CfgCodec.parse(ENUM, 1, enumIO(new int[][]{{0, 1}}), TYPES);
        Entry b = CfgCodec.parse(ENUM, 1, enumOI(new int[][]{{5, 1}}), TYPES);
        assertTrue(EnumMerge.incompatible(u, b));
        assertFalse(EnumMerge.incompatible(u, CfgCodec.parse(ENUM, 1, new byte[]{0}, TYPES)), "an empty BASE enum takes anything");
    }

    @Test void threeWayMergeOfAnObj() {
        // VAN: name, inv op 4 "Destroy", param 7 = 1. USER (re-saved by a 211 editor): "Drop", default fields written
        // explicitly (32 "Take", 42 -2, 113 0), wearpos 13 lost, param 7 removed, new op 36 "Wear".
        // BASE: Jagex added examine (3) and param 8, kept everything else.
        byte[] v = new BufOut().u8(2).str("Hat").u8(13).u8(0).u8(39).str("Destroy").u8(249).u8(1).u8(0).u24(7).i32(1).u8(0).done();
        byte[] u = new BufOut().u8(2).str("Hat").u8(32).str("Take").u8(36).str("Wear").u8(39).str("Drop").u8(42).u8(-2).u8(113).u8(0).u8(0).done();
        byte[] b = new BufOut().u8(2).str("Hat").u8(13).u8(0).u8(39).str("Destroy").u8(3).str("A hat.")
                .u8(249).u8(2).u8(0).u24(7).i32(1).u8(0).u24(8).i32(5).u8(0).done();
        CfgModel.Lossy lossy = new CfgModel.Lossy();
        lossy.keyValues.add("13=" + Bytes.hex(new byte[]{13, 0}));
        Merge3.Result r = Merge3.merge(OBJ, CfgCodec.parse(OBJ, 1, v, TYPES), CfgCodec.parse(OBJ, 1, u, TYPES), CfgCodec.parse(OBJ, 1, b, TYPES), (k, x) -> x, lossy);
        Entry e = r.entry;
        assertEquals("Drop", str(e.get("39")));
        assertEquals("Wear", str(e.get("36")));
        assertEquals("A hat.", str(e.get("3")), "Jagex's addition survives");
        assertTrue(e.get("13") != null, "wearpos 0 is an encoder loss, not a removal");
        assertTrue(e.get("32") == null && e.get("42") == null && e.get("113") == null, "default-valued fields are not added");
        CfgCodec.Item params = e.get("params");
        assertEquals(List.of("p8"), params.children.stream().map(c -> c.key).toList(), "the user removed param 7; BASE's param 8 stays");
        assertTrue(r.conflicts.isEmpty());
        assertArrayEquals(e.encode(), CfgCodec.parse(OBJ, 1, e.encode(), TYPES).encode());
        // no user change: BASE unchanged
        assertArrayEquals(b, Merge3.merge(OBJ, CfgCodec.parse(OBJ, 1, v, TYPES), CfgCodec.parse(OBJ, 1, v, TYPES), CfgCodec.parse(OBJ, 1, b, TYPES), (k, x) -> x, lossy).entry.encode());
    }

    static String str(CfgCodec.Item it) { return new String(it.raw, 1, it.raw.length - 2, java.nio.charset.StandardCharsets.ISO_8859_1); }

    @Test void sameEffectiveIgnoresOrderAndDefaults() {
        byte[] a = new BufOut().u8(1).u24(0xff00ff).u8(5).u8(7).u24(0x312d21).u8(0).done();
        byte[] b = new BufOut().u8(1).u24(0xff00ff).u8(7).u24(0x312d21).u8(5).u8(0).done();
        assertTrue(CfgModel.sameEffective(Arch.CFG_OVERLAY, CfgCodec.parse(Arch.CFG_OVERLAY, 1, a, TYPES), CfgCodec.parse(Arch.CFG_OVERLAY, 1, b, TYPES)));
        byte[] o1 = new BufOut().u8(2).str("x").u8(0).done(), o2 = new BufOut().u8(2).str("x").u8(42).u8(-2).u8(95).u16(0).u8(0).done();
        assertTrue(CfgModel.sameEffective(OBJ, CfgCodec.parse(OBJ, 1, o1, TYPES), CfgCodec.parse(OBJ, 1, o2, TYPES)));
    }

    @Test void transformsWithNoDefaultEqualPlainOnes() {
        byte[] t106 = new BufOut().u8(106).u16(5).u16(65535).u8(1).u16(10).u16(11).u8(0).done();
        byte[] t118 = new BufOut().u8(106).u16(5).u16(65535).u8(1).u16(10).u16(11)
                .u8(118).u16(5).u16(65535).u16(65535).u8(1).u16(10).u16(11).u8(0).done();
        assertEquals(CfgModel.transforms(CfgCodec.parse(NPC, 1, t106, TYPES), NPC), CfgModel.transforms(CfgCodec.parse(NPC, 1, t118, TYPES), NPC));
    }

    @Test void dbIndexRowsRenumbered() {
        // 1 tuple, int values: value 0 -> rows [2342, 2343]
        byte[] b = new BufOut().u8(1).u8(0).u8(1).i32(0).u8(2).u8(0xa6).u8(0x12).u8(0xa7).u8(0x12).done();
        DbIndex d = DbIndex.parse(b);
        assertEquals(List.of(2342, 2343), d.rows());
        assertArrayEquals(b, d.encode());
        assertEquals(List.of(60000, 60001), DbIndex.parse(d.mapRows(r -> r - 2342 + 60000).encode()).rows());
        assertEquals(3, DbIndex.parse(Bytes.concat(b, new byte[3])).padding);
    }

    @Test void dbRowColumnsTypedByScriptVarType() {
        // dbrow: op 3, 2 columns: column 0 one obj (type 33) value 4151, column 1 string; op 4 table 1001 (varint)
        byte[] b = new BufOut().u8(3).u8(2).u8(0).u8(1).u8(33).u8(1).i32(4151).u8(1).u8(1).u8(36).u8(1).str("x").u8(255)
                .u8(4).u8(0xe9).u8(0x07).u8(0).done();
        Entry e = CfgCodec.parse(Arch.CFG_DBROW, 2342, b, TYPES);
        assertArrayEquals(b, e.encode());
        assertEquals(List.of(4151), CfgCodec.refsOf(e, Kind.OBJ));
        assertEquals(List.of(1001), CfgCodec.refsOf(e, Kind.DBTABLE));
    }
}
