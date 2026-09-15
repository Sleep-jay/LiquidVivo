package com.FucVivo.xposed.hooks

import androidx.compose.runtime.Composable
import com.FucVivo.R
import com.FucVivo.ui.screen.apphook.FrameInterControlCard
import com.FucVivo.xposed.AppHooker
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.hooks.settings.AboutPageCustomizer
import com.FucVivo.xposed.hooks.settings.FrameInterController
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/**
 * vivo 设置（com.android.settings）：
 * 关于手机页自定义：背景图替换（选图+裁切）、设备信息文字点击修改
 *
 * OriginOS 6（Settings 16.0）关于手机页重构：
 * ThemeDeviceFragmentRom13 → com.vivo.settings.aboutphone.deviceinfo.VivoAboutPhoneFragment
 * （Fragment + Controller 架构，行 = VivoDeviceInfoLayout），挂点由 bindListener 改为 initView。
 */
object SettingsHooker : AppHooker {

    override val packageName: String = "com.android.settings"
    override val appNameRes: Int = R.string.settings_app_name
    override val featureSummaryRes: Int = R.string.settings_app_feature
    override val adaptedVersion: String = "16.0"

    override val hookFeatureTitleRes: Int = R.string.about_custom_title
    override val hookFeatures: List<com.FucVivo.xposed.HookFeature> = listOf(
        // 一个总开关控制全部关于手机自定义（背景图 + 所有文本修改）
        com.FucVivo.xposed.HookFeature("about", "关于手机自定义"),
        // 游戏插帧控制（Settings 进程=system uid 直写 Settings.System 键）
        com.FucVivo.xposed.HookFeature("frameinter", "游戏插帧", defaultOn = false),
    )

    override val managementExtra: (@Composable () -> Unit)? = { FrameInterControlCard() }

    private const val TAG = "FucVivoSettings"

    override fun onPackageLoaded(module: HookEntry, param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val cl = param.defaultClassLoader
        // 功能开关（app 侧写入，经 32 分片 remote prefs 读取）
        AboutPageCustomizer.hookPrefs = module.hookedPrefs
        runCatching { hookAboutPage(module, cl) }.onFailure { logE(module, "about", it) }
        // 游戏插帧控制器：system uid 直写 Settings.System 键（实时监听 remote prefs）
        runCatching { FrameInterController.install(module) }.onFailure { logE(module, "frameinter", it) }
    }

    // ---------- 关于手机页自定义 ----------

    private fun hookAboutPage(module: HookEntry, cl: ClassLoader) {
        val frag = cl.loadClass("com.vivo.settings.aboutphone.deviceinfo.VivoAboutPhoneFragment")

        // initView() 之后：控制器已绑定视图树，装我们自己的点击监听 + 应用文本/背景覆盖
        hookAfter(module, frag.getDeclaredMethod("initView")) { chain ->
            AboutPageCustomizer.attach(module, chain.thisObject ?: return@hookAfter)
        }

        // Fragment.onActivityResult：回收选图结果（startActivityForResult 路径）
        runCatching {
            val fragmentClass = cl.loadClass("androidx.fragment.app.Fragment")
            hook(module, fragmentClass.getDeclaredMethod(
                "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                android.content.Intent::class.java
            )) { chain ->
                val f = chain.thisObject
                if (f != null && frag.isInstance(f)) {
                    AboutPageCustomizer.onPickResult(
                        module, f,
                        chain.args.getOrNull(0) as? Int ?: -1,
                        chain.args.getOrNull(1) as? Int ?: -1,
                        chain.args.getOrNull(2) as? android.content.Intent,
                    )
                }
                chain.proceed()
            }
        }.onFailure { logE(module, "onActivityResult", it) }

        // onResume() 之后：重新应用覆盖（异步数据可能晚到）
        runCatching {
            hookAfter(module, frag.getMethod("onResume")) { chain ->
                if (chain.thisObject != null && frag.isInstance(chain.thisObject)) {
                    AboutPageCustomizer.applyAll(chain.thisObject)
                }
            }
        }.onFailure { logE(module, "onResume", it) }
    }

    // ---------- 工具 ----------

    private fun hook(
        module: HookEntry,
        method: java.lang.reflect.Method,
        replace: (XposedInterface.Chain) -> Any?,
    ) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? = replace(chain)
        })
    }

    private fun hookAfter(
        module: HookEntry,
        method: java.lang.reflect.Method,
        after: (XposedInterface.Chain) -> Unit,
    ) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = runCatching { chain.proceed() }.getOrNull()
                after(chain)
                return result
            }
        })
    }

    private fun logE(module: HookEntry, what: String, t: Throwable) {
        module.log(6, TAG, "$what hook failed: $t")
    }
}
