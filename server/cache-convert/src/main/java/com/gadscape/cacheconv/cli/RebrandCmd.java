package com.gadscape.cacheconv.cli;

import com.gadscape.cacheconv.core.Options;

/**
 * gadconv rebrand: replaces old branding (Near-Reality, Roat Pkz, OSNR, RuneSpawn...) with GadScape in interface texts, script strings, config names/ops and enum strings, and reports every change.
 * Stub: the owning area replaces {@link #run} (keep the signature; GadConv dispatches to it).
 */
final class RebrandCmd {
    private RebrandCmd() {}

    static int run(Options o) throws Exception {
        return GadConv.notImplemented("rebrand", o);
    }
}
