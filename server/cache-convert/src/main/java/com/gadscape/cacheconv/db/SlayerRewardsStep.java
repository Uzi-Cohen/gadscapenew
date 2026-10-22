package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Report.Decision;
import com.gadscape.cacheconv.core.Step;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * db-slayer-rewards: GadScape's own slayer rewards as rows of 241's slayer reward table.
 * <p>
 * On 211 the reward interface (scripts 414/423/426/1090/1092) listed rewards by index from enums 834 (name), 835
 * (description), 836 (cost), 837 (icon obj) and 839 (the unlock tab's order); GadScape added index 53, "Slayer Statues", and
 * raised the scripts' index limit. 241 draws the rewards from dbtable 117 instead (column 0 reward id = unlock bit: ids 0-31
 * in varp 1076, 32-63 in varp 1344, 64-95 in varp 5587, scripts 8048/8049; 1 cost; 2 icon obj; 3 name; 4 description;
 * 6 (tab, position)), so the UI merge keeps BASE's scripts and the reward needs a row. 241's ids 0-52 are 211's indices
 * 0-52 (same rewards), but Jagex gave 53-66 to its own newer rewards (53 is "Revenenenenenants").
 * <p>
 * For every reward key the user added to enum 834 (USER vs VAN211) whose name the table does not have yet: a new row in the
 * reserved dbrow range ({@code IdMap.addNew}, idmap.json {@code _new} "slayer-reward:INDEX"), reward id = the highest id
 * up to 95 no row of the table uses (kept away from Jagex's next ids), cost / icon obj (mapped through the id map) / name /
 * description from the user's enums, on the unlock tab (0) after its last position when enum 839 lists it. The column
 * types are those of a BASE row of the table. The GadScape server still unlocks it as bit INDEX of its 211 bitset: it has
 * to use the new reward id (reported; the server's Slayer unlock code is 211-shaped anyway). Runs after {@code configs},
 * before {@code db-index} (which re-indexes table 117).
 */
public final class SlayerRewardsStep implements Step {
    public static final String NAME = "db-slayer-rewards";
    public static final int TABLE = 117, MAX_ID = 95;
    static final int E_NAME = 834, E_DESC = 835, E_COST = 836, E_OBJ = 837, E_UNLOCK_LIST = 839;

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        CacheView user = ctx.user(), van = ctx.van211();
        TreeMap<Integer, Object> names = enumMap(user, E_NAME), vanNames = enumMap(van, E_NAME);
        if (names == null || vanNames == null) { ctx.report.count("no-reward-enums", 1); return; }
        TreeMap<Integer, Object> desc = orEmpty(enumMap(user, E_DESC)), cost = orEmpty(enumMap(user, E_COST)), obj = orEmpty(enumMap(user, E_OBJ)),
                unlockList = orEmpty(enumMap(user, E_UNLOCK_LIST));
        // the table's rows in the output
        TreeMap<Integer, DbRow> rows = new TreeMap<>();
        for (int id : ctx.out.configIds(Arch.CFG_DBROW)) {
            DbRow r;
            try { r = DbRow.row(ctx.out.configRef(Arch.CFG_DBROW, id)); } catch (RuntimeException e) { continue; }
            if (r.tableId == TABLE) rows.put(id, r);
        }
        if (rows.isEmpty()) { ctx.report.count("no-table-" + TABLE, 1); return; }
        TreeSet<Integer> usedIds = new TreeSet<>();
        TreeSet<String> usedNames = new TreeSet<>();
        int lastUnlockPos = -1, template = -1;
        for (var e : rows.entrySet()) {
            DbRow r = e.getValue();
            Integer rid = intCol(r, 0, 0);
            if (rid != null) usedIds.add(rid);
            DbRow.Column n = r.columns.get(3);
            if (n != null && n.values.length > 0) usedNames.add(n.values[0].toString().toLowerCase(java.util.Locale.ROOT));
            Integer tab = intCol(r, 6, 0), pos = intCol(r, 6, 1);
            if (tab != null && tab == 0 && pos != null) lastUnlockPos = Math.max(lastUnlockPos, pos);
            if (template < 0 && r.columns.keySet().containsAll(List.of(0, 1, 2, 3, 4, 6))) template = e.getKey();
        }
        if (template < 0) { ctx.report.error("dbrow", TABLE, "no row of table " + TABLE + " with columns 0-4 and 6 to take the column types from"); return; }
        List<String> tsv = new ArrayList<>();
        for (var e : names.entrySet()) {
            int k = e.getKey();
            if (vanNames.containsKey(k)) continue;                       // vanilla 211 reward: 241 has it (ids 0-52 agree)
            String name = String.valueOf(e.getValue());
            if (usedNames.contains(name.toLowerCase(java.util.Locale.ROOT))) { ctx.report.add(Decision.SAME, "dbrow", k, "slayer reward '" + name + "' is in table " + TABLE + " already"); continue; }
            if (!(cost.get(k) instanceof Integer c) || !(obj.get(k) instanceof Integer o) || !(desc.get(k) instanceof String d)) {
                ctx.report.warn("dbrow", k, "user slayer reward " + k + " '" + name + "' lacks a cost, obj or description in enums 835-837: no row");
                continue;
            }
            if (!unlockList.containsValue(k)) { ctx.report.warn("dbrow", k, "user slayer reward " + k + " '" + name + "' is not on the unlock list (enum 839): no row (only unlock-tab rewards are carried)"); continue; }
            int rid = MAX_ID;
            while (rid >= 0 && usedIds.contains(rid)) rid--;
            if (rid < 0) { ctx.report.error("dbrow", k, "no free slayer reward id up to " + MAX_ID); return; }
            usedIds.add(rid);
            int pos = ++lastUnlockPos;
            int objTo = ctx.ids.map(Kind.OBJ, o);
            DbRow r = DbRow.row(ctx.out.configRef(Arch.CFG_DBROW, template));
            set(r, 0, rid); set(r, 1, c); set(r, 2, objTo); set(r, 3, name); set(r, 4, d);
            DbRow.Column c6 = r.columns.get(6);
            r.columns.put(6, new DbRow.Column(6, c6.types, new Object[]{0, pos}, false));
            for (int col : new ArrayList<>(r.columns.keySet())) if (col != 0 && col != 1 && col != 2 && col != 3 && col != 4 && col != 6) r.columns.remove(col);
            int bitVarp = rid < 32 ? 1076 : rid < 64 ? 1344 : 5587;
            String why = "GadScape's slayer reward '" + name + "' (211 enum 834 index " + k + ") as a row of table " + TABLE + ": reward id " + rid
                    + " (unlock bit " + (rid & 31) + " of varp " + bitVarp + "; 241's id " + k + " is " + baseName(rows, k) + "), cost " + c + ", icon obj " + o
                    + (objTo != o ? " -> " + objTo : "") + ", unlock tab position " + pos + ". The server must unlock it as reward id " + rid + " instead of bit " + k;
            int id = ctx.ids.addNew(Kind.DBROW, "slayer-reward:" + k, why);
            ctx.out.putConfig(Arch.CFG_DBROW, id, r.encode());
            ctx.report.add(Decision.USER_ADDED, "dbrow", id, why);
            ctx.report.count("slayer-rewards.rows-added", 1);
            tsv.add(k + "\t" + name + "\t" + id + "\t" + rid + "\t" + pos);
        }
        user.release(Arch.CONFIGS); van.release(Arch.CONFIGS);
        if (!tsv.isEmpty()) ctx.log("slayer rewards added to table %d: %s", TABLE, tsv);
    }

    static String baseName(TreeMap<Integer, DbRow> rows, int rid) {
        for (DbRow r : rows.values()) {
            Integer x = intCol(r, 0, 0);
            DbRow.Column n = r.columns.get(3);
            if (x != null && x == rid && n != null && n.values.length > 0) return "'" + n.values[0] + "'";
        }
        return "unused";
    }

    static void set(DbRow r, int col, Object v) {
        DbRow.Column c = r.columns.get(col);
        r.columns.put(col, new DbRow.Column(col, c.types, new Object[]{v}, false));
    }

    static Integer intCol(DbRow r, int col, int element) {
        DbRow.Column c = r.columns.get(col);
        if (c == null || c.values.length <= element || !(c.values[element] instanceof Integer i)) return null;
        return i;
    }

    static TreeMap<Integer, Object> orEmpty(TreeMap<Integer, Object> m) { return m == null ? new TreeMap<>() : m; }

    /** Key -> value (Integer or String) of an enum (211 or 241 layout, opcodes 1-6), or null if absent / unreadable. */
    public static TreeMap<Integer, Object> enumMap(CacheView c, int id) {
        byte[] b = c.configRef(Arch.CFG_ENUM, id);
        if (b == null) return null;
        TreeMap<Integer, Object> m = new TreeMap<>();
        try {
            Buf in = new Buf(b);
            while (true) {
                int op = in.u8();
                if (op == 0) return m;
                switch (op) {
                    case 1, 2 -> in.u8();
                    case 3 -> in.str();
                    case 4 -> in.i32();
                    case 5 -> { int n = in.u16(); for (int i = 0; i < n; i++) { int k = in.i32(); m.put(k, in.str()); } }
                    case 6 -> { int n = in.u16(); for (int i = 0; i < n; i++) { int k = in.i32(); m.put(k, in.i32()); } }
                    default -> { return null; }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
    }
}
