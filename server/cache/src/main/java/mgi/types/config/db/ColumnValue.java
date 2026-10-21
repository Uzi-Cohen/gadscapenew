package mgi.types.config.db;

import net.runelite.cache.util.ScriptVarType;

public class ColumnValue {
    public ScriptVarType type;
    /**
     * Raw var type id from the cache. {@link #type} is null for ids the bundled RuneLite 1.9.8 ScriptVarType enum
     * does not know (build 241 uses more, e.g. 41 category, 118 dbtable, 110 long).
     */
    public int typeId = -1;
    public int defaultInt = -1;
    public String defaultString = "";
    /**
     * Set for columns whose type has a long base type (see {@link DbVarTypes#isLong(int)}).
     */
    public long defaultLong;
    public boolean isLong;

    public ColumnValue(ScriptVarType type, int defaultInt) {
        this.type = type;
        this.defaultInt = defaultInt;
    }

    public ColumnValue(ScriptVarType type, String defaultString) {
        this.type = type;
        this.defaultString = defaultString;
    }

    /**
     * Reads one cell the way the client does: a string for base type string, a long for base type long, else an int.
     */
    public static ColumnValue read(final int typeId, final mgi.utilities.ByteBuffer stream) {
        final ScriptVarType type = ScriptVarType.forId(typeId);
        final ColumnValue value;
        if (DbVarTypes.isString(typeId)) {
            value = new ColumnValue(type, stream.readString());
        } else if (DbVarTypes.isLong(typeId)) {
            value = new ColumnValue(type, -1);
            value.defaultLong = stream.readLong();
            value.isLong = true;
        } else {
            value = new ColumnValue(type, stream.readInt());
        }
        value.typeId = typeId;
        return value;
    }

    /**
     * Writes one cell in the format {@link #read(int, mgi.utilities.ByteBuffer)} reads.
     */
    public void write(final mgi.utilities.ByteBuffer stream) {
        final int id = typeId != -1 ? typeId : (type == null ? -1 : type.getId());
        if (DbVarTypes.isString(id)) {
            stream.writeString(defaultString);
        } else if (DbVarTypes.isLong(id)) {
            stream.writeLong(defaultLong);
        } else {
            stream.writeInt(defaultInt);
        }
    }
}
