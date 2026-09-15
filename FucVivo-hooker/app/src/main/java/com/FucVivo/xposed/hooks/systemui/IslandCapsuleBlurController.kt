package com.FucVivo.xposed.hooks.systemui

import android.content.SharedPreferences
import com.FucVivo.xposed.HookEntry
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 原子岛胶囊态模糊统一控制器（仅 com.vivo.systemuiplugin 进程）。
 *
 * 逆向依据（rom-decomp/SystemUIPlugin com.vivo.island，OriginOS 15）：
 * ContainerAnimatorCommon 对岛容器做两套外观：
 *  - 展开态（expandFraction>=0.3）：updateBlurAppearance(true) →
 *    View.setMaterialForce(0,0) + setMaterialCustomized(半径0.15/黑色遮罩0.9/...)+
 *    setMaterialRegion(胶囊矩形)——深色实时模糊材质，走 View 门面（BlurStyleController 已覆盖，
 *    因此半径/不透明度滑条对展开态生效）；
 *  - 胶囊态（收起）：updateBlurAppearance(false) → clearMaterial()，回退
 *    drawBounds() 里 mPaint 纯黑填充（getPaintColor()）——不受任何模糊挂面影响，恒黑。
 *
 * 统一策略（保持系统原有安全门控）：
 *  - 仅在系统实时模糊可用（IslandAnimationConfigUtils.isRealtimeBlurSwitchEnable()
 *    && !isLowPowerModeOn()，单例反射读取，与原生 updateMainCapContentAppearance 同条件）时，
 *    把 updateBlurAppearance(false) 提级为 true —— 胶囊态获得与展开态同一材质；
 *    （双胶囊 DUAL_CAP 下主胶囊几何本就是横跨挖孔的单个宽胶囊，副胶囊在其边界内，
 *      材质区域 mainRect 天然全覆盖，无需排除；过渡瞬间副胶囊外溢部分随黑色画刷
 *      一并透明化，动画结束后即归位）
 *  - 同时 getPaintColor() 在材质生效期间返回全透明，撤掉纯黑填充避免盖住模糊；
 *  - 低电量/实时模糊关闭时完全走原逻辑（黑色胶囊），不引入不可见风险。
 *
 * remote prefs 键：enable_islandblur（默认 true，功能开关卡控制）。
 */
object IslandCapsuleBlurController {

    private const val TAG = "FucVivoIslandBlur"

    const val KEY_ENABLE = "enable_islandblur"

    @Volatile
    private var islandEnabled = true

    /** 材质当前是否处于生效状态（updateBlurAppearance 实际放行 true 时置位） */
    @Volatile
    private var blurActive = false

    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    // 反射缓存（install 时一次性解析，逐方法容错）
    private var animatorClass: Class<*>? = null
    private var configGetInstance: Method? = null
    private var configSInstance: Field? = null
    private var configBlurSwitch: Method? = null
    private var configLowPower: Method? = null
    private var animatorContainerField: Field? = null
    private var flagsOk = false

    /** 最近一次拦截到的动画器/容器（弱引用，用于配置变更时实时重放） */
    private var animatorRef = java.lang.ref.WeakReference<Any>(null)
    private var containerRef = java.lang.ref.WeakReference<android.view.View>(null)
    private var updateBlurAppearanceMethod: Method? = null
    private var blurVisibleField: Field? = null
    private var materialMethod: Method? = null

    fun install(module: HookEntry, cl: ClassLoader) {
        android.util.Log.i(TAG, "install start")
        val prefs = module.hookedPrefs
        islandEnabled = prefs.getBoolean(KEY_ENABLE, true)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            when (key) {
                KEY_ENABLE -> {
                    islandEnabled = p.getBoolean(KEY_ENABLE, true)
                    if (!islandEnabled) blurActive = false
                    android.util.Log.i(TAG, "config: enable=$islandEnabled")
                    // 开关立即翻转当前外观：走一遍 updateBlurAppearance(false)，
                    // 由 hook 决定提级为材质（开）还是 clearMaterial（关）
                    postUpdateBlurAppearance(false)
                }
                // 半径/不透明度/总开关变化：材质参数只在应用时烘焙一次，
                // 激活状态下需用原始参数重放 setMaterialCustomized（门面 hook 按最新滑条值缩放）。
                // 岛容器（DynamicCapsuleContainer）归「原子岛」场景子项；旧版全局键
                // 仍是其回退值，变化同样触发重放
                BlurStyleController.radiusKey(BlurStyleController.BlurScene.ISLAND),
                BlurStyleController.alphaKey(BlurStyleController.BlurScene.ISLAND),
                BlurStyleController.KEY_RADIUS_SCALE,
                BlurStyleController.KEY_ALPHA_SCALE,
                BlurStyleController.KEY_ENABLE -> reapplyMaterialIfActive()
            }
        }
        listeners += listener
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }

        var mounted = 0
        try {
            resolveRefs(module, cl)
        } catch (t: Throwable) {
            android.util.Log.i(TAG, "refs resolve failed, skip all: $t")
            return
        }
        if (!flagsOk) {
            android.util.Log.i(TAG, "ROM blur flags off, nothing to unify")
            return
        }
        val cls = animatorClass ?: return
        mounted += if (hookUpdateBlurAppearance(module, cls)) 1 else 0
        mounted += if (hookGetPaintColor(module, cls)) 1 else 0
        android.util.Log.i(TAG, "installed: $mounted/2 surfaces")
        module.logD(TAG, "installed: $mounted/2 surfaces")
    }

    private fun resolveRefs(module: HookEntry, cl: ClassLoader) {
        val anim = cl.loadClass("com.vivo.island.animation.ContainerAnimatorCommon")
        animatorClass = anim

        val configs = cl.loadClass("com.vivo.island.animation.IslandAnimConfigsCommon")
        val support = configs.getField("SUPPORT_REAL_TIME_BLUR").getBoolean(null)
        val debugOn = configs.getField("DEBUG_ENABLE_ISLAND_BLUR").getBoolean(null)
        flagsOk = support && debugOn

        val configUtils = cl.loadClass("com.vivo.island.animation.IslandAnimationConfigUtils")
        configGetInstance = configUtils.getDeclaredMethod(
            "getInstance", android.content.Context::class.java
        )
        configSInstance = configUtils.getDeclaredField("sInstance").apply { isAccessible = true }
        configBlurSwitch = configUtils.getDeclaredMethod("isRealtimeBlurSwitchEnable")
        configLowPower = configUtils.getDeclaredMethod("isLowPowerModeOn")

        animatorContainerField = findField(anim, "mContainer").apply { isAccessible = true }
        // mBlurVisible：ROM updateBlurAppearance 的早退状态位——true 时再传 true
        // 只更新材质区域、不重烘材质参数。重放前先复位再走完整原生序列
        blurVisibleField = runCatching {
            findField(anim, "mBlurVisible").apply { isAccessible = true }
        }.getOrNull()
    }

    /** 字段可能声明在父类（mContainer/mStateController 在 AbsContainerAnimator），向上逐级找 */
    private fun findField(cls: Class<*>, name: String): Field {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching { return c.getDeclaredField(name) }
            c = c.superclass
        }
        throw NoSuchFieldException("$name not found from ${cls.name}")
    }

    private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            runCatching { return c.getDeclaredMethod(name, *params) }
            c = c.superclass
        }
        throw NoSuchMethodException("$name not found from ${cls.name}")
    }

    // ---------- updateBlurAppearance：胶囊态提级为材质模糊 ----------

    private fun hookUpdateBlurAppearance(module: HookEntry, cls: Class<*>): Boolean = runCatching {
        val m = findMethod(cls, "updateBlurAppearance", java.lang.Boolean.TYPE)
        updateBlurAppearanceMethod = m
        module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val animator = chain.thisObject
                if (animator != null) {
                    animatorRef = java.lang.ref.WeakReference(animator)
                    runCatching {
                        (animatorContainerField?.get(animator) as? android.view.View)
                            ?.let { containerRef = java.lang.ref.WeakReference(it) }
                    }
                }
                val z = chain.args.getOrNull(0) as? Boolean
                if (z == null || !BlurStyleController.featureActive || !islandEnabled) {
                    return chain.proceed()
                }
                var effective = z
                if (!z && systemBlurAvailable()) {
                    effective = true
                }
                blurActive = effective
                return if (effective == z) chain.proceed()
                else chain.proceed(arrayOf<Any?>(effective))
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip updateBlurAppearance: $it")
        false
    }

    // ---------- getPaintColor：材质生效时撤掉纯黑填充 ----------

    private fun hookGetPaintColor(module: HookEntry, cls: Class<*>): Boolean = runCatching {
        val m = findMethod(cls, "getPaintColor")
        module.hook(m).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val original = chain.proceed()
                if (BlurStyleController.featureActive && islandEnabled && blurActive) {
                    return 0 // 全透明：胶囊外观交给与展开态相同的材质
                }
                return original
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip getPaintColor: $it")
        false
    }

    // ---------- 配置变更的实时刷新 ----------

    /** 在容器主线程上重放一次 updateBlurAppearance(false)，由 hook 决定提级/还原 */
    private fun postUpdateBlurAppearance(z: Boolean) {
        val container = containerRef.get() ?: return
        val animator = animatorRef.get() ?: return
        val method = updateBlurAppearanceMethod ?: return
        container.post {
            runCatching { method.invoke(animator, z) }
                .onFailure { android.util.Log.i(TAG, "postUpdateBlurAppearance failed: $it") }
        }
    }

    /**
     * 材质参数只在应用瞬间烘焙，滑条变化后需重放 setMaterialCustomized。
     * 传原始（未缩放）参数——View 门面挂面会按当前半径/不透明度滑条值缩放。
     * 参数与 ContainerAnimatorCommon.updateBlurAppearance 原生应用值一致：
     * (0.15, 0.0, #000000, 0.9, 0, #000000, 0.0, 0, 1.0, 0)
     */
    /**
     * 材质参数只在应用瞬间烘焙，滑条变化后需重新触发应用。
     *
     * 统一重放：复位 ROM 早退状态位 mBlurVisible 后调用 updateBlurAppearance(false)——
     * 由 updateBlurAppearance 挂面按系统条件决定提级为材质（等价于 ROM 自身对胶囊态
     * 的调用）；mBlurVisible 已复位，ROM 不会走「mBlurVisible==z」早退，而是重跑完整
     * 原生序列（setMaterialForce + setMaterialCustomized + setMaterialRegion），
     * 参数经 View 门面挂面按当前滑条值缩放。相比 v1 手动重发 setMaterialCustomized
     * 不漏 force/region；相比复位后传 true，还能覆盖 blurActive 状态陈旧
     * （材质未显示时系统条件满足也照常提级）的情形。
     * 系统实时模糊不可用/功能关闭时挂面不提级，ROM 收到 false 且状态位已复位 →
     * 早退无操作，安全。
     *
     * 回退：反射拿不到 mBlurVisible 时，手动重放 setMaterialCustomized 原始参数
     * （与 ContainerAnimatorCommon.updateBlurAppearance 原生应用值一致：
     * 0.15, 0.0, #000000, 0.9, 0, #000000, 0.0, 0, 1.0, 0）。
     */
    private fun reapplyMaterialIfActive() {
        val container = containerRef.get()
        val animator = animatorRef.get()
        val uba = updateBlurAppearanceMethod
        if (container == null || animator == null || uba == null) {
            android.util.Log.i(TAG, "replay skipped: refs missing container=${container != null} animator=${animator != null}")
            return
        }
        container.post {
            runCatching {
                val bv = blurVisibleField
                if (bv != null) {
                    bv.setBoolean(animator, false)
                    uba.invoke(animator, false)
                    android.util.Log.i(TAG, "replay via updateBlurAppearance(false), blurActive=$blurActive")
                } else {
                    val m = materialMethod ?: findMethod(
                        container.javaClass, "setMaterialCustomized",
                        java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                        java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE,
                        java.lang.Float.TYPE, Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                    ).also { it.isAccessible = true; materialMethod = it }
                    m.invoke(
                        container,
                        0.15f, 0.0f, -0x1000000, 0.9f, 0,
                        -0x1000000, 0.0f, 0, 1.0f, 0,
                    )
                    android.util.Log.i(TAG, "replay via manual customized (mBlurVisible unavailable)")
                }
            }.onFailure { android.util.Log.i(TAG, "reapplyMaterial failed: $it") }
        }
    }

    // ---------- 门控读取（与原生同条件） ----------

    private fun systemBlurAvailable(): Boolean = runCatching {
        val inst = configSInstance?.get(null)
            ?: configGetInstance?.invoke(null, appContext())
        inst != null &&
            (configBlurSwitch?.invoke(inst) as? Boolean == true) &&
            (configLowPower?.invoke(inst) as? Boolean == false)
    }.getOrDefault(false)

    /** plugin 进程内取 Application Context */
    private fun appContext(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context
    }.getOrNull()
}
