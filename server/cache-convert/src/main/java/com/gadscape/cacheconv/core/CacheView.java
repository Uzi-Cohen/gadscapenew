package com.gadscape.cacheconv.core;

/**
 * Read-only access to a JS5 cache: the inputs (BASE, USER, VAN211, OLD241) and the output under construction
 * ({@link OutCache}, which shows pending writes). All ids are plain ints; config entries live in archive 2.
 */
public interface CacheView {
    /** Short label for messages ("BASE", "USER", "OUT", ...). */
    String label();

    boolean hasArchive(int archive);
    /** Group ids of an archive, ascending (empty if the archive does not exist). */
    int[] groupIds(int archive);
    boolean hasGroup(int archive, int group);
    /** Highest group id + 1 (0 if empty). */
    default int groupLimit(int archive) { int[] ids = groupIds(archive); return ids.length == 0 ? 0 : ids[ids.length - 1] + 1; }

    /** The group's files, or null if the group does not exist or cannot be read. */
    GroupData group(int archive, int group);
    /** Same with an XTEA key (map loc files of 211 caches). Never cached. */
    GroupData group(int archive, int group, int[] xtea);

    /** Name hash of a group as the reference table holds it (-1 if none / absent). */
    int groupName(int archive, int group);
    /** Group id whose name hash matches the name, or -1. */
    int groupIdByName(int archive, String name);
    boolean usesNames(int archive);

    /** File ids of a group without decoding it, ascending (empty if absent). */
    int[] fileIds(int archive, int group);

    /** Drops decoded data cached for this archive (call after walking a big archive). */
    void release(int archive);

    // ------------------------------------------------------------------ conveniences

    default boolean hasFile(int archive, int group, int file) {
        for (int f : fileIds(archive, group)) if (f == file) return true;
        return false;
    }
    /** Copy of one file's bytes, or null. */
    default byte[] file(int archive, int group, int file) { GroupData g = group(archive, group); return g == null ? null : g.data(file); }
    /** One file's bytes without copying (read only!), or null. */
    default byte[] fileRef(int archive, int group, int file) { GroupData g = group(archive, group); return g == null ? null : g.dataRef(file); }

    /** Config entry (archive 2, group cfgGroup, file id), copied; null if absent. */
    default byte[] config(int cfgGroup, int id) { return file(Arch.CONFIGS, cfgGroup, id); }
    default byte[] configRef(int cfgGroup, int id) { return fileRef(Arch.CONFIGS, cfgGroup, id); }
    default int[] configIds(int cfgGroup) { return fileIds(Arch.CONFIGS, cfgGroup); }
    default boolean hasConfig(int cfgGroup, int id) { GroupData g = group(Arch.CONFIGS, cfgGroup); return g != null && g.has(id); }

    /** Bytes of file 0 of a single-file group (models, scripts, frames' files are separate), copied; null if absent. */
    default byte[] single(int archive, int group) { GroupData g = group(archive, group); return g == null || g.fileCount() == 0 ? null : g.dataAt(0).clone(); }
}
