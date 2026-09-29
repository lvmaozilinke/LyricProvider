/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlin.math.roundToLong

/**
 * 歌词解析：支持 LRC 文本、[{begin,text,...}] 列表、以及包含 lyrics 键的 Map。
 * 额外兼容逐字歌词字段（words/word）。
 */
object LyricsParser {

    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(payload: Any?): List<RichLyricLine>? = when (payload) {
        is String -> parseLrc(payload)
        is List<*> -> parseList(payload)
        is Map<*, *> -> {
            val lyricValue = payload.entries.firstOrNull { entry ->
                entry.key.toString().contains("lyric", ignoreCase = true)
            }?.value
            parse(lyricValue)
        }

        else -> null
    }

    private fun parseLrc(text: String): List<RichLyricLine>? {
        if (text.isBlank()) return null
        val result = mutableListOf<RichLyricLine>()

        text.lineSequence().forEach { rawLine ->
            val tags = TIME_TAG.findAll(rawLine).toList()
            if (tags.isEmpty()) return@forEach

            val content = rawLine.substring(tags.last().range.last + 1).trim()
            if (content.isBlank()) return@forEach

            tags.forEach { tag ->
                val (minutes, seconds, fraction) = tag.destructured
                val ms = fraction.padEnd(3, '0').take(3).toIntOrNull() ?: 0
                val begin = minutes.toLongOrNull()?.times(60_000L)
                    ?.plus(seconds.toLongOrNull()?.times(1_000L) ?: 0L)
                    ?.plus(ms)
                    ?: return@forEach

                result += RichLyricLine(begin = begin, text = content)
            }
        }

        result.sortBy { it.begin }
        for (i in 0 until result.size) {
            val current = result[i]
            val next = result.getOrNull(i + 1)
            current.duration = (next?.begin ?: (current.begin + 5_000L)) - current.begin
            current.end = current.begin + current.duration
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseList(list: List<*>): List<RichLyricLine>? {
        val result = mutableListOf<RichLyricLine>()

        list.forEach { item ->
            if (item !is Map<*, *>) return@forEach
            val beginMs = numberValue(item["begin"] ?: item["start"] ?: item["time"])
            val text = item["text"] ?: item["content"] ?: item["lrc"]
            if (beginMs == null || text == null) return@forEach

            val duration = numberValue(item["duration"]) ?: numberValue(item["end"])?.let { it - beginMs }
                ?: 0L
            val words = parseWords(item["words"] ?: item["word"])

            result += RichLyricLine(
                begin = beginMs,
                duration = duration,
                text = text.toString(),
                words = words
            )
        }

        result.sortBy { it.begin }
        for (i in 0 until result.size) {
            val current = result[i]
            if (current.duration <= 0) {
                current.duration = (result.getOrNull(i + 1)?.begin ?: (current.begin + 5_000L)) - current.begin
            }
            current.end = current.begin + current.duration
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseWords(words: Any?): List<io.github.proify.lyricon.lyric.model.LyricWord>? {
        if (words !is List<*>) return null
        val result = mutableListOf<io.github.proify.lyricon.lyric.model.LyricWord>()
        words.forEach { word ->
            if (word is Map<*, *>) {
                val begin = numberValue(word["begin"])
                val duration = numberValue(word["duration"])
                val text = word["text"]?.toString() ?: return@forEach
                result += io.github.proify.lyricon.lyric.model.LyricWord(
                    begin = begin ?: 0L,
                    duration = duration ?: 0L,
                    text = text
                )
            } else if (word is String) {
                result += io.github.proify.lyricon.lyric.model.LyricWord(
                    begin = 0L,
                    duration = 0L,
                    text = word
                )
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun numberValue(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toDoubleOrNull()?.roundToLong()
        else -> null
    }
}
