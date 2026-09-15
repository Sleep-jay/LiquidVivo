package com.FucVivo.xposed.hooks

import android.content.Context
import android.content.SharedPreferences
import com.FucVivo.R
import com.FucVivo.xposed.AppHooker
import com.FucVivo.xposed.HookEntry
import com.FucVivo.xposed.HookFeature
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * vivo 游戏魔盒（com.vivo.gamecube 14.0.16.01_de）：
 * 接管插帧写入 —— block GameCube 对 Settings.System.gamecube_frame_interpolation(_for_sr)
 * 的写入，防止它的控制器（前后台切换/策略不满足时回写 0:-1:0:0:0）抢方向盘。
 *
 * 目标类 com.vivo.common.utils.l0（IrisUtils；OriginOS 6 起去混淆为明文包名，
 * 旧版 13.0.05.01 混淆名 a7.t 保留为回退，按签名校验防版本漂移）：
 *  - a(Context,Z,I,I,I,I)V               写 gamecube_frame_interpolation_for_sr
 *  - b(Context,Z,I,I,int[],int[],int[])V 写 gamecube_frame_interpolation
 *                                         （单帧/多帧，多帧为 JSON；单帧格式与旧版一致）
 *  - d(Context)V / e(Context,I)V         关闭路径（内部调 b(false,...)）
 *  - c(Context,I,I)V                     写 gamecube_super_resolution（超分辨率，不属接管范围，不挂）
 * 接管开启时上述写方法全部 no-op；关闭时完全放行（GameCube 自用不受影响）。
 */
object GameCubeHooker : AppHooker {

    override val packageName: String = "com.vivo.gamecube"
    override val appNameRes: Int = R.string.gamecube_app_name
    override val featureSummaryRes: Int = R.string.gamecube_app_feature
    override val adaptedVersion: String = "14.0.16.01_de"

    override val hookFeatureTitleRes: Int = R.string.gamecube_feature_title
    override val hookFeatures: List<HookFeature> = listOf(
        HookFeature("fitakeover", "接管插帧写入", defaultOn = false),
    )

    private const val TAG = "FucVivoGameCube"
    private const val KEY_TAKEOVER = "enable_fitakeover"

    /** OriginOS 6（14.0.16.01_de）起 IrisUtils 去混淆；旧混淆名仅作回退 */
    private val IRIS_UTILS_CLASSES = arrayOf(
        "com.vivo.common.utils.l0", // OriginOS 6 / Android 16
        "a7.t",                     // OriginOS 5 / Android 15（13.0.05.01）
    )

    private var prefs: SharedPreferences? = null

    override fun onPackageLoaded(module: HookEntry, param: XposedModuleInterface.PackageLoadedParam) {
        if (!param.isFirstPackage) return
        prefs = module.hookedPrefs
        runCatching { hookIrisUtils(module, param.defaultClassLoader) }
            .onFailure { module.logE(TAG, "IrisUtils hook failed: $it") }
    }

    private fun hookIrisUtils(module: HookEntry, cl: ClassLoader) {
        val cls = IRIS_UTILS_CLASSES.firstNotNullOfOrNull { name ->
            runCatching { cl.loadClass(name) }.getOrNull()
        }
        if (cls == null || !looksLikeIrisUtils(cls)) {
            module.logE(TAG, "IrisUtils class not found or signature mismatch (GameCube updated?)")
            return
        }
        var hooked = 0
        for (m in cls.declaredMethods) {
            if (!Modifier.isStatic(m.modifiers) || !isIrisWriteMethod(m)) continue
            hook(module, m)
            hooked++
        }
        module.logD(TAG, "IrisUtils hooked: $hooked methods on ${cls.name}")
    }

    /** 签名校验：存在 static (Context,boolean,int,int,int,int) 方法（a：写 _for_sr） */
    private fun looksLikeIrisUtils(cls: Class<*>): Boolean =
        cls.declaredMethods.any { m ->
            Modifier.isStatic(m.modifiers) && m.parameterTypes.contentEquals(
                arrayOf(
                    Context::class.java, Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
            )
        }

    /**
     * 需接管的写方法（全部为 void 静态方法）：
     *  - a: (Context,Z,I,I,I,I)          → 写 _for_sr
     *  - b: (Context,Z,I,I,int[],int[],int[]) → 写主键（单帧/JSON 多帧）
     *  - d: (Context)V / e: (Context,I)  → 关闭路径（内部再调 b）
     */
    private fun isIrisWriteMethod(m: Method): Boolean {
        val pt = m.parameterTypes
        return when {
            pt.size == 6 && isIrisWriteSig(pt) -> true
            pt.size == 7 && isIrisArrayWriteSig(pt) -> true
            pt.size == 2 && pt[0] == Context::class.java && pt[1] == Int::class.javaPrimitiveType &&
                m.returnType == Void.TYPE -> true
            pt.size == 1 && pt[0] == Context::class.java && m.returnType == Void.TYPE -> true
            else -> false
        }
    }

    /** a: (Context,Z,I,I,I,I) */
    private fun isIrisWriteSig(pt: Array<Class<*>>): Boolean =
        pt[0] == Context::class.java && pt[1] == Boolean::class.javaPrimitiveType &&
            pt[2] == Int::class.javaPrimitiveType && pt[3] == Int::class.javaPrimitiveType &&
            pt[4] == Int::class.javaPrimitiveType && pt[5] == Int::class.javaPrimitiveType

    /** b: (Context,Z,I,I,int[],int[],int[]) */
    private fun isIrisArrayWriteSig(pt: Array<Class<*>>): Boolean =
        pt[0] == Context::class.java && pt[1] == Boolean::class.javaPrimitiveType &&
            pt[2] == Int::class.javaPrimitiveType && pt[3] == Int::class.javaPrimitiveType &&
            pt[4] == IntArray::class.java && pt[5] == IntArray::class.java &&
            pt[6] == IntArray::class.java

    private fun hook(module: HookEntry, method: Method) {
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                return if (takeoverEnabled()) {
                    module.logD(TAG, "blocked IrisUtils.${method.name} (takeover)")
                    null // void 方法：跳过原实现
                } else {
                    chain.proceed()
                }
            }
        })
    }

    private fun takeoverEnabled(): Boolean =
        runCatching { prefs?.getBoolean(KEY_TAKEOVER, false) }.getOrNull() == true
}
