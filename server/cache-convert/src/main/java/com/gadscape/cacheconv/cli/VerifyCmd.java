package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv verify: RefCheck (frames/keyframes/framemaps, model/seq/sprite refs, IF3 hook scripts, gosub targets and arg
 * counts), the client-decode oracles and the strict cache checker, a branding scan and the determinism check.
 * Implemented by {@link com.gadscape.cacheconv.verify.VerifyMain}.
 */
final class VerifyCmd {
    private VerifyCmd() {}

    static int run(Options o) throws Exception {
        return com.gadscape.cacheconv.verify.VerifyMain.run(o);
    }
}
