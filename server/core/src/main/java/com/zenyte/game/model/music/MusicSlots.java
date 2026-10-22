package com.zenyte.game.model.music;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mgi.types.config.DBRowDefinition;
import mgi.types.config.db.Column;
import mgi.types.config.db.ColumnValue;
import mgi.types.config.enums.Enums;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The music tab's track list: which track a jukebox slot is, its name and its unlock bit.
 * <p>
 * The 211 cache describes the list with enum 812 (slot -> name) and enum 819 (slot -> {@code (varpIndex + 1) << 14 |
 * bit}, -1 = always unlocked). Build 241 empties both enums and lists the tracks in db table 44 ("music"): the
 * jukebox (script 9290) creates child {@code i} for the {@code i}-th row of the column-9 index for value 0 (every row
 * that is not hidden), in row-id order, so slot {@code i + 1} here is jukebox child {@code i}. Per row: c1 display name,
 * c4 midi, c5 {@code (varpIndex 1-based, bit)} unlock bit (script 252/7305; varp list {@link
 * com.zenyte.game.world.entity.player.MusicHandler#VARP_IDS}), c6 = 1 unlocked for everyone, c9 = 1 hidden.
 * The same slot numbering is used whichever cache is loaded, with 1-based slots and the 819 unlock encoding.
 */
public final class MusicSlots {
    private static final Logger log = LoggerFactory.getLogger(MusicSlots.class);
    /** Build 241 music db table. */
    public static final int MUSIC_TABLE = 44;
    private static final int NAME_COLUMN = 1;
    private static final int HINT_COLUMN = 2;
    private static final int MIDI_COLUMN = 4;
    private static final int UNLOCK_COLUMN = 5;
    private static final int ALWAYS_UNLOCKED_COLUMN = 6;
    private static final int HIDDEN_COLUMN = 9;
    /** 211 music varps (enum 819 indices 1..23); build 241 adds four more (script 7305). */
    private static final int ENUM_UNLOCK_VARPS = 23;

    private static volatile MusicSlots instance;

    private final boolean table;
    private final int[] rows;
    private final String[] names;
    private final int[] midis;
    private final int[] unlocks;
    private final String[] hints;
    private final Object2IntOpenHashMap<String> slotByName = new Object2IntOpenHashMap<>();
    private final Int2IntOpenHashMap slotByMidi = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap slotByUnlock = new Int2IntOpenHashMap();

    private MusicSlots(final boolean table, final int[] rows, final String[] names, final int[] midis, final int[] unlocks,
                       final String[] hints) {
        this.table = table;
        this.rows = rows;
        this.names = names;
        this.midis = midis;
        this.unlocks = unlocks;
        this.hints = hints;
        slotByName.defaultReturnValue(-1);
        slotByMidi.defaultReturnValue(-1);
        slotByUnlock.defaultReturnValue(-1);
        for (int slot = 1; slot < names.length; slot++) {
            if (names[slot] == null) {
                continue;
            }
            slotByName.putIfAbsent(names[slot], slot);
            slotByName.putIfAbsent(normalise(names[slot]), slot);
            if (midis[slot] >= 0) {
                slotByMidi.putIfAbsent(midis[slot], slot);
            }
            if (unlocks[slot] != -1) {
                slotByUnlock.putIfAbsent(unlocks[slot], slot);
            }
        }
    }

    public static MusicSlots get() {
        MusicSlots slots = instance;
        if (slots == null) {
            synchronized (MusicSlots.class) {
                slots = instance;
                if (slots == null) {
                    slots = instance = load();
                }
            }
        }
        return slots;
    }

    private static MusicSlots load() {
        if (Enums.MUSIC_SLOT_NAME_ENUM.getSize() > 0) {
            int max = 0;
            for (final int key : Enums.MUSIC_SLOT_NAME_ENUM.getValues().keySet()) {
                max = Math.max(max, key);
            }
            final int[] rows = new int[max + 1];
            final String[] names = new String[max + 1];
            final int[] midis = new int[max + 1];
            final int[] unlocks = new int[max + 1];
            java.util.Arrays.fill(rows, -1);
            java.util.Arrays.fill(midis, -1);
            java.util.Arrays.fill(unlocks, -1);
            Enums.MUSIC_SLOT_NAME_ENUM.getValues().forEach((slot, name) -> {
                if (slot < 0) {
                    return;
                }
                names[slot] = name;
                unlocks[slot] = Enums.MUSIC_SLOT_INTERFACE_ENUM.getValue(slot).orElse(-1);
            });
            return new MusicSlots(false, rows, names, midis, unlocks, new String[max + 1]);
        }
        final List<DBRowDefinition> tableRows = DBRowDefinition.tableRows.get(MUSIC_TABLE);
        if (tableRows == null || tableRows.isEmpty()) {
            log.warn("No music track list: enum 812 is empty and db table {} is missing.", MUSIC_TABLE);
            return new MusicSlots(false, new int[] {-1}, new String[1], new int[] {-1}, new int[] {-1}, new String[1]);
        }
        final List<DBRowDefinition> visible = tableRows.stream()
                .filter(row -> intValue(row, HIDDEN_COLUMN, 0, 0) == 0)
                .sorted(java.util.Comparator.comparingInt(DBRowDefinition::getId))
                .toList();
        final int size = visible.size() + 1;
        final int[] rows = new int[size];
        final String[] names = new String[size];
        final int[] midis = new int[size];
        final int[] unlocks = new int[size];
        final String[] hints = new String[size];
        rows[0] = midis[0] = unlocks[0] = -1;
        for (int i = 0; i < visible.size(); i++) {
            final DBRowDefinition row = visible.get(i);
            final int slot = i + 1;
            rows[slot] = row.getId();
            names[slot] = stringValue(row, NAME_COLUMN);
            hints[slot] = stringValue(row, HINT_COLUMN);
            midis[slot] = intValue(row, MIDI_COLUMN, 0, -1);
            final int index = intValue(row, UNLOCK_COLUMN, 0, -1);
            final int bit = intValue(row, UNLOCK_COLUMN, 1, -1);
            if (intValue(row, ALWAYS_UNLOCKED_COLUMN, 0, 0) == 1 || index < 1 || bit < 0) {
                unlocks[slot] = -1;
            } else {
                unlocks[slot] = index << 14 | bit;
            }
        }
        log.info("Music track list: {} jukebox tracks from db table {}.", visible.size(), MUSIC_TABLE);
        return new MusicSlots(true, rows, names, midis, unlocks, hints);
    }

    private static int intValue(final DBRowDefinition row, final int column, final int index, final int absent) {
        final Column c = row.columns.get(column);
        if (c == null) {
            return absent;
        }
        final ColumnValue value = c.getForIdx(index);
        return value == null ? absent : value.defaultInt;
    }

    private static String stringValue(final DBRowDefinition row, final int column) {
        final Column c = row.columns.get(column);
        if (c == null) {
            return null;
        }
        final ColumnValue value = c.getForIdx(0);
        return value == null ? null : value.defaultString;
    }

    /** Names compared without punctuation: 241 renamed e.g. "Magic, Magic, Magic" to "Magic Magic Magic". */
    private static String normalise(final String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** True when the list comes from the build-241 music db table. */
    public boolean isTable() {
        return table;
    }

    /** Number of music unlock varps the loaded cache uses (the first N of MusicHandler.VARP_IDS). */
    public int unlockVarpCount(final int available) {
        return table ? available : Math.min(available, ENUM_UNLOCK_VARPS);
    }

    /** The slot of {@code music}: by name, then by name without punctuation, then (241 table) by midi. */
    public OptionalInt slotOf(final Music music) {
        int slot = slotByName.getInt(music.getName());
        if (slot == -1) {
            slot = slotByName.getInt(normalise(music.getName()));
        }
        if (slot == -1 && table) {
            slot = slotByMidi.get(music.getMusicId());
        }
        return slot == -1 ? OptionalInt.empty() : OptionalInt.of(slot);
    }

    public OptionalInt slotOf(final String name) {
        final Music music = Music.map.get(name);
        if (music != null) {
            return slotOf(music);
        }
        int slot = slotByName.getInt(name);
        if (slot == -1) {
            slot = slotByName.getInt(normalise(name));
        }
        return slot == -1 ? OptionalInt.empty() : OptionalInt.of(slot);
    }

    /** The slot whose unlock encoding is {@code unlock} ({@code varpIndex << 14 | bit}). */
    public OptionalInt slotOfUnlock(final int unlock) {
        final int slot = slotByUnlock.get(unlock);
        return slot == -1 ? OptionalInt.empty() : OptionalInt.of(slot);
    }

    public Optional<String> nameOf(final int slot) {
        if (slot < 1 || slot >= names.length) {
            return Optional.empty();
        }
        return Optional.ofNullable(names[slot]);
    }

    /** The server track of {@code slot}: by name, then by name without punctuation, then (241 table) by midi. */
    public Music musicOf(final int slot) {
        final String name = nameOf(slot).orElse(null);
        if (name == null) {
            return null;
        }
        Music music = Music.map.get(name);
        if (music != null) {
            return music;
        }
        final String key = normalise(name);
        for (final Music track : Music.map.values()) {
            if (normalise(track.getName()).equals(key) || (table && track.getMusicId() == midis[slot])) {
                return track;
            }
        }
        return null;
    }

    /** {@code (varpIndex 1-based) << 14 | bit}, -1 for always unlocked; empty for an unknown slot. */
    public OptionalInt unlockOf(final int slot) {
        if (slot < 1 || slot >= unlocks.length || names[slot] == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(unlocks[slot]);
    }

    /** The unlock hint the build-241 table gives for {@code slot} (e.g. "in Varrock."), or null. */
    public String hintOf(final int slot) {
        if (slot < 1 || slot >= hints.length) {
            return null;
        }
        return hints[slot];
    }

    /**
     * The value a build-241 playlist varbit stores for {@code slot}: {@code varpIndex * 100 + bit} of its unlock bit
     * (script 9303 looks the track up by c5), or 0 when the track has none.
     */
    public int playlistCodeOf(final int slot) {
        final int unlock = unlockOf(slot).orElse(-1);
        if (unlock == -1) {
            return 0;
        }
        return (unlock >> 14 & 16383) * 100 + (unlock & 16383);
    }

    /** The slot a build-241 playlist varbit value names, or empty. */
    public OptionalInt slotOfPlaylistCode(final int code) {
        if (code <= 0) {
            return OptionalInt.empty();
        }
        return slotOfUnlock((code / 100) << 14 | code % 100);
    }

    /** The music db row of {@code slot} (build 241), or -1. */
    public int rowOf(final int slot) {
        if (slot < 1 || slot >= rows.length) {
            return -1;
        }
        return rows[slot];
    }

    /** The midi the build-241 table lists for {@code slot}, or -1. */
    public int midiOf(final int slot) {
        if (slot < 1 || slot >= midis.length) {
            return -1;
        }
        return midis[slot];
    }

    /** The highest slot (slots are 1..size). */
    public int size() {
        return names.length - 1;
    }
}
