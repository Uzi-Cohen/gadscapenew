package com.gadscape.cacheconv.db;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Ctx;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Origin;
import com.gadscape.cacheconv.core.Step;

/**
 * db-rows (standalone runs only, {@link DbMain} without the configs area): writes the user's dbtables and dbrows at
 * their mapped ids with the row's table id mapped and the zero padding dropped, nothing else rewritten. In the full
 * pipeline the configs area writes these entries (with every typed column value, obj/npc/... ids, mapped) and this
 * step is not used.
 */
public final class DbRowsStep implements Step {
    public static final String NAME = "db-rows";

    @Override public String name() { return NAME; }

    @Override public void run(Ctx ctx) {
        var user = ctx.user();
        var van = ctx.van211();
        int tables = 0, rows = 0;
        for (int t : user.configIds(Arch.CFG_DBTABLE)) {
            if (!Origin.ofConfig(Arch.CFG_DBTABLE, t, user, van).isUser()) continue;
            ctx.out.putConfig(Arch.CFG_DBTABLE, ctx.ids.map(Kind.DBTABLE, t), DbRow.tableDef(user.configRef(Arch.CFG_DBTABLE, t)).encode());
            tables++;
        }
        for (int id : user.configIds(Arch.CFG_DBROW)) {
            if (!Origin.ofConfig(Arch.CFG_DBROW, id, user, van).isUser()) continue;
            DbRow r = DbRow.row(user.configRef(Arch.CFG_DBROW, id));
            if (r.tableId >= 0) r.tableId = ctx.ids.map(Kind.DBTABLE, r.tableId);
            ctx.out.putConfig(Arch.CFG_DBROW, ctx.ids.map(Kind.DBROW, id), r.encode());
            rows++;
        }
        user.release(Arch.CONFIGS);
        ctx.report.count("db-rows.tables-written", tables);
        ctx.report.count("db-rows.rows-written", rows);
    }
}
