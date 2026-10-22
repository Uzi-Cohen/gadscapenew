package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.convert.ModelConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.CacheView;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index of every model of a cache by its bytes and by its decoded geometry, to find a model that is identical to a
 * given one at another id ("twin"). Geometry means what {@link ModelConv#same} compares: vertices, faces, colours,
 * textures, render types and priorities, alpha and skins. Building it decodes every model once (about 12 s for the
 * 62,714 models of BASE).
 */
public final class ModelIndex {
    private final CacheView cache;
    private final Map<Integer, List<Integer>> byBytes = new HashMap<>();
    private final Map<Long, List<Integer>> byGeometry = new HashMap<>();
    public final int models, undecodable;

    private ModelIndex(CacheView cache, int models, int undecodable) { this.cache = cache; this.models = models; this.undecodable = undecodable; }

    public static ModelIndex build(CacheView c) {
        int n = 0, bad = 0;
        Map<Integer, List<Integer>> bb = new HashMap<>();
        Map<Long, List<Integer>> bg = new HashMap<>();
        for (int id : c.groupIds(Arch.MODELS)) {
            byte[] b = c.single(Arch.MODELS, id);
            if (b == null || b.length < 2) { bad++; continue; }
            n++;
            bb.computeIfAbsent(Arrays.hashCode(b), k -> new ArrayList<>(1)).add(id);
            try { bg.computeIfAbsent(geometryHash(new ModelLoader().load(id, b)), k -> new ArrayList<>(1)).add(id); }
            catch (Throwable e) { bad++; }
        }
        c.release(Arch.MODELS);
        ModelIndex x = new ModelIndex(c, n, bad);
        x.byBytes.putAll(bb);
        x.byGeometry.putAll(bg);
        return x;
    }

    /** A model of the indexed cache with exactly these bytes (lowest id), or -1. */
    public int sameBytes(byte[] b) {
        for (int c : byBytes.getOrDefault(Arrays.hashCode(b), List.of())) if (Arrays.equals(b, cache.single(Arch.MODELS, c))) return c;
        return -1;
    }

    /** A model of the indexed cache with the same geometry (lowest id), or -1. {@code id} only names the model in errors. */
    public int sameGeometry(int id, byte[] b) {
        ModelDefinition m;
        try { m = new ModelLoader().load(id, b); } catch (Throwable e) { return -1; }
        for (int c : byGeometry.getOrDefault(geometryHash(m), List.of())) {
            byte[] cb = cache.single(Arch.MODELS, c);
            if (cb != null && ModelConv.sameDecoded(c, b, cb) == null) return c;
        }
        return -1;
    }

    /** A twin: same bytes first, then same geometry; -1 if none. */
    public int twin(int id, byte[] b) {
        int t = sameBytes(b);
        return t >= 0 ? t : sameGeometry(id, b);
    }

    /** Hash over the fields {@link ModelConv#same} compares. */
    public static long geometryHash(ModelDefinition m) {
        long h = 1469598103934665603L;
        Object[] parts = {m.vertexX, m.vertexY, m.vertexZ, m.faceIndices1, m.faceIndices2, m.faceIndices3, m.faceColors, m.faceTextures,
                m.textureCoords, m.faceRenderTypes, m.faceRenderPriorities, m.faceTransparencies, m.packedVertexGroups,
                m.packedTransparencyVertexGroups, m.textureRenderTypes, m.texIndices1, m.texIndices2, m.texIndices3};
        for (Object o : parts) {
            int x = o == null ? 0 : o instanceof int[] a ? Arrays.hashCode(a) : o instanceof short[] s ? Arrays.hashCode(s)
                    : o instanceof byte[] y ? Arrays.hashCode(y) : 0; // all primitive arrays (never identity hashes: deterministic)
            h = (h ^ x) * 1099511628211L;
        }
        h = (h ^ m.vertexCount) * 1099511628211L;
        h = (h ^ m.faceCount) * 1099511628211L;
        h = (h ^ m.numTextureFaces) * 1099511628211L;
        return (h ^ m.priority) * 1099511628211L;
    }
}
