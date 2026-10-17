package utils

import cache.definitions.ModelDefinition
import controllers.AnimClip

/** Skeletal (2021+) animations. */
object SkeletalAnims {
    fun clip(md: ModelDefinition, seq: SeqDef, anims: AnimCache): Pair<AnimClip?, String> =
        null to "Animation ${seq.id} uses the skeletal format, which can't be played yet."
}
