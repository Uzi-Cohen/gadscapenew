package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv port: converts 211-era config entries or whole groups (items, NPCs, locs, seqs, models...) from a source cache into a 241 cache, relocating clashing ids through the IdMap.
 * Stub: the owning area replaces {@link #run} (keep the signature; GadConv dispatches to it).
 */
final class PortCmd {
    private PortCmd() {}

    static int run(Options o) throws Exception {
        return GadConv.notImplemented("port", o);
    }
}
