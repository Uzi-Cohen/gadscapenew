package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests of the database area on hand-made rows and tables (no caches needed), plus the real-cache self-check. */
class DbTest {
    static final int INT = 0, STRING = 36, LONG = 35, OBJ = 33;

    /** A dbrow of a table: column 0 int, column 1 obj, column 2 (int, string) with two fields when given. */
    static byte[] row(int table, int c0, int obj, boolean twoFields) {
        BufOut o = new BufOut().u8(3).u8(3);
        o.u8(0).u8(1).smart(INT).smart(1).i32(c0);
        o.u8(1).u8(1).smart(OBJ).smart(1).i32(obj);
        if (twoFields) o.u8(2).u8(2).smart(INT).smart(STRING).smart(2).i32(7).str("a").i32(7).str("b");
        o.u8(255);
        o.u8(4);
        DbRow.varint(o, table);
        return o.u8(0).done();
    }

    /** A table: column 0 int (default 99), column 1 obj, column 2 (int, string), column 3 long (default 5). */
    static byte[] table() {
        BufOut o = new BufOut().u8(1).u8(4);
        o.u8(0 | 128).u8(1).smart(INT).smart(1).i32(99);
        o.u8(1).u8(1).smart(OBJ);
        o.u8(2).u8(2).smart(INT).smart(STRING);
        o.u8(3 | 128).u8(1).smart(LONG).smart(1).i64(5L);
        o.u8(255);
        return o.u8(0).done();
    }

    @Test void rowsAndTablesRoundTrip() {
        byte[] r = row(1001, 4, 1323, true);
        DbRow d = DbRow.row(r);
        assertEquals(1001, d.tableId);
        assertEquals(2, d.columns.get(2).fields());
        assertEquals("b", d.columns.get(2).value(1, 1));
        assertArrayEquals(r, d.encode());
        byte[] padded = Arrays.copyOf(r, r.length + 30);            // the GadScape packer's zero padding
        DbRow p = DbRow.row(padded);
        assertEquals(30, p.padding);
        assertArrayEquals(r, p.encode());
        byte[] t = table();
        DbRow td = DbRow.tableDef(t);
        assertArrayEquals(t, td.encode());
        assertEquals(99, td.columns.get(0).value(0, 0));
        assertEquals(5L, td.columns.get(3).value(0, 0));
        byte[] junk = Arrays.copyOf(r, r.length + 2); junk[junk.length - 1] = 1;
        assertThrows(IllegalStateException.class, () -> DbRow.row(junk));
    }

    @Test void indexFileRoundTrip() {
        DbIndexFile f = new DbIndexFile();
        DbIndexFile.Tuple a = new DbIndexFile.Tuple(0), s = new DbIndexFile.Tuple(2), l = new DbIndexFile.Tuple(1);
        a.entries.add(new DbIndexFile.Entry(-1, new int[]{1, 2, 300}));
        s.entries.add(new DbIndexFile.Entry("Vote", new int[]{60586}));
        l.entries.add(new DbIndexFile.Entry(1L << 40, new int[]{}));
        f.tuples.addAll(List.of(a, s, l));
        byte[] b = f.encode();
        assertEquals(f, DbIndexFile.parse(b));
        assertArrayEquals(b, DbIndexFile.parse(Arrays.copyOf(b, b.length + 9)).encode());
        assertEquals(List.of(1, 2, 300, 60586), f.rows());
        assertEquals(List.of(61, 62, 400, 60686), f.mapRows(r -> r + (r < 100 ? 60 : 100)).rows());
    }

    @Test void builderFollowsJagexRules() {
        DbRow def = DbRow.tableDef(table());
        List<DbIndexBuilder.Row> rows = new ArrayList<>();
        // ascending ids; column 0 values 3, 256, 1 and one row without column 0 (gets the default 99)
        rows.add(new DbIndexBuilder.Row(10, DbRow.row(row(5, 3, 500, true))));
        rows.add(new DbIndexBuilder.Row(11, DbRow.row(row(5, 256, 500, false))));
        rows.add(new DbIndexBuilder.Row(12, DbRow.row(row(5, 1, 501, false))));
        byte[] noCol0 = new BufOut().u8(3).u8(3).u8(1).u8(1).smart(OBJ).smart(1).i32(502).u8(255).u8(4).u8(5).u8(0).done();
        rows.add(new DbIndexBuilder.Row(13, DbRow.row(noCol0)));
        // master: one int tuple, key 0, rows ascending
        DbIndexFile m = DbIndexBuilder.master(rows);
        assertEquals(List.of(10, 11, 12, 13), m.rows());
        assertEquals(0, m.tuples.get(0).entries.get(0).value());
        // empty table: the int tuple without keys
        assertArrayEquals(new byte[]{1, 0, 0}, DbIndexBuilder.master(List.of()).encode());
        // column 0: java.util.HashMap order (capacity 16: 256 -> bucket 0, 1 -> 1, 3 -> 3, 99 -> 3 after 3)
        DbIndexFile c0 = DbIndexBuilder.column(rows, def, 0);
        List<Object> keys = new ArrayList<>();
        for (var e : c0.tuples.get(0).entries) keys.add(e.value());
        assertEquals(List.of(256, 1, 3, 99), keys);
        assertArrayEquals(new int[]{13}, c0.tuples.get(0).entries.get(3).rows());
        // column 2 (int, string): two tuples; a row holding 7 in two fields is listed twice
        DbIndexFile c2 = DbIndexBuilder.column(rows, def, 2);
        assertEquals(2, c2.tuples.size());
        assertEquals(2, c2.tuples.get(1).baseType);
        assertArrayEquals(new int[]{10, 10}, c2.tuples.get(0).entries.get(0).rows());
        // a column neither the table nor a row defines
        assertNull(DbIndexBuilder.column(rows, def, 9));
        GroupData g = DbIndexBuilder.group(5, -1, new int[]{0, 1, 10}, new int[]{-1, -1, -1}, rows, def, new ArrayList<>());
        assertArrayEquals(new int[]{0, 1}, g.fileIds());
    }

    /** The real-cache self-check, only when the cache layout is available (-Dgadconv.root or GADCONV_ROOT). */
    @Test void selfCheckOnTheRealCaches(@TempDir Path work) throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user) && Files.isDirectory(o.van211), "cache layout not available");
        o.work = work;
        assertEquals(List.of(), DbSelfCheck.check(o));
    }
}
