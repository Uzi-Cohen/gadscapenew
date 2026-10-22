package com.zenyte.game.world.entity.player;

import com.zenyte.game.GameInterface;
import com.zenyte.game.content.treasuretrails.TreasureTrail;
import com.zenyte.game.model.music.Music;
import com.zenyte.game.model.music.MusicLoader;
import com.zenyte.game.model.music.MusicSlots;
import com.zenyte.game.model.ui.testinterfaces.advancedsettings.SettingVariables;
import com.zenyte.game.util.Colour;
import com.zenyte.game.util.Utils;
import com.zenyte.game.world.World;
import com.zenyte.game.world.region.area.plugins.MusicPlugin;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalInt;
import java.util.Set;

/**
 * @author Kris | 21. veebr 2018 : 4:27.50
 * @see <a href="https://www.rune-server.ee/members/kris/">Rune-Server profile</a>
 */
public final class MusicHandler {

	private static final Logger logger = LoggerFactory.getLogger(MusicHandler.class);

	/**
	 * Music unlock varps by unlock index (enum 819 / build-241 music table c5, 1-based). The first 23 are 211's; build
	 * 241 adds 4066, 4411, 4944 and 5238 (script 7305), only used when the 241 track list is loaded.
	 */
	public static final int[] VARP_IDS = new int[] {20, 21, 22, 23, 24, 25, 298, 311, 346, 414, 464, 598, 662, 721, 906, 1009, 1338, 1681, 2065, 2237, 2950, 3418, 3575, 4066, 4411, 4944, 5238};
	/** Build 241 musicplay (varp 18): the music tab mode. */
	private static final int MUSIC_PLAY_VARP = 18;
	public static final int MODE_AREA = 0;
	public static final int MODE_SHUFFLE = 1;
	public static final int MODE_SINGLE = 2;
	/** Build 241 music_current_track: the music db row playing (jukebox highlight, now-playing text; script 3936). */
	private static final int CURRENT_TRACK_VARP = 3883;
	/** Build 241 music_current_playlist (0 = none). */
	private static final int CURRENT_PLAYLIST_VARBIT = 19731;
	/** Build 241 playlist slots: playlist p (1-3), slot s (0-99) is varbit 19738 + (p - 1) * 100 + s (scripts 9316/9318/9320). */
	private static final int FIRST_PLAYLIST_VARBIT = 19738;
	public static final int PLAYLISTS = 3;
	private static final int PLAYLIST_SIZE = 100;
	private static final String SHUFFLE_ATTRIBUTE = "music shuffle mode";
	/**
	 * The given player who owns this handler.
	 */
	private final transient Player player;
	/**
	 * An int2int map containing the varps and their values.
	 */
	private final Int2IntOpenHashMap unlockedTracks;
	/**
	 * The music track that's currently playing.
	 */
	private transient Music currentlyPlaying;
	/**
	 * The amount of ticks the current song has played for, and the amount of ticks at which this song ends.
	 */
	private transient int ticks;
	private transient int nextSongAtTicks;
	/**
	 * Whether the music player is currently stopped or not.
	 */
	private transient boolean stopped;
	private transient boolean forcePlayNext;

	MusicHandler(final Player player) {
		this.player = player;
		unlockedTracks = new Int2IntOpenHashMap(VARP_IDS.length);
	}

	/**
	 * Refreshes the varps to portray on the music tab interface.
	 */
	void refreshListConfigs() {
		final VarManager manager = player.getVarManager();
		final boolean reset = isDefaultsReset();
		if (!reset) {
			setDefaultsReset();
		}
		for (int i = MusicSlots.get().unlockVarpCount(VARP_IDS.length) - 1; i >= 0; i--) {
			final int varp = VARP_IDS[i];
			if (!reset) {
				unlockedTracks.put(varp, unlockedTracks.get(varp) - (unlockedTracks.get(varp) & MusicLoader.getExcludedVarpValue(i)));
			}
			final int value = unlockedTracks.get(varp) | MusicLoader.DEFAULT_VARP_VALUES[i];
			manager.sendVar(varp, value);
		}
		sendPlayMode();
		if (MusicSlots.get().isTable()) {
			manager.sendVar(CURRENT_TRACK_VARP, -1);
		}
		if (getMode() == MODE_SHUFFLE) {
			playShuffleTrack();
		} else {
			playRandomTrack();
		}
	}

	/**
	 * The music tab mode: area (the 211 "Auto" setting), shuffle or single (211 "Manual"). On the 211 cache only area
	 * and single exist.
	 */
	public int getMode() {
		if (player.getBooleanSetting(Setting.AUTO_MUSIC)) {
			return MODE_AREA;
		}
		return MusicSlots.get().isTable() && player.getBooleanAttribute(SHUFFLE_ATTRIBUTE) ? MODE_SHUFFLE : MODE_SINGLE;
	}

	/** Switches the music tab mode (build 241 Area / Shuffle / Single buttons; 211 Auto / Manual). */
	public void setMode(final int mode) {
		player.addAttribute(SHUFFLE_ATTRIBUTE, mode == MODE_SHUFFLE ? 1 : 0);
		player.getSettings().setSetting(Setting.AUTO_MUSIC, mode == MODE_AREA ? 1 : 0);
		if (mode == MODE_AREA) {
			playRandomTrack();
		} else if (mode == MODE_SHUFFLE && (stopped || currentlyPlaying == null)) {
			playShuffleTrack();
		}
	}

	/**
	 * Sends musicplay (varp 18). The 211 music tab reads 1 = auto / 0 = manual; build 241's reads 0 = area, 1 =
	 * shuffle, 2 = single (scripts 315/318).
	 */
	public void sendPlayMode() {
		final int value = MusicSlots.get().isTable() ? getMode() : (player.getBooleanSetting(Setting.AUTO_MUSIC) ? 1 : 0);
		player.getVarManager().sendVar(MUSIC_PLAY_VARP, value);
	}

	/** Shuffle mode / Skip Track: a random unlocked track, from the selected playlist when it has any. */
	public void playShuffleTrack() {
		final MusicSlots slots = MusicSlots.get();
		final IntArrayList candidates = new IntArrayList();
		final int playlist = player.getVarManager().getBitValue(CURRENT_PLAYLIST_VARBIT);
		if (playlist >= 1 && playlist <= PLAYLISTS) {
			for (int i = 0; i < PLAYLIST_SIZE; i++) {
				final OptionalInt slot = slots.slotOfPlaylistCode(player.getVarManager().getBitValue(playlistVarbit(playlist, i)));
				if (slot.isPresent() && isUnlocked(slot.getAsInt()) && slots.musicOf(slot.getAsInt()) != null) {
					candidates.add(slot.getAsInt());
				}
			}
		}
		if (candidates.isEmpty()) {
			for (int slot = 1; slot <= slots.size(); slot++) {
				if (isUnlocked(slot) && slots.musicOf(slot) != null) {
					candidates.add(slot);
				}
			}
		}
		if (candidates.isEmpty()) {
			return;
		}
		int slot = candidates.getInt(Utils.random(candidates.size() - 1));
		if (candidates.size() > 1 && slots.musicOf(slot) == currentlyPlaying) {
			slot = candidates.getInt((candidates.indexOf(slot) + 1) % candidates.size());
		}
		play(slot);
	}

	private static int playlistVarbit(final int playlist, final int index) {
		return FIRST_PLAYLIST_VARBIT + (playlist - 1) * PLAYLIST_SIZE + index;
	}

	/**
	 * Build 241 "Add to playlist N" / "Remove from playlist N" on the track in {@code slot}: the playlist varbits hold
	 * each track's {@code varpIndex * 100 + bit} code.
	 */
	public void togglePlaylist(final int playlist, final int slot) {
		final MusicSlots slots = MusicSlots.get();
		if (!slots.isTable() || playlist < 1 || playlist > PLAYLISTS) {
			return;
		}
		final int code = slots.playlistCodeOf(slot);
		final String name = slots.nameOf(slot).orElse(null);
		if (code <= 0 || name == null) {
			player.sendMessage("That track can't be added to a playlist.");
			return;
		}
		final VarManager manager = player.getVarManager();
		int free = -1;
		for (int i = 0; i < PLAYLIST_SIZE; i++) {
			final int value = manager.getBitValue(playlistVarbit(playlist, i));
			if (value == code) {
				manager.sendBit(playlistVarbit(playlist, i), 0);
				player.sendMessage(name + " has been removed from playlist " + playlist + ".");
				return;
			}
			if (value == 0 && free == -1) {
				free = i;
			}
		}
		if (free == -1) {
			player.sendMessage("Playlist " + playlist + " is full.");
			return;
		}
		manager.sendBit(playlistVarbit(playlist, free), code);
		player.sendMessage(name + " has been added to playlist " + playlist + ".");
	}

	/** The slot of the track playing now, or -1. */
	public int getCurrentSlot() {
		final Music music = currentlyPlaying;
		if (music == null) {
			return -1;
		}
		return MusicSlots.get().slotOf(music).orElse(-1);
	}

	public void stop() {
		setStopped(true);
		player.getPacketDispatcher().sendMusic(-1);
	}

	private boolean isDefaultsReset() {
		return player.getBooleanAttribute("reset default music tracks");
	}

	private void setDefaultsReset() {
		player.addAttribute("reset default music tracks", 1);
	}

	/**
	 * Restarts the currently playing track again(after setting music volume from 0 to > 0)
	 */
	public void restartCurrent() {
		final Music music = currentlyPlaying;
		if (music == null) {
			return;
		}
		if (stopped) stopped = false;
		nextSongAtTicks = music.getDuration();
		currentlyPlaying = music;
		ticks = 0;
	}

	/**
	 * Plays a random track.
	 */
	private void playRandomTrack() {
		Set<Music> list = null;
		if (player.getArea() != null && player.getArea() instanceof final MusicPlugin plugin) {
			list = plugin.getMusics(player);
		}

		if (list == null || list.isEmpty()) {
			list = World.getRegion(player.getLocation().getRegionId()).getMusicTracks();
			if (list == null || list.isEmpty()) {
				return;
			}
		}
		final Music randomTrack = Utils.getRandomCollectionElement(list);
		if (randomTrack == null || randomTrack == currentlyPlaying) {
			return;
		}
		final String trackName = randomTrack.getName();
		final OptionalInt slot = MusicSlots.get().slotOf(randomTrack);
		if (slot.isPresent()) {
			try {
				play(slot.getAsInt());
			} catch (Exception e) {
				logger.error("Music failed to play " + randomTrack, e);
			}
		} else if (MusicSlots.get().isTable()) {
			// A GadScape track the build-241 music table does not list (no jukebox slot): area music only.
			playTrack(randomTrack, -1);
		} else {
			logger.debug("Slot not found for track name \"" + trackName + "\""+ " "+randomTrack.getMusicId());
		}
	}

	/**
	 * Unlocks all the music tracks associated with the given region id.
	 */
	public void unlock(final int regionId) {
		final Set<Music> list = World.getRegion(regionId).getMusicTracks();
		if (list == null || list.isEmpty()) {
			return;
		}
		for (final Music music : list) {
			unlock(music, false);
		}
		if (!player.getBooleanSetting(Setting.AUTO_MUSIC)) {
			return;
		}
		playRandomTrack();
	}

	public void unlock(final Music music) {
		unlock(music, true);
	}

	public void unlock(final Music music, final boolean play) {
		final OptionalInt optionalSlot = MusicSlots.get().slotOf(music);
		if (!optionalSlot.isPresent()) {
			if (play && MusicSlots.get().isTable() && currentlyPlaying != music) {
				playTrack(music, -1);
			}
			return;
		}
		final int slot = optionalSlot.getAsInt();
		final OptionalInt optionalMusicIndex = MusicSlots.get().unlockOf(slot);
		if (!optionalMusicIndex.isPresent()) {
			return;
		}
		final int musicIndex = optionalMusicIndex.getAsInt();
		final int index = (musicIndex >> 14 & 16383) - 1;
		if (musicIndex == -1 || index < 0 || index >= VARP_IDS.length) {
			if (play && currentlyPlaying != music) {
				play(slot);
			}
			return;
		}
		final int varp = VARP_IDS[index];
		final int value = player.getVarManager().getValue(varp) | (1 << (musicIndex & 16383));
		if (!isUnlocked(slot)) {
			unlockedTracks.put(varp, value - (value & MusicLoader.DEFAULT_VARP_VALUES[index]));
			player.getVarManager().sendVar(varp, value);
			final boolean unlocked = player.getEmotesHandler().isUnlocked(Emote.AIR_GUITAR);
			if (!unlocked && unlockedMusicCount() >= 500) {
				player.getEmotesHandler().unlock(Emote.AIR_GUITAR);
				player.sendMessage(Colour.RS_GREEN.wrap("Congratulations, you've unlocked the Air Guitar emote!"));
			}
		}
		if (play) {
			if (currentlyPlaying == music) {
				return;
			}
			play(slot);
		}
	}

	public int unlockedMusicCount() {
		int count = 0;
		final VarManager varManager = player.getVarManager();
		for (final int varp : VARP_IDS) {
			count += Integer.bitCount(varManager.getValue(varp));
		}
		return count;
	}

	/**
	 * Processes the music player.
	 */
	void processMusicPlayer() {
		if (stopped) {
			return;
		}
		if (forcePlayNext || ++ticks >= nextSongAtTicks) {
			forcePlayNext = false;
			resetCurrent();
			ticks = 0;
			final int mode = getMode();
			// Build 241's Single mode repeats the chosen track; 211's Manual mode only did with Loop on.
			final boolean loop = player.getBooleanSetting(Setting.LOOP_MUSIC) || (mode == MODE_SINGLE && MusicSlots.get().isTable());
			if (!loop) {
				if (mode == MODE_AREA) {
					playRandomTrack();
				} else if (mode == MODE_SHUFFLE) {
					playShuffleTrack();
				} else {
					stopped = true;
				}
			} else {
				final Music current = currentlyPlaying;
				currentlyPlaying = null;
				if (current != null) {
					final OptionalInt slot = MusicSlots.get().slotOf(current);
					if (slot.isPresent()) {
						play(slot.getAsInt());
					} else {
						playTrack(current, -1);
					}
				}
			}
		}
	}

	public void playJingle(final int jingle) {
		this.ticks = 0;
		player.getPacketDispatcher().playJingle(jingle);
	}

	/**
	 * Sends the hint for the music track at the given slot.
	 */
	public void sendUnlockHint(final int slotId) {
		final MusicSlots slots = MusicSlots.get();
		final Music music = slots.musicOf(slotId);
		final String hint = music != null && music.getHint() != null ? music.getHint().replace("unlocked ", "") : slots.hintOf(slotId);
		if (hint == null || hint.isEmpty()) {
			return;
		}
		player.sendMessage((!isUnlocked(slotId) ? "This track unlocks " : "This track was unlocked ") + hint);
	}

	/**
	 * Resets the current music by stopping it client-sided.
	 */
	private void resetCurrent() {
		// Only send packet is music volume is not 0
		if (player.getVarManager().getValue(SettingVariables.MUSIC_VOLUME_VARP_ID) != 0)
			player.getPacketDispatcher().sendMusic(-1);
	}

	/**
	 * Whether the track at the given slot is unlocked or not.
	 */
	private boolean isUnlocked(final int slot) {
		final OptionalInt optionalRandomSong = MusicSlots.get().unlockOf(slot);
		if (!optionalRandomSong.isPresent()) {
			return false;
		}
		final int randomSong = optionalRandomSong.getAsInt();
		if (randomSong == -1) {
			return true;
		}
		final int index = (randomSong >> 14 & 16383) - 1;
		if (index < 0) {
			return true;
		}
		if (index >= MusicSlots.get().unlockVarpCount(VARP_IDS.length)) {
			return false;
		}
		final int value = unlockedTracks.get(VARP_IDS[index]) | MusicLoader.DEFAULT_VARP_VALUES[index];
		final int bitIndex = randomSong & 16383;
		return (value >> bitIndex & 1) == 1;
	}

	/**
	 * Attempts to play the track at the requested slot. If it's not unlocked, or an error is thrown, returns as false and stops.
	 */
	public boolean play(final int slot) {
		if (!isUnlocked(slot)) {
			return false;
		}
		final Music music = MusicSlots.get().musicOf(slot);
		if (music == null) {
			return false;
		}
		playTrack(music, slot);
		return true;
	}

	/**
	 * Plays {@code music}. With the build-241 track list the midi is the one the music table gives the jukebox slot
	 * (the cache's own music archive) and music_current_track is set so the jukebox and the now-playing line follow.
	 */
	private void playTrack(final Music music, final int slot) {
		if (currentlyPlaying == music) {
			resetCurrent();
		}
		nextSongAtTicks = music.getDuration();
		ticks = 0;
		currentlyPlaying = music;
		stopped = false;
		final MusicSlots slots = MusicSlots.get();
		final int midi = slot != -1 && slots.midiOf(slot) >= 0 ? slots.midiOf(slot) : music.getMusicId();
		player.getPacketDispatcher().sendMusic(midi);
		if (slots.isTable()) {
			player.getVarManager().sendVar(CURRENT_TRACK_VARP, slots.rowOf(slot));
		}
		if (GameInterface.MUSIC_TAB.getPlugin().isPresent()) player.getPacketDispatcher().sendComponentText(GameInterface.MUSIC_TAB, GameInterface.MUSIC_TAB.getPlugin().get().getComponent("Song name"), slot != -1 ? slots.nameOf(slot).orElse(music.getName()) : music.getName());
		TreasureTrail.playSong(player, music.getName());
	}

	public Int2IntOpenHashMap getUnlockedTracks() {
		return unlockedTracks;
	}

	public boolean isStopped() {
		return stopped;
	}

	public void setStopped(boolean stopped) {
		this.stopped = stopped;
	}

	public void setForcePlayNext() { forcePlayNext = true; }
}
