package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv port-map: ports 211 map squares (terrain + locs, XTEA-aware) into a 241 cache under their region ids.
 * Implemented by {@link com.gadscape.cacheconv.map.PortMap}.
 */
final class PortMapCmd {
    private PortMapCmd() {}

    static int run(Options o) throws Exception {
        return com.gadscape.cacheconv.map.PortMap.run(o);
    }
}
