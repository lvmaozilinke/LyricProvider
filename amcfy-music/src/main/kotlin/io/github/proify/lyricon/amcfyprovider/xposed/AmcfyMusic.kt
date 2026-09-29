/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import android.app.Application
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XposedHelpers
import io.github.proify.lyricon.amcfyprovider.xposed.model.SongInfo
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

/**
 * 箭头音乐 (Amcfy Music) Lyricon Provider
 *
 * 播放链路：Flutter (audio_service) -> AudioService -> MediaSession
 * Hook 点：
 * 1. AudioService.setMetadata            -> 歌曲元数据
 * 2. AudioService.setPlaybackInfo        -> 播放位置 / 状态
 * 3. MethodChannel 调用（handler/widget） -> 歌词数据
 */
object AmcfyMusic : YukiBaseHooker() {
    private var application: Application? = null
    private var provider: LyriconProvider? = null

    override fun onHook() {
        YLog.info(tag = "AMCFYProvider", msg = "onHook called, package=$packageName, process=$processName")
        onAppLifecycle {
            onCreate {
                YLog.info(tag = "AMCFYProvider", msg = "App onCreate, package=$packageName, process=$processName")
                onAppCreate()
            }
        }
    }

    private fun onAppCreate() {
        application = appContext
        if (application == null) {
            YLog.error("AmcfyMusic: appContext is null, skip")
            return
        }

        initProvider()
        startHooks()
    }

    private fun initProvider() {
        val context = application ?: return

        val helper = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromSvg(Constants.LOGO_SVG)
        )

        PlaybackBridge.init(helper.player)
        PlaybackBridge.onLyricsReady = { song ->
            MediaSessionLyricInfoHooker.republishMetadataWithLyricInfo(song)
        }
        helper.register()
        provider = helper
        YLog.info(tag = "AMCFYProvider", msg = "provider registered, package=${context.packageName}")
    }

    private fun startHooks() {
        hookAudioService()
        hookMethodChannel()
        MediaSessionLyricInfoHooker.install()
        YLog.info(tag = "AMCFYProvider", msg = "All hooks installed")
    }

    // ---------------------------------------------------------------- AudioService

    private fun hookAudioService() {
        val classLoader = appClassLoader ?: return
        val audioService = "com.ryanheise.audioservice.AudioService".toClass(classLoader)

        // Captures every method call the Flutter side sends to audio_service,
        // including the full arguments payload (the app may send full lyrics here).
        audioService.method {
            name = "onMethodCall"
            paramCount = 1
        }.hook {
            after {
                val call = args.getOrNull(0) ?: return@after
                val method = runCatching {
                    XposedHelpers.getObjectField(call, "method") as? String
                }.getOrNull() ?: return@after
                val arguments = runCatching {
                    XposedHelpers.getObjectField(call, "arguments")
                }.getOrNull()
                YLog.debug(
                    tag = "AMCFYProvider",
                    msg = "AudioService.onMethodCall: $method -> ${arguments?.toString()?.take(2000)}"
                )
                if (method.contains("lyric", ignoreCase = true)) {
                    PlaybackBridge.onLyricPayload(arguments)
                } else if (method == "setState" || method == "setMediaItem") {
                    PlaybackBridge.onHandlerPayload(method, arguments)
                }
            }
        }

        // 歌曲元数据变化（createMediaMetadata 接收纯字符串参数，最可靠）
        audioService.method {
            name = "createMediaMetadata"
        }.hook {
            after {
                // Observed mapping: args[0] = media id (trk_...), args[1] = currently displayed
                // lyric line (title at song start, then lyric lines as the song plays),
                // args[2] = album, args[5] = numeric track id.
                val mediaId = args.getOrNull(0) as? String ?: return@after
                val line = args.getOrNull(1) as? String ?: return@after
                val album = args.getOrNull(2) as? String
                val trackId = args.getOrNull(5) as? String
                YLog.debug(
                    tag = "AMCFYProvider",
                    msg = "createMediaMetadata -> mediaId=$mediaId, line=$line, album=$album, trackId=$trackId"
                )
                PlaybackBridge.onLyricLineCaptured(mediaId, line)
            }
        }

        // 歌曲元数据变化（setMetadata 兼容 MediaMetadataCompat 反射）
        audioService.method {
            name = "setMetadata"
        }.hook {
            after {
                val metadata = args[0] ?: return@after
                YLog.debug(
                    tag = "AMCFYProvider",
                    msg = "setMetadata class=${metadata.javaClass.name} -> ${MetadataExtractor.snapshot(metadata)}"
                )
                val info = MetadataExtractor.toSongInfo(metadata)
                if (info != null) {
                    PlaybackBridge.onSongChanged(info)
                }
            }
        }

        // 播放位置 / 速度 / 状态
        audioService.method {
            name = "setPlaybackInfo"
        }.hook {
            after {
                YLog.debug(
                    tag = "AMCFYProvider",
                    msg = "setPlaybackInfo args=${args.joinToString { "${it?.javaClass?.simpleName}:$it" }}"
                )
                val position = args.getOrNull(1) as? Int ?: return@after
                val state = args.getOrNull(0) as? Int
                PlaybackBridge.onPlaybackInfo(state, position.toLong())
            }
        }

        // 播放状态整体变化
        audioService.method {
            name = "setState"
        }.hook {
            after {
                val playing = args.getOrNull(5) as? Boolean
                if (playing != null) {
                    PlaybackBridge.onPlayingChanged(playing)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Flutter 方法通道

    private fun hookMethodChannel() {
        val classLoader = appClassLoader ?: return
        val methodChannel = "io.flutter.plugin.common.MethodChannel".toClass(classLoader)

        methodChannel.method {
            name = "invokeMethod"
            paramCount = 2
        }.hook {
            after {
                val channelName = ChannelHelper.channelNameOf(instanceOrNull)
                val method = args[0] as? String ?: return@after
                val arguments = args[1]
                val payload = arguments?.toString().orEmpty()
                YLog.debug(
                    tag = "AMCFYProvider",
                    msg = "MethodChannel[$channelName].$method -> ${payload.take(500)}"
                )

                when {
                    channelName == Constants.AUDIO_SERVICE_HANDLER_CHANNEL -> {
                        if (method == "setState" || method == "setMediaItem") {
                            PlaybackBridge.onHandlerPayload(method, arguments)
                        }
                    }

                    channelName == Constants.AMCFY_WIDGET_UPDATE_CHANNEL -> {
                        PlaybackBridge.onWidgetPayload(arguments)
                    }

                    channelName?.contains("lyric", ignoreCase = true) == true ||
                        method.contains("lyric", ignoreCase = true) -> {
                        PlaybackBridge.onLyricPayload(arguments)
                    }
                }
            }
        }
    }
}
