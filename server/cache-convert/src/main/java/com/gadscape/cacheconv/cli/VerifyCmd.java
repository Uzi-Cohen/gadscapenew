package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv verify: RefCheck (frames/keyframes/framemaps, model/seq/sprite refs, IF3 hook scripts, gosub targets and arg counts), the client-decode oracles and the strict cache checker, and a branding scan.
 * Stub: the owning area replaces {@link #run} (keep the signature; GadConv dispatches to it).
 */
final class VerifyCmd {
    private VerifyCmd() {}

    static int run(Options o) throws Exception {
        return GadConv.notImplemented("verify", o);
    }
}
