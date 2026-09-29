/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

import java.lang.reflect.Field

object ChannelHelper {

    /** Cache of candidate "name" fields across the MethodChannel class hierarchy. */
    private val nameFields: List<Field> by lazy {
        runCatching {
            val clazz = Class.forName("io.flutter.plugin.common.MethodChannel")
            val fields = mutableListOf<Field>()
            var current: Class<*>? = clazz
            while (current != null) {
                runCatching { current.getDeclaredField("name") }.getOrNull()?.let { fields += it }
                current = current.superclass
            }
            fields.onEach { it.isAccessible = true }
        }.getOrDefault(emptyList())
    }

    fun channelNameOf(instance: Any?): String? {
        if (instance == null) return null
        for (field in nameFields) {
            runCatching { field.get(instance) as? String }.getOrNull()?.let {
                if (!it.isNullOrBlank()) return it
            }
        }
        return null
    }
}
