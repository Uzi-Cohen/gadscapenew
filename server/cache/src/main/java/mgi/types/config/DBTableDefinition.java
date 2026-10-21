package mgi.types.config;

import com.zenyte.CacheManager;
import mgi.tools.jagcached.ArchiveType;
import mgi.tools.jagcached.GroupType;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.File;
import mgi.tools.jagcached.cache.Group;
import mgi.types.Definitions;
import mgi.utilities.ByteBuffer;
import mgi.types.config.db.Column;
import mgi.types.config.db.ColumnValue;
import net.runelite.cache.util.ScriptVarType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;

public class DBTableDefinition implements Definitions, Cloneable {
	private static final Logger log = LoggerFactory.getLogger(DBTableDefinition.class);
	private static DBTableDefinition[] definitions;
	private int numColumns = -1;
	private final LinkedHashMap<Integer, Integer> typeCountMap = new LinkedHashMap<>();
	/**
	 * Raw var type ids per column (ScriptVarType.forId returns null for ids the bundled RuneLite 1.9.8 enum lacks).
	 */
	private final LinkedHashMap<Integer, int[]> typeIdMap = new LinkedHashMap<>();
	private final LinkedHashMap<Integer, Integer> settingMap = new LinkedHashMap<>();
	private final LinkedHashMap<Integer, Integer> fieldCountMap = new LinkedHashMap<>();
	private final LinkedHashMap<Integer, Boolean> defaultValueMap = new LinkedHashMap<>();

	public static DBTableDefinition get(final int id) {
		if (id < 0 || id >= definitions.length) throw new IllegalArgumentException();
		return definitions[id];
	}

	private DBTableDefinition(final int id, final ByteBuffer buffer) {
		this.id = id;
		decode(buffer);
	}

	private final int id;
	private ScriptVarType[][] types;
	private Column[] defaultColumnValues;

	@Override
	public void load() {
		final Cache cache = CacheManager.getCache();
		final Archive configs = cache.getArchive(ArchiveType.CONFIGS);
		final Group dbtables = configs.findGroupByID(GroupType.DBTABLE);
		definitions = new DBTableDefinition[dbtables.getHighestFileId()];
		for (int id = 0; id < dbtables.getHighestFileId(); id++) {
			final File file = dbtables.findFileByID(id);
			if (file == null) {
				continue;
			}
			final ByteBuffer buffer = file.getData();
			if (buffer == null) {
				continue;
			}
			if (buffer.remaining() < 1) {
				continue;
			}
			definitions[id] = new DBTableDefinition(id, buffer);
		}
	}

	public DBTableDefinition clone() throws CloneNotSupportedException {
		return (DBTableDefinition) super.clone();
	}

	@Override
	public void decode(final ByteBuffer buffer) {
		while (true) {
			final int opcode = buffer.readUnsignedByte();
			if (opcode == 0) {
				mgi.tools.jagcached.CacheFormat.checkFullyRead("dbtable", id, buffer);
				return;
			}
			try {
				decode(buffer, opcode);
			} catch (final mgi.tools.jagcached.CacheFormat.UnknownOpcodeException e) {
				mgi.tools.jagcached.CacheFormat.handle(e);
			}
		}
	}

	@Override
	public void decode(final ByteBuffer buffer, int opcode) {
		if (opcode == 1) {
			numColumns = buffer.readUnsignedByte();
			types = new ScriptVarType[numColumns][];
			defaultColumnValues = null;
			while(true) {
				int setting = buffer.readUnsignedByte();

				if(setting == 255)
					return;

				int columnId = setting & 127;
				settingMap.put(columnId, setting);

				boolean hasDefault = (setting & 128) != 0;
				defaultValueMap.put(columnId, hasDefault);
				int numTypes = buffer.readUnsignedByte();
				typeCountMap.put(columnId, numTypes);
				ScriptVarType[] columnTypes = new ScriptVarType[numTypes];

				final int[] typeIds = new int[numTypes];
				for(int i = 0; i < columnTypes.length; ++i) {
					typeIds[i] = buffer.readUnsignedSmart();
					columnTypes[i] = ScriptVarType.forId(typeIds[i]);
				}

				types[columnId] = columnTypes;
				typeIdMap.put(columnId, typeIds);
				if (hasDefault) {
					if (defaultColumnValues == null) {
						defaultColumnValues = new Column[types.length];
					}
					int fieldCount = buffer.readUnsignedSmart();
					fieldCountMap.put(columnId, fieldCount);
					Column column = new Column(columnId);
					decodeColumnDefaults(column, buffer);
					defaultColumnValues[columnId] = column;
				} else {
					fieldCountMap.put(columnId, 0);
				}
			}
		}
		throw mgi.tools.jagcached.CacheFormat.unknownOpcode("dbtable", id, opcode);
	}

	private void decodeColumnDefaults(Column column, ByteBuffer stream) {
		int fieldCount = fieldCountMap.getOrDefault(column.getId(), 0);
		final int[] typeIds = typeIdMap.get(column.getId());
		int typesCount = typeIds.length;

		for(int fieldIndex = 0; fieldIndex < fieldCount; ++fieldIndex) {
			for(int typeIndex = 0; typeIndex < typesCount; ++typeIndex) {
				int valuesIndex = fieldIndex * typesCount + typeIndex;
				column.addValue(valuesIndex, ColumnValue.read(typeIds[typeIndex], stream));
			}
		}
	}

	private void encodeColumnDefaults(Column column, ByteBuffer stream) {
		int fieldCount = fieldCountMap.getOrDefault(column.getId(), 0);
		ScriptVarType[] scriptTypes = types[column.getId()];
		int typesCount = scriptTypes.length;

		for(int fieldIndex = 0; fieldIndex < fieldCount; ++fieldIndex) {
			for(int typeIndex = 0; typeIndex < typesCount; ++typeIndex) {
				ScriptVarType type = scriptTypes[typeIndex];
				int valuesIndex = fieldIndex * typesCount + typeIndex;
				ColumnValue value = column.getForIdx(valuesIndex);
				if(value.type != type)
					throw new IllegalStateException();
				value.write(stream); // string, long or int by base type
			}
		}
	}

	@Override
	public ByteBuffer encode() {
		final ByteBuffer buffer = new ByteBuffer(1132);
		buffer.writeUnsignedByte(numColumns);

		for (Integer columnId : defaultValueMap.keySet()) {
			/* Write setting, +128 indicates it has a default */
			if (defaultValueMap.getOrDefault(columnId, false)) {
				buffer.writeUnsignedByte(columnId + 128);
			} else {
				buffer.writeUnsignedByte(columnId);
			}

			int typeCount = typeCountMap.get(columnId);
			buffer.writeUnsignedByte(typeCount);

			for (int typeIndex = 0; typeIndex < types[columnId].length; typeIndex++) {
				ScriptVarType type = types[columnId][typeIndex];
				buffer.writeUnsignedIntSmart(type.getId());
			}

			if (defaultValueMap.getOrDefault(columnId, false)) {
				int defaultFieldCount = fieldCountMap.get(columnId);
				buffer.writeUnsignedIntSmart(defaultFieldCount);
				Column column = defaultColumnValues[columnId];
				encodeColumnDefaults(column, buffer);
			}
		}

		buffer.writeUnsignedByte(255);
		buffer.writeUnsignedByte(0);
		return buffer;
	}

	@Override
	public void pack() {
		CacheManager.getCache().getArchive(ArchiveType.CONFIGS).findGroupByID(GroupType.DBTABLE).addFile(new File(id, encode()));
	}

	public DBTableDefinition(int id) {
		this.id = id;
	}

	public DBTableDefinition() {
		this.id = 0;
	}

	public int getId() {
		return id;
	}

	public Object[] getDefaultColumnValues() {
		return defaultColumnValues;
	}

	public ScriptVarType[][] getTypes() {
		return types;
	}

}
