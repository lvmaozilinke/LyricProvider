@file:Suppress("ClassName")

package com.highcapable.yukihookapi.hook.xposed.application

import io.github.proify.lyricon.amcfyprovider.xposed.HookEntry

/**
 * ModuleApplication_Impl Class
 *
 * Compiled from YukiHookXposedProcessor
 *
 * Generate Date: 2026年8月13日 12:33:49
 *
 * Powered by YukiHookAPI (C) HighCapable 2019
 *
 * Project URL: [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
 */
object ModuleApplication_Impl {

    fun callHookEntryInit() = try {
        HookEntry().onInit()
    } catch (_: Throwable) {
    }
}