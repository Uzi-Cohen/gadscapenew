package com.gadscape.cacheconv.core;

import java.util.Arrays;

/**
 * Where a USER entry comes from, relative to vanilla 211 (spec policy 2): both caches are 211-format, so comparing
 * their bytes is the same as comparing them after the (deterministic) 211 -> 241 conversion.
 */
public enum Origin {
    /** In neither USER nor VAN211. */
    ABSENT,
    /** In USER with the same bytes as VAN211: vanilla content the user never touched (BASE's version is used). */
    VANILLA,
    /** In both, bytes differ: the user edited a vanilla entry. */
    USER_CHANGED,
    /** In USER only: the user's own content. */
    USER_ADDED,
    /** In VAN211 only: the user deleted a vanilla entry. */
    USER_REMOVED;

    public boolean isUser() { return this == USER_CHANGED || this == USER_ADDED; }

    /** Classifies one id of a kind (config file, or whole group compared file by file). */
    public static Origin of(Kind k, int id, CacheView user, CacheView van211) {
        if (k.isConfig()) return of(user.configRef(k.configGroup, id), van211.configRef(k.configGroup, id));
        return ofGroup(k.archive, id, user, van211);
    }

    /** Classifies a config entry (archive 2 group cfgGroup, file id). */
    public static Origin ofConfig(int cfgGroup, int id, CacheView user, CacheView van211) {
        return of(user.configRef(cfgGroup, id), van211.configRef(cfgGroup, id));
    }

    /** Classifies a whole group. */
    public static Origin ofGroup(int archive, int group, CacheView user, CacheView van211) {
        boolean u = user.hasGroup(archive, group), v = van211.hasGroup(archive, group);
        if (!u) return v ? USER_REMOVED : ABSENT;
        if (!v) return USER_ADDED;
        GroupData gu = user.group(archive, group), gv = van211.group(archive, group);
        if (gu == null || gv == null) return gu == gv ? VANILLA : USER_CHANGED;
        return gu.sameContent(gv) ? VANILLA : USER_CHANGED;
    }

    public static Origin of(byte[] userBytes, byte[] vanillaBytes) {
        if (userBytes == null) return vanillaBytes == null ? ABSENT : USER_REMOVED;
        if (vanillaBytes == null) return USER_ADDED;
        return Arrays.equals(userBytes, vanillaBytes) ? VANILLA : USER_CHANGED;
    }
}
