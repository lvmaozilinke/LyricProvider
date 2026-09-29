/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import android.os.SystemClock
import io.github.proify.lyricon.amcfyprovider.xposed.model.SongInfo
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.RemotePlayer
import com.highcapable.yukihookapi.hook.log.YLog

/**
 * Playback bridge: receives hook data and pushes it to the Lyricon provider.
 */
object PlaybackBridge {
    private const val DEFAULT_LINE_DURATION_MS = 5_000L

    private var player: RemotePlayer? = null

    private var currentSongId: String? = null
    private var currentSong: SongInfo? = null

    /** Pending lyrics and the song id they belong to (prevents stale lyrics from a previous song). */
    private var pendingLyrics: List<RichLyricLine>? = null
    private var pendingLyricsForId: String? = null

    /** Lyrics collected from the app's live lyric-line updates (notification artist field). */
    private var collectedLyrics: MutableList<RichLyricLine>? = null
    private var lastCapturedLine: String? = null
    private var lastCapturedBegin: Long = -1L
    private var lastCaptureWallTime: Long = -1L

    /** Last Song pushed to Lyricon (read by the lock-screen lyricInfo injector). */
    private var lastSong: Song? = null
    private var lastLyricsKey: String? = null

    /** Callback invoked when lyrics become ready, used for MediaSession lyricInfo injection. */
    var onLyricsReady: ((Song) -> Unit)? = null

    private var isPlaying = false
    private var lastPosition = -1L
    private var pendingSeek = -1L

    fun init(remotePlayer: RemotePlayer) {
        player = remotePlayer
    }

    // ---------------------------------------------------------------- Song change

    fun onSongChanged(info: SongInfo) {
        if (info.id == currentSongId) {
            // The app may publish the album name as the first metadata title; refine it
            // to the first real title-like value (skipping credit lines).
            val current = currentSong
            if (current != null &&
                info.title != null &&
                info.album != null &&
                current.title == current.album &&
                info.title != info.album &&
                isTitleLike(info.title)
            ) {
                currentSong = current.copy(title = info.title)
                YLog.info("Refined song title -> ${info.title}")
                pushSong()
            }
            return
        }

        currentSongId = info.id
        currentSong = info
        pendingLyrics = null
        pendingLyricsForId = null
        collectedLyrics = null
        lastCapturedLine = null
        lastCapturedBegin = -1L
        lastCaptureWallTime = -1L
        lastLyricsKey = null
        lastPosition = -1L
        pendingSeek = -1L

        YLog.info("Song changed -> ${info.title} / ${info.artist} / ${info.id}")
        pushSong()
    }

    /**
     * audio_service handler channel setState/setMediaItem calls.
     * The mediaItem may carry lyric data.
     */
    fun onHandlerPayload(method: String, arguments: Any?) {
        if (arguments !is Map<*, *>) return

        val mediaItem = arguments["mediaItem"] as? Map<*, *> ?: arguments["queue"]?.let { queue ->
            (queue as? List<*>)?.firstOrNull { it is Map<*, *> } as? Map<*, *>
        } ?: return

        val title = mediaItem["title"]?.toString()
        val artist = mediaItem["artist"]?.toString()
        val id = (mediaItem["id"] ?: mediaItem["mediaId"])?.toString() ?: return

        if (currentSongId == null || currentSongId != id) {
            currentSongId = id
            currentSong = SongInfo(
                id = id,
                title = title,
                artist = artist,
                duration = numberValue(mediaItem["duration"]) ?: 0L
            )
            pendingLyrics = null
            pendingLyricsForId = null
            collectedLyrics = null
            lastCapturedLine = null
            lastCapturedBegin = -1L
            lastCaptureWallTime = -1L
            lastLyricsKey = null
            lastPosition = -1L
            pushSong()
        }

        extractLyrics(mediaItem)?.let { lyrics ->
            if (currentSongId == id) {
                pendingLyrics = lyrics
                pendingLyricsForId = id
                pushSong()
            } else {
                YLog.debug("Ignored lyrics from mediaItem $id (current=$currentSongId)")
            }
        }
    }

    fun onWidgetPayload(arguments: Any?) {
        // Desktop widget data: songTitle/artistName/isPlaying etc. Used as auxiliary metadata only.
        if (arguments !is Map<*, *>) return
        val title = arguments["songTitle"]?.toString()
        val artist = arguments["artistName"]?.toString()
        val playing = arguments["isPlaying"] as? Boolean
        if (title.isNullOrBlank()) return

        if (currentSong == null || currentSong?.title != title) {
            val id = currentSongId ?: title.hashCode().toString()
            currentSongId = id
            currentSong = SongInfo(
                id = id,
                title = title,
                artist = artist,
                duration = currentSong?.duration ?: 0L
            )
            pendingLyrics = null
            pendingLyricsForId = null
            collectedLyrics = null
            lastCapturedLine = null
            lastCapturedBegin = -1L
            lastCaptureWallTime = -1L
            lastLyricsKey = null
            pushSong()
        }
        playing?.let { onPlayingChanged(it) }
    }

    /**
     * The app updates createMediaMetadata with the currently displayed lyric line
     * (passed in the second argument) as the song plays. Collect these lines with
     * the current playback position to build a timed lyric list on the fly.
     */
    fun onLyricLineCaptured(mediaId: String, lineText: String) {
        if (currentSongId == null || mediaId != currentSongId) return
        if (lineText.isBlank()) return

        val title = currentSong?.title
        if (title != null && lineText == title) return
        val artist = currentSong?.artist
        if (artist != null && lineText == artist) return
        val album = currentSong?.album
        if (album != null && lineText == album) return

        // Prefer the framework playback position; fall back to wall-clock accumulation
        // (line changes arrive in real time, so the gap between captures ~ line duration).
        val now = SystemClock.elapsedRealtime()
        val begin = if (lastPosition > 0) {
            lastPosition
        } else if (lastCaptureWallTime >= 0 && lastCapturedBegin >= 0) {
            lastCapturedBegin + (now - lastCaptureWallTime)
        } else {
            0L
        }
        lastCaptureWallTime = now
        val effectiveBegin = if (begin <= lastCapturedBegin) lastCapturedBegin + 1 else begin

        // Skip repeated lines within 30s (e.g. the same line being re-sent), allow chorus repeats later.
        if (lineText == lastCapturedLine && effectiveBegin - lastCapturedBegin < 30_000L) return

        val lines = collectedLyrics ?: mutableListOf<RichLyricLine>().also { collectedLyrics = it }

        // Retro-fit the previous line's end/duration now that the next line's start is known.
        // Lyricon's normalize() drops lines unless begin < end and duration > 0.
        lines.lastOrNull()?.let { prev ->
            if (effectiveBegin > prev.begin && prev.end != effectiveBegin) {
                prev.end = effectiveBegin
                prev.duration = effectiveBegin - prev.begin
            }
        }

        val line = RichLyricLine(begin = effectiveBegin, text = lineText)
        line.end = effectiveBegin + DEFAULT_LINE_DURATION_MS
        line.duration = DEFAULT_LINE_DURATION_MS
        lines += line
        lastCapturedLine = lineText
        lastCapturedBegin = effectiveBegin

        YLog.debug("Captured lyric line [$effectiveBegin] $lineText (total=${lines.size})")
        pendingLyrics = lines
        pendingLyricsForId = currentSongId
        pushSong()
    }

    fun onLyricPayload(payload: Any?) {
        val lyrics = LyricsParser.parse(payload) ?: return
        val songId = currentSongId ?: return
        if (currentSong == null) return

        pendingLyrics = lyrics
        pendingLyricsForId = songId
        pushSong()
    }

    fun getCurrentSong(): Song? = lastSong

    // ---------------------------------------------------------------- Playback state

    fun onPlaybackInfo(state: Int?, position: Long) {
        onPlayingChanged(state == android.media.session.PlaybackState.STATE_PLAYING)

        // First position update after a song change -> seek
        if (pendingSeek >= 0 && position > 0) {
            player?.seekTo(position)
            pendingSeek = -1
        }

        // Position updates (roughly 4x/second)
        if (position != lastPosition) {
            lastPosition = position
            player?.setPosition(position)
        }
    }

    fun onPlayingChanged(playing: Boolean) {
        if (isPlaying == playing) return
        isPlaying = playing
        player?.setPlaybackState(playing)
        if (playing) {
            pendingSeek = lastPosition
        }
    }

    // ---------------------------------------------------------------- Push

    private fun pushSong() {
        val info = currentSong ?: return
        val lyrics = pendingLyrics.takeIf { pendingLyricsForId == currentSongId && !it.isNullOrEmpty() }

        if (lyrics.isNullOrEmpty()) {
            val song = Song(
                id = info.id,
                name = info.title,
                artist = info.artist,
                duration = info.duration
            )
            lastSong = song
            lastLyricsKey = null
            player?.setSong(song)
            return
        }

        val song = Song(
            id = info.id,
            name = info.title,
            artist = info.artist,
            duration = info.duration,
            lyrics = lyrics
        )

        // Skip pushing identical song+lyrics repeatedly to avoid lock screen / status bar refresh loops
        val lyricsKey = lyricsKey(lyrics)
        val unchanged = song.id == lastSong?.id &&
            song.name == lastSong?.name &&
            lyricsKey == lastLyricsKey
        if (unchanged) return

        lastSong = song
        lastLyricsKey = lyricsKey
        player?.setSong(song)
        player?.setPlaybackState(isPlaying)
        if (lastPosition > 0) player?.setPosition(lastPosition)

        onLyricsReady?.invoke(song)
    }

    private fun lyricsKey(lyrics: List<RichLyricLine>): String =
        lyrics.joinToString("|") { "${it.begin}:${it.end}:${it.text}" }

    private fun isTitleLike(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 2) return false
        val creditPrefixes = listOf(
            "作词", "作曲", "编曲", "词：", "曲：", "词:", "曲:", "制作", "出品", "发行", "OP", "SP"
        )
        return creditPrefixes.none { text.startsWith(it, ignoreCase = true) }
    }

    private fun extractLyrics(mediaItem: Map<*, *>): List<RichLyricLine>? {
        LyricsParser.parse(mediaItem["lyrics"] ?: mediaItem["lyric"] ?: mediaItem["lrc"])?.let {
            return it
        }
        val extras = mediaItem["extras"] as? Map<*, *>
        return extras?.let { LyricsParser.parse(it) }
    }

    private fun numberValue(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toDoubleOrNull()?.toLong()
        else -> null
    }
}
