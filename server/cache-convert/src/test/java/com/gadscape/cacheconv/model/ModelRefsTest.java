package com.gadscape.cacheconv.model;

import com.gadscape.cacheconv.convert.ConfigConv;
import com.gadscape.cacheconv.convert.InterfaceConv;
import com.gadscape.cacheconv.core.Arch;
import com.gadscape.cacheconv.core.BufOut;
import com.gadscape.cacheconv.core.GroupData;
import com.gadscape.cacheconv.core.IdMap;
import com.gadscape.cacheconv.core.Kind;
import com.gadscape.cacheconv.core.Options;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRefsTest {
    private static IdMap moves(int... fromTo) {
        IdMap m = new IdMap();
        for (int i = 0; i < fromTo.length; i += 2) m.record(Kind.MODEL, fromTo[i], fromTo[i + 1], "test");
        return m;
    }

    @Test void objIntModels() {
        // 44 inventory, name, 45 male wear + offset, 249 params with an int that looks like a model id, end
        byte[] b = new BufOut().u8(44).i32(1234).u8(2).str("Obligator").u8(45).i32(5).u8(7)
                .u8(249).u8(1).u8(0).u24(9000).i32(1234).u8(0).done();
        assertArrayEquals(new int[]{1234, 5}, ModelRefs.models(Arch.CFG_OBJ, 1, b, false));
        byte[] r = ModelRefs.remap(moves(1234, 65000), Arch.CFG_OBJ, 1, b);
        assertNotSame(b, r);
        assertArrayEquals(new int[]{65000, 5}, ModelRefs.models(Arch.CFG_OBJ, 1, r, false));
        int diff = 0;
        for (int i = 0; i < b.length; i++) if (b[i] != r[i]) diff++;
        assertTrue(diff <= 2, "only the inventory model bytes change (the param value stays)");
        assertSame(b, ModelRefs.remap(moves(4321, 65000), Arch.CFG_OBJ, 1, b), "nothing to map: same array");
    }

    @Test void convertedItemIsWalkable() {
        // 211 item: 1 inventory model (u16), 23 male wear + offset, name; ConfigConv widens them to 44/45
        byte[] b211 = new BufOut().u8(1).u16(40000).u8(23).u16(500).u8(3).u8(2).str("x").u8(0).done();
        byte[] b241 = ConfigConv.item(1, b211);
        assertArrayEquals(new int[]{40000, 500}, ModelRefs.models(Arch.CFG_OBJ, 1, b241, false));
    }

    @Test void npcLocSpotKit() {
        byte[] npc = new BufOut().u8(61).u8(2).i32(10).i32(11).u8(62).u8(1).i32(12).u8(2).str("n").u8(0).done();
        assertArrayEquals(new int[]{10, 11, 12}, ModelRefs.models(Arch.CFG_NPC, 1, npc, false));
        byte[] loc = new BufOut().u8(6).u8(1).i32(20).u8(10).u8(7).u8(1).i32(21).u8(78).u16(1).u8(2).u8(0).u8(0).done();
        assertArrayEquals(new int[]{20, 21}, ModelRefs.models(Arch.CFG_LOC, 1, loc, false));
        byte[] spot = new BufOut().u8(3).i32(30).u8(2).u16(800).u8(0).done();
        assertArrayEquals(new int[]{30}, ModelRefs.models(Arch.CFG_SPOT, 1, spot, false));
        byte[] kit = new BufOut().u8(1).u8(0).u8(5).u8(1).i32(40).u8(70).i32(41).u8(0).done();
        assertArrayEquals(new int[]{40, 41}, ModelRefs.models(Arch.CFG_IDENTKIT, 1, kit, false));
        IdMap m = moves(10, 62000, 21, 62001, 30, 62002, 41, 62003);
        assertArrayEquals(new int[]{62000, 11, 12}, ModelRefs.models(Arch.CFG_NPC, 1, ModelRefs.remap(m, Arch.CFG_NPC, 1, npc), false));
        assertArrayEquals(new int[]{20, 62001}, ModelRefs.models(Arch.CFG_LOC, 1, ModelRefs.remap(m, Arch.CFG_LOC, 1, loc), false));
        assertArrayEquals(new int[]{62002}, ModelRefs.models(Arch.CFG_SPOT, 1, ModelRefs.remap(m, Arch.CFG_SPOT, 1, spot), false));
        assertArrayEquals(new int[]{40, 62003}, ModelRefs.models(Arch.CFG_IDENTKIT, 1, ModelRefs.remap(m, Arch.CFG_IDENTKIT, 1, kit), false));
    }

    @Test void legacyFieldsAreFlaggedAndBounded() {
        byte[] npc = new BufOut().u8(1).u8(1).u16(500).u8(0).done();   // 211 layout left in a 241 entry
        List<ModelRefs.Ref> refs = ModelRefs.refs(Arch.CFG_NPC, 1, npc);
        assertEquals(1, refs.size());
        assertTrue(refs.get(0).legacy());
        assertEquals(0, ModelRefs.models(Arch.CFG_NPC, 1, npc, false).length);
        byte[] r = ModelRefs.rewrite(Arch.CFG_NPC, 1, npc, m -> m + 1);
        assertEquals(501, ((r[2] & 255) << 8) | (r[3] & 255));
        assertThrows(IllegalStateException.class, () -> ModelRefs.rewrite(Arch.CFG_NPC, 1, npc, m -> 70000));
    }

    @Test void noneStaysNone() {
        byte[] spot = new BufOut().u8(3).i32(-1).u8(0).done();
        assertEquals(0, ModelRefs.models(Arch.CFG_SPOT, 1, spot, true).length);
        assertSame(spot, ModelRefs.rewrite(Arch.CFG_SPOT, 1, spot, m -> 99));
    }

    @Test void badEntriesThrow() {
        assertThrows(IllegalStateException.class, () -> ModelRefs.refs(Arch.CFG_OBJ, 7, new byte[]{(byte) 199, 0}));      // unknown opcode
        assertThrows(IllegalStateException.class, () -> ModelRefs.refs(Arch.CFG_OBJ, 7, new byte[]{44, 0, 0}));          // truncated
        assertThrows(IllegalStateException.class, () -> ModelRefs.refs(Arch.CFG_SPOT, 7, new byte[]{10, 0, 0}));          // bytes after the end
        assertTrue(ModelRefs.refs(Arch.CFG_ENUM, 7, new byte[]{1, 2, 3}).isEmpty());                                       // no models in enums
        assertFalse(ModelRefs.hasModels(Arch.CFG_ENUM));
    }

    @Test void interfaceComponents() {
        // IF3 type 6, 211 layout (2-byte model at 19), converted to 241 (int), then remapped
        byte[] if3 = new byte[30];
        if3[0] = (byte) 255; if3[1] = 6; if3[19] = (byte) (1234 >> 8); if3[20] = (byte) 1234;
        byte[] c241 = InterfaceConv.component(if3);
        assertArrayEquals(new int[]{1234}, ModelRefs.componentModels(c241));
        GroupData g = new GroupData.Builder(5).put(0, c241).put(1, new byte[]{(byte) 255, 0, 0}).build();
        GroupData r = ModelRefs.remapInterface(moves(1234, 64000), g);
        assertNotSame(g, r);
        assertArrayEquals(new int[]{64000}, ModelRefs.componentModels(r.dataRef(0)));
        assertSame(g.dataRef(1), r.dataRef(1));
        assertSame(g, ModelRefs.remapInterface(moves(1, 2), g));
        assertEquals(-1, ModelRefs.remapModel(moves(1234, 64000), -1));
        assertEquals(64000, ModelRefs.remapModel(moves(1234, 64000), 1234));
    }

    /** The full self-check on the real caches, only when the cache layout is available (-Dgadconv.root). */
    @Test void walkersOnRealCaches() throws Exception {
        Options o = Options.defaults();
        Assumptions.assumeTrue(o.root != null && Files.isDirectory(o.base) && Files.isDirectory(o.user), "cache layout not available");
        try (var base = com.gadscape.cacheconv.core.JagCache.open("BASE", o.base)) {
            int n = 0;
            for (int id : base.configIds(Arch.CFG_SPOT)) {
                byte[] b = base.configRef(Arch.CFG_SPOT, id);
                for (int m : ModelRefs.models(Arch.CFG_SPOT, id, b, false)) { assertTrue(base.hasGroup(Arch.MODELS, m), "spot " + id + " model " + m); n++; }
            }
            assertTrue(n > 1000);
        }
    }
}
