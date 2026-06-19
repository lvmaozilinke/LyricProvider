/*  *
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.SearchOptions
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 云端歌词翻译管理器。
 * 当 Apple Music 歌词没有翻译时，从 QQ 音乐/LRCLIB 搜索带翻译的歌词作为回退。
 */
object CloudTranslationManager {
    private const val TAG = "CloudTranslation"

    // 缓存：cacheKey -> 云端原始翻译列表 (cloudTimestamp, translationText)
    private val translationCache = ConcurrentHashMap<String, List<Pair<Long, String>>>()

    private val cloudLyrics = CloudLyrics(listOf(QQMusicProvider()))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSearchJob: Job? = null
    private var currentSearchingSongId: String? = null

    /**
     * 异步搜索云端翻译。
     * 返回原始云端翻译列表（cloudTimestamp, translationText），不做时间匹配。
     * 匹配由调用方在 buildLyricInfoJson 时完成。
     */
    fun fetchTranslationAsync(song: Song, onTranslationReady: (List<Pair<Long, String>>) -> Unit) {
        val cacheKey = "${song.name}:${song.artist}"
        if (translationCache.containsKey(cacheKey)) {
            onTranslationReady(translationCache[cacheKey]!!)
            return
        }

        // 如果同一首歌正在搜索中，跳过
        if (currentSearchingSongId == cacheKey && currentSearchJob?.isActive == true) {
            YLog.debug(tag = TAG, msg = "Already searching translation for: ${song.name}, skip")
            return
        }

        currentSearchJob?.cancel()
        currentSearchingSongId = cacheKey
        currentSearchJob = scope.launch {
            try {
                val result = searchCloudTranslation(song)
                if (result != null) {
                    translationCache[cacheKey] = result
                    onTranslationReady(result)
                    YLog.debug(tag = TAG, msg = "Cloud translation found for ${song.name}: ${result.size} lines")
                } else {
                    YLog.debug(tag = TAG, msg = "No cloud translation found for ${song.name}")
                }
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "Cloud translation search failed", e = e)
            } finally {
                currentSearchingSongId = null
            }
        }
    }

    /**
     * 同步搜索云端翻译（阻塞）。
     * 用于需要立即获取结果的场景。
     */
    fun fetchTranslationSync(song: Song): List<Pair<Long, String>> {
        val cacheKey = "${song.name}:${song.artist}"
        translationCache[cacheKey]?.let { return it }

        return runBlocking {
            try {
                val result = searchCloudTranslation(song)
                if (result != null) {
                    translationCache[cacheKey] = result
                    result
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "Cloud translation sync search failed", e = e)
                emptyList()
            }
        }
    }

    private suspend fun searchCloudTranslation(song: Song): List<Pair<Long, String>>? {
        val response = withContext(Dispatchers.IO) {
            cloudLyrics.search {
                trackName = song.name
                artistName = song.artist
                perProviderLimit = 3
                maxTotalResults = 1
            }
        }

        YLog.debug(tag = TAG, msg = "Cloud search result for '${song.name}' by '${song.artist}': ${response.size} providers returned")

        if (response.isEmpty()) return null

        val firstResult = response.firstOrNull() ?: return null
        val cloudResult = firstResult.lyrics ?: return null
        val cloudSong = cloudResult.rich

        YLog.debug(tag = TAG, msg = "  Provider: ${firstResult.provider?.id}, trackName=${cloudResult.trackName}, artist=${cloudResult.artistName}")
        YLog.debug(tag = TAG, msg = "  Total rich lines: ${cloudSong.size}")

        if (cloudSong.isEmpty()) return null

        // 调试：打印前 5 行的翻译情况
        val sampleSize = minOf(5, cloudSong.size)
        for (i in 0 until sampleSize) {
            val line = cloudSong[i]
            YLog.debug(tag = TAG, msg = "  cloudLine[$i] time=${line.begin}ms text=[${line.text}] translation=[${line.translation}] roma=[${line.roma}]")
        }

        // 统计有翻译的行数
        val withTranslation = cloudSong.count { !it.translation.isNullOrBlank() }
        YLog.debug(tag = TAG, msg = "  Lines with translation: $withTranslation / ${cloudSong.size}")

        // 返回原始云端翻译列表 (cloudTimestamp, translationText)
        return cloudSong
            .filter { !it.translation.isNullOrBlank() }
            .map { Pair(it.begin, it.translation!!) }
    }

    /**
     * 从云端歌词中提取翻译。
     * 云端歌词的每行可能有 translation 字段，将它按时间戳与原歌词匹配。
     */
    private fun extractTranslations(
        cloudLines: List<RichLyricLine>,
        originalSong: Song
    ): Map<Long, String> {
        val translationMap = mutableMapOf<Long, String>()

        for (cloudLine in cloudLines) {
            val translation = cloudLine.translation
            if (translation.isNullOrBlank()) continue

            // 按时间戳匹配原歌词
            val originalTime = findMatchingOriginalTime(cloudLine.begin, originalSong)
            if (originalTime >= 0) {
                translationMap[originalTime] = translation
            }
        }

        return translationMap
    }

    /**
     * 找到原歌词中最接近的时间戳。
     */
    private fun findMatchingOriginalTime(cloudTime: Long, song: Song): Long {
        val lyrics = song.lyrics ?: return -1
        var bestTime = -1L
        var bestDiff = Long.MAX_VALUE

        for (line in lyrics) {
            val diff = kotlin.math.abs(line.begin - cloudTime)
            if (diff < bestDiff && diff < 500) { // 500ms 容差
                bestDiff = diff
                bestTime = line.begin
            }
        }

        return bestTime
    }

    /**
     * 清除缓存。
     */
    fun clearCache() {
        translationCache.clear()
    }

    /**
     * 取消当前搜索任务。
     */
    fun cancelSearch() {
        currentSearchJob?.cancel()
        currentSearchJob = null
    }
}
