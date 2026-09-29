@file:Suppress("ClassName", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate

import android.os.Handler
import android.os.Message
import androidx.annotation.Keep
import com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.caller.HandlerDelegateCaller

/**
 * HandlerDelegate Class
 *
 * Compiled from YukiHookXposedProcessor
 *
 * Generate Date: 2026年8月13日 12:33:49
 *
 * Powered by YukiHookAPI (C) HighCapable 2019
 *
 * Project URL: [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
 */
@Keep
class HandlerDelegate_io_github_proify_lyricon_amcfyprovider(private val baseInstance: Handler.Callback?) : Handler.Callback {

    override fun handleMessage(msg: Message) = HandlerDelegateCaller.callHandleMessage(baseInstance, msg)
}