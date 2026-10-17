package controllers

import cache.definitions.ModelDefinition
import cache.loaders.ModelLoader
import com.displee.cache.CacheLibrary
import utils.AnimCache
import utils.ItemFull
import utils.ItemPreview
import utils.ItemStore

/** Plays an animation on a plain player holding or wearing an item, the way the game shows it. */
object ItemOnPlayer {
    class Result(val model: ModelDefinition?, val message: String)

    /**
     * Shows [item] on a male or [female] player in [view] and plays animation [seq] with [playback]. Some animations
     * put another item in the hand or on the shield arm, or hide the item, while they play; that's shown too.
     */
    fun play(item: ItemFull, female: Boolean, seq: Int?, lib: CacheLibrary, models: ModelLoader, anims: AnimCache,
             view: ModelView3D, playback: AnimPlayback): Result {
        if (seq == null || seq < 0) return Result(null, "No animation id there.")
        val sd = try { anims.seq(seq) } catch (e: Exception) { null }
        // the game's equipment values: item id + 512, 0 = nothing in that slot
        val held = sd?.weapon?.takeIf { it >= 512 }?.let { ItemStore.read(lib, it - 512) }
        val shield = sd?.shield?.takeIf { it >= 512 }?.let { ItemStore.read(lib, it - 512) }
        val emptyHand = sd != null && sd.weapon in 0..511 && item.wearPos1 == 3
        val emptyShield = sd != null && sd.shield in 0..511 && item.wearPos1 == 5
        val main = when { held != null && item.wearPos1 == 3 -> held; shield != null && item.wearPos1 == 5 -> shield; else -> item }
        val extra = listOfNotNull(held?.takeIf { main !== it }, shield?.takeIf { main !== it })
        val md = try {
            if (emptyHand || emptyShield) ItemPreview.onPlayer(ItemFull(-1), female, lib, models, extra) else ItemPreview.onPlayer(main, female, lib, models, extra)
        } catch (e: Exception) { null } ?: return Result(null, "Couldn't build the player model.")
        view.show(md)
        val (clip, note) = AnimPlayback.clipFor(md, seq, anims)
        playback.play(clip)
        val msg = if (clip == null) note else "Playing animation $seq (${clip.frameCount} frames) on a ${if (female) "female" else "male"} player with ${item.name}." +
                (if (held != null) " The animation shows ${held.name} in the hand." else "") + (if (emptyHand || emptyShield) " The animation hides the item while it plays." else "") +
                (if (shield != null) " It shows ${shield.name} on the shield arm." else "") + (if (note.isNotEmpty()) " $note" else "")
        return Result(md, msg)
    }
}
