@file:Suppress("ClassName", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.proify.lyricon.amcfyprovider.xposed

import com.highcapable.yukihookapi.hook.xposed.bridge.caller.YukiXposedModuleCaller
import com.highcapable.yukihookapi.hook.xposed.bridge.resources.caller.YukiXposedResourcesCaller
import com.highcapable.yukihookapi.hook.xposed.bridge.type.HookEntryType
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed Init Impl Class
 *
 * Compiled from YukiHookXposedProcessor
 *
 * Generate Date: 2026年8月13日 12:33:50
 *
 * Powered by YukiHookAPI (C) HighCapable 2019
 *
 * Project URL: [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)
 */
object HookEntry_Impl {

    private const val MODULE_PACKAGE_NAME = "io.github.proify.lyricon.amcfyprovider"
    private var isZygoteCalled = false
    private val hookEntry = HookEntry()

    private fun callOnXposedModuleLoaded(
        isZygoteLoaded: Boolean = false,
        lpparam: XC_LoadPackage.LoadPackageParam? = null,
        resparam: XC_InitPackageResources.InitPackageResourcesParam? = null
    ) {
        if (isZygoteCalled.not()) runCatching {
            hookEntry.onXposedEvent()
            hookEntry.onInit()
            if (YukiXposedModuleCaller.isXposedCallbackSetUp) {
                YukiXposedModuleCaller.callLogError("You cannot load a hooker in \"onInit\" or \"onXposedEvent\" method! Aborted")
                return
            }
            hookEntry.onHook()
            YukiXposedModuleCaller.callOnFinishLoadModule()
        }.onFailure { YukiXposedModuleCaller.callLogError("YukiHookAPI try to load hook entry class failed", it) }
        YukiXposedModuleCaller.callOnPackageLoaded(
            type = when {
                isZygoteLoaded -> HookEntryType.ZYGOTE
                lpparam != null -> HookEntryType.PACKAGE
                resparam != null -> HookEntryType.RESOURCES
                else -> HookEntryType.ZYGOTE
            },
            packageName = lpparam?.packageName ?: resparam?.packageName,
            processName = lpparam?.processName,
            appClassLoader = lpparam?.classLoader ?: runCatching { XposedBridge.BOOTCLASSLOADER }.getOrNull(),
            appInfo = lpparam?.appInfo,
            appResources = YukiXposedResourcesCaller.createYukiResourcesFromXResources(resparam?.res)
        )
    }

    fun callInitZygote(sparam: IXposedHookZygoteInit.StartupParam?) {
        if (sparam == null) return
        runCatching {
            YukiXposedModuleCaller.callOnStartLoadModule(MODULE_PACKAGE_NAME, sparam.modulePath)
            callOnXposedModuleLoaded(isZygoteLoaded = true)
            isZygoteCalled = true
        }.onFailure { YukiXposedModuleCaller.callLogError("An exception occurred when YukiHookAPI loading Xposed Module", it) }
    }

    fun callHandleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if (lpparam != null && isZygoteCalled) callOnXposedModuleLoaded(lpparam = lpparam)
    }

    fun callHandleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam?) {
        if (resparam != null && isZygoteCalled) callOnXposedModuleLoaded(resparam = resparam)
    }
}