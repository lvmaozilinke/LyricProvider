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
 *
 * 核心流程：
 * 1. 播放歌曲时，检查磁盘缓存是否有效（文件存在 + translations 字段非空）
 * 2. 无效缓存直接删除，启动后台线程从 QQ 音乐获取
 * 3. 获取完成后，检查当前播放歌曲是否与下载的一致
 *    - 一致 → 替换歌词并广播
 *    - 不一致 → 仅存储到磁盘，不做其他操作
 * 4. 快速切歌时，每首歌独立创建后台任务，不互相干扰
 */
object CloudTranslationManager {
    private const val TAG = "CloudTranslation"
    private const val CACHE_DIR = "translation_cache"
    private const val CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L  // 7 天过期

    // 内存缓存：cacheKey -> 云端原始翻译列表 (cloudTimestamp, translationText)
    private val translationCache = ConcurrentHashMap<String, List<Pair<Long, String>>>()

    // 正在执行的下载任务：cacheKey -> Job
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // 当前正在播放的歌曲 cacheKey（用于下载完成后比对）
    @Volatile
    var currentPlayingKey: String = ""
        private set

    private val cloudLyrics = CloudLyrics(listOf(QQMusicProvider()))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var cacheDir: File? = null

    /**
     * 初始化，需要传入 Context。
     */
    fun initialize(context: Context) {
        cacheDir = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
    }

    /**
     * 异步获取翻译（非阻塞）。
     *
     * @param song 当前播放的歌曲
     * @param onTranslationReady 翻译就绪回调（仅当与当前播放一致时触发）
     */
    fun fetchTranslation(song: Song, onTranslationReady: (List<Pair<Long, String>>) -> Unit) {
        val cacheKey = "${song.name}:${song.artist}"
        currentPlayingKey = cacheKey

        // 1. 检查内存缓存
        translationCache[cacheKey]?.let { translations ->
            if (translations.isNotEmpty()) {
                onTranslationReady(translations)
                return
            }
        }

        // 2. 检查磁盘缓存（同步读取，很快）
        val diskResult = loadFromDisk(cacheKey)
        if (diskResult != null && diskResult.isNotEmpty()) {
            translationCache[cacheKey] = diskResult
            onTranslationReady(diskResult)
            YLog.debug(tag = TAG, msg = "Loaded valid translation from disk: ${song.name}, ${diskResult.size} lines")
            return
        }

        // 3. 无效缓存，删除
        deleteFromDisk(cacheKey)
        translationCache.remove(cacheKey)

        // 4. 已有任务在跑同一首歌，跳过
        if (activeJobs.containsKey(cacheKey) && activeJobs[cacheKey]?.isActive == true) {
            YLog.debug(tag = TAG, msg = "Already downloading translation for: ${song.name}, skip")
            return
        }

        // 5. 启动后台下载任务
        YLog.debug(tag = TAG, msg = "Starting cloud translation download for: ${song.name}")
        val job = scope.launch {
            try {
                val result = searchCloudTranslation(song)
                if (result == null || result.isEmpty()) {
                    YLog.debug(tag = TAG, msg = "No translation found for: ${song.name}")
                    return@launch
                }

                // 6. 下载完成，检查是否与当前播放一致
                val isCurrentSong = (currentPlayingKey == cacheKey)

                // 无论如何都存到磁盘
                translationCache[cacheKey] = result
                saveToDisk(cacheKey, result)
                YLog.debug(tag = TAG, msg = "Translation downloaded for: ${song.name}, ${result.size} lines, isCurrent=$isCurrentSong")

                // 7. 一致才广播
                if (isCurrentSong) {
                    withContext(Dispatchers.Main) {
                        onTranslationReady(result)
                    }
                } else {
                    YLog.debug(tag = TAG, msg = "Song changed, stored locally without broadcast: ${song.name}")
                }
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "Cloud translation download failed for: ${song.name}", e = e)
            } finally {
                activeJobs.remove(cacheKey)
            }
        }
        activeJobs[cacheKey] = job
    }

    /**
     * 同步搜索云端翻译（阻塞）。
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
     * 文件名：cacheKey 的 hashCode
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
     * 检查文件是否存在、未过期、且 translations 字段非空。
     * 如果文件存在但 translations 为空，删除文件并返回 null。
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

            // 检查 translations 字段是否存在且非空
            val jsonArray = json.optJSONArray("translations")
            if (jsonArray == null || jsonArray.length() == 0) {
                // 文件存在但翻译为空，删除坏文件
                file.delete()
                YLog.debug(tag = TAG, msg = "Translation cache has empty translations, deleted: $cacheKey")
                return null
            }

            val translations = mutableListOf<Pair<Long, String>>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONArray(i)
                translations.add(Pair(item.getLong(0), item.getString(1)))
            }

            return if (translations.isNotEmpty()) translations else null
        } catch (e: Exception) {
            // JSON 解析失败，删除坏文件
            try {
                File(cacheDir, "${cacheKey.hashCode()}.json").delete()
            } catch (_: Exception) {}
            YLog.error(tag = TAG, msg = "Failed to load translation from disk, deleted bad file", e = e)
            return null
        }
    }

    /**
     * 删除磁盘缓存。
     */
    private fun deleteFromDisk(cacheKey: String) {
        try {
            val file = File(cacheDir, "${cacheKey.hashCode()}.json")
            if (file.exists()) {
                file.delete()
                YLog.debug(tag = TAG, msg = "Deleted translation cache: $cacheKey")
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to delete translation cache", e = e)
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
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        try {
            cacheDir?.deleteRecursively()
            cacheDir?.mkdirs()
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to clear disk cache", e = e)
        }
    }

    /**
     * 取消所有进行中的搜索任务。
     * 切歌时调用。
     */
    fun cancelAllSearches() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }
}
