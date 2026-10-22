package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv port-interface: ports a 211 interface and the custom scripts it needs into a 241 cache (components widened, scripts converted with ScriptConv, references rewritten).
 * Stub: the owning area replaces {@link #run} (keep the signature; GadConv dispatches to it).
 */
final class PortInterfaceCmd {
    private PortInterfaceCmd() {}

    static int run(Options o) throws Exception {
        return GadConv.notImplemented("port-interface", o);
    }
}
