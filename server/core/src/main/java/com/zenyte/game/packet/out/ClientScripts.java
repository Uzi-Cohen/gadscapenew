package com.zenyte.game.packet.out;

/**
 * Ids of GadScape's own client scripts that the server runs by name rather than by a magic number.
 * The cache side is listed in content-work/interfaces-server/CACHE_NEEDS.md.
 */
public final class ClientScripts {

    /**
     * GadScape's "hide roofs" script (1 int: 1 = hide, 0 = show), used by cutscenes and the guide.
     * It used to be packed as 10700, which GadScape's Completionist Cape Progress setup script (onLoad of 1618:1)
     * overwrote, so the cutscenes ran the comp-cape title builder instead (IF-CS10700-09). It moves to this free id;
     * until the cache has it, RUNCLIENTSCRIPT drops the call (see RsprotOut.checkClientScript).
     */
    public static final int HIDE_ROOFS = 10719;

    private ClientScripts() {
    }
}
