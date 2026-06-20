/*  *
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.amprovider.xposed

import android.content.Context
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 云端歌词翻译管理器。
 * 当 Apple Music 歌词没有翻译时，从 QQ 音乐搜索带翻译的歌词作为回退。
 * 支持磁盘缓存：翻译结果存储到本地文件，后续播放直接读取。
 */
object CloudTranslationManager {
    private const val TAG = "CloudTranslation"
    private const val CACHE_DIR = "translation_cache"
    private const val CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 天过期

    // 内存缓存：cacheKey -> 云端原始翻译列表 (cloudTimestamp, translationText)
    private val translationCache = ConcurrentHashMap<String, List<Pair<Long, String>>>()

    private val cloudLyrics = CloudLyrics(listOf(QQMusicProvider()))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentSearchJob: Job? = null
    private var currentSearchingSongId: String? = null

    private var cacheDir: File? = null

    /**
     * 初始化，需要传入 Context。
     */
    fun initialize(context: Context) {
        cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
    }

    /**
     * 异步搜索云端翻译。
     * 返回原始云端翻译列表（cloudTimestamp, translationText），不做时间匹配。
     * 匹配由调用方在 buildLyricInfoJson 时完成。
     */
    fun fetchTranslationAsync(song: Song, onTranslationReady: (List<Pair<Long, String>>) -> Unit) {
        val cacheKey = "${song.name}:${song.artist}"

        // 1. 先检查内存缓存
        if (translationCache.containsKey(cacheKey)) {
            onTranslationReady(translationCache[cacheKey]!!)
            return
        }

        // 2. 检查磁盘缓存（异步读取）
        scope.launch {
            val diskResult = loadFromDisk(cacheKey)
            if (diskResult != null) {
                translationCache[cacheKey] = diskResult
                withContext(Dispatchers.Main) {
                    onTranslationReady(diskResult)
                }
                YLog.debug(tag = TAG, msg = "Loaded translation from disk cache for: ${song.name}, ${diskResult.size} lines")
                return@launch
            }

            // 3. 磁盘缓存未命中，搜索云端
            // 如果同一首歌正在搜索中，跳过
            if (currentSearchingSongId == cacheKey && currentSearchJob?.isActive == true) {
                YLog.debug(tag = TAG, msg = "Already searching translation for: ${song.name}, skip")
                return@launch
            }

            currentSearchJob?.cancel()
            currentSearchingSongId = cacheKey
            currentSearchJob = scope.launch {
                try {
                    val result = searchCloudTranslation(song)
                    if (result != null) {
                        translationCache[cacheKey] = result
                        saveToDisk(cacheKey, result)
                        withContext(Dispatchers.Main) {
                            onTranslationReady(result)
                        }
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
                    saveToDisk(cacheKey, result)
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

    // ==================== 磁盘缓存 ====================

    /**
     * 保存翻译到磁盘。
     * 文件名：cacheKey 的 MD5 hash
     * 格式：{ "key": "songName:artist", "time": timestamp, "translations": [[time, text], ...] }
     */
    private fun saveToDisk(cacheKey: String, translations: List<Pair<Long, String>>) {
        try {
            val dir = cacheDir ?: return
            val file = File(dir, "${cacheKey.hashCode()}.json")

            val jsonArray = JSONArray()
            for ((time, text) in translations) {
                val item = JSONArray()
                item.put(time)
                item.put(text)
                jsonArray.put(item)
            }

            val json = JSONObject()
            json.put("key", cacheKey)
            json.put("time", System.currentTimeMillis())
            json.put("translations", jsonArray)

            file.writeText(json.toString())
            YLog.debug(tag = TAG, msg = "Saved translation to disk: $cacheKey (${translations.size} lines)")
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to save translation to disk", e = e)
        }
    }

    /**
     * 从磁盘加载翻译。
     * 检查文件是否存在且未过期。
     */
    private fun loadFromDisk(cacheKey: String): List<Pair<Long, String>>? {
        try {
            val dir = cacheDir ?: return null
            val file = File(dir, "${cacheKey.hashCode()}.json")
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            val timestamp = json.optLong("time", 0)

            // 检查是否过期
            if (System.currentTimeMillis() - timestamp > CACHE_MAX_AGE_MS) {
                file.delete()
                YLog.debug(tag = TAG, msg = "Translation cache expired, deleted: $cacheKey")
                return null
            }

            val translations = mutableListOf<Pair<Long, String>>()
            val jsonArray = json.optJSONArray("translations") ?: return null
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONArray(i)
                translations.add(Pair(item.getLong(0), item.getString(1)))
            }

            return if (translations.isNotEmpty()) translations else null
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to load translation from disk", e = e)
            return null
        }
    }

    /**
     * 清除内存缓存。
     */
    fun clearCache() {
        translationCache.clear()
    }

    /**
     * 清除所有缓存（内存 + 磁盘）。
     */
    fun clearAllCache() {
        translationCache.clear()
        try {
            cacheDir?.deleteRecursively()
            cacheDir?.mkdirs()
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to clear disk cache", e = e)
        }
    }

    /**
     * 取消当前搜索任务。
     */
    fun cancelSearch() {
        currentSearchJob?.cancel()
        currentSearchJob = null
        currentSearchingSongId = null
    }
}
