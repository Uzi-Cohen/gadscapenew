@file:JvmName("ReferenceCountedExtensions")

package com.gadscape.buffer

import io.netty.util.ReferenceCounted

inline fun <T : ReferenceCounted?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.release()
    }
}