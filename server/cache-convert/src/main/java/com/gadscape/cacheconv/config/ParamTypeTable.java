package com.gadscape.cacheconv.config;

import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.Buf;
import com.gadscape.cacheconv.core.CacheView;

import java.util.HashMap;
import java.util.Map;

/**
 * Type chars of the params of one cache (config 11: op 1 type char, or build 241's op 8 script var type id), so
 * param values inside obj/npc/loc/struct/inv entries can be typed. Unknown params read as -1 (untyped).
 */
public final class ParamTypeTable implements CfgCodec.ParamTypes {
    private final CacheView cache;
    private final Map<Integer, Integer> memo = new HashMap<>();

    public ParamTypeTable(CacheView cache) { this.cache = cache; }

    @Override public int typeOf(int param) {
        return memo.computeIfAbsent(param, p -> typeOf(cache.configRef(Arch.CFG_PARAM, p)));
    }

    /** Type char of a param entry (either layout), -1 if it has none. */
    public static int typeOf(byte[] b) {
        if (b == null) return -1;
        Buf in = new Buf(b);
        int t = -1;
        try {
            while (in.p < b.length) {
                int op = in.u8();
                switch (op) {
                    case 0: return t;
                    case 1: t = in.u8(); break;
                    case 2: in.skip(4); break;
                    case 4: break;
                    case 5: in.skipStr(); break;
                    case 7: in.skip(8); break;
                    case 8: t = CfgCodec.charOfTypeId(in.u8()); break;
                    default: return t;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return t;
        }
        return t;
    }

    /** Whether the param holds strings (op 5 default or string type). */
    public static boolean isString(byte[] b) { return typeOf(b) == 's'; }
}
