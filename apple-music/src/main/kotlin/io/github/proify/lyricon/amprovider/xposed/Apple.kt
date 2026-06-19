/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.app.Application
import android.media.MediaMetadata
import android.media.session.MediaSession
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.extensions.android.ScreenStateMonitor
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Apple : YukiBaseHooker() {
    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val coroutineScope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    private var progressJob: Job? = null

    private var provider: LyriconProvider? = null

    // MediaSession 引用缓存（用于歌词就绪后重新发布元数据）
    @Volatile
    private var lastMediaSession: MediaSession? = null

    // 缓存最近一次 setMetadata 的原始 metadata（含封面图等完整数据）
    @Volatile
    private var lastMediaMetadata: MediaMetadata? = null

    private const val AM_TAG = "AMProvider"

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return
        PreferencesMonitor.initialize(application)
        PreferencesMonitor.listener = object : PreferencesMonitor.Listener {
            override fun onTranslationSelectedChanged(selected: Boolean) {
                provider?.player?.setDisplayTranslation(selected)
            }
        }

        DiskSongManager.initialize(application)
        initScreenStateMonitor()
        initProvider()

        startHooks()
    }

    private fun initProvider() {
        val helper =
            LyriconFactory.createProvider(
                context = application,
                providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
                playerPackageName = application.packageName,
                logo = ProviderLogo.fromBase64(Constants.ICON)
            )

        PlaybackManager.init(
            remotePlayer = helper.player,
            requester = LyricRequester(classLoader, application)
        )

        // 当歌词异步加载完成后，重新发布 MediaSession 元数据以注入 lyricInfo
        PlaybackManager.onLyricsReady = { song ->
            republishMetadataWithLyricInfo(song)
        }

        helper.player.setDisplayTranslation(PreferencesMonitor.isTranslationSelected())
        helper.register()
        this.provider = helper
    }

    private fun startHooks() {
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()
        hookMediaSessionForLyricInfo()

//        XposedHelpers.findAndHookMethod(
//            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
//            classLoader,
//            "loadLyrics",
//            classLoader.loadClass("com.apple.android.music.model.PlaybackItem"),
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    val arg = param?.args?.get(0) ?: return
//                    ObjectUtils.print(arg)
//                }
//            })
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        val method = findMediaMetadataChangeMethod() ?: return

        method.hook {
            after {
                val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                val metadata = MediaMetadataCache.putAndGet(mediaMetadata) ?: return@after

                // 委托给 Manager 处理
                PlaybackManager.onSongChanged(metadata.id)
            }
        }
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        val m =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .resolve()
                .firstMethod { name = "buildTimeRangeToLyricsMap" }
                .hook {
                    after {
                        YLog.debug("buildTimeRangeToLyricsMap:$args")
                        val arg: Any? = args[0]
                        if (arg == null) {
                            YLog.debug("args0 null")
                            return@after
                        }
                        val songNative = XposedHelpers.callMethod(arg, "get")
                        YLog.debug("songNative: $songNative")

                        // 委托给 Manager 处理
                        PlaybackManager.onLyricsBuilt(songNative)
                    }
                }
        YLog.debug("hookLyricBuildMethod Hooked: $m")
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after {
                    exoMediaPlayerInstance = instanceOrNull
                    getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                }
            }
        }

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    // --- 进度同步逻辑 ---

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = coroutineScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.player?.setPosition(pos)
                } catch (_: Exception) {
                }
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun findMediaMetadataChangeMethod() =
        "android.support.v4.media.MediaMetadataCompat".toClass()
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers)
                        && Modifier.isStatic(it.modifiers)
                        && it.parameterCount == 1
                        && it.returnType.simpleName.contains("MediaMetadata")
            }

    // --- ColorOS Lockscreen Lyrics Bridge 集成 ---

    /**
     * Hook MediaSession.setMetadata()，在元数据中注入 lyricInfo。
     * ColorOS-Live-Lyrics-Bridge 从 MediaSession 元数据读取 lyricInfo 来显示锁屏歌词。
     */
    private fun hookMediaSessionForLyricInfo() {
        MediaSession::class.java.getDeclaredMethod("setMetadata", MediaMetadata::class.java)
            .hook {
                before {
                    // 缓存 MediaSession 引用和最新 metadata
                    lastMediaSession = instanceOrNull as? MediaSession
                    val metadata = args[0] as? MediaMetadata ?: return@before
                    lastMediaMetadata = metadata
                    val song = PlaybackManager.getCurrentSong()?.takeIf {
                        !it.lyrics.isNullOrEmpty()
                    } ?: return@before

                    // 校验 metadata 标题是否匹配当前歌曲，防止切歌时旧歌词注入新 metadata
                    val metadataTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                    if (metadataTitle.isNotBlank() && song.name != null && metadataTitle != song.name) {
                        return@before
                    }

                    try {
                        val lyricInfo = buildLyricInfoJson(song)
                        if (lyricInfo.isBlank()) return@before

                        // 避免重复注入
                        if (metadata.getString("lyricInfo") == lyricInfo) return@before

                        val patched = MediaMetadata.Builder(metadata)
                            .putString("lyricInfo", lyricInfo)
                            .build()
                        args[0] = patched
                    } catch (e: Exception) {
                        YLog.error(tag = AM_TAG, msg = "Failed to inject lyricInfo", e = e)
                    }
                }
            }
        YLog.debug(tag = AM_TAG, msg = "hookMediaSessionForLyricInfo: Hooked MediaSession.setMetadata")
    }

    /**
     * 当歌词异步加载完成后，重新发布当前 MediaSession 元数据。
     * 这会触发 hookMediaSessionForLyricInfo 中的 before-hook，自动注入 lyricInfo。
     */
    private fun republishMetadataWithLyricInfo(song: Song) {
        val session = lastMediaSession ?: return
        val metadata = lastMediaMetadata ?: return
        try {
            // 校验：metadata 标题必须匹配当前歌曲
            val metadataTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            if (metadataTitle.isNotBlank() && song.name != null && metadataTitle != song.name) {
                YLog.debug(tag = AM_TAG, msg = "Skip republish: title mismatch '$metadataTitle' vs '${song.name}'")
                return
            }
            session.setMetadata(metadata)
            YLog.debug(tag = AM_TAG, msg = "Republished metadata with lyricInfo for: ${song.name}")
        } catch (e: Exception) {
            YLog.error(tag = AM_TAG, msg = "Failed to republish metadata", e = e)
        }
    }

    /**
     * 构建 ColorOS-Live-Lyrics-Bridge 协议要求的 lyricInfo JSON。
     *
     * 格式：
     * {
     *   "songName": "标题",
     *   "artist": "歌手",
     *   "songId": "adamId",
     *   "lyric": "[00:00.00]歌词行1\n[00:05.20]歌词行2",
     *   "rawLyric": "[00:00.000]逐[00:00.320]字[00:00.440]歌词"
     * }
     */
    private fun buildLyricInfoJson(song: Song): String {
        val lines = song.lyrics
        if (lines.isNullOrEmpty()) return ""

        val lyricSb = StringBuilder()
        val rawLyricSb = StringBuilder()
        val translationSb = StringBuilder()

        // 打印歌曲基本信息
        YLog.debug(tag = AM_TAG, msg = "===== buildLyricInfoJson: ${song.name} by ${song.artist} (${song.id}) =====")
        YLog.debug(tag = AM_TAG, msg = "  total lines: ${lines.size}")

        var lineIndex = 0
        for (line in lines) {
            val beginMs = line.begin
            val text = line.text ?: continue
            if (text.isBlank()) continue

            val words = line.words
            val wordCount = words?.size ?: 0
            val hasWords = !words.isNullOrEmpty()

            // 打印每行歌词详情
            YLog.debug(tag = AM_TAG, msg = "  line[$lineIndex] time=${beginMs}ms text=[$text] words=$wordCount")
            if (hasWords) {
                val firstWord = words!!.first()
                val lastWord = words.last()
                YLog.debug(tag = AM_TAG, msg = "    word[0] text=[${firstWord.text}] begin=${firstWord.begin} end=${firstWord.end}")
                if (wordCount > 1) {
                    YLog.debug(tag = AM_TAG, msg = "    word[${wordCount-1}] text=[${lastWord.text}] begin=${lastWord.begin} end=${lastWord.end}")
                }
            }

            // lyric: 逐行 LRC
            lyricSb.append("[${formatLrcTime(beginMs)}]$text\n")

            // rawLyric: 逐字 LRC
            if (hasWords) {
                appendRawLyricWords(rawLyricSb, words!!)
            } else {
                rawLyricSb.append("[${formatLrcTimeMs(beginMs)}]$text\n")
            }

            // translationLyric
            val translation = line.translation
            if (!translation.isNullOrBlank()) {
                translationSb.append("[${formatLrcTime(beginMs)}]$translation\n")
            }

            lineIndex++
        }

        if (lyricSb.isEmpty()) return ""

        val result = JSONObject().apply {
            put("songName", song.name ?: "")
            put("artist", song.artist ?: "")
            put("songId", song.id ?: "")
            put("lyric", lyricSb.toString().trimEnd())
            put("rawLyric", rawLyricSb.toString().trimEnd())
            if (translationSb.isNotEmpty()) {
                put("translationLyric", translationSb.toString().trimEnd())
            }
        }.toString()

        // 打印生成的完整 JSON
        YLog.debug(tag = AM_TAG, msg = "  lyricInfo JSON length=${result.length}")
        YLog.debug(tag = AM_TAG, msg = "  lyric length=${lyricSb.length}, rawLyric length=${rawLyricSb.length}")

        return result
    }

    /** 写入一组单词的 rawLyric */
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

    /** 毫秒精度格式化（用于 rawLyric 逐字时间轴） */
    private fun formatLrcTimeMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}