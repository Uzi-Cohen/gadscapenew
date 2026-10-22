/**
 * Database tables and rows (config groups 38/39) and their indexes (archive 21): BASE's rows and indexes keep their
 * ids and bytes; the user's tables (the universal shop, 1000-1014) are layered with their rows moved to the reserved
 * dbrow range (audit PVP-23, CRIT-23).
 *
 * <h2>Steps, in pipeline order</h2>
 * <ol>
 *   <li>{@link com.gadscape.cacheconv.db.DbPlanStep} ({@code db-plan}): early, before {@code configs-plan} (which
 *       then leaves the moved rows alone) and before the steps that rewrite dbrow/dbtable references (configs, UI).
 *       Records the moves; writes nothing.</li>
 *   <li>The configs area's {@code configs} writes the rows and tables at their new ids (with every typed column
 *       value mapped); standalone runs use {@link com.gadscape.cacheconv.db.DbRowsStep} instead.</li>
 *   <li>{@link com.gadscape.cacheconv.db.DbIndexStep} ({@code db-index}): after {@code configs} and every other
 *       step that writes dbrows; rebuilds archive 21 from the rows in the output and checks it.</li>
 * </ol>
 *
 * <h2>Index format</h2>
 * {@link com.gadscape.cacheconv.db.DbIndexFile} reads and writes one index file;
 * {@link com.gadscape.cacheconv.db.DbIndexBuilder} builds a table's group the way Jagex's packer does (rows ascending,
 * table defaults for missing columns, one row entry per field, keys in {@code java.util.HashMap} order), reproducing all
 * 156 BASE index groups (309 files) byte for byte. {@link com.gadscape.cacheconv.db.DbRow} decodes rows and tables
 * (and drops the zero padding the GadScape server's packer leaves).
 *
 * <h2>Results on the real inputs</h2>
 * USER has 2,929 rows (2,342 vanilla, BASE's) and 52 tables (37 vanilla); the user added tables 1000-1014 (BASE has
 * none of these ids: they stay) and 587 rows 2342-2928 in tables 1001-1014, all at ids BASE uses for its own rows.
 * They move, in ascending order, to 60000-60586 (each table keeps its row order: table 1001 = 60000-60047, 1008 =
 * 60048-60087, ..., 1002 = 60573-60586); BASE's rows 2342-2928 are BASE's again. Archive 21: all 156 BASE groups equal
 * BASE (the audited 0, 15, 18, 25, 34, 37, 38, 39, 40, 41, 44 and 65 among them), and the 15 user groups are rebuilt
 * from the moved rows: their masters list the same rows in the same order as USER's; the column file is now really
 * column 0's index (the server packer stored column 1's values there); table 1000's master, which listed rows
 * 5000-5203 of no user table (BASE rows of other tables once the ids are mapped), is empty. The universal-shop scripts
 * use only {@code db_listall}/{@code db_findbyindex}/{@code db_getfield}, so they see the same rows in the same order.
 * {@code WORK/sprites-db/dbrow-moves.tsv} lists table, index in table, old and new id for the server.
 *
 * <h2>Running alone</h2>
 * {@code dev/run.sh db.DbMain [--db.with=configs,sprites] [--work DIR]} (a dry run; {@code --write --out DIR} writes),
 * {@code dev/run.sh db.DbSelfCheck} (codecs, the BASE rebuild and the step results on the real caches); unit tests in
 * {@code DbTest}. Options: {@code --db.tableRange=LO-HI} (user tables that clash with BASE tables; default
 * 4000-4999), {@code --db.syncObjCopies=false}.
 */
package com.gadscape.cacheconv.db;
