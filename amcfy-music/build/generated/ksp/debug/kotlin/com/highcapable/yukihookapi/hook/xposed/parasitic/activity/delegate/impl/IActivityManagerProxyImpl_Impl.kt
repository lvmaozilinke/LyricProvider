@file:Suppress("ClassName", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.impl

import android.os.Handler
import com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.caller.IActivityManagerProxyCaller
import com.highcapable.yukihookapi.hook.xposed.parasitic.activity.delegate.IActivityManagerProxy_io_github_proify_lyricon_amcfyprovider
import java.lang.reflect.Proxy

/**
 * IActivityManagerProxyImpl_Impl Class
 *
 * Compiled from YukiHookXposedProcessor
 *
 * Generate Date: 2026年8月13日 12:33:49
 *
 * Powered by YukiHookAPI (C) HighCapable 2019
 *
 * Project URL: [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
 */
object IActivityManagerProxyImpl_Impl {

    fun createWrapper(clazz: Class<*>?, instance: Any) = 
        Proxy.newProxyInstance(IActivityManagerProxyCaller.currentClassLoader, arrayOf(clazz), IActivityManagerProxy_io_github_proify_lyricon_amcfyprovider(instance))
}