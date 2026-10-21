package com.zenyte.game.world.entity.masks;

/**
 * @author Kris | 6. nov 2017 : 14:34.26
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>}
 */
public enum UpdateFlag {

	/**
	 * Appearance update.
	 */
	APPEARANCE(true, false),

	/**
	 * Graphics update.
	 */
	GRAPHICS(true, true),

	/**
	 * Animation update.
	 */
	ANIMATION(true, true),

	/**
	 * Forced chat update.
	 */
	FORCED_CHAT(true, true),

	/**
	 * Interacting entity update.
	 */
	FACE_ENTITY(true, true),

	/**
	 * Face coordinate entity update.
	 */
	FACE_COORDINATE(true, true),

	/**
	 * Hit update.
	 */
	HIT(true, true),

	/**
	 * Update flag used to define player's current movement type (walk or run)
	 */
	MOVEMENT_TYPE(true, false),

	/**
	 * Update flag used to force move player.
	 */
	FORCE_MOVEMENT(true, true),

	/**
	 * Update flag used to set player's movement type for one tick (teleport or walk - supports run as well but never used)
	 */
	TEMPORARY_MOVEMENT_TYPE(true, false),

	/**
	 * Update flag used to set player's right-click strings (before name, after name and after combat)
	 */
	NAMETAG(true, false),
	
	/**
	 * Update flag used for chat messages.
	 */
	CHAT(true, false),

    /**
     * Update flag used to transform a npc to a different one.
     */
    TRANSFORMATION(false, true),

    /**
     * Update flag used to set an option on an npc.
     */
    OPTION(false, true),
	TINTING(true, true),
	COMBAT_LEVEL_CHANGE(false, true),
	NAME_CHANGE(false, true),
	HIDE_OPTIONS(false, true),
	MODEL_OVERRIDE(false, true),
	NPC_PRAYER_OVERHEAD(false, true)
	;

    public static final UpdateFlag[] VALUES = values();
    private final boolean player, npc;

    /**
     * @param player whether players use this flag.
     * @param npc    whether NPCs use this flag.
     *               (The 211 wire bits that used to live here are gone: rsprot encodes the 241 masks,
     *               see com.gadscape.network.rsprot.RsprotInfo.)
     */
    UpdateFlag(final boolean player, final boolean npc) {
        this.player = player;
        this.npc = npc;
    }

    public boolean appliesToPlayers() {
        return player;
    }

    public boolean appliesToNpcs() {
        return npc;
    }

}
