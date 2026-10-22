package com.zenyte.game.world.entity.player;

import com.zenyte.game.GameInterface;
import com.zenyte.game.model.ui.InterfacePosition;
import com.zenyte.game.task.WorldTasksManager;
import com.zenyte.game.util.RSColour;

import static com.zenyte.game.model.ui.testinterfaces.advancedsettings.SettingVariables.SHOW_BOSS_HEALTH_OVERLAY_VARBIT_ID;

/**
 * The boss HP HUD, interface 303 {@code hpbar_hud}, in its build-241 (cache 2746) layout (E15).
 * <p>
 * 2746 inserted {@code hp_bar_extra} as 303:11, so every component from the old 303:11 on moved up by one
 * (inner_border 12, inner 13, health_bar_back 14, health_bar_sliding 15, health_bar_remaining 16, hp_bar_1 17,
 * hp_bar_2 18, lower/upper threshold 19/20, hp_bar_text 21) and added the extra bar 303:22-27
 * (inner_border_extra, inner_extra, health_bar_back_extra, health_bar_sliding_extra, health_bar_remaining_extra,
 * hp_bar_text_extra) [CACHE 2746 interface 303; docs/rev241-interfaces.md "303 hpbar_hud"].
 * <p>
 * Main bar: varp 1683 {@code hpbar_hud_npc} (the NPC whose name and params the client shows; changing it while
 * the HUD is open redraws the name and bars: the HUD's var transmit listens to it), varbits 6099/6100 current/max.
 * <p>
 * Extra bar [CACHE 2746 procs 10149-10153]: varp 5991 {@code hpbar_hud_show_extra_bar} = 1, varbits 16570/16571
 * (varp 5990) extra current/base, 16 bits each, then RUNCLIENTSCRIPT 10152(boolean instant). Only NPCs whose
 * type has the extra-bar params 2762-2769 lay it out properly (e.g. 16642 "Bork and the Smorks", 16649 the
 * Dagon'hai cauldron, 16674 Ocellus P1). The layer is shown or hidden by {@code hp_hud_open} (script 10151), so
 * turning the extra bar on or off re-runs the open script.
 * <p>
 * Clientscripts: 2376 {@code hp_hud_open} (19 components, the onload {@code hp_hud_init} order) and 2889
 * {@code hp_hud_fade_out} (14 components + start transparency) [CACHE cacheV script headers 19/0 and 15/0].
 */
public class HpHud {

	private static final int HP_HUD = 303;

	/** 303 components in the 2746 layout. */
	private static final int UNIVERSE = 0;
	private static final int HPDODGERSIDE = 2;
	private static final int HPCONTENTSIDE = 3;
	private static final int HPDODGER = 4;
	private static final int HP = 5;
	private static final int OUTER_BORDER = 6;
	private static final int NAME_BACKING = 7;
	private static final int NAME_AREA = 8;
	private static final int CREATURE_NAME = 9;
	private static final int HP_BAR = 10;
	private static final int INNER_BORDER = 12;      // 211: 11
	private static final int HEALTH_BAR_BACK = 14;   // 211: 13
	private static final int HEALTH_BAR_SLIDING = 15; // 211: 14
	private static final int HEALTH_BAR_REMAINING = 16; // 211: 15
	private static final int HP_BAR_1 = 17;          // 211: 16
	private static final int HP_BAR_2 = 18;          // 211: 17
	private static final int LOWER_THRESHOLD = 19;   // 211: 18
	private static final int UPPER_THRESHOLD = 20;   // 211: 19
	private static final int HP_BAR_TEXT = 21;       // 211: 20
	/** Extra bar fill components (2746). */
	public static final int HEALTH_BAR_BACK_EXTRA = 24;
	public static final int HEALTH_BAR_SLIDING_EXTRA = 25;
	public static final int HEALTH_BAR_REMAINING_EXTRA = 26;

	/** Varp 1683 {@code hpbar_hud_npc}. */
	public static final int VARP_NPC = 1683;
	/** Varbits 6099 / 6100: main bar current / max. */
	public static final int VARBIT_CURRENT = 6099;
	public static final int VARBIT_MAX = 6100;
	/** Varbit 12401: centred HUD. */
	private static final int VARBIT_CENTRED = 12401;
	/** Varp 5991 {@code hpbar_hud_show_extra_bar}. */
	public static final int VARP_SHOW_EXTRA_BAR = 5991;
	/** Varbits 16570 / 16571 (varp 5990 {@code hpbar_hud_info_4}): extra bar current / base. */
	public static final int VARBIT_EXTRA_CURRENT = 16570;
	public static final int VARBIT_EXTRA_BASE = 16571;
	private static final int EXTRA_MAX = 0xFFFF;

	/** Clientscripts. */
	private static final int SCRIPT_OPEN = 2376;
	private static final int SCRIPT_FADE_OUT = 2889;
	/** {@code script10152(boolean instant)}: redraws the extra bar from 16570/16571. */
	public static final int SCRIPT_EXTRA_BAR = 10152;

	/** hp_hud_open(universe, hpdodgerside, hpdodger, hp, name_area, hp_bar, hp_bar_text, back, sliding, remaining, creature_name, outer_border, name_backing, inner_border, lower, upper, hp_bar_1, hp_bar_2, hpcontentside). */
	private static final Object[] OPEN_CS2_ARGS = {
			HP_HUD << 16 | UNIVERSE,
			HP_HUD << 16 | HPDODGERSIDE,
			HP_HUD << 16 | HPDODGER,
			HP_HUD << 16 | HP,
			HP_HUD << 16 | NAME_AREA,
			HP_HUD << 16 | HP_BAR,
			HP_HUD << 16 | HP_BAR_TEXT,
			HP_HUD << 16 | HEALTH_BAR_BACK,
			HP_HUD << 16 | HEALTH_BAR_SLIDING,
			HP_HUD << 16 | HEALTH_BAR_REMAINING,
			HP_HUD << 16 | CREATURE_NAME,
			HP_HUD << 16 | OUTER_BORDER,
			HP_HUD << 16 | NAME_BACKING,
			HP_HUD << 16 | INNER_BORDER,
			HP_HUD << 16 | LOWER_THRESHOLD,
			HP_HUD << 16 | UPPER_THRESHOLD,
			HP_HUD << 16 | HP_BAR_1,
			HP_HUD << 16 | HP_BAR_2,
			HP_HUD << 16 | HPCONTENTSIDE
	};
	/**
	 * hp_hud_fade_out(hp, name_area, outer_border, name_backing, creature_name, inner_border, back, sliding,
	 * remaining, hp_bar_text, lower, upper, hp_bar_1, hp_bar_2, int startTransparency): the order hp_hud_open
	 * passes to hp_hud_fade_in [CACHE 2746 proc 2377]. The 211 call had 12 components (no hp / name_area).
	 */
	private static final Object[] CLOSE_CS2_ARGS = {
			HP_HUD << 16 | HP,
			HP_HUD << 16 | NAME_AREA,
			HP_HUD << 16 | OUTER_BORDER,
			HP_HUD << 16 | NAME_BACKING,
			HP_HUD << 16 | CREATURE_NAME,
			HP_HUD << 16 | INNER_BORDER,
			HP_HUD << 16 | HEALTH_BAR_BACK,
			HP_HUD << 16 | HEALTH_BAR_SLIDING,
			HP_HUD << 16 | HEALTH_BAR_REMAINING,
			HP_HUD << 16 | HP_BAR_TEXT,
			HP_HUD << 16 | LOWER_THRESHOLD,
			HP_HUD << 16 | UPPER_THRESHOLD,
			HP_HUD << 16 | HP_BAR_1,
			HP_HUD << 16 | HP_BAR_2,
			0
	};
	private static final RSColour[] ORIGINAL_COLORS = { new RSColour(25, 0, 0), new RSColour(0, 15, 0), new RSColour(0, 25, 0) };
	/** The extra bar's cache colours 0x2b2727 / 0x009595 / 0x009ccc as 15-bit colours. */
	private static final RSColour[] ORIGINAL_EXTRA_COLORS = { new RSColour(5, 4, 4), new RSColour(0, 18, 18), new RSColour(0, 19, 25) };

	private final transient Player player;
	/** Whether the open HUD shows the extra bar (varp 5991 as last sent through this class). */
	private transient boolean extraBar;

	public HpHud(Player player) {
		this.player = player;
	}

	public void open(int npcId, int maxValue) {
		open(true, npcId, maxValue);
	}

	/** Opens the HUD for [npcId] with a full main bar of [maxValue] and no extra bar. */
	public void open(boolean centered, int npcId, int maxValue) {
		if (isDisabled()) {
			return;
		}
		setExtraBarVar(false);
		player.getVarManager().sendVarInstant(VARP_NPC, npcId);
		player.getVarManager().sendBitInstant(VARBIT_CENTRED, centered);
		updateValue(maxValue);
		updateMaxValue(maxValue);
		player.getInterfaceHandler().sendInterface(GameInterface.HP_HUD);
		player.getPacketDispatcher().sendClientScript(SCRIPT_OPEN, OPEN_CS2_ARGS);
	}

	/**
	 * Opens the HUD for [npcId] with the main bar at [current]/[max] and the extra bar at
	 * [extraCurrent]/[extraBase] (e.g. Bork and the Smorks: Bork's HP and the Smorks' HP).
	 */
	public void openWithExtraBar(int npcId, int current, int max, int extraCurrent, int extraBase) {
		if (isDisabled()) {
			return;
		}
		setExtraValues(extraCurrent, extraBase);
		setExtraBarVar(true);
		player.getVarManager().sendVarInstant(VARP_NPC, npcId);
		player.getVarManager().sendBitInstant(VARBIT_CENTRED, true);
		updateValue(current);
		updateMaxValue(max);
		player.getInterfaceHandler().sendInterface(GameInterface.HP_HUD);
		player.getPacketDispatcher().sendClientScript(SCRIPT_OPEN, OPEN_CS2_ARGS);
		player.getPacketDispatcher().sendClientScript(SCRIPT_EXTRA_BAR, 1);
	}

	/**
	 * Switches the open HUD to another NPC id (a transformation or the next phase) without closing it; the client
	 * redraws the name and bars for the new id. Also sets the bar values when [max] is above 0.
	 */
	public void switchNpc(int npcId, int current, int max) {
		if (isDisabled()) {
			return;
		}
		if (max > 0) {
			player.getVarManager().sendBitInstant(VARBIT_MAX, max);
			player.getVarManager().sendBitInstant(VARBIT_CURRENT, current);
		}
		player.getVarManager().sendVarInstant(VARP_NPC, npcId);
		if (extraBar && isOpen()) {
			// the extra bar's text/fill depends on the npc's params 2765/2766/2769
			player.getPacketDispatcher().sendClientScript(SCRIPT_EXTRA_BAR, 1);
		}
	}

	/** Shows the extra bar on the open HUD at [current]/[base]. */
	public void showExtraBar(int current, int base) {
		if (isDisabled()) {
			return;
		}
		setExtraValues(current, base);
		if (!extraBar) {
			setExtraBarVar(true);
			relayout();
		}
		player.getPacketDispatcher().sendClientScript(SCRIPT_EXTRA_BAR, 1);
	}

	/** Updates the extra bar's values; [instant] skips the client's sliding animation. */
	public void updateExtraBar(int current, int base, boolean instant) {
		if (isDisabled() || !extraBar) {
			return;
		}
		setExtraValues(current, base);
		player.getPacketDispatcher().sendClientScript(SCRIPT_EXTRA_BAR, instant ? 1 : 0);
	}

	/** Hides the extra bar of the open HUD. */
	public void hideExtraBar() {
		if (isDisabled() || !extraBar) {
			return;
		}
		setExtraBarVar(false);
		relayout();
	}

	public boolean hasExtraBar() {
		return extraBar;
	}

	public void close() {
		if (isDisabled() || !player.getInterfaceHandler().containsInterface(InterfacePosition.HP_HUD_POS)) {
			return;
		}

		player.getPacketDispatcher().sendClientScript(SCRIPT_FADE_OUT, CLOSE_CS2_ARGS);
		WorldTasksManager.schedule(() -> player.getInterfaceHandler().closeInterface(GameInterface.HP_HUD), 2);
		if (extraBar) {
			setExtraBarVar(false);
		}
	}

	/** Recolours the main bar: back (303:14), sliding (303:15) and remaining (303:16). */
	public void sendColorChange(RSColour rsColour, RSColour rsColour1, RSColour rsColour2) {
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_BACK, rsColour);
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_SLIDING, rsColour1);
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_REMAINING, rsColour2);
	}

	public void resetColors() {
		sendColorChange(ORIGINAL_COLORS[0], ORIGINAL_COLORS[1], ORIGINAL_COLORS[2]);
	}

	/** Recolours the extra bar: back (303:24), sliding (303:25) and remaining (303:26). */
	public void sendExtraColorChange(RSColour back, RSColour sliding, RSColour remaining) {
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_BACK_EXTRA, back);
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_SLIDING_EXTRA, sliding);
		player.getPacketDispatcher().sendComponentSpriteColour(HP_HUD, HEALTH_BAR_REMAINING_EXTRA, remaining);
	}

	public void resetExtraColors() {
		sendExtraColorChange(ORIGINAL_EXTRA_COLORS[0], ORIGINAL_EXTRA_COLORS[1], ORIGINAL_EXTRA_COLORS[2]);
	}

	public void sendMaxHitPoints(int hitPoints) {
		player.getVarManager().sendBitInstant(VARBIT_MAX, hitPoints);
	}

	public boolean isOpen() {
		return player.getInterfaceHandler().containsInterface(InterfacePosition.HP_HUD_POS);
	}

	public void updateValue(int newValue) {
		player.getVarManager().sendBitInstant(VARBIT_CURRENT, newValue);
	}

	public void updateMaxValue(int newValue) {
		player.getVarManager().sendBitInstant(VARBIT_MAX, newValue);
	}

	private boolean isDisabled() {
		return player.getVarManager().getBitValue(SHOW_BOSS_HEALTH_OVERLAY_VARBIT_ID) == 1;
	}

	private void setExtraValues(int current, int base) {
		player.getVarManager().sendBitInstant(VARBIT_EXTRA_CURRENT, Math.max(0, Math.min(EXTRA_MAX, current)));
		player.getVarManager().sendBitInstant(VARBIT_EXTRA_BASE, Math.max(1, Math.min(EXTRA_MAX, base)));
	}

	private void setExtraBarVar(boolean show) {
		if (extraBar == show && player.getVarManager().getValue(VARP_SHOW_EXTRA_BAR) == (show ? 1 : 0)) {
			return;
		}
		extraBar = show;
		player.getVarManager().sendVarInstant(VARP_SHOW_EXTRA_BAR, show ? 1 : 0);
	}

	/**
	 * Shows/hides the extra layer (hp_hud_open runs script 10151) and re-lays the bars out (hp_hud_update runs
	 * script 10149 on the next var transmit of varp 1683, so the same npc id is sent again).
	 */
	private void relayout() {
		if (!isOpen()) {
			return;
		}
		player.getPacketDispatcher().sendClientScript(SCRIPT_OPEN, OPEN_CS2_ARGS);
		player.getVarManager().sendVarInstant(VARP_NPC, player.getVarManager().getValue(VARP_NPC));
	}

}
