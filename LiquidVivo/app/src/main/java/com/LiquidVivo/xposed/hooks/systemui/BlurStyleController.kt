package com.LiquidVivo.xposed.hooks.systemui

import android.content.SharedPreferences
import android.view.View
import com.LiquidVivo.xposed.HookEntry
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SystemUI / SystemUIPlugin 模糊与透明度自定义控制器。
 *
 * 挂载依据（OriginOS 15 实机 ROM 验证：AAX/rom/framework/framework.jar dexdump
 * + vivo-framework.jar + rom-decomp，详见 shared/md/AAX/rom/ 01/03/04 文档）：
 *
 * android.view.View（framework.jar）声明的是材质【门面】方法（隐藏 API，PUBLIC）：
 *   setMaterial(II)Z / setMaterial(III)Z（O6 主入口，三参带 materialPositionType）/
 *   setMaterialForce(II/III)Z / setMaterialAlpha(F) / setMaterialBlurRadius(F) /
 *   setMaterialCustomized(FFIFIIFIFI / FFIFIIFIIFIFI / FFIFIIFIIFIIFII / Bundle) /
 *   setMaterialMaskFusion(FIFIIFIFI / FIFIIFIIFIIFI)（O6 新增 12 参带第三遮罩层）/
 *   setMaterialDarkenPercent(F) ...
 * 底层 setBlurRadius/setBlurAlpha/setBlurCustomized/setWindowBlur* 不在 View 上，
 * 在 android.view.VivoViewImpl（vivo-framework.jar，bootclasspath），门面内部委托过去。
 * SystemUI/Plugin 的所有调用（originui VBlurUtils 反射、VivoMaterialBlurDelegator 等）
 * 都落在 View 门面方法上 —— 因此只挂门面，天然全覆盖且无二次缩放（不再挂底层委托目标）。
 * 门面缺失的 ROM 回退挂底层（View + VivoViewImpl 双探，O6 回退覆盖 10/13/14 参）。
 *
 * 其余挂面：
 *  - 材质 token：android.view.blur.BlurMaterial.resolveBlurGeometryByStyle(BlurGeometry, style)
 *    静态解析 76 个材质常量的几何参数。已验证（framework.jar View.java +
 *    vivo-framework VivoViewImpl）：View.setMaterial(II)/setMaterialForce(II) →
 *    VivoViewImpl.setMaterialForce → setWindowBlurEffect/setBackdropBlurEffect，
 *    解析全部发生在同一调用栈内 —— 这两个门面挂点用 ThreadLocal 记录接收者场景，
 *    解析时读取归场景；栈外调用（无法归因）回落「其他表面」。
 *  - SystemUI 私有 Drawable 栈（不走 View 扩展）：
 *    O6 起 VivoBackgroundBlurDrawable 的 setBlurRadius(int)/setColor/setFallbackColor 已删除，
 *    改由 BlurDrawableController.getDrawable() 创建时直接写字段（mBlurRadius=300、
 *    mPaint/mDisableBlurPaint 取资源色）。挂点改为 after-hook getDrawable 缩放 mBlurRadius
 *    与 Paint alpha（存原始值幂等），配置变更时遍历 Aggregator.mDrawables 重放。
 *    该栈是下拉面板/锁屏的窗口级遮罩（NotificationShadeWindowView / KeyguardHostContainer
 *    共用），主用户是通知中心面板 → 归「通知中心」子项。
 *
 * ---------- 场景模型 ----------
 * 半径/不透明度按生效场景拆分为独立子项，键名 = 前缀 + 场景后缀：
 *   blur_radius_scale_shade / blur_radius_scale_volume / blur_radius_scale_keyguard /
 *   blur_radius_scale_island / blur_radius_scale_other
 *   blur_alpha_scale_shade / blur_alpha_scale_volume / blur_alpha_scale_keyguard /
 *   blur_alpha_scale_popup / blur_alpha_scale_island / blur_alpha_scale_other
 *
 * 场景识别（1.5.0 v2，实机回归后修订）：
 *  1. 类名锚点：沿接收者 View 自身类 + 父链（≤24 级）按 simpleName 匹配，
 *     优先级 原子岛 > 音量 > 锁屏 > 通知中心。锚点依据（rom-decomp 源码核对）：
 *       音量：VivoVolumeSeekbar（材质主体）
 *       锁屏：Keyguard* 前缀 + BouncerBlurView / BaseRealtimeBlurView / ChargeBlurView 等
 *       通知中心：NotificationStackScrollLayout / PhoneStatusBarView / VivoPanelViewHolder
 *         + QS 容器（QSPanel / QSContainerImpl / VivoQSCenter 等）——实机验证控制中心
 *         与通知中心共用同一面板背景表面，无法分别调节，并入通知中心子项
 *       原子岛：DynamicCapsuleContainer（com.vivo.island.view，展开/胶囊态材质载体）
 *       系统弹窗：VCustomRoundRectLayout（originui dialog/sheet 圆角容器）/
 *         VListPopupWindow / VSpinner
 *  2. 窗口类型兜底（优先于弹窗锚点与未匹配结论）：接收者所在窗口类型为
 *     TYPE_VOLUME_OVERLAY(2020) → 音量面板（音量对话框内部视图无类名锚点，
 *     实测窗口类型是可靠信号；VivoVolumeNewImpl 即 setType(2020)）
 *  3. 通知卡片兜底：识别为通知卡片表面（v3）且无锚点命中时归通知中心
 *     （悬浮/锁屏通知行可能未挂入带锚点的层级即被应用材质）
 *  4. 其余 → 其他表面（含 token 解析的栈外调用）
 *
 * 迁移兼容：旧版全局键 blur_radius_scale / blur_alpha_scale 保留为回退值——
 * 场景键未单独设置时沿用全局键（存量用户配置不丢失）；两者皆无 → 100%。
 * 1.5.0(35) 曾短暂引入的 blur_*_scale_qs 键已废弃（控制中心并入通知中心）。
 *
 * remote prefs 键（32 分片路由，见 ShardedHookPrefs）：
 *  - enable_blur       总开关（默认 true）
 *  - 场景键如上（0-200，默认 100）
 *
 * 诊断：安装结果用 android.util.Log 直写 logcat（tag [TAG]），
 * 不依赖 libxposed 服务侧日志路由，logcat -s LiquidVivoBlurStyle 即可查。
 *
 * v3 修复（通知卡片防透）：悬浮通知/锁屏通知卡片走 VivoNotificationBlurController →
 * NotificationBackgroundView/NotificationHeaderView，其 setMaterialAlpha 传的是行
 * 动画 alpha（非设计不透明度），遮罩不透明度本就低（亮色 0.2/0.4、暗色 0.25/0.6），
 * 两处同时 ×alphaScale 造成二次缩放（有效值 ≈ scale²），且缩放后的值会触发 ROM
 * 「alpha<0.1 禁用模糊/兜底色」门控 → 通知卡片近乎全透明。
* 处理：通知卡片表面跳过 setMaterialAlpha 类缩放；setMaterialCustomized 的遮罩
* 不透明度改温和映射（0.5+0.5×场景缩放，最低保留 50% 设计值），finalOpacity 不缩放。
*
* v2 增补（实机回归）：
 *
 * v4 液态玻璃：开时遮罩走 overlayScaleOf（场景 alpha × 玻璃不透明度，
 * 默认 0.4），饱和度 ×1.5；通知卡片 customized 遮罩也吃玻璃不透明度（半径 0 / 玻璃 0
 * 仍全透明）。卡片 scaled 后低于 0.12 时抬到 0.12，避开 ROM alpha≤0.1 关模糊门控。
 * 控制中心磁贴/按钮/亮度条按类名归 SHADE（Plugin 进程同样重放快照）。
 *  - setMaterialMaskFusion(F,I,F,I,I,F,I,F,I) 挂面：面板/锁屏遮罩不透明度与
 *    最终不透明度（[2]/[5]/[7]）此前无挂点 → 通知中心/锁屏的「不透明度」不生效；
 *    [0] 语义不明（疑似 fusion 参数，非模糊半径）不缩放。通知卡片无此调用路径，
 *    不做温和映射。
 *  - setMaterial(II)/setMaterialForce(II) 挂点：token 型材质（音量面板、系统弹窗、
 *    部分通知表面走 VBlurUtils.setBlurEffect → setMaterial 模式）经 ThreadLocal
 *    归场景后再进 token 解析。
 */
object BlurStyleController {

    private const val TAG = "LiquidVivoBlurStyle"

    const val KEY_ENABLE = "enable_blur"

    /** 旧版全局键（1.4.x）：场景键缺省时的回退值，勿删 */
    const val KEY_RADIUS_SCALE = "blur_radius_scale"
    const val KEY_ALPHA_SCALE = "blur_alpha_scale"

    const val KEY_RADIUS_PREFIX = "blur_radius_scale_"
    const val KEY_ALPHA_PREFIX = "blur_alpha_scale_"

    const val DEFAULT_SCALE = 100
    const val MIN_SCALE = 0
    const val MAX_SCALE = 200

    /** 液态玻璃：开关 + 遮罩不透明度（0=全透明） */
    const val KEY_LIQUID_GLASS = "enable_liquidglass"
    const val KEY_GLASS_OPACITY = "blur_glass_opacity"
    const val DEFAULT_GLASS_OPACITY = 40
    const val MIN_GLASS_OPACITY = 0
    const val MAX_GLASS_OPACITY = 100

    /** WeKit 悬浮底栏的可调模糊半径（px），映射到原生材质半径。 */
    const val KEY_GLASS_BLUR_RADIUS = "blur_glass_radius_px"
    const val DEFAULT_GLASS_BLUR_RADIUS = 8
    const val MIN_GLASS_BLUR_RADIUS = 0
    const val MAX_GLASS_BLUR_RADIUS = 32

    const val PKG_SYSTEMUI = "com.android.systemui"
    const val PKG_SYSTEMUI_PLUGIN = "com.vivo.systemuiplugin"
    const val PKG_UPSLIDE = "com.vivo.upslide"
    const val PKG_CARD = "com.vivo.card"

    /** WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY（音量对话框窗口） */
    private const val WINDOW_TYPE_VOLUME_OVERLAY = 2020

    /** 场景父链探测深度上限（vivo 通知行嵌套较深，12 级实测不够） */
    private const val MAX_PARENT_DEPTH = 24

    /**
     * 生效场景。suffix 参与组键；hasRadius/hasAlpha 标记该场景暴露哪些调节子项
     * （POPUP 的模糊半径由 ROM 设计值决定、无独立子项，但 hook 侧仍按场景解析——
     * 其半径回退到全局/默认值，与未拆分前行为一致）。
     * 控制中心与通知中心实机验证共用同一面板背景表面 → 不设独立场景。
     * SHADE 不透明度：面板遮罩浓度（setMaterialMaskFusion 参数）由 ROM 在面板
     * 初始化时烘焙一次，下拉过程不重放，滑条无法实时生效 → 实机回归后砍掉子项
     * （挂面保留，走旧版全局键回退，语义与 1.4.x 一致）。
     * KEYGUARD 不透明度：锁屏实时模糊（BaseRealtimeBlurView）遮罩 opacity 恒为 0，
     * 观感浓度走 setMaterialDarkenPercent(进度×0.45)——动画驱动值，缩放会破坏
     * 渐变（同 v3 通知卡片教训）→ 实机回归后砍掉子项。
     */
    enum class BlurScene(val suffix: String, val hasRadius: Boolean, val hasAlpha: Boolean) {
        SHADE("shade", true, false),
        VOLUME("volume", true, true),
        KEYGUARD("keyguard", true, false),
        POPUP("popup", false, true),
        ISLAND("island", true, true),
        OTHER("other", true, true),
    }

    fun radiusKey(scene: BlurScene): String = KEY_RADIUS_PREFIX + scene.suffix
    fun alphaKey(scene: BlurScene): String = KEY_ALPHA_PREFIX + scene.suffix

    @Volatile
    private var enabled = true

    /** 场景 → 缩放系数（0-2），下标用 ordinal */
    private val radiusScales = FloatArray(BlurScene.entries.size) { 1f }
    private val alphaScales = FloatArray(BlurScene.entries.size) { 1f }

    @Volatile
    private var liquidGlass = true

    /** 液态玻璃遮罩不透明度 0-1 */
    @Volatile
    private var glassOpacity = DEFAULT_GLASS_OPACITY / 100f

    @Volatile
    private var glassBlurRadiusPx = DEFAULT_GLASS_BLUR_RADIUS

    /** 当前进程是否为 OriginOS 侧边栏宿主。该进程中的未命名材质按通知中心处理。 */
    @Volatile
    private var upslideProcess = false

    @Volatile
    private var cardProcess = false

    /** 总开关状态（供同域控制器（如原子岛胶囊统一）做联合门控） */
    val featureActive: Boolean
        get() = enabled

    /** listener 可能被弱引用，强引用保活 */
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    // ---------- 实时生效：材质调用快照重放 ----------
    // 设置（滑块/开关）变化后，系统不会自动重调已显示的材质 → 记录最近一次
    // 材质调用的原始参数，配置变更时按新设置反射重放，立即刷新画面，无需重启进程。

    /** 一次材质调用的快照（保存缩放前的原始参数） */
    private class MaterialCall(val method: Method, val args: Array<Any?>)

    /** 接收者 View → (方法 → 最近一次调用快照)。弱引用：View 回收自动清理 */
    private val materialSnapshots =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, MutableMap<Method, MaterialCall>>())

    private fun snapshotMaterial(view: View?, method: Method, args: Array<Any?>) {
        if (view == null) return
        synchronized(materialSnapshots) {
            materialSnapshots.getOrPut(view) { HashMap() }[method] = MaterialCall(method, args.copyOf())
        }
    }

    /** 配置变更后重放当前材质快照：反射 invoke 原方法 → 重新进入 hook →
     *  按最新设置缩放 → proceed。invoke 走 hook 分发，proceed 不再触发 hook，无递归。 */
    private fun replayMaterialSnapshots() {
        if (!enabled) return
        val calls = synchronized(materialSnapshots) {
            materialSnapshots.entries.flatMap { (view, byMethod) ->
                byMethod.values.map { call -> call to view }
            }
        }
        var replayed = 0
        for ((call, view) in calls) {
            runCatching { call.method.invoke(view, *call.args) }.onFailure { /* 视图已失效：忽略 */ }
            replayed++
        }
        diag("replay", "replayed $replayed material calls")
    }

    /** 已处理的重启动作时间戳（防重复/防进程启动后误触发） */
    @Volatile
    private var lastRestartToken = 0L

    /**
     * 收到 app 侧重启请求后延迟自杀：system_server 会立即重新拉起 SystemUI，
     * 重新 fork 时 LSPosed 注入最新模块代码。延迟 600ms 让 prefs 事务落盘、
     * app 侧提示来得及展示。
     */
    private fun scheduleSelfRestart(prefs: SharedPreferences) {
        val token = runCatching {
            prefs.getLong(HookEntry.ACTION_RESTART_SYSTEMUI, 0L)
        }.getOrDefault(0L)
        if (token <= 0L || token == lastRestartToken) return
        lastRestartToken = token
        android.util.Log.i(TAG, "restart SystemUI requested (token=$token), killing self in 600ms")
        diag("restart", "requested token=$token, killing self")
        Thread {
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        }.also { it.isDaemon = true }.start()
    }

    // BlurGeometry 字段反射缓存（android.view.blur.IVivoBlurRender$BlurGeometry）
    private val geometryFields = HashMap<String, Field?>()

    /** setMaterial(II)/setMaterialForce(II) 调用栈内的接收者场景（供 token 解析归因） */
    private val threadScene = ThreadLocal<BlurScene>()

    /** 诊断：按 key 限流打印（前 8 次 + 每 40 次），定位材质模糊数据流。
     *  vivo user 版静音了 SystemUI 的 logcat，同步落盘到自身外部 files 目录供 adb pull */
    private val diagCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun diag(key: String, msg: String) {
        val n = (diagCounts[key] ?: 0) + 1
        diagCounts[key] = n
        if (n <= 8 || n % 40 == 0) {
            android.util.Log.i(TAG, "[diag#$n] $key $msg")
            runCatching {
                val at = Class.forName("android.app.ActivityThread")
                val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
                    ?: return@runCatching
                val dir = app.getExternalFilesDir(null) ?: return@runCatching
                val f = java.io.File(dir, "liquidvivo_diag.log")
                f.appendText("${android.os.SystemClock.uptimeMillis()} #$n $key $msg\n")
            }
        }
    }

    /**
     * 视图资源条目名 → 场景 缓存（含未命中结论，null 值）。
     * 实机回归：音量对话框背景（mDialogView，id=volume_dialog，已验证插件资源表
     * 条目名未混淆）的材质在视图未附着窗口时即被应用，窗口类型/父链锚点都够不着
     * → 以接收者自身资源条目名兜底归类。用视图自己的 resources 解析，
     * 与所在进程/类加载器/附着状态均无关。
     */
    private val idNameSceneCache =
        java.util.Collections.synchronizedMap(HashMap<Int, BlurScene?>())

    fun install(module: HookEntry, cl: ClassLoader, packageName: String) {
        android.util.Log.i(TAG, "install start pkg=$packageName")
        upslideProcess = packageName == PKG_UPSLIDE
        cardProcess = packageName == PKG_CARD
        val prefs = module.hookedPrefs
        reload(prefs, verbose = true)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            // 重启系统界面动作：仅 SystemUI 主进程响应（plugin 进程不处理）
            if (key == HookEntry.ACTION_RESTART_SYSTEMUI) {
                if (packageName == PKG_SYSTEMUI) scheduleSelfRestart(p)
                return@OnSharedPreferenceChangeListener
            }
            if (key == KEY_ENABLE ||
                key?.startsWith(KEY_RADIUS_PREFIX) == true ||
                key?.startsWith(KEY_ALPHA_PREFIX) == true ||
                key == KEY_RADIUS_SCALE || key == KEY_ALPHA_SCALE ||
                key == KEY_LIQUID_GLASS || key == KEY_GLASS_OPACITY ||
                    key == KEY_GLASS_BLUR_RADIUS
            ) {
                reload(p)
                if (packageName == PKG_SYSTEMUI) {
                    reapplyDrawableStack()
                }
                // SystemUI 与 Plugin 各有一份快照：控制中心磁贴/音量/原子岛在 Plugin
                replayMaterialSnapshots()
            }
        }
        listeners += listener
        runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }

        val mounted = ArrayList<String>()
        hookViewSurface(module, cl, mounted)
        // 侧边栏及其展开卡片有一部分走 ColorDrawable 实底，不会经过标准材质参数入口。
        // 单独拦截这些宿主，替换为与通知中心相同的实时玻璃材质。
        hookForceGlassOnSidebar(module, cl, mounted)
        if (hookBlurMaterialTokens(module, cl)) mounted += "BlurMaterial.token"
        if (packageName == PKG_SYSTEMUI) {
            hookBlurDrawableStack(module, cl, mounted)
        }
        android.util.Log.i(TAG, "installed in $packageName: ${mounted.size} surfaces $mounted")
        module.logD(TAG, "installed in $packageName: ${mounted.size} surfaces $mounted")
    }

    private fun reload(prefs: SharedPreferences, verbose: Boolean = false) {
        enabled = prefs.getBoolean(KEY_ENABLE, true)
        liquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, true)
        glassOpacity = prefs.getInt(KEY_GLASS_OPACITY, DEFAULT_GLASS_OPACITY)
            .coerceIn(MIN_GLASS_OPACITY, MAX_GLASS_OPACITY) / 100f
        glassBlurRadiusPx = prefs.getInt(KEY_GLASS_BLUR_RADIUS, DEFAULT_GLASS_BLUR_RADIUS)
            .coerceIn(MIN_GLASS_BLUR_RADIUS, MAX_GLASS_BLUR_RADIUS)
        for (scene in BlurScene.entries) {
            radiusScales[scene.ordinal] =
                clampPercent(resolve(prefs, radiusKey(scene), KEY_RADIUS_SCALE)) / 100f
            alphaScales[scene.ordinal] =
                clampPercent(resolve(prefs, alphaKey(scene), KEY_ALPHA_SCALE)) / 100f
        }
        if (verbose) {
            android.util.Log.i(
                TAG,
                "config: enable=$enabled glass=$liquidGlass opacity=$glassOpacity radius=${scaleDump(radiusScales)} alpha=${scaleDump(alphaScales)}",
            )
        }
    }

    /** 场景键 → 旧版全局键 → 默认值 三级回退 */
    private fun resolve(prefs: SharedPreferences, sceneKey: String, legacyKey: String): Int =
        if (prefs.contains(sceneKey)) prefs.getInt(sceneKey, DEFAULT_SCALE)
        else if (prefs.contains(legacyKey)) prefs.getInt(legacyKey, DEFAULT_SCALE)
        else DEFAULT_SCALE

    private fun scaleDump(scales: FloatArray): String =
        BlurScene.entries.joinToString { "${it.suffix}=${scales[it.ordinal]}x" }

    private fun clampPercent(v: Int): Int = v.coerceIn(MIN_SCALE, MAX_SCALE)

    private fun radiusScaleOf(scene: BlurScene): Float = radiusScales[scene.ordinal]
    private fun alphaScaleOf(scene: BlurScene): Float = alphaScales[scene.ordinal]

    /**
     * 遮罩不透明度系数。液态玻璃开启时，半径为 0 的材质保持全透明；
     * 否则再乘玻璃不透明度（默认 0.4），让面板透过模糊而不是一层实底。
     */
    private fun overlayScaleOf(scene: BlurScene): Float {
        val sceneScale = alphaScales[scene.ordinal]
        if (!liquidGlass) return sceneScale
        if (radiusScales[scene.ordinal] <= 0f) return 0f
        return sceneScale * glassOpacity
    }

    /** 仅对 0-2 量纲的饱和度参数增强；vivo 面板取色参数保持原值。 */
    private fun glassSaturation(original: Float): Float {
        if (!liquidGlass) return original
        if (original <= 0f) return original
        if (original <= 2f) return (original * 1.5f).coerceAtMost(2f)
        return 0f
    }

    /**
     * 液态玻璃半径。控制中心激活磁贴（网络/震动/专注）原生 blurRadius=0，只铺实色。
     * 半径为 0 时补 0.22，让卡片真正采背后实时模糊。
     */
    private fun glassRadius(original: Float, scene: BlurScene): Float {
        val scaled = original * radiusScaleOf(scene)
        if (!liquidGlass) return scaled
        if (radiusScaleOf(scene) <= 0f) return 0f
        if (scaled < 0.08f) return 0.22f * radiusScaleOf(scene)
        return scaled
    }

    /** 侧边栏专用半径：与 WeKit 底栏默认 8px 的质感保持一致，并可实时调节。 */
    private fun sidebarGlassRadius(scene: BlurScene): Float =
        (glassBlurRadiusPx / 16f * radiusScaleOf(scene)).coerceIn(0f, 2f)

    // ---------- 场景识别 ----------

    /** 通知中心锚点（含 QS 容器：实机验证控制中心与通知中心共用面板背景表面） */
    private val SHADE_ANCHORS = setOf(
        "NotificationShade", "ShadeLayout", "NotificationStackScrollLayout",
        "PhoneStatusBarView", "VivoPanelViewHolder", "GlanceableHubContainer",
        "QSPanel", "QSContainerImpl", "QsFrame", "VivoQSCenter", "VivoQsStatusContainer",
        "QsPagedPage", "NonInterceptingScrollView", "PageIndicator",
        "QuickStatusBarHeader", "VivoQuickStatusBarHeader",
        "VivoPanelTileViewImpl", "NotificationClearAllImageView",
        "VivoSplitQsStatusBarContainer",
    )
    private val POPUP_ANCHORS = setOf(
        "VCustomRoundRectLayout", "VListPopupWindow", "VSpinner",
    )

    /** 类 → 场景锚点 缓存（ConcurrentHashMap 不存 null：只缓存命中类；
     *  非锚点类每次重新做字符串比较，开销为常数级可忽略） */
    private val anchorClassCache = ConcurrentHashMap<Class<*>, BlurScene>()

    /**
     * 场景判定：
     *  1. 类名锚点（自身类 + 父链，岛>音量>锁屏>通知中心/弹窗按匹配顺序）
     *  2. 弹窗锚点命中或无命中时，窗口类型兜底：音量窗口(2020) 优先归音量
     *  3. 通知卡片表面无锚点时归通知中心
     *  4. 其余 → 其他表面
     */
    private fun classifyScene(receiver: Any?): BlurScene {
        if (receiver !is View) return BlurScene.OTHER
        var classHit: BlurScene? = null
        var v: View? = receiver
        var depth = 0
        while (v != null && depth < MAX_PARENT_DEPTH) {
            matchAnchor(v.javaClass)?.let {
                classHit = it
                break
            }
            v = v.parent as? View
            depth++
        }
        when (classHit) {
            BlurScene.ISLAND, BlurScene.VOLUME, BlurScene.KEYGUARD, BlurScene.SHADE ->
                return classHit
            else -> { /* POPUP 或 null：进入兜底判定 */ }
        }
        if (windowTypeOf(receiver) == WINDOW_TYPE_VOLUME_OVERLAY) return BlurScene.VOLUME
        if (idSceneOf(receiver) == BlurScene.VOLUME) return BlurScene.VOLUME
        if (classHit == BlurScene.POPUP) return BlurScene.POPUP
        if (isNotificationSurface(receiver)) return BlurScene.SHADE
        if (upslideProcess || cardProcess) return BlurScene.SHADE
        return BlurScene.OTHER
    }

    /** OriginOS 原生 QS 圆卡自带玻璃材质；不要套用普通二级容器的最低半径/遮罩。 */
    private fun isNativeQsGlass(view: Any?): Boolean =
        (view as? View)?.javaClass?.simpleName?.contains("VivoSuperQSTileBg", ignoreCase = true) == true

    /** 接收者自身资源条目名归场景（音量对话框背景 id=volume_dialog，与附着状态无关） */
    private fun idSceneOf(view: View): BlurScene? {
        val id = view.id
        if (id == 0 || id == View.NO_ID) return null
        if (idNameSceneCache.containsKey(id)) return idNameSceneCache[id]
        val name = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
        val hit = when (name) {
            "volume_dialog" -> BlurScene.VOLUME
            else -> null
        }
        idNameSceneCache[id] = hit
        return hit
    }

    private fun matchAnchor(cls: Class<*>): BlurScene? {
        anchorClassCache[cls]?.let { return it }
        val simple = cls.simpleName
        val hit = when {
            simple == "DynamicCapsuleContainer" -> BlurScene.ISLAND
            simple == "VivoVolumeSeekbar" || simple.startsWith("VolumeDialog") -> BlurScene.VOLUME
            simple.startsWith("Keyguard") || simple == "BouncerBlurView" ||
                simple == "ChargeBlurView" || simple == "BaseRealtimeBlurView" ||
                simple == "BlurRenderView" -> BlurScene.KEYGUARD
            simple in SHADE_ANCHORS -> BlurScene.SHADE
            simple.contains("QSTile") || simple.contains("QsTile") ||
                simple.contains("QSCenter") || simple.contains("QsCenter") ||
                simple.contains("SuperQS") ||
                simple.endsWith("MoveBoolButton") ||
                simple.contains("Seekbar") || simple.contains("SeekBar") ||
                simple.startsWith("VivoEffect") ||
                simple.endsWith("DetailLayout") ||
                simple.contains("MixMusic") ||
                simple.contains("FakeWifi") ||
                simple.contains("FakeBluetooth") ||
                simple.contains("QSIOT") || simple.contains("IOT") ||
                simple.contains("ConnectGroup") ||
            simple.contains("MediaPlayer") ||
                simple.contains("FocusMode") ||
                // OriginOS 侧边栏/小窗/快捷工具的材质载体。不同版本类名略有差异，
                // 统一按关键词归入通知中心场景，复用同一套液态玻璃参数。
                simple.contains("Sidebar", ignoreCase = true) ||
                simple.contains("SideBar", ignoreCase = true) ||
                simple.contains("SidePanel", ignoreCase = true) ||
                simple.contains("SmallWindow", ignoreCase = true) ||
                simple.contains("SmallApp", ignoreCase = true) ||
                simple.contains("QuickTool", ignoreCase = true) ||
                simple.contains("QuickTools", ignoreCase = true) ||
                simple.contains("FloatPanel", ignoreCase = true) ||
                simple.contains("FloatingPanel", ignoreCase = true) ||
                simple.contains("VivoPanel", ignoreCase = true) -> BlurScene.SHADE
            simple in POPUP_ANCHORS -> BlurScene.POPUP
            else -> null
        }
        // 框架通用容器（android.* / androidx.*）可能出现在任意场景的层级中，不缓存
        val name = cls.name
        if (!name.startsWith("android.") && !name.startsWith("androidx.") && !name.startsWith("java.")) {
            hit?.let { anchorClassCache[cls] = it }
        }
        return hit
    }

    // ---------- 窗口类型识别（音量面板兜底） ----------

    private var getViewRootImplMethod: Method? = null
    private var windowAttributesField: Field? = null
    @Volatile
    private var windowTypeProbeFailed = false

    /**
     * 接收者所在窗口类型；反射失败一次后永久放弃（不影响其它判定路径）。
     * View.getViewRootImpl()（public @hide）+ ViewRootImpl.mWindowAttributes
     * （public final 字段，framework.jar 验证）。
     */
    private fun windowTypeOf(view: View): Int? {
        if (windowTypeProbeFailed) return null
        return runCatching {
            val m1 = getViewRootImplMethod
                ?: view.javaClass.getMethod("getViewRootImpl").also {
                    it.isAccessible = true
                    getViewRootImplMethod = it
                }
            val root = m1.invoke(view) ?: return null
            val f = windowAttributesField
                ?: root.javaClass.getField("mWindowAttributes").also {
                    windowAttributesField = it
                }
            (f.get(root) as? android.view.WindowManager.LayoutParams)?.type
        }.onFailure {
            windowTypeProbeFailed = true
            android.util.Log.i(TAG, "window type probe unavailable: $it")
        }.getOrNull()
    }

    // ---------- 通知卡片表面识别（v3 防透修复） ----------

    /** 类 → 是否通知卡片表面 缓存（同一 Class 只走一次继承链探测） */
    private val surfaceClassCache = java.util.Collections.synchronizedMap(HashMap<Class<*>, Boolean>())

    /**
     * 悬浮通知 / 锁屏通知 / 通知栏行 的材质载体：
     *   com.android.systemui.statusbar.notification.row.NotificationBackgroundView
     *   android.view.NotificationHeaderView（分组头）
     *   com.vivo.systemui.statusbar.notification.row.VivoNotificationBlockMenu
     * 按类名（含继承链）识别，不硬依赖 SystemUI 类。
     */
    private fun isNotificationSurface(receiver: Any?): Boolean {
        if (receiver == null) return false
        val cls = receiver.javaClass
        surfaceClassCache[cls]?.let { return it }
        var c: Class<*>? = cls
        var hit = false
        while (c != null && c != Any::class.java) {
            when (c.simpleName) {
                "NotificationBackgroundView", "NotificationHeaderView", "VivoNotificationBlockMenu",
                "ExpandableNotificationRow", "ActivatableNotificationView" -> {
                    hit = true
                    break
                }
            }
            c = c.superclass
        }
        surfaceClassCache[cls] = hit
        return hit
    }

    /** 通知卡片遮罩不透明度的温和映射：滑块 0% 也保留 50% 设计浓度，防不可读 */
    private fun gentleAlphaScale(scene: BlurScene): Float = 0.5f + 0.5f * alphaScaleOf(scene)

    /**
     * customized 遮罩。液态玻璃开：卡片与面板共用同一套轻透遮罩；
     * 卡片 scaled 后若仍 >0 且低于 0.12，抬到 0.12，避免 ROM ≤0.1 关模糊。
     * 玻璃关：卡片继续温和映射。
     */
    private fun maskScale(original: Float, scene: BlurScene, notif: Boolean): Float {
        if (notif && !liquidGlass) {
            return (original * gentleAlphaScale(scene)).coerceIn(0f, 1f)
        }
        return glassMask(original, scene)
    }

    /**
     * 液态玻璃遮罩。网络/震动/专注等卡原生 mask 只有 0.22/0.08，
     * 再乘玻璃不透明度会掉到 ROM <=0.1 关模糊门控以下，抬到 0.12。
     */
    private fun glassMask(original: Float, scene: BlurScene): Float {
        val scaled = (original * overlayScaleOf(scene)).coerceIn(0f, 1f)
        if (liquidGlass && original > 0f && overlayScaleOf(scene) > 0f) {
            return scaled.coerceAtLeast(0.12f)
        }
        return scaled
    }

    // ---------- android.view.View 材质门面（主挂面） ----------

    private fun hookViewSurface(module: HookEntry, cl: ClassLoader, mounted: MutableList<String>) {
        val viewClass = runCatching { cl.loadClass("android.view.View") }.getOrElse {
            android.util.Log.e(TAG, "load android.view.View failed: $it")
            return
        }

        // 门面：setMaterialAlpha / setMaterialBlurRadius / setMaterialCustomized(10/13/14)
        val facadeAlpha = hookFloat(module, viewClass, "setMaterialAlpha") { v, scene ->
            if (liquidGlass) v else (v * overlayScaleOf(scene)).coerceIn(0f, 1f)
        }
        if (facadeAlpha) mounted += "View.setMaterialAlpha"

        val facadeRadius = hookFloat(module, viewClass, "setMaterialBlurRadius") { v, scene ->
            glassRadius(v, scene)
        }
        if (facadeRadius) mounted += "View.setMaterialBlurRadius"

        val facadeC10 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 10)
        if (facadeC10) mounted += "View.setMaterialCustomized(10)"
        val facadeC13 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 13)
        if (facadeC13) mounted += "View.setMaterialCustomized(13)"
        val facadeC14 = hookCustomized(module, viewClass, "setMaterialCustomized", argCount = 14)
        if (facadeC14) mounted += "View.setMaterialCustomized(14)"

        // 遮罩融合：面板/锁屏遮罩不透明度（通知中心/锁屏「不透明度」的落点）
        // O6 新增带第三遮罩层（maskColor3/maskOpacity3/maskFusion3）的 12 参重载
        if (hookMaskFusion(module, viewClass, argCount = 9)) mounted += "View.setMaterialMaskFusion(9)"
        if (hookMaskFusion(module, viewClass, argCount = 12)) mounted += "View.setMaterialMaskFusion(12)"

        // token 型材质入口：记录接收者场景供 BlurMaterial 解析归因。
        // O6 起 setMaterial/setMaterialForce 新增 (style,contentType,materialPositionType) 三参主入口，
        // 两参/三参均挂（三参为实际主入口，两参委派带 0）
        if (hookMaterialTagger(module, viewClass, "setMaterial", argCount = 2)) mounted += "View.setMaterial(II)"
        if (hookMaterialTagger(module, viewClass, "setMaterial", argCount = 3)) mounted += "View.setMaterial(III)"
        if (hookMaterialTagger(module, viewClass, "setMaterialForce", argCount = 2)) mounted += "View.setMaterialForce(II)"
        if (hookMaterialTagger(module, viewClass, "setMaterialForce", argCount = 3)) mounted += "View.setMaterialForce(III)"

        // 回退：门面缺失的 ROM 改挂底层（View 与 VivoViewImpl 双探）。
        // 门面存在时不挂底层——门面内部委托底层，同时挂会二次缩放。
        val candidates = buildList {
            add(viewClass)
            runCatching { cl.loadClass("android.view.VivoViewImpl") }.getOrNull()?.let { add(it) }
        }
        if (!facadeAlpha) {
            for (c in candidates) {
                if (hookFloat(module, c, "setBlurAlpha") { v, scene ->
                        if (liquidGlass) v else (v * overlayScaleOf(scene)).coerceIn(0f, 1f)
                    }) {
                    mounted += "${c.simpleName}.setBlurAlpha"
                }
                if (hookFloat(module, c, "setWindowBlurAlpha") { v, scene ->
                        if (liquidGlass) v else (v * overlayScaleOf(scene)).coerceIn(0f, 1f)
                    }) {
                    mounted += "${c.simpleName}.setWindowBlurAlpha"
                }
            }
        }
        if (!facadeRadius) {
            for (c in candidates) {
                if (hookFloat(module, c, "setBlurRadius") { v, scene -> glassRadius(v, scene) }) {
                    mounted += "${c.simpleName}.setBlurRadius"
                }
                if (hookFloat(module, c, "setWindowBlurRadius") { v, scene -> glassRadius(v, scene) }) {
                    mounted += "${c.simpleName}.setWindowBlurRadius"
                }
            }
        }
        if (!facadeC10 && !facadeC13 && !facadeC14) {
            for (c in candidates) {
                for (name in arrayOf("setBlurCustomized", "setWindowBlurCustomized")) {
                    for (n in intArrayOf(10, 13, 14)) {
                        if (hookCustomized(module, c, name, argCount = n)) {
                            mounted += "${c.simpleName}.$name($n)"
                        }
                    }
                }
            }
        }
        if (hookRecorderBlur(module, viewClass)) mounted += "View.setRecorderBlurredBackground"
        hookAmbientLightVibrancy(module, cl, mounted)
        hookAddBlurBgRetry(module, cl, mounted)
        hookColorSupportBlur(module, cl, mounted)
        hookSupportBlur(module, cl, mounted)
        hookQsMaterialParams(module, cl, mounted)
        // 不拦截通用 View.setBackground/ setBackgroundColor：控制中心大量原生卡片
        // 共用这些入口，强行写入白色材质会把整个面板冲成白底。二级卡片只走
        // SystemUI 自己的材质参数入口，由 setMaterialCustomized / token hook 调节。
    }

    /** 液态玻璃：关掉静态截图模糊，改走实时背后。 */
    private fun hookRecorderBlur(module: HookEntry, viewClass: Class<*>): Boolean = runCatching {
        val method = viewClass.getDeclaredMethod("setRecorderBlurredBackground", java.lang.Boolean.TYPE)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled || !liquidGlass) return chain.proceed()
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty() && args[0] == true) args[0] = false
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse { false }

    /** 关掉控制中心磁贴的氛围取色（油膜彩斑），只留实时模糊。 */
    private fun hookAmbientLightVibrancy(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val cls = runCatching { cl.loadClass("com.vivo.systemlighteffect.sdk.ViewEffect") }.getOrNull() ?: return
        var n = 0
        for (method in cls.declaredMethods) {
            if (method.name != "enableAmbientLightVibrancy") continue
            runCatching {
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (!enabled || !liquidGlass) return chain.proceed()
                        val args = chain.args.toTypedArray()
                        if (args.isNotEmpty() && args[0] is Boolean) args[0] = false
                        return chain.proceed(args)
                    }
                })
                n++
            }
        }
        if (n > 0) mounted += "ViewEffect.enableAmbientLightVibrancy*$n"
    }

    /** 磁贴未附着时 addBlurBg 会早退，附着后再跑一次，避免要滑一下才出玻璃。 */
    private fun hookAddBlurBgRetry(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val cls = runCatching {
            cl.loadClass("com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper")
        }.getOrNull() ?: return
        val method = runCatching { cls.getDeclaredMethod("addBlurBg") }.getOrNull() ?: return
        val viewField = runCatching { cls.getDeclaredField("mView").apply { isAccessible = true } }.getOrNull()
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (!enabled || !liquidGlass || viewField == null) return result
                    val helper = chain.thisObject ?: return result
                    val view = viewField.get(helper) as? View ?: return result
                    if (view.isAttachedToWindow) return result
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            v.removeOnAttachStateChangeListener(this)
                            runCatching { method.invoke(helper) }
                        }
                        override fun onViewDetachedFromWindow(v: View) {}
                    })
                    return result
                }
            })
            mounted += "VivoEffectHelper.addBlurBg"
        }
    }

    /** 激活磁贴颜色常被判定不支持模糊 → 走实色。液态玻璃强制走材质路径。 */
    private fun hookColorSupportBlur(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val cls = runCatching {
            cl.loadClass("com.vivo.systemuiplugin.systemui.qs.utils.QsUtils")
        }.getOrNull() ?: return
        val method = runCatching {
            cls.getDeclaredMethod("colorSupportBlur", android.content.Context::class.java, Integer.TYPE)
        }.getOrNull() ?: return
        runCatching {
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (enabled && liquidGlass) return true
                    return chain.proceed()
                }
            })
            mounted += "QsUtils.colorSupportBlur"
        }
    }

    /** 网络/震动/专注等自定义卡没开 blur flag 时走实色。液态玻璃强制开模糊。 */
    private fun hookSupportBlur(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val cls = runCatching {
            cl.loadClass("com.vivo.systemuiplugin.systemui.qs.utils.VivoEffectHelper")
        }.getOrNull() ?: return
        for (name in arrayOf("supportBlur", "supportBlurIgnoreFlag")) {
            val method = runCatching { cls.getDeclaredMethod(name) }.getOrNull() ?: continue
            runCatching {
                module.hook(method).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (enabled && liquidGlass) return true
                        return chain.proceed()
                    }
                })
                mounted += "VivoEffectHelper.$name"
            }
        }
        runCatching {
            val clear = cls.getDeclaredMethod("clearBlurBg")
            module.hook(clear).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (enabled && liquidGlass) return null
                    return chain.proceed()
                }
            })
            mounted += "VivoEffectHelper.clearBlurBg"
        }
        runCatching {
            val addBlur = cls.getDeclaredMethod("addBlurBg")
            val paramsField = runCatching {
                cls.getDeclaredField("mMaterialParams").apply { isAccessible = true }
            }.getOrNull()
            module.hook(addBlur).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (enabled && liquidGlass && paramsField != null) {
                        val params = paramsField.get(chain.thisObject)
                        if (params != null) {
                            runCatching {
                                val field = params.javaClass.getField("blurRadius")
                                val v = field.getFloat(params)
                                val patched = glassRadius(v, BlurScene.SHADE)
                                if (patched != v) field.setFloat(params, patched)
                            }
                        }
                    }
                    return chain.proceed()
                }
            })
        }
    }

    /** 激活磁贴 blurRadius=0。工厂方法返回后补半径。 */
    private fun hookQsMaterialParams(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val names = arrayOf(
            "com.vivo.systemuiplugin.systemui.qs.ui.VivoThreeLayerMaterialParamsKt",
            "com.vivo.systemuiplugin.systemui.qs.utils.QsUtils",
        )
        var n = 0
        for (className in names) {
            val cls = runCatching { cl.loadClass(className) }.getOrNull() ?: continue
            for (method in cls.declaredMethods) {
                if (!method.name.startsWith("getQs") || !method.name.contains("MaterialParam")) continue
                if (method.parameterCount != 0) continue
                runCatching {
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val result = chain.proceed()
                            if (enabled && liquidGlass && result != null) {
                                runCatching {
                                    val field = result.javaClass.getField("blurRadius")
                                    field.setFloat(
                                        result,
                                        glassRadius(field.getFloat(result), BlurScene.SHADE),
                                    )
                                }
                            }
                            return result
                        }
                    })
                    n++
                }
            }
        }
        if (n > 0) mounted += "QsMaterialParam*$n"
    }

    /** 侧边栏及其展开卡片用 ColorDrawable 实底，改成实时材质。 */
    private fun hookForceGlassOnSidebar(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val viewClass = runCatching { cl.loadClass("android.view.View") }.getOrNull() ?: return
        runCatching {
            val setBg = viewClass.getDeclaredMethod("setBackground", android.graphics.drawable.Drawable::class.java)
            module.hook(setBg).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val view = chain.thisObject as? View
                    val incoming = chain.args.getOrNull(0) as? android.graphics.drawable.Drawable
                    if (view == null || !enabled || !liquidGlass ||
                        !isSidebarSolidCard(view, incoming)
                    ) {
                        return chain.proceed()
                    }
                    val result = chain.proceed()
                    clearSolidFill(view)
                    applyGlassMaterial(view)
                    return result
                }
            })
            mounted += "View.setBackground"
        }
        runCatching {
            val setColor = viewClass.getDeclaredMethod("setBackgroundColor", Integer.TYPE)
            module.hook(setColor).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val view = chain.thisObject as? View
                    if (view == null || !enabled || !liquidGlass || !isSidebarGlassHost(view)) {
                        return chain.proceed()
                    }
                    val result = chain.proceed(arrayOf<Any?>(0))
                    applyGlassMaterial(view)
                    return result
                }
            })
            mounted += "View.setBackgroundColor"
        }
        hookContainerAddView(module, cl, mounted)
    }

    private fun hookContainerAddView(
        module: HookEntry,
        cl: ClassLoader,
        mounted: MutableList<String>,
    ) {
        val viewGroup = runCatching { cl.loadClass("android.view.ViewGroup") }.getOrNull() ?: return
        val add = runCatching {
            viewGroup.getDeclaredMethod(
                "addView",
                View::class.java,
                Integer.TYPE,
                android.view.ViewGroup.LayoutParams::class.java,
            )
        }.getOrNull() ?: return
        runCatching {
            module.hook(add).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (!enabled || !liquidGlass) return result
                    val parent = chain.thisObject as? View ?: return result
                    if (!isSidebarGlassHost(parent)) return result
                    applyGlassMaterial(parent)
                    (chain.args.getOrNull(0) as? View)?.let { child ->
                        if (isSidebarSolidCard(child, child.background)) {
                            clearSolidFill(child)
                            applyGlassMaterial(child)
                        }
                    }
                    return result
                }
            })
            mounted += "ViewGroup.addView.glass"
        }
    }

    /** 主磁贴 VivoSuperQSTileBg / VivoEffect 已有 ROM 圆角玻璃，绝不能清背景。 */
    private fun isSidebarGlassHost(view: View?): Boolean {
        var v: View? = view
        var depth = 0
        // 展开卡片大多挂在侧边栏根容器下 6~10 层，保留余量但不扫描整棵视图树。
        while (v != null && depth < 12) {
            if (isSidebarHostName(v.javaClass.simpleName)) return true
            if (cardProcess && isVivoCardSurface(v)) return true
            // vivo.upslide 的实际宿主类名随 ROM 混淆变化，使用稳定的进程包名
            // + 大容器特征兜底；排除手势条和图标等小控件，避免整棵树被套材质。
            if (upslideProcess && isLikelyUpslideSurface(v)) return true
            v = v.parent as? View
            depth++
        }
        return false
    }

    private fun isVivoCardSurface(view: View): Boolean {
        val id = view.id
        if (id == 0 || id == View.NO_ID) return false
        val entry = runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
            ?: return false
        return entry == "allbox_container" ||
            entry == "sidedock_allbox" ||
            entry == "sidedock_allbox_bg" ||
            entry == "scroll_container" ||
            entry == "hotseat_container" ||
            entry == "hotseat_app_area_container" ||
            entry == "hotseat_app_area"
    }

    private fun isLikelyUpslideSurface(view: View): Boolean {
        val name = view.javaClass.simpleName.lowercase(java.util.Locale.ROOT)
        if (name.contains("gesture") || name.contains("icon") ||
            name.contains("image") || name.contains("text") ||
            name.contains("button")) return false
        val lp = view.layoutParams
        val width = when {
            view.width > 0 -> view.width
            lp?.width ?: 0 > 0 -> lp?.width ?: 0
            else -> 0
        }
        val height = when {
            view.height > 0 -> view.height
            lp?.height ?: 0 > 0 -> lp?.height ?: 0
            else -> 0
        }
        return view is android.view.ViewGroup && (width >= 220 || height >= 220)
    }

    private fun isSidebarHostName(simple: String): Boolean {
        val name = simple.lowercase(java.util.Locale.ROOT)
        return name.contains("sidebar") ||
            name.contains("sidepanel") ||
            name.contains("smallwindow") ||
            name.contains("smallapp") ||
            name.contains("quicktool") ||
            name.contains("floatpanel") ||
            name.contains("floatingpanel") ||
            name.contains("miniapp")
    }

    private fun isSidebarSolidCard(view: View, incoming: android.graphics.drawable.Drawable?): Boolean {
        if (!isSidebarGlassHost(view)) return false
        val simple = view.javaClass.simpleName
        if (simple == "VivoSuperQSTileBg" || simple.startsWith("VivoEffect")) return false
        return incoming is android.graphics.drawable.ColorDrawable ||
            view.background is android.graphics.drawable.ColorDrawable
    }

    private fun clearSolidFill(view: View) {
        val bg = view.background as? android.graphics.drawable.ColorDrawable ?: return
        bg.alpha = 0
        runCatching { bg.color = 0x00FFFFFF }
    }

    private fun applyGlassMaterial(view: View) {
        val apply = Runnable {
            val radius = sidebarGlassRadius(BlurScene.SHADE)
            val mask = overlayScaleOf(BlurScene.SHADE).coerceIn(0.12f, 0.45f)
            val ok = invokeCustomized(view, radius, mask) ||
                invokeCustomized10(view, radius, mask)
            if (!ok) diag("glass-apply", "fail cls=${view.javaClass.simpleName}")
        }
        if (view.isAttachedToWindow) {
            runCatching { apply.run() }
            return
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                runCatching { apply.run() }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun invokeCustomized(view: View, radius: Float, mask: Float): Boolean {
        val viewClass = View::class.java
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        val args13 = arrayOf<Any?>(
            radius, 0f, android.graphics.Color.WHITE, mask, 0,
            android.graphics.Color.WHITE, (mask * 0.5f).coerceIn(0f, 1f), 0,
            android.graphics.Color.TRANSPARENT, 0f, 0, 1f, 0,
        )
        val ok13 = runCatching {
            viewClass.getDeclaredMethod(
                "setMaterialCustomized",
                f, f, i, f, i, i, f, i, i, f, i, f, i,
            ).invoke(view, *args13)
        }.isSuccess
        if (ok13) return true
        return runCatching {
            viewClass.getDeclaredMethod(
                "setMaterialCustomized",
                f, f, i, f, i, i, f, i, i, f, i, f, i, i,
            ).invoke(view, *args13, 0)
        }.isSuccess
    }

    private fun invokeCustomized10(view: View, radius: Float, mask: Float): Boolean {
        val f = java.lang.Float.TYPE
        val i = Integer.TYPE
        return runCatching {
            View::class.java.getDeclaredMethod(
                "setMaterialCustomized",
                f, f, i, f, i, i, f, i, f, i,
            ).invoke(
                view,
                radius, 0f, android.graphics.Color.WHITE, mask, 0,
                android.graphics.Color.WHITE, (mask * 0.5f).coerceIn(0f, 1f), 0,
                1f, 0,
            )
        }.isSuccess
    }

    private fun hookFloat(
        module: HookEntry,
        cls: Class<*>,
        name: String,
        transform: (Float, BlurScene) -> Float,
    ): Boolean = runCatching {
        val method = cls.getDeclaredMethod(name, java.lang.Float.TYPE)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                // 通知卡片表面：此处传的是行动画 alpha（VivoNotificationBlurController
                // .updateBackgroundAlpha），缩放会破坏淡入淡出并触发 ROM 的
                // 「<=0.1 禁用模糊/兜底色」门控 → 悬浮/锁屏通知近乎全透明，跳过
                if (isNotificationSurface(chain.thisObject)) return chain.proceed()
                val args = chain.args.toTypedArray()
                if (args.isNotEmpty()) {
                    snapshotMaterial(chain.thisObject as? View, method, args)
                    val before = args[0]
                    (args[0] as? Float)?.let {
                        val scene = classifyScene(chain.thisObject)
                        args[0] = if (liquidGlass && isSidebarGlassHost(chain.thisObject as? View)) {
                            sidebarGlassRadius(scene)
                        } else {
                            transform(it, scene)
                        }
                    }
                    diag("float-$name", "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} v=$before->${args[0]}")
                }
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name: $it")
        false
    }

    /**
     * setMaterialCustomized / setBlurCustomized / setWindowBlurCustomized：
     * 10 参 = (radius, saturation, c1, o1, f1, c2, o2, f2, finalOpacity, finalFusion)
     * 13 参 = 10 参在 finalOpacity 前插入 (c3, o3, f3)，finalOpacity 移到 [11]
     * 14 参（O6 新增）= 13 参 + 末尾 materialPositionType
     */
    private fun hookCustomized(
        module: HookEntry,
        cls: Class<*>,
        name: String,
        argCount: Int,
    ): Boolean = runCatching {
        val base = arrayOf<Class<*>>(
            java.lang.Float.TYPE, java.lang.Float.TYPE, Integer.TYPE,
            java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE,
            java.lang.Float.TYPE, Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
        )
        val params = when (argCount) {
            10 -> base
            13 -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE,
            )
            else -> base.copyOfRange(0, 8) + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE,
            )
        }
        val method = cls.getDeclaredMethod(name, *params)
        val finalOpacityIndex = if (argCount == 10) 8 else 11
        val opacityIndexes = if (argCount == 10) intArrayOf(3, 6) else intArrayOf(3, 6, 9)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                if (isNativeQsGlass(chain.thisObject)) return chain.proceed()
                val notif = isNotificationSurface(chain.thisObject)
                val scene = classifyScene(chain.thisObject)
                val args = chain.args.toTypedArray()
                snapshotMaterial(chain.thisObject as? View, method, args)
                (args[0] as? Float)?.let {
                    args[0] = if (liquidGlass && isSidebarGlassHost(chain.thisObject as? View)) {
                        sidebarGlassRadius(scene)
                    } else {
                        glassRadius(it, scene)
                    }
                }
                (args[1] as? Float)?.let { args[1] = glassSaturation(it) }
                for (i in opacityIndexes) {
                    (args[i] as? Float)?.let { v ->
                        args[i] = maskScale(v, scene, notif)
                    }
                }
                (args[finalOpacityIndex] as? Float)?.let {
                    // 通知卡片 finalOpacity 恒为 1.0，缩放即整卡半透明 → 不缩放
                    args[finalOpacityIndex] = when {
                        notif -> it
                        liquidGlass -> it
                        else -> (it * alphaScaleOf(scene)).coerceIn(0f, 1f)
                    }
                }
                diag(
                    "custom$argCount",
                    "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} scene=$scene notif=$notif " +
                        "r=${args[0]} sat=${args[1]} ops=${opacityIndexes.joinToString(",") { args[it].toString() }} " +
                        "fin=${args[finalOpacityIndex]}",
                )
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name($argCount): $it")
        false
    }

    /**
     * setMaterialMaskFusion(F,I,F,I,I,F,I,F,I)：
     * 对照 framework.jar View.java 与 customized(10)，即去掉 blurRadius 的 customized：
     *   [0]=saturation（10.0/20.0 等，不缩放）
     *   [1]=c1 [2]=o1 [3]=f1 [4]=c2 [5]=o2 [6]=f2 [7]=finalOpacity [8]=finalFusion
     * 遮罩不透明度（[2]/[5]）与最终不透明度（[7]）归入场景「不透明度」。
     * 实机调用点：VivoDynamicSurfaceBlurBg（面板背景遮罩 0.6/0.24 + finalOpacity 1.0，
     * 通知中心/锁屏面板「不透明度」此前无挂点不生效的根因）、
     * BaseRealtimeBlurView/BouncerBlurView（锁屏/解锁面板，finalOpacity 1.0）。
     * 通知卡片无此调用路径（VivoNotificationBlurController 仅用 Force/Customized），
     * 不做温和映射。Bundle 重载极少用，不挂。
     */
    /**
     * setMaterialMaskFusion：
     * 9 参 = (saturation, c1, o1, f1, c2, o2, f2, finalOpacity, finalFusion)
     * 12 参（O6 新增）= 9 参在 finalOpacity 前插入 (c3, o3, f3)
     * 缩放遮罩不透明度：9 参 [2][5][7]，12 参 [2][5][8][10]。
     */
    private fun hookMaskFusion(module: HookEntry, cls: Class<*>, argCount: Int): Boolean = runCatching {
        val base = arrayOf<Class<*>>(
            java.lang.Float.TYPE, Integer.TYPE, java.lang.Float.TYPE,
            Integer.TYPE, Integer.TYPE, java.lang.Float.TYPE,
            Integer.TYPE,
        )
        val params = if (argCount == 9) {
            base + arrayOf<Class<*>>(java.lang.Float.TYPE, Integer.TYPE)
        } else {
            base + arrayOf<Class<*>>(
                Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE,
                java.lang.Float.TYPE, Integer.TYPE,
            )
        }
        val method = cls.getDeclaredMethod("setMaterialMaskFusion", *params)
        val opacityIndexes = if (argCount == 9) intArrayOf(2, 5, 7) else intArrayOf(2, 5, 8, 10)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (!enabled) return chain.proceed()
                if (isNativeQsGlass(chain.thisObject)) return chain.proceed()
                val scene = classifyScene(chain.thisObject)
                val args = chain.args.toTypedArray()
                snapshotMaterial(chain.thisObject as? View, method, args)
                (args[0] as? Float)?.let { args[0] = glassSaturation(it) }
                for (i in opacityIndexes) {
                    (args[i] as? Float)?.let { args[i] = glassMask(it, scene) }
                }
                diag(
                    "maskfusion$argCount",
                    "cls=${(chain.thisObject as? View)?.javaClass?.simpleName} scene=$scene " +
                        "sat=${args[0]} ops=${opacityIndexes.joinToString(",") { args[it].toString() }}",
                )
                return chain.proceed(args)
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip View.setMaterialMaskFusion($argCount): $it")
        false
    }

    /** setMaterial/setMaterialForce：token 型材质入口，记录接收者场景（O6 主入口为三参） */
    private fun hookMaterialTagger(module: HookEntry, cls: Class<*>, name: String, argCount: Int): Boolean = runCatching {
        val params = Array(argCount) { Integer.TYPE }
        val method = cls.getDeclaredMethod(name, *params)
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val scene = if (enabled) classifyScene(chain.thisObject) else null
                if (scene != null) threadScene.set(scene)
                return try {
                    chain.proceed()
                } finally {
                    threadScene.remove()
                }
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip ${cls.name}.$name: $it")
        false
    }

    // ---------- 材质 token 解析（BlurMaterial 76 常量） ----------

    private fun hookBlurMaterialTokens(module: HookEntry, cl: ClassLoader): Boolean = runCatching {
        val geometryClass = cl.loadClass("android.view.blur.IVivoBlurRender\$BlurGeometry")
        val blurMaterial = cl.loadClass("android.view.blur.BlurMaterial")
        val resolve: Method = blurMaterial.getDeclaredMethod(
            "resolveBlurGeometryByStyle", geometryClass, Integer.TYPE
        )
        module.hook(resolve).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (enabled) {
                    // setMaterial(II)/setMaterialForce(II) 调用栈内携带场景；
                    // 栈外解析（无法归因）回落「其他表面」
                    val scene = threadScene.get() ?: BlurScene.OTHER
                    val geometry = chain.args.getOrNull(0) ?: return result
                    diag("token", "scene=$scene before.r=${runCatching { geometry.javaClass.getField("mBlurRadius").getFloat(geometry) }.getOrNull()}")
                    scaleFloatField(geometry, "mBlurRadius", radiusScaleOf(scene), clamp01 = false)
                    if (liquidGlass) {
                        runCatching {
                            val field = geometry.javaClass.getField("mBlurRadius")
                            val v = field.getFloat(geometry)
                            val min = 0.22f * radiusScaleOf(scene)
                            if (v < 0.08f && min > 0f) field.setFloat(geometry, min)
                        }
                    }
                    scaleFloatField(geometry, "mMaskOpacity_1", overlayScaleOf(scene), clamp01 = true)
                    scaleFloatField(geometry, "mMaskOpacity_2", overlayScaleOf(scene), clamp01 = true)
                    // O6 新增第三遮罩层不透明度（BlurGeometry.mMaskOpacity_3）
                    scaleFloatField(geometry, "mMaskOpacity_3", overlayScaleOf(scene), clamp01 = true)
                    if (liquidGlass) {
                        floorMaskField(geometry, "mMaskOpacity_1")
                        floorMaskField(geometry, "mMaskOpacity_2")
                        floorMaskField(geometry, "mMaskOpacity_3")
                    }
                    if (!liquidGlass) {
                        scaleFloatField(geometry, "mFinalOpacity", alphaScaleOf(scene), clamp01 = true)
                    }
                }
                return result
            }
        })
        true
    }.getOrElse {
        android.util.Log.i(TAG, "skip BlurMaterial token hook: $it")
        false
    }

    private fun scaleFloatField(target: Any, name: String, scale: Float, clamp01: Boolean) {
        runCatching {
            val field = synchronized(geometryFields) {
                if (geometryFields.containsKey(name)) geometryFields[name]
                else runCatching { target.javaClass.getField(name) }.getOrNull()
                    .also { geometryFields[name] = it }
            } ?: return
            val v = field.getFloat(target)
            val scaled = v * scale
            field.setFloat(target, if (clamp01) scaled.coerceIn(0f, 1f) else scaled)
        }
    }

    private fun floorMaskField(target: Any, name: String) {
        runCatching {
            val field = synchronized(geometryFields) { geometryFields[name] }
                ?: target.javaClass.getField(name)
            val v = field.getFloat(target)
            if (v > 0f && v < 0.12f) field.setFloat(target, 0.12f)
        }
    }

    // ---------- SystemUI 私有 Drawable 模糊栈（O6：字段直写） ----------

    /** 每实例原始值（mBlurRadius 与 Paint alpha），保证幂等重放 */
    private data class DrawableOriginal(
        val blurRadius: Int,
        val paintAlphas: Map<String, Int>,
    )

    private val drawableOriginals =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, DrawableOriginal>())
    private val aggregatorRef = java.util.concurrent.atomic.AtomicReference<Any?>()

    /** VivoBackgroundBlurDrawable 的三个 Paint（public final，alpha 可变） */
    private val PAINT_FIELDS = arrayOf("mPaint", "mDisableBlurPaint", "mFallbackPaint")

    /**
     * O6 Drawable 栈：VivoBackgroundBlurDrawable 的 setBlurRadius(int)/setColor/setFallbackColor
     * 已删除，改由 BlurDrawableController.getDrawable(Context) 创建时直接写字段
     * （mBlurRadius=300、mPaint/mDisableBlurPaint 取资源色）。每次 getDrawable 都是新建
     * drawable → after-hook 缩放即幂等。窗口级遮罩无视图层级可归类 → 「通知中心」子项。
     */
    private fun hookBlurDrawableStack(module: HookEntry, cl: ClassLoader, mounted: MutableList<String>) {
        val controller = runCatching { cl.loadClass("com.vivo.blur.BlurDrawableController") }
            .getOrElse {
                android.util.Log.i(TAG, "skip drawable stack: $it")
                return
            }
        val getDrawable = runCatching {
            controller.getDeclaredMethod("getDrawable", android.content.Context::class.java)
        }.getOrElse {
            android.util.Log.i(TAG, "skip BlurDrawableController.getDrawable: $it")
            return
        }
        module.hook(getDrawable).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (enabled && result != null) {
                    scaleDrawable(result)
                    aggregatorRef.set(
                        runCatching { controller.getField("blurRegionAggregator").get(null) }.getOrNull()
                    )
                }
                return result
            }
        })
        mounted += "Drawable.getDrawable"
    }

    /** 配置变更时对 Aggregator 内已创建的 drawable 重放缩放（幂等） */
    private fun reapplyDrawableStack() {
        if (!enabled) return
        val aggregator = aggregatorRef.get() ?: return
        runCatching {
            val drawables =
                aggregator.javaClass.getField("mDrawables").get(aggregator) as? Collection<*> ?: return
            for (d in drawables) if (d != null) scaleDrawable(d)
        }
    }

    /** 缩放单个 drawable：mBlurRadius × 半径缩放；Paint alpha × 不透明度缩放 */
    private fun scaleDrawable(drawable: Any) {
        if (!liquidGlass &&
            radiusScaleOf(BlurScene.SHADE) == 1f &&
            alphaScaleOf(BlurScene.SHADE) == 1f
        ) return
        val cls = drawable.javaClass
        val originals = drawableOriginals.getOrPut(drawable) {
            DrawableOriginal(
                blurRadius = runCatching { cls.getField("mBlurRadius").getInt(drawable) }.getOrDefault(0),
                paintAlphas = PAINT_FIELDS.associateWith { name ->
                    runCatching {
                        (cls.getField(name).get(drawable) as? android.graphics.Paint)?.alpha ?: -1
                    }.getOrDefault(-1)
                },
            )
        }
        runCatching {
            val newR = (originals.blurRadius * radiusScaleOf(BlurScene.SHADE)).toInt().coerceAtLeast(0)
            cls.getField("mBlurRadius").setInt(drawable, newR)
            diag("drawable", "cls=${cls.simpleName} r=${originals.blurRadius}->$newR")
        }
        val overlay = overlayScaleOf(BlurScene.SHADE)
        for ((name, origAlpha) in originals.paintAlphas) {
            if (origAlpha < 0) continue
            runCatching {
                val paint = cls.getField(name).get(drawable) as? android.graphics.Paint ?: return@runCatching
                paint.alpha = (origAlpha * overlay).toInt().coerceIn(0, 255)
            }
        }
    }
}
