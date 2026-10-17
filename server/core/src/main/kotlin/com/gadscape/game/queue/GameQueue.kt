package com.gadscape.game.queue

import com.gadscape.game.coroutine.GameCoroutineTask

@JvmInline
value class GameQueue(val task: GameCoroutineTask)