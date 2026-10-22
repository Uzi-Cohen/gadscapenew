package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv port-map: ports 211 map squares (terrain + locs, XTEA-aware) into a 241 cache under their region ids.
 * Stub: the owning area replaces {@link #run} (keep the signature; GadConv dispatches to it).
 */
final class PortMapCmd {
    private PortMapCmd() {}

    static int run(Options o) throws Exception {
        return GadConv.notImplemented("port-map", o);
    }
}
