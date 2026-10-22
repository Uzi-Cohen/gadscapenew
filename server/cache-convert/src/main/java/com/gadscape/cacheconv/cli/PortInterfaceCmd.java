package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv port-interface: ports 211 interfaces and the custom scripts they need into a 241 cache (components widened,
 * scripts converted with ScriptConv, references rewritten). Implemented by {@link com.gadscape.cacheconv.ui.UiPort}.
 */
final class PortInterfaceCmd {
    private PortInterfaceCmd() {}

    static int run(Options o) throws Exception {
        return com.gadscape.cacheconv.ui.UiPort.run(o);
    }
}
