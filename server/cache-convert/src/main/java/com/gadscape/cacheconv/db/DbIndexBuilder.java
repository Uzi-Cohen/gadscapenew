package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.GroupData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the archive-21 index group of one dbtable from its rows, the way Jagex's packer does. Verified byte for byte
 * against every index group of the stock 241 cache (156 master files, 153 column files; {@link DbSelfCheck}):
 * <ul>
 *   <li>file 0 (master): one int tuple whose only key 0 lists every row of the table in ascending id order; a table
 *       without rows has the int tuple and no key;</li>
 *   <li>file c + 1 (column c): one tuple per type of the column (types from the dbtable definition); for each row in
 *       ascending id order the row's values of that column, or the table's default values when the row does not set
 *       the column, each field adding the row id to its key's list (a row holding one value in two fields is listed
 *       twice); the keys come out in the iteration order of a {@code java.util.HashMap} filled with get/put in that
 *       order (Jagex's tool is Java; the order is the same in every JDK since 8).</li>
 * </ul>
 */
public final class DbIndexBuilder {
    private DbIndexBuilder() {}

    /** One row with its id. */
    public record Row(int id, DbRow row) {}

    public static DbIndexFile master(List<Row> rows) {
        if (rows.isEmpty()) {
            DbIndexFile d = new DbIndexFile();
            d.tuples.add(new DbIndexFile.Tuple(0));
            return d;
        }
        int[] ids = new int[rows.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = rows.get(i).id();
        return DbIndexFile.master(ids);
    }

    /** The lookup index of one column, or null when neither the table nor any row gives the column's types. */
    public static DbIndexFile column(List<Row> rows, DbRow tableDef, int column) {
        DbRow.Column def = tableDef == null ? null : tableDef.columns.get(column);
        int[] types = def != null ? def.types : null;
        if (types == null) for (Row r : rows) { DbRow.Column c = r.row().columns.get(column); if (c != null) { types = c.types; break; } }
        if (types == null) return null;
        DbIndexFile d = new DbIndexFile();
        for (int t = 0; t < types.length; t++) {
            HashMap<Object, List<Integer>> m = new HashMap<>();
            for (Row r : rows) {
                DbRow.Column c = r.row().columns.get(column);
                if (c == null && def != null && def.hasDefaults) c = def;
                if (c == null || t >= c.types.length) continue;
                for (int f = 0; f < c.fields(); f++) {
                    Object key = c.value(f, t);
                    List<Integer> l = m.get(key);
                    if (l == null) { l = new ArrayList<>(); m.put(key, l); }
                    l.add(r.id());
                }
            }
            DbIndexFile.Tuple tu = new DbIndexFile.Tuple(DbRow.baseType(types[t]));
            for (Map.Entry<Object, List<Integer>> e : m.entrySet())
                tu.entries.add(new DbIndexFile.Entry(e.getKey(), e.getValue().stream().mapToInt(Integer::intValue).toArray()));
            d.tuples.add(tu);
        }
        return d;
    }

    /**
     * The index group of a table: the given file ids (0 = master, c + 1 = column c), file names copied from
     * {@code names} (same order, -1 when none). A column file whose column has no types is left out (returned in
     * {@code skipped}).
     */
    public static GroupData group(int table, int groupName, int[] fileIds, int[] names, List<Row> rows, DbRow tableDef, List<Integer> skipped) {
        GroupData.Builder b = new GroupData.Builder(table).name(groupName);
        for (int i = 0; i < fileIds.length; i++) {
            int f = fileIds[i];
            DbIndexFile d = f == 0 ? master(rows) : column(rows, tableDef, f - 1);
            if (d == null) { if (skipped != null) skipped.add(f); continue; }
            b.put(f, names == null ? -1 : names[i], d.encode());
        }
        return b.build();
    }
}
