package com.gadscape.raids.tfa.hud

import com.gadscape.raids.tfa.ids.TfaIds
import com.gadscape.raids.tfa.raid.TfaRoomId
import com.zenyte.game.world.entity.player.Player
import com.zenyte.game.world.entity.player.VarManager
import org.slf4j.LoggerFactory

/**
 * Raid music (spec §3.7 "Music", §5.8): on encounter start the room's midi plays for every member and spectator in
 * the room, and its music dbrow's unlock bit is set. Every TFA track unlocks through music varp index 27
 * = `musicmulti_27`, varp **5238** [CACHE varp symbols], at the bit of the §3.7 table (`TfaIds.MusicUnlock`):
 *
 * | Track | dbrow | midi | bit |
 * |---|---|---|---|
 * | Into the Archive (lobby) | 17001 | 891 | 22 |
 * | The Archive Guardian | 17002 | 887 | 23 |
 * | Blade of Forgotten Justice (Ezekiel) | 17003 | 890 | 24 |
 * | Bork's Beats | 17004 | 888 | 25 |
 * | Triple Threat (Dagon'hai) | 17005 | 889 | 26 |
 * | Hellatine (Dreadnoth) | 17006 | 895 | 27 |
 * | Daughter of Darkness (Moia) | 17007 | 892 | 28 |
 * | Forged Through Fire (Xarth) | 17008 | 896 | 29 |
 * | Artifacts of Old (Ocellus P1/P2) | 17009 | 893 | 30 |
 * | Ascension (Ocellus P3/P4) | 17010 | 894 | 31 |
 *
 * The auto-music player is paused while a raid track plays (so a region track does not replace it) and resumed by
 * [resume] when the player leaves the raid.
 */
object MusicUnlock {

    private val log = LoggerFactory.getLogger(MusicUnlock::class.java)

    /** `musicmulti_27` [CACHE]: the music unlock varp of index 27. */
    const val UNLOCK_VARP = 5238

    /** Registers the unlock varp as saved with the player (once, at launch). */
    @JvmStatic
    fun install() {
        try {
            VarManager.appendPersistentVarp(UNLOCK_VARP)
        } catch (e: Exception) {
            log.warn("Fractured Archive: could not register varp {} as persistent", UNLOCK_VARP, e)
        }
    }

    /** Plays and unlocks [room]'s track (Ocellus P1/P2 for [TfaRoomId.OCELLUS]). */
    @JvmStatic
    fun play(player: Player, room: TfaRoomId) = play(player, room.midi, room.musicUnlockBit)

    /** Plays and unlocks "Ascension" (Ocellus P3/P4, dbrow 17010, midi 894, bit 31). */
    @JvmStatic
    fun playOcellusP3(player: Player) = play(player, TfaIds.Midi.OCELLUS_P3, TfaIds.MusicUnlock.OCELLUS_P3_BIT)

    /** Plays [midi] and sets [bit] of varp 5238. */
    @JvmStatic
    fun play(player: Player, midi: Int, bit: Int) {
        try {
            unlock(player, bit)
            player.music.isStopped = true
            player.packetDispatcher.sendMusic(midi)
        } catch (e: Exception) {
            log.warn("Fractured Archive: music {} failed for {}", midi, player.username, e)
        }
    }

    /** Sets [bit] of the unlock varp (no-op if already set). */
    @JvmStatic
    fun unlock(player: Player, bit: Int) {
        if (bit !in 0..31) return
        val vm = player.varManager
        val value = vm.getValue(UNLOCK_VARP)
        if (value and (1 shl bit) != 0) return
        vm.sendVar(UNLOCK_VARP, value or (1 shl bit))
    }

    @JvmStatic
    fun isUnlocked(player: Player, bit: Int): Boolean = player.varManager.getValue(UNLOCK_VARP) and (1 shl bit) != 0

    /** Hands music back to the auto-player (leaving the raid). */
    @JvmStatic
    fun resume(player: Player) {
        try {
            val music = player.music
            if (music.isStopped) {
                music.isStopped = false
                music.setForcePlayNext()
            }
        } catch (e: Exception) {
            log.warn("Fractured Archive: resuming music failed for {}", player.username, e)
        }
    }
}
