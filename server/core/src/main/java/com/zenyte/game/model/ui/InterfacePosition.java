package com.zenyte.game.model.ui;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import mgi.types.config.enums.EnumDefinitions;

/**
 * @author Tommeh | 28 jan. 2018 : 23:38:10 | @author Kris | 22. sept 2018 : 20:34:02
 * @see <a href="https://www.rune-server.ee/members/tommeh/">Rune-Server profile</a>
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 * @see <a href="https://rune-status.net/members/kris.354/">Rune-Status profile</a>
 */
public enum InterfacePosition {
	/*
	 * Build 241 component ids. The seed is a component of toplevel_osrs_stretch (161); the other panes are
	 * resolved through the cache remap enums 1129-1132/1745. 211 ids are in the trailing comments; see
	 * server/docs/rev241-interfaces.md for how they were matched.
	 */
	CHATBOX(96, 162, true), // 211: 94 chat_container
	PRIVATE_CHAT(93, 163, true), // 211: 91 pm_container
	WILDERNESS_OVERLAY(3, true), // pvp_icons
	BH_OVERLAY(5, true), // stat_boosts_hud
	ORBS(33, true), // 211: 32 orbs
	SKILLS_TAB(77, 320, true), // 211: 76 side1
	JOURNAL_TAB_HEADER(78, 629, true), // 211: 77 side2
	CHAT_TAB_HEADER(83, true), // 211: 82 side7
	INVENTORY_TAB(79, 149, true), // 211: 78 side3
	EQUIPMENT_TAB(80, 387, true), // 211: 79 side4
	PRAYER_TAB(81, 541, true), // 211: 80 side5
	SPELLBOOK_TAB(82, 218, true), // 211: 81 side6
	ACCOUNT_MANAGEMENT(84, 1702, true), // 211: 83 side8
	FRIENDS_TAB(85, 429, true), // 211: 84 side9
	LOGOUT_TAB(86, 182, true), // 211: 85 side10
	SETTINGS_TAB(87, 116, true), // 211: 86 side11
	EMOTE_TAB(88, 216, true), // 211: 87 side12
	MUSIC_TAB(89, 239, true), // 211: 88 side13
	COMBAT_TAB(76, 593, true), // 211: 75 side0
	CENTRAL(16, false), // mainmodal
	DIALOGUE(568, false), // chatbox (162) chatmodal, 211: 559
	MINIGAME_OVERLAY(8, true), // overlay_hud
	OVERLAY(1, true), // overlay_atmosphere
	SINGLE_TAB(74, false), // 211: 73 sidemodal
	WORLD_MAP(18, false), // 211: 17 floater
	UNKNOWN_OVERLAY(8, true), // overlay_hud
	XP_TRACKER(9, true), // xp_drops
	// Special type, parent = adv. settings (134 settings:popup, 211: 8)
	COLOUR_PICKER(9, false),
	NOTIFICATION_POS(13, true), // notifications
	HP_HUD_POS(2, -1, true), // hpbar_hud
	TOA_MANAGEMENT(61, false), // toa_partydetails (774) popup, unchanged
	NIGHTMARE_TOTEMS_POS(12, -1, true), // helper_content
	;
	/**
	 * An array containing all of the component types.
	 */
	public static final InterfacePosition[] VALUES = values();

	/**
	 * Gets the component pairs when moving from one game pane to another.
	 * 
	 * @param fromPane
	 *            the pane we're moving from.
	 * @param toPane
	 *            the pane we're moving to.
	 * @return a primitive int map containing all the pairs.
	 */
	public static Int2IntOpenHashMap getPairs(final PaneType fromPane, final PaneType toPane) {
		final Int2IntOpenHashMap pairs = new Int2IntOpenHashMap(VALUES.length);
		for (final InterfacePosition position : VALUES) {
			if (position.equals(DIALOGUE)) {
				continue;
			}
			final int from = position.getComponent(fromPane);
			final int to = position.getComponent(toPane);
			if (from != -1 && to != -1) {
				pairs.put(from, to);
			}
		}
		return pairs;
	}

	/**
	 * Gets the component type for the respective component id and pane.
	 * 
	 * @param componentId
	 *            the resizable component id.
	 * @param pane
	 *            the pane to search.
	 * @return the respective component type, or null if not found.
	 */
	public static InterfacePosition getPosition(final int componentId, final PaneType pane) {
		for (final InterfacePosition position : VALUES) {
			final EnumDefinitions e = pane.getEnum();
			final int bitpacked = e.getIntValue(161 << 16 | componentId);
			if (componentId == (bitpacked & 65535)) {
				return position;
			}
		}
		return null;
	}

	private final int resizableComponent;
	private final int gameframeInterfaceId;
	private final boolean walkable;

	InterfacePosition(final int resizableComponent, final boolean walkable) {
		this(resizableComponent, -1, walkable);
	}

	/**
	 * Constructs the component types with the seed component of resizable, used to search the other types.
	 * 
	 * @param resizableComponent
	 *            the resizable component id, used as a seed.
	 * @param walkable
	 *            whether the component is walkable or not.
	 */
	InterfacePosition(final int resizableComponent, final int gameframeInterfaceId, final boolean walkable) {
		this.resizableComponent = resizableComponent;
		this.gameframeInterfaceId = gameframeInterfaceId;
		this.walkable = walkable;
	}

	/**
	 * Gets the component id for the respective pane based on the resizable type.
	 * 
	 * @param pane
	 *            the pane to seek.
	 * @return the component id
	 */
	public final int getComponent(final PaneType pane) {
		if (pane == null) {
			return -1;
		} else if (pane == PaneType.RESIZABLE || pane == PaneType.TOA_MANAGEMENT) {
			return resizableComponent;
		}
		final EnumDefinitions e = pane.getEnum();
		final int bitpacked = e.getIntValue(161 << 16 | resizableComponent);
		return bitpacked == 0 ? resizableComponent : bitpacked & 65535;
	}

	/**
	 * Gets the fixed component based on the resizable one.
	 * 
	 * @return the fixed component's id.
	 */
	public final int getFixedComponent() {
		final EnumDefinitions e = PaneType.FIXED.getEnum();
		final int bitpacked = e.getIntValue(161 << 16 | resizableComponent);
		return bitpacked == 0 ? resizableComponent : bitpacked & 65535;
	}

	/**
	 * Gets the fullscreen component based on the resizable one.
	 * 
	 * @return the fullscreen component's id.
	 */
	public final int getFullScreenComponent() {
		final EnumDefinitions e = PaneType.FULL_SCREEN.getEnum();
		final int bitpacked = e.getIntValue(161 << 16 | resizableComponent);
		return bitpacked == 0 ? resizableComponent : bitpacked & 65535;
	}

	/**
	 * Gets the mobile component based on the resizable one.
	 * 
	 * @return the mobile component's id.
	 */
	public final int getMobileComponent() {
		final EnumDefinitions e = PaneType.MOBILE.getEnum();
		final int bitpacked = e.getIntValue(161 << 16 | resizableComponent);
		return bitpacked == 0 ? resizableComponent : bitpacked & 65535;
	}

	/**
	 * Gets the sidepanels component based on the resizable one.
	 *
	 * @return the sidepanels component's id.
	 */
	public final int getSidepanelsComponent() {
		final EnumDefinitions e = PaneType.SIDE_PANELS.getEnum();
		final int bitpacked = e.getIntValue(161 << 16 | resizableComponent);
		return bitpacked == 0 ? resizableComponent : bitpacked & 65535;
	}

	public int getResizableComponent() {
		return resizableComponent;
	}

	public int getGameframeInterfaceId() {
		return gameframeInterfaceId;
	}

	public boolean isWalkable() {
		return walkable;
	}
}
