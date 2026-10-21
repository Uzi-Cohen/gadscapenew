package com.gadscape.cache.verify;

import com.zenyte.CacheManager;
import mgi.tools.jagcached.ArchiveType;
import mgi.tools.jagcached.CacheFormat;
import mgi.tools.jagcached.GroupType;
import mgi.tools.jagcached.Helper;
import mgi.tools.jagcached.MapFiles;
import mgi.tools.jagcached.cache.Archive;
import mgi.tools.jagcached.cache.Cache;
import mgi.tools.jagcached.cache.Group;
import mgi.tools.jagcached.cache.Index;
import mgi.types.Definitions;
import mgi.types.clientscript.ClientScriptDefinitions;
import mgi.types.component.ComponentDefinitions;
import mgi.types.config.AnimationDefinitions;
import mgi.types.config.DBRowDefinition;
import mgi.types.config.DBTableDefinition;
import mgi.types.config.HitbarDefinitions;
import mgi.types.config.InventoryDefinitions;
import mgi.types.config.ObjectDefinitions;
import mgi.types.config.OverlayDefinitions;
import mgi.types.config.ParamDefinitions;
import mgi.types.config.SpotAnimationDefinition;
import mgi.types.config.StructDefinitions;
import mgi.types.config.UnderlayDefinitions;
import mgi.types.config.VarbitDefinitions;
import mgi.types.config.db.Column;
import mgi.types.config.db.ColumnValue;
import mgi.types.config.enums.EnumDefinitions;
import mgi.types.config.identitykit.IdentityKitDefinitions;
import mgi.types.config.items.ItemDefinitions;
import mgi.types.config.npcs.NPCDefinitions;
import mgi.types.draw.sprite.SpriteGroupDefinitions;
import mgi.types.skeleton.SkeletonDefinitions;
import mgi.types.worldmap.MapElementDefinitions;
import mgi.utilities.ByteBuffer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.zip.CRC32;

/**
 * Verifies that the server can read a whole cache (written for the build-241 test cache, OpenRS2 2746; also runs on
 * a vanilla rev-211 cache as a regression check). Run it with
 * <pre>
 *   ./gradlew :cache:verifyCache -Dgadscape.cache.dir=/path/to/cache [-Dgadscape.rlref.jar=/path/runelite-cache.jar]
 * </pre>
 * Decoders run in strict mode ({@link CacheFormat#setStrict(boolean)}): an unknown opcode, a definition with unread
 * bytes, or a clientscript whose code does not end exactly where its trailer says is an error. Steps:
 * <ol>
 *     <li>every reference table: protocol, flags, and for every group of every archive the stored CRC32, the
 *     compressed/uncompressed sizes (flag 0x4) and the uncompressed CRC32 (flag 0x8) against the actual data</li>
 *     <li>the server's own runtime loaders ({@link Definitions#highPriorityDefinitions} and
 *     {@link Definitions#lowPriorityDefinitions}), exactly as GameLoader/Main call them</li>
 *     <li>every file of every config type the server reads, decoded one by one</li>
 *     <li>every interface component and every clientscript</li>
 *     <li>every map square: terrain and locs found through {@link MapFiles} and parsed to the last byte</li>
 *     <li>with a RuneLite master reference jar: byte-for-byte equality of every config/interface/script/map file
 *     between jagcached and RuneLite's reader, and field-by-field comparison of the decoded values (names, model
 *     ids, animations, sizes, options, params, tile heights, loc lists...)</li>
 * </ol>
 * Exit status 0 only when there are no errors and no mismatches.
 */
public final class CacheVerifier {

    private static final int EXAMPLES = 8;

    private final Cache cache;
    private final RuneLiteReference rl;
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final Map<String, Long> mismatchCounts = new TreeMap<>();
    private final Map<String, List<String>> mismatchExamples = new TreeMap<>();
    private long comparisons;

    private CacheVerifier(final Cache cache, final RuneLiteReference rl) {
        this.cache = cache;
        this.rl = rl;
    }

    public static void main(final String[] args) throws Exception {
        final String dir = args.length > 0 ? args[0] : System.getProperty(CacheManager.CACHE_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            System.err.println("Usage: -D" + CacheManager.CACHE_DIR_PROPERTY + "=<cache dir> [-Dgadscape.rlref.jar=<jar>]");
            System.exit(2);
        }
        CacheFormat.setStrict(true);
        final long start = System.nanoTime();
        final Cache cache = CacheManager.loadCacheFiles(dir, true);
        System.out.println("Cache: " + new File(dir).getAbsolutePath());
        System.out.println("Format revision: " + CacheFormat.revision() + ", archives: " + cache.getArchiveCount()
                + ", maps keyed by region: " + MapFiles.keyedByRegion(cache));

        final String jar = System.getProperty("gadscape.rlref.jar");
        RuneLiteReference rl = null;
        if (jar != null && !jar.isBlank()) {
            rl = new RuneLiteReference(jar, new File(dir));
            System.out.println("RuneLite reference: " + jar);
        } else {
            System.out.println("RuneLite reference: none (set -Dgadscape.rlref.jar to compare values)");
        }

        final CacheVerifier verifier = new CacheVerifier(cache, rl);
        verifier.step("reference tables", verifier::referenceTables);
        verifier.step("runtime loaders", verifier::runtimeLoaders);
        verifier.step("config files", verifier::configs);
        verifier.step("interfaces", verifier::interfaces);
        verifier.step("clientscripts", verifier::scripts);
        verifier.step("maps", verifier::maps);
        if (rl != null) {
            rl.close();
        }
        final boolean ok = verifier.report();
        System.out.printf("Finished in %.1f s%n", (System.nanoTime() - start) / 1e9);
        System.exit(ok ? 0 : 1);
    }

    private interface Step {
        void run() throws Exception;
    }

    private void step(final String name, final Step step) {
        final long t = System.nanoTime();
        try {
            step.run();
        } catch (Throwable e) {
            error(name + ": " + e);
            e.printStackTrace();
        }
        System.out.printf("  [%s] %.1f s%n", name, (System.nanoTime() - t) / 1e9);
    }

    private void count(final String key, final long n) {
        counts.merge(key, n, Long::sum);
    }

    private void error(final String message) {
        errors.add(message);
        if (errors.size() <= 50) {
            System.out.println("ERROR " + message);
        }
    }

    private void mismatch(final String key, final String example) {
        mismatchCounts.merge(key, 1L, Long::sum);
        final List<String> list = mismatchExamples.computeIfAbsent(key, k -> new ArrayList<>());
        if (list.size() < EXAMPLES) {
            list.add(example);
        }
    }

    // ------------------------------------------------------------------------------------------ reference tables

    private void referenceTables() {
        final List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < cache.getArchiveCount(); i++) ids.add(i);
        for (int id : ids) {
            if (!cache.hasArchive(id)) {
                System.out.printf("    archive %3d: missing%n", id);
                count("archives missing", 1);
                continue;
            }
            final Archive archive = cache.getArchive(id);
            final Index index = cache.getIndex(id);
            long groups = 0, files = 0, crcOk = 0, sizesOk = 0, ucrcOk = 0, decoded = 0, encrypted = 0, absent = 0;
            for (Group group : archive.getGroups()) {
                groups++;
                files += group.fileCount();
                final ByteBuffer raw = index.get(group.getID());
                if (raw == null) {
                    absent++;
                    error("archive " + id + " group " + group.getID() + " is in the reference table but has no data");
                    continue;
                }
                final byte[] data = raw.getBuffer();
                final int containerLength = containerLength(data);
                if (containerLength < 0 || (data.length != containerLength && data.length != containerLength + 2)) {
                    error("archive " + id + " group " + group.getID() + ": bad container (" + data.length + " bytes)");
                    continue;
                }
                if (Helper.crc32(data, 0, containerLength) == group.getCrc()) {
                    crcOk++;
                } else {
                    error("archive " + id + " group " + group.getID() + ": crc mismatch");
                }
                if (archive.usesSizes()) {
                    if (group.getCompressedSize() != containerLength) {
                        error("archive " + id + " group " + group.getID() + ": compressed size " + group.getCompressedSize()
                                + " in reference table, container is " + containerLength);
                    }
                }
                final byte[] unpacked;
                try {
                    unpacked = Helper.decodeFilesContainer(new ByteBuffer(Arrays.copyOf(data, data.length))).getBuffer();
                } catch (RuntimeException e) {
                    if (id == ArchiveType.MAPS.getId() && archive.usesNames()) {
                        encrypted++; // rev 211 loc groups are XTEA-encrypted
                        continue;
                    }
                    error("archive " + id + " group " + group.getID() + ": cannot decompress: " + e);
                    continue;
                }
                decoded++;
                if (archive.usesSizes()) {
                    if (group.getUncompressedSize() == unpacked.length) {
                        sizesOk++;
                    } else {
                        error("archive " + id + " group " + group.getID() + ": uncompressed size " + group.getUncompressedSize()
                                + " in reference table, data is " + unpacked.length);
                    }
                }
                if (archive.usesUncompressedCrcs()) {
                    final CRC32 crc = new CRC32();
                    crc.update(unpacked);
                    if ((int) crc.getValue() == group.getUncompressedCrc()) {
                        ucrcOk++;
                    } else {
                        error("archive " + id + " group " + group.getID() + ": uncompressed crc mismatch");
                    }
                }
            }
            System.out.printf("    archive %3d: protocol %d, flags%s%s%s%s, %6d groups, %7d files, crc ok %6d, decompressed %6d%s%s%s%n",
                    id, archive.getProtocol(),
                    archive.usesNames() ? " names" : "", archive.usesWhirlpool() ? " digests" : "",
                    archive.usesSizes() ? " sizes" : "", archive.usesUncompressedCrcs() ? " ucrc" : "",
                    groups, files, crcOk, decoded,
                    archive.usesSizes() ? ", sizes ok " + sizesOk : "",
                    archive.usesUncompressedCrcs() ? ", ucrc ok " + ucrcOk : "",
                    encrypted > 0 ? ", encrypted (not checked) " + encrypted : "");
            count("archives present", 1);
            count("groups checked", groups);
            count("files listed", files);
            if (absent > 0) count("groups without data", absent);
        }
    }

    private static int containerLength(final byte[] data) {
        if (data.length < 5) return -1;
        final int compression = data[0] & 0xFF;
        final int length = ((data[1] & 0xFF) << 24) | ((data[2] & 0xFF) << 16) | ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
        if (compression > 2 || length < 0) return -1;
        return 5 + (compression == 0 ? 0 : 4) + length;
    }

    // ------------------------------------------------------------------------------------------ runtime loaders

    private void runtimeLoaders() throws Exception {
        final List<Class<?>> classes = new ArrayList<>();
        classes.addAll(Arrays.asList(Definitions.highPriorityDefinitions));
        classes.addAll(Arrays.asList(Definitions.lowPriorityDefinitions));
        for (Class<?> type : classes) {
            final long t = System.nanoTime();
            try {
                final Object instance = Reflect.construct(type);
                if (type == ItemDefinitions.class) {
                    ((ItemDefinitions) instance).load(cache); // load() also posts a plugin event
                } else {
                    ((Definitions) instance).load();
                }
                System.out.printf("    %-28s loaded in %5.2f s%n", type.getSimpleName(), (System.nanoTime() - t) / 1e9);
            } catch (Throwable e) {
                error("runtime load of " + type.getSimpleName() + ": " + e);
                e.printStackTrace(System.out);
            }
        }
        count("runtime: objects", Arrays.stream(ObjectDefinitions.definitions).filter(Objects::nonNull).count());
        count("runtime: npcs", countNonNull(i -> NPCDefinitions.get(i), 65535));
        count("runtime: items", countNonNull(i -> ItemDefinitions.get(i), 65535));
        count("runtime: sequences", countNonNull(i -> AnimationDefinitions.get(i), 65535));
        count("runtime: spotanims", SpotAnimationDefinition.definitions.size());
        count("runtime: enums", EnumDefinitions.definitions.size());
        count("runtime: frames (skeleton defs)", SkeletonDefinitions.getDefinitions().size());
    }

    private static long countNonNull(final Function<Integer, Object> getter, final int max) {
        long n = 0;
        for (int i = 0; i < max; i++) {
            if (getter.apply(i) != null) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ config files

    /**
     * One field comparison between our definition and RuneLite's.
     */
    private record Cmp(String ours, String rl, Function<Object, Object> rlMap, BiPredicate<Object, Object> when) {
        static Cmp of(final String both) {
            return new Cmp(both, both, null, null);
        }

        static Cmp of(final String ours, final String rl) {
            return new Cmp(ours, rl, null, null);
        }

        static Cmp map(final String ours, final String rl, final Function<Object, Object> rlMap) {
            return new Cmp(ours, rl, rlMap, null);
        }

        static Cmp when(final String ours, final String rl, final BiPredicate<Object, Object> when) {
            return new Cmp(ours, rl, null, when);
        }
    }

    private record ConfigSpec(String name, GroupType group, Class<?> ours, String rlLoader, boolean rlBytesFirst,
                              boolean rlNoId, List<Cmp> fields) {
    }

    private static final Function<Object, Object> ANIM = v -> ((Number) v).intValue() == 65535 ? -1 : v;

    private static Function<Object, Object> opsText() {
        return ops -> {
            final String[] out = new String[5];
            final List<?> list = (List<?>) Reflect.get(ops, "ops");
            for (int i = 0; i < Math.min(5, list.size()); i++) {
                final Object op = list.get(i);
                out[i] = op == null ? null : (String) Reflect.get(op, "text");
            }
            return out;
        };
    }

    private static Function<Object, Object> unsignedShorts() {
        return v -> {
            if (v == null) return null;
            final short[] s = (short[]) v;
            final int[] out = new int[s.length];
            for (int i = 0; i < s.length; i++) out[i] = s[i] & 0xFFFF;
            return out;
        };
    }

    private static List<ConfigSpec> configSpecs() {
        final List<ConfigSpec> specs = new ArrayList<>();
        specs.add(new ConfigSpec("item", GroupType.ITEM, ItemDefinitions.class, "ItemLoader", false, false, List.of(
                Cmp.of("name"), Cmp.of("inventoryModelId", "inventoryModel"),
                Cmp.of("primaryMaleModel", "maleModel0"), Cmp.of("secondaryMaleModel", "maleModel1"),
                Cmp.of("tertiaryMaleModel", "maleModel2"), Cmp.of("primaryFemaleModel", "femaleModel0"),
                Cmp.of("secondaryFemaleModel", "femaleModel1"), Cmp.of("tertiaryFemaleModel", "femaleModel2"),
                Cmp.of("primaryMaleHeadModelId", "maleHeadModel"), Cmp.of("secondaryMaleHeadModelId", "maleHeadModel2"),
                Cmp.of("primaryFemaleHeadModelId", "femaleHeadModel"), Cmp.of("secondaryFemaleHeadModelId", "femaleHeadModel2"),
                Cmp.of("maleOffset"), Cmp.of("femaleOffset"), Cmp.of("price", "cost"), Cmp.of("isStackable", "stackable"),
                Cmp.of("notedTemplate"), Cmp.of("notedId", "notedID"), Cmp.of("placeholderId"),
                Cmp.of("placeholderTemplate", "placeholderTemplateId"), Cmp.of("bindId", "boughtId"),
                Cmp.of("bindTemplateId", "boughtTemplateId"), Cmp.of("isMembers", "members"),
                Cmp.of("grandExchange", "geTradeable"), Cmp.of("cacheTradeable", "tradeable"), Cmp.of("bronzeman"),
                Cmp.of("inventoryOptions", "interfaceOptions"), Cmp.of("zoom", "zoom2d"), Cmp.of("modelPitch", "xan2d"),
                Cmp.of("modelRoll", "yan2d"), Cmp.of("modelYaw", "zan2d"), Cmp.of("offsetX", "xOffset2d"),
                Cmp.of("offsetY", "yOffset2d"),
                // our default is 0, RuneLite's -1: compare only when the opcode was present
                Cmp.when("wearPos1", "wearPos1", (o, r) -> ((Number) Reflect.get(r, "wearPos1")).intValue() != -1),
                Cmp.when("wearPos2", "wearPos2", (o, r) -> ((Number) Reflect.get(r, "wearPos2")).intValue() != -1),
                Cmp.when("wearPos3", "wearPos3", (o, r) -> ((Number) Reflect.get(r, "wearPos3")).intValue() != -1),
                Cmp.of("category"), Cmp.of("teamId", "team"), Cmp.of("resizeX"), Cmp.of("resizeY"), Cmp.of("resizeZ"),
                Cmp.of("ambient"), Cmp.map("contrast", "contrast", v -> ((Number) v).intValue() * 5),
                Cmp.of("stackIds", "countObj"), Cmp.of("stackAmounts", "countCo"), Cmp.of("parameters", "params"),
                Cmp.of("originalColours", "colorFind"), Cmp.of("replacementColours", "colorReplace"),
                Cmp.of("originalTextureIds", "textureFind"), Cmp.of("replacementTextureIds", "textureReplace"),
                Cmp.of("shiftClickIndex", "shiftClickDropIndex"), Cmp.of("holdingWhitelist"),
                // RuneLite zeroes the weight of stackable items after decoding
                new Cmp("cacheWeight", "weight", null, (o, r) -> ((Number) Reflect.get(r, "stackable")).intValue() != 1))));
        specs.add(new ConfigSpec("npc", GroupType.NPC, NPCDefinitions.class, "NpcLoader", false, false, List.of(
                Cmp.of("name"), Cmp.of("models"), Cmp.of("chatModels", "chatheadModels"), Cmp.of("size"),
                Cmp.map("standAnimation", "standingAnimation", ANIM), Cmp.map("walkAnimation", "walkingAnimation", ANIM),
                Cmp.map("rotateLeftAnimation", "idleRotateLeftAnimation", ANIM),
                Cmp.map("rotateRightAnimation", "idleRotateRightAnimation", ANIM),
                Cmp.map("rotate180Animation", "rotate180Animation", ANIM),
                Cmp.map("rotate90Animation", "rotateLeftAnimation", ANIM),
                Cmp.map("rotate270Animation", "rotateRightAnimation", ANIM),
                Cmp.map("field2039", "runAnimation", ANIM), Cmp.map("field2043", "crawlAnimation", ANIM),
                Cmp.of("combatLevel"), Cmp.of("varbit", "varbitId"), Cmp.of("varp", "varpIndex"),
                Cmp.of("transmogrifiedIds", "configs"), Cmp.of("stats"), Cmp.of("height"), Cmp.of("isFollower"),
                Cmp.of("lowPriorityFollowerOps"), Cmp.of("renderPriority"), Cmp.of("resizeX", "widthScale"),
                Cmp.of("resizeY", "heightScale"), Cmp.of("direction", "rotationSpeed"),
                Cmp.of("minimapVisible", "isMinimapVisible"), Cmp.of("clickable", "rotationFlag"),
                Cmp.of("clippedMovement", "isInteractable"), Cmp.of("headIconArchiveIds"), Cmp.of("headIconSpriteIndex"),
                Cmp.of("ambience", "ambient"), Cmp.of("contrast"), Cmp.of("parameters", "params"),
                Cmp.map("options", "ops", opsText()),
                Cmp.of("originalColours", "recolorToFind"), Cmp.of("replacementColours", "recolorToReplace"),
                Cmp.of("originalTextures", "retextureToFind"), Cmp.of("replacementTextures", "retextureToReplace"),
                // RuneLite fills in a computed footprint when the opcode is absent
                Cmp.when("footprintSize", "footprintSize", (o, r) -> ((Number) o).intValue() != -1))));
        specs.add(new ConfigSpec("object", GroupType.OBJECT, ObjectDefinitions.class, "ObjectLoader", false, false, List.of(
                Cmp.of("name"), Cmp.of("models", "objectModels"), Cmp.of("types", "objectTypes"), Cmp.of("sizeX"),
                Cmp.of("sizeY"), Cmp.of("clipType", "interactType"), Cmp.of("projectileClip", "blocksProjectile"),
                Cmp.of("animationId", "animationID"), Cmp.of("mapSceneId", "mapSceneID"), Cmp.of("mapIconId", "mapAreaId"),
                Cmp.of("varbit", "varbitID"), Cmp.of("varp", "varpID"), Cmp.of("transformedIds", "configChangeDest"),
                Cmp.of("ambientSoundId"), Cmp.of("ambientSoundDistance"), Cmp.of("anInt456", "ambientSoundChangeTicksMin"),
                Cmp.of("anInt457", "ambientSoundChangeTicksMax"), Cmp.of("anIntArray100", "ambientSoundIds"),
                Cmp.map("accessBlockFlag", "blockingMask", v -> ((Number) v).intValue() & 0xFF),
                Cmp.of("modelSizeX"), Cmp.of("modelSizeHeight"), Cmp.of("modelSizeY"), Cmp.of("offsetX"),
                Cmp.of("offsetHeight"), Cmp.of("offsetY"), Cmp.of("isRotated"), Cmp.of("clipped", "shadow"),
                Cmp.of("obstructsGround"), Cmp.of("hollow", "isHollow"), Cmp.of("contouredGround"),
                Cmp.of("nonFlatShading", "mergeNormals"), Cmp.of("modelClipped"), Cmp.of("decorDisplacement"),
                Cmp.of("ambient"), Cmp.of("contrast"), Cmp.map("options", "ops", opsText()),
                Cmp.of("parameters", "params"),
                Cmp.map("modelColours", "recolorToFind", unsignedShorts()),
                Cmp.map("replacementColours", "recolorToReplace", unsignedShorts()),
                Cmp.of("modelTexture", "retextureToFind"), Cmp.of("replacementTexture", "textureToReplace"),
                // RuneLite derives these after decoding when the opcode is absent
                Cmp.when("optionsInvisible", "wallOrDoor", (o, r) -> ((Number) o).intValue() != -1),
                Cmp.when("supportItems", "supportsItems", (o, r) -> ((Number) o).intValue() != -1))));
        final Function<Object, Object> handItem = v -> {
            final int raw = ((Number) v).intValue();
            return raw > 0 ? raw - 512 : raw;
        };
        specs.add(new ConfigSpec("sequence", GroupType.SEQUENCE, AnimationDefinitions.class, "SequenceLoader", false, false, List.of(
                Cmp.of("frameIds", "frameIDs"), Cmp.of("frameLengths"), Cmp.of("frameStep"),
                Cmp.of("mergedBoneGroups", "interleaveLeave"), Cmp.of("stretches"), Cmp.of("forcedPriority"),
                Cmp.map("leftHandItem", "leftHandItem", handItem), Cmp.map("rightHandItem", "rightHandItem", handItem),
                Cmp.of("iterations", "maxLoops"), Cmp.of("precedenceAnimating"), Cmp.of("priority"), Cmp.of("replyMode"),
                Cmp.of("extraFrameIds", "chatFrameIds"), Cmp.of("animMayaID"), Cmp.of("animMayaStart"),
                Cmp.of("animMayaEnd"), Cmp.of("animMayaMasks"), Cmp.of("verticalOffset"), Cmp.of("debugName"))));
        specs.add(new ConfigSpec("spotanim", GroupType.SPOTANIM, SpotAnimationDefinition.class, "SpotAnimLoader", false, false, List.of(
                Cmp.of("modelId"), Cmp.of("animationId"), Cmp.of("widthScale", "resizeX"), Cmp.of("heightScale", "resizeY"),
                Cmp.of("orientation", "rotaton"), Cmp.of("ambient"), Cmp.of("contrast"),
                Cmp.of("recolorFrom", "recolorToFind"), Cmp.of("recolorTo", "recolorToReplace"),
                Cmp.of("retextureFrom", "textureToFind"), Cmp.of("retextureTo", "textureToReplace"),
                Cmp.of("debugName"), Cmp.of("clickable"))));
        specs.add(new ConfigSpec("enum", GroupType.ENUM, EnumDefinitions.class, "EnumLoader", false, false, List.of(
                Cmp.of("defaultInt"), Cmp.of("defaultLong"), Cmp.of("size"),
                Cmp.when("defaultString", "defaultString", (o, r) -> Reflect.get(r, "defaultString") != null))));
        specs.add(new ConfigSpec("struct", GroupType.STRUCT, StructDefinitions.class, "StructLoader", false, false, List.of(
                Cmp.of("parameters", "params"))));
        specs.add(new ConfigSpec("param", GroupType.PARAMS, ParamDefinitions.class, "ParamLoader", true, true, List.of(
                Cmp.of("defaultInt"), Cmp.of("defaultString"), Cmp.of("defaultLong"), Cmp.of("autoDisable", "isMembers"))));
        specs.add(new ConfigSpec("varbit", GroupType.VARBIT, VarbitDefinitions.class, "VarbitLoader", false, false, List.of(
                Cmp.of("baseVar", "index"), Cmp.of("startBit", "leastSignificantBit"), Cmp.of("endBit", "mostSignificantBit"))));
        specs.add(new ConfigSpec("inventory", GroupType.INV, InventoryDefinitions.class, "InventoryLoader", false, false, List.of(
                Cmp.of("size"), Cmp.of("parameters", "params"))));
        specs.add(new ConfigSpec("identkit", GroupType.IDENTKIT, IdentityKitDefinitions.class, "KitLoader", false, false, List.of(
                Cmp.of("bodyPartId"), Cmp.of("modelIds", "models"),
                Cmp.map("selectable", "nonSelectable", v -> !((Boolean) v)),
                Cmp.map("headModels", "chatheadModels", v -> v == null ? null : Arrays.copyOf((int[]) v, 5)))));
        specs.add(new ConfigSpec("overlay", GroupType.OVERLAY, OverlayDefinitions.class, "OverlayLoader", false, false, List.of(
                Cmp.of("color", "rgbColor"), Cmp.of("texture"), Cmp.of("hidden", "hideUnderlay"),
                Cmp.of("otherColor", "secondaryRgbColor"))));
        specs.add(new ConfigSpec("underlay", GroupType.UNDERLAY, UnderlayDefinitions.class, "UnderlayLoader", false, false, List.of(
                Cmp.of("rgb", "color"))));
        specs.add(new ConfigSpec("hitbar", GroupType.HITBAR, HitbarDefinitions.class, "HealthBarLoader", false, false, List.of(
                Cmp.of("secondarySprite", "healthBarFrontSpriteId"), Cmp.of("primarySprite", "healthBarBackSpriteId"),
                Cmp.of("size", "healthScale"), Cmp.of("extraWidth", "healthBarPadding"),
                Cmp.of("anInt2098", "field3277"), Cmp.of("anInt2099", "field3278"), Cmp.of("anInt2100", "field3275"))));
        specs.add(new ConfigSpec("mapelement", GroupType.MAP_LABELS, MapElementDefinitions.class, "AreaLoader", true, false, List.of(
                Cmp.of("spriteId"), Cmp.of("field3306", "field3294"), Cmp.of("text", "name"), Cmp.of("colour", "textColor"),
                Cmp.of("textSize", "textScale"), Cmp.of("options", "field3298"), Cmp.of("optionName", "field3308"),
                Cmp.of("tooltipId", "category"))));
        specs.add(new ConfigSpec("dbtable", GroupType.DBTABLE, DBTableDefinition.class, "DBTableLoader", false, false, List.of()));
        specs.add(new ConfigSpec("dbrow", GroupType.DBROW, DBRowDefinition.class, "DBRowLoader", false, false, List.of(
                Cmp.of("tableId"))));
        return specs;
    }

    private void configs() throws Exception {
        final Archive configs = cache.getArchive(ArchiveType.CONFIGS);
        for (ConfigSpec spec : configSpecs()) {
            final Group group = configs.findGroupByID(spec.group.getId());
            if (group == null) {
                System.out.printf("    %-10s group %d not in this cache%n", spec.name, spec.group.getId());
                continue;
            }
            final Map<Integer, byte[]> rlFiles = rl == null ? null : rl.files(ArchiveType.CONFIGS.getId(), spec.group.getId());
            long decoded = 0, failed = 0, compared = 0, bytesDiffer = 0;
            for (mgi.tools.jagcached.cache.File file : group.getFiles()) {
                if (file == null || file.getData() == null) continue;
                final int id = file.getID();
                final byte[] bytes = file.getData().getBuffer().clone();
                if (bytes.length == 0) continue;
                final Object ours;
                try {
                    ours = Reflect.construct(spec.ours, id, new ByteBuffer(bytes.clone()));
                    decoded++;
                } catch (Throwable e) {
                    failed++;
                    error(spec.name + " " + id + ": " + e);
                    continue;
                }
                if (rlFiles == null) continue;
                final byte[] rlBytes = rlFiles.get(id);
                if (!Arrays.equals(bytes, rlBytes)) {
                    bytesDiffer++;
                    mismatch(spec.name + ".bytes", "id " + id + ": jagcached and RuneLite read different bytes");
                    continue;
                }
                final Object ref;
                if (spec.rlNoId) {
                    ref = rl.load(spec.rlLoader, (Object) rlBytes);
                } else if (spec.rlBytesFirst) {
                    ref = rl.load(spec.rlLoader, rlBytes, id);
                } else {
                    ref = rl.load(spec.rlLoader, id, rlBytes);
                }
                printSample(spec.name, id, ours, ref);
                if (ref == null) { // RuneLite returns null for enums that are only the end opcode
                    count("config " + spec.name + " files that are only the end opcode (RuneLite skips them)", 1);
                    continue;
                }
                compared++;
                compareFields(spec.name, id, ours, ref, spec.fields);
                extraCompare(spec.name, id, ours, ref);
            }
            System.out.printf("    %-10s %6d decoded, %d failed%s%n", spec.name, decoded, failed,
                    rlFiles == null ? "" : ", " + compared + " compared with RuneLite" + (bytesDiffer > 0 ? ", " + bytesDiffer + " byte differences" : ""));
            count("config " + spec.name, decoded);
        }
    }

    /**
     * A few human-readable values, ours next to RuneLite's, so the log shows what was compared.
     */
    private static final Map<String, Map<Integer, String[][]>> SAMPLES = Map.of(
            "item", Map.of(995, new String[][]{{"name", "name"}, {"inventoryModelId", "inventoryModel"}},
                    4151, new String[][]{{"name", "name"}, {"inventoryModelId", "inventoryModel"}, {"primaryMaleModel", "maleModel0"}},
                    11802, new String[][]{{"name", "name"}, {"inventoryModelId", "inventoryModel"}, {"primaryMaleModel", "maleModel0"}},
                    34000, new String[][]{{"name", "name"}, {"inventoryModelId", "inventoryModel"}}),
            "npc", Map.of(2042, new String[][]{{"name", "name"}, {"models", "models"}, {"standAnimation", "standingAnimation"}},
                    8061, new String[][]{{"name", "name"}, {"models", "models"}, {"standAnimation", "standingAnimation"}},
                    13433, new String[][]{{"name", "name"}, {"models", "models"}, {"standAnimation", "standingAnimation"}}),
            "object", Map.of(1276, new String[][]{{"name", "name"}, {"models", "objectModels"}, {"sizeX", "sizeX"}},
                    10355, new String[][]{{"name", "name"}, {"models", "objectModels"}, {"clipType", "interactType"}}),
            "sequence", Map.of(808, new String[][]{{"frameLengths", "frameLengths"}, {"animMayaID", "animMayaID"}},
                    10600, new String[][]{{"animMayaID", "animMayaID"}, {"animMayaStart", "animMayaStart"}, {"animMayaEnd", "animMayaEnd"}}));

    private void printSample(final String type, final int id, final Object ours, final Object ref) {
        final Map<Integer, String[][]> byId = SAMPLES.get(type);
        if (byId == null || !byId.containsKey(id)) return;
        final StringBuilder sb = new StringBuilder("      sample " + type + " " + id + ":");
        for (String[] f : byId.get(id)) {
            sb.append(' ').append(f[0]).append(" ours=").append(abbreviate(Reflect.normalize(Reflect.get(ours, f[0]))));
            if (ref != null) sb.append(" runelite=").append(abbreviate(Reflect.normalize(Reflect.get(ref, f[1]))));
            sb.append(';');
        }
        System.out.println(sb);
    }

    private void compareFields(final String type, final int id, final Object ours, final Object ref, final List<Cmp> fields) {
        for (Cmp cmp : fields) {
            final Object a = Reflect.get(ours, cmp.ours);
            Object b = Reflect.get(ref, cmp.rl);
            if (cmp.when != null && !cmp.when.test(a, ref)) continue;
            if (cmp.rlMap != null && b != null) b = cmp.rlMap.apply(b);
            comparisons++;
            final Object na = Reflect.normalize(a);
            final Object nb = Reflect.normalize(b);
            if (!Objects.equals(na, nb)) {
                mismatch(type + "." + cmp.ours, "id " + id + ": ours=" + abbreviate(na) + " runelite=" + abbreviate(nb));
            }
        }
    }

    private static String abbreviate(final Object value) {
        final String s = String.valueOf(value);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    /**
     * Comparisons that are not a simple field pair.
     */
    private void extraCompare(final String type, final int id, final Object ours, final Object ref) {
        switch (type) {
            case "sequence" -> {
                // sounds: ours keep frame-based and skeletal sounds in two maps, RuneLite in one
                final Map<Long, Object> mine = new TreeMap<>();
                for (String field : new String[]{"soundEffects", "animMayaFrameSounds"}) {
                    final Map<?, ?> map = (Map<?, ?>) Reflect.get(ours, field);
                    if (map != null) map.forEach((k, v) -> mine.put(((Number) k).longValue(), sound(v)));
                }
                // RuneLite keeps a multimap (several sounds per frame); our definition keeps the last one per frame
                final Map<Long, Object> theirs = new TreeMap<>();
                final Object multimap = Reflect.get(ref, "frameSounds");
                if (multimap != null) {
                    final Map<?, ?> map = (Map<?, ?>) Reflect.call(multimap, "asMap");
                    map.forEach((k, v) -> {
                        Object last = null;
                        for (Object o : (java.util.Collection<?>) v) last = o;
                        theirs.put(((Number) k).longValue(), sound(last));
                        if (((java.util.Collection<?>) v).size() > 1) count("sequence frames with several sounds (we keep the last)", 1);
                    });
                }
                comparisons++;
                if (!mine.equals(theirs)) {
                    mismatch("sequence.sounds", "id " + id + ": ours=" + abbreviate(mine) + " runelite=" + abbreviate(theirs));
                }
            }
            case "enum" -> {
                final Map<Long, Object> mine = new TreeMap<>();
                final Map<?, ?> values = (Map<?, ?>) Reflect.get(ours, "values");
                if (values != null) values.forEach((k, v) -> mine.put(((Number) k).longValue(), Reflect.normalize(v)));
                final Map<Long, Object> theirs = new TreeMap<>();
                final int[] keys = (int[]) Reflect.get(ref, "keys");
                final int[] ints = (int[]) Reflect.get(ref, "intVals");
                final String[] strings = (String[]) Reflect.get(ref, "stringVals");
                final long[] longs = (long[]) Reflect.get(ref, "longVals");
                if (keys != null) {
                    for (int i = 0; i < keys.length; i++) {
                        theirs.put((long) keys[i], ints != null ? (Object) (long) ints[i] : strings != null ? strings[i] : (Object) longs[i]);
                    }
                }
                comparisons++;
                if (!mine.equals(theirs)) {
                    mismatch("enum.values", "id " + id + ": ours=" + abbreviate(mine) + " runelite=" + abbreviate(theirs));
                }
                final char ourKey = (char) Reflect.get(ours, "keyType");
                final Object rlKey = Reflect.get(ref, "keyType");
                if (rlKey != null) {
                    comparisons++;
                    if (ourKey != (char) Reflect.call(rlKey, "getKeyChar")) {
                        mismatch("enum.keyType", "id " + id + ": ours=" + ourKey + " runelite=" + rlKey);
                    }
                }
            }
            case "dbrow" -> {
                // RuneLite reads every non-string cell as an int, so only columns without long types are compared
                final Map<?, ?> columns = (Map<?, ?>) Reflect.get(ours, "columns");
                final Object[][] rlValues = (Object[][]) Reflect.get(ref, "columnValues");
                @SuppressWarnings("unchecked") final Map<Integer, int[]> typeIds = (Map<Integer, int[]>) Reflect.get(ours, "typeIdMap");
                for (Map.Entry<?, ?> e : columns.entrySet()) {
                    final int columnId = (Integer) e.getKey();
                    final int[] ids = typeIds.get(columnId);
                    boolean hasLong = false;
                    for (int t : ids) hasLong |= mgi.types.config.db.DbVarTypes.isLong(t);
                    if (hasLong) {
                        count("dbrow columns with long cells (not compared)", 1);
                        continue;
                    }
                    final Column column = (Column) e.getValue();
                    final List<Object> mine = new ArrayList<>();
                    for (int i = 0; i < column.values.size(); i++) {
                        final ColumnValue v = column.getForIdx(i);
                        mine.add(mgi.types.config.db.DbVarTypes.isString(v.typeId) ? v.defaultString : (Object) (long) v.defaultInt);
                    }
                    comparisons++;
                    final Object theirs = Reflect.normalize(rlValues == null ? null : rlValues[columnId]);
                    if (!mine.equals(theirs)) {
                        mismatch("dbrow.column", "id " + id + " column " + columnId + ": ours=" + abbreviate(mine) + " runelite=" + abbreviate(theirs));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static Object sound(final Object sound) {
        if (sound == null) return null;
        return Arrays.asList(Reflect.normalize(Reflect.get(sound, "id")), Reflect.normalize(Reflect.get(sound, "loops")),
                Reflect.normalize(Reflect.get(sound, "location")), Reflect.normalize(Reflect.get(sound, "retain")),
                Reflect.normalize(Reflect.get(sound, "weight")));
    }

    // ------------------------------------------------------------------------------------------ interfaces

    private static final List<Cmp> COMPONENT_FIELDS = List.of(
            Cmp.of("type"), Cmp.of("contentType"), Cmp.of("x", "originalX"), Cmp.of("y", "originalY"),
            Cmp.of("width", "originalWidth"), Cmp.of("height", "originalHeight"), Cmp.of("hidden", "isHidden"),
            Cmp.of("modelId"), Cmp.of("spriteId"), Cmp.of("text"), Cmp.of("actions"), Cmp.of("accessMask", "clickMask"),
            Cmp.of("targetVerb"), Cmp.of("onLoadListener"), Cmp.of("onOpListener"), Cmp.of("onVarTransmitListener"),
            Cmp.of("varTransmitTriggers"), Cmp.of("invTransmitTriggers"), Cmp.of("statTransmitTriggers"));

    private void interfaces() throws Exception {
        final Archive interfaces = cache.getArchive(ArchiveType.INTERFACES);
        long components = 0, groups = 0, compared = 0, if1 = 0, if3 = 0, models = 0;
        for (Group g : interfaces.getGroups()) {
            final Group group = interfaces.findGroupByID(g.getID());
            groups++;
            final Map<Integer, byte[]> rlFiles = rl == null ? null : rl.files(ArchiveType.INTERFACES.getId(), group.getID());
            for (mgi.tools.jagcached.cache.File file : group.getFiles()) {
                if (file == null || file.getData() == null || file.getData().getBuffer().length == 0) continue;
                final int id = group.getID() << 16 | file.getID();
                final byte[] bytes = file.getData().getBuffer().clone();
                final ComponentDefinitions ours;
                try {
                    ours = new ComponentDefinitions(id, new ByteBuffer(bytes.clone()));
                    components++;
                    if (bytes[0] == -1) if3++; else if1++;
                    if (ours.type == 6) models++;
                } catch (Throwable e) {
                    error("component " + group.getID() + ":" + file.getID() + ": " + e);
                    continue;
                }
                if (rlFiles == null) continue;
                final byte[] rlBytes = rlFiles.get(file.getID());
                if (!Arrays.equals(bytes, rlBytes)) {
                    mismatch("component.bytes", group.getID() + ":" + file.getID());
                    continue;
                }
                compareFields("component", id, ours, rl.load("InterfaceLoader", id, rlBytes), COMPONENT_FIELDS);
                compared++;
            }
        }
        System.out.printf("    %d interfaces, %d components (%d if1, %d if3, %d model components)%s%n", groups, components,
                if1, if3, models, rl == null ? "" : ", " + compared + " compared with RuneLite");
        count("interfaces", groups);
        count("components", components);
    }

    // ------------------------------------------------------------------------------------------ clientscripts

    private void scripts() throws Exception {
        final Archive scripts = cache.getArchive(ArchiveType.CLIENTSCRIPTS);
        long decoded = 0, compared = 0, withLongs = 0;
        for (Group g : scripts.getGroups()) {
            final Group group = scripts.findGroupByID(g.getID());
            final mgi.tools.jagcached.cache.File file = group.findFileByID(0);
            if (file == null || file.getData() == null) continue;
            final byte[] bytes = file.getData().getBuffer().clone();
            final ClientScriptDefinitions ours;
            try {
                ours = (ClientScriptDefinitions) Reflect.construct(ClientScriptDefinitions.class, group.getID(), new ByteBuffer(bytes.clone()));
                decoded++;
                if (ours.longArgs != null || ours.getLongArgumentCount() > 0 || ours.getLocalLongCount() > 0) withLongs++;
            } catch (Throwable e) {
                error("clientscript " + group.getID() + ": " + e);
                continue;
            }
            if (rl == null) continue;
            final Map<Integer, byte[]> rlFiles = rl.files(ArchiveType.CLIENTSCRIPTS.getId(), group.getID());
            final byte[] rlBytes = rlFiles == null ? null : rlFiles.get(0);
            if (!Arrays.equals(bytes, rlBytes)) {
                mismatch("clientscript.bytes", String.valueOf(group.getID()));
                continue;
            }
            final Object ref;
            try {
                ref = rl.load("ScriptLoader", group.getID(), rlBytes);
            } catch (RuntimeException e) {
                if (ours.instructions.length == 0) {
                    // e.g. build 241 script 0 (two bytes): a placeholder neither decoder can read
                    count("clientscript placeholders (too short for a trailer, RuneLite fails on them too)", 1);
                    continue;
                }
                throw e;
            }
            compareFields("clientscript", group.getID(), ours, ref, List.of(
                    Cmp.of("instructions"), Cmp.of("intArgs", "intOperands"), Cmp.of("stringArgs", "stringOperands"),
                    Cmp.of("intArgumentCount", "localIntCount"), Cmp.of("stringArgumentCount", "localObjCount"),
                    Cmp.of("intArgsCount", "intArgCount"), Cmp.of("stringArgsCount", "objArgCount"),
                    Cmp.of("localLongCount"), Cmp.of("longArgumentCount", "longArgCount"),
                    Cmp.when("longArgs", "longOperands", (o, r) -> o != null)));
            compared++;
        }
        System.out.printf("    %d clientscripts decoded (%d use longs)%s%n", decoded, withLongs,
                rl == null ? "" : ", " + compared + " compared with RuneLite");
        count("clientscripts", decoded);
    }

    // ------------------------------------------------------------------------------------------ maps

    /**
     * One map square decoded the way the server's Region does it, keeping everything RuneLite also exposes.
     */
    private static final class Square {
        final int[][][] heights = new int[5][64][64];
        final int[][][] attr = new int[5][64][64];
        final int[][][] settings = new int[5][64][64];
        final int[][][] overlay = new int[5][64][64];
        final int[][][] underlay = new int[5][64][64];
        final List<List<Long>> locs = new ArrayList<>();
        boolean underwater;
        int levelFourLocs;
    }

    private static Square parse(final ByteBuffer terrain, final ByteBuffer locs) {
        final Square sq = new Square();
        parseLevels(terrain, sq, 0, 4);
        if (terrain.getPosition() < terrain.getBuffer().length) {
            // build 241: flags byte after the four levels; bit 0 = a fifth (underwater) level follows
            final int flags = terrain.readUnsignedByte();
            if ((flags & ~1) != 0) {
                throw new IllegalStateException("unknown terrain flags " + flags);
            }
            if ((flags & 1) != 0) {
                sq.underwater = true;
                parseLevels(terrain, sq, 4, 5);
            }
        }
        if (terrain.getPosition() != terrain.getBuffer().length) {
            throw new IllegalStateException("terrain has " + (terrain.getBuffer().length - terrain.getPosition()) + " unread bytes");
        }
        parseLocs(locs, sq);
        return sq;
    }

    private static void parseLevels(final ByteBuffer terrain, final Square sq, final int from, final int to) {
        for (int z = from; z < to; z++) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    sq.heights[z][x][y] = Integer.MIN_VALUE;
                    while (true) {
                        final int attribute = terrain.readUnsignedShort();
                        if (attribute == 0) {
                            break;
                        } else if (attribute == 1) {
                            sq.heights[z][x][y] = terrain.readUnsignedByte();
                            break;
                        } else if (attribute <= 49) {
                            sq.attr[z][x][y] = attribute;
                            sq.overlay[z][x][y] = terrain.readShort();
                        } else if (attribute <= 81) {
                            sq.settings[z][x][y] = attribute - 49;
                        } else {
                            sq.underlay[z][x][y] = attribute - 81;
                        }
                    }
                }
            }
        }
    }

    private static void parseLocs(final ByteBuffer locs, final Square sq) {
        if (locs != null) {
            int objectId = -1;
            int incr;
            while ((incr = locs.readHugeSmart()) != 0) {
                objectId += incr;
                int location = 0;
                int incr2;
                while ((incr2 = locs.readUnsignedSmart()) != 0) {
                    location += incr2 - 1;
                    final int data = locs.readUnsignedByte();
                    if ((location >> 12) > 3) sq.levelFourLocs++;
                    sq.locs.add(Arrays.asList((long) objectId, (long) (data >> 2), (long) (data & 3),
                            (long) (location >> 6 & 63), (long) (location & 63), (long) (location >> 12 & 3)));
                }
            }
            if (locs.getPosition() != locs.getBuffer().length) {
                throw new IllegalStateException("locs have " + (locs.getBuffer().length - locs.getPosition()) + " unread bytes");
            }
        }
    }

    private void maps() throws Exception {
        final Archive maps = cache.getArchive(ArchiveType.MAPS);
        final int objectCount = ObjectDefinitions.definitions.length;
        long squares = 0, withLocs = 0, locs = 0, badLocIds = 0, badTypes = 0, compared = 0, otherFiles = 0;
        final boolean keyed = MapFiles.keyedByRegion(cache);
        final int[] fileCounts = new int[8];
        for (int regionId = 0; regionId < 0x10000; regionId++) {
            if (!MapFiles.hasRegion(cache, regionId)) continue;
            final ByteBuffer terrain = MapFiles.terrain(cache, regionId);
            if (terrain == null) {
                error("region " + regionId + ": group exists but has no terrain");
                continue;
            }
            ByteBuffer locBuffer;
            try {
                locBuffer = MapFiles.locs(cache, regionId);
            } catch (RuntimeException e) {
                locBuffer = null; // rev 211 without keys
                count("maps: loc groups not decryptable (rev 211 without XTEA keys)", 1);
            }
            final Square sq;
            try {
                sq = parse(terrain, locBuffer);
            } catch (Throwable e) {
                error("region " + regionId + ": " + e);
                continue;
            }
            squares++;
            if (sq.underwater) count("map squares with an underwater level", 1);
            if (sq.levelFourLocs > 0) count("map locs on level 4 (skipped by the server)", sq.levelFourLocs);
            if (locBuffer != null) withLocs++;
            locs += sq.locs.size();
            for (List<Long> loc : sq.locs) {
                if (loc.get(0) < 0 || loc.get(0) >= objectCount || ObjectDefinitions.get(loc.get(0).intValue()) == null) badLocIds++;
                if (loc.get(1) > 22) badTypes++;
            }
            if (keyed) {
                final Group group = maps.findGroupByID(regionId);
                fileCounts[Math.min(7, group.fileCount())]++;
                if (group.fileCount() > 2) otherFiles += group.fileCount() - 2;
            }
            if (regionId == 12850) { // Lumbridge
                System.out.printf("      sample map square 12850 (Lumbridge): %d locs, level-0 height at (0,0)=%d (32,32)=%d (63,63)=%d%n",
                        sq.locs.size(), sq.heights[0][0][0], sq.heights[0][32][32], sq.heights[0][63][63]);
            }
            if (rl != null) {
                compared += compareSquare(regionId, sq, keyed);
            }
        }
        if (badLocIds > 0) error(badLocIds + " locs reference object ids without a definition");
        if (badTypes > 0) error(badTypes + " locs have a shape type above 22");
        System.out.printf("    %d map squares, %d with locs, %d locs total%s%s%n", squares, withLocs, locs,
                keyed ? ", groups by file count " + Arrays.toString(fileCounts) : "",
                rl == null ? "" : ", " + compared + " squares compared with RuneLite");
        count("map squares", squares);
        count("map squares with locs", withLocs);
        count("map locs", locs);
    }

    private int compareSquare(final int regionId, final Square sq, final boolean keyed) throws Exception {
        final Map<Integer, byte[]> files = keyed ? rl.files(ArchiveType.MAPS.getId(), regionId) : null;
        if (!keyed) {
            return 0; // RuneLite needs XTEA keys for rev-211 locs; terrain-only comparison is not worth the extra code
        }
        if (files == null) {
            mismatch("map.group", "region " + regionId + " missing in RuneLite");
            return 0;
        }
        final Object mapDef = rl.load("MapLoader", regionId >> 8, regionId & 255, files.get(0));
        final Object[][][] tiles = (Object[][][]) Reflect.get(mapDef, "tiles");
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    final Object tile = tiles[z][x][y];
                    final Integer h = (Integer) Reflect.get(tile, "height");
                    comparisons += 5;
                    if ((h == null ? Integer.MIN_VALUE : h) != sq.heights[z][x][y]) {
                        mismatch("map.height", "region " + regionId + " " + z + "," + x + "," + y + ": ours=" + sq.heights[z][x][y] + " runelite=" + h);
                    }
                    if (((Number) Reflect.get(tile, "settings")).intValue() != sq.settings[z][x][y]) {
                        mismatch("map.settings", "region " + regionId + " " + z + "," + x + "," + y);
                    }
                    if (((Number) Reflect.get(tile, "overlayId")).intValue() != sq.overlay[z][x][y]) {
                        mismatch("map.overlay", "region " + regionId + " " + z + "," + x + "," + y);
                    }
                    if ((((Number) Reflect.get(tile, "underlayId")).intValue() & 0xFFFF) != sq.underlay[z][x][y]) {
                        mismatch("map.underlay", "region " + regionId + " " + z + "," + x + "," + y);
                    }
                    if (((Number) Reflect.get(tile, "attrOpcode")).intValue() != sq.attr[z][x][y]) {
                        mismatch("map.overlayShape", "region " + regionId + " " + z + "," + x + "," + y);
                    }
                }
            }
        }
        final byte[] locBytes = files.get(1);
        final List<List<Long>> theirs = new ArrayList<>();
        if (locBytes != null) {
            final Object locDef = rl.load("LocationsLoader", regionId >> 8, regionId & 255, locBytes);
            for (Object loc : (List<?>) Reflect.get(locDef, "locations")) {
                final Object pos = Reflect.get(loc, "position");
                theirs.add(Arrays.asList(((Number) Reflect.get(loc, "id")).longValue(), ((Number) Reflect.get(loc, "type")).longValue(),
                        ((Number) Reflect.get(loc, "orientation")).longValue(), ((Number) Reflect.get(pos, "x")).longValue(),
                        ((Number) Reflect.get(pos, "y")).longValue(), ((Number) Reflect.get(pos, "z")).longValue()));
            }
        }
        comparisons++;
        if (!theirs.equals(sq.locs)) {
            mismatch("map.locs", "region " + regionId + ": ours " + sq.locs.size() + " locs, runelite " + theirs.size());
        }
        return 1;
    }

    // ------------------------------------------------------------------------------------------ report

    private boolean report() {
        System.out.println();
        System.out.println("=== Counts");
        counts.forEach((k, v) -> System.out.printf("  %-60s %,d%n", k, v));
        if (rl != null) {
            System.out.println("=== RuneLite comparison: " + String.format("%,d", comparisons) + " values compared, "
                    + mismatchCounts.values().stream().mapToLong(Long::longValue).sum() + " mismatches");
            mismatchCounts.forEach((k, v) -> {
                System.out.printf("  %-40s %,d%n", k, v);
                mismatchExamples.get(k).forEach(e -> System.out.println("      " + e));
            });
        }
        System.out.println("=== Errors: " + errors.size());
        errors.stream().limit(200).forEach(e -> System.out.println("  " + e));
        final boolean ok = errors.isEmpty() && mismatchCounts.isEmpty();
        System.out.println(ok ? "RESULT: OK" : "RESULT: FAILED");
        return ok;
    }
}
