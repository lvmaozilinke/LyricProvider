/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed.model

data class SongInfo(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val duration: Long = 0L
)
