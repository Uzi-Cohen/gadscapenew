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
     * Where a model being tried on goes: [slot] null = instead of the item's own worn models, else worn in that
     * equipment slot (hiding [hides] body parts, as an item's "also hides" would).
     */
    class Place(val label: String, val slot: Int?, val hides: Int = -1, val hides2: Int = -1)

    val PLACES = listOf(
        Place("Instead of the item's worn model", null),
        Place("In the hand (weapon)", 3),
        Place("On the shield arm", 5),
        Place("On the head (hair shows)", 0),
        Place("On the head, hiding hair and beard (like a full helm)", 0, 8, 11),
        Place("On the body (arms show)", 4),
        Place("On the body, hiding the arms (like a platebody)", 4, 6),
        Place("On the legs", 7),
        Place("On the hands", 9),
        Place("On the feet", 10),
        Place("As a cape", 1),
        Place("Round the neck", 2))

    /**
     * What to put on the player to try model [modelId] on [item] (null = no item): the main item and anything worn
     * with it. The item keeps its colour changes when the model replaces its own; an item that isn't worn shows the
     * model in the hand.
     */
    fun dress(item: ItemFull?, modelId: Int, place: Place): Pair<ItemFull, List<ItemFull>> {
        val slot = place.slot
        if (slot == null) {
            val main = (item?.copy(item.id) ?: ItemFull(-2).apply { name = "Model $modelId" }).apply {
                maleModel0 = modelId; maleModel1 = -1; maleModel2 = -1
                femaleModel0 = modelId; femaleModel1 = -1; femaleModel2 = -1
                if (wearPos1 == -1) wearPos1 = 3
            }
            return main to emptyList()
        }
        val holder = ItemFull(-2).apply {
            name = "Model $modelId"; wearPos1 = slot; wearPos2 = place.hides; wearPos3 = place.hides2
            maleModel0 = modelId; femaleModel0 = modelId
        }
        return if (item != null && item.wearPos1 >= 0 && item.wearPos1 != slot) item to listOf(holder) else holder to emptyList()
    }

    /**
     * Shows [item] on a male or [female] player in [view] and plays animation [seq] with [playback]. Some animations
     * put another item in the hand or on the shield arm, or hide the item, while they play; that's shown too.
     * [also] = more things worn at the same time.
     */
    fun play(item: ItemFull, female: Boolean, seq: Int?, lib: CacheLibrary, models: ModelLoader, anims: AnimCache,
             view: ModelView3D, playback: AnimPlayback, also: List<ItemFull> = emptyList()): Result {
        if (seq == null) return Result(null, "No animation id there.")
        if (seq < 0) return Result(null, "Animation ids are 0 or more.")
        val (md, clip, msg) = build(item, female, seq, lib, models, anims, also)
        if (md == null) { playback.stop(); return Result(null, msg) }
        playback.stop()
        view.show(md)
        playback.play(clip)
        return Result(md, msg)
    }

    /** The dressed player and the clip of [seq] on it (null when it can't play), with what to tell the user. */
    fun build(item: ItemFull, female: Boolean, seq: Int, lib: CacheLibrary, models: ModelLoader, anims: AnimCache,
              also: List<ItemFull> = emptyList()): Triple<ModelDefinition?, AnimClip?, String> {
        val sd = try { anims.seq(seq) } catch (e: Exception) { null }
            ?: return Triple(null, null, "There's no animation $seq in the cache.")
        // the game's equipment values: item id + 512, 0 = nothing in that slot
        val held = sd.weapon.takeIf { it >= 512 }?.let { try { ItemStore.read(lib, it - 512) } catch (e: Exception) { null } }
        val shield = sd.shield.takeIf { it >= 512 }?.let { try { ItemStore.read(lib, it - 512) } catch (e: Exception) { null } }
        val worn = listOf(item) + also
        val emptyHand = sd.weapon in 0..511 && worn.any { it.wearPos1 == 3 }
        val emptyShield = sd.shield in 0..511 && worn.any { it.wearPos1 == 5 }
        // what the animation takes out of the hands, and what it puts in them
        val shown = worn.filter { w ->
            !(w.wearPos1 == 3 && (emptyHand || held != null)) && !(w.wearPos1 == 5 && (emptyShield || shield != null))
        } + listOfNotNull(held, shield)
        val md = try {
            if (shown.isEmpty()) ItemPreview.onPlayer(ItemFull(-1), female, lib, models)
            else ItemPreview.onPlayer(shown.first(), female, lib, models, shown.drop(1))
        } catch (e: Exception) { null } ?: return Triple(null, null, "Couldn't build the player model.")
        val (clip, note) = AnimPlayback.clipFor(md, seq, anims)
        // a classic animation made for another skeleton (an NPC's) twists the player's parts at random
        val playerRig = try { anims.seq(808)?.frameIds?.firstOrNull()?.let { anims.frame(it)?.skeletonId } } catch (e: Exception) { null }
        val rig = if (sd.skeletalModel != -1) null else try { sd.frameIds.firstOrNull()?.let { anims.frame(it)?.skeletonId } } catch (e: Exception) { null }
        val rigNote = if (playerRig != null && rig != null && rig != playerRig) " This animation is made for a different body (not a player's), so the player moves strangely; in game a player can't use it." else ""
        val msg = if (clip == null) note else "Playing ${utils.AnimCatalog.label(seq)} (${clip.frameCount} frames) on a ${if (female) "female" else "male"} player with ${item.name}." +
                (if (held != null) " The animation shows ${held.name} in the hand." else "") + (if (emptyHand || emptyShield) " The animation hides what's in that hand while it plays." else "") +
                (if (shield != null) " It shows ${shield.name} on the shield arm." else "") + (if (note.isNotEmpty()) " $note" else "") + rigNote
        return Triple(md, clip, msg)
    }
}
