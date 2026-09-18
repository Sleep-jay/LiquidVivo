package com.LiquidVivo.xposed.hooks

import androidx.compose.runtime.Composable
import com.LiquidVivo.R
import com.LiquidVivo.ui.screen.apphook.BlurCustomControlCard
import com.LiquidVivo.xposed.AppHooker
import com.LiquidVivo.xposed.HookEntry
import com.LiquidVivo.xposed.hooks.systemui.BlurStyleController
import com.LiquidVivo.xposed.hooks.systemui.IslandCapsuleBlurController
import io.github.libxposed.api.XposedModuleInterface

/**
 * 系统界面（com.android.systemui）+ 系统界面插件（com.vivo.systemuiplugin）+
 * 侧边栏窗口（com.vivo.upslide）与内容卡片（com.vivo.card）：
 * 模糊半径 / 透明度自定义。两个进程同一管理页（一张应用卡，作用域一起申请）。
 *
 * 挂载依据：OriginOS 6 逆向（本机 %TEMP%\liquidvivo_rom\decomp，framework/systemui/plugin）：
 *  - 模糊统一收口在 android.view.View 的 vivo 注入扩展（基座侵入），
 *    setMaterial 系方法全部路由到 setBlur / setWindowBlur 底层方法；
 *  - token 材质经 BlurMaterial.resolveBlurGeometryByStyle 解析；
 *  - SystemUI 另有 com.vivo.blur.VivoBackgroundBlurDrawable 平行 Drawable 栈
 *    （O6 起改为 BlurDrawableController.getDrawable 字段直写）。
 */
object SystemUIHooker : AppHooker {

    override val packageName: String = BlurStyleController.PKG_SYSTEMUI

    override val extraPackageNames: List<String> =
        listOf(
            BlurStyleController.PKG_SYSTEMUI_PLUGIN,
            BlurStyleController.PKG_UPSLIDE,
            BlurStyleController.PKG_CARD,
        )

    override val appNameRes: Int = R.string.systemui_app_name
    override val featureSummaryRes: Int = R.string.systemui_app_feature

    /** SystemUI 16.0.7.251 / Plugin 16.0.7.252，两包版本号不一致，展示用区间 */
    override val adaptedVersion: String = "16.0.7.x"

    /** SystemUI 无启动器入口，状态不报「无法启动」，管理页隐藏「打开应用」 */
    override val requiresLaunchability: Boolean = false

    override val hookFeatureTitleRes: Int = R.string.about_custom_title

    override val hookFeatures: List<com.LiquidVivo.xposed.HookFeature> = listOf(
        com.LiquidVivo.xposed.HookFeature("blur", "模糊与透明度自定义"),
        com.LiquidVivo.xposed.HookFeature("liquidglass", "液态玻璃"),
        com.LiquidVivo.xposed.HookFeature("islandblur", "原子岛胶囊与展开态统一"),
    )

    override val managementExtra: (@Composable () -> Unit)? = { BlurCustomControlCard() }

    override fun onPackageLoaded(module: HookEntry, param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        runCatching {
            BlurStyleController.install(module, param.defaultClassLoader, param.packageName)
        }.onFailure { module.logE("LiquidVivoBlurStyle", "install failed: $it") }
        // 原子岛渲染在 Plugin 进程：胶囊态提级为与展开态同一材质
        if (param.packageName == BlurStyleController.PKG_SYSTEMUI_PLUGIN) {
            runCatching {
                IslandCapsuleBlurController.install(module, param.defaultClassLoader)
            }.onFailure { module.logE("LiquidVivoIslandBlur", "install failed: $it") }
        }
    }
}
