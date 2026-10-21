package mgi.tools.jagcached;

import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.utilities.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which OSRS cache format the definition decoders read, plus the rules for opcodes they do not know.
 * <p>
 * GadScape's own cache is revision 211. The build-241 test cache (OpenRS2 2746) changed several formats:
 * <ul>
 *     <li>rev 220: object opcodes 78/79 gained a sound "retain" byte; NPC opcode 111 stopped meaning "follower"</li>
 *     <li>rev 226: sequence opcodes were renumbered (13 = skeletal id, 14 = sounds with a weight byte, 15 = frame range,
 *     16 = vertical offset, 18 = debug name, 19 = cross-world-view sounds)</li>
 *     <li>rev 233: NPC opcode 111 means render priority 2</li>
 *     <li>rev 237: interface type-6 model ids are 4-byte ints, clientscripts carry long locals/arguments,
 *     maps are grouped by region id with no names and no XTEA</li>
 * </ul>
 * Only revisions 211 and 241 are supported. The revision is decided once per cache by {@link #detect(Cache)}:
 * the system property {@value #REVISION_PROPERTY} wins, otherwise a cache with archive 24 (gamevals, added after
 * 211) is treated as 241.
 * <p>
 * Unknown opcodes: every decoder calls {@link #unknownOpcode(String, int, int)}, which throws
 * {@link UnknownOpcodeException} before reading anything. The decode loop catches it through
 * {@link #handle(UnknownOpcodeException)}: in strict mode (system property {@value #STRICT_PROPERTY}=true, used by
 * the cache tests) it is rethrown; at runtime it is logged once per type and opcode and decoding continues with the
 * next byte, which is what the old decoders silently did (correct for a payload-less flag, garbage otherwise).
 */
public final class CacheFormat {

    private static final Logger log = LoggerFactory.getLogger(CacheFormat.class);

    public static final int REV_211 = 211;
    public static final int REV_241 = 241;

    /**
     * Overrides the detected cache revision (211 or 241).
     */
    public static final String REVISION_PROPERTY = "gadscape.cache.revision";
    /**
     * When true, unknown opcodes and definitions that are not consumed exactly throw instead of being logged.
     */
    public static final String STRICT_PROPERTY = "gadscape.cache.strict";

    private static volatile int revision = REV_211;
    private static volatile boolean strict = Boolean.getBoolean(STRICT_PROPERTY);

    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private CacheFormat() {
        throw new UnsupportedOperationException();
    }

    /**
     * Decides which format a cache uses: {@value #REVISION_PROPERTY} if set, else 241 when the cache has
     * archive 24 (gamevals), or archive 22 (animayas) together with sized reference tables, else 211.
     */
    public static int detect(final Cache cache) {
        final String forced = System.getProperty(REVISION_PROPERTY);
        if (forced != null && !forced.isBlank()) {
            final int rev = Integer.parseInt(forced.trim());
            if (rev != REV_211 && rev != REV_241) {
                throw new IllegalArgumentException(REVISION_PROPERTY + " must be 211 or 241, was " + rev);
            }
            return rev;
        }
        // Archive 24 (gamevals) only exists in post-211 caches. Archive 22 (animayas) alone is not enough: a
        // GadScape cache could gain one by porting newer content, so it must come with the rev-23x reference
        // tables (flag 0x4, sizes) that GadScape's own packer never writes.
        if (cache.hasArchive(ArchiveType.GAMEVALS)) {
            return REV_241;
        }
        if (cache.hasArchive(ArchiveType.ANIMAYAS) && cache.getArchive(ArchiveType.CONFIGS).usesSizes()) {
            return REV_241;
        }
        return REV_211;
    }

    public static int revision() {
        return revision;
    }

    public static void setRevision(final int revision) {
        if (revision != REV_211 && revision != REV_241) {
            throw new IllegalArgumentException("Only cache revisions 211 and 241 are supported, not " + revision);
        }
        CacheFormat.revision = revision;
    }

    /**
     * True when decoding a build-241 cache.
     */
    public static boolean is241() {
        return revision >= REV_241;
    }

    /**
     * Rev 220+: object opcodes 78/79 carry an extra "retain" byte.
     */
    public static boolean objectSoundRetain() {
        return revision >= 220;
    }

    /**
     * Rev 226+: sequence opcode layout 13..19 and sounds with a weight byte.
     */
    public static boolean rev226Sequences() {
        return revision >= 226;
    }

    /**
     * Rev 233+: NPC opcode 111 means render priority 2 (in 211 it means "follower").
     */
    public static boolean npcOp111IsRenderPriority() {
        return revision >= 233;
    }

    /**
     * Rev 237+: interface model ids are ints and clientscripts have long locals/arguments.
     */
    public static boolean rev237Interfaces() {
        return revision >= 237;
    }

    public static boolean rev237Scripts() {
        return revision >= 237;
    }

    public static boolean isStrict() {
        return strict;
    }

    public static void setStrict(final boolean strict) {
        CacheFormat.strict = strict;
    }

    /**
     * Throws the exception that marks an opcode the decoder does not know.
     */
    public static UnknownOpcodeException unknownOpcode(final String type, final int id, final int opcode) {
        throw new UnknownOpcodeException(type, id, opcode);
    }

    /**
     * Called by a decode loop that caught {@link UnknownOpcodeException}: rethrows in strict mode, otherwise logs
     * the first occurrence per type and opcode; the caller then continues with the next opcode (legacy behaviour).
     */
    public static void handle(final UnknownOpcodeException e) {
        if (strict) {
            throw e;
        }
        if (reported.add(e.type + ':' + e.opcode)) {
            log.warn("Unknown {} opcode {} (first seen in id {}, cache revision {}); it is skipped as if it had no payload."
                    + " Further occurrences of this opcode are not logged.", e.type, e.opcode, e.id, revision);
        }
    }

    /**
     * Called when a definition's terminating opcode 0 was read: in strict mode, throws if bytes are left over
     * (which means the decoder read a field with the wrong size).
     */
    public static void checkFullyRead(final String type, final int id, final ByteBuffer buffer) {
        if (buffer.getPosition() != buffer.getBuffer().length) {
            final String message = type + " " + id + " has " + (buffer.getBuffer().length - buffer.getPosition())
                    + " unread bytes after its end opcode (cache revision " + revision + ")";
            if (strict) {
                throw new IllegalStateException(message);
            }
            if (reported.add(type + ":trailing")) {
                log.warn(message + "; further cases for this type are not logged.");
            }
        }
    }

    /**
     * Reports a structural problem: throws in strict mode, logs once per key otherwise.
     */
    public static void problem(final String key, final String message) {
        if (strict) {
            throw new IllegalStateException(message);
        }
        if (reported.add(key)) {
            log.warn(message);
        }
    }

    /**
     * Maps archive: true when groups are keyed by region id (rev 237+, files 0 = terrain, 1 = locs, no XTEA),
     * false when they are named "m{x}_{y}" / "l{x}_{y}" (rev 211).
     */
    public static boolean mapsKeyedByRegion(final Archive maps) {
        return maps != null && maps.isLoaded() && !maps.usesNames();
    }

    public static final class UnknownOpcodeException extends RuntimeException {
        private final String type;
        private final int id;
        private final int opcode;

        public UnknownOpcodeException(final String type, final int id, final int opcode) {
            super("Unknown " + type + " opcode " + opcode + " in id " + id + " (cache revision " + revision + ")", null, false, false);
            this.type = type;
            this.id = id;
            this.opcode = opcode;
        }

        public String getType() {
            return type;
        }

        public int getId() {
            return id;
        }

        public int getOpcode() {
            return opcode;
        }
    }
}
