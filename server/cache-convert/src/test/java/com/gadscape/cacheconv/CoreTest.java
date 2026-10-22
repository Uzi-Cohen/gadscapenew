package com.gadscape.cacheconv;

import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.Bytes;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.JagCache;
import com.gadscape.cacheconv.core.Json;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.RefTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreTest {
    @Test void bufRoundTrip() {
        byte[] b = new BufOut().u8(7).u16(65535).u24(0x123456).i32(-2).i64(1L << 40).str("Obligator").smart(5).smart(300)
                .bigSmart(-1).bigSmart(100).bigSmart(70000).smartInt(10).smartInt(100000).done();
        Buf in = new Buf(b);
        assertEquals(7, in.u8()); assertEquals(65535, in.u16()); assertEquals(0x123456, in.u24()); assertEquals(-2, in.i32());
        assertEquals(1L << 40, in.i64()); assertEquals("Obligator", in.str()); assertEquals(5, in.smart()); assertEquals(300, in.smart());
        assertEquals(-1, in.bigSmart()); assertEquals(100, in.bigSmart()); assertEquals(70000, in.bigSmart());
        assertEquals(10, in.smartInt()); assertEquals(100000, in.smartInt());
        assertTrue(in.eof());
    }

    @Test void zeroTail() {
        assertTrue(Bytes.equalsIgnoringZeroTail(new byte[]{1, 2}, new byte[]{1, 2, 0, 0}));
        assertFalse(Bytes.equalsIgnoringZeroTail(new byte[]{1, 2}, new byte[]{1, 2, 0, 1}));
        assertEquals(-1, Bytes.firstDiff(new byte[]{1}, new byte[]{1}));
    }

    @Test void json() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", 1); m.put("b", List.of(1, 2, 3)); m.put("c", "x\"y\né"); m.put("d", Map.of("k", true));
        Object back = Json.parse(Json.write(m));
        assertEquals(1L, Json.obj(back).get("a"));
        assertEquals("x\"y\né", Json.obj(back).get("c"));
        assertEquals(List.of(1L, 2L, 3L), Json.obj(back).get("b"));
        assertEquals(Json.write(m), Json.write(m));
    }

    @Test void splitFiles() {
        // two files "ab" and "cde" in one chunk: data, then lengths (delta coded per chunk), then the chunk count
        byte[] d = new BufOut().raw(new byte[]{'a', 'b', 'c', 'd', 'e'}).i32(2).i32(1).u8(1).done();
        byte[][] f = JagCache.split(d, 2, "test");
        assertArrayEquals("ab".getBytes(), f[0]);
        assertArrayEquals("cde".getBytes(), f[1]);
    }

    @Test void groupBuilder() {
        GroupData g = new GroupData.Builder(5).put(3, new byte[]{3}).put(1, new byte[]{1}).build();
        assertArrayEquals(new int[]{1, 3}, g.fileIds());
        GroupData h = g.toBuilder().remove(1).put(2, new byte[]{2}).build();
        assertArrayEquals(new int[]{2, 3}, h.fileIds());
        assertTrue(g.sameContent(g.toBuilder().build()));
    }

    @Test void refTable() {
        // protocol 7, version 9, flags 0x4 (sizes), groups 3 and 7 with 1 and 2 files, then 4 bytes of padding
        BufOut o = new BufOut().u8(7).i32(9).u8(0x4).smartInt(2).smartInt(3).smartInt(4);
        o.i32(11).i32(12);                     // crcs
        o.i32(1).i32(2).i32(3).i32(4);         // sizes
        o.i32(1).i32(1);                       // versions
        o.smartInt(1).smartInt(2);             // file counts
        o.smartInt(0).smartInt(0).smartInt(5); // file ids (delta)
        o.i32(0);                              // padding
        RefTable t = RefTable.parse(o.done());
        assertArrayEquals(new int[]{3, 7}, t.groupIds);
        assertArrayEquals(new int[]{0, 5}, t.fileIds[1]);
        assertEquals(4, t.length - t.end);
    }

    @Test void idMap(@TempDir Path dir) throws Exception {
        IdMap m = new IdMap();
        int a = m.relocate(Kind.SEQ, 15000, "raid clash");
        assertEquals(40000, a);
        assertEquals(40000, m.relocate(Kind.SEQ, 15000, "again"));
        assertEquals(40001, m.relocate(Kind.SEQ, 15001, "raid clash"));
        assertEquals(15002, m.map(Kind.SEQ, 15002));
        assertThrows(IllegalStateException.class, () -> m.record(Kind.SEQ, 15000, 40005, "conflict"));
        assertThrows(IllegalStateException.class, () -> m.record(Kind.SEQ, 15003, 40000, "target taken"));
        m.record(Kind.FRAME, 4478, 30000, "frame group clash");
        assertEquals((30000 << 16) | 7, m.mapFrameRef((4478 << 16) | 7));
        assertThrows(IllegalStateException.class, () -> m.allocate(Kind.OBJ));
        m.save(dir.resolve("idmap.json"));
        IdMap back = IdMap.load(dir.resolve("idmap.json"));
        assertEquals(40001, back.map(Kind.SEQ, 15001));
        assertEquals("raid clash", back.reason(Kind.SEQ, 15000));
        assertEquals(40002, back.allocate(Kind.SEQ));
        assertEquals(Json.write(m.toJson()), Json.write(IdMap.load(dir.resolve("idmap.json")).toJson()));
    }

    @Test void idMapAliases(@TempDir Path dir) throws Exception {
        IdMap m = new IdMap();
        m.record(Kind.MODEL, 58011, 596, "twin");
        m.alias(Kind.MODEL, 58013, 596, "twin, shared");
        m.alias(Kind.MODEL, 58013, 596, "again");                                   // same alias twice: fine
        assertEquals(596, m.map(Kind.MODEL, 58013));
        assertEquals(58011, m.origin(Kind.MODEL, 596));                             // origin stays the recorded source
        assertTrue(m.isAlias(Kind.MODEL, 58013));
        assertFalse(m.isAlias(Kind.MODEL, 58011));
        assertThrows(IllegalStateException.class, () -> m.alias(Kind.MODEL, 58013, 597, "conflict"));
        assertThrows(IllegalStateException.class, () -> m.alias(Kind.MODEL, 1, 58011, "target is moved"));
        assertThrows(IllegalStateException.class, () -> m.record(Kind.MODEL, 2, 596, "target taken by a record"));
        m.save(dir.resolve("idmap.json"));
        IdMap back = IdMap.load(dir.resolve("idmap.json"));
        assertEquals(596, back.map(Kind.MODEL, 58013));
        assertTrue(back.isAlias(Kind.MODEL, 58013));
        assertEquals(Json.write(m.toJson()), Json.write(back.toJson()));
    }

    @Test void kindRanges() {
        IdMap m = new IdMap();
        assertEquals(null, m.range(Kind.MODEL));                                    // set from BASE by Ctx.open
        assertEquals(new IdMap.Range(64500, 65534), m.range(Kind.LOC));            // u16 loc ids on the 241 wire
        assertEquals(new IdMap.Range(5200, 5299), m.range(Kind.INTERFACE));
        assertEquals(new IdMap.Range(4000, 4999), m.range(Kind.DBTABLE));
        assertEquals(null, m.range(Kind.INV));
        for (Kind k : Kind.values()) { IdMap.Range r = m.range(k); if (r != null) assertTrue(r.hi() <= 65535, k.key); }
        assertTrue(com.gadscape.cacheconv.convert.Cs2.BRANCH.containsAll(List.of(6, 7, 31, 32, 68, 73)));
    }
}
