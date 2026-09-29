/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.amcfyprovider.xposed.model.SongInfo
import org.json.JSONObject
import java.lang.reflect.Field

/**
 * ColorOS lock-screen lyric bridge (ported from the ColorOS apple-music module).
 *
 * Injects lyricInfo JSON into the metadata during MediaSession.setMetadata (before-hook):
 * {
 *   "songName": "title",
 *   "artist": "artist",
 *   "songId": "song id",
 *   "lyric": "[00:00.00]lyric line\n...",
 *   "rawLyric": "[00:00.000]word-by-word lyrics"
 * }
 */
object MediaSessionLyricInfoHooker {
    private const val TAG = "LyricInfoHook"

    @Volatile
    var lastMediaSession: MediaSession? = null

    @Volatile
    var lastMediaMetadata: MediaMetadata? = null

    private var bundleField: Field? = null

    fun install() {
        bundleField = runCatching {
            MediaMetadata::class.java.getDeclaredField("mBundle").also { it.isAccessible = true }
        }.getOrElse {
            MediaMetadata::class.java.declaredFields.firstOrNull { f ->
                f.type == Bundle::class.java
            }?.also { it.isAccessible = true }
        }

        XposedHelpers.findAndHookMethod(
            "android.media.session.MediaSession",
            MediaSession::class.java.classLoader,
            "setMetadata",
            MediaMetadata::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    lastMediaSession = param.thisObject as? MediaSession
                    val metadata = param.args[0] as? MediaMetadata ?: return
                    lastMediaMetadata = metadata

                    // Framework MediaMetadata is not obfuscated: extract song info directly
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                        ?: title?.hashCode()?.toString()
                    if (title != null && id != null) {
                        PlaybackBridge.onSongChanged(
                            SongInfo(
                                id = id,
                                title = title,
                                artist = artist,
                                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                            )
                        )
                    }

                    val song = PlaybackBridge.getCurrentSong()?.takeIf {
                        !it.lyrics.isNullOrEmpty()
                    } ?: return

                    // The app may broadcast the current lyric line as the metadata TITLE,
                    // so match by stable MEDIA_ID instead of title.
                    val metadataId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    if (metadataId != null && song.id != null && metadataId != song.id) {
                        YLog.debug(tag = TAG, msg = "Skip inject: media id mismatch '$metadataId' vs '${song.id}'")
                        return
                    }

                    try {
                        val lyricInfo = buildLyricInfoJson(song)
                        if (lyricInfo.isBlank()) return
                        if (metadata.getString("lyricInfo") == lyricInfo) return

                        val field = bundleField
                        if (field != null) {
                            val bundle = field.get(metadata) as? Bundle
                            if (bundle != null) {
                                bundle.putString("lyricInfo", lyricInfo)
                                return
                            }
                        }

                        val patched = MediaMetadata.Builder(metadata)
                            .putString("lyricInfo", lyricInfo)
                            .build()
                        param.args[0] = patched
                    } catch (e: Exception) {
                        YLog.error(tag = TAG, msg = "Failed to inject lyricInfo", e = e)
                    }
                }
            }
        )
        YLog.debug(tag = TAG, msg = "MediaSession.setMetadata hooked for lyricInfo injection")

        // Framework-level playback state: reliable source of playing/paused + position
        XposedHelpers.findAndHookMethod(
            "android.media.session.MediaSession",
            MediaSession::class.java.classLoader,
            "setPlaybackState",
            PlaybackState::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val state = param.args[0] as? PlaybackState ?: return
                    YLog.debug(
                        tag = TAG,
                        msg = "PlaybackState -> state=${state.state}, position=${state.position}"
                    )
                    PlaybackBridge.onPlaybackInfo(state.state, state.position)
                }
            }
        )
        YLog.debug(tag = TAG, msg = "MediaSession.setPlaybackState hooked for playback state")
    }

    /** Re-publishes the current metadata so the before-hook injects lyricInfo. */
    fun republishMetadataWithLyricInfo(song: Song) {
        val session = lastMediaSession ?: return
        val metadata = lastMediaMetadata ?: return
        try {
            val metadataId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            if (metadataId != null && song.id != null && metadataId != song.id) {
                YLog.debug(tag = TAG, msg = "Skip republish: media id mismatch '$metadataId' vs '${song.id}'")
                return
            }
            session.setMetadata(metadata)
            YLog.debug(tag = TAG, msg = "Republished metadata with lyricInfo for: ${song.name}")
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to republish metadata", e = e)
        }
    }

    fun buildLyricInfoJson(song: Song): String {
        val lines = song.lyrics
        if (lines.isNullOrEmpty()) return ""

        val lyricSb = StringBuilder()
        val rawLyricSb = StringBuilder()

        for (line in lines) {
            val beginMs = line.begin
            val text = line.text ?: continue
            if (text.isBlank()) continue

            val words = line.words
            lyricSb.append("[${formatLrcTime(beginMs)}]$text\n")

            if (!words.isNullOrEmpty()) {
                appendRawLyricWords(rawLyricSb, words)
            } else {
                rawLyricSb.append("[${formatLrcTimeMs(beginMs)}]$text\n")
            }
        }

        if (lyricSb.isEmpty()) return ""

        return JSONObject().apply {
            put("songName", song.name ?: "")
            put("artist", song.artist ?: "")
            put("songId", song.id ?: "")
            put("lyric", lyricSb.toString().trimEnd())
            put("rawLyric", rawLyricSb.toString().trimEnd())
        }.toString()
    }

    private fun appendRawLyricWords(sb: StringBuilder, words: List<io.github.proify.lyricon.lyric.model.LyricWord>) {
        if (words.isEmpty()) return
        for ((i, word) in words.withIndex()) {
            val wText = word.text ?: continue
            if (wText.isBlank()) continue
            if (i > 0) sb.append(" ")
            sb.append("[${formatLrcTimeMs(word.begin)}]$wText[${formatLrcTimeMs(word.end)}]")
        }
        sb.append("\n")
    }

    private fun formatLrcTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val centiseconds = (ms % 1000) / 10
        return "%02d:%02d.%02d".format(minutes, seconds, centiseconds)
    }

    private fun formatLrcTimeMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
