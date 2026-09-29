/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import io.github.proify.lyricon.amcfyprovider.xposed.model.SongInfo
import java.lang.reflect.Method

/**
 * 从 MediaMetadataCompat (support.v4) 反射读取标准字段。
 * 兼容音频服务内部同时存在 android.media.MediaMetadata 的情况。
 */
object MetadataExtractor {

    private const val KEY_TITLE = "android.media.metadata.TITLE"
    private const val KEY_ARTIST = "android.media.metadata.ARTIST"
    private const val KEY_ALBUM = "android.media.metadata.ALBUM"
    private const val KEY_DURATION = "android.media.metadata.DURATION"
    private const val KEY_MEDIA_ID = "android.media.metadata.MEDIA_ID"

    fun toSongInfo(metadata: Any?): SongInfo? {
        if (metadata == null) return null
        return runCatching {
            val getString = findGetter(metadata, "getString")
            val getLong = findGetter(metadata, "getLong")

            val title = invoke(getString, metadata, KEY_TITLE) as? String
            val artist = invoke(getString, metadata, KEY_ARTIST) as? String
            val album = invoke(getString, metadata, KEY_ALBUM) as? String
            val duration = (invoke(getLong, metadata, KEY_DURATION) as? Number)?.toLong() ?: 0L
            val id = (invoke(getString, metadata, KEY_MEDIA_ID) as? String)
                ?: title?.hashCode()?.toString()
                ?: return null

            SongInfo(
                id = id,
                title = title,
                artist = artist,
                album = album,
                duration = duration
            )
        }.getOrNull()
    }

    fun snapshot(metadata: Any?): String {
        if (metadata == null) return "null"
        val info = toSongInfo(metadata) ?: return "null"
        return "id=${info.id}, title=${info.title}, artist=${info.artist}, album=${info.album}, duration=${info.duration}"
    }

    /** 查找 getString(String)/getLong(String) 方法（兼容混淆后的 support 库类名/方法名） */
    private fun findGetter(target: Any, methodName: String): Method? {
        val clazz = target.javaClass
        clazz.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 1
        }?.let { return it }

        return clazz.methods.firstOrNull {
            it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java &&
                (methodName == "getString" && it.returnType == String::class.java ||
                    methodName == "getLong" &&
                    (it.returnType == Long::class.javaPrimitiveType || it.returnType == java.lang.Long::class.java))
        }
    }

    private fun invoke(method: Method?, target: Any, key: String): Any? {
        if (method == null) return null
        return runCatching { method.invoke(target, key) }.getOrNull()
    }
}
