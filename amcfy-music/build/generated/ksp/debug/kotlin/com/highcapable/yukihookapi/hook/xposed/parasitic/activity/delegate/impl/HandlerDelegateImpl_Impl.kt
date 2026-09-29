@file:Suppress("ClassName", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.impl

import android.os.Handler
import com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.HandlerDelegate_io_github_proify_lyricon_amcfyprovider

/**
 * HandlerDelegateImpl_Impl Class
 *
 * Compiled from YukiHookXposedProcessor
 *
 * Generate Date: 2026年8月13日 12:33:49
 *
 * Powered by YukiHookAPI (C) HighCapable 2019
 *
 * Project URL: [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
 */
object HandlerDelegateImpl_Impl {

    val wrapperClassName get() = "com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.HandlerDelegate_io_github_proify_lyricon_amcfyprovider"

    fun createWrapper(baseInstance: Handler.Callback? = null): Handler.Callback = HandlerDelegate_io_github_proify_lyricon_amcfyprovider(baseInstance)
}