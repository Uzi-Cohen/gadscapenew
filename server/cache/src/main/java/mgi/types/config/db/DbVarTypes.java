package mgi.types.config.db;

/**
 * Base types of cache var types, as far as dbrow/dbtable cells need them: the client reads a string for base type
 * string, a long for base type long and an int for everything else.
 * <p>
 * The long-based type ids (35 region uid, 49 clan hash, 56 clan forum qfc, 71 hash64, 110 long, 115 mtxn package,
 * 116 mtxn price point) and the string type id (36) were cross-checked against OpenRune's CacheVarLiteral table.
 * RuneLite's DBTableLoader reads every non-string cell as an int, which is wrong for those long types.
 */
public final class DbVarTypes {

    private DbVarTypes() {
    }

    public static boolean isString(final int typeId) {
        return typeId == 36;
    }

    public static boolean isLong(final int typeId) {
        switch (typeId) {
            case 35:
            case 49:
            case 56:
            case 71:
            case 110:
            case 115:
            case 116:
                return true;
            default:
                return false;
        }
    }
}
