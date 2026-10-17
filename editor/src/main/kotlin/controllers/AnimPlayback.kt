package controllers

import cache.definitions.ModelDefinition
import javafx.animation.AnimationTimer
import utils.AnimCache
import utils.Animator
import utils.SeqDef

/**
 * One animation ready to play on one model: [frameCount] poses, each shown for [delay] client cycles (20 ms).
 * [pose] moves the model's vertices into frame [i] and returns them.
 */
interface AnimClip {
    val frameCount: Int
    fun delay(i: Int): Int
    fun pose(i: Int): Triple<IntArray, IntArray, IntArray>
    /** False when the model has nothing this animation can move. */
    val moves: Boolean
}

/** A classic animation: frames from index 0 moving the model's vertex groups. */
class FrameClip(md: ModelDefinition, private val seq: SeqDef, private val anims: AnimCache) : AnimClip {
    private val animator = Animator(md.vertexPositionsX.clone(), md.vertexPositionsY.clone(), md.vertexPositionsZ.clone(), md.vertexSkins)
    override val frameCount get() = seq.frameIds.size
    override fun delay(i: Int) = seq.delays.getOrElse(i) { 1 }.coerceAtLeast(1)
    override fun pose(i: Int): Triple<IntArray, IntArray, IntArray> {
        animator.pose(anims.frame(seq.frameIds[i]))
        return Triple(animator.x, animator.y, animator.z)
    }
    override val moves get() = animator.animatable
}

/** Plays an [AnimClip] on a [ModelView3D], looping. */
class AnimPlayback(private val view: ModelView3D) {
    private var clip: AnimClip? = null
    private var index = 0
    private var time = 0.0
    private var last = 0L
    var playing = true
    var speed = 1.0
    /** Called with the frame number each time it changes. */
    var onFrame: (Int) -> Unit = {}

    private val timer = object : AnimationTimer() {
        override fun handle(now: Long) {
            val dt = if (last == 0L) 0.0 else (now - last) / 1e9
            last = now
            val c = clip ?: return
            if (!playing || c.frameCount == 0) return
            time += dt * 50 * speed
            val d = c.delay(index)
            if (time >= d) { time -= d; if (time > d * 4) time = 0.0; show((index + 1) % c.frameCount) }
        }
    }

    val current get() = clip
    val frame get() = index

    fun play(c: AnimClip?) {
        clip = c; index = 0; time = 0.0; last = 0L
        if (c != null && c.frameCount > 0) show(0)
        timer.start()
    }

    fun show(i: Int) {
        val c = clip ?: return
        if (c.frameCount == 0) return
        index = Math.floorMod(i, c.frameCount)
        val (x, y, z) = c.pose(index)
        view.update(x, y, z)
        onFrame(index)
    }

    fun stop() { clip = null; timer.stop() }

    companion object {
        /** The clip for [seqId] on [md], or null with the reason. */
        fun clipFor(md: ModelDefinition, seqId: Int, anims: AnimCache): Pair<AnimClip?, String> {
            val seq = try { anims.seq(seqId) } catch (e: Exception) { null } ?: return null to "Animation $seqId isn't in the cache."
            if (seq.skeletalModel != -1) return utils.SkeletalAnims.clip(md, seq, anims)
            if (seq.frameIds.isEmpty()) return null to "Animation $seqId has no frames."
            val c = FrameClip(md, seq, anims)
            return c to if (c.moves) "" else "That model can't move (no animation labels)."
        }
    }
}
