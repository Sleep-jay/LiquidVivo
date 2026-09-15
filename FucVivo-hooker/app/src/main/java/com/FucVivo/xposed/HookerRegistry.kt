package com.FucVivo.xposed

import com.FucVivo.xposed.hooks.GameCubeHooker
import com.FucVivo.xposed.hooks.SettingsHooker
import com.FucVivo.xposed.hooks.SystemUIHooker
import com.FucVivo.xposed.hooks.UpdaterHooker

/**
 * Hooker 注册表（对应 Rain 中的 ka）：
 * HookEntry 按包名分发，UI 应用列表 / 使用说明也来自这里。
 * 一个 hooker 可覆盖多个包（[AppHooker.extraPackageNames]），find 按全部包名匹配。
 */
object HookerRegistry {

    val hookers: List<AppHooker> = listOf(
        SettingsHooker,
        SystemUIHooker,
        GameCubeHooker,
        UpdaterHooker,
    )

    fun find(packageName: String): AppHooker? =
        hookers.firstOrNull { packageName in it.allPackageNames }
}
