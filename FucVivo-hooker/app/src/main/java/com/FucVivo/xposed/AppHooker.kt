package com.FucVivo.xposed

import androidx.compose.runtime.Composable
import io.github.libxposed.api.XposedModuleInterface

/**
 * 单个目标应用的 Hook 实现（对应 Rain 中的 fa 接口）。
 * 每个目标应用一个 object，在 HookerRegistry 注册。
 */
/** 可独立开关的 hook 功能（key 与开关 prefs 的 enable_<key> 对应） */
data class HookFeature(
    val key: String,
    val title: String,
    /** 开关默认值；hook 侧读取时必须用同一默认值 */
    val defaultOn: Boolean = true,
)

interface AppHooker {

    /** 目标包名，必须与 scope.list / manifest queries 一致 */
    val packageName: String

    /** UI 展示用名称（字符串资源，支持多语言） */
    val appNameRes: Int

    /** 功能说明（字符串资源） */
    val featureSummaryRes: Int

    /** 已适配的目标版本；null 表示全版本支持 */
    val adaptedVersion: String?
        get() = null

    /** 管理页功能开关分组标题（字符串资源）；null 表示无独立开关 */
    val hookFeatureTitleRes: Int?
        get() = null

    /** 可独立开关的 hook 功能列表（key 与 hook 侧读取的开关 key 一致） */
    val hookFeatures: List<HookFeature>
        get() = emptyList()

    /** 管理页附加 UI（功能开关卡之后渲染），null 表示无 */
    val managementExtra: (@Composable () -> Unit)?
        get() = null

    /** 同一 hooker 的其它目标包（合并管理：一张应用卡，作用域/功能开关共享） */
    val extraPackageNames: List<String>
        get() = emptyList()

    /** 全部目标包 = 主包 + 附加包；注册表分发与作用域操作一律用它 */
    val allPackageNames: List<String>
        get() = listOf(packageName) + extraPackageNames

    /**
     * 目标是否要求有启动器入口。系统级目标（如 SystemUI）没有 launcher Activity，
     * 设 false 时状态文案不报「无法启动」，管理页隐藏「打开应用」按钮。
     */
    val requiresLaunchability: Boolean
        get() = true

    fun supportsVersion(versionName: String): Boolean = true

    fun onPackageLoaded(module: HookEntry, param: XposedModuleInterface.PackageLoadedParam) {}

    fun onPackageReady(module: HookEntry, param: XposedModuleInterface.PackageReadyParam) {}
}
