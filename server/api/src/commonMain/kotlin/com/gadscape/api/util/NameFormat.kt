package com.gadscape.api.util

fun String.formatUsername() =
    lowercase().replace("_", " ")
