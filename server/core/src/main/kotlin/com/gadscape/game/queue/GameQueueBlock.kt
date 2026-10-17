package com.gadscape.game.queue

@JvmInline
value class GameQueueBlock(val block: suspend () -> Unit)