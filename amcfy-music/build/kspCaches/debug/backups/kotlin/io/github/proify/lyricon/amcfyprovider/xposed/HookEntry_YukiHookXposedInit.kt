@file:Suppress("ClassName", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.proify.lyricon.amcfyprovider.xposed

import androidx.annotation.Keep
import com.highcapable.yukihookapi.hook.xposed.bridge.event.caller.YukiXposedEventCaller

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit

import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed Init Class
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
class HookEntry_YukiHookXposedInit : IXposedHookZygoteInit, IXposedHookLoadPackage {

    override fun initZygote(sparam: IXposedHookZygoteInit.StartupParam?) {
        HookEntry_Impl.callInitZygote(sparam)
        YukiXposedEventCaller.callInitZygote(sparam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        HookEntry_Impl.callHandleLoadPackage(lpparam)
        YukiXposedEventCaller.callHandleLoadPackage(lpparam)
    }
}