package com.LiquidVivo.xposed

import com.LiquidVivo.xposed.hooks.SystemUIHooker

/**
 * Hooker 注册表（对应 Rain 中的 ka）：
 * HookEntry 按包名分发，UI 应用列表 / 使用说明也来自这里。
 * 一个 hooker 可覆盖多个包（[AppHooker.extraPackageNames]），find 按全部包名匹配。
 *
 * 仅保留系统界面修改（SystemUI 模糊）模块；设置 / 游戏魔盒 / 系统升级已移除。
 */
object HookerRegistry {

    val hookers: List<AppHooker> = listOf(
        SystemUIHooker,
    )

    fun find(packageName: String): AppHooker? =
        hookers.firstOrNull { packageName in it.allPackageNames }
}
