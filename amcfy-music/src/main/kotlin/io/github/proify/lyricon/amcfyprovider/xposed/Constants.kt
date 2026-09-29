/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amcfyprovider.xposed

object Constants {
    const val PROVIDER_PACKAGE_NAME: String = "io.github.proify.lyricon.amcfyprovider"
    const val AMCFY_MUSIC_PACKAGE_NAME: String = "com.sumsg.musichub"

    /** Flutter audio_service 播放器 -> 原生 通道 */
    const val AUDIO_SERVICE_HANDLER_CHANNEL: String =
        "com.ryanheise.audio_service.handler.methods"

    /** 箭头音乐自研桌面组件更新通道 */
    const val AMCFY_WIDGET_UPDATE_CHANNEL: String = "com.amcfy.player/android_widget"

    /** 蓝色箭头 Logo (SVG) */
    const val LOGO_SVG: String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"192\" height=\"192\" viewBox=\"0 0 192 192\">" +
            "<rect width=\"192\" height=\"192\" rx=\"42\" fill=\"#4C8DFF\"/>" +
            "<path d=\"M104 32 L112 32 L112 90 L144 58 L144 70 L116 96 L144 122 L144 134 L112 102 L112 160 L104 160 Z\" fill=\"#FFFFFF\"/>" +
            "</svg>"
}
