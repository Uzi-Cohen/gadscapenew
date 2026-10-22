package com.gadscape.cacheconv.convert;

import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;

import java.util.Arrays;

/**
 * Model encodings 211 -> 241, ported from rev241/tools/ModelConv. Build 241 only ships models in the two newest
 * encodings (trailer FF FE and FF FD). The old encoding and the FF FF one hold the same data blocks in the same order;
 * only the header differs. {@link #convert} rewrites the header and appends the right trailer; {@link #toRev241} also
 * adds the face-depth tail ({@link ModelTail}) and checks the geometry is unchanged.
 */
public final class ModelConv {
    private ModelConv() {}

    private static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }

    /** Converted bytes, or null if the model is already in a 241 encoding (FF FE / FF FD). */
    public static byte[] convert(byte[] b) {
        int L = b.length;
        if (b[L - 2] == -1 && (b[L - 1] == -2 || b[L - 1] == -3)) return null; // already a 241 encoding
        if (b[L - 2] == -1 && b[L - 1] == -1) {            // FF FF -> FF FD: 21-byte header -> 24-byte header
            int h = L - 23; byte[] hd = Arrays.copyOfRange(b, h, h + 21);
            int vc = u16(hd, 0), vgroups = hd[10] & 255;
            byte[] n = new byte[h + 26]; System.arraycopy(b, 0, n, 0, h);
            System.arraycopy(hd, 0, n, h, 11); n[h + 11] = 0;                  // counts and flags, no skeletal groups
            System.arraycopy(hd, 11, n, h + 12, 10);                           // x, y, z, face index, texture coord lengths
            int vg = vgroups == 1 ? vc : 0; n[h + 22] = (byte) (vg >> 8); n[h + 23] = (byte) vg;
            n[h + 24] = -1; n[h + 25] = -3; return n;
        }
        int h = L - 18; byte[] hd = Arrays.copyOfRange(b, h, L);              // old -> FF FE: 18-byte header -> 21-byte header
        int vc = u16(hd, 0), vgroups = hd[9] & 255;
        byte[] n = new byte[h + 23]; System.arraycopy(b, 0, n, 0, h);
        System.arraycopy(hd, 0, n, h, 10); n[h + 10] = 0;                      // counts and flags, no skeletal groups
        System.arraycopy(hd, 10, n, h + 11, 8);                                // x, y, z, face index lengths
        int vg = vgroups == 1 ? vc : 0; n[h + 19] = (byte) (vg >> 8); n[h + 20] = (byte) vg;
        n[h + 21] = -1; n[h + 22] = -2; return n;
    }

    /** "FF FF -> FF FD", "old -> FF FE" or "241" for a model's source encoding. */
    public static String kind(byte[] b) {
        int L = b.length;
        if (b[L - 2] == -1 && (b[L - 1] == -2 || b[L - 1] == -3)) return "241";
        return b[L - 2] == -1 && b[L - 1] == -1 ? "FF FF -> FF FD" : "old -> FF FE";
    }

    /** Null if both decode (RuneLite ModelLoader) to the same geometry, colours, textures, priorities, alpha and skins; else what differs. */
    public static String same(ModelDefinition a, ModelDefinition b) {
        if (a.vertexCount != b.vertexCount || a.faceCount != b.faceCount || a.numTextureFaces != b.numTextureFaces) return "counts";
        if (!Arrays.equals(a.vertexX, b.vertexX) || !Arrays.equals(a.vertexY, b.vertexY) || !Arrays.equals(a.vertexZ, b.vertexZ)) return "vertices";
        if (!Arrays.equals(a.faceIndices1, b.faceIndices1) || !Arrays.equals(a.faceIndices2, b.faceIndices2) || !Arrays.equals(a.faceIndices3, b.faceIndices3)) return "faces";
        if (!Arrays.equals(a.faceColors, b.faceColors) || !Arrays.equals(a.faceTextures, b.faceTextures) || !Arrays.equals(a.textureCoords, b.textureCoords)) return "colours/textures";
        if (!Arrays.equals(a.faceRenderTypes, b.faceRenderTypes) || !Arrays.equals(a.faceRenderPriorities, b.faceRenderPriorities) || a.priority != b.priority) return "render types/priorities";
        if (!Arrays.equals(a.faceTransparencies, b.faceTransparencies) || !Arrays.equals(a.packedVertexGroups, b.packedVertexGroups) || !Arrays.equals(a.packedTransparencyVertexGroups, b.packedTransparencyVertexGroups)) return "alpha/skins";
        if (!Arrays.equals(a.textureRenderTypes, b.textureRenderTypes) || !Arrays.equals(a.texIndices1, b.texIndices1) || !Arrays.equals(a.texIndices2, b.texIndices2) || !Arrays.equals(a.texIndices3, b.texIndices3)) return "texture triangles";
        return null;
    }

    /** {@link #same} on the two encodings; "decode ..." if either does not decode. */
    public static String sameDecoded(int id, byte[] before, byte[] after) {
        try { return same(new ModelLoader().load(id, before), new ModelLoader().load(id, after)); }
        catch (Throwable e) { return "decode " + e; }
    }

    /**
     * Full 211 -> 241 model conversion as cacheU got it (ModelConv then ModelTail): header/trailer rewrite when needed,
     * then the face-depth tail. With {@code check}, the converted model must decode to the same geometry as the source
     * (IllegalStateException otherwise). Returns the input unchanged (same array) when nothing was needed.
     */
    public static byte[] toRev241(int id, byte[] b, boolean check) {
        byte[] c = convert(b);
        if (c != null && check) {
            String why = sameDecoded(id, b, c);
            if (why != null) throw new IllegalStateException("model " + id + " not converted: " + why);
        }
        byte[] m = c != null ? c : b;
        byte[] t = ModelTail.fix(id, m);
        return t != null ? t : m;
    }
}
