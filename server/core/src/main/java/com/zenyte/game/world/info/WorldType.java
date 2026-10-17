package com.zenyte.game.world.info;

/**
 * RECONSTRUCTED — not original source.
 *
 * com.zenyte.game.world.info.WorldType was absent from the published
 * repository. Its only consumer is LogoutTransfer, which needs exactly
 * three accessors: getAddress(), getWorldId() and getFlag().
 *
 * Nothing in the tree constructs a LogoutTransfer, so this class is
 * compile-time-only in practice. If world hopping is wired up later,
 * this is where the real field set would need filling in.
 */
public final class WorldType {

    private final String address;
    private final int worldId;
    private final int flag;

    public WorldType(final String address, final int worldId, final int flag) {
        this.address = address;
        this.worldId = worldId;
        this.flag = flag;
    }

    public String getAddress() {
        return address;
    }

    public int getWorldId() {
        return worldId;
    }

    public int getFlag() {
        return flag;
    }
}
